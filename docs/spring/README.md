# Documentation Spring Boot

Sommaire de la documentation des concepts Java et Spring mis en œuvre dans ce dépôt. Chaque guide part du principe général, montre son implémentation avec Spring, puis renvoie au code du dépôt.

## Structure d'un guide

```
 # NN. Titre
 [Sommaire] | < précédent | suivant >

 ## Contexte                    problème, usage, alternatives
 ## Concepts clés               vocabulaire, théorie sans code
 ## Implémentation avec Spring  arborescence, étapes, "Vérifier",
                                référence rapide, limites
 ## Concepts avancés            sous le capot, instances, surveiller
 ## Pièges à éviter             piège | conséquence | parade
 ## En résumé                   la phrase à retenir, la checklist
 ## Dans ce dépôt               fichiers, réglages, tests
 ## Voir aussi                  guides liés, doc officielle
```

Conventions :

- Les liens vers le code pointent vers les sources du dépôt, jamais vers un numéro de ligne.
- Les étapes et les parades sont à l'infinitif ; les explications utilisent « on ».
- Les schémas sont en ASCII, sur 80 colonnes au plus.

## Parcours conseillé

Pour une première lecture : 01, 03, 05, 07, 11, 14, 16, 19, 20, puis 29. Les autres guides se lisent selon le besoin.

## Sommaire

**Configuration**

- [01. Configuration et `@ConfigurationProperties`](01-configuration-proprietes.md) : fichiers YAML, `.env`, propriétés typées et validées, beans conditionnels.
- [02. Profil de production](02-profil-production.md) : un seul profil `prod`, réglages obligatoires vérifiés au démarrage, logs structurés, arrêt propre.

**Web et API**

- [03. Contrôleurs MVC](03-controleurs-mvc.md) : DTO en records, pagination stable, upload multipart, cache HTTP.
- [04. Versioning d'API](04-versioning-api.md) : `/api/v1` avec Spring Framework 7, sans écrire la version dans chaque contrôleur.
- [05. Validation et erreurs](05-validation-erreurs.md) : Bean Validation, `ProblemDetail` (RFC 9457), un gestionnaire d'erreurs global.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : documentation générée, enrichie par du code, figée et vérifiée par un test.

**Sécurité**

- [07. Chaîne de filtres et JWT](07-securite-jwt.md) : API sans session, jetons JWT émis par l'API, endpoints publics.
- [08. Autorisations](08-autorisations.md) : règles par URL, annotations de sécurité maison, règles « propriétaire ».
- [09. Mots de passe et jetons opaques](09-mots-de-passe-jetons.md) : Argon2id, migration des anciens hachages, jetons de rafraîchissement.
- [10. Rate limiting](10-rate-limiting.md) : limiter les appels par adresse ou par compte, en base, sans Redis.

**Données**

- [11. Spring Data JPA](11-spring-data-jpa.md) : repositories, requêtes, projections, chargement sans N+1.
- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : suppression logique, UUIDv7, verrou optimiste, colonnes JSON.
- [13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) : quand sortir de JPA, requêtes atomiques Postgres.
- [14. Transactions](14-transactions.md) : propagation, lecture seule, travail après le commit.
- [15. Concurrence et verrous](15-concurrence-verrous.md) : verrou optimiste, `SELECT ... FOR UPDATE`, `SKIP LOCKED`, réservation par `UPDATE`.
- [16. Événements applicatifs et `@Async`](16-evenements-async.md) : découpler les fonctionnalités, agir après le commit.
- [17. Cache Caffeine](17-cache-caffeine.md) : `@Cacheable`, expiration, éviction après le commit.
- [18. Migrations Liquibase](18-migrations-liquibase.md) : changesets, génération depuis les entités, conventions de nommage.

**Traitements asynchrones**

- [19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) : files, dead letters, réessais, confirmations du broker.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : un message part si et seulement si la transaction est validée.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : `@Scheduled`, et un seul passage par tour avec plusieurs instances.
- [22. Spring Batch](22-spring-batch.md) : gros traitements par paquets, reprise après un plantage.
- [23. Envoi de mails](23-envoi-mails.md) : envoi par une file, tests avec Mailpit.

**Temps réel**

- [24. Flux de notifications (SSE)](24-flux-notifications-sse.md) : pousser des événements au navigateur sur une requête HTTP ouverte.
- [25. WebSocket et STOMP](25-websocket-stomp.md) : abonnements par sujet, sécurité des messages.

**Appels sortants et fichiers**

- [26. Clients HTTP et filtre SSRF](26-clients-http-ssrf.md) : `RestClient`, clients déclaratifs, adresses autorisées.
- [27. Résilience](27-resilience.md) : `@Retryable` de Spring 7, disjoncteur et cloison par hôte.
- [28. Uploads et drivers de stockage](28-uploads-stockage.md) : multipart, stockage S3, implémentation choisie par configuration.

**Tests**

- [29. Couches de tests et contextes](29-tests-couches-contextes.md) : tests unitaires, slices, intégration, et le cache des contextes Spring.
- [30. Testcontainers](30-testcontainers.md) : vraies bases et vrais brokers en test, réutilisation des conteneurs.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : horloge contrôlée, comptage des requêtes, tests qui vérifient des conventions.

**Exploitation**

- [32. Actuator, sondes et métriques](32-actuator-metriques.md) : port de gestion, sondes de vie, métriques Micrometer.
- [33. Image Docker, build et CI](33-docker-build-ci.md) : image en couches, cache AOT, compose, pipeline.

## Cas pratiques

Des parcours de bout en bout, à travers plusieurs guides :

- [Une requête à travers la sécurité](cas-pratiques/requete-securite.md)
- [Une notification de bout en bout](cas-pratiques/notification-bout-en-bout.md)
- [La vie d'un export](cas-pratiques/vie-d-un-export.md)
- [L'export RGPD et son PDF](cas-pratiques/export-rgpd-pdf.md)
- [L'upload d'une photo de profil](cas-pratiques/upload-photo-profil.md)
- [La livraison d'un webhook](cas-pratiques/livraison-webhook.md)
- [Le chiffrement des secrets de webhook](cas-pratiques/chiffrement-secrets.md)
- [La rétention RGPD d'un compte](cas-pratiques/retention-rgpd.md)
- [Pourquoi les contextes de test se multiplient](cas-pratiques/contextes-de-test.md)

## Glossaire

Les termes qui reviennent dans plusieurs guides :

| Terme | Sens |
|---|---|
| Bean | un objet créé et géré par Spring, injecté là où on en a besoin |
| BOM | une liste de versions à importer dans Maven, pour aligner les dépendances d'une famille |
| Broker | le serveur de messages (ici RabbitMQ), qui reçoit, range et distribue les messages |
| Buffer | une zone d'attente bornée, entre celui qui produit et celui qui consomme |
| Commit | la validation d'une transaction : ses changements deviennent visibles et définitifs |
| Endpoint | une URL de l'API, avec sa méthode HTTP |
| Heartbeat | un petit message envoyé à intervalle régulier pour garder une connexion ouverte |
| Idempotent | qu'on peut relancer sans dégât : deux passages donnent le même résultat qu'un seul |
| Instance | un exemplaire de l'application en marche ; en production, on en lance souvent plusieurs |
| Listener | une méthode appelée quand un événement ou un message arrive |
| Outbox | une table où l'on écrit les messages dans la transaction, avant de les publier (guide 20) |
| Proxy Spring | l'objet que Spring place devant un bean pour ajouter un comportement (transaction, cache, verrou) |
| Reverse proxy | le serveur placé devant l'application (nginx, load balancer), qui relaie les requêtes |
| Thread virtuel | un thread Java très léger (Java 21 et plus), qu'on peut créer par milliers |
