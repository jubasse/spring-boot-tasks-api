# 12. Mapping Hibernate 7

[Sommaire](README.md) | [< 11. Spring Data JPA](11-spring-data-jpa.md) | [13. SQL avec JdbcTemplate >](13-sql-jdbctemplate.md)

## Contexte

- **Problème** : décrire des tables dans des classes Java pour qu'Hibernate les lise et les écrive, en laissant à la base ses règles (ids, contraintes uniques, clés étrangères) et sans perdre de données à la suppression.
- **Quand l'utiliser** : toute table que l'application modifie par JPA.
- **Quand s'en passer** : une table écrite seulement par du SQL atomique (compteurs, verrous). Une entité en lecture seule suffit, ou aucune (guides 13 et 18).
- **Dans ce dépôt** : la classe de base `AuditableEntity` (audit et suppression logique) pour `User` et `Task`, des clés primaires UUIDv7 produites par Postgres 18, compte et profil en deux tables qui partagent un id, `@Version` sur `Task`, `@DynamicUpdate` sur `User` et `UserProfile`, des charges `jsonb`.
- **Prérequis** : guides 11 (Spring Data JPA) et 18 (migrations). Versions : Spring Boot 4.1, Hibernate 7.4, PostgreSQL 18.

Les façons de supprimer une ligne :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Suppression physique | `DELETE` | données sans historique ni référence |
| `@SoftDelete` de Hibernate | `deleted_at` rempli, lignes filtrées partout | l'historique doit garder ses références |
| Colonne gérée à la main | un `WHERE deleted_at IS NULL` dans chaque requête | à éviter : un oubli suffit |
| Table d'archives | la ligne est déplacée | les supprimés ne sont plus jamais lus |

## Concepts clés

### Vocabulaire

- **Mapping** : la description d'une table dans une classe Java (les annotations de l'entité).
- **Suppression logique** (soft delete) : la ligne reste, une colonne la marque supprimée, et les lectures l'ignorent.
- **Audit** : les dates de création et de modification, remplies automatiquement.
- **Dirty checking** : au commit, Hibernate compare chaque entité chargée à son état initial. Il écrit un `UPDATE` pour ce qui a changé, sans `save()`.
- **UUIDv7** : un UUID dont les premiers bits sont une date, donc les nouveaux ids se rangent après les anciens.

### La suppression logique

Avec `@SoftDelete(strategy = TIMESTAMP, columnName = "deleted_at")`, `repository.delete()` devient un `UPDATE ... SET deleted_at = <horloge de la base>`. Hibernate ajoute aussi `deleted_at IS NULL` à chaque requête qu'il écrit (JPQL, requêtes dérivées, `findById`, associations). La colonne n'est pas un attribut de l'entité, Hibernate la gère. La date vient de l'horloge de la base, pas d'une `Clock` de test. `@SoftDelete` est `@Incubating` dans Hibernate 7.4, donc son API peut changer.

```
 customerRepository.delete(customer)
        |
        v
 UPDATE customers SET deleted_at = localtimestamp WHERE id = ?
        |
        v
 lectures JPA       : ... WHERE deleted_at IS NULL     --> ligne invisible
 SQL natif, JDBC    : ... (rien d'ajouté)               --> ligne visible
 contrainte unique  : couvre toutes les lignes          --> email toujours pris
```

Qui voit une ligne supprimée logiquement :

| Accès | Voit la ligne ? |
|---|---|
| `findById`, requête dérivée, JPQL | non |
| association `@ManyToOne` vers l'entité | non |
| `@Query(nativeQuery = true)`, `JdbcTemplate` | oui : il faut écrire `deleted_at IS NULL` soi-même |
| contrainte `UNIQUE`, clé étrangère | oui |

Un contrôle d'existence écrit en JPQL répond donc « libre » pour un email que détient un utilisateur supprimé, et l'`INSERT` casse la contrainte. Le contrôle doit être natif. Un insert concurrent peut encore passer entre le contrôle et l'insertion, et la contrainte reste le dernier garde-fou (guide 15).

### L'id généré par la base

Avec `@Id @Generated @ColumnDefault("uuidv7()")`, Hibernate laisse l'id hors de l'`INSERT`. Postgres 18 le remplit avec `uuidv7()`, et Hibernate le relit dans la même instruction (`RETURNING`, géré par le dialecte Postgres). Un UUIDv7 croît avec le temps. L'index reçoit les insertions à la fin comme avec une séquence, les ids se trient par création, et rien n'est devinable comme 1, 2, 3. On n'assigne jamais l'id en Java.

```
 invoiceRepository.save(invoice)          invoice.id == null
        |
        v
 INSERT INTO invoices (amount, ...) VALUES (?, ...) RETURNING id
        |                                  Postgres : DEFAULT uuidv7()
        v
 invoice.id == 0192f3a1-...               relu par Hibernate
```

### Ce qu'écrit un UPDATE

Par défaut, l'`UPDATE` d'Hibernate contient toutes les colonnes, avec les valeurs de l'entité chargées dans cette transaction, plus ses changements. Deux transactions qui changent des colonnes différentes d'une même ligne s'écrasent alors. La seconde réécrit la colonne de la première avec l'ancienne valeur qu'elle avait chargée.

```
 transaction A (worker)               transaction B (admin)
 lit le client : enabled = true
                                      lit le client, enabled = false
                                      commit
 change avatar_id
 commit : UPDATE customers SET
   enabled = true,       <-- l'ancienne valeur, réécrite
   avatar_id = ?, ...
 --> le compte désactivé par B est réactivé
```

`@DynamicUpdate` n'écrit que les colonnes modifiées. Hibernate génère le SQL à l'exécution, pour un petit coût. L'`UPDATE` devient `UPDATE customers SET avatar_id = ? WHERE id = ?`, et le changement de B survit. Cela protège des colonnes différentes. Deux écritures sur la même colonne demandent `@Version` ou un verrou (guide 15).

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  shared/
    AuditableEntity.java              audit et suppression logique (étape 1)
    JpaAuditingConfiguration.java     active l'audit (étape 1)
  customer/
    Customer.java                     id, contraintes, version, JSON (2 à 5, 8)
    CustomerRepository.java           contrôle d'unicité natif (étape 6)
  invoice/
    Invoice.java                      référence vers un client (étape 7)
src/test/java/.../
  CustomerRepositoryTests.java        la vérification (étape 9)
```

### Une entité de base

**Étape 1. Créer la classe de base.** Chaque entité reçoit ses dates et sa suppression logique d'un seul endroit.

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
@Getter
@Setter
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

```java
@Configuration
@EnableJpaAuditing          // sans elle, @CreatedDate reste null et l'INSERT échoue (NOT NULL)
class JpaAuditingConfiguration {
}
```

`@SoftDelete` et `SoftDeleteType` viennent de `org.hibernate.annotations`, `@CreatedDate` et `@LastModifiedDate` de `org.springframework.data.annotation`.

**Étape 2. Laisser Postgres générer l'id.** La base possède l'id, et Hibernate le relit après l'insertion.

```java
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
public class Customer extends AuditableEntity {

    @Id
    @Generated                              // relu après l'INSERT
    @ColumnDefault("uuidv7()")              // fonction de PostgreSQL 18
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;
}
```

La migration déclare le même défaut : `defaultValueComputed: uuidv7()` (guide 18).

**Étape 3. Nommer les contraintes.** Une contrainte nommée se lit clairement dans une erreur et dans la migration. Elle suit la convention, vérifiée à la main (guide 18).

```java
@Table(
        name = "customers",
        uniqueConstraints = @UniqueConstraint(name = "customers_emailUQ", columnNames = "email")
)
```

**Étape 4. Ajouter un numéro de version.** Hibernate refuse alors d'écrire par-dessus un changement validé entre-temps (le concept et la réponse 409 : guide 15).

```java
// dans Customer
@Version
@Column(nullable = false)
private long version;           // Hibernate l'incrémente ; ne jamais l'écrire soi-même
```

**Étape 5. Stocker du JSON.** Une charge libre va dans une colonne `jsonb`, sans convertisseur.

```java
// dans Customer
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> preferences;
```

### Suppression logique et références

**Étape 6. Vérifier l'unicité en SQL natif.** La contrainte unique couvre les lignes supprimées, que le JPQL ne voit pas.

```java
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    // natif exprès : une requête dérivée ignore les clients supprimés, la contrainte non
    @Query(value = "SELECT EXISTS (SELECT 1 FROM customers WHERE lower(email) = lower(:email))",
            nativeQuery = true)
    boolean existsByEmailIncludingDeleted(@Param("email") String email);
}
```

Le service l'appelle avant l'insertion et répond 409 si elle renvoie `true` (guide 15 pour l'insertion concurrente).

**Étape 7. Pointer vers une entité supprimée logiquement.** Hibernate refuse un to-one `LAZY` vers une entité qui porte `@SoftDelete`. L'erreur n'apparaît qu'au démarrage.

```java
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
public class Invoice {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // EAGER obligatoire : en LAZY, la SessionFactory ne démarre pas (Customer porte @SoftDelete)
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
            foreignKey = @ForeignKey(name = "invoices_customerFK"))
    private Customer customer;
}
```

L'autre option est de pointer vers une entité sans `@SoftDelete` (le `UserProfile` du dépôt, voir les concepts avancés).

**Étape 8. N'écrire que les colonnes modifiées.** Deux transactions qui changent des colonnes différentes ne s'écrasent plus.

```java
@Entity
@DynamicUpdate
@Table(name = "customers",
        uniqueConstraints = @UniqueConstraint(name = "customers_emailUQ", columnNames = "email"))
public class Customer extends AuditableEntity {
    // ...
}
```

**Étape 9. Vérifier.** Contre un vrai Postgres, un client supprimé disparaît de JPA mais garde son email.

```java
@DataJpaTest
@Import({PostgresTestcontainersConfiguration.class, JpaAuditingConfiguration.class})  // guide 30
class CustomerRepositoryTests {

    @Autowired CustomerRepository customerRepository;
    @Autowired TestEntityManager entityManager;

    @Test
    void emailOfASoftDeletedCustomerStaysTaken() {
        String email = UUID.randomUUID() + "@example.com";
        Customer customer = new Customer();
        customer.setEmail(email);
        entityManager.persistAndFlush(customer);

        customerRepository.delete(customer);
        entityManager.flush();
        entityManager.clear();

        assertThat(customerRepository.findById(customer.getId())).isEmpty();          // filtré par Hibernate
        assertThat(customerRepository.existsByEmailIncludingDeleted(email)).isTrue(); // vu en SQL natif
    }
}
```

### Référence rapide

Les annotations de ce guide :

| Annotation | Paquet | Rôle |
|---|---|---|
| `@SoftDelete(strategy = TIMESTAMP, columnName = ...)` | `org.hibernate.annotations` | suppression logique datée |
| `@Generated` + `@ColumnDefault("uuidv7()")` | `org.hibernate.annotations` | id produit par la base, relu après l'insertion |
| `@Version` | `jakarta.persistence` | verrou optimiste (guide 15) |
| `@DynamicUpdate` | `org.hibernate.annotations` | `UPDATE` limité aux colonnes modifiées |
| `@JdbcTypeCode(SqlTypes.JSON)` | `org.hibernate.annotations` | colonne JSON |
| `@Immutable` | `org.hibernate.annotations` | entité en lecture seule : changements ignorés |
| `@MapsId` | `jakarta.persistence` | l'id de l'entité est celui de l'association |
| `@CreatedDate`, `@LastModifiedDate` | `org.springframework.data.annotation` | audit, avec `@EnableJpaAuditing` |

Limites de cette version : les lignes supprimées logiquement restent hors de portée de JPA. Les purger, les anonymiser ou les joindre demande du SQL natif (guide 13).

## Concepts avancés

### Sous le capot : le refus du `LAZY`

Hibernate 7 lève à la construction de la session factory : « To-one attribute (X.y) cannot be mapped as LAZY as its associated entity is defined with @SoftDelete » (`UnsupportedMappingException`). Un proxy `LAZY` devrait charger une ligne que le filtre peut cacher, et Hibernate préfère échouer. L'erreur n'apparaît qu'au démarrage, jamais à la compilation.

Dans ce dépôt, les pièces jointes d'une tâche supprimée ne se chargent plus par JPA : leur purge passe par des requêtes natives.

### Compte et profil : deux tables, un id

Une tâche ou un historique doit encore afficher un utilisateur supprimé (« Deleted user »). Pourtant son compte (email, hash du mot de passe) doit disparaître après la durée de rétention. Le dépôt sépare donc `users` (le compte, `@SoftDelete` par `AuditableEntity`) et `user_profiles` (nom affiché, photo, copie du statut, sans suppression logique). Les deux partagent un UUID. Toute référence (tâches, commentaires, historique, médias) vise le profil, jamais supprimé logiquement, donc le `LAZY` y est permis.

`@MapsId` sur `User.profile` donne au compte l'id du profil. La clé étrangère va de `users.id` vers `user_profiles.id`, jamais dans l'autre sens. Supprimer la ligne du compte laisse donc le profil.

```java
// dans Account (le compte) ; Profile n'a pas de @SoftDelete
@Id
@Column(name = "id", nullable = false, updatable = false)
private UUID id;

@OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
@MapsId                                                    // l'id du compte = l'id du profil
@JoinColumn(name = "id", foreignKey = @ForeignKey(name = "accounts_profileFK"))
private Profile profile = new Profile();                   // créé avec le compte (cascade)
```

```
 user_profiles (jamais supprimé)        users (@SoftDelete)
 +------------------------+             +------------------------+
 | id  (uuidv7)           |<--- FK -----| id  (même valeur)      |
 | display_name, status   |             | email, password_hash   |
 +------------------------+             +------------------------+
          ^
          |  FK : tasks.assigned_to_id, task_comments.author_id, ...
```

### Unicité d'une association : `@ManyToOne` plutôt que `@OneToOne`

Un `@OneToOne` fait ajouter à Hibernate une contrainte unique implicite au nom généré. `liquibase:diff` la signale face à celle qui est nommée. Le dépôt mappe donc `UserProfile.avatar`, `UserProfile.pendingAvatar` et `TaskAttachment.media` en `@ManyToOne`, avec la contrainte unique nommée dans `@Table(uniqueConstraints = ...)`.

### Le piège `@NotFound(IGNORE)`

Cette annotation tolère un utilisateur manquant, mais elle implique « pas de clé étrangère » dans le modèle (`ConstraintMode.NO_CONSTRAINT`) et rend le to-one non paresseux. Le diff a alors proposé de supprimer les vraies contraintes : 7 clés étrangères ont un jour disparu du modèle (côté diff : guide 18).

### Entités en lecture seule

Avec `@Immutable`, les changements faits en mémoire ne sont jamais écrits, sans erreur, et Hibernate ne garde aucune copie pour le dirty checking. Le dépôt l'utilise pour les tables écrites seulement par SQL (`ScheduledJobLock`, `RateLimitCounter`, `TaskReminder`). L'entité garde la table dans le modèle d'Hibernate. `ddl-auto: validate` la vérifie donc, et le diff ne propose pas de la supprimer (guide 18).

### Le JSON et Jackson

Hibernate sérialise les colonnes JSON avec son propre mapper Jackson, pas le `JsonMapper` de Spring. Jackson 2 n'est sur le classpath de ce dépôt que pour cela. D'où les charges en `Map<String, Object>` et non en `JsonNode` de Jackson 3.

## Pièges à éviter

### Avec le mapping

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Assigner l'id en Java | `save` croit l'entité existante : `merge` et un `SELECT` de plus | Laisser l'id à `null`, la base le génère |
| Laisser Hibernate nommer les contraintes | des noms générés, différents de la migration | Les nommer dans `@Table` et `@ForeignKey` |
| Mapper l'unicité par `@OneToOne` | contrainte implicite au nom généré, signalée par le diff | Écrire `@ManyToOne` et une contrainte nommée |
| Poser `@NotFound(IGNORE)` sur une association | la clé étrangère sort du modèle, le diff veut la supprimer | Viser une entité qui ne disparaît jamais |
| Oublier `@EnableJpaAuditing` | `created_at` reste null, l'insertion échoue | Déclarer la configuration d'audit |

### Avec la suppression logique

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Vérifier l'unicité en JPQL | la ligne supprimée est ignorée, l'insertion viole la contrainte | Vérifier en SQL natif |
| Mapper en `LAZY` un to-one vers une entité `@SoftDelete` | la SessionFactory refuse de démarrer | Mapper en `EAGER`, ou viser une entité sans `@SoftDelete` |
| Oublier `deleted_at IS NULL` en SQL natif | les lignes supprimées reviennent dans le résultat | L'écrire dans chaque requête native |
| Purger les supprimés avec JPA | `findAll` ne les trouve pas : rien n'est purgé | Purger en SQL natif (guide 13) |

### Avec les mises à jour

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder l'`UPDATE` complet par défaut | une transaction réécrit la colonne qu'une autre vient de changer | Poser `@DynamicUpdate` |
| Écrire la version soi-même | Hibernate compare une valeur fausse : conflit ou écrasement | La laisser à Hibernate |

## En résumé

**À retenir :** une classe de base pour l'audit et la suppression logique, des ids et des défauts laissés à Postgres, des contraintes toutes nommées et du SQL natif partout où les lignes supprimées comptent. Les associations vers une entité `@SoftDelete` sont `EAGER`, et `@DynamicUpdate` s'impose dès que deux écrivains touchent la même ligne.

- [ ] Classe de base `@MappedSuperclass` avec audit et `@SoftDelete`, `@EnableJpaAuditing` déclaré
- [ ] Ids `@Generated` + `@ColumnDefault("uuidv7()")`, jamais assignés en Java
- [ ] Contraintes et clés étrangères nommées selon la convention
- [ ] Contrôles d'unicité en SQL natif quand la contrainte couvre les supprimés
- [ ] To-one vers une entité `@SoftDelete` en `EAGER`, ou vers une entité sans suppression
- [ ] `@Version` sur les entités modifiées par plusieurs requêtes à la fois
- [ ] `@DynamicUpdate` sur les entités modifiées par plusieurs écrivains
- [ ] Pas de `@NotFound(IGNORE)`, pas de `@OneToOne` pour imposer l'unicité
- [ ] Colonnes JSON en `Map<String, Object>`

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`AuditableEntity`][auditable] | `created_at`, `updated_at` et `@SoftDelete` sur `deleted_at`, pour `User` et `Task` |
| [`JpaAuditingConfiguration`][auditing] | `@EnableJpaAuditing` |
| [`User`][user] | le compte : `@DynamicUpdate`, `users_emailUQ`, `@MapsId` vers le profil, rôles en `@ElementCollection` `EAGER` |
| [`UserProfile`][profile] | le profil visible, sans suppression logique ; photos en `@ManyToOne` avec contraintes uniques nommées |
| [`Task`][task] | UUIDv7, `tasks_referenceUQ`, `@Version`, références `LAZY` vers `UserProfile` |
| [`TaskAttachment`][attachment] | `task` en `EAGER` (cible `@SoftDelete`), `media` en `@ManyToOne` avec contrainte unique nommée |
| [`TaskEvent`][event] | `payload` en `jsonb` (`Map<String, Object>`) |
| [`UserRepository`][user-repo], [`TaskRepository`][task-repo] | `existsByEmailIncludingDeleted`, `existsByReferenceIncludingDeleted` en SQL natif |
| [`ScheduledJobLock`][lock], [`RateLimitCounter`][counter], [`TaskReminder`][reminder] | entités `@Immutable` : tables écrites en SQL |

`spring.jpa.hibernate.ddl-auto` vaut `validate` : Hibernate compare les entités au schéma et refuse de démarrer s'il manque une table ou une colonne.

Ce qui est propre au dépôt :

- La convention de noms : `<table>_<column>UQ`, `<table>_<column>FK`, `<table>PK` (`users_emailUQ`, `tasks_referenceUQ`, `tasks_created_byFK`, `users_profileFK`, `tasksPK`). Les index suivent `<table>_<column>IDX` (`tasks_due_atIDX`, `task_attachments_task_idIDX`).
- `users_emailUQ` et `tasks_referenceUQ` couvrent aussi les lignes supprimées logiquement. L'email d'un utilisateur supprimé et la référence d'une tâche supprimée restent pris, l'email jusqu'à l'anonymisation, qui supprime la ligne `users` (guide 13). `ApiExceptionHandler` répond 409 à une `DataIntegrityViolationException` restante, deux insertions concurrentes par exemple (guide 15).
- `User.markDeleted()` s'appelle avant `delete()`. Hibernate pose `deleted_at` sans passer par l'entité, donc la copie du statut du profil (`DELETED`) s'écrit à la main.
- `UserProfile.status` est une copie de l'état du compte, écrite par `User` à chaque transition (`setEnabled`, `setEmailVerifiedAt`, `markDeleted`). Les contrôles d'accès lisent le compte lui-même.
- Les associations vers `UserProfile` et `Media` sont `LAZY`. Celles vers `Task` sont `EAGER` (`TaskAttachment.task`, `TaskEvent.task`, `TaskComment.task`, `TaskReminder.task`).
- `@DynamicUpdate` sur `User` et `UserProfile` : le worker de la photo réécrivait toute la ligne et réactivait un compte qu'un admin venait de désactiver.
- L'audit date `created_at` et `updated_at` à l'heure système, pas avec le `Clock` des services. C'est voulu : avec le `Clock`, les tâches créées par `TaskReminderTests` sous une horloge figée en 2100 passaient en tête de toutes les listes « plus récentes d'abord » de la base de test partagée.

Les tests :

- `UserRepositoryTests` (`@RepositoryTest`) : l'email supprimé reste pris, le contrôle natif ignore la casse, la suppression garde la ligne du compte et un profil au statut `DELETED`. `updatingAUserWritesOnlyTheColumnsItChanged` vérifie que l'`UPDATE users` contient `last_active_at` mais pas `enabled`, `email` ni `password_hash`.
- `TaskRepositoryTests` : la référence d'une tâche supprimée reste prise.
- `UserProfileSchemaTests` (`@JdbcSliceTest`) : `users.id` référence `user_profiles.id`, aucune clé étrangère dans l'autre sens, toute référence à un utilisateur vise son profil.

Pour ajouter une entité : étendre `AuditableEntity` si elle a besoin de la suppression logique, un id `@Generated` + `@ColumnDefault("uuidv7()")`, des contraintes nommées, `EAGER` vers `Task` ou `User` et `LAZY` vers `UserProfile`, puis le changeset (guide 18).

## Voir aussi

- [11. Spring Data JPA](11-spring-data-jpa.md) : charger les associations sans N+1.
- [15. Concurrence et verrous](15-concurrence-verrous.md) : ce que protège `@Version`, et le 409.
- [18. Migrations Liquibase](18-migrations-liquibase.md) : le changeset qui correspond à l'entité, et le diff.
- [13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) : atteindre les lignes supprimées logiquement.
- Documentation officielle : [Hibernate ORM 7.4](https://hibernate.org/orm/documentation/7.4/), [audit avec Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/auditing.html).

[Sommaire](README.md) | [< 11. Spring Data JPA](11-spring-data-jpa.md) | [13. SQL avec JdbcTemplate >](13-sql-jdbctemplate.md)

[auditable]: ../../src/main/java/io/julienmetral/tasks/shared/entities/AuditableEntity.java
[auditing]: ../../src/main/java/io/julienmetral/tasks/shared/config/JpaAuditingConfiguration.java
[user]: ../../src/main/java/io/julienmetral/tasks/identity/entities/User.java
[profile]: ../../src/main/java/io/julienmetral/tasks/identity/entities/UserProfile.java
[task]: ../../src/main/java/io/julienmetral/tasks/task/entities/Task.java
[attachment]: ../../src/main/java/io/julienmetral/tasks/task/entities/TaskAttachment.java
[event]: ../../src/main/java/io/julienmetral/tasks/task/entities/TaskEvent.java
[user-repo]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/UserRepository.java
[task-repo]: ../../src/main/java/io/julienmetral/tasks/task/repositories/TaskRepository.java
[lock]: ../../src/main/java/io/julienmetral/tasks/shared/entities/ScheduledJobLock.java
[counter]: ../../src/main/java/io/julienmetral/tasks/ratelimit/entities/RateLimitCounter.java
[reminder]: ../../src/main/java/io/julienmetral/tasks/task/entities/TaskReminder.java
