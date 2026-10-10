# 21. Jobs planifiés et ShedLock

[Sommaire](README.md) | [< 20. Outbox transactionnelle](20-outbox-transactionnelle.md) | [22. Spring Batch >](22-spring-batch.md)

## Contexte

- **Problème** : lancer un traitement à heure fixe, comme une purge chaque nuit ou des rappels toutes les 15 minutes.
- **Quand l'utiliser** : `@Scheduled` pour tout traitement périodique ; ShedLock en plus dès que l'application tourne sur plusieurs instances.
- **Quand s'en passer** : un traitement déclenché par une action (une file, guide 19) ou un très gros volume (Spring Batch, guide 22).
- **Dans ce dépôt** : purges, rétention RGPD, rappels d'échéance, récupération des exports.
- **Prérequis** : guides 14 (transactions) et 15 (verrous). Versions : Spring Boot 4.1, ShedLock 7.10.

Les autres façons d'éviter qu'un job tourne sur chaque instance :

| Solution | Principe | Quand la choisir |
|---|---|---|
| ShedLock | un verrou en base par job | l'application a déjà une base, cas le plus courant |
| Quartz en mode cluster | un planificateur complet, avec ses propres tables | beaucoup de jobs, planifiés dynamiquement |
| CronJob Kubernetes | la plateforme lance un conteneur à heure fixe | jobs lourds, isolés de l'API |
| Verrou consultatif Postgres | `pg_try_advisory_xact_lock` pendant le job | à éviter : libéré au commit, il n'arrête pas une instance en retard |

## Concepts clés

### Vocabulaire

- **Job** : une méthode lancée automatiquement, à heure fixe ou à intervalle régulier.
- **Cron** : une expression qui décrit des heures de passage (« tous les jours à 03:30 »).
- **Verrou** : une ligne en base qui dit « ce job est en cours chez telle instance, jusqu'à telle heure ».
- **Tour** : un passage du job ; un tour sauté ne se rattrape qu'au tour suivant.

### Les déclencheurs

| Déclencheur | Sens | Exemple |
|---|---|---|
| Cron | à des heures précises | tous les jours à 03:30 |
| Délai fixe (fixed delay) | X après la **fin** du tour précédent | 5 s après la fin |
| Cadence fixe (fixed rate) | toutes les X depuis le **début** des tours | toutes les 20 s |

### Le problème des instances multiples

Sans verrou, chaque instance lance le même job au même moment :

```
              04:00
                |
    +-----------+-----------+
    |           |           |
    v           v           v
 instance A  instance B  instance C
 fait le job fait le job fait le job      --> 3 fois le même travail
```

### Le verrou partagé

Avec un verrou par job, rangé dans la base que toutes les instances partagent :

```
              04:00
                |
    +-----------+-----------+
    |           |           |
    v           v           v
 instance A  instance B  instance C
    |           |           |
    |           +-- verrou pris --> saute son tour
    |                       |
    |                       +-- verrou pris --> saute son tour
    v
 prend le verrou --> fait le job --> rend le verrou
```

- On prend le verrou en une seule opération atomique, « prends-le s'il est libre ». La base arbitre, et une seule instance gagne.
- Un verrou expire. Si l'instance qui le tient plante, il se libère tout seul, mais le job n'est pas relancé : il attend son prochain tour.
- **`lockAtMostFor`** est l'heure d'expiration à coup sûr. Elle doit dépasser la durée réelle du job.
- **`lockAtLeastFor`** garde le verrou au moins ce temps, même si le job finit vite.

Ce qui arrive quand une instance plante, avec `lockAtMostFor` :

```
 instance A  --> prend le verrou --> plante
                     |
                     |<------- lockAtMostFor ------->|
                                                     |
                                              verrou libre
 tour suivant, sur n'importe quelle instance --> le job repart
```

Pourquoi `lockAtLeastFor` :

```
 instance A, à l'heure        04:00:00  prend le verrou
                              04:00:02  job fini, rend le verrou
 instance B, 30 s de retard   04:00:30  essaie à son tour

 sans lockAtLeastFor  --> verrou libre depuis 04:00:02 --> B refait le job
 avec 5 min           --> verrou pris jusqu'à 04:05    --> B saute son tour
```

Le déclenchement suit l'horloge de chaque serveur. Le verrou, lui, est daté par l'horloge de la base.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                               dépendances ShedLock (étape 4)
src/main/java/.../
  config/
    SchedulingConfiguration.java      active @Scheduled (étape 1)
    SchedulerLockConfiguration.java   active ShedLock (étape 6)
  invoice/
    InvoicePurgeJob.java              le job (étapes 2 et 7)
    InvoiceService.java               la logique, transactionnelle
src/main/resources/
  application.yaml                    le cron (étape 3)
  db/changelog/...                    la migration de la table (étape 5)
src/test/java/.../
  SchedulerLockTests.java             la vérification (étape 8)
```

### Une seule instance : `@Scheduled`

**Étape 1. Activer la planification.** Une annotation sur une classe de configuration suffit pour toute l'application.

```java
@Configuration
@EnableScheduling
class SchedulingConfiguration {
}
```

**Étape 2. Écrire le job.** La méthode `public` ne fait qu'appeler le service, qui porte la transaction et la logique.

```java
@Slf4j
@Component
@RequiredArgsConstructor
class InvoicePurgeJob {

    private final InvoiceService invoiceService;

    @Scheduled(cron = "${invoices.purge-cron}")
    public void run() {
        int deleted = invoiceService.purge();
        log.info("Invoices: {} purged", deleted);
    }
}
```

**Étape 3. Mettre le cron dans la configuration.** On peut alors le changer sans recompiler.

```yaml
invoices:
  purge-cron: 0 30 3 * * *      # 6 champs, secondes d'abord : chaque jour à 03:30
```

### Plusieurs instances : ShedLock

**Étape 4. Ajouter les dépendances.** Spring Boot ne gère pas la version de ShedLock : on importe son BOM.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>net.javacrumbs.shedlock</groupId>
            <artifactId>shedlock-bom</artifactId>
            <version>7.10.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-spring</artifactId>
    </dependency>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-provider-jdbc-template</artifactId>
    </dependency>
</dependencies>
```

**Étape 5. Créer la table des verrous.** On la crée par une migration (guide 18). ShedLock y insère ensuite une ligne par job, au premier tour.

```sql
CREATE TABLE scheduler_locks (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,     -- sans fuseau, exprès (voir les pièges)
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

**Étape 6. Activer ShedLock et lui donner la base.** Avec `usingDbTime()`, toutes les heures viennent de la base, pas des serveurs.

```java
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
class SchedulerLockConfiguration {

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTableName("scheduler_locks")
                .usingDbTime()
                .build());
    }
}
```

**Étape 7. Poser le verrou sur le job.** Chaque job a un nom unique, et ses deux durées sont écrites explicitement.

```java
@Scheduled(cron = "${invoices.purge-cron}")
@SchedulerLock(name = "invoice-purge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
public void run() {
    invoiceService.purge();
}
```

**Étape 8. Vérifier.** Deux `LockProvider` sur la même base jouent deux instances : le second ne doit pas obtenir le verrou.

```java
@JdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // ShedLock valide dans ses propres transactions
class SchedulerLockTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void aSecondInstanceCannotTakeAHeldLock() {
        LockProvider instanceA = instance("instance-a");
        LockProvider instanceB = instance("instance-b");
        LockConfiguration lock = new LockConfiguration(
                Instant.now(), "test-lock", Duration.ofMinutes(1), Duration.ZERO);

        Optional<SimpleLock> held = instanceA.lock(lock);

        assertThat(held).isPresent();
        assertThat(instanceB.lock(lock)).isEmpty();
        held.get().unlock();
    }

    private LockProvider instance(String name) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTableName("scheduler_locks")
                .usingDbTime()
                .withLockedByValue(name)
                .build());
    }
}
```

### Référence rapide

Les attributs de `@Scheduled` :

| Attribut | Exemple | Remarque |
|---|---|---|
| `cron` | `0 30 3 * * *` | sec, min, heure, jour, mois, jour de la semaine |
| `cron` (raccourci) | `@daily`, `@hourly` | minuit chaque jour, chaque heure pile |
| `fixedDelayString` | `PT5S`, `5m` | délai fixe |
| `fixedRateString` | `PT20S` | cadence fixe |
| `zone` | `Europe/Paris` | fuseau du cron ; sans lui, celui du serveur |

Les threads qui font tourner les jobs :

| Réglage | Comportement |
|---|---|
| Par défaut | un seul thread pour tous les jobs : un job long retarde les autres |
| `spring.task.scheduling.pool.size: 4` | quatre jobs peuvent tourner en même temps |
| `spring.threads.virtual.enabled: true` | chaque tour `cron` ou `fixedRate` a son thread virtuel ; les `fixedDelay` partagent un thread |

Quand un job lève une exception, Spring la log en ERROR, et le job repart au tour suivant.

Limites de cette version : un plantage pendant le job fait perdre le tour, et rien ne signale un tour sauté hors des métriques.

## Concepts avancés

### Sous le capot : le SQL envoyé

Pour prendre le verrou, une seule requête. Une ligne modifiée, c'est gagné ; zéro ligne, le verrou est déjà pris et on saute le tour :

```sql
UPDATE scheduler_locks
SET    lock_until = timezone('utc', CURRENT_TIMESTAMP) + <lockAtMostFor>,
       locked_at  = timezone('utc', CURRENT_TIMESTAMP),
       locked_by  = '<hostname>'
WHERE  name = 'invoice-purge'
AND    lock_until <= timezone('utc', CURRENT_TIMESTAMP);
```

Pour rendre le verrou, l'instance repousse `lock_until` au plus tard entre `locked_at + lockAtLeastFor` et maintenant :

```sql
UPDATE scheduler_locks
SET    lock_until = <le plus tard des deux>
WHERE  name = 'invoice-purge' AND locked_by = '<hostname>';
```

- Après chaque démarrage, le premier essai d'une instance est un `INSERT ... ON CONFLICT DO UPDATE`, au cas où la ligne n'existe pas encore. Ensuite, l'instance retient que la ligne existe et n'envoie plus que l'`UPDATE`.
- Quand deux instances tentent en même temps, la seconde attend que la première valide. Postgres relit alors la condition, et la seconde obtient 0 ligne.
- La ligne n'est jamais supprimée : elle passe de « pris » à « libre » par des `UPDATE`.

### Sous le capot : le proxy Spring

`@EnableSchedulerLock` place un proxy Spring devant le bean du job, comme le fait `@Transactional`. Le planificateur appelle ce proxy, qui prend le verrou, appelle la vraie méthode, puis rend le verrou dans un `finally`. Un appel qui contourne le proxy n'a donc pas de verrou.

### Plusieurs instances : partager un stock de travail

Un verrou sert pour une tâche à faire une fois par tour. Pour un stock de lignes à traiter, chaque instance prend plutôt son propre lot :

```
 outbox_messages : 300 lignes à publier

 instance A  -- FOR UPDATE SKIP LOCKED LIMIT 100 -->  lignes   1 à 100
 instance B  -- FOR UPDATE SKIP LOCKED LIMIT 100 -->  lignes 101 à 200
 instance C  -- FOR UPDATE SKIP LOCKED LIMIT 100 -->  lignes 201 à 300
```

Avec un verrou, une seule instance travaillerait pendant que le retard s'accumule (guide 15).

### Surveiller

Les métriques de ShedLock demandent la dépendance `shedlock-micrometer` et ce bean :

```java
@Bean
MicrometerLockingTaskExecutorListener schedulerLockMetrics(MeterRegistry meterRegistry) {
    return new MicrometerLockingTaskExecutorListener(meterRegistry);
}
```

| Métrique | Sens |
|---|---|
| `shedlock.lock.acquired` | verrou obtenu, le job a tourné |
| `shedlock.lock.not.acquired` | verrou pris ailleurs, tour sauté |
| `shedlock.execution.duration` | durée du travail, pour régler `lockAtMostFor` |
| `tasks.scheduled.execution` (Spring) | chaque tour, avec le tag `outcome=ERROR` quand le job échoue |

Un tour sauté n'apparaît dans les logs qu'en niveau debug.

## Pièges à éviter

### Avec `@Scheduled`

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire un cron à 5 champs (format Unix) | Spring en attend 6 et refuse de démarrer | Ajouter les secondes en tête : `0 30 3 * * *` |
| Oublier le fuseau du cron | il suit celui du serveur, souvent UTC en conteneur | Préciser `zone`, ou raisonner en UTC |
| Garder le thread unique par défaut | un job long retarde tous les autres | Agrandir le pool, ou activer les threads virtuels |
| Appeler `this.run()` depuis la classe | l'appel contourne le proxy : ni verrou, ni transaction | Laisser le planificateur appeler le job |
| Laisser les jobs actifs en test | ils modifient les données des tests en cours | Éteindre les jobs en test et appeler le service |
| Ajouter `@EnableWebSocketMessageBroker` | Boot ne crée plus son `taskScheduler` | Redéclarer `taskScheduler` (guide 25) |

### Avec ShedLock

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Poser `@SchedulerLock` sur une méthode `@Transactional` | le verrou est rendu avant le commit du travail | Garder la transaction dans le service |
| Supprimer une ligne pour débloquer | seuls des `UPDATE` suivent : job sauté jusqu'au redémarrage | Mettre `lock_until` à l'heure UTC (ci-dessous) |
| Écrire `lock_until = now()` à la main | `now()` suit le fuseau de la session SQL : verrou bloqué 2 h de plus | Écrire `timezone('utc', now())` |
| Créer les colonnes en `timestamptz` | l'heure UTC écrite par ShedLock est relue dans le fuseau de la session | Créer les colonnes en `timestamp` |
| Choisir un `lockAtMostFor` trop court | le verrou expire pendant le job, et deux instances le font | Prendre une large marge sur la durée réelle |
| Dépasser l'intervalle avec `lockAtMostFor` | un plantage fait perdre plusieurs tours | Rester sous l'intervalle entre deux tours |
| Déclarer la méthode `private` ou `final` | le proxy ne l'intercepte pas : job sans verrou ni erreur | La déclarer `public`, non `final` |
| Renommer un verrou | anciennes et nouvelles instances ont chacune le leur : tour en double | Garder le nom, ou accepter un doublon |
| Mettre l'état du verrou en cache | une valeur vieille de quelques secondes relance le job | Lire la base à chaque tentative |

Pour débloquer un verrou à la main :

```sql
UPDATE scheduler_locks SET lock_until = timezone('utc', now()) WHERE name = 'invoice-purge';
```

## En résumé

**À retenir :** `@Scheduled` lance le job ; avec plusieurs instances, `@SchedulerLock` sur la méthode du job (jamais sur une méthode transactionnelle) garantit un seul passage par tour, à condition de régler `lockAtMostFor` entre la durée du job et l'intervalle.

- [ ] `@EnableScheduling` présent, cron à 6 champs en propriété, fuseau choisi
- [ ] Une classe `...Job` qui appelle un service transactionnel
- [ ] Le job est idempotent et rattrapable (seuil de date, pas « ceux d'hier »)
- [ ] Table des verrous créée par migration, colonnes `timestamp` sans fuseau
- [ ] `usingDbTime()` dans le `LockProvider`
- [ ] Un nom unique, `lockAtMostFor` et `lockAtLeastFor` explicites sur chaque job
- [ ] Méthode du job `public`, non `final`, jamais appelée directement
- [ ] Les stocks de travail partagés passent par `SKIP LOCKED`, pas par un verrou
- [ ] Une alerte sur `outcome=ERROR` et les métriques ShedLock exposées
- [ ] Les jobs sont éteints dans les tests

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`SchedulingConfiguration`][scheduling] | `@EnableScheduling`, séparée du verrou car certains tests la chargent sans base |
| [`SchedulerLockConfiguration`][lock-config] | `@EnableSchedulerLock`, le `LockProvider`, les métriques |
| [`ScheduledJobLocks`][lock-names] | les noms des verrous, et la liste `NAMES` enregistrée dans les métriques |
| [`ScheduledJobLock`][lock-entity] | entité en lecture seule : sans elle, `liquibase:diff` propose de supprimer la table |
| [`021-scheduler-locks.yaml`][changeset] | la migration de la table |
| [`TaskReminderJob`][reminder-job] | un exemple de job verrouillé |

Les verrous et leurs durées :

| Verrou | Quand | `lockAtMostFor` | `lockAtLeastFor` |
|---|---|---|---|
| `task-reminders` | toutes les 15 min | 14 min | 30 s |
| `data-export-recovery` | 5 min après la fin du tour précédent | 4 min | 30 s |
| `outbox-purge`, `rate-limit-purge` | toutes les heures | 30 min | 1 min |
| `media-cleanup`, `user-retention` | chaque nuit (03:30, 04:00) | 2 h | 5 min |
| `webhook-delivery-purge`, `data-export-purge` | chaque nuit (03:45, 04:30) | 1 h | 5 min |

Les deux pollers sans verrou, qui se partagent leur stock avec `SKIP LOCKED` : `OutboxRelayJob.publishDue` et `WebhookRetryJob.enqueueDueRetries`.

Les tests :

- [`ScheduledJobLocksTest`][lock-test] parcourt tout le code. Il échoue si un `@Scheduled` n'a pas de verrou (hors pollers autorisés), si un nom manque dans `NAMES` ou sert à deux jobs, si une durée manque, si la méthode est `private` ou `final`, ou si un nom dépasse 64 caractères.
- [`SchedulerLockConfigurationTests`][lock-config-test] joue deux instances sur un vrai Postgres : verrou refusé, durée minimale, heures en UTC, course à deux.
- [`ScheduledJobLockingTests`][locking-test] vérifie que le vrai job de purge est sauté quand une autre instance tient son verrou.

Pour ajouter un job : la constante et son entrée dans `NAMES`, puis les deux annotations sur une méthode `public` d'une classe `...Job`. Les jobs sont éteints dans `src/test/resources/config/application.yaml`.

## Voir aussi

- [15. Concurrence et verrous](15-concurrence-verrous.md) : `SKIP LOCKED` et les réservations par `UPDATE`.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : le poller qui tourne sans verrou.
- [25. WebSocket et STOMP](25-websocket-stomp.md) : pourquoi `taskScheduler` est redéclaré.
- [README du dépôt, « Background work »](../../README.md#background-work) : débloquer un verrou en production.
- Documentation officielle : [ShedLock](https://github.com/lukas-krecan/ShedLock), [planification dans Spring](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).

[Sommaire](README.md) | [< 20. Outbox transactionnelle](20-outbox-transactionnelle.md) | [22. Spring Batch >](22-spring-batch.md)

[scheduling]: ../../src/main/java/io/julienmetral/tasks/config/SchedulingConfiguration.java
[lock-config]: ../../src/main/java/io/julienmetral/tasks/config/SchedulerLockConfiguration.java
[lock-names]: ../../src/main/java/io/julienmetral/tasks/config/ScheduledJobLocks.java
[lock-entity]: ../../src/main/java/io/julienmetral/tasks/shared/entities/ScheduledJobLock.java
[changeset]: ../../src/main/resources/db/changelog/changes/021-scheduler-locks.yaml
[reminder-job]: ../../src/main/java/io/julienmetral/tasks/task/services/TaskReminderJob.java
[lock-test]: ../../src/test/java/io/julienmetral/tasks/config/ScheduledJobLocksTest.java
[lock-config-test]: ../../src/test/java/io/julienmetral/tasks/config/SchedulerLockConfigurationTests.java
[locking-test]: ../../src/test/java/io/julienmetral/tasks/config/ScheduledJobLockingTests.java
