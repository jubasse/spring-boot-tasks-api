# Cas pratique : La livraison d'un webhook

[Sommaire](../README.md)

## Le scénario

Une utilisatrice déclare un webhook vers son outil d'automatisation. Un collègue lui assigne une tâche : la notification doit partir signée, après le commit. Le récepteur tombe en panne plusieurs heures puis revient : elle doit arriver quand même, sans que l'API s'acharne sur lui ni retarde les autres livraisons.

## Vue d'ensemble

Le trajet d'une notification :

```
 TaskService.assign                                transaction de la tâche
      |  TaskAssigned
      v
 TaskNotificationPublisher          une notification par destinataire actif
      |  TaskNotificationCreated
      v
 WebhookNotificationSender          endpoints actifs abonnés à task.assigned
      |
      v
 WebhookDeliveryService.schedule    ligne PENDING + outbox "webhook.deliver"
      |
      |  après le commit                                         asynchrone
      v
 file "webhook.deliver" --> WebhookDeliveryListener (4 par instance)
      |
      v
 deliver()
      |
      +-- 1. prepare     transaction courte : livraison, endpoint, compte
      +-- 2. tryAcquire  disjoncteur et cloison de l'hôte (refus : report)
      +-- 3. send        hors transaction : signature, appel HTTP filtré
      +-- 4. record      transaction courte : DELIVERED ou prochain essai
      |
      v
 next_attempt_at, en base
      |
      v
 WebhookRetryJob, toutes les 30 s         SKIP LOCKED, bail de 5 min
      |
      +--> outbox "webhook.deliver" --> nouvel essai, même webhook-id
```

- L'appel HTTP se fait entre deux transactions courtes : un récepteur lent ne tient aucune connexion à la base.

## Pas à pas

**1. La déclaration.** `POST /api/v1/users/{id}/webhooks` avec `url` et `events`, pour l'utilisatrice ou un admin (guide 08). [`WebhookEndpointService`][endpoint-service] verrouille la ligne du compte (`findByIdForUpdate`) puis compte ses endpoints : à 5, 422 `webhook-limit-reached`. Grâce au verrou, deux créations simultanées se comptent l'une l'autre (guide 15).

**2. Le contrôle de l'URL.** [`WebhookUrlPolicy`][url-policy] exige une URL absolue, sans identifiants, en HTTPS sur le port 443 (sauf en développement). Elle résout l'hôte et passe chaque adresse au filtre sortant : une adresse privée donne 422 `webhook-url-not-allowed`. La vraie barrière est à chaque appel (étape 8).

**3. Le secret.** [`WebhookSecrets`][secrets] crée un secret Standard Webhooks : `whsec_` suivi du Base64 de 32 octets aléatoires. Il le chiffre en AES-256-GCM avec `webhooks.encryption-key` et préfixe le résultat de `v1:`, qui nomme la clé. Chiffré et non haché, car le serveur doit le relire pour signer ; seule la réponse 201 le montre en clair.

**4. L'événement.** `TaskService.assign` publie `TaskAssigned`. [`TaskNotificationPublisher`][publisher] en fait un `TaskNotificationCreated`, sauf si l'action vient d'elle ou si son compte n'est pas actif. [`WebhookNotificationSender`][sender] cherche les endpoints actifs abonnés à `task.assigned` et sérialise une fois le contenu `{type, timestamp, data}`, dans la transaction de l'assignation (guide 16).

**5. La livraison planifiée.** `WebhookDeliveryService.schedule` (`MANDATORY`) crée une [`WebhookDelivery`][delivery] `PENDING` par endpoint et écrit `webhook.deliver` dans l'outbox (guide 20) : rien ne part si l'assignation est annulée. Le contenu est en `TEXT`, car la signature couvre ces octets et un `jsonb` les réordonnerait. `next_attempt_at` vaut déjà maintenant plus 5 min (`webhooks.delivery-lease`) : si le message se perd, la livraison redevient due. Son id est le `webhook-id` de chaque essai.

**6. La consommation.** [`WebhookDeliveryListener`][delivery-listener] reçoit le message par sa propre fabrique ([`WebhookQueues`][queues]) : 4 consommateurs par instance, un message à la fois, sans les réessais du listener, qui dormiraient sur le thread consommateur (guide 19).

**7. Préparer, puis sortir de la transaction.** [`WebhookDeliveryService`][delivery-service]`.deliver` lit dans une transaction courte : la livraison doit être `PENDING` (sinon c'est un doublon), l'endpoint actif (sinon `FAILED`, `EndpointDisabled`) et le compte actif (sinon `FAILED`, `AccountNotActive`). Il en tire l'URL, les octets et les secrets déchiffrés, puis la transaction se termine (guide 14).

**8. Signer et appeler.** [`WebhookSigner`][signer] ajoute `webhook-id`, `webhook-timestamp` et `webhook-signature` : `v1,` puis le Base64 d'un HMAC-SHA256 de `id.timestamp.contenu`, un par secret valide (deux pendant une rotation). [`WebhookClient`][client] (`@PostExchange`) envoie ces octets tels quels : 5 s pour se connecter, 15 s pour répondre, ni redirection ni cookie, chaque statut revenant comme une réponse. Le filtre d'adresses joue à chaque appel : un hôte désormais résolu vers une adresse privée lève `FilteredHostException`, notée `DestinationNotAllowed` (guide 26).

**9. Un réessai immédiat, un seul.** `send` porte le `@Retryable` de Spring 7, activé par `@EnableResilientMethods` sur [`WebhookConfiguration`][config] : un nouvel essai après 200 à 300 ms, seulement sur `ConnectException` ou `ConnectTimeoutException`. Sans connexion, la requête n'a pas pu arriver : la renvoyer ne crée pas de doublon. Un délai de lecture dépassé n'est pas réessayé, car le récepteur a peut-être traité la requête (guide 27).

**10. Enregistrer le résultat.** `record` ouvre une transaction et verrouille la livraison, puis l'endpoint. Il incrémente `attempts` et garde le statut et un type d'erreur court (`Timeout`, `ConnectionFailed`), jamais le message d'une exception, qui contient l'URL. Un 2xx passe la livraison `DELIVERED`.

**11. La panne du récepteur.** Chaque essai finit en `ConnectionFailed`. Le premier échec date `failing_since` sur l'endpoint, et chaque échec programme l'essai suivant :

| Essai | Attente après l'échec précédent |
|---|---|
| 2 | 5 s |
| 3 | 5 min |
| 4 | 30 min |
| 5 | 2 h |
| 6 | 5 h |
| 7 | 10 h |

Chaque attente varie de 20 % : des livraisons échouées ensemble ne reviennent pas ensemble. Le septième essai tombe 17 h 35 après le premier envoi. Un `Retry-After` plus long l'emporte, plafonné à 10 h. Après un septième échec, la livraison passe `FAILED`.

**12. Le disjoncteur de l'hôte.** Avant chaque appel, [`WebhookResilience`][resilience] interroge le disjoncteur et la cloison de l'hôte (Resilience4j, en mémoire de chaque instance). Il s'ouvre quand la moitié des appels des 60 dernières secondes ont échoué (5xx ou pas de réponse), dès 5 appels ; il refuse tout pendant 1 min, puis laisse passer un appel de test. La cloison limite à 2 les appels simultanés. Un refus reporte la livraison d'une minute et une seconde (`HostUnavailable`), sans consommer d'essai.

**13. Le retour.** [`WebhookRetryJob`][retry-job] tourne toutes les 30 s sur chaque instance, sans ShedLock (guide 21). [`WebhookDeliveryQueries`][queries] prend jusqu'à 100 livraisons dues avec `FOR UPDATE SKIP LOCKED` et repousse leur `next_attempt_at` de 5 min, le bail de l'essai : chaque instance prend un lot différent (guide 15). Le récepteur revenu, l'essai suivant (même `webhook-id`, nouvel horodatage) reçoit un 2xx : `DELIVERED`, et `failing_since` est effacé.

**14. Quand la panne dure.** Un 410 désactive l'endpoint (`GONE`) et termine la livraison. Si tous les essais échouent depuis 3 jours (`webhooks.disable-after`), le prochain échec désactive l'endpoint (`FAILING`) et envoie au propriétaire un mail qui ne cite que l'hôte. Les livraisons abandonnant après 17 h, ce sont les événements suivants qui font durer l'échec. Un `PUT` avec `"enabled": true` réactive l'endpoint.

**15. Rejouer, tester, purger.** `POST .../deliveries/{deliveryId}/redeliver` remet une livraison `PENDING` avec tout le calendrier, sous le même `webhook-id`. `POST .../test` envoie aussitôt un `webhook.test` signé, sans disjoncteur ni enregistrement, limité à 10 par heure (guide 10). Chaque nuit à 03:45, [`WebhookDeliveryPurgeJob`][purge] supprime les livraisons de plus de 30 jours (noms, extraits de commentaires).

**16. La variante Slack.** Un endpoint `SLACK` n'accepte que `https://hooks.slack.com/services/...`, URL chiffrée et masquée car c'est son secret. Le contenu est un message Slack ([`SlackMessages`][slack]), sans signature. Les 403, 404 et 410 de Slack désactivent l'endpoint ; un 400 termine la livraison sans réessai.

## Quand ça se passe mal

Les pannes prévues et la reprise :

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Connexion refusée | l'appel échoue sans atteindre le récepteur | un réessai après 200 à 300 ms, puis le calendrier en base |
| Récepteur plus lent que 15 s | l'essai finit en `Timeout` | pas de réessai immédiat, le calendrier s'applique |
| Hôte en panne pour tous ses abonnés | son disjoncteur s'ouvre | les livraisons attendent sans consommer d'essai |
| 503 avec `Retry-After: 3600` | Apache ne renvoie rien de lui-même | le prochain essai attend au moins une heure |
| Hôte qui pointe vers une adresse interne | le filtre refuse la connexion | `FAILED` aussitôt, sans compter contre l'hôte |
| Message perdu, ou instance arrêtée pendant l'essai | la livraison reste `PENDING` | elle redevient due à la fin de son bail de 5 min |
| Message `webhook.deliver` en double | une livraison déjà `DELIVERED` revient | `prepare` voit qu'elle n'est plus `PENDING`, et n'envoie rien |

La livraison reste « au moins une fois » : un arrêt entre la réponse du récepteur et l'enregistrement fait renvoyer la notification, et le récepteur écarte un `webhook-id` déjà traité.

## À retenir

- L'appel HTTP se fait hors transaction, entre une lecture et un enregistrement.
- Les réessais vivent en base : calendrier, aléa, bail, et `SKIP LOCKED` pour partager le travail entre instances.
- On ne réessaie tout de suite que ce qui n'a pas pu arriver.
- Le disjoncteur par hôte ménage un récepteur en panne, sans faire perdre d'essai.
- On signe des octets stockés, jamais un objet sérialisé à nouveau ; le `webhook-id` stable sert à écarter les doublons.

## Voir aussi

- [14. Transactions](../14-transactions.md) : `TransactionTemplate` et l'appel hors transaction.
- [15. Concurrence et verrous](../15-concurrence-verrous.md) : `FOR UPDATE`, `SKIP LOCKED` et bail.
- [16. Événements applicatifs et `@Async`](../16-evenements-async.md) : les listeners dans la transaction.
- [20. Outbox transactionnelle](../20-outbox-transactionnelle.md) : `webhook.deliver` n'existe qu'après le commit.
- [21. Jobs planifiés et ShedLock](../21-jobs-planifies-shedlock.md) : le poller sans verrou et la purge.
- [26. Clients HTTP et filtre SSRF](../26-clients-http-ssrf.md) : client déclaratif et filtre d'adresses.
- [27. Résilience](../27-resilience.md) : `@Retryable`, disjoncteur et cloison par hôte.
- [Cas pratique : Une notification de bout en bout](notification-bout-en-bout.md) : le même événement, vers le mail et le SSE.
- [README du dépôt, « Receive task notifications by webhook »](../../../README.md#receive-task-notifications-by-webhook) : le contrat pour les récepteurs.

[endpoint-service]: ../../../src/main/java/io/julienmetral/tasks/notification/services/WebhookEndpointService.java
[url-policy]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookUrlPolicy.java
[secrets]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookSecrets.java
[publisher]: ../../../src/main/java/io/julienmetral/tasks/notification/services/TaskNotificationPublisher.java
[sender]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookNotificationSender.java
[delivery]: ../../../src/main/java/io/julienmetral/tasks/notification/entities/WebhookDelivery.java
[delivery-listener]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryListener.java
[queues]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookQueues.java
[delivery-service]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[signer]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookSigner.java
[client]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookClient.java
[config]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookConfiguration.java
[resilience]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookResilience.java
[retry-job]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookRetryJob.java
[queries]: ../../../src/main/java/io/julienmetral/tasks/notification/repositories/WebhookDeliveryQueries.java
[purge]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryPurgeJob.java
[slack]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/SlackMessages.java
