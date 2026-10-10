# Cas pratique : Une notification de bout en bout

[Sommaire](../README.md)

## Le scénario

Un administrateur assigne une tâche à Bruno. Bruno a l'application ouverte (un flux de notifications et la page de la tâche), un webhook abonné à `task.assigned` et les emails d'assignation activés. Il doit recevoir un email, un appel HTTP signé et une notification à l'écran. S'il est annulé, il ne doit rien recevoir.

## Vue d'ensemble

Tout s'écrit dans la transaction, puis chaque canal part de son côté après le commit :

```
 Admin : PATCH /api/v1/tasks/{id}/assign
      |
      v
 +-------------------------- une seule transaction --------------------------+
 |  TaskService.assign : UPDATE tasks, INSERT task_events                    |
 |    |                                                                      |
 |    +--> TaskEventRecorded --> RealtimeBroadcasts ---> outbox (salle)      |
 |    |                                                                      |
 |    +--> TaskAssigned                                                      |
 |           |                                                               |
 |           +--> TaskNotificationSender --> MailService --> outbox (mail)   |
 |           |                                                               |
 |           +--> TaskNotificationPublisher                                  |
 |                  |  TaskNotificationCreated, une par destinataire         |
 |                  |                                                        |
 |                  +--> WebhookNotificationSender --> livraison + outbox    |
 |                  +--> RealtimeBroadcasts --> outbox (notification)        |
 +---------------------------------------------------------------------------+
      |
      |  commit ; OutboxRelay publie chaque ligne (@Async)
      v
      +-----------------------+-----------------------+
      |                       |                       |
      v                       v                       v
 file mail.send         file webhook.deliver    exchange tasks.realtime
      |                       |                 (fanout, une file
      v                       v                  par instance)
 MailQueueListener      WebhookDeliveryListener       |
      |                       |                       v
      v                       v                 RealtimeListener
 SMTP                   WebhookClient                 |
                              |                       +--> NotificationStreams
                              v                       |    (flux SSE)
                        récepteur HTTPS               +--> TaskRooms
                                                           (salle STOMP)
```

- Le paquet `task` ne connaît aucun canal : il publie des événements, et les listeners des paquets `notification` et `realtime` décident.

## Pas à pas

**1. L'assignation.** `PATCH /api/v1/tasks/{id}/assign` avec `{"userId": "..."}`, réservé aux administrateurs (`@AdminOnly`). [`TaskService.assign`][task-service] ouvre la transaction et vérifie que Bruno est actif, sinon 422 `assignee-not-active`. Il change l'assigné et appelle `TaskEventService.assignmentChanged`, qui écrit la ligne `ASSIGNED` de l'historique. Il publie ensuite `TaskAssigned`, plus `TaskUnassigned` pour l'ancien assigné s'il y en avait un (guide 16).

**2. Des listeners dans la transaction.** Ces `@EventListener` simples sont appelés tout de suite, sur le même thread, dans la transaction ouverte. Ce qu'ils écrivent est validé ou annulé avec l'assignation, et une exception de l'un d'eux annulerait l'assignation. Ils écrivent donc en base seulement, sans appel réseau (guide 16).

**3. Le tri des destinataires.** [`TaskNotificationPublisher`][publisher] fait de `TaskAssigned` un `TaskNotificationCreated` par destinataire. Il applique les règles que partagent webhooks et flux. Personne n'est notifié de sa propre action : un admin qui s'assigne une tâche ne reçoit rien. Seul un compte actif reçoit quelque chose. Le compte est relu en base, car le cache de statut peut avoir 30 s de retard.

**4. L'email.** [`TaskNotificationSender`][mail-sender] écoute aussi `TaskAssigned`, avec ses propres règles. Les conditions sont les mêmes, plus l'interrupteur `taskAssigned` des réglages de Bruno (tout est activé tant qu'il n'a pas de ligne de réglages). Il écrit le texte et appelle [`MailService.send`][mail-service]. Celui-ci n'envoie rien : il écrit le message dans l'outbox pour la file `mail.send` (guides 20 et 23).

**5. Le webhook.** [`WebhookNotificationSender`][webhook-sender] écoute `TaskNotificationCreated` et cherche les endpoints actifs de Bruno abonnés à `task.assigned`. Ces abonnements remplacent les interrupteurs d'email. Il sérialise le corps (`type`, `timestamp`, `data`, ou un message Slack). [`WebhookDeliveryService.schedule`][delivery-service] enregistre une livraison `PENDING` et écrit son id dans l'outbox pour la file `webhook.deliver`. Les essais sont détaillés dans [La livraison d'un webhook](livraison-webhook.md).

**6. Le temps réel.** [`RealtimeBroadcasts`][broadcasts] écrit deux diffusions dans l'outbox, vers l'exchange `tasks.realtime`. La première est une `UserNotification` pour Bruno, tirée de `TaskNotificationCreated`. La seconde est un `TaskRoomEvent` pour la salle de la tâche, tiré de la ligne d'historique (`TaskEventRecorded`). La salle sert tous ceux qui regardent la tâche, la notification Bruno seul (guide 24).

**7. Le commit, puis la publication.** Le commit valide d'un coup la tâche, l'historique, la livraison et quatre lignes d'outbox. Pour chaque ligne, [`Outbox`][outbox] a enregistré une synchronisation `afterCommit`. [`OutboxRelay.publishNow`][relay] tourne alors sur un thread `@Async` et verrouille la ligne (`FOR UPDATE SKIP LOCKED`). Il publie, attend la confirmation du broker et vérifie que le message n'est pas revenu faute de file. Il remplit enfin `published_at`. L'id de la ligne devient l'id du message AMQP. Si l'assignation est annulée, les lignes disparaissent avec le rollback (guides 14, 19 et 20).

**8. L'envoi de l'email.** [`MailQueueListener`][mail-listener] consomme `mail.send` et envoie par SMTP (`mail.from`). Un échec est réessayé dans le consommateur, avec un délai croissant (environ 1,5 min en tout). Le message part ensuite dans `mail.send.dead-letter` (guides 19 et 23).

**9. L'appel du webhook.** `WebhookDeliveryListener` reçoit l'id par une fabrique sans réessais de listener, à 4 consommateurs par instance. `WebhookDeliveryService.deliver` relit la livraison dans une transaction courte et interroge le disjoncteur et la cloison de l'hôte. Il appelle [`WebhookClient`][webhook-client] hors transaction, avec un corps signé et un en-tête `webhook-id` égal à l'id de la livraison. Il enregistre ensuite le résultat dans une seconde transaction. Le client HTTP ne joint que des adresses publiques (guides 26 et 27).

**10. La notification à l'écran.** L'exchange fanout copie chaque diffusion dans la file de chaque instance. Sur toutes, [`RealtimeListener`][realtime-listener] (un seul consommateur, sans réessais) remet `UserNotification` à [`NotificationStreams`][streams]. L'événement entre dans le buffer de replay de l'instance. Sur celle où Bruno est connecté, il entre aussi dans le buffer de son flux. Le thread virtuel du flux l'écrit : `id` (la ligne d'outbox), `event: task.assigned`, `data`. Le `TaskRoomEvent` part vers [`TaskRooms`][rooms], qui l'envoie aux abonnés STOMP de `/topic/tasks/{id}` avec l'en-tête `event-id`. Il dit seulement ce qui s'est passé : la page relit la tâche par l'API (guides 24 et 25).

## Quand ça se passe mal

Chaque canal se remet à sa façon :

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Transaction annulée (conflit de version, assigné inactif) | aucune ligne n'est validée | rien ne part : ni email, ni webhook, ni notification |
| RabbitMQ arrêté au commit | pas de confirmation, la ligne garde `last_error` | `OutboxRelayJob` réessaie : 5 s, doublé, plafond 10 min |
| Plantage après la confirmation, avant le commit du relais | message publié, `published_at` vide | il repart : chaque consommateur tolère un doublon |
| Serveur SMTP en panne | `MailQueueListener` lève une exception | réessais dans le consommateur, puis `mail.send.dead-letter` |
| Récepteur du webhook arrêté | connexion refusée, un réessai rapide | nouvel essai à 5 s, 5 min, 30 min, 2 h, 5 h et 10 h, puis `FAILED` |
| Message `webhook.deliver` perdu | la livraison reste `PENDING` | `WebhookRetryJob` la reprend à la fin de son bail de 5 min |
| Bruno a fermé l'onglet | l'écriture échoue, le flux est oublié | l'événement reste 5 min en mémoire ; `Last-Event-ID` le rejoue |
| Navigateur trop lent | son buffer de 100 événements déborde | flux fermé ; le client revient et rejoue ce qu'il a manqué |
| Instance coupée de RabbitMQ | sa file disparaît avec les événements en attente | à la reconnexion, `resync` à tous ses flux : les clients rechargent |

- Une notification à l'écran n'est jamais réessayée : un événement temps réel périmé vaut moins que ceux qui suivent. Le replay et le `resync` rattrapent les trous.
- La livraison est « au moins une fois » sur les trois canaux. L'id de l'événement SSE, l'en-tête `event-id` de la salle et l'en-tête `webhook-id` permettent d'écarter un doublon. Un email en double, lui, arrive deux fois.

## À retenir

- Un seul commit décide de tout : outbox et livraison sont écrites dans la transaction de l'assignation, et publiées seulement après.
- Les règles « pas sa propre action, compte actif » vivent en un seul endroit pour webhooks et flux. Les emails gardent les leurs.
- Chaque canal a sa politique d'échec : réessais du consommateur pour l'email, calendrier en base pour le webhook, aucun réessai pour le temps réel.
- Diffuser à toutes les instances par un exchange fanout évite de savoir où le client est connecté.

## Voir aussi

- [16. Événements applicatifs et `@Async`](../16-evenements-async.md) : les listeners dans la transaction.
- [19. RabbitMQ avec Spring AMQP](../19-rabbitmq-spring-amqp.md) : files, dead letters, exchange fanout.
- [20. Outbox transactionnelle](../20-outbox-transactionnelle.md) : publier si et seulement si le commit a lieu.
- [23. Envoi de mails](../23-envoi-mails.md) : la file `mail.send` et ses réessais.
- [24. Flux de notifications (SSE)](../24-flux-notifications-sse.md) : buffers, replay et `resync`.
- [25. WebSocket et STOMP](../25-websocket-stomp.md) : les salles de tâches.
- [26. Clients HTTP et filtre SSRF](../26-clients-http-ssrf.md) et [27. Résilience](../27-resilience.md) : l'appel du webhook.
- [La livraison d'un webhook](livraison-webhook.md) : le calendrier des essais et le disjoncteur.

[task-service]: ../../../src/main/java/io/julienmetral/tasks/task/services/TaskService.java
[publisher]: ../../../src/main/java/io/julienmetral/tasks/notification/services/TaskNotificationPublisher.java
[mail-sender]: ../../../src/main/java/io/julienmetral/tasks/notification/mail/TaskNotificationSender.java
[mail-service]: ../../../src/main/java/io/julienmetral/tasks/mail/MailService.java
[webhook-sender]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookNotificationSender.java
[delivery-service]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[broadcasts]: ../../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcasts.java
[outbox]: ../../../src/main/java/io/julienmetral/tasks/messaging/services/Outbox.java
[relay]: ../../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelay.java
[mail-listener]: ../../../src/main/java/io/julienmetral/tasks/mail/MailQueueListener.java
[webhook-client]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookClient.java
[realtime-listener]: ../../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeListener.java
[streams]: ../../../src/main/java/io/julienmetral/tasks/realtime/sse/NotificationStreams.java
[rooms]: ../../../src/main/java/io/julienmetral/tasks/realtime/stomp/TaskRooms.java
