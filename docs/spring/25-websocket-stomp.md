# 25. WebSocket et STOMP

[Sommaire](README.md) | [< 24. Flux de notifications (SSE)](24-flux-notifications-sse.md) | [26. Clients HTTP et filtre SSRF >](26-clients-http-ssrf.md)

## Contexte

- **Problème** : pousser les changements d'un même objet (une tâche) à tous ceux qui le regardent, et laisser le client parler sur la même connexion.
- **Quand l'utiliser** : des abonnements par sujet (« tout ce qui arrive à la tâche 42 »), des échanges dans les deux sens.
- **Quand s'en passer** : un flux personnel que le client se contente d'écouter (SSE, guide 24) ; des données qui changent rarement (un rafraîchissement suffit).
- **Dans ce dépôt** : les salons de tâches, `/topic/tasks/{id}` sur `/ws`. Le client s'abonne, apprend que la tâche a changé, puis la relit par l'API.
- **Prérequis** : guides 07 (JWT), 08 (autorisations) et 24 (diffusion à toutes les instances). Versions : Spring Boot 4.1, Spring Framework 7, Spring Security 7, `@stomp/stompjs` 7.

Ce qu'apporte chaque option :

| Critère | SSE (guide 24) | WebSocket seul | WebSocket et STOMP |
|---|---|---|---|
| Sens | serveur vers client | deux sens | deux sens |
| Format des messages | texte, une ligne par champ | libre, à inventer | trames normalisées : commande, en-têtes, corps |
| Abonnements | un flux par requête | à écrire soi-même | `SUBSCRIBE` à une destination |

## Concepts clés

### Vocabulaire

- **WebSocket** : une connexion ouverte par une requête HTTP, puis gardée ouverte dans les deux sens.
- **Poignée de main** (handshake) : cette première requête HTTP, `GET` avec l'en-tête `Upgrade: websocket` ; une fois acceptée, HTTP s'efface.
- **STOMP** : un protocole texte simple, posé sur WebSocket, qui découpe l'échange en trames.
- **Trame** (frame) : une commande, des en-têtes et un corps, comme `SUBSCRIBE` ou `MESSAGE`.
- **Destination** : le nom d'un canal, comme `/topic/tasks/42` ; on s'y abonne, on y publie.
- **Broker STOMP** : le composant qui retient qui est abonné à quoi, et remet chaque message aux abonnés de sa destination.

### La connexion et ses trames

La poignée de main ouvre le WebSocket, puis STOMP prend le relais :

```
  Navigateur                                     Serveur
      |                                             |
      |---- GET /ws  (Upgrade: websocket) --------->|  poignée de main HTTP
      |<--- 101 Switching Protocols ----------------|
      |                                             |
      |---- CONNECT  Authorization: Bearer ... ---->|  première trame STOMP
      |<--- CONNECTED  heart-beat:10000,10000 ------|
      |---- SUBSCRIBE  destination:/topic/... ----->|
      |                                             |
      |<--- MESSAGE  destination:/topic/... --------|  à chaque événement
      |<--- heartbeat ------------------------------|  pendant les silences
      |---- heartbeat ----------------------------->|
```

Les trames utiles :

| Trame | Sens | Rôle |
|---|---|---|
| `CONNECT` | client vers serveur | ouvre la session STOMP, avec ses en-têtes |
| `CONNECTED` | serveur vers client | accepte la session, annonce les heartbeats |
| `SUBSCRIBE`, `UNSUBSCRIBE` | client vers serveur | s'abonne à une destination, ou s'en retire |
| `SEND` | client vers serveur | publie un message vers une destination |
| `MESSAGE` | serveur vers client | remet un message d'une destination suivie |
| `ERROR` | serveur vers client | refuse une trame, la raison dans l'en-tête `message` ; la connexion se ferme |

Une trame est du texte : commande, un en-tête par ligne, ligne vide, corps, octet nul (`^@`) :

```
SUBSCRIBE
id:sub-0
destination:/topic/tasks/42

^@
```

### Les destinations

Spring range les destinations par préfixe :

| Préfixe | Qui la traite | Pour quoi |
|---|---|---|
| `/topic/...` | le broker | diffusion à tous les abonnés |
| `/queue/...` | le broker | par convention, un message pour un seul destinataire |
| `/app/...` | une méthode `@MessageMapping` | un message du client que le serveur traite |
| `/user/...` | Spring, puis le broker | la destination privée d'un utilisateur connecté |

### Broker simple ou relais

Le broker peut vivre dans l'application ou dans un serveur à part :

| Critère | Broker simple (`enableSimpleBroker`) | Relais (`enableStompBrokerRelay`) |
|---|---|---|
| Abonnements | en mémoire de l'instance | dans RabbitMQ ou ActiveMQ, avec leur plugin STOMP |
| Plusieurs instances | chacune ne connaît que ses clients | le broker externe distribue à tous |
| Quand le choisir | une instance, ou une diffusion déjà en place (guide 24) | beaucoup de clients, des messages à garder |

### Où placer le jeton

L'API JavaScript `WebSocket` ne laisse ajouter aucun en-tête à la poignée de main. Il reste trois places :

| Place | Conséquence |
|---|---|
| Paramètre d'URL (`/ws?token=...`) | le jeton finit dans les logs des reverse proxies |
| Cookie de session | n'importe quelle page peut ouvrir la connexion avec le cookie du visiteur : il faut vérifier l'origine et un jeton CSRF |
| En-tête de la trame `CONNECT` | la poignée de main reste publique, et la session STOMP est authentifiée par sa première trame |

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                               les dépendances (étape 1)
src/main/java/.../
  realtime/
    StompConfiguration.java           endpoint, broker, sécurité (étapes 2-11)
    TaskRooms.java                    publie vers un salon (étape 3)
    TypingController.java             reçoit un message du client (étape 4)
    StompAuthentication.java          authentifie CONNECT (étape 9)
    TaskRoomAuthorization.java        autorise un abonnement (étape 10)
  config/
    SecurityConfiguration.java        laisse passer la poignée de main (étape 8)
src/main/resources/static/
  task-room.js                        le client (étape 6)
src/test/java/.../
  TaskRoomTests.java                  les vérifications (étapes 7 et 12)
```

### Une instance : le broker simple

**Étape 1. Ajouter les dépendances.** `spring-security-messaging` sert à partir de l'étape 10.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-messaging</artifactId>
</dependency>
```

**Étape 2. Déclarer l'endpoint et le broker.** `/ws`, broker simple sur `/topic`, `/app` réservé aux messages des clients.

```java
@Configuration
@EnableWebSocketMessageBroker
public class StompConfiguration implements WebSocketMessageBrokerConfigurer {

    private final TaskScheduler brokerScheduler;

    // @Lazy : ce planificateur est créé par la configuration même qu'on écrit ici
    public StompConfiguration(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler) {
        this.brokerScheduler = brokerScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("https://*.example.com");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10_000, 10_000})   // serveur, puis client, en ms
                .setTaskScheduler(brokerScheduler);               // sans lui, refus de démarrer
        registry.setApplicationDestinationPrefixes("/app");
        registry.setPreservePublishOrder(true);                   // les messages d'une session dans l'ordre
    }
}
```

**Étape 3. Publier vers un sujet, après le commit.** `SimpMessagingTemplate` remet le message au broker, qui l'envoie en JSON aux abonnés.

```java
public record TaskChanged(UUID taskId, String type) {
}
```

```java
@Component
@RequiredArgsConstructor
class TaskRooms {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener                // rien ne part pour un changement annulé (guide 16)
    void onTaskChanged(TaskChanged event) {
        messagingTemplate.convertAndSend("/topic/tasks/" + event.taskId(), event);
    }
}
```

**Étape 4. Recevoir un message du client.** Ce que SSE ne permet pas : le client envoie `SEND /app/tasks/42/typing`, et la méthode reçoit la destination sans `/app`.

```java
public record Typing(String displayName) {
}
```

```java
@Controller
@RequiredArgsConstructor
class TypingController {

    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/tasks/{taskId}/typing")
    void typing(@DestinationVariable UUID taskId, @Payload Typing typing) {
        messagingTemplate.convertAndSend("/topic/tasks/" + taskId + "/typing", typing);
    }
}
```

**Étape 5. Rendre son planificateur à Boot.** `@EnableWebSocketMessageBroker` déclare le bean `messageBrokerTaskScheduler`. Boot voit un planificateur déjà présent et ne crée plus le sien : les jobs `@Scheduled` (guide 21) partent alors sur les threads des heartbeats. On redéclare `taskScheduler` comme Boot le déclare ; avec deux planificateurs, les jobs prennent celui qui s'appelle `taskScheduler`.

```java
// dans StompConfiguration
@Configuration(proxyBeanMethods = false)
static class ApplicationTaskScheduler {

    @Bean(name = "taskScheduler")
    @ConditionalOnThreading(Threading.VIRTUAL)
    SimpleAsyncTaskScheduler taskSchedulerVirtualThreads(SimpleAsyncTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = "taskScheduler")
    @ConditionalOnThreading(Threading.PLATFORM)
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
```

**Étape 6. S'abonner côté navigateur.** `@stomp/stompjs` gère `CONNECT`, les heartbeats et la reconnexion ; `beforeConnect` place un jeton frais.

```js
import { Client } from '@stomp/stompjs';

// freshAccessToken, showChange, taskId et displayName viennent de l'application
const client = new Client({
  brokerURL: 'wss://api.example.com/ws',
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
  beforeConnect: async (c) => {
    c.configure({ connectHeaders: { Authorization: `Bearer ${await freshAccessToken()}` } });
  },
  onConnect: () => {
    client.subscribe(`/topic/tasks/${taskId}`, (message) => showChange(JSON.parse(message.body)));
  },
  onStompError: (frame) => console.warn('Refusé :', frame.headers.message),
});
client.activate();

function sendTyping() {
  client.publish({ destination: `/app/tasks/${taskId}/typing`, body: JSON.stringify({ displayName }) });
}
```

**Étape 7. Vérifier.** Un vrai serveur sur un port libre et un client STOMP Java.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskRoomTests {

    @LocalServerPort int port;
    @Autowired SimpMessagingTemplate messagingTemplate;

    @Test
    void messagePublishedToATaskRoomReachesItsSubscriber() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        UUID taskId = UUID.randomUUID();
        CompletableFuture<TaskChanged> received = new CompletableFuture<>();

        session.subscribe("/topic/tasks/" + taskId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return TaskChanged.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.complete((TaskChanged) payload);
            }
        });

        // STOMP ne confirme pas un SUBSCRIBE : on publie jusqu'à ce que le broker l'ait enregistré
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            messagingTemplate.convertAndSend("/topic/tasks/" + taskId, new TaskChanged(taskId, "UPDATED"));
            assertThat(received).isCompleted();
        });
    }
}
```

### Plusieurs utilisateurs : authentifier et autoriser les trames

**Étape 8. Laisser passer la poignée de main.** Le navigateur l'envoie sans jeton ; il est vérifié sur la trame `CONNECT` (filtres HTTP, guide 07).

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.GET, "/ws").permitAll()
        // ... les autres règles
);
```

**Étape 9. Authentifier la trame `CONNECT`.** Un `ChannelInterceptor` lit le jeton, le décode avec le `JwtDecoder` de l'API et attache l'utilisateur à la session STOMP, pour toutes les trames suivantes.

```java
@Component
@RequiredArgsConstructor
class StompAuthentication implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;                                    // celui de l'API (guide 07)
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        accessor.removeNativeHeader(HttpHeaders.AUTHORIZATION);            // avant qu'un log l'affiche
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new BadCredentialsException("A bearer access token is required");
        }
        try {
            Jwt jwt = jwtDecoder.decode(authorization.substring(7));
            accessor.setUser(jwtAuthenticationConverter.convert(jwt));
        } catch (JwtException invalid) {
            throw new BadCredentialsException("Invalid access token", invalid);
        }
        return message;
    }
}
```

**Étape 10. Autoriser chaque trame.** Trois intercepteurs, dans cet ordre : l'authentification, le contexte de sécurité (il place l'utilisateur de la session dans `SecurityContextHolder`), puis la décision. `@Order` fait passer cette configuration avant celles de Spring.

```java
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class StompConfiguration implements WebSocketMessageBrokerConfigurer {

    // ... étapes 2 et 5 ; stompAuthentication et taskRoomAuthorization injectés par le constructeur

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                stompAuthentication,
                new SecurityContextChannelInterceptor(),
                new AuthorizationChannelInterceptor(authorizationManager()));
    }

    private AuthorizationManager<Message<?>> authorizationManager() {
        return MessageMatcherDelegatingAuthorizationManager.builder()
                .simpTypeMatchers(SimpMessageType.CONNECT).authenticated()
                .simpTypeMatchers(SimpMessageType.HEARTBEAT, SimpMessageType.UNSUBSCRIBE,
                        SimpMessageType.DISCONNECT).permitAll()
                .simpSubscribeDestMatchers("/topic/tasks/{taskId}").access(taskRoomAuthorization)
                .simpMessageDestMatchers("/app/**").authenticated()      // SEND seulement
                .anyMessage().denyAll()                                  // tout le reste, /topic compris
                .build();
    }
}
```

La règle d'un salon lit la variable du motif :

```java
@Component
@RequiredArgsConstructor
class TaskRoomAuthorization implements AuthorizationManager<MessageAuthorizationContext<?>> {

    private final TaskRepository taskRepository;                  // un repository Spring Data (guide 11)

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         MessageAuthorizationContext<?> context) {
        if (!(authentication.get() instanceof JwtAuthenticationToken)) {
            return new AuthorizationDecision(false);              // anonyme
        }
        try {
            UUID taskId = UUID.fromString(context.getVariables().get("taskId"));
            return new AuthorizationDecision(taskRepository.existsById(taskId));
        } catch (IllegalArgumentException notAnId) {
            return new AuthorizationDecision(false);
        }
    }
}
```

**Étape 11. Donner la raison du refus.** L'exception d'un intercepteur arrive enveloppée dans une `MessageDeliveryException` au message générique (« Failed to send message ») ; on renvoie sa cause dans la trame `ERROR`.

```java
// dans registerStompEndpoints
registry.setErrorHandler(new RefusalReasons());
```

```java
// dans StompConfiguration
private static final class RefusalReasons extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        Throwable reason = ex instanceof MessageDeliveryException && ex.getCause() != null
                ? ex.getCause() : ex;
        return super.handleClientMessageProcessingError(clientMessage, reason);
    }
}
```

**Étape 12. Vérifier.** Un `CONNECT` sans jeton reçoit une trame `ERROR` avec sa raison.

```java
// dans TaskRoomTests
@Test
void connectWithoutATokenIsRefusedWithItsReason() throws Exception {
    CompletableFuture<String> refusal = new CompletableFuture<>();
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());

    client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
        @Override
        public void handleFrame(StompHeaders headers, Object payload) {   // les trames ERROR arrivent ici
            refusal.complete(headers.getFirst("message"));
        }
    });

    assertThat(refusal.get(5, TimeUnit.SECONDS)).isEqualTo("A bearer access token is required");
}
```

### Référence rapide

Les réglages de `WebSocketMessageBrokerConfigurer` :

| Méthode | Réglage | Défaut |
|---|---|---|
| `registerStompEndpoints` | `addEndpoint`, `setAllowedOriginPatterns`, `setErrorHandler` | la même origine seulement |
| `configureMessageBroker` | `enableSimpleBroker`, `setApplicationDestinationPrefixes`, `setPreservePublishOrder` | aucun broker |
| `configureWebSocketTransport` | `setMessageSizeLimit` | 64 Ko par trame reçue |
| `configureWebSocketTransport` | `setTimeToFirstMessage` | 60 s pour envoyer `CONNECT` |
| `configureWebSocketTransport` | `setSendTimeLimit`, `setSendBufferSizeLimit` | 10 s, 512 Ko vers un client lent |

Les règles de `MessageMatcherDelegatingAuthorizationManager` :

| Règle | Trames visées |
|---|---|
| `simpSubscribeDestMatchers("/topic/x/{id}")` | `SUBSCRIBE` à ce motif ; `{id}` lisible dans le contexte |
| `simpMessageDestMatchers("/app/**")` | `SEND` à ce motif |
| `simpDestMatchers(...)` | toute trame vers cette destination, quel que soit son type |

Limites de cette version : une session survit à l'expiration de son jeton et à la désactivation du compte, et chaque instance ne parle qu'à ses propres clients.

## Concepts avancés

### Sous le capot : trois canaux

Côté serveur, chaque trame traverse des canaux de messages :

```
  client
    |  trame CONNECT, SUBSCRIBE, SEND
    v
  clientInboundChannel     intercepteurs : authentification,
    |                      contexte de sécurité, autorisation
    +-- /app/... ----> méthode @MessageMapping
    |
    +-- /topic/... --> broker simple <-- brokerChannel <-- SimpMessagingTemplate
                          |
                          v
                   clientOutboundChannel
                          |
                          v
                   trame MESSAGE vers chaque abonné
```

- Les intercepteurs tournent sur le thread qui a lu la trame. Une exception remonte au gestionnaire STOMP, qui répond `ERROR` puis ferme la connexion (code 1002).
- Les traitements suivants passent par un pool de threads : sans `setPreservePublishOrder`, deux messages pour un même client peuvent sortir dans le désordre.
- `SimpMessagingTemplate` publie sur `brokerChannel` : le broker ne distingue pas le serveur d'un client, d'où le refus de tout `SEND` vers `/topic`.

### La session qui survit au jeton

STOMP n'authentifie qu'une fois, sur `CONNECT`. Ensuite, chaque trame porte l'utilisateur de la session, même quand son jeton a expiré ou que son compte vient d'être désactivé. On ferme donc la connexion soi-même :

```
  CONNECT accepté, jeton valable jusqu'à 10:15
        |
        +-- fermeture planifiée à 10:15 ---> close 1008 "Access token expired"
        |
        +-- compte désactivé (événement) --> close 1008 "Account not active"
        |
        v
  le client revient avec un jeton frais (beforeConnect)
```

Un décorateur de `WebSocketHandler` garde les connexions ouvertes, et le planificateur du broker ferme chacune à l'expiration de son jeton :

```java
@Component
public class StompSessions {

    private static final CloseStatus TOKEN_EXPIRED =
            CloseStatus.POLICY_VIOLATION.withReason("Access token expired");

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final TaskScheduler scheduler;

    public StompSessions(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    // branché dans configureWebSocketTransport : registration.addDecoratorFactory(stompSessions::track)
    WebSocketHandler track(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    // appelé par StompAuthentication sur CONNECT : l'id de session STOMP est celui de la connexion
    void connected(String sessionId, Instant tokenExpiresAt) {
        scheduler.schedule(() -> {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null) {
                try {
                    session.close(TOKEN_EXPIRED);
                } catch (IOException alreadyClosing) {
                    // le client est parti entre-temps
                }
            }
        }, tokenExpiresAt);
    }
}
```

- Le code 1008 (« violation de règle ») dit au client de ne pas revenir avec les mêmes identifiants.
- Annuler la fermeture planifiée quand la connexion se termine, sinon les tâches s'accumulent jusqu'à l'expiration des jetons.
- Pour la désactivation, même ordre que pour SSE (guide 24) : enregistrer la session, puis lire le compte.

### Plusieurs instances

Le broker simple ne connaît que les abonnés de son instance. On diffuse donc l'événement à toutes les instances, comme les notifications (guide 24), et chaque broker local le remet à ses abonnés :

```
  instance A (a traité le changement)
        |
        |  outbox --> exchange fanout
        v
  +-----------+-----------+
  |           |           |
  v           v           v
 instance A  instance B  instance C
 broker      broker      broker
 simple      simple      simple
              |
              v
       abonnés de /topic/tasks/42 connectés à B
```

Un relais STOMP (tableau plus haut) est l'alternative. Des sessions collantes seules ne changent rien : l'événement naît sur une autre instance que la session.

- Pour qu'un client ignore un doublon, l'id de l'événement doit être le même sur toutes les instances : celui de la ligne d'outbox convient.
- Un changement de compte est diffusé aussi : chaque instance ferme les sessions qu'elle porte.

### Surveiller

Spring écrit toutes les 30 minutes, au niveau INFO, une ligne de `WebSocketMessageBrokerStats` : sessions ouvertes, sessions fermées faute de `CONNECT`, trames `CONNECT` et `ERROR`, files des pools. Le bean `webSocketMessageBrokerStats` expose les mêmes chiffres.

## Pièges à éviter

### Dans tous les cas

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Exiger le jeton à la poignée de main | un navigateur ne peut pas l'envoyer : toute connexion échoue | Laisser `GET /ws` public, authentifier `CONNECT` |
| Laisser `Authorization` dans la trame | les logs debug impriment les en-têtes natifs, jeton compris | Retirer l'en-tête dès sa lecture |
| Régler des heartbeats sans planificateur | le broker refuse de démarrer | Passer `messageBrokerTaskScheduler`, avec `@Lazy` |
| Ajouter `@EnableWebSocketMessageBroker` seul | Boot ne crée plus `taskScheduler` : les jobs tournent sur les threads du broker | Redéclarer `taskScheduler` |
| Ouvrir `setAllowedOriginPatterns("*")` | n'importe quel site ouvre une connexion depuis le navigateur d'un visiteur | Lister les origines |
| Oublier `anyMessage().denyAll()` | une destination non prévue reste ouverte, `/topic` compris : de faux événements | Fermer la liste par un refus |
| Autoriser un `SEND` avec `simpDestMatchers` | la règle vaut aussi pour un `SUBSCRIBE` au même chemin | Utiliser `simpMessageDestMatchers` |
| Publier juste après un `SUBSCRIBE` en test | le message part avant l'abonnement : échec au hasard | Attendre l'abonnement dans le registre du broker |

### Avec Spring Security

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Utiliser `@EnableWebSocketSecurity` avec des jetons | il exige un jeton CSRF sur `CONNECT`, impossible à désactiver | Brancher les intercepteurs à la main |
| Activer `setPreserveReceiveOrder` | un `CONNECT` refusé n'obtient pas de `ERROR` et reste ouvert sans utilisateur | Ne pas l'activer |
| Oublier `SecurityContextChannelInterceptor` | l'autorisation ne trouve pas l'utilisateur de la session | Le placer entre authentification et autorisation |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Publier dans le broker local seulement | seuls les abonnés de l'instance qui a traité le changement le reçoivent | Diffuser à toutes les instances |
| Fermer les sessions d'un compte en local | celles des autres instances restent ouvertes | Diffuser aussi les changements de compte |
| Traiter chaque message reçu | la livraison au moins une fois crée des doublons | Ignorer côté client les id déjà reçus |

## En résumé

**À retenir :** la poignée de main reste publique, la trame `CONNECT` porte le jeton, trois intercepteurs authentifient puis autorisent chaque trame avec un refus par défaut, et le serveur ferme lui-même la session quand le jeton expire ou que le compte change.

- [ ] `spring-boot-starter-websocket`, `@EnableWebSocketMessageBroker`, endpoint et origines listées
- [ ] Heartbeats du broker avec `messageBrokerTaskScheduler`, et `taskScheduler` redéclaré
- [ ] Publication après le commit, des messages qui disent ce qui s'est passé
- [ ] `GET /ws` public, jeton lu puis retiré de la trame `CONNECT`
- [ ] Intercepteurs dans l'ordre : authentification, contexte, autorisation
- [ ] Règles par type et par destination, terminées par `anyMessage().denyAll()`
- [ ] Raison du refus dans la trame `ERROR`, sans `setPreserveReceiveOrder`
- [ ] Session fermée en 1008 à l'expiration du jeton et quand le compte n'est plus actif
- [ ] Diffusion à toutes les instances, un id par événement
- [ ] Un test contre un vrai serveur : poignée de main, refus, codes de fermeture

## Dans ce dépôt

Le trajet d'un événement de salon, du changement au client :

```
  TaskService                          transaction
       |  TaskEventService écrit l'historique et publie TaskEventRecorded
       v
  RealtimeBroadcasts                   Outbox.broadcast(TaskRoomEvent)
       |
       v
  commit --> OutboxRelay --> exchange fanout "tasks.realtime"
                                    |
                                    v
                          file de chaque instance
                                    |
                                    v
  RealtimeListener.onTaskRoomEvent     un seul thread par instance
       |
       v
  TaskRooms.publish                    /topic/tasks/{id}, en-tête event-id
       |
       v
  broker simple --> abonnés de cette instance
                        |
                        v
                 GET /api/v1/tasks/{id} pour relire la tâche
```

| Fichier | Rôle |
|---|---|
| [`StompConfiguration`][config] | endpoint `/ws`, broker simple, intercepteurs, règles des trames, `RefusalReasons`, `taskScheduler` |
| [`StompAuthentication`][auth] | authentifie `CONNECT`, retire l'en-tête, enregistre la session puis lit le compte en base |
| [`TaskRoomAuthorization`][room-auth] | `SUBSCRIBE` à un salon : statut actif (cache, guide 17) et tâche existante |
| [`StompSessions`][sessions] | connexions de l'instance, fermées en 1008 à l'expiration du jeton ou à la désactivation |
| [`TaskRooms`][rooms], [`TaskRoomEvent`][event] | publie dans le salon, avec `event-id` : tâche, ligne d'historique, type, auteur, date |
| [`RealtimeBroadcasts`][broadcasts] | diffuse l'événement par l'outbox, dans la transaction du changement |
| [`RealtimeListener`][listener] | reçoit les diffusions de l'instance, ferme les sessions d'un compte changé |
| [`SecurityConfiguration`][security] | `GET /ws` public, en-tête `Authorization` ignoré sur ce chemin |

Les réglages, sous `realtime.rooms` dans `application.yaml` (lus par [`RealtimeProperties`][properties]) :

| Réglage | Valeur | Rôle |
|---|---|---|
| `allowed-origins` | `${REALTIME_ALLOWED_ORIGINS:}`, vide | origines autorisées, en motifs ; vide : l'origine de l'API seule |
| `heartbeat` | 10s | heartbeats dans les deux sens |
| `message-size-limit` | 16KB | plus grande trame acceptée d'un client |
| `time-to-first-message` | 10s | délai pour envoyer `CONNECT` après la poignée de main |

Les refus et leur réponse, tous vérifiés contre un vrai serveur :

| Situation | Réponse |
|---|---|
| poignée de main depuis une origine non listée | 403, pas de WebSocket |
| `CONNECT` sans en-tête `Bearer` | `ERROR` « A bearer access token is required », puis fermeture 1002 |
| jeton invalide, expiré ou signé par une autre clé | `ERROR` « Invalid access token » |
| compte non vérifié, désactivé ou supprimé | `ERROR` « The account is not active » |
| `SUBSCRIBE` hors des salons, ou au salon d'une tâche inconnue | `ERROR` « Access Denied » |
| tout `SEND`, même vers un salon | `ERROR` « Access Denied » |
| jeton expiré pendant la session | fermeture 1008 « Access token expired » |
| compte désactivé pendant la session | fermeture 1008 « Account not active » |

Ce qui est propre au dépôt :

- Aucune méthode `@MessageMapping` : les clients ne font que s'abonner, et le préfixe `/app` n'est pas déclaré.
- `CONNECT` lit le compte en base ; `SUBSCRIBE` lit le statut en cache, évincé sur chaque instance par `AccountStatusChanged`.
- L'en-tête `event-id` est l'id de la ligne d'outbox. Pour une suppression, `TaskRoomEvent.eventId` est `null` : elle n'écrit pas d'historique.
- Rien n'est rejoué : après une reconnexion, le client relit la tâche.
- La fermeture à l'expiration utilise le planificateur du broker ; `forget` l'annule quand la connexion se termine ou que `CONNECT` est refusé.
- Le contrat pour les clients est dans le [README du dépôt, « Follow a task live »](../../README.md#follow-a-task-live).

Les tests : [`StompConfigurationTest`][config-test] (ordre des intercepteurs, règles, trame `ERROR`), [`StompAuthenticationTest`][auth-test] (jeton, en-tête retiré, enregistrement avant la lecture), [`StompSessionsTest`][sessions-test], [`TaskRoomAuthorizationTest`][room-auth-test], [`TaskRoomsTest`][rooms-test], [`TaskRoomsWebSocketTests`][websocket-tests] (vrai serveur : origine, refus, codes de fermeture, événements), [`ApplicationTaskSchedulerTests`][scheduler-tests] (jobs sur `taskScheduler`, heartbeats sur le pool du broker).

Pour ajouter un salon : une règle `simpSubscribeDestMatchers` avant `anyMessage().denyAll()`, un `AuthorizationManager` comme `TaskRoomAuthorization`, et une méthode de publication appelée par `RealtimeListener`.

## Voir aussi

- [07. Chaîne de filtres et JWT](07-securite-jwt.md) : le `JwtDecoder` et le convertisseur réutilisés sur `CONNECT`.
- [17. Cache Caffeine](17-cache-caffeine.md) : le statut en cache lu à chaque `SUBSCRIBE`.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : le `taskScheduler` des jobs.
- [24. Flux de notifications (SSE)](24-flux-notifications-sse.md) : la diffusion à toutes les instances, et l'ordre « enregistrer, puis lire ».
- Documentation officielle : [STOMP dans Spring](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html), [WebSocket et Spring Security](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html), [spécification STOMP 1.2](https://stomp.github.io/stomp-specification-1.2.html).

[Sommaire](README.md) | [< 24. Flux de notifications (SSE)](24-flux-notifications-sse.md) | [26. Clients HTTP et filtre SSRF >](26-clients-http-ssrf.md)

[config]: ../../src/main/java/io/julienmetral/tasks/realtime/stomp/StompConfiguration.java
[auth]: ../../src/main/java/io/julienmetral/tasks/realtime/stomp/StompAuthentication.java
[room-auth]: ../../src/main/java/io/julienmetral/tasks/realtime/stomp/TaskRoomAuthorization.java
[sessions]: ../../src/main/java/io/julienmetral/tasks/realtime/stomp/StompSessions.java
[rooms]: ../../src/main/java/io/julienmetral/tasks/realtime/stomp/TaskRooms.java
[event]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/TaskRoomEvent.java
[broadcasts]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcasts.java
[listener]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeListener.java
[security]: ../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[properties]: ../../src/main/java/io/julienmetral/tasks/realtime/RealtimeProperties.java
[config-test]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/StompConfigurationTest.java
[auth-test]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/StompAuthenticationTest.java
[sessions-test]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/StompSessionsTest.java
[room-auth-test]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/TaskRoomAuthorizationTest.java
[rooms-test]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/TaskRoomsTest.java
[websocket-tests]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/TaskRoomsWebSocketTests.java
[scheduler-tests]: ../../src/test/java/io/julienmetral/tasks/realtime/stomp/ApplicationTaskSchedulerTests.java
