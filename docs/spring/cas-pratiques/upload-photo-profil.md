# Cas pratique : L'upload d'une photo de profil

[Sommaire](../README.md)

## Le scénario

Bob, utilisateur actif, envoie `PUT /api/v1/users/{id}/avatar` avec une photo de 3 Mo prise au téléphone (JPEG en portrait, orientation EXIF 6, position GPS dans les métadonnées), dans le champ multipart `file`. L'API doit contrôler le fichier et répondre tout de suite. Elle doit ensuite produire en arrière-plan une photo carrée de 256 px, redressée et sans métadonnées, qui remplace l'ancienne.

## Vue d'ensemble

Deux transactions, une réponse synchrone et un traitement asynchrone :

```
 Client : PUT /api/v1/users/{id}/avatar (multipart, champ file)
      |
      v
 sécurité : jeton, @AllowedRolesOrSelfOnly(UserRole.ADMIN) -- refus --> 403
      |
 ===== transaction 1 (la requête) ============================
 |    v                                                       |
 |  AvatarService.update                                      |
 |    verrou de la ligne users (findByIdForUpdate)            |
 |    MediaService.store(AVATAR_UPLOAD)                       |
 |      vide 400 | > 5 Mo 413 | type 415 | virus 422 | 503    |
 |      objet avatar-upload/<uuid> dans le stockage           |
 |    checkDimensions (en-tête) -- trop grand --> 422         |
 |    photo en attente : pending_avatar_media_id              |
 |    Outbox.enqueue("avatar.process", AvatarUploaded)        |
 ===== commit ================================================
      |
      v
 202 : photo actuelle (ou identicon), avatarPending: true
      |
      v  (après le commit, autre thread)
 OutboxRelay --> RabbitMQ, file avatar.process
      |
      v
 AvatarProcessingListener (consommateur RabbitMQ)
      |
 ===== transaction 2 (le worker) =============================
 |  AvatarService.process                                     |
 |    verrou de la ligne users ; message périmé ? --> rien    |
 |    AvatarImageProcessor.process                            |
 |      orientation EXIF, carré centré, 256 px, JPEG ou PNG   |
 |    MediaService.storeGenerated(AVATAR) : avatar/<uuid>     |
 |    ancienne photo et upload : lignes supprimées            |
 ===== commit : objets supprimés du stockage, message acquitté
```

- La requête ne fait que ce qui échoue vite et à peu de frais : les contrôles, le stockage de l'original et la ligne d'outbox. Le décodage et le redimensionnement se font dans le worker.
- Les deux transactions commencent par verrouiller la ligne `users` de Bob : la requête et le worker ne s'entremêlent jamais sur le même compte.

## Pas à pas

**1. La sécurité.** La chaîne de filtres authentifie le JWT (guide 07). Les endpoints des utilisateurs ne sont pas réservés aux comptes actifs (guide 08). [`UserController`][user-controller]`.uploadAvatar` porte `@AllowedRolesOrSelfOnly(UserRole.ADMIN)`, et Bob passe en tant que lui-même. La limite multipart (`max-file-size`, 25MB) laisse passer 3 Mo. Tomcat a écrit la partie dans un fichier temporaire (guide 03). La méthode répond 202 (`@ResponseStatus`).

**2. Le verrou du compte.** [`AvatarService`][avatar-service]`.update` est `@Transactional`. Il appelle d'abord `findByIdForUpdate` ([`UserRepository`][user-repository], `@Lock(PESSIMISTIC_WRITE)`), qui ignore aussi les utilisateurs supprimés. Un compte inconnu ou supprimé donne donc 404. La requête et le worker modifient tous deux le profil et suppriment des lignes `media`. Sans verrou commun pris en premier, ils se sont bloqués l'un l'autre (Postgres 40P01) ou ont lu un upload déjà remplacé (guide 15).

**3. Les contrôles du fichier.** [`MediaService`][media-service]`.store(file, MediaUsage.AVATAR_UPLOAD, uploaderId)` tourne dans la même transaction. Un fichier vide donne 400, un fichier au-dessus de `media.avatar-max-size` (5MB) donne 413. Un type lu dans les octets par [`ContentTypeDetector`][detector] (Tika) hors de JPEG, PNG et WebP donne 415. Le fichier part ensuite en entier vers clamd ([`ClamAvScanner`][clamav], `INSTREAM`). Une menace donne 422 `infected-file`, et un clamd injoignable donne 503 (guide 28). L'auteur enregistré est l'appelant (`CurrentUser`), Bob ou un admin.

**4. Le stockage de l'original.** Le fichier est envoyé au stockage sous `avatar-upload/<uuid>`, jamais sous le nom de Bob. Son SHA-256 est calculé au passage, et une ligne `media` est enregistrée. `MediaService` inscrit une `TransactionSynchronization` : si la transaction 1 est annulée, `afterCompletion` supprime l'objet (guides 14 et 28).

**5. Les dimensions, par l'en-tête.** [`AvatarImageProcessor`][image-processor]`.checkDimensions` ne lit que l'en-tête de l'image (`ImageReader`, `getWidth(0)`, `getHeight(0)`). Au-dessus de 10000 px de côté ou de 40 mégapixels, ou si l'en-tête est illisible, la réponse est 422 `invalid-image`. Quelques kilo-octets peuvent déclarer 50000 x 50000 et épuiser le tas au décodage. Ce contrôle passe après celui du type : un PDF reçoit un 415. Le 422 annule la transaction, et le callback de l'étape 4 supprime l'original déjà stocké.

**6. La photo en attente.** `replacePendingAvatar` range le nouvel upload dans `user_profiles.pending_avatar_media_id`. Si un ancien upload attendait encore, `MediaService.delete` le supprime : la ligne tout de suite, l'objet après le commit (`afterCommit`). La photo actuelle reste en place jusqu'à ce que le worker la remplace.

**7. Le message dans l'outbox.** [`Outbox`][outbox]`.enqueue(AvatarQueues.PROCESS, new AvatarUploaded(userId, uploadId))` écrit une ligne `outbox_messages` dans la transaction 1, avec `Propagation.MANDATORY` (guide 20). La réponse est un `UserResponseDto` dont `avatarUrl` pointe encore vers la photo actuelle (ou l'identicon), avec `avatarPending: true`. `ProfilesForDisplay.load` charge le profil avant la fin de la transaction, car `open-in-view` est désactivé (guide 11). Vient ensuite le commit, puis le 202.

**8. La publication.** Juste après le commit, [`OutboxRelay`][relay] publie le message vers la file durable `avatar.process` et attend la confirmation du broker. Si RabbitMQ est arrêté, `OutboxRelayJob` réessaie (guide 20).

**9. Le worker et le message périmé.** [`AvatarProcessingListener`][listener] (`@RabbitListener(queues = AvatarQueues.PROCESS)`) appelle `AvatarService.process(userId, uploadId)`, `@Transactional`. Il verrouille de nouveau la ligne de Bob, puis vérifie que l'upload est toujours son upload en attente. L'utilisateur peut avoir disparu, ne plus avoir d'upload en attente ou en avoir un autre (Bob a renvoyé une photo, ou retiré la sienne). Dans ces cas, le worker rend la main sans rien faire : un message en double ou périmé est sans effet (guide 19).

**10. Le traitement de l'image.** L'upload en attente est vidé et sa ligne supprimée (l'objet après le commit). Ses octets sont lus dans le stockage. Puis `AvatarImageProcessor.process` :

- vérifie de nouveau les dimensions, décode l'image avec `ImageIO` (les plugins TwelveMonkeys ajoutent WebP) et décide de la transparence sur l'original décodé ;
- lit l'orientation EXIF avec metadata-extractor (1 si elle est absente ou illisible) et l'applique, donc la photo de Bob, enregistrée couchée avec l'orientation 6, est redressée ;
- recadre au centre en carré et réduit à 256 px ;
- ré-encode en PNG si l'original avait de la transparence, sinon en JPEG de qualité 0,85 sur fond blanc. Ce ré-encodage supprime toute métadonnée, GPS compris, et les octets d'origine ne sont jamais servis.

Une image dont l'en-tête passe mais qui ne se décode pas lève `InvalidImageException`. Elle est journalisée en avertissement (« Dropped the profile photo ... ») et jamais rejouée. La transaction se termine sans upload en attente, et l'ancienne photo reste.

**11. Le remplacement.** `MediaService.storeGenerated(bytes, "avatar.jpg", "image/jpeg", MediaUsage.AVATAR, uploaderId)` stocke le résultat sous `avatar/<uuid>`, sans détection de type ni second passage par l'antivirus : l'original a été analysé à l'étape 3, et le résultat n'est fait que de pixels décodés puis ré-encodés par l'application. Seul le type annoncé par `ProcessedImage` est comparé à ceux de `AVATAR`. Avec `store`, une panne de clamd pendant le worker faisait échouer le traitement, puis l'envoyait en dead letter, alors que la photo avait déjà été analysée. `replaceAvatar` y fait pointer `user_profiles.avatar_media_id` et supprime l'ancienne photo par `MediaService.delete`. Au commit, les anciens objets quittent le stockage et le message est acquitté. La réponse suivante pour Bob porte une URL présignée de la nouvelle photo (guides 17 et 28).

**12. La mise à jour partielle.** [`User`][user] et [`UserProfile`][profile] sont `@DynamicUpdate` : l'`UPDATE user_profiles` du worker n'écrit que les colonnes de la photo (guide 12). L'échec mesuré tient à ce que le worker verrouille `users`, pas `user_profiles`. Si un admin désactive Bob pendant ce temps, `user_profiles.status = 'DISABLED'` est validé. Une mise à jour de toute la ligne par le worker réécrivait alors l'ancien statut et réactivait le compte à l'affichage.

## Quand ça se passe mal

Chaque panne, et ce que voit Bob :

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Fichier PDF renommé `.jpg` | Tika lit `application/pdf` (étape 3) | 415, rien n'est stocké |
| Photo de 6 Mo | au-dessus de `media.avatar-max-size` (étape 3) | 413, rien n'est stocké |
| Fichier infecté | clamd répond `FOUND` (étape 3) | 422 `infected-file`, rien n'est stocké |
| clamd injoignable | `AntivirusUnavailableException` (étape 3) | 503 : jamais stocké sans analyse ; Bob réessaie plus tard |
| Image qui déclare 50000 x 50000 | l'en-tête suffit à la refuser (étape 5) | 422 `invalid-image`, l'original est supprimé au rollback |
| En-tête valide, image indécodable | `InvalidImageException` dans le worker (étape 10) | avertissement, pas de réessai ; l'ancienne photo reste |
| Stockage en panne dans le worker | `StorageUnavailableException` (étape 10) | rollback, 4 réessais (2, 6, 18, 54 s), puis `avatar.process.dead-letter` |
| clamd injoignable pendant le worker | le worker n'analyse pas (étape 11) | la photo est remplacée normalement |
| Deux photos coup sur coup | la seconde remplace la première en attente (étape 6) | le message de la première ne fait rien (étape 9) |
| Plantage après le commit, avant l'acquittement | RabbitMQ relivre le message | plus d'upload en attente : message ignoré (étape 9) |
| Un admin désactive Bob en plein traitement | les deux transactions touchent le compte | verrou commun et `@DynamicUpdate` : il reste désactivé (étape 12) |

Les réessais viennent du réglage global `spring.rabbitmq.listener.simple.retry` (4 réessais, 2 s au départ, facteur 3, plafond 1 minute). Le routage de la file envoie ensuite le message vers `avatar.process.dead-letter` ([`AvatarQueues`][queues]). Un upload dont le message est en dead letter reste en attente. L'ancienne photo est conservée et Bob peut renvoyer une photo.

## À retenir

- La requête vérifie tout ce qui coûte peu (taille, type réel, antivirus, dimensions de l'en-tête) et répond 202. Le travail coûteux passe au worker par l'outbox.
- Le worker stocke son résultat par `storeGenerated`, sans seconde analyse : l'application a écrit ces octets, et une panne de clamd ne bloque plus une photo déjà analysée.
- Un premier verrou commun sur la ligne du compte met la requête et le worker en file, et `@DynamicUpdate` empêche le worker de réécrire des colonnes qu'il n'a pas changées.
- Le worker est idempotent : un message pour un upload qui n'est plus en attente ne fait rien.
- Les objets suivent la transaction : supprimés au rollback pour un nouvel upload, après le commit pour une photo remplacée.
- Le ré-encodage garantit la confidentialité : aucun octet d'origine, aucun EXIF, aucun GPS n'est servi.

## Voir aussi

- [28. Uploads et drivers de stockage](../28-uploads-stockage.md) : contrôles, stockage, URL présignées.
- [20. Outbox transactionnelle](../20-outbox-transactionnelle.md) : le message écrit dans la transaction.
- [19. RabbitMQ avec Spring AMQP](../19-rabbitmq-spring-amqp.md) : réessais et dead letters.
- [15. Concurrence et verrous](../15-concurrence-verrous.md) : `SELECT ... FOR UPDATE` et l'interblocage.
- [12. Mapping Hibernate 7](../12-mapping-hibernate.md) : `@DynamicUpdate`.
- [08. Autorisations](../08-autorisations.md) : `@AllowedRolesOrSelfOnly`.

[user-controller]: ../../../src/main/java/io/julienmetral/tasks/identity/controllers/UserController.java
[avatar-service]: ../../../src/main/java/io/julienmetral/tasks/identity/services/AvatarService.java
[user-repository]: ../../../src/main/java/io/julienmetral/tasks/identity/repositories/UserRepository.java
[media-service]: ../../../src/main/java/io/julienmetral/tasks/media/services/MediaService.java
[image-processor]: ../../../src/main/java/io/julienmetral/tasks/media/services/AvatarImageProcessor.java
[clamav]: ../../../src/main/java/io/julienmetral/tasks/media/services/ClamAvScanner.java
[detector]: ../../../src/main/java/io/julienmetral/tasks/media/services/ContentTypeDetector.java
[outbox]: ../../../src/main/java/io/julienmetral/tasks/messaging/services/Outbox.java
[listener]: ../../../src/main/java/io/julienmetral/tasks/identity/messaging/AvatarProcessingListener.java
[queues]: ../../../src/main/java/io/julienmetral/tasks/identity/messaging/AvatarQueues.java
[user]: ../../../src/main/java/io/julienmetral/tasks/identity/entities/User.java
[profile]: ../../../src/main/java/io/julienmetral/tasks/identity/entities/UserProfile.java
[relay]: ../../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelay.java
