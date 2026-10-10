# 15. Concurrence et verrous

[Sommaire](README.md) | [< 14. Transactions](14-transactions.md) | [16. Événements applicatifs et `@Async` >](16-evenements-async.md)

## Contexte

- **Problème** : deux requêtes (ou deux instances) travaillent sur les mêmes lignes au même moment. Un compteur perd un incrément, une limite est dépassée, une modification est écrasée, un job tourne deux fois.
- **Quand l'utiliser** : tout « lire, décider, écrire » sur des données partagées, et toute file de travail partagée par plusieurs threads ou instances.
- **Quand s'en passer** : une donnée que seule une requête touche (ses propres lignes neuves).
- **Dans ce dépôt** : `@Version` sur les tâches, un verrou sur la ligne du compte pour compter les exports et les webhooks, `SKIP LOCKED` pour l'outbox et les réessais de webhooks, un `UPDATE` conditionnel pour réserver un export, une contrainte unique derrière chaque contrôle d'existence.
- **Prérequis** : guides 14 (transactions) et 13 (SQL). Versions : Spring Boot 4.1, Hibernate 7.4, PostgreSQL 18.

Les outils, du plus léger au plus lourd :

| Outil | Protège contre | Coût |
|---|---|---|
| Une seule instruction atomique | compteur perdu, insertion en double | aucun, si le SQL sait l'écrire (guide 13) |
| Contrainte unique | doublon, en dernier recours | une erreur à traduire en 409 |
| Verrou optimiste `@Version` | écrasement d'une modification concurrente | le perdant reçoit 409 et recommence |
| Verrou de ligne `FOR UPDATE` | décision prise sur une lecture périmée | les autres attendent la fin de la transaction |
| `FOR UPDATE SKIP LOCKED` | deux consommateurs sur la même ligne | aucune attente, mais pas d'ordre strict |
| `UPDATE` conditionnel | deux exécutions du même travail | une colonne d'état et un bail |

## Concepts clés

### Vocabulaire

- **Accès concurrent** : deux transactions qui tournent en même temps sur les mêmes lignes.
- **Mise à jour perdue** : une écriture écrasée par une autre, partie d'une valeur plus ancienne.
- **Verrou optimiste** : aucun verrou. Un numéro de version révèle, à l'écriture, que quelqu'un a écrit avant.
- **Verrou de ligne** (pessimiste) : `SELECT ... FOR UPDATE` bloque la ligne jusqu'à la fin de la transaction, et les autres attendent.
- **Bail** (lease) : une date jusqu'à laquelle un travail réservé appartient à celui qui l'a pris. Au-delà, un autre peut le reprendre.
- **Interblocage** (deadlock) : deux transactions attendent chacune un verrou que l'autre tient. Postgres en annule une.

### Lire, décider, écrire : la fenêtre

Dans l'isolation par défaut de Postgres (`READ COMMITTED`), chaque instruction voit ce qui était validé avant son départ. Deux transactions peuvent donc lire la même valeur, décider, puis écrire toutes les deux. Tout contrôle en Java (« moins de 5 », « email libre ») suivi d'une écriture a cette fenêtre.

```
 requête A                          requête B
 SELECT count(*) --> 4
                                    SELECT count(*) --> 4
 4 < 5 : INSERT
                                    4 < 5 : INSERT
 COMMIT                             COMMIT
                 --> 6 clés pour une limite de 5
```

Trois façons de la fermer : faire décider la base en une instruction, verrouiller ce que la décision lit, ou détecter le conflit à l'écriture.

### Optimiste ou pessimiste

- Optimiste (`@Version`) : adapté aux conflits rares. Le second écrivain échoue et la requête répond 409.
- Pessimiste (`FOR UPDATE`) : adapté aux conflits probables, comme une limite par propriétaire. La seconde transaction attend le commit de la première.

Le même conflit, traité des deux façons :

| | `@Version` | `findByIdForUpdate` |
|---|---|---|
| deuxième requête | échoue au commit | attend le commit de la première |
| réponse | 409, le client relit et recommence | 200 ou 422, sur une valeur juste |
| durée de verrou | aucune | jusqu'à la fin de la transaction |

### Partager un stock de travail : `SKIP LOCKED` et bail

Plusieurs pollers prennent des lignes à traiter. `FOR UPDATE` seul les fait s'attendre. Avec `SKIP LOCKED`, chacun prend les lignes que les autres n'ont pas verrouillées.

Le verrou de ligne s'arrête au commit. Pour garder une ligne réservée pendant un traitement hors transaction, on avance une date dans la même instruction : c'est le bail. Si le worker plante, la ligne redevient due à la fin du bail.

```
 print_jobs : 1 2 3 4 5 6   (dus)

 instance A : UPDATE ... WHERE id IN (SELECT ... LIMIT 3 FOR UPDATE SKIP LOCKED)
              --> 1 2 3, next_attempt_at = maintenant + 5 min
 instance B : même requête au même moment
              --> 4 5 6 (1 2 3 sont verrouillées : sautées, pas attendues)

 A plante pendant le travail
 5 min plus tard : 1 2 3 redeviennent dues --> reprises par n'importe qui
```

Le SQL est au guide 13 (étape 5). La livraison est au moins une fois, donc le traitement tolère un doublon.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  invoice/
    Invoice.java                  le numéro de version (étape 1)
  shared/
    ApiExceptionHandler.java      409 pour les conflits (étapes 2 et 3)
  customer/
    CustomerRepository.java       le verrou de ligne (étape 4)
    ApiKeyService.java            la limite par client (étapes 4 et 6)
  export/
    ExportRepository.java         la réservation par UPDATE (étape 5)
    ExportRunner.java             l'utilise (étape 5)
src/test/java/.../
  ApiKeyLimitTests.java           la vérification (étape 7)
```

### Deux requêtes sur une instance

**Étape 1. Numéroter les versions.** Hibernate ajoute la version au `WHERE` de son `UPDATE`. Zéro ligne modifiée veut dire que quelqu'un a écrit avant.

```java
// dans Invoice (mapping : guide 12)
@Version
@Column(nullable = false)
private long version;
```

**Étape 2. Répondre 409 au conflit.** L'exception arrive au commit, après le retour de la méthode du service. Le gestionnaire global la transforme en problème (guide 05).

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleVersionConflict(OptimisticLockingFailureException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Another request changed this resource at the same time: reload it and try again.");
    }
}
```

**Étape 3. Laisser la contrainte unique trancher en dernier.** Un contrôle d'existence en Java laisse une fenêtre. La contrainte la ferme, sans laisser fuiter du SQL.

```java
// dans ApiExceptionHandler
@ExceptionHandler(DataIntegrityViolationException.class)
public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
}
```

**Étape 4. Sérialiser les décisions d'un même propriétaire.** En verrouillant la ligne du propriétaire, deux requêtes simultanées se comptent l'une l'autre. La seconde lit le total après le commit de la première.

```java
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** SELECT ... FOR UPDATE : la ligne reste verrouillée jusqu'à la fin de la transaction */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") UUID id);
}
```

```java
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final int MAX_KEYS = 5;

    private final CustomerRepository customerRepository;
    private final ApiKeyRepository apiKeyRepository;     // un JpaRepository<ApiKey, UUID>

    @Transactional
    public ApiKey create(UUID customerId, String label) {
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        if (apiKeyRepository.countByCustomerId(customerId) >= MAX_KEYS) {
            throw new ApiKeyLimitReachedException(MAX_KEYS);
        }
        return apiKeyRepository.save(new ApiKey(customer, label));
    }
}
```

`Customer`, `ApiKey` et les exceptions sont des types d'exemple.

### Plusieurs instances et travail partagé

**Étape 5. Réserver un travail par un `UPDATE` conditionnel.** Un message en double trouve la ligne déjà prise. La base décide en une instruction, et le nombre de lignes renvoyé dit qui a gagné.

```java
public interface ExportRepository extends JpaRepository<Export, UUID> {

    /** @return 1 si cette instance a pris l'export, 0 sinon */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Export e
            SET e.status = :running, e.leaseUntil = :leaseUntil, e.attempts = e.attempts + 1
            WHERE e.id = :id
              AND (e.status = :queued OR (e.status = :running AND e.leaseUntil < :now))
            """)
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil,
              @Param("queued") ExportStatus queued, @Param("running") ExportStatus running);
}
```

```java
// dans ExportRunner, qui reçoit le message "export demandé" ; transactions : un TransactionTemplate
Instant now = clock.instant();
Integer claimed = transactions.execute(status ->
        exportRepository.claim(exportId, now, now.plus(Duration.ofMinutes(30)),
                ExportStatus.QUEUED, ExportStatus.RUNNING));
if (claimed == null || claimed == 0) {
    return;                                  // déjà pris ailleurs, ou terminé : rien à faire
}
// ... le travail long, hors transaction
```

**Étape 6. Verrouiller toujours dans le même ordre.** Deux transactions qui verrouillent les mêmes lignes dans l'ordre inverse s'attendent pour toujours, et Postgres en annule une.

```java
// dans ApiKeyService : toute modification des clés d'un client verrouille d'abord le client
@Transactional
public void revoke(UUID customerId, UUID apiKeyId) {
    customerRepository.findByIdForUpdate(customerId)       // 1. le client, comme create()
            .orElseThrow(() -> new CustomerNotFoundException(customerId));
    apiKeyRepository.deleteById(apiKeyId);                  // 2. puis ses clés
}
```

**Étape 7. Vérifier.** Seules de vraies transactions concurrentes prouvent que la limite tient. Le test tourne hors d'une transaction de test, pour que chaque appel valide (guide 29).

```java
@SpringBootTest
class ApiKeyLimitTests {

    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired TestData testData;                     // crée un client (à écrire)

    @Test
    void concurrentCreationsNeverExceedTheLimit() throws Exception {
        UUID customerId = testData.customer();
        List<Future<?>> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++) {
                attempts.add(executor.submit(() -> apiKeyService.create(customerId, "key")));
            }
        }                                              // close() attend la fin des 10 essais

        assertThat(apiKeyRepository.countByCustomerId(customerId)).isEqualTo(5);
    }
}
```

Sans `findByIdForUpdate`, ce test échoue de temps en temps.

### Référence rapide

Les modes de verrou sur une méthode de repository :

| Annotation | SQL sur Postgres (Hibernate 7) | Usage |
|---|---|---|
| `@Lock(PESSIMISTIC_WRITE)` | `FOR NO KEY UPDATE` | décider puis écrire, une ligne à la fois |
| `nativeQuery` + `FOR UPDATE SKIP LOCKED` | tel quel | partager un stock de lignes |

Les exceptions de conflit :

| Exception | Quand | Réponse dans ce dépôt |
|---|---|---|
| `OptimisticLockingFailureException` | version changée entre lecture et écriture | 409 `version-conflict` |
| `DataIntegrityViolationException` | contrainte unique ou clé étrangère violée | 409 sans type |
| `PessimisticLockingFailureException` | interblocage, délai de verrou | 500 (aucun gestionnaire dédié) |

Limites de cette version : la version n'est contrôlée qu'entre deux transactions du serveur. Un client qui édite une page longtemps peut écraser une modification faite entre-temps, sauf s'il renvoie la version (`ETag` / `If-Match`). Ce dépôt ne le fait pas.

## Concepts avancés

### Sous le capot : le verrou optimiste

Hibernate écrit `UPDATE invoices SET ..., version = 8 WHERE id = ? AND version = 7`. Si zéro ligne est modifiée, il lève une exception que Spring traduit en `ObjectOptimisticLockingFailureException` (une sous-classe de `OptimisticLockingFailureException`).

Avec le dirty checking, l'`UPDATE` part au flush, le plus souvent au commit. L'exception sort donc du proxy Spring après le corps de la méthode, et un `try` autour du setter n'attrape rien.

```
 A : lit version 7          B : lit version 7
 A : UPDATE ... WHERE version = 7  --> 1 ligne, version 8, COMMIT
                            B : UPDATE ... WHERE version = 7  --> 0 ligne
                            B : ObjectOptimisticLockingFailureException --> 409
```

### Sous le capot : ce que Hibernate 7 verrouille

- Sur Postgres, `PESSIMISTIC_WRITE` devient `FOR NO KEY UPDATE`. Il bloque les autres écrivains et les `FOR UPDATE`, mais pas le `FOR KEY SHARE` que prend la vérification d'une clé étrangère. On peut donc encore insérer des lignes qui référencent la ligne verrouillée.
- Hibernate 7 ne verrouille que les lignes de l'entité interrogée (portée `ROOT_ONLY`, `PessimisticLockScope.NORMAL` en JPA). Les lignes jointes par un fetch ne sont pas verrouillées. Verrouiller toutes les tables chargées était l'ancien comportement (`INCLUDE_FETCHES`).

### Interblocages

Postgres détecte l'interblocage et annule une des deux transactions avec l'état SQL `40P01` (« deadlock detected »). Hibernate le traduit en `LockAcquisitionException`, qui remonte côté Spring dans la famille de `PessimisticLockingFailureException`.

Pour l'éviter, toute transaction qui touche un groupe de lignes verrouille d'abord la même ligne de tête (le propriétaire, le compte) :

```
 sans ordre commun                       avec un premier verrou commun
 A : verrou ligne média                  A : verrou utilisateur
 B : verrou ligne utilisateur            B : attend l'utilisateur
 A : attend utilisateur (tenu par B)     A : média, utilisateur... COMMIT
 B : attend média (tenu par A)           B : reprend, lit l'état validé
 --> 40P01, une transaction annulée
```

### Plusieurs instances

Les outils de ce guide vivent dans la base, donc ils tiennent d'une instance à l'autre. Un bloc `synchronized` ou un `ReentrantLock` ne protège qu'une JVM. Pour un travail « une fois par tour », on prend un verrou de planificateur (ShedLock, guide 21).

## Pièges à éviter

### Sur une instance

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Vérifier en Java puis écrire | deux requêtes passent le test en même temps | Une instruction atomique, ou un verrou sur la ligne lue |
| Compter sans verrouiller le propriétaire | la limite est dépassée par deux créations simultanées | Verrouiller la ligne du propriétaire d'abord |
| Attraper le conflit de version dans le service | il part au commit, après la méthode | Le traduire en 409 dans le gestionnaire global |
| Compter sur la vérification d'existence seule | une insertion concurrente passe entre les deux | Garder la contrainte unique et traduire l'erreur en 409 |
| Renvoyer le message SQL de la contrainte | noms de tables et de contraintes exposés | Répondre un détail générique |
| Verrouiller dans un ordre différent selon le chemin | interblocage, une transaction annulée | Verrouiller d'abord la même ligne de tête partout |
| Croire qu'un `JOIN FETCH` verrouille les lignes jointes | seule l'entité racine est verrouillée | Verrouiller chaque ligne par sa propre requête |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Protéger avec `synchronized` | chaque instance a son propre verrou | Verrouiller en base |
| Réserver un lot avec `FOR UPDATE` sans `SKIP LOCKED` | les pollers s'attendent les uns les autres | Ajouter `SKIP LOCKED` |
| Réserver sans bail | la ligne redevient libre au commit, avant la fin du travail | Repousser une date dans le même `UPDATE` |
| Lire l'état puis le changer pour réserver | deux instances lisent `QUEUED` et lancent le travail | Un `UPDATE` conditionnel qui renvoie le nombre de lignes |
| Supposer un seul traitement | un message en double ou un bail expiré relance le travail | Rendre le traitement idempotent |

## En résumé

**À retenir :** tout « lire, décider, écrire » sur des données partagées a une fenêtre, qu'on ferme dans la base. On utilise une instruction atomique quand le SQL le permet, un verrou sur la ligne du propriétaire quand la décision lit plusieurs lignes, `@Version` pour détecter un écrasement, et `SKIP LOCKED` avec un bail pour partager le travail. Une contrainte unique reste le dernier recours, toujours traduite en 409.

- [ ] Aucune décision prise sur une lecture sans verrou ni contrainte derrière
- [ ] `@Version` sur les entités modifiées par plusieurs requêtes, conflit en 409
- [ ] Contrainte unique derrière chaque contrôle d'existence, `DataIntegrityViolationException` en 409
- [ ] Ligne du propriétaire verrouillée avant de compter ses éléments
- [ ] Même ordre de verrouillage sur tous les chemins
- [ ] `FOR UPDATE SKIP LOCKED` et un bail pour les stocks de travail
- [ ] Réservation d'un travail par `UPDATE` conditionnel qui renvoie le nombre de lignes
- [ ] Traitements idempotents : livraison au moins une fois
- [ ] Un test à transactions concurrentes pour chaque limite

## Dans ce dépôt

Deux exports demandés en même temps par le même compte :

```
 POST /exports/tasks (A)               POST /exports/tasks (B)
        |                                      |
        v                                      v
 findByIdForUpdate(owner)              findByIdForUpdate(owner) : attend A
 existsBy...StatusIn(ACTIVE) : non
 INSERT data_exports (QUEUED)
 outbox.enqueue(export.run)
 COMMIT --------------------------------> verrou rendu
                                       existsBy...StatusIn(ACTIVE) : oui
                                       --> 409 export-in-progress
```

| Fichier | Rôle |
|---|---|
| [`Task`][task] | `@Version` : deux modifications simultanées d'une tâche, la seconde reçoit 409 |
| [`ApiExceptionHandler`][handler] | version changée en 409 `version-conflict`, contrainte violée en 409 générique |
| [`UserRepository`][user-repo] | `findByIdForUpdate` : `@Lock(PESSIMISTIC_WRITE)`, ignore les comptes supprimés |
| [`DataExportService`][export-service] | `request` verrouille le propriétaire : un export de chaque type en cours par compte |
| [`WebhookEndpointService`][endpoint-service] | `create` verrouille le compte : 5 points de terminaison au plus |
| [`AvatarService`][avatar] | la requête d'upload et le worker verrouillent tous deux l'utilisateur d'abord |
| [`DataExportRepository`][export-repo] | `claim` : `UPDATE` conditionnel `QUEUED` ou bail expiré vers `RUNNING` |
| [`WebhookDeliveryRepository`][delivery-repo] | `findForUpdateById` : verrouille la livraison seule (table racine) |
| [`WebhookDeliveryService`][delivery-service] | `record` : verrou de la livraison, puis de son point de terminaison |
| [`OutboxMessageRepository`][outbox-repo] | `lockUnpublished`, `lockDue` : `FOR UPDATE SKIP LOCKED` en `@Query` native |

Les réponses de conflit :

| Situation | Statut | Type |
|---|---|---|
| version changée (`Task`) | 409 | `version-conflict`, titre « Changed by another request » |
| contrainte violée (deux inscriptions simultanées) | 409 | `about:blank`, détail « The request conflicts with existing data » |
| export du même type déjà en cours | 409 | `export-in-progress` |
| plus de 5 points de terminaison webhook | 422 | `webhook-limit-reached` |

Ce qui est propre au dépôt :

- `findByIdForUpdate` sur la ligne du compte est le premier verrou commun de `UserService`, `EmailVerificationService`, `PasswordResetService`, `DataExportService.request`, `WebhookEndpointService.create` et `AvatarService`. Il ignore les comptes supprimés (JPQL).
- L'upload d'avatar et le worker se bloquaient l'un l'autre (`40P01`) jusqu'à ce que les deux prennent d'abord le verrou de la ligne utilisateur. Des colonnes différentes de la même ligne s'écrasaient aussi, jusqu'à `@DynamicUpdate` (guide 12).
- `WebhookDeliveryService.record` verrouille la livraison, puis le point de terminaison explicitement, car Hibernate 7 ne le verrouille pas à travers la jointure. Sans cela, deux échecs enregistrés ensemble le désactivaient tous deux et prévenaient son propriétaire deux fois.
- `DataExportRepository.claim` utilise `@Modifying(flushAutomatically = true, clearAutomatically = true)`. Un message en double trouve l'export `RUNNING` sous un bail vivant et ne fait rien. `DataExportRecoveryJob` remet en file les exports dont le bail est expiré (guide 22).
- Deux pollers sans ShedLock partagent leurs lignes avec `SKIP LOCKED` : `OutboxRelayJob` (par `OutboxMessageRepository`) et `WebhookRetryJob` (par `WebhookDeliveryQueries.claimDue`, avec le bail `webhooks.delivery-lease`). Voir les guides 20 et 21.
- `RateLimitQueries` (compteur) et `TaskReminderQueries` (chaque rappel une fois) remplacent les verrous par des instructions atomiques (guide 13).

Les tests :

- [`UserRepositoryTests`][user-repo-tests] : `findByIdForUpdateLocksTheRow` (le SQL correspond à `for (no key )?update`).
- [`OutboxTests`][outbox-tests] : `lockDueSkipsTheRowsLockedByAnotherTransactionAndFillsTheBatchWithTheNextOnes` (une ligne verrouillée par une autre connexion JDBC est sautée).
- [`DataExportRepositoryTests`][export-repo-tests] : `claim` prend un export en attente, laisse celui d'un bail vivant, reprend celui d'un bail expiré.
- [`TaskControllerWebMvcTests`][task-web-tests] : `updateLosingAConcurrentModificationReturnsConflict` et `referenceTakenByAConcurrentCreationReturnsAnUntypedConflict`.

Pour ajouter une limite par compte : `userRepository.findByIdForUpdate`, compter, puis insérer, dans une seule méthode `@Transactional`. Tout autre chemin qui change ces lignes verrouille d'abord le compte.

## Voir aussi

- [13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) : le SQL du compteur atomique, de l'insertion unique et de la réservation `SKIP LOCKED`.
- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : le mapping de `@Version` et de `@DynamicUpdate`.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : `SKIP LOCKED` entre la publication immédiate et le poller.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : un seul passage par tour avec plusieurs instances.
- Documentation officielle : [verrous explicites dans PostgreSQL](https://www.postgresql.org/docs/current/explicit-locking.html), [niveaux d'isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

[Sommaire](README.md) | [< 14. Transactions](14-transactions.md) | [16. Événements applicatifs et `@Async` >](16-evenements-async.md)

[task]: ../../src/main/java/io/julienmetral/tasks/task/entities/Task.java
[handler]: ../../src/main/java/io/julienmetral/tasks/shared/exceptions/ApiExceptionHandler.java
[user-repo]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/UserRepository.java
[export-service]: ../../src/main/java/io/julienmetral/tasks/export/services/DataExportService.java
[endpoint-service]: ../../src/main/java/io/julienmetral/tasks/notification/services/WebhookEndpointService.java
[avatar]: ../../src/main/java/io/julienmetral/tasks/identity/services/AvatarService.java
[export-repo]: ../../src/main/java/io/julienmetral/tasks/export/repositories/DataExportRepository.java
[delivery-repo]: ../../src/main/java/io/julienmetral/tasks/notification/repositories/WebhookDeliveryRepository.java
[delivery-service]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[outbox-repo]: ../../src/main/java/io/julienmetral/tasks/messaging/repositories/OutboxMessageRepository.java
[user-repo-tests]: ../../src/test/java/io/julienmetral/tasks/identity/repositories/UserRepositoryTests.java
[export-repo-tests]: ../../src/test/java/io/julienmetral/tasks/export/repositories/DataExportRepositoryTests.java
[outbox-tests]: ../../src/test/java/io/julienmetral/tasks/messaging/OutboxTests.java
[task-web-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskControllerWebMvcTests.java
