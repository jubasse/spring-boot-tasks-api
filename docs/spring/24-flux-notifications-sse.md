# 24. Flux de notifications (SSE)

[Sommaire](README.md) | [< 23. Envoi de mails](23-envoi-mails.md) | [25. WebSocket et STOMP >](25-websocket-stomp.md)

## Contexte

- **Problème** : afficher une notification dès qu'elle arrive, sans interroger le serveur en boucle.
- **Quand l'utiliser** : le serveur pousse des événements, le client se contente d'écouter.
- **Quand s'en passer** : des données qui changent rarement (un rafraîchissement suffit) ; un client qui envoie aussi des messages (guide 25).
- **Dans ce dépôt** : `GET /api/v1/notifications/stream`, les notifications de tâches de l'utilisateur connecté.
- **Prérequis** : guides 07 (sécurité) et 16 (événements applicatifs). Versions : Spring Boot 4.1, Spring Framework 7.

Les trois façons de recevoir des nouveautés :

| Critère | Polling | SSE | WebSocket |
|---|---|---|---|
| Sens | le client demande en boucle | le serveur pousse | dans les deux sens |
| Transport | requêtes HTTP répétées | une réponse HTTP qui reste ouverte | un protocole propre, ouvert par HTTP |
| Reconnexion | sans objet | prévue par le standard | à écrire soi-même |
| Coût | une requête par intervalle, même sans nouveauté | une connexion par client | une connexion par client |

## Concepts clés

### Vocabulaire

- **SSE** (Server-Sent Events) : un standard du web pour pousser des événements texte sur une réponse HTTP.
- **Flux** : une réponse HTTP ouverte, dans laquelle le serveur écrit au fil de l'eau.
- **Heartbeat** : une ligne de commentaire envoyée pendant les silences, pour que rien ne coupe la connexion.
- **Replay** : le renvoi des événements manqués pendant une coupure.

### L'échange

Le navigateur ouvre une requête, et le serveur ne la ferme pas :

```
  Navigateur                                Serveur
      |                                        |
      |---- GET /notifications/stream -------->|
      |                                        |  la réponse reste ouverte
      |<---- event: task.assigned -------------|
      |<---- : heartbeat ----------------------|  après 20 s sans rien
      |<---- event: task.commented ------------|
      |                                        |
```

### Le format

Un flux est du texte, une ligne par champ, et une ligne vide entre deux événements :

| Ligne | Rôle |
|---|---|
| `id: 0192f3a1` | numéro de l'événement, renvoyé par le client après une coupure |
| `event: task.assigned` | type de l'événement |
| `data: {...}` | contenu, souvent du JSON |
| `retry: 3000` | délai de reconnexion conseillé au client, en millisecondes |
| `: heartbeat` | commentaire, ignoré par le client |

### La reconnexion

Après une coupure, le client rouvre le flux avec l'en-tête `Last-Event-ID`, qui porte le dernier id reçu. Le serveur peut alors renvoyer ce qui a été manqué.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  notification/
    NotificationStreams.java            les flux (étapes 1, 4, 5)
    NotificationStreamController.java   l'endpoint (étape 2)
  task/
    TaskAssigned.java                   l'événement (étape 3)
    TaskService.java                    le publie (étape 3)
  config/
    SecurityConfiguration.java          laisse finir les flux (étape 6)
src/main/resources/static/
  notifications.js                      le client (étape 7)
src/test/java/.../
  NotificationStreamTests.java          la vérification (étape 8)
```

**Étape 1. Garder les flux ouverts.** Un composant range chaque `SseEmitter` par utilisateur, et l'oublie quand le flux se termine.

```java
@Component
public class NotificationStreams {

    private final Map<UUID, Set<SseEmitter>> streamsByUser = new ConcurrentHashMap<>();

    public SseEmitter open(UUID userId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(15).toMillis());
        streamsByUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(emitter);

        Runnable forget = () -> streamsByUser.computeIfPresent(userId, (id, streams) -> {
            streams.remove(emitter);
            return streams.isEmpty() ? null : streams;
        });
        emitter.onCompletion(forget);          // client parti, ou flux terminé
        emitter.onError(error -> forget.run());
        emitter.onTimeout(emitter::complete);  // les 15 min sont écoulées
        return emitter;
    }
}
```

**Étape 2. Exposer l'endpoint.** Le contrôleur renvoie l'emitter, et Spring garde alors la réponse ouverte.

```java
@RestController
@RequiredArgsConstructor
class NotificationStreamController {

    private final NotificationStreams notificationStreams;

    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(JwtAuthenticationToken authentication) {
        UUID userId = UUID.fromString(authentication.getToken().getClaimAsString("uid"));
        return notificationStreams.open(userId);
    }
}
```

**Étape 3. Publier un événement depuis le service.** Le service ne connaît pas les flux : il annonce seulement ce qui s'est passé.

```java
public record TaskAssigned(UUID eventId, UUID taskId, UUID assigneeId) {
}
```

```java
@Transactional
public void assign(UUID taskId, UUID assigneeId) {
    // ... la modification de la tâche
    // events : l'ApplicationEventPublisher injecté
    events.publishEvent(new TaskAssigned(UUID.randomUUID(), taskId, assigneeId));
}
```

**Étape 4. Écrire l'événement dans les flux, après le commit.** Avec `@TransactionalEventListener`, rien ne part pour un changement annulé ; un événement publié hors transaction demande `fallbackExecution = true`.

```java
// dans NotificationStreams
@TransactionalEventListener
public void onTaskAssigned(TaskAssigned event) {
    for (SseEmitter emitter : streamsByUser.getOrDefault(event.assigneeId(), Set.of())) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.eventId().toString())
                    .name("task.assigned")
                    .data(event));
        } catch (IOException | IllegalStateException clientGone) {
            // Spring termine la réponse lui-même, puis appelle onError
        }
    }
}
```

**Étape 5. Ajouter un heartbeat.** Un commentaire toutes les 20 s empêche les reverse proxies de couper une ligne silencieuse (il faut `@EnableScheduling`, guide 21).

```java
// dans NotificationStreams
@Scheduled(fixedRate = 20_000)
public void heartbeat() {
    streamsByUser.values().forEach(streams -> streams.forEach(emitter -> {
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (IOException | IllegalStateException clientGone) {
            // Spring termine la réponse lui-même, puis appelle onError
        }
    }));
}
```

**Étape 6. Laisser finir les flux dans la sécurité.** Pendant qu'un flux est ouvert, Spring rend le thread à Tomcat. Pour terminer la réponse, Tomcat repasse la requête dans la chaîne de filtres (dispatch `ASYNC`) : elle a déjà été autorisée à l'ouverture, on la laisse passer.

```java
http.authorizeHttpRequests(auth -> auth
        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
        // ... les autres règles
);
```

**Étape 7. Écouter côté navigateur.** L'`EventSource` natif ne sait pas envoyer d'en-tête `Authorization`. La bibliothèque `@microsoft/fetch-event-source` le peut, et elle renvoie `Last-Event-ID` toute seule après une coupure.

```js
import { fetchEventSource } from '@microsoft/fetch-event-source';

await fetchEventSource('/api/v1/notifications/stream', {
  headers: { Authorization: `Bearer ${accessToken}` },
  onmessage(event) {
    if (event.event === 'resync') {
      reloadNotifications();                 // voir les concepts avancés
    } else {
      showNotification(event.event, JSON.parse(event.data));
    }
  },
});
```

**Étape 8. Vérifier.** MockMvc ouvre le flux, le test déclenche l'action, puis attend l'événement dans la réponse.

```java
@SpringBootTest
@AutoConfigureMockMvc
class NotificationStreamTests {

    @Autowired MockMvc mockMvc;
    @Autowired TaskService taskService;

    @Test
    void assigningATaskSendsAnEventToTheAssignee() throws Exception {
        MvcResult stream = mockMvc.perform(get("/notifications/stream")
                        .with(jwt().jwt(token -> token.claim("uid", ASSIGNEE_ID.toString()))))
                .andExpect(request().asyncStarted())
                .andReturn();

        taskService.assign(TASK_ID, ASSIGNEE_ID);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(stream.getResponse().getContentAsString()).contains("event:task.assigned"));
    }
}
```

En manuel, `curl -N` affiche les événements au fil de l'eau :

```bash
curl -N -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/notifications/stream
```

### Référence rapide

Les méthodes de `SseEmitter` :

| Méthode | Rôle |
|---|---|
| `new SseEmitter(ms)` | crée un flux, avec sa durée maximale |
| `event().id(...).name(...).data(...)` | construit un événement |
| `event().comment("...")` | construit une ligne de commentaire (heartbeat) |
| `event().reconnectTime(ms)` | remplit le champ `retry:` |
| `send(event)` | écrit tout de suite dans la réponse |
| `complete()` | termine la réponse normalement |
| `onCompletion`, `onTimeout`, `onError` | réagissent à la fin du flux |

Limites de cette version : un client lent ralentit l'envoi aux autres, rien n'est rejoué après une coupure, et un seul serveur connaît les flux.

## Concepts avancés

### Sous le capot : un buffer et un thread par flux

Avec un seul thread qui écrit à tous, un client lent bloque tous les autres. On place un buffer et un thread devant chaque flux :

```
       thread qui distribue (un seul, pour tous les flux)
                 |
                 |  dépose sans attendre
                 v
       +------------------+     +------------------+     +----------+
       |  buffer (100)    | --> |  thread virtuel  | --> |  client  |
       +------------------+     +------------------+     +----------+
                 |
                 +-- plein --> on coupe ce flux ; le client revient et rejoue
```

```java
final class BufferedStream {

    record Event(String id, String name, String data) {
    }

    private static final Event WAKE_UP = new Event(null, null, null);   // jamais écrit

    private final SseEmitter emitter;
    private final BlockingQueue<Event> buffer = new ArrayBlockingQueue<>(100);
    private final AtomicBoolean closed = new AtomicBoolean();

    BufferedStream(SseEmitter emitter) {
        this.emitter = emitter;
        Thread.ofVirtual().start(this::writeEvents);
    }

    void send(Event event) {                    // appelé par le thread qui distribue
        if (!closed.get() && !buffer.offer(event)) {
            close();                            // buffer plein : le client ne suit pas
        }
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            buffer.clear();
            buffer.offer(WAKE_UP);              // réveille le thread, sans l'interrompre
            Thread.ofVirtual().start(() -> {    // complete() peut attendre une écriture lente
                try {
                    emitter.complete();
                } catch (IllegalStateException alreadyCompleted) {
                    // le client est parti en même temps
                }
            });
        }
    }

    private void writeEvents() {
        try {
            while (true) {
                Event event = buffer.poll(20, TimeUnit.SECONDS);
                if (closed.get()) {
                    return;
                }
                emitter.send(event == null
                        ? SseEmitter.event().comment("heartbeat")           // 20 s de silence
                        : SseEmitter.event().id(event.id()).name(event.name()).data(event.data()));
            }
        } catch (IOException | IllegalStateException clientGone) {
            closed.set(true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
```

Le heartbeat vient maintenant du thread de chaque flux, et le `@Scheduled` de l'étape 5 devient inutile.

### L'ouverture contrôlée

Chaque ouverture passe par ces contrôles, dans cet ordre :

```
       GET /notifications/stream
                 |
                 v
       trop de flux pour ce compte ? -- oui --> 429
                 | non
                 v
       enregistrer le flux
                 |
                 v
       compte actif ? (lu en base) ---- non --> retirer le flux, 403
                 | oui
                 v
       Last-Event-ID présent ? -------- oui --> replay, ou "resync"
                 | non
                 v
       flux ouvert jusqu'à l'expiration du jeton
```

- On enregistre avant de lire le compte. Une désactivation validée après la lecture trouve alors le flux, et le ferme.
- Le flux s'arrête quand le jeton expire. Le client revient avec un nouveau jeton, et les droits sont vérifiés à nouveau.
- Le champ `retry:` envoyé à l'ouverture a une part aléatoire. Après un redémarrage, les clients ne reviennent pas tous dans la même seconde.

### Le replay

Le serveur garde en mémoire les événements des dernières minutes :

```
       reconnexion avec Last-Event-ID: 42
                 |
                 v
       le serveur connaît l'événement 42 ?
                 |
                 +-- oui --> envoie 43, 44..., puis le flux en direct
                 |
                 +-- non --> envoie "resync", puis le flux en direct
                             (le client recharge sa liste via l'API)
```

Le serveur n'attend pas le client : il envoie `resync`, puis enchaîne. C'est le client qui recharge en parallèle. Un événement `resync` porte quand même un petit JSON, car le navigateur ignore un événement sans `data`.

### Plusieurs instances

Le client est connecté à l'instance B, mais c'est l'instance A qui traite le changement. On diffuse donc l'événement à toutes les instances :

```
       instance A (a traité le changement)
                 |
                 |  publie l'événement
                 v
       +----------------------------------+
       |  RabbitMQ : exchange fanout      |
       |  (une copie pour chaque file)    |
       +----------------------------------+
            |              |              |
            v              v              v
       file de A      file de B      file de C
            |              |              |
            v              v              v
       instance A     instance B     instance C
                           |
                           v
                 navigateur connecté à B
```

Chaque instance déclare sa propre file, qui disparaît avec elle :

```java
@Bean
FanoutExchange realtimeExchange() {
    return new FanoutExchange("app.realtime");
}

@Bean
Queue instanceQueue() {
    return new AnonymousQueue();        // un nom aléatoire, supprimée quand l'instance s'arrête
}

@Bean
Binding instanceBinding(Queue instanceQueue, FanoutExchange realtimeExchange) {
    return BindingBuilder.bind(instanceQueue).to(realtimeExchange);
}

@RabbitListener(queues = "#{instanceQueue.name}")
void onNotification(UserNotification notification) {
    notificationStreams.deliver(notification);     // n'écrit qu'aux flux de cette instance
}
```

- Pour publier, on passe par l'outbox (guide 20) : l'événement ne part qu'après le commit.
- Pour qu'un replay marche sur n'importe quelle instance, l'id doit être le même partout, et chaque instance doit garder tous les événements reçus, pas seulement ceux de ses clients.

Les autres façons de diffuser :

| Solution | Quand la choisir |
|---|---|
| RabbitMQ, exchange fanout | un broker est déjà là |
| Redis pub/sub | Redis est déjà là |
| Postgres `LISTEN` / `NOTIFY` | aucun service en plus, petit volume |

Des sessions « collantes » (sticky) ne règlent rien : l'événement naît sur une autre instance que celle du client.

### Surveiller

Deux réglages évitent des métriques trompeuses. Une jauge compte les flux ouverts, et on retire le flux de la mesure des temps de réponse :

```java
Gauge.builder("notification.streams.open", this, NotificationStreams::openStreams)
        .register(meterRegistry);

@Bean
ObservationPredicate noObservationOfNotificationStreams() {   // une requête de 15 min fausse les moyennes
    return (name, context) -> !(context instanceof ServerRequestObservationContext request
            && request.getCarrier().getRequestURI().endsWith("/notifications/stream"));
}
```

## Pièges à éviter

### Dans tous les cas

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Créer un `SseEmitter` sans durée | le flux s'arrête au délai asynchrone par défaut (30 s sous Tomcat) | Passer une durée explicite |
| Utiliser l'`EventSource` natif | il ne peut pas envoyer `Authorization` | Utiliser un client basé sur `fetch` |
| Mettre le jeton dans l'URL | il finit dans les logs des reverse proxies | Le passer dans l'en-tête |
| Oublier d'autoriser `ASYNC` | la fin de chaque flux répond par une erreur | Ajouter `dispatcherTypeMatchers(ASYNC).permitAll()` |
| Publier hors transaction (`@TransactionalEventListener`) | l'événement n'arrive jamais | Publier dans une transaction |
| Laisser nginx mettre en tampon | les événements arrivent par paquets | Répondre avec `X-Accel-Buffering: no` |
| Laisser la ligne silencieuse | le reverse proxy coupe le flux au bout de 60 s | Envoyer un heartbeat toutes les 20 s |
| Écrire à tous depuis un seul thread | un client lent bloque tous les autres | Donner un buffer et un thread à chaque flux |
| Appeler `complete()` sur ce même thread | il attend la fin de l'écriture d'un client lent | Fermer sur un thread à part |
| Interrompre le thread qui écrit | Tomcat ferme toute la connexion | Le réveiller avec un faux événement |
| Réutiliser un `SseEventBuilder` | son `build()` ajoute à chaque appel : contenu doublé | Garder des événements immuables, convertis à l'envoi |
| Lire le compte avant d'enregistrer le flux | une désactivation passe entre les deux, et le flux reste ouvert | Enregistrer d'abord, lire ensuite |
| Arrêter l'application sans fermer les flux | Tomcat attend la fin de chaque flux ouvert | Tout fermer sur `ContextClosedEvent` |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Publier l'événement en local | seuls les clients de l'instance qui a traité le changement le reçoivent | Diffuser à toutes les instances |
| Publier vers le broker avant le commit | une notification part pour un changement annulé | Passer par l'outbox (guide 20) |
| Ignorer une perte du broker | les événements de la coupure manquent sans que le client le sache | Envoyer `resync` à tous les flux au retour |
| Traiter chaque événement reçu | la livraison au moins une fois crée des doublons | Ignorer côté client les id déjà reçus |

## En résumé

**À retenir :** un `SseEmitter` par client, écrit seulement après le commit, avec un heartbeat, une durée bornée et un id par événement ; dès qu'il y a plusieurs instances, on diffuse à toutes, et le client ignore les doublons.

- [ ] Endpoint `text/event-stream` qui renvoie un `SseEmitter` avec une durée explicite
- [ ] Flux rangés par utilisateur, oubliés sur `onCompletion` et `onError`
- [ ] Envoi après le commit (`@TransactionalEventListener` ou outbox)
- [ ] Heartbeat toutes les 20 s, `X-Accel-Buffering: no` derrière nginx
- [ ] `DispatcherType.ASYNC` autorisé dans la sécurité
- [ ] Un id par événement, un replay court et un `resync`
- [ ] Client basé sur `fetch`, qui ignore les id déjà reçus
- [ ] Un buffer borné et un thread par flux dès que le trafic grandit
- [ ] Nombre de flux limité par compte, durée bornée par le jeton
- [ ] Diffusion à toutes les instances, flux exclus des métriques de latence

## Dans ce dépôt

Le trajet d'une notification, de la modification à l'écran :

```
  TaskService                          transaction
       |  publie TaskAssigned
       v
  TaskNotificationPublisher            une notification par destinataire
       |
       v
  RealtimeBroadcasts                   Outbox.broadcast(UserNotification),
       |                               dans la même transaction
       v
  commit
       |
       v
  OutboxRelay                          exchange fanout "tasks.realtime"
       |
       v
  une file par instance
       |
       v
  RealtimeListener                     un seul thread par instance
       |
       v
  NotificationStreams.deliver()
       |
       +--> buffer de replay           tous les événements reçus
       |
       +--> buffer de chaque flux      du destinataire, puis le client
```

| Fichier | Rôle |
|---|---|
| [`NotificationStreamController`][controller] | l'endpoint, avec l'en-tête `X-Accel-Buffering: no` |
| [`NotificationStreams`][streams] | ouverture contrôlée, distribution, fermeture des flux d'un compte désactivé |
| [`NotificationStream`][stream] | un flux : son buffer et son thread virtuel |
| [`NotificationReplayBuffer`][replay] | la mémoire de replay de l'instance |
| [`StreamEvent`][event] | l'événement immuable, converti à chaque envoi |
| [`TaskNotificationPublisher`][publisher] | décide qui reçoit une notification |
| [`RealtimeBroadcasts`][broadcasts] | écrit l'événement dans l'outbox, dans la transaction |
| [`RealtimeListener`][listener] | reçoit les événements de la file de l'instance |
| [`RealtimeConfiguration`][config] | l'exchange fanout, la file de l'instance, l'exclusion de la métrique |

Les réglages, sous `realtime.streams` dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `heartbeat` | 20s | commentaire après 20 s de silence |
| `max-duration` | 15m | durée maximale d'un flux |
| `max-per-user` | 5 | flux par compte et par instance, puis 429 |
| `reconnect-delay` | 3s | base du délai de reconnexion, plus 0 à 3 s au hasard |
| `replay-window` | 5m | durée de la mémoire de replay |
| `replay-size` | 10000 | taille de la mémoire de replay |
| `buffer-size` | 100 | buffer d'envoi de chaque flux |

Ce qui est propre au dépôt :

- L'id d'un événement est l'id de sa ligne d'outbox, le même sur toutes les instances. Chaque instance gardant tous les événements reçus, une reconnexion sur une autre instance donne en général un replay.
- `resync` part aussi quand plus de 99 événements ont été manqués (le replay remplirait le buffer d'envoi), et vers tous les flux d'une instance qui a perdu RabbitMQ.
- Un compte désactivé publie `AccountStatusChanged` par le même canal : chaque instance ferme les flux de ce compte.

Les tests : [`NotificationStreamApiTests`][api-tests] (le trajet complet, le replay, le `resync`, la désactivation), [`NotificationStreamTest`][stream-test] (le buffer et le thread), [`NotificationReplayBufferTest`][replay-test].

## Voir aussi

- [16. Événements applicatifs et `@Async`](16-evenements-async.md) : `@TransactionalEventListener` et ses phases.
- [19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) : exchanges, files et listeners.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : publier seulement après le commit.
- [25. WebSocket et STOMP](25-websocket-stomp.md) : quand le client doit aussi parler.
- [Cas pratique : une notification de bout en bout](cas-pratiques/notification-bout-en-bout.md).
- Documentation officielle : [SSE dans Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html), [SSE sur MDN](https://developer.mozilla.org/fr/docs/Web/API/Server-sent_events/Using_server-sent_events).

[Sommaire](README.md) | [< 23. Envoi de mails](23-envoi-mails.md) | [25. WebSocket et STOMP >](25-websocket-stomp.md)

[controller]: ../../src/main/java/io/julienmetral/tasks/realtime/controllers/NotificationStreamController.java
[streams]: ../../src/main/java/io/julienmetral/tasks/realtime/sse/NotificationStreams.java
[stream]: ../../src/main/java/io/julienmetral/tasks/realtime/sse/NotificationStream.java
[replay]: ../../src/main/java/io/julienmetral/tasks/realtime/sse/NotificationReplayBuffer.java
[event]: ../../src/main/java/io/julienmetral/tasks/realtime/sse/StreamEvent.java
[publisher]: ../../src/main/java/io/julienmetral/tasks/notification/services/TaskNotificationPublisher.java
[broadcasts]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcasts.java
[listener]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeListener.java
[config]: ../../src/main/java/io/julienmetral/tasks/realtime/RealtimeConfiguration.java
[api-tests]: ../../src/test/java/io/julienmetral/tasks/realtime/controllers/NotificationStreamApiTests.java
[stream-test]: ../../src/test/java/io/julienmetral/tasks/realtime/sse/NotificationStreamTest.java
[replay-test]: ../../src/test/java/io/julienmetral/tasks/realtime/sse/NotificationReplayBufferTest.java
