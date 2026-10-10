# 14. Transactions

[Sommaire](README.md) | [< 13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) | [15. Concurrence et verrous >](15-concurrence-verrous.md)

## Contexte

- **Problème** : plusieurs écritures doivent réussir ou échouer ensemble, et certains travaux ne doivent se faire qu'une fois la transaction validée (un fichier supprimé, un message envoyé), ou doivent survivre à un échec (un essai compté).
- **Quand l'utiliser** : chaque méthode de service qui écrit, et chaque lecture qui demande une vue cohérente ou un chargement paresseux.
- **Quand s'en passer** : un appel externe lent (HTTP, SMTP, envoi S3) : il ne doit pas tenir une connexion et des verrous de lignes ; on découpe autour de lui.
- **Dans ce dépôt** : les services portent les transactions et les contrôleurs n'en ont pas (guide 11) ; l'historique et l'outbox exigent la transaction de l'appelant (`MANDATORY`) ; les comptages du rate limiting valident seuls (`REQUIRES_NEW`) ; les appels de webhook tournent entre deux transactions courtes.
- **Prérequis** : guide 11. Versions : Spring Boot 4.1, Spring Framework 7, Hibernate 7.4, PostgreSQL 18.

Les deux façons de délimiter une transaction :

| Forme | Exemple | Quand la choisir |
|---|---|---|
| Déclarative | `@Transactional` sur une méthode publique d'un bean | presque toujours |
| Programmatique | `TransactionTemplate.execute(status -> ...)` | plusieurs transactions dans une méthode, ou autour d'un appel lent |

## Concepts clés

### Vocabulaire

- **Transaction** : un groupe d'instructions SQL validées ensemble, ou pas du tout.
- **Propagation** : ce que fait une méthode transactionnelle appelée alors qu'une transaction tourne déjà (la rejoindre, refuser, en ouvrir une autre).
- **Rollback** : l'annulation de tout ce que la transaction a écrit.
- **Synchronisation** : un rappel enregistré sur la transaction en cours, exécuté avant ou après son commit ou son rollback.
- **Proxy Spring** : ici, l'objet qui ouvre et ferme la transaction autour de l'appel (voir le glossaire).

### Le proxy Spring

`@Transactional` agit par le proxy Spring placé devant le bean : seul un appel qui passe par lui, depuis un autre bean, ouvre une transaction. Un `this.methode()` dans la classe le contourne, et l'annotation de la méthode appelée est ignorée. Une méthode `private` n'est jamais interceptée, car le proxy ne peut pas la redéfinir.

```
 InvoiceController
       |
       v
 +----------------+  begin  +---------------------------+
 |  proxy Spring  | ------> |  InvoiceService           |
 |  transaction   | <------ |    pay()                  |
 +----------------+  commit |      |                    |
                            |      +--> this.log()      |
                            |           @Transactional  |
                            |           ignoré : pas    |
                            |           de proxy        |
                            +---------------------------+
```

### La propagation

Ce que fait une méthode appelée pendant une transaction :

| Propagation | Transaction en cours | Aucune transaction |
|---|---|---|
| `REQUIRED` (défaut) | la rejoint | en ouvre une |
| `MANDATORY` | la rejoint | refuse : `IllegalTransactionStateException` |
| `REQUIRES_NEW` | la suspend, en ouvre une autre, la valide à part | en ouvre une |
| `NOT_SUPPORTED` | la suspend, s'exécute sans | s'exécute sans |

`MANDATORY` sans transaction lève : « No existing transaction found for transaction marked with propagation 'mandatory' ».

Avec `REQUIRES_NEW`, le comptage est validé tout de suite, et l'échec de l'appelant ne l'annule pas :

```
 login()                          transaction A (connexion 1)
   |
   +--> rateLimiter.count()       transaction B (connexion 2)
   |        INSERT ... count + 1
   |        COMMIT B              <-- validé tout de suite
   |
   +--> mot de passe faux --> exception
   v
 ROLLBACK A                       le comptage reste
```

Pendant que B tourne, deux connexions du pool sont tenues.

### Quand une exception annule

Par défaut, une `RuntimeException` ou une `Error` qui sort du proxy annule la transaction ; une exception vérifiée la valide. `rollbackFor` ajoute des types, `noRollbackFor` en retire.

Le piège : quand une méthode `REQUIRED` interne lève par son propre proxy, elle marque la transaction partagée « rollback-only ». La méthode externe peut attraper l'exception : au commit, Spring annule quand même et lève `UnexpectedRollbackException` (« Transaction silently rolled back because it has been marked as rollback-only »). `noRollbackFor` doit donc figurer sur chaque méthode transactionnelle que l'exception traverse, pas seulement celle qui lève.

```
 refresh()   @Transactional(noRollbackFor = InvalidTokenException)
    |
    +--> rotate()   @Transactional(noRollbackFor = InvalidTokenException)
    |       revokeFamily()      UPDATE ... revoked_at = now
    |       throw InvalidTokenException
    |    <-- traverse le proxy de rotate : pas de marque rollback-only
    |
    <-- traverse le proxy de refresh : COMMIT, la révocation reste
```

### Après le commit

Certaines actions ne s'annulent pas : supprimer un fichier, publier un message, envoyer un email. Faites dans la transaction, elles ont lieu même si elle est annulée. `TransactionSynchronizationManager.registerSynchronization(...)` enregistre un rappel :

- `afterCommit()` ne s'exécute qu'après un commit réussi.
- `afterCompletion(status)` s'exécute après le commit ou le rollback, avec `STATUS_COMMITTED`, `STATUS_ROLLED_BACK` ou `STATUS_UNKNOWN`.

Ce qui reste lié au thread pendant `afterCommit` : guide 20.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../invoice/
  InvoiceService.java        transactions, appel lent (étapes 1, 4, 5)
  InvoiceHistory.java        écrit dans la transaction de l'appelant (étape 2)
  PaymentAttempts.java       compte les essais à part (étape 3)
  InvoiceFiles.java          supprime un fichier après le commit (étape 6)
src/test/java/.../
  InvoiceTransactionTests.java    la vérification (étape 7)
```

Spring Boot active les transactions par annotation et fournit un bean `TransactionTemplate` (`TransactionAutoConfiguration`) : aucun `@EnableTransactionManagement` à écrire.

### Des transactions sur les services

**Étape 1. Placer la transaction sur le service.** La méthode de service est l'unité de travail ; les lectures reçoivent `readOnly`, qui évite à Hibernate le suivi des changements.

```java
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;        // guide 11
    private final InvoiceHistory history;

    @Transactional
    public Invoice cancel(UUID id, String reason) {
        Invoice invoice = invoiceRepository.findById(id)
                .orElseThrow(() -> new InvoiceNotFoundException(id));
        invoice.setCancelledAt(Instant.now());
        invoice.setCancelReason(reason);          // pas de save() : dirty checking au commit
        history.record(invoice.getId(), "CANCELLED");
        return invoice;
    }

    @Transactional(readOnly = true)
    public Invoice find(UUID id) {
        return invoiceRepository.findById(id).orElseThrow(() -> new InvoiceNotFoundException(id));
    }
}
```

`InvoiceNotFoundException` est une `RuntimeException` du domaine d'exemple.

**Étape 2. Exiger la transaction de l'appelant.** Une ligne d'historique ne doit jamais exister sans le changement qu'elle décrit : `MANDATORY` refuse un appel hors transaction, au lieu de valider la ligne seule.

```java
@Component
@RequiredArgsConstructor
public class InvoiceHistory {

    private final InvoiceEventRepository eventRepository;      // un JpaRepository<InvoiceEvent, UUID>

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID invoiceId, String type) {
        eventRepository.save(new InvoiceEvent(invoiceId, type, Instant.now()));
    }
}
```

**Étape 3. Valider à part ce qui doit survivre à un échec.** Un essai de paiement raté doit rester compté même si la requête échoue ensuite : `REQUIRES_NEW` valide avant de rendre la main. La méthode renvoie le compte et l'appelant lève : une exception levée dans la méthode `REQUIRES_NEW` annulerait son propre incrément.

```java
@Component
@RequiredArgsConstructor
public class PaymentAttempts {

    private final QuotaQueries quotaQueries;          // le compteur atomique du guide 13

    /** @return le nombre d'essais dans la fenêtre, celui-ci compris, déjà validé */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int count(UUID customerId, Instant windowStart) {
        return quotaQueries.increment("payment:" + customerId, windowStart);
    }
}
```

```java
// dans le service de paiement, dans sa propre transaction
if (paymentAttempts.count(customerId, windowStart) > 5) {
    throw new TooManyAttemptsException();           // l'essai reste compté
}
```

**Étape 4. Garder une écriture malgré l'exception.** Une révocation faite juste avant de lever doit être validée : `noRollbackFor` va sur chaque méthode transactionnelle que l'exception traverse.

```java
// dans TokenService
@Transactional(noRollbackFor = InvalidTokenException.class)
public Token rotate(String rawToken) {
    Token current = tokenRepository.findByHash(hash(rawToken))
            .orElseThrow(InvalidTokenException::new);
    if (current.getRevokedAt() != null) {
        tokenRepository.revokeFamily(current.getFamilyId(), Instant.now());   // doit rester
        throw new InvalidTokenException();
    }
    // ... émettre le successeur
}

// dans AuthService, qui appelle rotate() dans sa propre transaction
@Transactional(noRollbackFor = InvalidTokenException.class)
public Tokens refresh(String rawToken) {
    return tokens(tokenService.rotate(rawToken));
}
```

`revokeFamily` est une requête `@Modifying` (guide 11) ; `Token`, `Tokens`, `hash` et `tokens` sont du domaine d'exemple.

### Travail hors de la transaction

**Étape 5. Faire l'appel lent entre deux transactions courtes.** Pendant un appel HTTP de plusieurs secondes, une transaction tiendrait une connexion du pool et ses verrous de lignes.

```java
// dans InvoiceService ; transactions : un TransactionTemplate injecté (bean de Spring Boot)
public void collect(UUID invoiceId) {                      // pas de @Transactional ici
    PaymentRequest request = transactions.execute(status -> {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
        return new PaymentRequest(invoice.getId(), invoice.getAmount());       // ce que l'appel lit
    });

    PaymentResult result = paymentClient.charge(request);   // HTTP (guide 26), hors transaction

    transactions.executeWithoutResult(status -> {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();  // relu : il a pu changer
        invoice.setPaidAt(result.succeeded() ? Instant.now() : null);
    });
}
```

La seconde transaction relit la ligne. Quand deux copies du même travail peuvent enregistrer en même temps, on la verrouille (`findByIdForUpdate`, guide 15).

**Étape 6. Agir après le commit.** Un fichier supprimé dans la transaction serait perdu même si elle était annulée.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceFiles {

    private final InvoicePdfRepository pdfRepository;     // un JpaRepository
    private final ObjectStorage storage;                  // stockage S3 (guide 28)

    @Transactional
    public void delete(InvoicePdf pdf) {
        pdfRepository.delete(pdf);
        String key = pdf.getStorageKey();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {                   // jamais après un rollback
                try {
                    storage.delete(key);
                } catch (RuntimeException exception) {    // le commit est fait : rien ne remonte
                    log.warn("Could not delete object {}", key, exception);
                }
            }
        });
    }
}
```

Pour le cas inverse, un fichier écrit avant le commit et retiré si la transaction échoue, on utilise `afterCompletion` :

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCompletion(int status) {
        if (status != STATUS_COMMITTED) {
            storage.delete(key);
        }
    }
});
```

**Étape 7. Vérifier.** On prouve que `MANDATORY` refuse un appel seul et qu'il ne reste rien après un rollback.

```java
@SpringBootTest
class InvoiceTransactionTests {

    @Autowired InvoiceHistory history;
    @Autowired InvoiceEventRepository eventRepository;
    @Autowired TransactionTemplate transactions;

    @Test
    void historyOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> history.record(UUID.randomUUID(), "CANCELLED"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void historyOfARolledBackChangeLeavesNoRow() {
        UUID invoiceId = UUID.randomUUID();

        transactions.executeWithoutResult(status -> {
            history.record(invoiceId, "CANCELLED");
            status.setRollbackOnly();                    // comme une exception
        });

        assertThat(eventRepository.findByInvoiceId(invoiceId)).isEmpty();
    }
}
```

`findByInvoiceId` est une requête dérivée de `InvoiceEventRepository` (guide 11). Côté test, trois outils :

- Une méthode de test `@Transactional` s'annule à sa fin.
- `@Transactional(propagation = NOT_SUPPORTED)` la lance sans transaction, pour que chaque appel valide (guide 13, étape 8).
- Un `TransactionTemplate` en `PROPAGATION_REQUIRES_NEW` valide depuis une autre connexion (`Transactions.inNewTransaction` dans le dépôt).

### Référence rapide

Les attributs de `@Transactional` :

| Attribut | Défaut | Rôle |
|---|---|---|
| `propagation` | `REQUIRED` | rejoindre, exiger, suspendre (voir la table) |
| `readOnly` | `false` | lecture seule : flush manuel, pas de suivi des changements, connexion en lecture seule |
| `rollbackFor`, `noRollbackFor` | `RuntimeException` et `Error` annulent | ajuster les exceptions qui annulent |
| `timeout` | celui de la base | en secondes, pour une transaction nouvelle |
| `isolation` | celui de la base | Postgres : `READ COMMITTED` par défaut (guide 15) |

L'API programmatique :

| API | Rôle |
|---|---|
| `transactionTemplate.execute(status -> valeur)` | une transaction qui renvoie une valeur |
| `transactionTemplate.executeWithoutResult(status -> ...)` | sans valeur |
| `status.setRollbackOnly()` | annuler sans exception |
| `TransactionSynchronizationManager.isActualTransactionActive()` | une transaction est-elle en cours sur ce thread ? |
| `registerSynchronization(...)` | `afterCommit`, `afterCompletion(status)`, `beforeCommit(readOnly)` |

Limites de cette version : un travail de `afterCommit` est perdu si l'instance s'arrête juste après le commit. Pour ce qui doit absolument avoir lieu (un message), on l'écrit dans la transaction et on le publie ensuite : l'outbox, guide 20.

## Concepts avancés

### Sous le capot : une connexion par transaction

Le gestionnaire de transactions (`JpaTransactionManager` avec JPA) prend une connexion du pool au `begin`, la lie au thread courant, et la rend au commit ou au rollback. Chaque appel de repository ou de `JdbcTemplate` sur ce thread l'utilise (guide 13).

Le pool HikariCP compte 10 connexions par défaut. Dix requêtes bloquées dans un appel lent, à l'intérieur d'une transaction, l'épuisent ; et avec un `REQUIRES_NEW` dans chaque requête, dix requêtes peuvent chacune tenir une connexion en attendant la seconde, jusqu'au délai d'attente du pool (`connectionTimeout`, 30 s par défaut), puis échouer.

```
 pool : 10 connexions
 requête 1 : [ transaction ...... appel HTTP 5 s ...... ]  1 connexion tenue 5 s
 ...
 requête 11 : attend une connexion  --> délai dépassé
```

Avec Hibernate, `readOnly` passe le flush en mode `MANUAL`, rend la session en lecture seule (aucune copie gardée pour le dirty checking) et appelle `Connection.setReadOnly(true)` : Postgres refuse alors une écriture.

### Ce qui ne doit pas tourner dans une transaction

Les cas de ce dépôt, et leur guide :

| Travail | Pourquoi hors transaction | Guide |
|---|---|---|
| appel HTTP sortant | il tiendrait connexion et verrous pendant l'appel | 26, 27 |
| verrou ShedLock | il serait rendu avant le commit du travail | 21 |
| démarrage d'un job Spring Batch | Batch refuse de démarrer dans une transaction existante | 22 |
| envoi d'un email | un rollback ne le rappelle pas | 20, 23 |

Une transaction est locale à une connexion : elle ne coordonne rien entre les instances (guide 15).

## Pièges à éviter

### Avec `@Transactional`

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Appeler `this.method()` annotée | l'appel contourne le proxy : pas de transaction | Appeler la méthode depuis un autre bean |
| Annoter une méthode `private` | le proxy ne l'intercepte pas | Annoter une méthode publique du service |
| Attraper l'exception d'une méthode `REQUIRED` | transaction marquée : `UnexpectedRollbackException` | Mettre `noRollbackFor` sur chaque méthode traversée |
| Lever une exception vérifiée | la transaction est validée | Ajouter `rollbackFor`, ou lever une `RuntimeException` |
| Écrire l'historique en `REQUIRED` | appelé seul, il valide sa ligne sans le changement | Le déclarer `MANDATORY` |
| Mettre `readOnly` sur une méthode qui écrit | Hibernate ne flush pas, Postgres refuse l'écriture | Garder `readOnly` pour les lectures |

### Avec le travail autour de la transaction

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Appeler un service HTTP dans la transaction | connexion et verrous tenus pendant tout l'appel | Deux transactions courtes et l'appel entre elles |
| Abuser de `REQUIRES_NEW` | deux connexions par requête : le pool s'épuise | Le réserver à ce qui doit survivre à un échec |
| Lever l'exception dans la méthode `REQUIRES_NEW` | sa propre écriture est annulée | Renvoyer le résultat, lever chez l'appelant |
| Supprimer un fichier dans la transaction | il disparaît même si elle est annulée | Le supprimer dans `afterCommit()` |
| Relancer une erreur depuis `afterCommit()` | l'appelant reçoit une erreur pour un changement déjà validé | L'attraper et la journaliser |
| Relire après l'appel lent sans recharger | la ligne a pu changer entre-temps | Relire, et verrouiller si besoin (guide 15) |

## En résumé

**À retenir :** une transaction par méthode de service, rejointe par défaut ; `MANDATORY` pour ce qui ne peut pas exister seul, `REQUIRES_NEW` pour ce qui doit survivre à un échec, `noRollbackFor` sur chaque méthode que l'exception traverse ; les appels lents entre deux transactions courtes, les actions irréversibles après le commit.

- [ ] `@Transactional` sur les méthodes publiques des services, rien dans les contrôleurs
- [ ] `readOnly = true` sur les lectures
- [ ] Jamais d'auto-appel vers une méthode annotée
- [ ] `MANDATORY` pour l'historique et les messages
- [ ] `REQUIRES_NEW` rare, et sans lever d'exception à l'intérieur
- [ ] `noRollbackFor` sur toute la chaîne d'appels
- [ ] Appels lents hors transaction, avec `TransactionTemplate`
- [ ] Fichiers et effets irréversibles dans `afterCommit()`, nettoyage dans `afterCompletion()`
- [ ] Un test qui prouve le refus hors transaction et l'absence de trace après rollback

## Dans ce dépôt

Une tentative de webhook, entre deux transactions courtes :

```
 WebhookDeliveryService.deliver(id)      pas de @Transactional
        |
        +--> transactions.execute(prepare)
        |        transaction 1 : lit la livraison
        |
        +--> client.send(...)
        |        HTTP, hors transaction
        |
        +--> transactions.executeWithoutResult(record)
                 transaction 2 : verrouille la livraison
                 (findForUpdateById), écrit le résultat
```

| Fichier | Rôle |
|---|---|
| [`TaskEventService`][events] | chaque méthode en `MANDATORY` : une ligne d'historique n'existe qu'avec son changement |
| [`Outbox`][outbox] | `enqueue` et `broadcast` en `MANDATORY`, publication demandée dans `afterCommit()` (guide 20) |
| [`WebhookDeliveryService`][webhooks] | `schedule` en `MANDATORY` ; `deliver` entre deux `TransactionTemplate`, l'appel HTTP entre les deux |
| [`RateLimiter`][rate-limiter] | chaque contrôle en `REQUIRES_NEW` : validé avant le traitement de la requête |
| [`RefreshTokenService`][refresh], [`AuthService`][auth] | `noRollbackFor = InvalidRefreshTokenException.class` sur `rotate` et sur `refresh` |
| [`MediaService`][media] | `delete` : objet supprimé dans `afterCommit()` ; `store` : objet retiré dans `afterCompletion()` si rollback |
| [`RealtimeBroadcasts`][broadcasts] | `TransactionTemplate` quand l'événement arrive hors transaction |
| [`Transactions`][test-transactions] | test : `inNewTransaction`, valider sur une autre connexion pendant que le test garde la sienne |

Ce qui est propre au dépôt :

- Les services portent les transactions et rendent des entités ; les contrôleurs les enveloppent dans des DTO après le commit, `open-in-view` étant coupé (guide 11). Les lectures sont en `@Transactional(readOnly = true)` (`TaskService.findAll`, `findById`, `findByReference`, `TaskCommentService.findAll`, `UserService`, `DataExportService.findOwn`).
- `RateLimiter` est appelé par des contrôleurs (`AuthController`, `UserController`) et par `WebhookEndpointService.sendTest`, non transactionnel. `REQUIRES_NEW` valide chaque comptage avant le traitement : un login qui échoue ensuite compte quand même.
- `RefreshTokenService.rotate` révoque toute la famille quand un jeton révoqué est rejoué, puis lève : `noRollbackFor` sur `rotate` et sur `AuthService.refresh` garde la révocation (la méthode externe en a besoin aussi).
- `WebhookDeliveryService` construit son `TransactionTemplate` à partir du `PlatformTransactionManager`, dans son constructeur ; `prepare` et `record` y tournent, et `record` verrouille la livraison et son endpoint (guide 15).
- `MediaService.delete` retire la ligne tout de suite et l'objet S3 après le commit (un échec est logué, l'orphelin est balayé par le nettoyage des médias, guide 28). Un upload stocké est supprimé si la transaction de l'appelant est annulée (`deleteObjectIfRolledBack`, `afterCompletion`). `MediaCleanupService` supprime aussi ses objets dans `afterCommit()`.
- `MailService.send` est `@Transactional` : appelé hors transaction, il en ouvre une pour la ligne d'outbox (guide 20).
- `RealtimeBroadcasts.onAccountStateChanged` ouvre une transaction seulement s'il n'y en a pas : un listener peut être appelé dans une transaction ou hors d'elle, et `MANDATORY` échouerait dans le second cas.

```java
if (TransactionSynchronizationManager.isActualTransactionActive()) {
    outbox.broadcast(EXCHANGE, message);                          // rejoint celle de l'appelant
} else {
    transactionTemplate.executeWithoutResult(status -> outbox.broadcast(EXCHANGE, message));
}
```

- L'isolation est celle de Postgres (`READ COMMITTED`) ; les exécutions Spring Batch sont créées en read committed aussi (guide 22).

Les tests :

- [`OutboxTests`][outbox-tests] : `enqueueOutsideATransactionIsRefusedAndWritesNothing` attend `IllegalTransactionStateException` ; une transaction annulée (`status.setRollbackOnly()`) ne laisse aucune ligne et n'envoie rien.
- `MailQueueTests.emailSentInRolledBackTransactionIsNeverQueued`.
- [`RefreshTokenApiTests`][refresh-tests] : `replayingUsedRefreshTokenRevokesWholeFamily`, la révocation survit au 401.
- [`RealtimeBroadcastsTest`][broadcasts-test] : dans une transaction, ou dans la sienne.

Pour ajouter un service qui écrit : `@Transactional` sur la méthode publique, `MANDATORY` sur tout assistant qui ne doit pas tourner seul, `TransactionTemplate` autour d'un appel externe, `afterCommit()` ou l'outbox pour une action irréversible.

## Voir aussi

- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : `afterCommit` en détail, et un message qui survit à un plantage.
- [15. Concurrence et verrous](15-concurrence-verrous.md) : isolation, verrous, et ce que deux transactions se font.
- [16. Événements applicatifs et `@Async`](16-evenements-async.md) : `@TransactionalEventListener` et ses phases.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : pourquoi le verrou reste hors de la transaction.
- Documentation officielle : [gestion des transactions dans Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction.html), [propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).

[Sommaire](README.md) | [< 13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) | [15. Concurrence et verrous >](15-concurrence-verrous.md)

[events]: ../../src/main/java/io/julienmetral/tasks/task/services/TaskEventService.java
[outbox]: ../../src/main/java/io/julienmetral/tasks/messaging/services/Outbox.java
[webhooks]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[rate-limiter]: ../../src/main/java/io/julienmetral/tasks/ratelimit/services/RateLimiter.java
[refresh]: ../../src/main/java/io/julienmetral/tasks/identity/services/RefreshTokenService.java
[auth]: ../../src/main/java/io/julienmetral/tasks/identity/services/AuthService.java
[media]: ../../src/main/java/io/julienmetral/tasks/media/services/MediaService.java
[broadcasts]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcasts.java
[test-transactions]: ../../src/test/java/io/julienmetral/tasks/support/Transactions.java
[outbox-tests]: ../../src/test/java/io/julienmetral/tasks/messaging/OutboxTests.java
[refresh-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/RefreshTokenApiTests.java
[broadcasts-test]: ../../src/test/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcastsTest.java
