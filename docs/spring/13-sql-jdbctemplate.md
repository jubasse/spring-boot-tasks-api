# 13. SQL avec JdbcTemplate

[Sommaire](README.md) | [< 12. Mapping Hibernate 7](12-mapping-hibernate.md) | [14. Transactions >](14-transactions.md)

## Contexte

- **Problème** : JPA ne sait pas écrire certaines instructions SQL (un upsert qui renvoie le nouveau compte, un lot réservé avec `SKIP LOCKED`) et ne voit pas les lignes supprimées logiquement.
- **Quand l'utiliser** : compteurs atomiques, enregistrements « une seule fois », lots partagés entre plusieurs instances, purges et anonymisations sur des lignes supprimées logiquement, rapports.
- **Quand s'en passer** : un CRUD simple sur des entités (guide 11). JPA apporte gratuitement le dirty checking, `@Version`, l'audit et la suppression logique.
- **Dans ce dépôt** : une classe `...Queries` par besoin (`RateLimitQueries`, `TaskReminderQueries`, `WebhookDeliveryQueries`, `UserRetentionQueries`, `MediaCleanupQueries`, `PersonalDataQueries`, `BatchMetadataQueries`).
- **Prérequis** : guides 12 (suppression logique) et 14 (transactions). Versions : Spring Boot 4.1, Spring Framework 7 (`spring-jdbc`), PostgreSQL 18.

Les façons d'envoyer du SQL écrit à la main :

| Outil | Principe | Quand le choisir |
|---|---|---|
| `@Query(nativeQuery = true)` | SQL dans un repository Spring Data | une requête qui renvoie des entités ou un booléen |
| `NamedParameterJdbcTemplate` | SQL et paramètres `:nom`, résultat lu à la main | instructions Postgres, résultats qui ne sont pas des entités |
| `JdbcClient` (Spring 6.1 et plus) | API fluide sur le même moteur | même usage, écriture plus courte |
| `JdbcTemplate` | paramètres `?` positionnels | requêtes très courtes, tests |

## Concepts clés

### Vocabulaire

- **Paramètre nommé** : un `:nom` dans le SQL, lié par son nom et jamais concaténé (pas d'injection SQL).
- **RowMapper** : la fonction qui transforme une ligne de résultat en objet Java (ici un record).
- **Upsert** : « insérer, ou mettre à jour si la ligne existe », en une instruction (`INSERT ... ON CONFLICT`).
- **CTE** : une sous-requête nommée, introduite par `WITH`, qui peut elle-même insérer ou modifier dans Postgres.
- **`RETURNING`** : la clause qui fait renvoyer à un `INSERT`, un `UPDATE` ou un `DELETE` les lignes qu'il a touchées.

### Quand sortir de JPA

| Besoin | JPA | SQL écrit à la main |
|---|---|---|
| lire et modifier une entité | oui, dirty checking | inutile |
| compter sans perdre d'incrément | non : lire puis écrire | `ON CONFLICT DO UPDATE ... RETURNING` |
| savoir si une ligne était nouvelle | non : vérifier puis insérer | `ON CONFLICT DO NOTHING RETURNING` |
| réserver un lot sans attendre les autres | non | `FOR UPDATE SKIP LOCKED` |
| toucher les lignes supprimées logiquement | non : filtrées | SQL natif |
| mettre à jour des milliers de lignes | lent, ligne par ligne | un seul `UPDATE` |

Pourquoi « lire puis écrire » perd des incréments : guide 15.

### Une instruction qui répond

`RETURNING` fait d'une écriture une lecture. L'appelant apprend le nouveau compte, les lignes insérées ou les ids réservés sans seconde requête, donc sans fenêtre entre les deux. Avec `ON CONFLICT DO NOTHING`, il ne renvoie que les lignes réellement insérées, ce qui dit exactement « ce qui est nouveau ».

```
 INSERT ... ON CONFLICT (bucket_key, window_start)
        |
        +-- pas de ligne   --> insère count = 1      --> RETURNING 1
        |
        +-- ligne présente --> DO UPDATE count + 1   --> RETURNING 7
                               (Postgres verrouille la ligne :
                                deux appels ne lisent jamais le même nombre)
```

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  invoice/
    InvoiceQueries.java          lecture, rappels, anonymisation (1, 2, 4, 6)
  quota/
    QuotaQueries.java            le compteur atomique (étape 3)
  print/
    PrintJobQueries.java         la réservation d'un lot (étape 5)
src/test/java/.../
  JdbcSliceTest.java             l'annotation de test (étape 7)
  QuotaQueriesTests.java         les vérifications (étapes 7 et 8)
```

Dans les exemples, `invoices` a les colonnes `id`, `reference`, `due_at`, `paid_at`, `deleted_at`, `anonymized_at` et `customer_name`. `invoice_reminders` a une contrainte unique sur `(invoice_id, due_at)`, `quota_counters` une clé primaire `(bucket_key, window_start)`, et `print_jobs` les colonnes `id`, `status` et `next_attempt_at`. Des migrations créent ces tables (guide 18).

### Une classe de requêtes

**Étape 1. Créer la classe de requêtes.** Une classe regroupe le SQL d'un besoin. Spring Boot fournit déjà `NamedParameterJdbcTemplate` comme bean, et les erreurs SQL arrivent en `DataAccessException`.

```java
@Repository
@RequiredArgsConstructor
public class InvoiceQueries {

    private final NamedParameterJdbcTemplate jdbc;     // créé par Spring Boot à partir de la DataSource
}
```

**Étape 2. Lire des lignes en records.** Un bloc de texte garde le SQL lisible, et une lambda transforme chaque ligne en record.

```java
// dans InvoiceQueries
public record OverdueInvoice(UUID id, String reference, Instant dueAt) {
}

public List<OverdueInvoice> overdueAt(Instant now, int limit) {
    return jdbc.query(
            """
                    SELECT id, reference, due_at
                    FROM invoices
                    WHERE deleted_at IS NULL AND paid_at IS NULL AND due_at < :now
                    ORDER BY due_at
                    LIMIT :limit
                    """,
            new MapSqlParameterSource()
                    .addValue("now", Timestamp.from(now))      // Instant --> Timestamp
                    .addValue("limit", limit),
            (row, index) -> new OverdueInvoice(
                    row.getObject("id", UUID.class),
                    row.getString("reference"),
                    row.getTimestamp("due_at").toInstant()));
}
```

`deleted_at IS NULL` s'écrit à la main (guide 12). Le typage JDBC de Spring ne connaît pas `Instant` (il connaît `OffsetDateTime`), donc le dépôt le convertit chaque fois par `Timestamp.from`.

**Étape 3. Compter sans perdre d'appel.** Une seule instruction insère ou incrémente, et renvoie le compte. Deux appels concurrents ne lisent jamais la même valeur (guide 15).

```java
@Repository
@RequiredArgsConstructor
public class QuotaQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /** @return le nombre d'appels comptés dans la fenêtre, celui-ci compris */
    public int increment(String key, Instant windowStart) {
        Integer count = jdbc.queryForObject(
                """
                        INSERT INTO quota_counters (bucket_key, window_start, count)
                        VALUES (:key, :windowStart, 1)
                        ON CONFLICT (bucket_key, window_start)
                        DO UPDATE SET count = quota_counters.count + 1
                        RETURNING count
                        """,
                new MapSqlParameterSource()
                        .addValue("key", key)
                        .addValue("windowStart", Timestamp.from(windowStart)),
                Integer.class);
        return count == null ? 0 : count;
    }
}
```

`ON CONFLICT (colonnes)` demande une contrainte unique ou une clé primaire sur exactement ces colonnes.

**Étape 4. N'enregistrer qu'une fois, et savoir ce qui est nouveau.** La contrainte unique tranche, et `RETURNING` ne donne que les lignes insérées par cet appel. Un second passage, ou une autre instance, n'obtient rien.

```java
// dans InvoiceQueries
public List<UUID> recordReminders(Instant from, Instant to, Instant now) {
    return jdbc.queryForList(
            """
                    WITH due AS (
                        SELECT id, due_at FROM invoices
                        WHERE deleted_at IS NULL AND paid_at IS NULL
                          AND due_at > :from AND due_at <= :to
                    )
                    INSERT INTO invoice_reminders (invoice_id, due_at, sent_at)
                    SELECT id, due_at, :now FROM due
                    ON CONFLICT (invoice_id, due_at) DO NOTHING
                    RETURNING invoice_id
                    """,
            new MapSqlParameterSource()
                    .addValue("from", Timestamp.from(from))
                    .addValue("to", Timestamp.from(to))
                    .addValue("now", Timestamp.from(now)),
            UUID.class);
}
```

L'appelant n'envoie un rappel que pour les ids renvoyés. Une échéance déplacée forme un nouveau couple `(invoice_id, due_at)`, donc un nouveau rappel.

### Plusieurs instances et lignes cachées

**Étape 5. Réserver un lot sans attendre les autres instances.** Chaque instance prend ses propres lignes. Celles qu'une autre réserve sont sautées, pas attendues, et le bail les tient à l'écart du tour suivant (concept : guide 15).

```java
@Repository
@RequiredArgsConstructor
public class PrintJobQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public List<UUID> claimDue(Instant now, Instant leaseEnd, int limit) {
        return jdbc.queryForList(
                """
                        UPDATE print_jobs
                        SET next_attempt_at = :leaseEnd
                        WHERE id IN (
                            SELECT id FROM print_jobs
                            WHERE status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("leaseEnd", Timestamp.from(leaseEnd))
                        .addValue("limit", limit),
                UUID.class);
    }
}
```

La méthode est appelée dans un service `@Transactional`. Les verrous de lignes durent jusqu'au commit, et le `next_attempt_at` repoussé leur survit.

**Étape 6. Atteindre les lignes supprimées logiquement.** JPA ne les charge jamais, donc une purge ou une anonymisation doit être native. Une collection liée à `:ids` se développe en `IN (?, ?, ...)`.

```java
// dans InvoiceQueries
public List<UUID> anonymizeDeletedBefore(Instant cutoff, Instant now) {
    List<UUID> ids = jdbc.queryForList(
            """
                    UPDATE invoices
                    SET customer_name = 'Deleted customer', anonymized_at = :now
                    WHERE deleted_at < :cutoff AND anonymized_at IS NULL
                    RETURNING id
                    """,
            new MapSqlParameterSource()
                    .addValue("cutoff", Timestamp.from(cutoff))
                    .addValue("now", Timestamp.from(now)),
            UUID.class);

    if (!ids.isEmpty()) {                       // rien à supprimer sinon
        jdbc.update("DELETE FROM invoice_reminders WHERE invoice_id IN (:ids)",
                new MapSqlParameterSource("ids", ids));
    }
    return ids;
}
```

**Étape 7. Vérifier contre un vrai Postgres.** Seule la vraie base vérifie le SQL. Une annotation charge toutes les classes `...Queries` dans un contexte de test partagé (guide 29), et chaque test tourne dans une transaction annulée à sa fin.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JdbcTest(includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Queries"))
@Import(PostgresTestcontainersConfiguration.class)     // le conteneur Postgres (guide 30)
public @interface JdbcSliceTest {
}
```

```java
@JdbcSliceTest
class QuotaQueriesTests {

    // en 2000 : avant tout ce que les autres tests écrivent dans la base partagée
    private static final Instant WINDOW = Instant.parse("2000-06-01T12:00:00Z");

    @Autowired QuotaQueries queries;

    @Test
    void eachCallOfAWindowIncrementsItsCount() {
        String key = UUID.randomUUID().toString();

        assertThat(queries.increment(key, WINDOW)).isEqualTo(1);
        assertThat(queries.increment(key, WINDOW)).isEqualTo(2);
    }
}
```

**Étape 8. Vérifier la concurrence.** Une transaction de test annulée masque la concurrence. Le test en sort, pour que chaque appel valide sur sa propre connexion, comme des requêtes séparées.

```java
// dans QuotaQueriesTests ; jdbc : un JdbcTemplate injecté
@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)     // chaque appel valide sur sa connexion
void concurrentCallsNeverReadTheSameCount() throws Exception {
    String key = UUID.randomUUID().toString();
    List<Future<Integer>> results = new ArrayList<>();

    try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
        for (int i = 0; i < 200; i++) {
            results.add(executor.submit(() -> queries.increment(key, WINDOW)));
        }
        List<Integer> counts = new ArrayList<>();
        for (Future<Integer> result : results) {
            counts.add(result.get(30, TimeUnit.SECONDS));
        }
        assertThat(counts).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, 200).boxed().toList());
    } finally {
        jdbc.update("DELETE FROM quota_counters WHERE bucket_key = ?", key);   // rien n'est annulé : nettoyer
    }
}
```

### Référence rapide

Les méthodes les plus utiles de `NamedParameterJdbcTemplate` :

| Méthode | Renvoie | Usage |
|---|---|---|
| `query(sql, params, rowMapper)` | `List<T>` | plusieurs lignes en objets |
| `queryForObject(sql, params, Integer.class)` | une valeur | un compteur, un `RETURNING count` |
| `queryForList(sql, params, UUID.class)` | `List<T>` | une colonne, souvent un `RETURNING id` |
| `queryForMap(sql, params)` | `Map<String, Object>` | une ligne, dans un test |
| `update(sql, params)` | `int` | lignes modifiées par `INSERT`, `UPDATE`, `DELETE` |
| `update(sql, params, keyHolder)` | `int` | lire des clés générées |

Limites de cette version : rien ne vérifie le SQL à la compilation ni au démarrage. Une faute de frappe se voit au premier appel, d'où un test de slice par classe.

## Concepts avancés

### Sous le capot : la même connexion que JPA

Dans une transaction gérée par `JpaTransactionManager`, `JdbcTemplate` utilise la même connexion JDBC. Les écritures JPA et JDBC valident ou s'annulent donc ensemble. En revanche, `JdbcTemplate` ignore Hibernate et ne voit pas les changements d'entités pas encore écrits. Il faut appeler `repository.flush()` avant une requête JDBC qui doit les voir.

```
 @Transactional service
    |
    +-- invoice.setPaidAt(now)        en mémoire (contexte JPA)
    |
    +-- jdbc.query("... paid_at IS NULL")   même connexion,
    |                                        mais l'UPDATE n'est pas parti
    |                                        --> la facture paraît impayée
    v
 commit : flush de Hibernate, puis COMMIT
```

### Écrire du SQL qui touche un compte

Une instruction native contourne le code de l'entité qui garde les données dérivées à jour. Dans ce dépôt, un SQL natif qui change un compte doit aussi mettre à jour `user_profiles.status` et publier `AccountStateChanged`. Sinon, le statut en cache laisse un compte désactivé actif jusqu'à la fin du TTL (guide 17). `UserRetentionQueries.anonymizeUsersDeletedBefore` écrit ainsi `status = 'DELETED'` sur le profil, dans le même `UPDATE`.

### Plusieurs instances

Chaque motif de ce guide est sûr avec plusieurs instances, car Postgres sérialise les lignes en conflit : l'upsert verrouille la ligne du compteur, `DO NOTHING` s'appuie sur l'index unique, `SKIP LOCKED` sépare les lots. C'est pourquoi `WebhookRetryJob` et `OutboxRelayJob` tournent sur chaque instance sans ShedLock (guide 21).

## Pièges à éviter

### Avec JdbcTemplate

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Concaténer une valeur dans le SQL | une valeur du client devient du SQL : injection | Passer par un paramètre `:nom` |
| Lier un `Instant` tel quel | le pilote Postgres ne sait pas quel type SQL lui donner : erreur | Lier `Timestamp.from(instant)` |
| Oublier `deleted_at IS NULL` | les lignes supprimées logiquement reviennent | L'écrire dans chaque requête native |
| Lire en JDBC une entité modifiée sans flush | l'ancienne valeur revient | Appeler `flush()` avant |
| Lier une liste vide à `IN (:ids)` | Spring écrit `IN ()`, que Postgres refuse | Ne pas lancer la requête quand la liste est vide |
| Écrire un nom de contrainte en casse mixte sans guillemets | Postgres le met en minuscules et ne la trouve pas | L'écrire entre guillemets doubles |

### Avec plusieurs requêtes concurrentes

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lire un compteur puis l'écrire | deux appels lisent la même valeur : un incrément perdu | Un seul `INSERT ... ON CONFLICT ... RETURNING` |
| Vérifier l'existence puis insérer | deux instances insèrent, ou l'une échoue sur la contrainte | `ON CONFLICT DO NOTHING RETURNING` |
| Réserver un lot avec `FOR UPDATE` seul | les autres instances attendent le commit de la première | Ajouter `SKIP LOCKED` |
| Réserver sans repousser la date | le tour suivant reprend les mêmes lignes après le commit | Repousser `next_attempt_at` (bail) dans le même `UPDATE` |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Tester la concurrence dans la transaction du test | une seule connexion sert tout, rien n'est concurrent | Utiliser `NOT_SUPPORTED` et nettoyer |
| Dater les données d'aujourd'hui dans une base partagée | une purge par date touche les lignes des autres tests | Dater les données en 2000 |

## En résumé

**À retenir :** on sort de JPA quand une instruction Postgres fait le travail de façon atomique (upsert, insertion unique, lot réservé par `SKIP LOCKED`) ou quand la suppression logique cache les lignes. On l'écrit dans une classe `...Queries` avec des paramètres nommés, `RETURNING` pour la réponse, et un test de slice contre la vraie base.

- [ ] Une classe `...Queries` par besoin, avec `NamedParameterJdbcTemplate`
- [ ] Paramètres nommés, jamais de concaténation
- [ ] `Instant` converti en `Timestamp`
- [ ] `deleted_at IS NULL` écrit dans chaque requête native qui ne vise pas les supprimés
- [ ] Une seule instruction avec `RETURNING` plutôt que lire puis écrire
- [ ] Une contrainte unique derrière chaque `ON CONFLICT`
- [ ] `SKIP LOCKED` et un bail pour partager un stock entre instances
- [ ] `flush()` avant une lecture JDBC qui doit voir des changements JPA
- [ ] Un test de slice par classe, données datées en 2000, test de concurrence hors transaction

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`RateLimitQueries`][rate-limit] | `INSERT ... ON CONFLICT DO UPDATE ... RETURNING count`, la purge des fenêtres (guide 10) |
| [`TaskReminderQueries`][reminders] | CTE `due`, `ON CONFLICT ON CONSTRAINT ... DO NOTHING RETURNING` : un rappel par tâche, type, échéance et destinataire |
| [`WebhookDeliveryQueries`][webhooks] | `claimDue` : `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id`, et la purge |
| [`UserRetentionQueries`][retention] | anonymise les comptes supprimés (`UPDATE user_profiles ... FROM users`), puis supprime les lignes du compte |
| [`MediaCleanupQueries`][media] | détache et purge les médias des tâches et comptes supprimés, `RETURNING` des clés de stockage |
| [`PersonalDataQueries`][personal] | lit toutes les données d'un compte, tâches supprimées comprises, pour l'export RGPD |
| [`BatchMetadataQueries`][batch] | purge l'historique de Spring Batch (guide 22) |
| [`JdbcSliceTest`][slice] | `@JdbcTest` avec toutes les classes `.*Queries`, Postgres réel |

Ce qui est propre au dépôt :

- Chaque classe `...Queries` est un `@Repository` avec `@RequiredArgsConstructor` et un `NamedParameterJdbcTemplate`. Le SQL est en blocs de texte, avec `MapSqlParameterSource` et `Timestamp.from` pour chaque `Instant`. Les lignes deviennent des records imbriqués (`TaskReminderQueries.Reminder`, `UserRetentionQueries.InactiveUser`).
- Les tables écrites par ces seules classes ont une entité `@Immutable` (`RateLimitCounter`, `TaskReminder`, guide 12), pour que le schéma reste dans le modèle d'Hibernate.
- `TaskReminderQueries` nomme la contrainte entre guillemets, à cause du `UQ` en majuscules : `ON CONFLICT ON CONSTRAINT "task_reminders_task_id_kind_due_at_recipient_idUQ"`. Il saute les tâches supprimées, archivées, sans assigné, ou `DONE`, `CANCELLED`, `ARCHIVED`, et renvoie les rappels triés par échéance puis référence.
- `UserRetentionQueries.anonymizeUsersDeletedBefore` transforme le profil en « Deleted user », statut `DELETED`, sans photo. Il supprime ensuite, pour ces ids, les livraisons et événements de webhook, puis `webhook_endpoints`, `notification_settings`, `refresh_tokens`, `email_verification_tokens`, `password_reset_tokens`, `user_roles`, et enfin les lignes `users`. Tâches et historique gardent leur référence vers le profil. Les requêtes d'inactivité excluent les admins par un `NOT EXISTS` sur `user_roles`.
- `WebhookDeliveryQueries.claimDue` repousse les livraisons réservées à la fin d'un bail (`webhooks.delivery-lease`), pour qu'une tentative plantée redevienne due.

Les tests :

- [`RateLimitQueriesTests`][rate-limit-tests] : fenêtre datée `2000-06-01T12:00:00Z`, et `concurrentRequestsNeverReadTheSameCount` (8 threads de 25 requêtes, sous `NOT_SUPPORTED`, compteur supprimé dans `finally`).
- [`TaskReminderQueriesTests`][reminders-tests] : échéances en 2000 ; un rappel par échéance, une échéance déplacée ou un nouvel assigné en crée un autre, les tâches closes ou supprimées sont sautées.
- [`UserRetentionQueriesTests`][retention-tests] et [`MediaCleanupQueriesTests`][media-tests].
- Un test qui doit valider depuis une autre connexion utilise [`Transactions.inNewTransaction`][transactions] (guide 14).

Pour ajouter une requête : la mettre dans la classe `...Queries` de sa fonctionnalité (son nom finit par `Queries`, pour que `@JdbcSliceTest` la charge), écrire `deleted_at IS NULL` si besoin, puis un test de ce slice avec des données datées en 2000 et des clés uniques.

## Voir aussi

- [15. Concurrence et verrous](15-concurrence-verrous.md) : pourquoi une instruction vaut mieux que lire puis écrire, et ce que garantit `SKIP LOCKED`.
- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : suppression logique et entités `@Immutable`.
- [10. Rate limiting](10-rate-limiting.md) : le compteur en service.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : `SKIP LOCKED` dans un `@Query` de repository.
- [Cas pratique : La rétention RGPD d'un compte](cas-pratiques/retention-rgpd.md) : l'anonymisation en SQL natif, de bout en bout.
- Documentation officielle : [JDBC dans Spring](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html), [`INSERT ... ON CONFLICT` dans PostgreSQL](https://www.postgresql.org/docs/current/sql-insert.html), [`FOR UPDATE SKIP LOCKED`](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE).

[Sommaire](README.md) | [< 12. Mapping Hibernate 7](12-mapping-hibernate.md) | [14. Transactions >](14-transactions.md)

[rate-limit]: ../../src/main/java/io/julienmetral/tasks/ratelimit/repositories/RateLimitQueries.java
[reminders]: ../../src/main/java/io/julienmetral/tasks/task/repositories/TaskReminderQueries.java
[webhooks]: ../../src/main/java/io/julienmetral/tasks/notification/repositories/WebhookDeliveryQueries.java
[retention]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/UserRetentionQueries.java
[media]: ../../src/main/java/io/julienmetral/tasks/media/repositories/MediaCleanupQueries.java
[personal]: ../../src/main/java/io/julienmetral/tasks/export/personaldata/PersonalDataQueries.java
[batch]: ../../src/main/java/io/julienmetral/tasks/export/repositories/BatchMetadataQueries.java
[slice]: ../../src/test/java/io/julienmetral/tasks/support/JdbcSliceTest.java
[rate-limit-tests]: ../../src/test/java/io/julienmetral/tasks/ratelimit/repositories/RateLimitQueriesTests.java
[reminders-tests]: ../../src/test/java/io/julienmetral/tasks/task/repositories/TaskReminderQueriesTests.java
[retention-tests]: ../../src/test/java/io/julienmetral/tasks/identity/repositories/UserRetentionQueriesTests.java
[media-tests]: ../../src/test/java/io/julienmetral/tasks/media/repositories/MediaCleanupQueriesTests.java
[transactions]: ../../src/test/java/io/julienmetral/tasks/support/Transactions.java
