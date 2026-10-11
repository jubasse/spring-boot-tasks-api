# 18. Migrations Liquibase

[Sommaire](README.md) | [< 17. Cache Caffeine](17-cache-caffeine.md) | [19. RabbitMQ avec Spring AMQP >](19-rabbitmq-spring-amqp.md)

## Contexte

- **Problème** : le schéma doit évoluer avec le code, dans le même ordre sur chaque machine et chaque environnement, et les entités doivent lui correspondre.
- **Quand l'utiliser** : tout projet à base relationnelle qui vit plus longtemps qu'un prototype.
- **Quand s'en passer** : ne jamais laisser Hibernate créer le schéma hors des tests jetables. `ddl-auto: create` ou `update` ne renomme pas, ne migre pas les données et ne se relit pas.
- **Dans ce dépôt** : 24 fichiers YAML de changesets sous `db/changelog/changes/`, inclus par un fichier maître. Hibernate ne fait que valider.
- **Prérequis** : guide 12 (mapping). Versions : Spring Boot 4.1, Liquibase 5.0.4 (plugin Maven et `liquibase-hibernate7` 5.0.4), Hibernate 7.4, PostgreSQL 18.

Les façons de faire évoluer un schéma :

| Outil | Principe | Quand le choisir |
|---|---|---|
| Liquibase | changesets YAML, XML ou SQL, suivis par id et somme de contrôle | diff depuis les entités, plusieurs bases |
| Flyway | fichiers SQL numérotés `V1__...sql` | équipe qui préfère écrire tout le SQL |
| `ddl-auto: update` | Hibernate ajoute ce qui manque | prototype seulement : ni renommage, ni données, ni relecture |

## Concepts clés

### Vocabulaire

- **Migration** : un changement versionné du schéma (ou de ses données), appliqué une fois par base.
- **Changelog** : le fichier qui liste les changesets. Le changelog maître inclut les autres.
- **Changeset** : une unité de changement, identifiée par son `id`, son `author` et le chemin de son fichier.
- **Somme de contrôle** (checksum) : un hash d'un changeset appliqué, gardé pour détecter une modification ultérieure.
- **Diff** : la comparaison entre deux schémas (ici les entités et une vraie base), qui écrit les changesets manquants.

### Le suivi des changesets

Liquibase crée deux tables dans la base :

- `DATABASECHANGELOG` : une ligne par changeset appliqué (id, auteur, fichier, somme de contrôle).
- `DATABASECHANGELOGLOCK` : une ligne qui n'autorise qu'une mise à jour Liquibase à la fois. Une autre instance attend (« Waiting for changelog lock.... »).

À chaque passage, Liquibase applique un changeset d'identité inconnue. Il compare un changeset connu par somme de contrôle. Si elle diffère, quelqu'un l'a modifié et Liquibase s'arrête avec une erreur (sauf `runOnChange` ou `runAlways`). `includeAll` inclut les fichiers du dossier dans l'ordre alphabétique, d'où le préfixe `NNN-`.

```
 démarrage
    |
    v
 verrou DATABASECHANGELOGLOCK (une instance à la fois)
    |
    v
 pour chaque changeset, dans l'ordre des fichiers :
    |
    +-- inconnu dans DATABASECHANGELOG --> appliqué, ligne ajoutée
    |
    +-- connu, même somme de contrôle  --> ignoré
    |
    +-- connu, somme différente        --> erreur : le démarrage s'arrête
```

### Au démarrage de Spring Boot

Avec `spring-boot-starter-liquibase`, Boot lance Liquibase sur la `DataSource` de l'application avant de créer l'`EntityManagerFactory` de JPA. Hibernate, avec `ddl-auto: validate`, vérifie ensuite chaque entité contre le schéma migré. Une table ou une colonne manquante, ou un type incompatible, arrête le démarrage. Une entité modifiée sans son changeset échoue donc au premier démarrage, en test aussi.

```
 DataSource
    |
    v
 Liquibase : applique les nouveaux changesets
    |
    v
 Hibernate (ddl-auto: validate) : entités == schéma ?
    |
    +-- non --> démarrage refusé (table ou colonne manquante)
    |
    v
 application prête
```

### Écrit à la main ou généré

- **Généré** par `liquibase:diff` (les entités comparées à une base migrée) : types et nullabilité tels qu'Hibernate les attend, mais ids génériques et angles morts (voir Concepts avancés).
- **Écrit à la main** : migrations de données, SQL que Liquibase ne sait pas générer, tables sans entité, schéma d'une bibliothèque.
- Dans les deux cas, le fichier se relit comme du code et ne bouge plus une fois appliqué.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                     dépendance et plugin (étapes 1 et 4)
src/main/resources/
  application.yaml                          validate (étape 3)
  liquibase.properties                      base locale, sans secret (étape 4)
  db/changelog/
    db.changelog-master.yaml                le changelog maître (étape 1)
    changes/
      001-invoices.yaml                     écrit à la main (étape 2)
      002-invoices-due-at.yaml              généré puis relu (étapes 5 et 6)
      003-backfill-due-at.yaml              migration de données (étape 7)
src/test/java/.../
  InvoiceSchemaTests.java                   la vérification (étape 8)
```

### Un schéma versionné

**Étape 1. Ajouter Liquibase et créer le changelog maître.** Avec le starter, Boot lance les migrations à chaque démarrage, avant JPA. Un seul fichier, au chemin que Boot lit par défaut, inclut tous les changesets du dossier.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-liquibase</artifactId>
</dependency>
```

```yaml
# src/main/resources/db/changelog/db.changelog-master.yaml
databaseChangeLog:
    - includeAll:
          path: db/changelog/changes
```

`spring.liquibase.change-log` vaut par défaut `classpath:/db/changelog/db.changelog-master.yaml`.

**Étape 2. Écrire un changeset.** Un `id` lisible, un vrai `author`, un commentaire qui dit pourquoi, et chaque contrainte nommée.

```yaml
# src/main/resources/db/changelog/changes/001-invoices.yaml
databaseChangeLog:
- changeSet:
    id: 001-create-invoices
    author: jane-doe
    comment: Invoices of the customers, soft-deleted (deleted_at).
    changes:
    - createTable:
        tableName: invoices
        columns:
        - column:
            name: id
            type: UUID
            defaultValueComputed: uuidv7()        # PostgreSQL 18, relu par @Generated (guide 12)
            constraints:
              nullable: false
              primaryKey: true
              primaryKeyName: invoicesPK
        - column:
            name: reference
            type: VARCHAR(30)
            constraints:
              nullable: false
        - column:
            name: customer_id
            type: UUID
            constraints:
              nullable: false
        - column:
            name: created_at
            type: TIMESTAMP(6) WITH TIME ZONE
        - column:
            name: deleted_at
            type: TIMESTAMP(6) WITH TIME ZONE
    - addUniqueConstraint:
        tableName: invoices
        columnNames: reference
        constraintName: invoices_referenceUQ
    - addForeignKeyConstraint:
        baseTableName: invoices
        baseColumnNames: customer_id
        constraintName: invoices_customerFK
        referencedTableName: customers
        referencedColumnNames: id
```

**Étape 3. Valider le schéma au démarrage.** Hibernate compare les entités à ce que Liquibase a construit, au lieu de modifier le schéma lui-même. Le défaut de `ddl-auto` est `none` avec une vraie base, donc `validate` doit être écrit.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  liquibase:
    change-log: classpath:/db/changelog/db.changelog-master.yaml   # la valeur par défaut, écrite pour la lisibilité
```

### Générer les changesets depuis les entités

**Étape 4. Configurer le plugin Maven.** `liquibase-hibernate7` permet au plugin de lire les entités comme une « base de référence ». Les tables de Spring Batch n'ont pas d'entité. On les exclut, sinon le diff proposerait de les supprimer.

```xml
<plugin>
    <groupId>org.liquibase</groupId>
    <artifactId>liquibase-maven-plugin</artifactId>
    <version>${liquibase.version}</version>
    <configuration>
        <propertyFile>liquibase.properties</propertyFile>
        <changeLogFile>db/changelog/db.changelog-master.yaml</changeLogFile>
        <diffExcludeObjects>table:batch_.*, sequence:batch_.*</diffExcludeObjects>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.liquibase.ext</groupId>
            <artifactId>liquibase-hibernate7</artifactId>
            <version>${liquibase-hibernate.version}</version>
        </dependency>
        <!-- et le driver PostgreSQL, spring-context, spring-orm, jakarta.validation-api -->
    </dependencies>
</plugin>
```

```properties
# src/main/resources/liquibase.properties : pas de mot de passe, on le passe en ligne de commande
url=jdbc:postgresql://localhost:5432/invoices
username=invoices
driver=org.postgresql.Driver
```

**Étape 5. Générer le diff.** On compare les entités compilées à une base qui a déjà toutes les migrations, et le plugin écrit ce qui manque.

```bash
./mvnw compile                     # le diff lit les entités dans target/classes
./mvnw liquibase:diff \
  -Dliquibase.url=jdbc:postgresql://localhost:5432/invoices -Dliquibase.username=... -Dliquibase.password=... \
  "-Dliquibase.referenceUrl=hibernate:spring:com.example?dialect=org.hibernate.dialect.PostgreSQLDialect&hibernate.physical_naming_strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl" \
  -Dliquibase.diffChangeLogFile=src/main/resources/db/changelog/changes/002-invoices-due-at.yaml
```

- `hibernate:spring:<paquet>` fait scanner les entités de ce paquet.
- La stratégie de nommage physique doit produire les noms de Boot (`PhysicalNamingStrategySnakeCaseImpl` en 4.1). Sans elle, Hibernate garde les noms Java en camelCase et toutes les colonnes semblent différentes.
- Un conteneur Postgres jetable, migré, convient comme base cible.

**Étape 6. Relire le changeset généré.** Le diff écrit des ids horodatés et un auteur « (generated) ». Il ne voit pas les contraintes ni les index renommés. Tel que généré :

```yaml
- changeSet:
    id: 1790251254578-1
    author: jane-doe (generated)
    changes:
    - addColumn:
        tableName: invoices
        columns:
        - column:
            name: due_at
            type: TIMESTAMP(6) WITH TIME ZONE
```

Après relecture :

```yaml
- changeSet:
    id: 002-add-invoices-due-at
    author: jane-doe
    comment: Due date of an invoice, read by the reminder job.
    changes:
    - addColumn:
        tableName: invoices
        columns:
        - column:
            name: due_at
            type: TIMESTAMP(6) WITH TIME ZONE
    - createIndex:
        tableName: invoices
        indexName: invoices_due_atIDX
        columns:
        - column:
            name: due_at
```

À vérifier à la relecture :

- un `id` lisible (`NNN-verbe-objet`) et un vrai `author` ;
- chaque contrainte et chaque index nommés selon la convention ;
- rien de supprimé par surprise (une table sans entité, une clé étrangère après un changement de mapping) ;
- un `comment` qui dit pourquoi.

**Étape 7. Faire évoluer sans réécrire.** Un changeset appliqué est figé par sa somme de contrôle. Une correction ou un changement de données est donc un nouveau changeset.

```yaml
# 003-backfill-due-at.yaml
databaseChangeLog:
- changeSet:
    id: 003-backfill-invoices-due-at
    author: jane-doe
    comment: Existing invoices get a due date 30 days after their creation.
    changes:
    - sql:
        sql: >
          UPDATE invoices SET due_at = created_at + INTERVAL '30 days'
          WHERE due_at IS NULL
```

**Étape 8. Vérifier.** Chaque contexte de test migre une vraie base Postgres (guide 30), et la validation contrôle les entités. Un test de schéma contrôle ce que la validation ne voit pas, comme les noms de contraintes.

```java
@JdbcTest
@Import(PostgresTestcontainersConfiguration.class)     // Postgres réel, migré par Liquibase (guide 30)
class InvoiceSchemaTests {

    @Autowired JdbcTemplate jdbc;

    @Test
    void invoicesReferenceTheirCustomerThroughANamedForeignKey() {
        List<String> names = jdbc.queryForList("""
                SELECT con.conname FROM pg_constraint con
                JOIN pg_class src ON src.oid = con.conrelid
                WHERE src.relname = 'invoices' AND con.contype = 'f'
                """, String.class);

        assertThat(names).contains("invoices_customerFK");
    }
}
```

En manuel : `SELECT id, author, filename FROM databasechangelog ORDER BY orderexecuted;`.

### Référence rapide

Les types de changement utilisés dans ce dépôt :

| Type de changement | Rôle |
|---|---|
| `createTable`, `addColumn`, `dropColumn` | tables et colonnes |
| `addPrimaryKey`, `addUniqueConstraint`, `addForeignKeyConstraint` | contraintes nommées |
| `createIndex` | index nommé |
| `dropDefaultValue`, `dropForeignKeyConstraint`, `dropUniqueConstraint` | défaire avant de refaire |
| `sql` | SQL libre : données, schéma copié d'une bibliothèque |

Les propriétés de Spring Boot :

| Propriété | Défaut | Rôle |
|---|---|---|
| `spring.liquibase.enabled` | `true` | lancer Liquibase au démarrage |
| `spring.liquibase.change-log` | `classpath:/db/changelog/db.changelog-master.yaml` | le changelog maître |
| `spring.liquibase.contexts` | aucun | n'appliquer que les changesets de ces contextes |
| `spring.liquibase.drop-first` | `false` | vider le schéma avant : jamais hors d'un poste local |
| `spring.jpa.hibernate.ddl-auto` | `none` (base réelle) | `validate` : comparer sans modifier |

Limites de cette version : aucun retour arrière n'est écrit. Une mauvaise migration en production se corrige par un nouveau changeset.

## Concepts avancés

### Sous le capot : ce que le diff ne voit pas

- `liquibase:diff` compare les index et les clés étrangères par table et colonnes, pas par nom. Une contrainte ou un index renommé n'est pas signalé (vérifié le 2026-09-27), alors qu'une contrainte manquante ou une colonne modifiée l'est. On vérifie donc les noms à la main, contre la convention.
- Il lit les entités dans `target/classes`. Sans compilation préalable, il compare l'ancien code.
- Il écrit ce que les entités impliquent, y compris la vision d'Hibernate sur le mapping.

Ce que le mapping fait dire au diff :

| Mapping | Ce que le diff propose | Guide |
|---|---|---|
| table sans entité | la supprimer | ci-dessous |
| `@NotFound(IGNORE)` | supprimer la clé étrangère | 12 |
| `@OneToOne` | une contrainte unique au nom généré | 12 |
| colonne `deleted_at` de `@SoftDelete` | la créer, avec la remarque « Soft-delete indicator » | 12 |

### Les tables sans entité

Deux remèdes, selon le propriétaire de la table :

- Les tables d'une bibliothèque (Spring Batch) : les exclure du diff (`diffExcludeObjects: table:batch_.*, sequence:batch_.*`).
- Les tables de l'application écrites en SQL seulement (verrous ShedLock, compteurs de rate limit, rappels envoyés) : une entité `@Immutable` en lecture seule les garde dans le modèle d'Hibernate. La validation les contrôle et le diff les laisse tranquilles (guide 12).

Le schéma d'une bibliothèque se copie dans un changeset. Sinon, une montée de version changerait le script, donc la somme de contrôle (changeset 023, guide 22).

### Plusieurs instances

- Si plusieurs instances démarrent ensemble, `DATABASECHANGELOGLOCK` en laisse une appliquer les changesets. Les autres attendent le verrou, puis n'ont plus rien à faire.
- Un changeset doit rester compatible avec la version encore en marche pendant un déploiement progressif : on ajoute une colonne d'abord, on l'utilise à la version suivante, on supprime l'ancienne plus tard.

### Les tests et une base réutilisée

Chaque contexte d'intégration ou de tranche migre sa base au démarrage. Avec la réutilisation de Testcontainers (guide 30), la base de test garde son `DATABASECHANGELOG` d'un lancement à l'autre, d'une branche à l'autre.

Modifier un changeset appliqué, ou changer de branche pour d'autres migrations, fait échouer le lancement suivant sur une somme de contrôle. On supprime alors les conteneurs de test et on relance (README, « Tests fail at startup with a Liquibase checksum error »).

## Pièges à éviter

### En écrivant les changesets

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Modifier un changeset déjà appliqué | somme de contrôle différente : le démarrage s'arrête | Ajouter un nouveau changeset |
| Renommer l'`id` ou l'`author` d'un changeset appliqué | Liquibase le croit nouveau et le rejoue | Garder l'identité ; ne renommer que les nouveaux |
| Numéroter deux fichiers pareil ou sans zéros | l'ordre alphabétique place `10-` avant `2-` | Préfixer `NNN-` sur trois chiffres |
| Laisser Hibernate créer le schéma (`update`) | ni renommage, ni données, ni relecture | `ddl-auto: validate` et des changesets |
| Référencer le script SQL d'une bibliothèque | une montée de version change la somme de contrôle | Copier le script dans un changeset |
| Mettre le mot de passe dans `liquibase.properties` | il part dans Git | Le passer avec `-Dliquibase.password` |

### En générant avec `liquibase:diff`

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lancer le diff sans compiler | il compare l'ancienne version des entités | `./mvnw compile` d'abord |
| Comparer à une base pas à jour | le diff recopie des changesets déjà écrits | Appliquer toutes les migrations à la base cible |
| Faire confiance aux noms | un renommage de contrainte ou d'index n'est pas signalé | Vérifier les noms à la main |
| Garder une table sans entité ni exclusion | le diff propose de la supprimer | Entité `@Immutable`, ou `diffExcludeObjects` |
| Oublier la stratégie de nommage de Boot | les noms restent en camelCase : tout semble différent | Passer `PhysicalNamingStrategySnakeCaseImpl` |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Réutiliser la base de test après avoir modifié une migration | erreur de somme de contrôle au démarrage | Supprimer les conteneurs de test |

## En résumé

**À retenir :** Liquibase applique des changesets numérotés au démarrage, et Hibernate ne fait que valider. On génère depuis les entités quand c'est utile, mais on relit chaque fichier, on nomme chaque contrainte à la main et on ne touche jamais un changeset appliqué.

- [ ] `spring-boot-starter-liquibase`, un changelog maître avec `includeAll`
- [ ] Fichiers `NNN-nom.yaml`, `id` lisible, `author` réel, `comment` qui dit pourquoi
- [ ] `ddl-auto: validate`
- [ ] Contraintes et index nommés selon la convention, vérifiés à la main
- [ ] Compiler avant `liquibase:diff`, sur une base à jour
- [ ] Tables sans entité exclues du diff ou gardées par une entité `@Immutable`
- [ ] Jamais de modification d'un changeset appliqué : un nouveau changeset
- [ ] Pas de mot de passe dans `liquibase.properties`
- [ ] Un test de schéma pour ce que la validation ne voit pas

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`db.changelog-master.yaml`][master] | un seul `includeAll` sur `db/changelog/changes` (24 fichiers, de `001` à `024`) |
| [`001-users.yaml`][users] | généré depuis les entités : ids `1790251247142-N`, auteur `julien-metral (generated)`, gardés tels quels |
| [`016-user-profiles.yaml`][profiles] | migration de données et de clés étrangères, puis un second changeset correctif |
| [`021-scheduler-locks.yaml`][locks] | table écrite par ShedLock seul, colonnes sans fuseau (guide 21) |
| [`023-batch-metadata.yaml`][batch] | copie du `schema-postgresql.sql` de Spring Batch 6.0.5, en `sql` |
| [`liquibase.properties`][properties] | `url`, `username`, `driver` de la base locale, sans mot de passe |
| [`pom.xml`][pom] | `liquibase-maven-plugin` 5.0.4, `liquibase-hibernate7`, `diffExcludeObjects` pour Batch |
| [`UserProfileSchemaTests`][schema-test] | vérifie dans `pg_constraint` le sens et le nom des clés étrangères |

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.liquibase.enabled` | `true` | migrations à chaque démarrage |
| `spring.liquibase.change-log` | `classpath:/db/changelog/db.changelog-master.yaml` | le changelog maître |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate compare, ne modifie jamais |
| `-Dspring.liquibase.enabled=false` (`Dockerfile`) | `false` | l'entraînement AOT ne touche aucune base (guide 33) |

La commande de génération du dépôt :

```bash
./mvnw liquibase:diff \
  -Dliquibase.url=jdbc:postgresql://localhost:5432/tasks -Dliquibase.username=... -Dliquibase.password=... \
  "-Dliquibase.referenceUrl=hibernate:spring:io.julienmetral.tasks?dialect=org.hibernate.dialect.PostgreSQLDialect&hibernate.physical_naming_strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl" \
  -Dliquibase.diffChangeLogFile=src/main/resources/db/changelog/changes/NNN-name.yaml
```

La commande nomme `PhysicalNamingStrategySnakeCaseImpl`, la stratégie par défaut de Boot 4, pour que le diff voie les mêmes noms de colonnes que l'application. `CamelCaseToUnderscoresNamingStrategy`, dépréciée pour suppression en Hibernate 7, ne doit plus servir.

Ce qui est propre au dépôt :

- La convention de nommage : `<table>_<column>UQ`, `<table>_<column>FK`, `<table>PK`. Exemples : `users_emailUQ`, `tasks_referenceUQ`, `user_roles_userFK`, `tasksPK`, `rate_limit_countersPK`. Les index suivent `<table>_<column>IDX`.
- Les clés primaires valent `uuidv7()` par défaut dans les changesets (`defaultValueComputed: uuidv7()`), comme `@ColumnDefault("uuidv7()")` sur les entités (guide 12).
- Les changesets 001 à 003 gardent leur id et leur auteur générés, car les renommer les ferait rejouer. Les suivants ont des ids lisibles (`014-create-outbox-messages`, `024-create-data-exports`).
- `016-user-profiles.yaml` déplace les données de profil de `users` vers `user_profiles`, repointe toutes les clés étrangères, et supprime les comptes déjà anonymisés. Un second changeset, `016-clear-photos-of-anonymized-profiles`, corrige le premier au lieu de le modifier : les photos des comptes anonymisés avaient été copiées et ne seraient jamais purgées.
- Les tables écrites en SQL seulement gardent une entité `@Immutable` : `ScheduledJobLock` (021), `RateLimitCounter` (015), `TaskReminder` (011). Les tables de Spring Batch (023) sont exclues du diff dans le `pom.xml`.
- Une fois, 7 clés étrangères ont disparu du modèle à cause de `@NotFound(IGNORE)` (guide 12).

Les tests : chaque contexte `@IntegrationTest`, `@JdbcSliceTest` et `@RepositoryTest` migre le Postgres partagé avant la validation d'Hibernate. `UserProfileSchemaTests` contrôle le sens et le nom des clés étrangères.

Pour ajouter une migration : modifier l'entité, `./mvnw compile`, lancer le diff sur une base qui a toutes les migrations, renommer le fichier `NNN-name.yaml` et ses changesets (`id`, `author`, `comment`), vérifier les noms contre la convention, puis lancer les tests.

## Voir aussi

- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : le côté entité des contraintes, `uuidv7` et `@Immutable`.
- [22. Spring Batch](22-spring-batch.md) : pourquoi le schéma de Batch est copié dans un changeset.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : la table des verrous et ses colonnes sans fuseau.
- [30. Testcontainers](30-testcontainers.md) : la base de test réutilisée et ses migrations.
- Documentation officielle : [`includeAll` dans Liquibase](https://docs.liquibase.com/change-types/includeall.html), [migrations avec Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).

[Sommaire](README.md) | [< 17. Cache Caffeine](17-cache-caffeine.md) | [19. RabbitMQ avec Spring AMQP >](19-rabbitmq-spring-amqp.md)

[master]: ../../src/main/resources/db/changelog/db.changelog-master.yaml
[users]: ../../src/main/resources/db/changelog/changes/001-users.yaml
[profiles]: ../../src/main/resources/db/changelog/changes/016-user-profiles.yaml
[locks]: ../../src/main/resources/db/changelog/changes/021-scheduler-locks.yaml
[batch]: ../../src/main/resources/db/changelog/changes/023-batch-metadata.yaml
[properties]: ../../src/main/resources/liquibase.properties
[pom]: ../../pom.xml
[schema-test]: ../../src/test/java/io/julienmetral/tasks/identity/repositories/UserProfileSchemaTests.java
