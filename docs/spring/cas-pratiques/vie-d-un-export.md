# Cas pratique : La vie d'un export

[Sommaire](../README.md)

## Le scénario

Alice, dont le compte est actif, demande l'export CSV de ses tâches en cours. L'instance qui exécute le job s'arrête en pleine écriture. Le fichier doit quand même arriver, une seule fois, rester téléchargeable sept jours, puis disparaître avec l'historique de Spring Batch.

## Vue d'ensemble

Le trajet complet, de la requête à la purge :

```
 POST /api/v1/exports/tasks                                    synchrone
      |
      v
 DataExportService.request          transaction : verrou du compte,
      |                             ligne QUEUED, ligne d'outbox
      v
 202 Accepted + Location
      |
      |  après le commit                                       asynchrone
      v
 file "export.run" --> DataExportListener --> DataExportRunner
      |
      v
 claim : QUEUED --> RUNNING, bail de 15 min          transaction courte
      |
      v
 JobOperator.start                                   hors transaction
      |
      v
 étape tasksCsvWrite            une transaction par paquet de 500 lignes,
      |                         bail repoussé après chaque paquet
      |
      +-- plantage --> bail expiré --> DataExportRecoveryJob : QUEUED
      |                                --> une instance : recover, start
      v
 étape tasksCsvExportPublish    transaction : fichier stocké, COMPLETED,
      |                         mail écrit dans l'outbox
      v
 GET /api/v1/exports/{id} --> URL présignée, valable 10 min
      |
      v
 DataExportPurgeJob, 04:30      fichier supprimé après 7 jours (EXPIRED),
                                historique Batch après 30 jours
```

- Le bail (`lease_until`) est l'échéance au-delà de laquelle un export en cours est tenu pour interrompu : l'instance qui travaille le repousse, une instance arrêtée non.
- Le travail long ne tient aucune transaction ouverte : requête, réservation, paquets et publication ont chacun la leur.

## Pas à pas

**1. La demande.** `POST /api/v1/exports/tasks` avec les filtres de la liste des tâches (`status`, `assigneeId`, `archived`). La chaîne de filtres réserve `/api/*/exports/**` aux comptes actifs (guide 08), puis [`DataExportController`][controller] appelle `DataExportService.request` avec le type `TASKS_CSV`.

**2. Un seul export par type.** [`DataExportService`][service] ouvre une transaction et verrouille la ligne du compte (`findByIdForUpdate`). Si un export du même type est `QUEUED` ou `RUNNING` pour ce compte : 409 `export-in-progress`. Sans le verrou, deux requêtes simultanées créeraient chacune leur export (guide 15).

**3. La ligne et son message.** Dans la même transaction, le service enregistre un [`DataExport`][entity] `QUEUED`, avec ses filtres et un bail de 15 min (`exports.lease`), puis écrit `export.run` dans l'outbox (guide 20). Le contrôleur répond 202 avec `Location: /api/v1/exports/{id}` (guide 03) ; l'outbox publie le message après le commit, sur un thread `@Async`.

**4. La réservation.** [`DataExportListener`][listener] consomme `export.run` avec sa propre fabrique ([`ExportQueues`][queues]) : un consommateur par instance, un message à la fois, sans réessai, acquitté seulement quand le job est fini. [`DataExportRunner`][runner] appelle `claim`, un `UPDATE` conditionnel qui passe l'export de `QUEUED` à `RUNNING`, incrémente `attempts` et pose un bail neuf. Une ligne modifiée : cette instance exécute l'export. Zéro ligne : doublon ou export déjà pris, rien ne se passe (guide 15).

**5. Le lancement hors transaction.** Le seul paramètre du job est l'id de l'export : les filtres sont relus dans la ligne, donc aucune donnée personnelle n'arrive dans les tables de Spring Batch. Le runner appelle `JobOperator.start` hors de toute transaction, car Batch refuse de démarrer un job dans une transaction ; chaque étape valide la sienne (guide 22).

**6. L'écriture par paquets.** L'étape `tasksCsvWrite` de [`CsvExportJobs`][jobs] lit les tâches 500 par 500 avec un `JdbcPagingItemReader` : chaque page commence après le dernier id de la précédente (UUIDv7), et la requête native écarte elle-même les tâches supprimées. Un `FlatFileItemWriter` écrit un fichier local ([`ExportFiles`][files]) : UTF-8 avec BOM, valeurs entre guillemets, formules neutralisées par [`CsvLineAggregator`][aggregator]. Chaque paquet a sa transaction, et [`DataExportLeaseRenewal`][renewal] repousse le bail au début de chaque étape et après chaque paquet.

**7. Le plantage.** L'instance s'arrête au milieu de l'étape. Restent trois traces : l'export `RUNNING`, dont le bail expire dans 15 min au plus ; une exécution Batch restée `STARTED` ; un demi-fichier sur le disque de l'instance arrêtée. Le message non acquitté est remis à une autre instance, dont la réservation échoue tant que le bail court : il est acquitté sans rien lancer.

**8. La remise en file.** [`DataExportRecoveryJob`][recovery] tourne toutes les 5 min, sur une seule instance (verrou ShedLock `data-export-recovery`, guide 21), et cherche les exports actifs dont le bail est dépassé. Un export `RUNNING` à qui il reste des essais (3 au plus, `exports.max-attempts`) repasse `QUEUED`, avec un bail neuf et un nouveau message `export.run`. Celui qui les a tous utilisés passe `FAILED` (`Interrupted`), et son propriétaire reçoit un mail.

**9. La récupération de l'exécution Batch.** Une instance réserve l'export pour son deuxième essai. Avant de démarrer, le runner retrouve l'instance de job et passe `JobOperator.recover` sur chaque exécution encore `STARTED` : elle et ses étapes deviennent `FAILED`. Sans cela, `start` refuserait avec `JobExecutionAlreadyRunningException`. Il relance alors la même instance de job, avec une nouvelle exécution.

**10. Le redémarrage depuis le début.** Les deux étapes ont `allowStartIfComplete(true)` : même une étape `COMPLETED` repart, par exemple l'écriture si l'arrêt a eu lieu pendant la publication. Le lecteur et l'écrivain ont `saveState(false)` : la lecture repart de la première ligne, et le fichier est recréé. Reprendre au dernier paquet est impossible : le demi-fichier est resté sur l'instance arrêtée.

**11. La publication.** Le tasklet [`PublishExport`][publish] de l'étape `tasksCsvExportPublish` compte les lignes et appelle `DataExportService.complete`, dans la transaction de l'étape. `MediaService.storeGenerated` stocke le fichier sans détection de type ni antivirus, puisque l'application l'a écrit ; l'objet est supprimé si la transaction est annulée (guide 28). L'export passe `COMPLETED`, avec `expiresAt` à 7 jours. L'événement `DataExportCompleted` déclenche [`DataExportEmailSender`][mail] dans la même transaction : le mail part de l'outbox après le commit (guide 23), avec un lien vers la page de téléchargement du front, jamais le lien présigné, qui expire vite. Enfin, [`DataExportJobListener`][job-listener] supprime le dossier local, quoi qu'il soit arrivé.

**12. Le téléchargement.** `GET /api/v1/exports/{id}` ne trouve que les exports de l'appelant (404 sinon). Pour un export `COMPLETED`, la réponse porte `downloadUrl` : une URL présignée, valable 10 min (`storage.presigned-url-ttl`), qui force le téléchargement sous le nom `tasks-AAAA-MM-JJ.csv`. Le cache `mediaDownloads` rend la même URL pendant la moitié de sa validité (guide 17).

**13. La purge.** [`DataExportPurgeJob`][purge] tourne chaque nuit à 04:30 (verrou `data-export-purge`). Il supprime le fichier des exports `COMPLETED` dont `expiresAt` est passé : la ligne `media` dans la transaction, l'objet après le commit ; l'export reste listé en `EXPIRED`. Il supprime ensuite les exports `EXPIRED` et `FAILED` vieux de plus de 30 jours (`exports.history-retention`). Enfin, [`BatchMetadataQueries`][batch-queries] supprime en SQL les exécutions Batch finies depuis plus de 30 jours, avec leurs étapes : Batch ne supprime jamais son historique (guide 13).

## Quand ça se passe mal

Les pannes prévues et la reprise :

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Message `export.run` en double | la réservation trouve l'export `RUNNING` ou `COMPLETED` | l'`UPDATE` ne modifie aucune ligne, rien n'est lancé |
| Message `export.run` perdu | l'export reste `QUEUED` | son bail expire, et le job de reprise renvoie le message |
| RabbitMQ indisponible à la demande | le message reste dans l'outbox | `OutboxRelayJob` le publie au retour du broker |
| Instance arrêtée pendant le job | export `RUNNING`, exécution `STARTED` | remise en file après le bail, `recover`, tout refait |
| Trois arrêts de suite | les essais sont épuisés | l'export passe `FAILED` (`Interrupted`), mail au propriétaire |
| Arrêt pendant la publication | objet stocké, transaction jamais validée | le nettoyage des médias supprime l'objet orphelin après un jour |
| Stockage indisponible à la publication | la transaction de l'étape est annulée | le job échoue : `FAILED`, et un mail avec une référence |
| Job refusé au démarrage | `start` lève une `JobExecutionException` | le runner passe l'export `FAILED`, avec le nom de l'exception |

Seul un arrêt d'instance est réessayé ; un job qui échoue ne l'est pas. La colonne `failure` ne garde que la classe de l'exception, jamais son message, qui peut contenir des données des lignes. Un export `FAILED` ne compte plus comme actif : le propriétaire peut redemander aussitôt.

## À retenir

- La requête n'écrit qu'une ligne et un message ; le travail long tourne ailleurs, en petites transactions.
- Un `UPDATE` conditionnel décide qui exécute l'export.
- Le bail, repoussé pendant le travail, distingue un export long d'un export abandonné.
- Après un plantage, on récupère l'exécution Batch avec `recover`, puis on refait tout : le fichier local est perdu avec l'instance.
- Ce qui contient des données personnelles a une durée de vie : 7 jours pour le fichier, 30 jours pour l'historique.

## Voir aussi

- [03. Contrôleurs MVC](../03-controleurs-mvc.md) : répondre 202 avec un en-tête `Location`.
- [15. Concurrence et verrous](../15-concurrence-verrous.md) : `SELECT ... FOR UPDATE` et réservation par `UPDATE`.
- [20. Outbox transactionnelle](../20-outbox-transactionnelle.md) : le message `export.run` et le mail, publiés après le commit.
- [21. Jobs planifiés et ShedLock](../21-jobs-planifies-shedlock.md) : la reprise et la purge, une instance à la fois.
- [22. Spring Batch](../22-spring-batch.md) : jobs, étapes, paquets, `recover` et redémarrage.
- [28. Uploads et drivers de stockage](../28-uploads-stockage.md) : le fichier stocké et l'URL présignée.
- [Cas pratique : L'export RGPD et son PDF](export-rgpd-pdf.md) : le même parcours, avec un job en trois étapes.
- [README du dépôt, « Export data »](../../../README.md#export-data) : l'API côté client.

[controller]: ../../../src/main/java/io/julienmetral/tasks/export/controllers/DataExportController.java
[service]: ../../../src/main/java/io/julienmetral/tasks/export/services/DataExportService.java
[entity]: ../../../src/main/java/io/julienmetral/tasks/export/entities/DataExport.java
[listener]: ../../../src/main/java/io/julienmetral/tasks/export/messaging/DataExportListener.java
[queues]: ../../../src/main/java/io/julienmetral/tasks/export/messaging/ExportQueues.java
[runner]: ../../../src/main/java/io/julienmetral/tasks/export/services/DataExportRunner.java
[jobs]: ../../../src/main/java/io/julienmetral/tasks/export/batch/CsvExportJobs.java
[files]: ../../../src/main/java/io/julienmetral/tasks/export/batch/ExportFiles.java
[aggregator]: ../../../src/main/java/io/julienmetral/tasks/export/batch/CsvLineAggregator.java
[renewal]: ../../../src/main/java/io/julienmetral/tasks/export/batch/DataExportLeaseRenewal.java
[recovery]: ../../../src/main/java/io/julienmetral/tasks/export/services/DataExportRecoveryJob.java
[publish]: ../../../src/main/java/io/julienmetral/tasks/export/batch/PublishExport.java
[mail]: ../../../src/main/java/io/julienmetral/tasks/export/mail/DataExportEmailSender.java
[job-listener]: ../../../src/main/java/io/julienmetral/tasks/export/batch/DataExportJobListener.java
[purge]: ../../../src/main/java/io/julienmetral/tasks/export/services/DataExportPurgeJob.java
[batch-queries]: ../../../src/main/java/io/julienmetral/tasks/export/repositories/BatchMetadataQueries.java
