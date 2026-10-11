# 19. RabbitMQ avec Spring AMQP

[Sommaire](README.md) | [< 18. Migrations Liquibase](18-migrations-liquibase.md) | [20. Outbox transactionnelle >](20-outbox-transactionnelle.md)

## Contexte

- **Problème** : faire un travail lent ou fragile (un mail, une image, un appel HTTP) hors de la requête, sans le perdre s'il échoue.
- **Quand l'utiliser** : un travail différé avec réessais, partagé entre instances, ou un événement à diffuser à toutes les instances.
- **Quand s'en passer** : un travail court dont la perte est acceptable (`@Async`, guide 16), un traitement à heure fixe (guide 21) ou un gros volume (guide 22).
- **Dans ce dépôt** : quatre files de travail (`mail.send`, `avatar.process`, `webhook.deliver`, `export.run`), chacune avec sa dead letter, et un exchange fanout pour le temps réel.
- **Prérequis** : guides 14 (transactions) et 16 (événements applicatifs). Versions : Spring Boot 4.1, Spring AMQP 4.1, RabbitMQ 4.3.

Les façons de sortir un travail de la requête :

| Solution | Principe | Quand la choisir |
|---|---|---|
| `@Async` | un autre thread de l'instance | travail court, perte acceptable |
| Table en base et poller | les lignes servent de file (`SKIP LOCKED`) | petit volume, aucun service en plus |
| RabbitMQ | un broker garde et distribue les messages | réessais, dead letters, plusieurs instances |
| Kafka | un journal d'événements relisible | gros débit, relecture de l'historique |

## Concepts clés

### Vocabulaire

- **Message** : un contenu (ici du JSON) avec des en-têtes, publié par un producteur.
- **File** (queue) : où les messages attendent un consommateur, jusqu'à leur acquittement.
- **Exchange** : la porte d'entrée du broker. Il ne garde rien et copie chaque message vers des files.
- **Binding** : la règle qui relie une file à un exchange, souvent avec une clé de routage.
- **Acquittement** (ack) : le signal du consommateur, « message traité, on peut l'effacer ».
- **Dead letter** : un message écarté après ses échecs, rangé à part pour examen.

### Le trajet d'un message

Un producteur publie vers un exchange, avec une clé de routage, jamais dans une file.

```
 producteur
     |  publie (exchange, clé de routage, message)
     v
 +-----------+   binding    +-----------+
 | exchange  | -----------> |   file    |  le message attend sur disque
 +-----------+              +-----------+
                                  |
                                  v
                            consommateur -- ack --> message effacé
```

Le type de l'exchange décide quelles files reçoivent une copie :

| Type | Routage | Exemple dans ce dépôt |
|---|---|---|
| Défaut (`""`) | la file dont le nom est la clé de routage | `mail.send` |
| `direct` | les files liées avec exactement cette clé | aucun |
| `fanout` | toutes les files liées, clé ignorée | `tasks.realtime` |
| `topic` | les files dont le motif correspond à la clé (`task.*`) | aucun |

L'exchange par défaut existe toujours et lie chaque file sous son nom. Une file de travail n'a donc besoin d'aucun exchange déclaré.

### Acquittement, réessais et dead letter

Si le traitement échoue :

```
 file mail.send
      |
      v
 consommateur --> succès -------------------------> ack, message effacé
      |
      +-- échec --> nouvel essai après 2 s, 6 s, 18 s, 54 s
                         |
                         +-- toujours en échec --> rejet sans remise en file
                                                          |
                                                          v
                                          dead letter exchange de la file
                                                          |
                                                          v
                                            file mail.send.dead-letter
```

- Un rejet avec remise en file (requeue) redonne aussitôt le message. Si l'échec est permanent, la boucle est infinie.
- Un rejet sans remise en file envoie le message au dead letter exchange (DLX) de la file, ou le supprime sans DLX. Le broker ajoute un en-tête `x-death` (file d'origine, raison, nombre de passages).
- Un message empoisonné (poison message) échouera toujours, comme une adresse invalide. On le rejette sans réessai.

### Livraison au moins une fois

L'acquittement part après le traitement. Un arrêt entre les deux livre le message deux fois :

```
 consommateur A : envoie le mail --> s'arrête avant l'ack
 broker         : connexion perdue, message non acquitté --> redonné à B
 consommateur B : envoie le mail --> ack              (mail reçu deux fois)
```

RabbitMQ garantit « au moins une fois », jamais « exactement une fois ». Un consommateur doit donc être idempotent : vérifier un état en base avant d'agir, ou accepter un doublon sans gravité.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                la dépendance (étape 1)
src/main/resources/
  application.yaml                     les réglages (étapes 2, 6 et 11)
src/main/java/.../
  config/
    MessagingConfiguration.java        JSON, messages empoisonnés (étapes 3, 8)
  invoice/
    InvoiceQueues.java                 files et fabrique (étapes 4 et 10)
    InvoiceRequested.java              le message (étape 5)
    InvoicePublisher.java              l'envoi (étapes 5 et 12)
    InvoiceListener.java               la réception (étapes 7 et 8)
src/test/java/.../
  InvoiceQueueTests.java               la vérification (étapes 9 et 13)
```

### Une file de travail

**Étape 1. Ajouter la dépendance.** Le starter apporte Spring AMQP et le client RabbitMQ.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

**Étape 2. Configurer la connexion.** Sans réglage, Boot vise `localhost:5672` avec `guest`/`guest`. En développement, `spring-boot-docker-compose` fournit la connexion de `compose.yaml`.

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
```

**Étape 3. Échanger du JSON.** Boot applique ce bean au `RabbitTemplate` et aux listeners. Un message ne peut nommer qu'une classe des paquets listés.

```java
@Configuration
class MessagingConfiguration {

    @Bean
    MessageConverter messageConverter(JsonMapper jsonMapper) {   // Jackson 3 : tools.jackson
        return new JacksonJsonMessageConverter(jsonMapper, "com.example.invoice");
    }
}
```

**Étape 4. Déclarer la file et sa dead letter.** Des beans `Queue` suffisent : le `RabbitAdmin` de Boot les déclare à la connexion. La dead letter passe par l'exchange par défaut.

```java
@Configuration
public class InvoiceQueues {

    public static final String GENERATE = "invoice.generate";
    public static final String DEAD_LETTER = "invoice.generate.dead-letter";

    @Bean
    Queue invoiceGenerateQueue() {
        return QueueBuilder.durable(GENERATE)            // gardée après un redémarrage du broker
                .deadLetterExchange("")                  // un rejet part vers l'exchange par défaut...
                .deadLetterRoutingKey(DEAD_LETTER)       // ... qui le range dans la dead letter
                .build();
    }

    @Bean
    Queue invoiceDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER).build();
    }
}
```

**Étape 5. Publier un message.** Avec l'exchange par défaut, la clé de routage est le nom de la file. Le message est persistant : il survit à un redémarrage du broker.

```java
public record InvoiceRequested(UUID orderId) {
}
```

```java
@Component
@RequiredArgsConstructor
class InvoicePublisher {

    private final RabbitTemplate rabbitTemplate;

    void requestInvoice(UUID orderId) {
        // part même si la transaction en cours est annulée (guide 20)
        rabbitTemplate.convertAndSend("", InvoiceQueues.GENERATE, new InvoiceRequested(orderId));
    }
}
```

**Étape 6. Activer les réessais.** Sans eux, une exception remet le message en file sans fin. Avec eux, Spring réessaie puis rejette le message vers la dead letter.

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        concurrency: 2            # consommateurs par listener
        retry:
          enabled: true
          max-retries: 4          # 5 essais en tout
          initial-interval: 2s    # puis 6 s, 18 s, 54 s : environ 1,5 min
          multiplier: 3
          max-interval: 1m
```

**Étape 7. Consommer.** Une méthode qui se termine normalement acquitte le message, une exception déclenche les réessais.

```java
@Component
@RequiredArgsConstructor
class InvoiceListener {

    private final InvoiceService invoiceService;     // le travail, transactionnel

    @RabbitListener(queues = InvoiceQueues.GENERATE)
    void generate(InvoiceRequested request) {
        invoiceService.generate(request.orderId());
    }
}
```

**Étape 8. Rejeter un message empoisonné.** Le listener lève `AmqpRejectAndDontRequeueException`. Les réessais de Boot reprennent toute exception : un prédicat exclut celle-ci.

```java
// dans InvoiceListener
@RabbitListener(queues = InvoiceQueues.GENERATE)
void generate(InvoiceRequested request) {
    try {
        invoiceService.generate(request.orderId());
    } catch (UnknownOrderException neverValid) {          // exception métier de l'exemple
        throw new AmqpRejectAndDontRequeueException("Unknown order " + request.orderId(), neverValid);
    }
}
```

```java
// dans MessagingConfiguration
@Bean
RabbitListenerRetrySettingsCustomizer noRetryForRejectedMessages() {
    return settings -> settings.setExceptionPredicate(exception -> !isRejected(exception));
}

// Spring enveloppe l'exception du listener : on remonte les causes
private static boolean isRejected(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof AmqpRejectAndDontRequeueException) {
            return true;
        }
    }
    return false;
}
```

**Étape 9. Vérifier.** Avec un vrai RabbitMQ en conteneur (guide 30), des réessais raccourcis et un service qui échoue, le message doit finir dans la dead letter.

```java
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.retry.max-retries=2",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms"
})
@Testcontainers
class InvoiceQueueTests {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.3.6-management");

    @MockitoBean InvoiceService invoiceService;
    @Autowired InvoicePublisher publisher;
    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void failingMessageIsRetriedThenDeadLettered() {
        doThrow(new IllegalStateException("PDF engine down")).when(invoiceService).generate(any());

        publisher.requestInvoice(UUID.randomUUID());

        assertThat(rabbitTemplate.receive(InvoiceQueues.DEAD_LETTER, 10_000)).isNotNull();
        verify(invoiceService, times(3)).generate(any());      // 1 essai et 2 réessais
    }
}
```


En manuel, la console de RabbitMQ (image `-management`, port 15672) montre les files et les dead letters.

### Une fabrique de listeners à part

**Étape 10. Créer une fabrique sans réessais.** Elle sert aux travaux longs, ou dont les réessais vivent en base. Elle reprend les réglages de Boot sans réessais, avec un seul message réservé.

```java
// dans InvoiceQueues
public static final String LISTENER_FACTORY = "invoiceListenerContainerFactory";

@Bean(LISTENER_FACTORY)
SimpleRabbitListenerContainerFactory invoiceListenerContainerFactory(
        SimpleRabbitListenerContainerFactoryConfigurer configurer,   // fourni par Boot
        ConnectionFactory connectionFactory
) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);   // convertisseur JSON, réessais...
    factory.setAdviceChain();                            // ... puis on retire les réessais
    factory.setDefaultRequeueRejected(false);            // un échec part en dead letter, pas en boucle
    factory.setPrefetchCount(1);                         // un seul message réservé d'avance
    factory.setConcurrentConsumers(1);
    factory.setMaxConcurrentConsumers(1);
    return factory;
}
```

```java
@RabbitListener(queues = InvoiceQueues.GENERATE, containerFactory = InvoiceQueues.LISTENER_FACTORY)
```

### Ne rien perdre à l'envoi : les confirmations

**Étape 11. Activer les confirmations et les retours.** Le broker accuse réception de chaque message et renvoie celui qu'aucune file ne reçoit.

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated   # un accusé par message, relié à son envoi
    publisher-returns: true              # le broker renvoie un message sans file...
    template:
      mandatory: true                    # ... quand l'envoi le demande
```

**Étape 12. Attendre l'accusé.** Un `ack` ne suffit pas : RabbitMQ confirme aussi un message sans file.

```java
// dans InvoicePublisher
void publishConfirmed(String queue, Object message) {
    CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
    rabbitTemplate.convertAndSend("", queue, message, correlation);

    CorrelationData.Confirm confirm;
    try {
        confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AmqpException("Interrupted while waiting for the broker", interrupted);
    } catch (ExecutionException | TimeoutException noConfirm) {
        throw new AmqpException("No confirm from the broker", noConfirm);
    }
    if (!confirm.ack()) {
        throw new AmqpException("Refused by the broker: " + confirm.reason());
    }
    if (correlation.getReturned() != null) {             // confirmé, mais aucune file ne l'a reçu
        throw new AmqpException("No queue " + queue);
    }
}
```

**Étape 13. Vérifier.** Un message pour une file qui n'existe pas doit être signalé.

```java
@Test
void messageForAMissingQueueIsReported() {
    InvoiceRequested message = new InvoiceRequested(UUID.randomUUID());

    assertThatThrownBy(() -> publisher.publishConfirmed("no-such-queue", message))
            .isInstanceOf(AmqpException.class)
            .hasMessageContaining("No queue");
}
```

### Référence rapide

Méthodes de `QueueBuilder` :

| Méthode | Argument RabbitMQ | Rôle |
|---|---|---|
| `durable(name)` | aucun | file gardée après un redémarrage du broker |
| `deadLetterExchange(x)` | `x-dead-letter-exchange` | où partent les messages rejetés ou expirés |
| `deadLetterRoutingKey(k)` | `x-dead-letter-routing-key` | leur clé de routage |
| `maxLength(n)` | `x-max-length` | au-delà, les messages les plus anciens sont supprimés |
| `ttl(ms)` | `x-message-ttl` | durée de vie d'un message en file |

Propriétés sous `spring.rabbitmq` :

| Propriété | Défaut | Rôle |
|---|---|---|
| `listener.simple.concurrency` | 1 | consommateurs par listener |
| `listener.simple.prefetch` | 250 | messages réservés d'avance par consommateur |
| `listener.simple.default-requeue-rejected` | true | remettre en file un message en échec |
| `listener.simple.retry.enabled` | false | réessais dans le consommateur |
| `listener.simple.retry.max-retries` | 3 | réessais après le premier essai |
| `publisher-confirm-type` | aucun | `correlated` : un accusé par message |
| `publisher-returns`, `template.mandatory` | false | recevoir les messages sans file |

Attributs de `@RabbitListener` : `queues` (`"#{bean.name}"` pour un nom calculé), `containerFactory` (sinon celle de Boot), `id`, `concurrency` (pour ce listener seul).

Limites de cette version : un message publié dans une transaction part même si elle est annulée, et un message publié quand le broker est injoignable est perdu (guide 20).

## Concepts avancés

### Sous le capot : le réessai dort sur le thread du consommateur

Les réessais de Boot tournent dans le consommateur, qui attend entre deux essais avec le message non acquitté :

```
 consommateur 1 : essai -- échec --> dort 2 s --> essai -- échec --> dort 6 s
 consommateur 2 : essai -- échec --> dort 2 s --> ...
 file           : les messages suivants attendent, plus personne ne consomme
```

- Avec deux consommateurs, deux messages en échec bloquent la file 1,5 min. Un échec lent (15 s) sur cinq essais occupe un consommateur plusieurs minutes.
- Le compteur d'essais vit en mémoire. Si l'instance s'arrête, le message repart de zéro.
- Pour des délais longs, on planifie les réessais en base (webhooks, guide 27) ou dans une file d'attente dont la `x-message-ttl` expire vers la file de travail.

### Sous le capot : le type d'un message

Le convertisseur JSON écrit la classe dans l'en-tête `__TypeId__`. À la réception, il choisit le type ainsi :

```
 message reçu
      |
      v
 paramètre de méthode d'un type concret ? -- oui --> ce type, en-tête ignoré
      | non (@RabbitHandler sur la classe, receiveAndConvert)
      v
 classe de l'en-tête __TypeId__ dans un paquet de confiance ?
      |                                    |
     oui --> cette classe                 non --> refusé
```

- Sans liste, seuls `java.lang` et `java.util` sont de confiance. Sinon un émetteur ferait instancier n'importe quelle classe.
- La comparaison est exacte : `com.example` ne couvre pas `com.example.invoice`.

### Sous le capot : confirmation et retour

Avec `mandatory`, le broker répond ainsi :

```
 RabbitTemplate -- message --> broker
                                  |
                                  +-- aucune file --> retour, puis ack
                                  |
                                  +-- file trouvée --> écrit en file, puis ack
                                  |
                                  +-- erreur interne --> nack
```

- Un `ack` veut dire « le broker en prend la responsabilité », pas « une file l'a reçu ».
- Le retour arrive avant l'`ack` et Spring AMQP le range dans la `CorrelationData` avant de compléter son `Future`. Après l'attente, `getReturned()` est fiable.

### Plusieurs instances

Une file nommée, écoutée par toutes les instances, partage le travail : chaque message va à un seul consommateur.

```
                         file mail.send
                               |
          +--------------------+--------------------+
          |                    |                    |
          v                    v                    v
     instance A           instance B           instance C
     message 1            message 2            message 3
```

Pour diffuser, chaque instance déclare sa propre file, liée à un exchange fanout :

```java
@Bean
FanoutExchange realtimeExchange() {
    return new FanoutExchange("app.realtime", true, false);   // durable, jamais supprimé seul
}

@Bean
AnonymousQueue realtimeQueue() {
    return new AnonymousQueue(
            new Base64UrlNamingStrategy("app.realtime."),     // nom aléatoire, propre à l'instance
            Map.of("x-max-length", 10_000));                  // une instance lente perd les plus anciens
}

@Bean
Binding realtimeBinding(FanoutExchange realtimeExchange, AnonymousQueue realtimeQueue) {
    return BindingBuilder.bind(realtimeQueue).to(realtimeExchange);
}
```

- Une `AnonymousQueue` est exclusive et supprimée avec sa connexion. Les messages d'une coupure sont perdus pour l'instance (rattrapage côté client : guide 24). Le `RabbitAdmin` la redéclare, sous le même nom, à la reconnexion.

### Surveiller

Une jauge par dead letter, lue auprès du broker à chaque collecte :

```java
public class DeadLetterQueueMetrics implements MeterBinder {

    private final AmqpAdmin amqpAdmin;
    private final List<String> queues;

    // constructeur avec les deux champs

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String queue : queues) {
            Gauge.builder("rabbitmq.dead.letter.messages", () -> messageCount(queue))
                    .tag("queue", queue)
                    .register(registry);
        }
    }

    private double messageCount(String queue) {
        try {
            QueueInformation information = amqpAdmin.getQueueInfo(queue);
            return information == null ? Double.NaN : information.getMessageCount();
        } catch (AmqpException brokerUnreachable) {
            return Double.NaN;                            // inconnu, plutôt qu'un faux zéro
        }
    }
}
```

Au-dessus de 0, `rabbitmq.dead.letter.messages` demande un examen. Boot publie aussi `rabbitmq.connections`, `rabbitmq.rejected` et `rabbitmq.unrouted_published`.

## Pièges à éviter

### Avec une file et un listener

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder le convertisseur par défaut | un record non `Serializable` est refusé à l'envoi | Déclarer un `JacksonJsonMessageConverter` |
| Mettre `"*"` ou un parent comme paquet de confiance | l'émetteur choisit la classe, ou des sous-paquets sont refusés | Lister chaque paquet |
| Réessayer un message empoisonné | il occupe un consommateur 1,5 min pour rien | Lever `AmqpRejectAndDontRequeueException`, exclue des réessais |
| Attraper toutes les exceptions dans le listener | le message est acquitté, donc perdu | Laisser remonter l'exception |
| Ajouter une dead letter à une file existante | le broker refuse la déclaration (`PRECONDITION_FAILED`) | Supprimer la file vide, ou poser une policy |
| Garder le préchargement de 250 pour des tâches longues | un consommateur réserve ce que les autres pourraient traiter | Mettre `prefetch` à 1 |
| Garder un message non acquitté pendant un long travail | au-delà de 30 min par défaut, le broker ferme le canal | Acquitter vite, suivre l'état en base |
| Supposer qu'un message n'arrive qu'une fois | un arrêt avant l'ack le redonne | Rendre le consommateur idempotent |

### Avec les confirmations

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Prendre un `ack` pour une livraison en file | un message sans file est confirmé, puis perdu | Activer `mandatory` et lire `getReturned()` |
| Publier depuis la transaction métier | le message part pour un changement annulé | Passer par l'outbox (guide 20) |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Diffuser par une file nommée | les instances se partagent les messages au lieu de tous les recevoir | Une `AnonymousQueue` par instance |
| Laisser une file d'instance sans limite | une instance lente accumule des messages sans fin | Poser `x-max-length` |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Partager un broker entre contextes de test | un contexte consomme les messages d'un autre | Un RabbitMQ par contexte, jamais réutilisé |
| Garder les délais de réessai réels | un test de dead letter dure 1,5 min | Raccourcir les délais par propriétés |

## En résumé

**À retenir :** une file durable avec sa dead letter, des paquets JSON listés, des réessais courts qui épargnent les messages empoisonnés, des confirmations avec `mandatory`, et des consommateurs idempotents.

- [ ] `JacksonJsonMessageConverter` avec la liste exacte des paquets des messages
- [ ] Chaque file durable, avec `x-dead-letter-exchange` et sa file de dead letter
- [ ] Réessais bornés (ou fabrique sans réessais), `AmqpRejectAndDontRequeueException` pour un message empoisonné
- [ ] `prefetch` à 1 pour les travaux longs
- [ ] Confirmations `correlated`, `mandatory`, vérification du retour, consommateurs idempotents
- [ ] Diffusion par `AnonymousQueue` et exchange fanout, avec `x-max-length`
- [ ] Une jauge et une alerte sur chaque dead letter
- [ ] Un vrai RabbitMQ en test, des délais de réessai raccourcis

## Dans ce dépôt

Les files et leurs consommateurs :

| File | Message | Listener | Fabrique |
|---|---|---|---|
| `mail.send` | `MailMessage` | `MailQueueListener` | celle de Boot (2 consommateurs, réessais) |
| `avatar.process` | `AvatarUploaded` | `AvatarProcessingListener` | celle de Boot (2 consommateurs, réessais) |
| `webhook.deliver` | `WebhookDeliveryRequested` | `WebhookDeliveryListener` | 4 consommateurs, prefetch 1, sans réessais |
| `export.run` | `DataExportRequested` | `DataExportListener` | 1 consommateur, prefetch 1, sans réessais |
| file de l'instance | `UserNotification`, `TaskRoomEvent`... | `RealtimeListener` | 1 consommateur, sans réessais |

| Fichier | Rôle |
|---|---|
| [`MailQueues`][mail-queues], [`AvatarQueues`][avatar-queues] | le modèle simple : file, dead letter, fabrique de Boot |
| [`WebhookQueues`][webhook-queues] | fabrique sans réessais, planifiés en base |
| [`ExportQueues`][export-queues] | un export à la fois, acquitté une fois le job terminé |
| [`RealtimeConfiguration`][realtime-config] | exchange fanout, file anonyme, fabrique à un consommateur |
| [`MessagingConfiguration`][messaging-config] | convertisseur et paquets, prédicat de réessai, jauges |
| [`MailQueueListener`][mail-listener] | rejette un mail impossible à construire |
| [`RealtimeListener`][realtime-listener] | listener sur la classe, une méthode `@RabbitHandler` par type |
| [`DeadLetterQueueMetrics`][dlq-metrics] | une jauge par dead letter |
| [`OutboxRelay`][relay] | le seul code qui publie, avec confirmation et retour |

Réglages de `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `publisher-confirm-type`, `publisher-returns`, `template.mandatory` | `correlated`, `true` | accusé par message, retour d'un message sans file |
| `listener.simple.retry` | 4 réessais, 2 s, x3, 1 min au plus | 2 consommateurs par listener, environ 1,5 min avant la dead letter |
| `realtime.queue-max-length` | 10000 | taille maximale de la file d'une instance |
| `webhooks.concurrent-deliveries` | 4 | consommateurs de `webhook.deliver` |

Ce qui est propre au dépôt :

- Aucun code ne publie avec `RabbitTemplate`. Tout passe par l'outbox, que seul `OutboxRelay` publie (guide 20).
- Cinq paquets de confiance : `mail`, `identity.messaging`, `notification.webhook`, `realtime.messaging`, `export.messaging`.
- Idempotence : la photo vérifie que l'upload est en attente, l'export se réserve par un `UPDATE` conditionnel, la livraison de webhook ignore une livraison terminée et l'événement temps réel porte un id. Un mail en double est accepté.
- Un message en dead letter y reste, examiné ou déplacé à la main depuis la console (http://localhost:15672 en développement).
- `RealtimeListener` relance un `resync` des flux au redémarrage de son consommateur (guide 24), car sa file anonyme a disparu.

Les tests :

- [`MessagingConfigurationTest`][messaging-config-test] : le JSON, l'en-tête de type, le refus des classes hors des paquets de confiance.
- [`MailQueueTests`][mail-queue-tests] : file durable, ses arguments (lus par l'API de la console), message en attente.
- [`MailQueueDeadLetterTests`][mail-dead-letter-tests] et [`AvatarQueueDeadLetterTests`][avatar-dead-letter-tests] : réessais, dead letter, `x-death`, rejet immédiat d'un mail impossible. Ils utilisent [`@DeadLetterIntegrationTest`][dead-letter-annotation] (2 réessais de 50 ms, SMTP simulé, stockage et antivirus espionnés, un seul contexte).
- [`DeadLetterQueueMetricsTest`][dlq-metrics-test] : la jauge, et `NaN` sans réponse du broker.
- [`TestcontainersConfiguration`][containers] : RabbitMQ n'est jamais réutilisé, car les tests de dead letter font échouer les listeners.

Pour ajouter une file : une classe `...Queues`, le message dans un paquet de confiance, la dead letter dans `DeadLetterQueueMetrics`, l'envoi par `Outbox.enqueue`, un listener et un test `@DeadLetterIntegrationTest`.

## Voir aussi

- [16. Événements applicatifs et `@Async`](16-evenements-async.md) : le travail en arrière-plan sans broker.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : publier seulement après le commit, sans rien perdre.
- [23. Envoi de mails](23-envoi-mails.md) : la file `mail.send` de bout en bout.
- [24. Flux de notifications (SSE)](24-flux-notifications-sse.md) : ce que fait la diffusion à toutes les instances.
- [27. Résilience](27-resilience.md) : les réessais des webhooks, planifiés en base.
- [30. Testcontainers](30-testcontainers.md) : un RabbitMQ par contexte de test.
- Documentation officielle : [Spring AMQP](https://docs.spring.io/spring-amqp/reference/), [dead letter exchanges](https://www.rabbitmq.com/docs/dlx), [confirmations](https://www.rabbitmq.com/docs/confirms).

[Sommaire](README.md) | [< 18. Migrations Liquibase](18-migrations-liquibase.md) | [20. Outbox transactionnelle >](20-outbox-transactionnelle.md)

[mail-queues]: ../../src/main/java/io/julienmetral/tasks/mail/MailQueues.java
[avatar-queues]: ../../src/main/java/io/julienmetral/tasks/identity/messaging/AvatarQueues.java
[webhook-queues]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookQueues.java
[export-queues]: ../../src/main/java/io/julienmetral/tasks/export/messaging/ExportQueues.java
[realtime-config]: ../../src/main/java/io/julienmetral/tasks/realtime/RealtimeConfiguration.java
[messaging-config]: ../../src/main/java/io/julienmetral/tasks/config/MessagingConfiguration.java
[mail-listener]: ../../src/main/java/io/julienmetral/tasks/mail/MailQueueListener.java
[realtime-listener]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeListener.java
[dlq-metrics]: ../../src/main/java/io/julienmetral/tasks/messaging/services/DeadLetterQueueMetrics.java
[relay]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelay.java
[messaging-config-test]: ../../src/test/java/io/julienmetral/tasks/config/MessagingConfigurationTest.java
[mail-queue-tests]: ../../src/test/java/io/julienmetral/tasks/mail/MailQueueTests.java
[mail-dead-letter-tests]: ../../src/test/java/io/julienmetral/tasks/mail/MailQueueDeadLetterTests.java
[avatar-dead-letter-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/AvatarQueueDeadLetterTests.java
[dead-letter-annotation]: ../../src/test/java/io/julienmetral/tasks/support/DeadLetterIntegrationTest.java
[dlq-metrics-test]: ../../src/test/java/io/julienmetral/tasks/messaging/services/DeadLetterQueueMetricsTest.java
[containers]: ../../src/test/java/io/julienmetral/tasks/TestcontainersConfiguration.java
