# 11. Spring Data JPA

[Sommaire](README.md) | [< 10. Rate limiting](10-rate-limiting.md) | [12. Mapping Hibernate 7 >](12-mapping-hibernate.md)

## Contexte

- **Problème** : lire et écrire des entités sans écrire du SQL pour chaque cas, et sans requête cachée par ligne quand une réponse JSON lit une association.
- **Quand l'utiliser** : tout accès CRUD à des entités mappées avec JPA ; les listes filtrées et paginées.
- **Quand s'en passer** : une instruction atomique que JPA ne sait pas dire (`INSERT ... ON CONFLICT`, `UPDATE ... RETURNING`, lot `SKIP LOCKED`, guide 13) ; un rapport sur beaucoup de tables.
- **Dans ce dépôt** : un repository par entité, une `Specification` pour la liste des tâches, un graphe d'entités sur chaque méthode qui nourrit une réponse, des tests qui figent le nombre de requêtes.
- **Prérequis** : guides 12 (mapping) et 14 (transactions). Versions : Spring Boot 4.1, Spring Data JPA 4.1, Hibernate 7.4, PostgreSQL 18.

Les façons d'écrire une requête avec Spring Data :

| Forme | Exemple | Quand la choisir |
|---|---|---|
| Requête dérivée | `findByStatusAndCustomerId(...)` | un filtre simple, deux ou trois critères fixes |
| JPQL `@Query` | `SELECT i FROM Invoice i WHERE ...` | jointures, projection, mise à jour en masse |
| SQL natif `@Query(nativeQuery = true)` | `SELECT EXISTS (...)` | fonctions Postgres, lignes filtrées par Hibernate |
| `Specification` | lambda `(root, query, cb) -> ...` | critères optionnels combinés à l'exécution |

## Concepts clés

### Vocabulaire

- **Repository** : une interface que Spring Data implémente au démarrage ; on déclare des méthodes, Spring écrit les requêtes.
- **Requête dérivée** : une requête déduite du nom de la méthode (`findByReference`).
- **Projection** : une lecture qui renvoie quelques colonnes dans un record, au lieu de l'entité entière.
- **Contexte de persistance** : les entités qu'Hibernate suit pendant une transaction ; au commit, il écrit les changements (dirty checking).
- **Proxy Hibernate** : un objet de remplacement qui charge sa ligne au premier accès. À ne pas confondre avec le proxy Spring du glossaire.
- **N+1** : une requête pour la liste, puis une de plus par ligne pour charger une association.

### Le problème N+1

Un `@ManyToOne` en `LAZY` n'est pas chargé avec la liste. Le lire plus tard, par exemple pendant l'écriture du JSON, lance une requête par ligne distincte :

```
 GET /invoices?size=20
        |
        v
 SELECT * FROM invoices LIMIT 20            1 requête
        |
        v
 écriture du JSON : invoice.getCustomer().getName()
        |
        +--> SELECT * FROM customers WHERE id = ?   client 1
        +--> SELECT * FROM customers WHERE id = ?   client 2
        +--> ...                                    jusqu'à 20 de plus
```

Le coût grandit avec la page et ne se voit qu'aux volumes de production.

### Où se ferme le contexte de persistance

Dans Spring Boot, `spring.jpa.open-in-view` vaut `true` par défaut : l'`EntityManager` reste ouvert jusqu'à l'écriture de la réponse. Une association `LAZY` lue par Jackson se charge donc encore, une requête par ligne, sans erreur.

```
 open-in-view: true (défaut)
   requête --> contrôleur --> service [transaction] --> JSON
              |<------- EntityManager ouvert ------->|
                                         requêtes cachées ici

 open-in-view: false
   requête --> contrôleur --> service [transaction] --> JSON
                              |<-- EntityManager -->|
                                                     association non chargée
                                                 --> LazyInitializationException
```

Avec `false`, le contexte se ferme avec la transaction du service. Une réponse qui lit une association non chargée échoue : en test, pas en production.

### LOAD ou FETCH

`@EntityGraph(attributePaths = ...)` charge les associations listées dans la même requête (une jointure). Le type change le sort des autres attributs. `FETCH`, le défaut de l'annotation de Spring Data, traite comme `LAZY` tout attribut non listé, même mappé `EAGER`. `LOAD` leur laisse leur mapping. On écrit donc toujours `type = EntityGraphType.LOAD`, sauf si l'on veut vraiment la sémantique `FETCH`.

Ce que devient un attribut selon le type du graphe :

| Attribut | `FETCH` (défaut) | `LOAD` |
|---|---|---|
| listé dans le graphe | chargé dans la requête | chargé dans la requête |
| non listé, mappé `LAZY` | `LAZY` | `LAZY` |
| non listé, mappé `EAGER` | traité comme `LAZY` | reste `EAGER` |

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../invoice/
  InvoiceRepository.java        le repository (étapes 1, 2, 5)
  InvoiceSummary.java           la projection (étape 2)
  InvoiceService.java           filtres, pagination, proxys (étapes 3, 6)
src/main/resources/
  application.yaml              open-in-view, chargement par lots (étape 4)
src/test/java/.../
  InvoiceQueryCountTests.java   la vérification (étape 7)
```

Dans les exemples, `Invoice` a `id` (UUID), `reference`, `status` (enum `InvoiceStatus`), `amount`, `createdAt`, `paidAt`, `archivedAt` et `customer`, un `@ManyToOne(fetch = LAZY)` vers `Customer` (`id`, `name`, `email`). Le mapping est au guide 12.

### Un repository et ses requêtes

**Étape 1. Déclarer le repository.** Spring Data écrit l'implémentation au démarrage ; un nom de méthode suffit pour un filtre simple, et un nom qui vise un attribut inconnu arrête le démarrage.

```java
public interface InvoiceRepository
        extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findByReference(String reference);

    List<Invoice> findByStatusAndCustomerIdOrderByCreatedAtDesc(InvoiceStatus status, UUID customerId);

    boolean existsByReference(String reference);

    long countByCustomerId(UUID customerId);
}
```

**Étape 2. Écrire les requêtes que le nom ne sait pas dire.** Le JPQL nomme des entités et des attributs, pas des tables ; le SQL natif reste pour ce que le JPQL ne sait pas écrire. Une projection lit deux colonnes sans charger la ligne entière ni ses associations `EAGER`.

```java
public record InvoiceSummary(String reference, BigDecimal amount, Instant paidAt) {
}
```

```java
// dans InvoiceRepository
@Query("SELECT i FROM Invoice i WHERE i.customer.id = :customerId AND i.paidAt IS NULL")
List<Invoice> findUnpaidOf(@Param("customerId") UUID customerId);

// l'expression constructeur nomme la classe complète
@Query("SELECT new com.example.invoice.InvoiceSummary(i.reference, i.amount, i.paidAt) "
        + "FROM Invoice i WHERE i.customer.id = :customerId")
List<InvoiceSummary> findSummariesOf(@Param("customerId") UUID customerId);

// SQL natif : il voit aussi les lignes que Hibernate filtre (suppression logique, guide 12)
@Query(value = "SELECT EXISTS (SELECT 1 FROM invoices WHERE reference = :reference)",
        nativeQuery = true)
boolean existsByReferenceIncludingDeleted(@Param("reference") String reference);

// mise à jour en masse, appelée depuis une méthode @Transactional (guide 14)
@Modifying(clearAutomatically = true)
@Query("UPDATE Invoice i SET i.archivedAt = :now "
        + "WHERE i.paidAt < :cutoff AND i.archivedAt IS NULL")
int archivePaidBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
```

**Étape 3. Filtrer et paginer.** Les critères optionnels se combinent à l'exécution ; `Pageable` ajoute `LIMIT`, `OFFSET` et une requête de comptage.

```java
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;

    @Transactional(readOnly = true)              // guide 14
    public Page<Invoice> search(InvoiceStatus status, UUID customerId, Pageable pageable) {
        Specification<Invoice> specification = (root, query, builder) -> builder.and(
                builder.isNull(root.get("archivedAt")),
                status == null ? builder.conjunction() : builder.equal(root.get("status"), status),
                customerId == null
                        ? builder.conjunction()
                        : builder.equal(root.get("customer").get("id"), customerId)
        );

        return invoiceRepository.findAll(specification, pageable);
    }
}
```

Le contrôleur reçoit le `Pageable` de `?page=0&size=20&sort=createdAt,desc` et répond un `PagedModel` (guide 03). `spring.data.web.pageable.max-page-size` vaut 2000 par défaut dans Boot ; ce dépôt le fixe à 100.

### Sans N+1, contexte fermé avec la transaction

**Étape 4. Fermer le contexte avec la transaction.** Une association non chargée échoue alors en test, au lieu de coûter une requête par ligne en production. Le chargement par lots, lui, rend peu coûteuse une association restée `LAZY`.

```yaml
spring:
  jpa:
    open-in-view: false
    properties:
      # une association ou collection restée LAZY se charge par lots de 50 (IN), pas ligne par ligne
      hibernate.default_batch_fetch_size: 50
```

**Étape 5. Charger les associations avec un graphe.** Le client arrive dans la même requête que la page ; `LOAD` garde les autres mappings tels quels. On redéclare la méthode héritée pour lui poser le graphe.

```java
// dans InvoiceRepository
@EntityGraph(type = EntityGraphType.LOAD, attributePaths = {"customer"})
Page<Invoice> findAll(Specification<Invoice> specification, Pageable pageable);

@EntityGraph(type = EntityGraphType.LOAD, attributePaths = {"customer"})
Optional<Invoice> findById(UUID id);
```

**Étape 6. Charger les proxys avant de rendre l'entité.** `getReferenceById` pose une association sans `SELECT`, mais la réponse lirait ensuite un proxy Hibernate qui ne peut plus se charger après la transaction.

```java
// dans InvoiceService ; customerRepository : un JpaRepository<Customer, UUID>
@Transactional
public Invoice create(String reference, BigDecimal amount, UUID customerId) {
    Invoice invoice = new Invoice();
    invoice.setReference(reference);
    invoice.setAmount(amount);
    invoice.setCustomer(customerRepository.getReferenceById(customerId));   // pas de SELECT

    Invoice saved = invoiceRepository.save(invoice);
    Hibernate.initialize(saved.getCustomer());       // chargé tant que la transaction est ouverte
    return saved;
}
```

`getReferenceById` ne vérifie pas que la ligne existe : un mauvais id échoue au premier accès (`EntityNotFoundException`) ou à l'insertion. Quand l'id vient du client, on vérifie l'existence avant.

**Étape 7. Vérifier.** On compare le nombre de requêtes pour une liste courte et une longue : il doit être identique. Le compteur, `SqlStatementCounter`, est un `StatementInspector` de Hibernate enregistré par une propriété, donc sans nouveau contexte Spring ; il est construit au guide 31 (étape 4).

```java
@SpringBootTest
class InvoiceQueryCountTests {

    @Autowired InvoiceService invoiceService;
    @Autowired TestData testData;               // crée des factures, un client chacune (à écrire)

    @Test
    void searchingRunsTheSameNumberOfQueriesWhateverTheNumberOfCustomers() throws Exception {
        testData.invoicesOfDistinctCustomers(2);
        List<String> fewQueries = queriesToReadNewest(2);

        testData.invoicesOfDistinctCustomers(20);
        List<String> manyQueries = queriesToReadNewest(20);

        assertThat(manyQueries).hasSameSizeAs(fewQueries);
    }

    private List<String> queriesToReadNewest(int count) throws Exception {
        // page pleine dans les deux cas : la requête de comptage part des deux côtés
        Pageable newest = PageRequest.of(0, count, Sort.by("createdAt").descending());

        return SqlStatementCounter.statementsDuring(() -> invoiceService.search(null, null, newest)
                .forEach(invoice -> invoice.getCustomer().getName()));     // ce que lirait le JSON
    }
}
```

Sans le graphe de l'étape 5, ce test échoue : plus de requêtes, ou `LazyInitializationException` (étape 4). En manuel, `logging.level.org.hibernate.SQL: debug` affiche chaque requête.

### Référence rapide

Les mots-clés les plus utiles d'une requête dérivée :

| Mot-clé | Exemple | SQL produit (idée) |
|---|---|---|
| `And`, `Or` | `findByStatusAndCustomerId` | `status = ? AND customer_id = ?` |
| `In` | `findByStatusIn(Collection)` | `status IN (...)` |
| `Before`, `After`, `LessThan` | `findByDueAtBefore(Instant)` | `due_at < ?` |

Les annotations d'un repository :

| Annotation | Rôle |
|---|---|
| `@Query` | JPQL, ou SQL avec `nativeQuery = true` |
| `@Param("x")` | lie `:x` au paramètre |
| `@Modifying` | `UPDATE` ou `DELETE` ; `flushAutomatically` et `clearAutomatically` valent `false` par défaut |
| `@EntityGraph(type = LOAD, attributePaths = ...)` | charge des associations dans la même requête |
| `@Lock(PESSIMISTIC_WRITE)` | verrouille les lignes lues (guide 15) |

Côté `JpaRepository`, `findById` et `existsById` sont filtrées par `@SoftDelete` (guide 12), `getReferenceById` ne lance aucun `SELECT` et renvoie un proxy Hibernate, et `save` fait un `persist` pour une entité nouvelle, sinon un `merge`.

Limites de cette version : le graphe couvre les associations vers un seul objet ; les collections d'une page restent à charger par lots, et seul le test empêche une requête par ligne de revenir.

## Concepts avancés

### Sous le capot : le repository est un bean généré

- Au démarrage, Spring Data crée une implémentation (fondée sur `SimpleJpaRepository`) derrière un proxy Spring, et analyse le nom de chaque méthode dérivée.
- Les méthodes CRUD héritées de `SimpleJpaRepository` sont transactionnelles (en lecture seule pour les lectures). Les méthodes déclarées dans l'interface ne le sont pas, sauf annotation : une méthode `@Modifying` doit tourner dans une transaction ouverte par le service.
- Une requête `@Modifying` en JPQL contourne le contexte de persistance : les entités déjà chargées gardent leurs anciennes valeurs sans `clearAutomatically = true`, et les changements en attente ne sont écrits d'abord qu'avec `flushAutomatically = true`. `DataExportRepository.claim` utilise les deux (guide 15).

### Sous le capot : le chargement par lots

Avec `hibernate.default_batch_fetch_size: 50`, une association ou collection `LAZY` touchée se charge pour 50 propriétaires au plus avec un seul `IN (...)`, au lieu d'une requête par propriétaire. Sans ce réglage, Hibernate ne groupe que ce qui porte `@BatchSize`.

```
 page de 20 commentaires, mentions LAZY
        |
        v
 sans lots : 20 x SELECT ... FROM mentions WHERE comment_id = ?
 avec 50   :  1 x SELECT ... FROM mentions WHERE comment_id IN (?, ?, ... 20)
```

### Pagination et collections

Paginer une requête qui joint une collection (un graphe, ou un `JOIN FETCH` sur un `@OneToMany`) force Hibernate à charger toutes les lignes et à couper la page en mémoire, avec l'avertissement HHH90003004. Le réglage `hibernate.query.fail_on_pagination_over_collection_fetch: true` en fait une erreur. Parade : un graphe pour les associations vers un seul objet, le chargement par lots pour les collections.

## Pièges à éviter

### Avec les requêtes

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Appeler une méthode `@Modifying` hors transaction | la mise à jour échoue : aucune transaction | L'appeler depuis un service `@Transactional` |
| Relire une entité après un `UPDATE` JPQL | le contexte rend l'ancienne valeur | Mettre `clearAutomatically = true` |
| Vérifier l'unicité avec une requête dérivée | elle ignore les lignes supprimées, l'insertion échoue | Écrire la vérification en SQL natif (guide 12) |
| Utiliser `getReferenceById` avec un id du client | l'erreur arrive à l'insertion ou au premier accès | Vérifier l'existence avant |

### Avec le chargement des associations

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser `open-in-view` à `true` | Jackson charge les associations, une requête par ligne, sans erreur | Mettre `spring.jpa.open-in-view: false` |
| Écrire `@EntityGraph` sans `type` | `FETCH` rend `LAZY` les attributs non listés, même `EAGER` | Écrire `type = EntityGraphType.LOAD` |
| Mettre une collection dans un graphe paginé | Hibernate charge tout et pagine en mémoire | Charger les collections par lots |
| Rendre une entité créée avec `getReferenceById` | `LazyInitializationException` à l'écriture du JSON | Initialiser le proxy dans la transaction |
| Passer une association en `EAGER` pour corriger | chaque lecture la charge, même quand rien ne la lit | Garder `LAZY` et charger par graphe |
| Mesurer avec une page pleine d'un côté seulement | le comptage n'apparaît que d'un côté | Page pleine des deux côtés, ou plus grande que les deux listes |

## En résumé

**À retenir :** un repository par entité, des requêtes dérivées pour les filtres simples et du JPQL au-delà ; `open-in-view: false`, `@EntityGraph(type = LOAD)` pour les associations vers un seul objet et le chargement par lots pour les collections, et un test qui prouve que le nombre de requêtes ne grandit pas avec les données.

- [ ] Une interface `JpaRepository` par entité, sans implémentation à écrire
- [ ] Requêtes dérivées pour les filtres simples, `@Query` au-delà
- [ ] Projections en record pour les lectures partielles
- [ ] `@Modifying` appelé dans une transaction, avec `clearAutomatically` si on relit
- [ ] `spring.jpa.open-in-view: false`
- [ ] `@EntityGraph(type = EntityGraphType.LOAD)` sur les méthodes qui nourrissent une réponse
- [ ] `hibernate.default_batch_fetch_size` pour les collections
- [ ] Proxys de `getReferenceById` initialisés avant la fin de la transaction
- [ ] Un test qui compare le nombre de requêtes entre une petite et une grande liste (page pleine des deux côtés)

## Dans ce dépôt

Le trajet d'une page de tâches :

```
 GET /api/v1/tasks?size=20
        |
        v
 TaskService.findAll                     @Transactional(readOnly = true)
        |  Specification : archivedAt, status, assignee
        v
 TaskRepository.findAll(spec, pageable)  @EntityGraph LOAD :
        |                                assignedTo, createdBy et leurs photos
        v
 SELECT tasks JOIN user_profiles JOIN media ... LIMIT 20     1 requête
 SELECT count(*) ...                                          1 requête
        |
        v
 commit, contexte fermé --> TaskResponseDto (tout est chargé)
```

Quand le statut de l'appelant n'est pas en cache, `ActiveUserAuthorizationManager` ajoute une requête : d'où les 3 requêtes mesurées pour 20 tâches.

| Fichier | Rôle |
|---|---|
| [`TaskRepository`][task-repo] | `JpaSpecificationExecutor`, graphes `LOAD` sur `findAll(spec, pageable)`, `findById`, `findAll()`, contrôle d'unicité natif |
| [`TaskService`][task-service] | la `Specification` de la liste (`findAll`), `getReferenceById`, `withUsersLoaded` |
| [`UserRepository`][user-repo] | requête dérivée, graphe `findWithProfileById`, projection `findAccountStateById`, `findByIdForUpdate` |
| [`AccountState`][account-state] | le record lu par l'expression constructeur : `enabled` et `emailVerifiedAt` seuls |
| [`UserProfileRepository`][profile-repo] | `getReferenceById` pour pointer vers un utilisateur sans le charger |
| [`ProfilesForDisplay`][profiles] | `Hibernate.initialize` du profil et de sa photo avant le retour du service |
| [`TaskCommentRepository`][comment-repo], [`TaskEventRepository`][event-repo] | graphes `author` et `actor`, avec leur photo |
| [`TaskCommentService`][comment-service] | `withDetailsLoaded` : touche mentions et fichiers, chargés par lots |
| [`DataExportRepository`][export-repo] | `@Modifying` avec `flush` et `clear` automatiques sur `claim` |

Les réglages, dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.jpa.open-in-view` | `false` | le contexte se ferme avec la transaction du service |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | `50` | associations et collections chargées par lots de 50 |
| `spring.data.web.pageable.max-page-size` | `100` | une taille plus grande est ramenée à 100 sans erreur |
| `spring.jpa.hibernate.ddl-auto` | `validate` | le schéma vient de Liquibase (guide 18) |

Ce qui est propre au dépôt :

- Les associations vers les utilisateurs ou les médias sont `LAZY` ; celles vers `Task` sont `EAGER`, car `Task` porte `@SoftDelete` (guide 12).
- Tous les graphes sont en `LOAD`. Le commentaire de `TaskRepository` garde la trace de l'incident : avec le `FETCH` par défaut, les rôles du compte (un `@ElementCollection` `EAGER`) et les tâches des événements et des commentaires n'étaient plus chargés.
- `findAccountStateById` lit deux colonnes par une expression constructeur JPQL, dans le record `AccountState`. Il est appelé à chaque requête sur les tâches, et `findById` chargerait le hash du mot de passe et les rôles. Étant du JPQL, il renvoie vide pour un compte supprimé logiquement.
- Les références vers les utilisateurs se posent avec `userProfileRepository.getReferenceById(id)`, puis `ProfilesForDisplay.load` initialise le proxy et sa photo avant le retour du service (`TaskService.withUsersLoaded`).
- `TaskCommentService.withDetailsLoaded` touche l'auteur, les mentions et les fichiers ; le chargement par lots les lit ensuite pour toute la page.
- Mesuré sur une page de 20 tâches : 3 requêtes, quel que soit le nombre d'utilisateurs distincts (8 avant, en croissance avec eux). Les tests fixent, statut déjà en cache : page de tâches 2, une tâche 1, événements 3, commentaires 6, pièces jointes 2, un utilisateur 2.

Les tests :

- [`TaskQueryCountTests`][count-test] vérifie que chaque lecture lance autant de requêtes pour une longue liste que pour une courte (page de 50, statut de l'appelant chauffé d'abord).
- [`UserQueryCountTests`][user-count-test] fait de même pour les utilisateurs. Les deux utilisent [`SqlStatementCounter`][counter], un `StatementInspector` enregistré par `hibernate.session_factory.statement_inspector` dans le `application.yaml` de test, qui ne note que le thread du test (guide 31).
- [`UserRepositoryTests`][user-repo-test] (`@RepositoryTest`) vérifie le graphe de `findWithProfileById` (profil, photos, rôles) et que `findAccountStateById` lit la seule ligne du compte.

Pour ajouter une lecture : un `@EntityGraph(type = LOAD)` qui liste les associations lues par la réponse, les collections touchées dans le service, puis un cas dans le test de comptage.

## Voir aussi

- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : suppression logique, pourquoi certaines requêtes sont natives, `EAGER` vers `Task`.
- [13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) : quand un repository ne suffit pas.
- [14. Transactions](14-transactions.md) : `readOnly` et l'endroit où la transaction commence.
- [15. Concurrence et verrous](15-concurrence-verrous.md) : `@Lock` et l'`UPDATE` conditionnel.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : le compteur de requêtes.
- Documentation officielle : [Spring Data JPA, méthodes de requête](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html), [Hibernate ORM 7.4](https://hibernate.org/orm/documentation/7.4/).

[Sommaire](README.md) | [< 10. Rate limiting](10-rate-limiting.md) | [12. Mapping Hibernate 7 >](12-mapping-hibernate.md)

[task-repo]: ../../src/main/java/io/julienmetral/tasks/task/repositories/TaskRepository.java
[task-service]: ../../src/main/java/io/julienmetral/tasks/task/services/TaskService.java
[user-repo]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/UserRepository.java
[account-state]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/AccountState.java
[profile-repo]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/UserProfileRepository.java
[profiles]: ../../src/main/java/io/julienmetral/tasks/identity/services/ProfilesForDisplay.java
[comment-repo]: ../../src/main/java/io/julienmetral/tasks/task/repositories/TaskCommentRepository.java
[event-repo]: ../../src/main/java/io/julienmetral/tasks/task/repositories/TaskEventRepository.java
[comment-service]: ../../src/main/java/io/julienmetral/tasks/task/services/TaskCommentService.java
[export-repo]: ../../src/main/java/io/julienmetral/tasks/export/repositories/DataExportRepository.java
[count-test]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskQueryCountTests.java
[user-count-test]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/UserQueryCountTests.java
[counter]: ../../src/test/java/io/julienmetral/tasks/support/SqlStatementCounter.java
[user-repo-test]: ../../src/test/java/io/julienmetral/tasks/identity/repositories/UserRepositoryTests.java
