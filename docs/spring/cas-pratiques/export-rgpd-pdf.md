# Cas pratique : L'export RGPD et son PDF

[Sommaire](../README.md)

## Le scénario

Un utilisateur exerce ses droits d'accès et de portabilité : il veut tout ce que l'API garde sur lui. L'archive doit contenir un JSON complet pour un logiciel, un PDF lisible pour lui et sa photo de profil, sans rien révéler d'un secret ni des autres comptes. Le rendu du PDF ne doit jamais devenir une porte d'entrée vers le serveur.

## Vue d'ensemble

Le job de l'export, entre la demande et le téléchargement :

```
 POST /api/v1/exports/my-data                                  synchrone
      |
      |  ligne QUEUED, message "export.run", 202 + Location
      |  (le chemin commun à tous les exports)
      v
 DataExportRunner --> job personalDataExport                   asynchrone,
      |                                                  hors transaction
      v
 +-----------------------------+
 | étape personalDataJson      |   PersonalDataQueries, SQL natif,
 |   --> my-data.json          |   une requête par section
 +-----------------------------+
      |
      v
 +-----------------------------+
 | étape personalDataPdf       |   relit my-data.json
 |   Thymeleaf --> XHTML       |   mode XML, th:text seulement
 |   openhtmltopdf --> PDF     |   aucune ressource externe
 +-----------------------------+
      |
      v
 +-----------------------------+
 | étape personalDataPublish   |   ZIP : JSON, PDF, photo du stockage
 |   --> COMPLETED, mail       |   mail écrit dans l'outbox
 +-----------------------------+
      |
      v
 GET /api/v1/exports/{id} --> URL présignée de my-data-AAAA-MM-JJ.zip
```

- Chaque étape a sa transaction, et lit ce que l'étape précédente a écrit dans le dossier local de l'export.
- Les fichiers restent sur le disque de l'instance jusqu'à la fin du job, qui les supprime.

## Pas à pas

**1. La demande.** Le client envoie `POST /api/v1/exports/my-data`, sans corps, depuis n'importe quel compte actif. [`DataExportController`][controller] appelle `DataExportService.request` avec le type `PERSONAL_DATA`. La suite est commune à tous les exports, et décrite dans le [cas pratique sur la vie d'un export](vie-d-un-export.md) : verrou du compte, un seul export en cours par type, outbox, réservation, bail, reprise. Un export de tâches peut donc tourner en même temps.

**2. Ce que demande le RGPD.** L'article 15 (droit d'accès) donne à chacun une copie de ses données. L'article 20 (portabilité) les veut dans un format structuré, couramment utilisé et lisible par machine. D'où deux fichiers : `my-data.json` pour un logiciel, `my-data.pdf` pour la personne. Le règlement laisse un mois pour répondre (article 12) : un traitement en arrière-plan suffit largement.

**3. Un job en trois étapes.** [`PersonalDataJob`][job] enchaîne trois étapes tasklet, chacune dans sa transaction. [`DataExportLeaseRenewal`][renewal] repousse le bail de l'export au début de chaque étape. Les trois ont `allowStartIfComplete(true)` : après l'arrêt d'une instance, tout repart, car les fichiers étaient sur son disque (guide 22).

**4. Le JSON, depuis le SQL natif.** L'étape `personalDataJson` appelle [`PersonalDataQueries`][queries], une requête `NamedParameterJdbcTemplate` par section : compte, réglages de notification, sessions, webhooks et leurs livraisons, tâches, commentaires, mentions, rappels, pièces jointes, historique, exports (guide 13). Le SQL est natif car `@SoftDelete` cache à JPA les tâches supprimées, qui restent les données de la personne : elles apparaissent avec leur `deleted_at` (guide 12). Les dates deviennent des `Instant`, donc de l'ISO 8601 en UTC. Le `JsonMapper` de Jackson 3 écrit le tout, indenté, dans le dossier local.

**5. Ce que le JSON tait.** Les autres comptes n'apparaissent que par leur nom affiché (créateur, assigné, auteur d'une mention), jamais par leur email. Une mention dit qui, où et quand, pas le texte du commentaire d'autrui. Aucun secret ne sort : pas de hachage de mot de passe, des sessions réduites à leurs dates, des webhooks sans secret de signature. L'URL d'un webhook Slack, qui est elle-même un secret, est remplacée dans la requête par `WebhookUrls.SLACK_MASK`. Les livraisons de webhooks sont listées sans leur contenu.

**6. Un format versionné.** Le JSON commence par `format` (`tasks-api-personal-data`), `version` et `exported_at`. La constante `FORMAT_VERSION`, à 1, augmente quand la forme du JSON change : un logiciel qui lit d'anciens exports sait les distinguer. Les clés reprennent les noms des colonnes (`display_name`, `created_at`).

**7. Le PDF, depuis le JSON.** L'étape `personalDataPdf` relit `my-data.json` dans une `Map`, sans retourner en base. Les deux fichiers montrent ainsi le même instantané, et le rendu ne dépend que du fichier. [`PersonalDataPdf`][pdf] produit alors le PDF en deux temps.

**8. Le gabarit Thymeleaf.** Un `TemplateEngine` simple, sans vue Spring MVC, lit [`personal-data.xhtml`][template] par un `ClassLoaderTemplateResolver` en mode XML : la sortie reste du XHTML bien formé, ce qu'attend le moteur PDF. Chaque valeur passe par `th:text`, qui échappe le texte, jamais par `th:utext`. Chaque section s'arrête à `exports.pdf-max-rows-per-section` lignes (1000), puis indique combien d'autres le JSON contient.

**9. Le rendu openhtmltopdf.** Le `PdfRendererBuilder` reçoit le XHTML sans URI de base, et deux contrôles d'accès qui refusent toute ressource externe, avant puis après la résolution de son URI. Les polices Noto Sans normale et grasse viennent du jar ([`fonts/`][fonts], licence OFL), incorporées en sous-ensemble : seuls les caractères utilisés entrent dans le fichier. Les polices standard du PDF ne couvrent que les lettres d'Europe de l'Ouest. Un bloc statique envoie les logs du moteur vers SLF4J (`XRLog.setLoggerImpl(new Slf4jLogger())`) : sans lui, ils sortaient en texte brut au milieu des logs structurés du profil `prod` (guide 02).

**10. L'archive.** L'étape `personalDataPublish` écrit un ZIP avec `my-data.json`, `my-data.pdf` et, si le profil a une photo, `profile-photo.jpg` (ou `.png`, `.webp`), lue dans le stockage objet (guide 28). `DataExportService.complete` stocke ensuite l'archive en `application/zip`, sans nombre de lignes, et passe l'export `COMPLETED` pour 7 jours. Le mail « Your export is ready » part après le commit, comme pour tout export (guide 23).

**11. Le téléchargement et la fin.** `GET /api/v1/exports/{id}` donne une URL présignée de `my-data-AAAA-MM-JJ.zip`, valable 10 min. La purge des exports supprime le ZIP après 7 jours, et l'export reste listé en `EXPIRED`. Il figure lui-même dans la section `exports` des exports suivants.

## Quand ça se passe mal

Les attaques et les pannes que le code prévoit :

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Titre de tâche qui contient `<script>` | le texte arrive dans le gabarit | `th:text` l'échappe : le PDF l'affiche tel quel |
| Valeur ou gabarit qui cite une URL | le moteur irait la chercher (SSRF) | les deux contrôles refusent tout, sans URI de base |
| Nom en grec, polonais ou cyrillique | les polices standard n'ont pas ces lettres | Noto Sans, incorporée, les couvre |
| Compte très volumineux | le PDF ferait des milliers de pages | 1000 lignes par section dans le PDF, le JSON reste complet |
| Étape plus longue que le bail | le bail n'est repoussé qu'au début d'une étape | réglage : `exports.lease` doit dépasser l'étape la plus longue |
| Photo illisible dans le stockage | l'étape de publication lève une exception | le job échoue, l'export passe `FAILED`, mail au propriétaire |
| Instance arrêtée entre deux étapes | les fichiers disparaissent avec elle | les trois étapes repartent sur une autre instance |
| Avertissement du moteur PDF | il partirait en texte brut sur la console | `XRLog` l'envoie à SLF4J, en log structuré |

Le JSON est construit en mémoire avant d'être écrit, puis relu en entier par l'étape suivante : sa taille suit celle du compte. Seul le PDF est plafonné.

## À retenir

- Deux fichiers pour deux lecteurs : le JSON complet et versionné pour un logiciel, le PDF plafonné pour une personne.
- Le SQL natif voit les lignes supprimées, qui restent des données personnelles ; il choisit aussi colonne par colonne ce qui sort, sans secret ni email d'autrui.
- Un moteur de rendu HTML est un client HTTP en puissance : refuser toute ressource externe et n'insérer que du texte échappé.
- Chaque étape relit le fichier de la précédente : le PDF montre exactement ce que contient le JSON.

## Voir aussi

- [12. Mapping Hibernate 7](../12-mapping-hibernate.md) : `@SoftDelete`, que le SQL natif contourne.
- [13. SQL avec JdbcTemplate](../13-sql-jdbctemplate.md) : les requêtes natives à paramètres nommés.
- [22. Spring Batch](../22-spring-batch.md) : les étapes tasklet et le redémarrage.
- [26. Clients HTTP et filtre SSRF](../26-clients-http-ssrf.md) : la même menace, du côté des appels sortants.
- [02. Profil de production](../02-profil-production.md) : les logs structurés.
- [Cas pratique : La vie d'un export](vie-d-un-export.md) : réservation, bail, reprise et purge.
- [README du dépôt, « Export your personal data »](../../../README.md#export-your-personal-data) : le contenu de l'archive, côté client.
- Documentation officielle : [le RGPD sur EUR-Lex](https://eur-lex.europa.eu/eli/reg/2016/679/oj).

[controller]: ../../../src/main/java/io/julienmetral/tasks/export/controllers/DataExportController.java
[job]: ../../../src/main/java/io/julienmetral/tasks/export/batch/PersonalDataJob.java
[renewal]: ../../../src/main/java/io/julienmetral/tasks/export/batch/DataExportLeaseRenewal.java
[queries]: ../../../src/main/java/io/julienmetral/tasks/export/personaldata/PersonalDataQueries.java
[pdf]: ../../../src/main/java/io/julienmetral/tasks/export/personaldata/PersonalDataPdf.java
[template]: ../../../src/main/resources/templates/exports/personal-data.xhtml
[fonts]: ../../../src/main/resources/fonts/
