# 22. Spring Batch

[Sommaire](README.md) | [< 21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) | [23. Envoi de mails >](23-envoi-mails.md)

## Contexte

- **Problème** : produire un gros fichier, des centaines de milliers de lignes, sans tenir une requête HTTP ouverte, et reprendre après un plantage.
- **Quand l'utiliser** : un traitement long et volumineux, découpé en paquets, à suivre et relancer (export, import, recalcul).
- **Quand s'en passer** : un petit fichier (réponse en streaming), une purge en une requête SQL (guide 21) ou un traitement par message (guide 19).
- **Dans ce dépôt** : les exports CSV des tâches et des utilisateurs, et l'export RGPD de chaque compte.
- **Prérequis** : guides 14 (transactions), 20 (outbox) et 21 (jobs planifiés). Versions : Spring Boot 4.1, Spring Batch 6.0.

Façons de produire un gros fichier :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Réponse en streaming | le contrôleur écrit le fichier dans la réponse | fichier petit ou moyen |
| `@Async` et code maison | un thread lit, écrit puis stocke le fichier | traitement simple, sans reprise |
| Spring Batch | un job en steps et chunks, suivi en base | gros volumes, reprise, historique |
| Programme externe (CronJob, ETL) | un autre processus travaille | traitement lourd, isolé de l'API |

## Concepts clés

### Vocabulaire

- **Job** : le traitement complet, une suite de steps.
- **Step** : une étape du job, par chunks ou par tasklet.
- **Chunk** : un paquet d'éléments lus, transformés puis écrits en une transaction.
- **Tasklet** : un step d'un seul bloc, pour une action unique (stocker un fichier).
- **JobRepository** : la mémoire de Batch, dans les tables `batch_*` : ce qui a tourné, avec quels paramètres, jusqu'où.
- **Paramètres de job** : les valeurs qui identifient une exécution. Mêmes paramètres, même instance.

### Pourquoi pas dans la requête HTTP

Avec `StreamingResponseBody`, Spring MVC écrit la réponse sur un autre thread, ligne après ligne. C'est suffisant pour quelques secondes, mais une erreur au milieu est mal gérée :

```
  Navigateur                       API                            Base
      |                             |                               |
      |---- GET /invoices.csv ----->|                               |
      |                             |---- SELECT ... --------------->|
      |<---- 200 et en-têtes -------|                               |
      |<---- lignes 1 à 10000 ------|<---- lignes -------------------|
      |                             x  erreur à la ligne 50000      |
      |<---- connexion coupée ------|                               |
      |                                                             |
   fichier tronqué, alors que le statut 200 est déjà parti
```

- Une erreur en route ne peut plus changer le statut. Le client garde un fichier tronqué qui paraît valide.
- Pas de progression, une coupure réseau fait tout recommencer, et la requête tient un thread, une connexion à la base et le délai du reverse proxy.

Pour un gros fichier, on répond tout de suite et le travail continue en arrière-plan :

```
  POST /exports  -->  202 Accepted et Location     la demande est enregistrée
                            |
                            v
                   job en arrière-plan  -->  fichier stocké  -->  mail « prêt »

  GET /exports/{id}  -->  QUEUED, RUNNING, COMPLETED, lien de téléchargement
```

### Le modèle de Spring Batch

Un job enchaîne des steps. Un step par chunks répète « lire, transformer, écrire » jusqu'à la fin des données :

```
  Job "invoiceExport"
    |
    +-- Step 1 "invoiceCsvWrite" : chunks de 500
    |      |
    |      |   une transaction par chunk
    |      |   +--------+      +-----------+      +--------+
    |      |   | reader | ---> | processor | ---> | writer |
    |      |   +--------+      +-----------+      +--------+
    |      |   lit 500 lignes  transforme,        écrit les 500,
    |      |                   écarte (null)      puis commit
    |      v
    +-- Step 2 "invoicePublish" : tasklet
           stocke le fichier, marque l'export prêt
                     |
                     v
  JobRepository (tables batch_*) : statuts, compteurs, position de reprise
```

- Le reader rend un élément par appel, puis `null` en fin de données. Le processor, facultatif, rend `null` pour écarter un élément. Le writer reçoit le chunk entier.
- Le `JobOperator` lance, relance et récupère les jobs.
- `@StepScope` crée le bean au début de chaque step, qui peut alors lire les paramètres du job (`#{jobParameters['exportId']}`).

### Instance, exécution et reprise

Une instance de job est un nom de job plus ses paramètres. Une exécution est une tentative de cette instance. Au lancement, Batch consulte l'historique :

```
  start(job, exportId=42)
          |
          v
  instance "invoiceExport, exportId=42" connue ?
          |
          +-- non ------------------------> nouvelle instance, exécution n°1
          |
          +-- oui, dernière COMPLETED ----> refus : déjà terminée
          |
          +-- oui, dernière STARTED ------> refus : déjà en cours
          |
          +-- oui, dernière FAILED -------> exécution n°2, même instance :
                                            steps COMPLETED sautés,
                                            step en échec repris à sa position
```

- Un arrêt brutal laisse l'exécution en `STARTED`, que Batch croit en cours. `JobOperator.recover` la passe en `FAILED`, et la relance devient possible.
- `allowStartIfComplete(true)` rejoue un step terminé dont le résultat a disparu, comme un fichier local.

### Ce que Spring Batch fournit

Batch apporte le moteur : la boucle par chunks, l'historique et la position de reprise dans `batch_*`, des readers et writers prêts, les sauts et réessais (`faultTolerant()`), les listeners et les observations Micrometer. L'application écrit le reste : déclenchement, état visible, format du fichier, détection d'une instance arrêtée, stockage, notification et purge de l'historique.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                    la dépendance (étape 1)
src/main/resources/
  application.yaml                         les réglages Batch (étape 2)
  db/changelog/...                         les tables batch_* (étape 2)
src/main/java/.../invoice/
  InvoiceExportJobConfiguration.java       reader, writer, steps, job (3-6)
  Csv.java                                 une ligne CSV sûre (étape 4)
  InvoiceExportListener.java               le nettoyage après le job (étape 6)
  InvoiceExportLauncher.java               le lancement (étape 7)
src/test/java/.../
  InvoiceExportJobTests.java               la vérification (étape 8)
```

### Un export CSV en arrière-plan

**Étape 1. Ajouter la dépendance.** Le starter `-jdbc` range l'historique en base. Sans lui, la reprise est impossible.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch-jdbc</artifactId>
</dependency>
```

**Étape 2. Créer les tables, régler Batch.** Le script `org/springframework/batch/core/schema-postgresql.sql` du jar `spring-batch-core` crée six tables et trois séquences. On le copie dans une migration (guide 18), pour qu'une montée de version de Batch ne change pas la somme de contrôle.

```yaml
spring:
  batch:
    job:
      enabled: false                             # aucun job lancé au démarrage
    jdbc:
      initialize-schema: never                   # les tables viennent de la migration
      isolation-level-for-create: read-committed # voir les pièges
```

**Étape 3. Écrire le reader.** Il lit par pages triées sur une clé unique, sans rien garder d'ouvert entre deux chunks.

```java
public record Invoice(UUID id, String number, BigDecimal amount, Instant issuedAt) {
}
```

```java
// dans InvoiceExportJobConfiguration (étape 5)
@Bean
@StepScope                                      // un reader neuf pour chaque exécution
JdbcPagingItemReader<Invoice> invoiceReader(DataSource dataSource) throws Exception {
    PostgresPagingQueryProvider query = new PostgresPagingQueryProvider();
    query.setSelectClause("id, number, amount, issued_at");
    query.setFromClause("invoices");
    query.setWhereClause("cancelled_at IS NULL");
    query.setSortKeys(Map.of("id", Order.ASCENDING));     // page suivante : WHERE id > dernier lu

    return new JdbcPagingItemReaderBuilder<Invoice>()
            .name("invoiceReader")
            .dataSource(dataSource)
            .queryProvider(query)               // donné : sinon Batch lit les métadonnées de la base
            .pageSize(500)
            .rowMapper((rows, rowNumber) -> new Invoice(
                    rows.getObject("id", UUID.class),
                    rows.getString("number"),
                    rows.getBigDecimal("amount"),
                    rows.getTimestamp("issued_at").toInstant()))
            .build();
}
```

**Étape 4. Écrire le writer.** Le `DelimitedLineAggregator` de Batch ne met pas de guillemets : on écrit les lignes soi-même.

```java
final class Csv {

    private static final String FORMULA_STARTS = "=+-@\t\r";

    private Csv() {
    }

    // RFC 4180 : chaque valeur entre guillemets, guillemets doublés, null en valeur vide
    static String line(Object... values) {
        return Arrays.stream(values).map(Csv::field).collect(Collectors.joining(","));
    }

    private static String field(Object value) {
        String text = value == null ? "" : value.toString();
        if (!text.isEmpty() && FORMULA_STARTS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;                  // un tableur n'exécute plus =HYPERLINK(...)
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
```

```java
// dans InvoiceExportJobConfiguration
@Bean
@StepScope
FlatFileItemWriter<Invoice> invoiceWriter(@Value("#{jobParameters['exportId']}") String exportId) {
    Path file = Path.of(System.getProperty("java.io.tmpdir"), "invoices-" + exportId + ".csv");

    return new FlatFileItemWriterBuilder<Invoice>()
            .name("invoiceWriter")
            .resource(new FileSystemResource(file))
            .encoding(StandardCharsets.UTF_8.name())
            .lineSeparator("\r\n")
            // le BOM en tête : Excel lit alors le fichier en UTF-8
            .headerCallback(writer -> writer.write(
                    "﻿" + Csv.line("id", "number", "amount", "issued_at")))
            .lineAggregator(invoice -> Csv.line(
                    invoice.id(), invoice.number(), invoice.amount(), invoice.issuedAt()))
            .shouldDeleteIfExists(true)
            .build();
}
```

**Étape 5. Assembler le step et le job.** Le step reçoit le gestionnaire de transactions de l'application, qui encadre chaque chunk.

```java
@Configuration
@RequiredArgsConstructor
class InvoiceExportJobConfiguration {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    @Bean
    Step invoiceCsvWrite(JdbcPagingItemReader<Invoice> invoiceReader,
                         FlatFileItemWriter<Invoice> invoiceWriter) {
        return new StepBuilder("invoiceCsvWrite", jobRepository)
                .<Invoice, Invoice>chunk(500)
                .reader(invoiceReader)
                .writer(invoiceWriter)
                .transactionManager(transactionManager)   // sans lui, Batch 6 prend un gestionnaire factice
                .build();
    }

    @Bean
    Job invoiceExport(Step invoiceCsvWrite, Step invoicePublish, InvoiceExportListener listener) {
        return new JobBuilder("invoiceExport", jobRepository)
                .listener(listener)
                .start(invoiceCsvWrite)
                .next(invoicePublish)
                .build();
    }
}
```

**Étape 6. Publier le fichier, puis nettoyer.** Un tasklet stocke le fichier. Un listener de job supprime le fichier local dans tous les cas.

```java
// dans InvoiceExportJobConfiguration ; InvoiceExportService gère la table des exports (non détaillé)
@Bean
Step invoicePublish(InvoiceExportService invoiceExports) {
    return new StepBuilder("invoicePublish", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                String exportId = (String) chunkContext.getStepContext().getJobParameters().get("exportId");
                invoiceExports.publish(UUID.fromString(exportId));   // stocke le fichier (guide 28)
                return RepeatStatus.FINISHED;
            }, transactionManager)
            .build();
}
```

```java
@Component
@RequiredArgsConstructor
class InvoiceExportListener implements JobExecutionListener {

    private final InvoiceExportService invoiceExports;

    @Override
    public void afterJob(JobExecution execution) {             // appelé après un succès comme un échec
        UUID exportId = UUID.fromString(execution.getJobParameters().getString("exportId"));
        invoiceExports.deleteLocalFile(exportId);

        if (execution.getStatus() != BatchStatus.COMPLETED) {
            String failure = execution.getAllFailureExceptions().stream()
                    .findFirst()
                    .map(error -> error.getClass().getSimpleName())   // la classe, jamais le message
                    .orElse(execution.getExitStatus().getExitCode());
            invoiceExports.fail(exportId, failure);
        }
    }
}
```

**Étape 7. Lancer le job hors de la requête.** La requête publie un événement, et le job démarre après le commit sur un autre thread (`@EnableAsync`, guide 16).

```java
public record InvoiceExportRequested(UUID exportId) {     // publié par le service, dans sa transaction
}
```

```java
@Slf4j
@Component
@RequiredArgsConstructor
class InvoiceExportLauncher {

    private final JobOperator jobOperator;
    private final Job invoiceExport;

    @Async
    @TransactionalEventListener              // après le commit : on lance hors de toute transaction
    public void onRequested(InvoiceExportRequested event) throws JobExecutionException {
        JobParameters parameters = new JobParametersBuilder()
                .addString("exportId", event.exportId().toString())   // un id, jamais de donnée personnelle
                .toJobParameters();

        JobExecution execution = jobOperator.start(invoiceExport, parameters);   // rend la main à la fin
        log.info("Invoice export {} ended {}", event.exportId(), execution.getStatus());
    }
}
```

**Étape 8. Vérifier.** `JobOperatorTestUtils`, construit avec les beans, lance le step d'écriture seul.

```java
@SpringBootTest                       // pas @SpringBatchTest : il crée un contexte de plus (guide 29)
class InvoiceExportJobTests {

    private static final UUID EXPORT_ID = UUID.randomUUID();

    @Autowired JobOperator jobOperator;
    @Autowired JobRepository jobRepository;
    @Autowired Job invoiceExport;

    @Test
    void writeStepPutsAHeaderAndOneLinePerInvoice() throws Exception {
        // 3 factures insérées par le jeu de données du test
        JobOperatorTestUtils batch = new JobOperatorTestUtils(jobOperator, jobRepository);
        batch.setJob(invoiceExport);
        JobParameters parameters = new JobParametersBuilder()
                .addString("exportId", EXPORT_ID.toString())
                .toJobParameters();

        JobExecution execution = batch.startStep("invoiceCsvWrite", parameters, new ExecutionContext());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "invoices-" + EXPORT_ID + ".csv");
        assertThat(Files.readAllLines(file)).hasSize(4);
    }
}
```

L'historique se lit aussi en SQL :

```sql
SELECT i.job_name, e.job_execution_id, e.status, e.start_time, e.end_time, e.exit_code
FROM batch_job_execution e
JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
ORDER BY e.job_execution_id DESC
LIMIT 10;
```

### Référence rapide

Méthodes des builders :

| Méthode | Rôle |
|---|---|
| `new JobBuilder(nom, jobRepository)` | crée un job ; `.start(step).next(step)` enchaîne les steps |
| `new StepBuilder(nom, jobRepository)` | crée un step |
| `.<I, O>chunk(n)` | step par chunks de n éléments |
| `.transactionManager(tm)` | la transaction de chaque chunk |
| `.tasklet(tasklet, tm)` | step d'un seul bloc |
| `.allowStartIfComplete(true)` | rejoue le step à chaque relance |
| `.faultTolerant().skip(...).skipLimit(n)` | saute n éléments en erreur |
| `.faultTolerant().retry(...).retryLimit(n)` | réessaie un élément en erreur |

Réglages de Spring Boot :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.batch.job.enabled` | `true` | lance le job au démarrage (avec plusieurs jobs, exige `spring.batch.job.name`) |
| `spring.batch.jdbc.initialize-schema` | `embedded` | crée les tables (`embedded`, `always` ou `never`) |
| `spring.batch.jdbc.isolation-level-for-create` | `SERIALIZABLE` | isolation de la création des exécutions |

Méthodes de `JobOperator` :

| Méthode | Rôle |
|---|---|
| `start(job, paramètres)` | lance une instance, ou relance la dernière exécution en échec |
| `recover(execution)` | passe en `FAILED` une exécution restée `STARTED` |
| `restart(execution)` | relance une exécution en échec |
| `stop(execution)` | demande l'arrêt, pris en compte entre deux chunks |

Par défaut, `start` exécute le job sur le thread appelant, jusqu'à la fin.

Limites de cette version : un export lancé par `@Async` se perd si l'instance s'arrête avant le job.

## Concepts avancés

### Sous le capot : une transaction par chunk

Chaque tour de boucle du step se déroule ainsi :

```
  début de transaction
       |
       v
  read() x 500       une requête par page, WHERE id > :dernier LIMIT 500
       |
       v
  process() x 500    facultatif
       |
       v
  write(chunk)       FlatFileItemWriter : en tampon jusqu'au commit
       |
       v
  batch_step_execution          compteurs (lus, écrits, commits)
  batch_step_execution_context  dernier id lu, taille du fichier
       |
       v
  commit             les lignes et la position de reprise sont validées ensemble
```

- Une erreur annule le chunk en cours : ses lignes ne sont pas écrites et la position reste celle du chunk précédent.
- À la relance, le reader repart après le dernier id enregistré et le writer tronque le fichier à la taille enregistrée.
- `saveState(false)` coupe cette mémoire : le step repart du début, utile si le fichier est perdu.

La pagination sur une clé unique reste stable quand la table change pendant la lecture :

| Pagination | Page 2 | Si une ligne de la page 1 disparaît |
|---|---|---|
| `OFFSET` | `LIMIT 500 OFFSET 500` | une ligne de la page 2 est sautée |
| Clé unique (`JdbcPagingItemReader`) | `WHERE id > :dernier LIMIT 500` | rien ne change |

### Sous le capot : le proxy de `@StepScope`

Spring crée un proxy à la place du bean `@StepScope`. Le vrai reader naît au début du step, quand les paramètres sont connus, et disparaît à la fin. Ce proxy est une sous-classe du type de retour de la méthode `@Bean`. Si elle annonce `ItemReader`, il n'implémente pas `ItemStream`, et Batch n'appelle jamais `open()` ni `update()`.

### Plusieurs instances

Un message de lancement est livré au moins une fois, et une instance peut s'arrêter en plein job. L'application réserve donc l'export et le reprend ailleurs :

```
  message "lancer l'export 42"
           |
     +-----+------+
     |            |
     v            v
  instance A   instance B
     |            |
     |  UPDATE exports SET status = 'RUNNING', lease_until = now + 15 min
     |  WHERE id = 42 AND status = 'QUEUED'
     |            |
  1 ligne      0 ligne --> ne fait rien
     |
     v
  job : le bail est repoussé après chaque chunk
     |
     x  instance A s'arrête
                  |
  job de récupération (guide 21) : bail expiré --> QUEUED, nouveau message
                  |
                  v
  instance B : recover() de l'exécution STARTED, puis start()
               même instance de job, steps rejoués
```

- Le bail couvre un chunk, pas tout le job : renouvelé en route, il évite qu'un export long passe pour interrompu.
- Un fichier local reste sur l'instance arrêtée : on rejoue tous les steps (`allowStartIfComplete`, `saveState(false)`) ou on écrit sur un stockage partagé.
- En `read-committed`, les démarrages ne sont plus sérialisés. La contrainte unique `job_inst_un` empêche toujours deux instances identiques.

### L'historique de Batch

Batch garde chaque exécution indéfiniment. Une purge supprime les enfants avant les parents :

```
  batch_step_execution_context
       |
       v
  batch_step_execution
       |
       v
  batch_job_execution_context, batch_job_execution_params
       |
       v
  batch_job_execution          WHERE end_time < :limite
       |
       v
  batch_job_instance           seulement celles que la purge a vidées
```

- Les heures de Batch sont des `TIMESTAMP` sans fuseau, écrites dans le fuseau de la JVM : la limite se convertit de même.
- Batch crée une instance puis sa première exécution dans deux transactions : la purge épargne une instance sans exécution.

### Surveiller

Boot transforme les observations de Batch en métriques Micrometer, `spring.batch.job` et `spring.batch.step`. Une exécution `STARTED` longtemps après le dernier redémarrage signale un job interrompu.

## Pièges à éviter

### Avec Spring Batch

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lancer un job depuis une méthode `@Transactional` | le `JobRepository` refuse : « Existing transaction detected » | Lancer hors transaction, après le commit |
| Oublier `.transactionManager(...)` sur un step chunk | Batch 6 prend un gestionnaire factice : chunk non atomique | Passer celui de l'application |
| Annoncer `ItemReader` comme type d'un bean `@StepScope` | le proxy cache `ItemStream` : `open()` n'est jamais appelé | Annoncer le type concret |
| Laisser le reader deviner la base | il lit les métadonnées JDBC à sa construction | Donner le `PostgresPagingQueryProvider` |
| Mettre un e-mail ou des filtres en paramètres | ils restent dans `batch_job_execution_params` | Passer un id, relire le reste en base |
| Garder `SERIALIZABLE` pour la création sur Postgres | des démarrages simultanés échouent | Passer à `read-committed` |
| Compter sur Batch pour vider ses tables | l'historique grossit sans fin | Purger les exécutions terminées par un job planifié |

### Avec un fichier CSV

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Utiliser `DelimitedLineAggregator` | ni guillemets ni échappement : une virgule casse la ligne | Mettre chaque valeur entre guillemets |
| Écrire les valeurs telles quelles | `=HYPERLINK(...)` s'exécute à l'ouverture dans un tableur | Préfixer `=`, `+`, `-`, `@`, tabulation d'une apostrophe |
| Enregistrer le message d'une exception | il peut citer des valeurs des lignes | Enregistrer la classe de l'exception |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lancer le job à chaque message reçu | un message en double lance deux exécutions | Réserver l'export par un `UPDATE` conditionnel |
| Relancer une exécution restée `STARTED` | `start` lève `JobExecutionAlreadyRunningException` | Appeler `recover` d'abord |
| Reprendre un step avec `saveState` ailleurs | le writer cherche un fichier resté sur l'instance arrêtée | `saveState(false)` et `allowStartIfComplete(true)` |

### En test et au build

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Annoter la classe de test `@SpringBatchTest` | un contexte Spring de plus, avec ses conteneurs | Construire `JobOperatorTestUtils` avec les beans |
| Appeler `JobRepositoryTestUtils.removeJobExecutions()` | efface l'historique des autres tests (base partagée) | Ne supprimer que ses propres exécutions |
| Garder le repository JDBC pendant l'entraînement AOT | il lit les métadonnées de la base : le build échoue | Exclure `BatchJdbcAutoConfiguration` |

## En résumé

**À retenir :** Spring Batch valide un gros traitement chunk par chunk et sait le reprendre. On le lance hors de la requête et de toute transaction, avec un id pour seul paramètre. L'application gère la reprise entre instances et la purge.

- [ ] `spring-boot-starter-batch-jdbc`, tables `batch_*` créées par migration
- [ ] `spring.batch.job.enabled: false`, `isolation-level-for-create: read-committed`
- [ ] Un seul paramètre de job : un id
- [ ] Steps chunk avec `.transactionManager(...)`, beans `@StepScope` au type concret
- [ ] Pagination sur une clé unique, fournisseur de requêtes explicite
- [ ] CSV : guillemets partout, formules neutralisées, BOM
- [ ] Lancement hors transaction, après le commit
- [ ] Un listener de job qui nettoie et enregistre l'échec
- [ ] Plusieurs instances : réservation par `UPDATE`, bail renouvelé, `recover` des `STARTED`
- [ ] Une purge planifiée des tables `batch_*`

## Dans ce dépôt

La vie d'un export :

```
  POST /api/v1/exports/tasks
       |
       v
  DataExportService.request         transaction : verrou, ligne QUEUED,
       |                            Outbox.enqueue("export.run")
       v
  commit --> OutboxRelay --> file "export.run"
                                    |
                                    v
  DataExportListener                un consommateur, un export à la fois
       |
       v
  DataExportRunner.run              hors transaction
       |   claim : UPDATE QUEUED --> RUNNING, avec un bail
       |   recover : exécutions STARTED laissées par une instance arrêtée
       v
  JobOperator.start(job, exportId)
       |
       +--> step d'écriture         chunks de 500, bail renouvelé
       |
       +--> step de publication     storeGenerated, COMPLETED, mail par l'outbox
       |
       v
  DataExportJobListener             fichiers locaux supprimés, FAILED si échec
```

| Fichier | Rôle |
|---|---|
| [`CsvExportJobs`][csv-jobs] | les deux jobs CSV : step chunk et step de publication |
| [`CsvLineAggregator`][aggregator] | une ligne RFC 4180, formules neutralisées |
| [`PublishExport`][publish] | le tasklet qui stocke le CSV |
| [`PersonalDataJob`][personal-data] | l'export RGPD : trois tasklets (JSON, PDF, ZIP) |
| [`DataExportJobListener`][job-listener] | supprime les fichiers, enregistre l'échec |
| [`DataExportLeaseRenewal`][lease] | renouvelle le bail |
| [`ExportFiles`][files] | le dossier local d'un export |
| [`DataExportRunner`][runner] | réserve, récupère les `STARTED`, lance |
| [`DataExportListener`][listener], [`ExportQueues`][queues] | la file `export.run`, sans réessai |
| [`DataExportService`][service] | demande, réservation, bail, fin, échec, purge |
| [`DataExportRecoveryJob`][recovery] | remet en file les exports au bail expiré |
| [`DataExportPurgeJob`][purge], [`BatchMetadataQueries`][batch-queries] | purge des fichiers et de l'historique |
| [`023-batch-metadata.yaml`][changeset] | les tables `batch_*` de Spring Batch 6.0.5 |

Réglages de `application.yaml`, sous `exports` et `spring.batch` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `exports.chunk-size` | 500 | lignes par transaction et par page |
| `exports.lease` | 15m | durée d'un bail |
| `exports.max-attempts` | 3 | exécutions d'un export interrompu avant l'échec |
| `exports.recovery-interval` | 5m | recherche des interrompus |
| `exports.retention` | 7d | durée du fichier, puis `EXPIRED` |
| `exports.history-retention` | 30d | durée des exports expirés ou en échec, et des exécutions |
| `exports.purge-cron` | `0 30 4 * * *` | heure de la purge |

Ce qui est propre au dépôt :

- Le seul paramètre de job est l'id de l'export. Les filtres se relisent dans `data_exports`, sans donnée personnelle dans les tables de Batch.
- Les readers enveloppent leur requête dans une sous-requête (`(...) AS rows`), car les jointures rendraient `id` ambigu. Le SQL natif voit les lignes supprimées logiquement : chaque requête écrit `deleted_at IS NULL`.
- `DataExportRunner` réserve aussi un export `RUNNING` au bail expiré. `DataExportRecoveryJob` le remet en file jusqu'à `max-attempts`, tout comme un export `QUEUED` dont le message s'est perdu.
- `MediaService.storeGenerated` stocke le fichier sans détection de type ni antivirus, car l'application l'a écrit (guide 28). Le mail part par l'outbox, dans la transaction du step de publication.
- Le PDF de l'export RGPD et ses protections : [cas pratique dédié](cas-pratiques/export-rgpd-pdf.md).
- `liquibase:diff` ignore les tables `batch_*` (`diffExcludeObjects` dans `pom.xml`) : sans entité, il proposerait de les supprimer.

Les tests :

- [`DataExportApiTests`][api-tests] fait le trajet complet par l'API : CSV entre guillemets, formules neutralisées, 1001 lignes lues dans l'ordre des id.
- [`DataExportLifecycleTests`][lifecycle-tests] : exécution `STARTED`, reprise, bail, message perdu, purge.
- [`DataExportFailureTests`][failure-tests] : stockage en échec (`@DeadLetterIntegrationTest`).
- [`BatchMetadataQueriesTests`][batch-queries-tests], [`CsvLineAggregatorTest`][aggregator-test], [`DataExportRunnerTest`][runner-test] et [`PersonalDataJobTest`][personal-data-test] : les pièces seules.

Aucun test n'utilise `@SpringBatchTest` : ils injectent `JobRepository` et les jobs du contexte commun.

Pour ajouter un export : une valeur de `DataExportType`, un job dont le seul paramètre est l'id, avec `allowStartIfComplete(true)`, le listener de bail sur chaque step et `DataExportJobListener` sur le job, puis son entrée dans la table des jobs de `DataExportRunner`.

## Voir aussi

- [15. Concurrence et verrous](15-concurrence-verrous.md) : la réservation par `UPDATE` conditionnel.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : le message de lancement.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : la récupération et la purge, verrouillées.
- [28. Uploads et drivers de stockage](28-uploads-stockage.md) : où le fichier est rangé.
- [Cas pratique : la vie d'un export](cas-pratiques/vie-d-un-export.md).
- Documentation officielle : [Spring Batch](https://docs.spring.io/spring-batch/reference/index.html), [Spring Batch dans Spring Boot](https://docs.spring.io/spring-boot/how-to/batch.html), [injection CSV (OWASP)](https://owasp.org/www-community/attacks/CSV_Injection).

[Sommaire](README.md) | [< 21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) | [23. Envoi de mails >](23-envoi-mails.md)

[csv-jobs]: ../../src/main/java/io/julienmetral/tasks/export/batch/CsvExportJobs.java
[aggregator]: ../../src/main/java/io/julienmetral/tasks/export/batch/CsvLineAggregator.java
[publish]: ../../src/main/java/io/julienmetral/tasks/export/batch/PublishExport.java
[personal-data]: ../../src/main/java/io/julienmetral/tasks/export/batch/PersonalDataJob.java
[job-listener]: ../../src/main/java/io/julienmetral/tasks/export/batch/DataExportJobListener.java
[lease]: ../../src/main/java/io/julienmetral/tasks/export/batch/DataExportLeaseRenewal.java
[files]: ../../src/main/java/io/julienmetral/tasks/export/batch/ExportFiles.java
[runner]: ../../src/main/java/io/julienmetral/tasks/export/services/DataExportRunner.java
[listener]: ../../src/main/java/io/julienmetral/tasks/export/messaging/DataExportListener.java
[queues]: ../../src/main/java/io/julienmetral/tasks/export/messaging/ExportQueues.java
[service]: ../../src/main/java/io/julienmetral/tasks/export/services/DataExportService.java
[recovery]: ../../src/main/java/io/julienmetral/tasks/export/services/DataExportRecoveryJob.java
[purge]: ../../src/main/java/io/julienmetral/tasks/export/services/DataExportPurgeJob.java
[batch-queries]: ../../src/main/java/io/julienmetral/tasks/export/repositories/BatchMetadataQueries.java
[changeset]: ../../src/main/resources/db/changelog/changes/023-batch-metadata.yaml
[api-tests]: ../../src/test/java/io/julienmetral/tasks/export/controllers/DataExportApiTests.java
[lifecycle-tests]: ../../src/test/java/io/julienmetral/tasks/export/services/DataExportLifecycleTests.java
[failure-tests]: ../../src/test/java/io/julienmetral/tasks/export/services/DataExportFailureTests.java
[batch-queries-tests]: ../../src/test/java/io/julienmetral/tasks/export/repositories/BatchMetadataQueriesTests.java
[aggregator-test]: ../../src/test/java/io/julienmetral/tasks/export/batch/CsvLineAggregatorTest.java
[runner-test]: ../../src/test/java/io/julienmetral/tasks/export/services/DataExportRunnerTest.java
[personal-data-test]: ../../src/test/java/io/julienmetral/tasks/export/batch/PersonalDataJobTest.java
