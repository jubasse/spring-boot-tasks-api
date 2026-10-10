# 23. Envoi de mails

[Sommaire](README.md) | [< 22. Spring Batch](22-spring-batch.md) | [24. Flux de notifications (SSE) >](24-flux-notifications-sse.md)

## Contexte

- **Problème** : envoyer un mail (vérification, réinitialisation de mot de passe, notification) sans faire attendre la requête, sans mail pour un changement annulé, et sans perdre le mail si le serveur SMTP est en panne.
- **Quand l'utiliser** : tout mail transactionnel, déclenché par une action.
- **Quand s'en passer** : les envois de masse et les newsletters (un service d'emailing dédié), ou une alerte qui doit arriver tout de suite à coup sûr (un autre canal).
- **Dans ce dépôt** : six sortes de mails (vérification, réinitialisation, avertissement d'inactivité, notifications de tâches, export prêt ou échoué, webhook désactivé), tous en texte brut et par la file `mail.send`.
- **Prérequis** : guides 16 (événements), 19 (RabbitMQ) et 20 (outbox). Versions : Spring Boot 4.1, Jakarta Mail 2.1 (Angus Mail 2.0), Mailpit 1.31.

Trois façons d'envoyer :

| Façon | Ce qui se passe | Quand la choisir |
|---|---|---|
| Dans la requête | la réponse attend le serveur SMTP | jamais pour une API |
| Après le commit, `@Async` | un thread envoie, un arrêt ou une panne SMTP perd le mail | petite application, perte acceptable |
| Outbox puis file | le mail attend dans RabbitMQ, réessayé, puis mis de côté | dès que le mail compte (cas du dépôt) |

## Concepts clés

### Vocabulaire

- **SMTP** : le protocole d'envoi des mails. L'application parle à un serveur SMTP (Mailpit, un fournisseur), qui livre.
- **`JavaMailSender`** : l'interface de Spring qui envoie un mail. Spring Boot la crée dès que `spring.mail.host` est réglé.
- **Expéditeur** : l'adresse `From` du mail, qui doit appartenir à un domaine configuré chez le fournisseur.
- **Mailpit** : un faux serveur SMTP qui garde chaque mail reçu, avec une interface web et une API, pour le développement et les tests.
- **Dead letter** : la file où un message est mis de côté après ses réessais (guide 19).

### Le trajet d'un mail

Le mail se prépare dans la transaction de la requête, et part après le commit :

```
 transaction de la requête
 -------------------------
 service métier --> publishEvent(OrderPlaced)
                         |
                         v
                 listener qui écrit le mail      (destinataire, sujet, texte)
                         |
                         v
                 MailService.send --> INSERT outbox
 COMMIT
   |
   v
 file mail.send                                  (guide 20 : après le commit)
   |
   v
 listener de la file --> JavaMailSender --> serveur SMTP
   |
   +-- échec --> réessais, puis mail.send.dead-letter   (guide 19)
```

- Écrire le mail et l'envoyer sont deux étapes. Le contenu est écrit dans la transaction, avec les données du moment. L'envoi se fait plus tard, ailleurs.
- Un changement annulé n'écrit pas de ligne d'outbox, donc aucun mail ne part.
- La livraison est « au moins une fois » : un arrêt entre l'envoi SMTP et l'acquittement renvoie le mail. Un doublon est accepté (guide 19).

### Les échecs SMTP

Spring range les échecs d'envoi dans des sous-classes de `MailException` :

| Exception | Cause | Réessayer ? |
|---|---|---|
| `MailSendException` | serveur injoignable, refus d'un destinataire | oui |
| `MailAuthenticationException` | identifiants refusés | oui : une configuration corrigée le débloque |
| `MailParseException` | adresse ou contenu illisible | non : il échouera toujours |
| `MailPreparationException` | message impossible à préparer | non |

Le listener de la file ne rejette tout de suite que les deux dernières. Les deux premières remontent, et les réessais du conteneur les reprennent.

### Les délais SMTP

- Par défaut, certains délais de JavaMail sont infinis : un serveur muet bloque le thread qui envoie.
- On les borne avec `mail.smtp.connectiontimeout`, `mail.smtp.timeout` et `mail.smtp.writetimeout`, en millisecondes, sous `spring.mail.properties`.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                   la dépendance (étape 1)
compose.yaml                              Mailpit en développement (étape 2)
src/main/resources/
  application.yaml                        serveur, délais, expéditeur (étape 2)
src/main/java/.../
  mail/
    MailMessage.java                      le mail à envoyer (étape 3)
    AppMailProperties.java                l'expéditeur (étape 3)
    MailService.java                      l'envoi (étapes 5 et 6)
    MailQueues.java                       la file (étape 7)
    MailQueueListener.java                l'envoi SMTP (étape 8)
  order/
    OrderConfirmationEmailSender.java     écrit le mail (étape 4)
src/test/java/.../
  MailpitContainerConfiguration.java      Mailpit en test (étape 9)
  OrderConfirmationMailTests.java         la vérification (étape 9)
```

### Un mail après le commit

**Étape 1. Ajouter la dépendance.** Le starter apporte Jakarta Mail et le `JavaMailSender` configuré par Boot.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

**Étape 2. Configurer le serveur SMTP.** Boot ne crée le `JavaMailSender` que si `spring.mail.host` est réglé. Les délais évitent un thread bloqué pour toujours.

```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}                    # Mailpit en développement
    properties:
      "[mail.smtp.connectiontimeout]": 5000    # millisecondes
      "[mail.smtp.timeout]": 3000
      "[mail.smtp.writetimeout]": 5000
app:
  mail:
    from: ${MAIL_FROM:no-reply@shop.local}
```

```yaml
# compose.yaml
services:
  mailpit:
    image: axllent/mailpit:v1.31.2
    ports:
      - "1025:1025"     # SMTP
      - "8025:8025"     # interface web : http://localhost:8025
```

**Étape 3. Décrire le mail et l'expéditeur.** Un record suffit pour un mail texte. L'expéditeur est une propriété validée au démarrage (guide 01).

```java
public record MailMessage(String to, String subject, String text) {
}
```

```java
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record AppMailProperties(@NotBlank @Email String from) {
}
```

On l'enregistre par `@EnableConfigurationProperties(AppMailProperties.class)` sur une configuration, comme au guide 01.

**Étape 4. Écrire le mail dans un listener.** Le service métier ne connaît pas les mails. Le listener écrit le contenu dans la transaction (guide 16).

```java
@Component
@RequiredArgsConstructor
class OrderConfirmationEmailSender {

    private final MailService mailService;

    @EventListener
    public void onOrderPlaced(OrderPlaced event) {          // l'événement du guide 16
        mailService.send(new MailMessage(
                event.customerEmail(),
                "Votre commande est confirmée",
                """
                        Bonjour,

                        Votre commande %s d'un montant de %s EUR est confirmée.
                        """.formatted(event.orderId(), event.total())));
    }
}
```

**Étape 5. Envoyer après le commit, sur un autre thread.** Rien ne part pour une commande annulée, et la requête n'attend pas le serveur SMTP. `@EnableAsync` est requis (guide 16).

```java
@Service
@RequiredArgsConstructor
public class MailService {

    private final ApplicationEventPublisher events;
    private final JavaMailSender mailSender;
    private final AppMailProperties properties;

    public void send(MailMessage message) {
        events.publishEvent(message);                       // gardé jusqu'au commit
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void deliver(MailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());
        mailSender.send(mail);                              // une exception est seulement journalisée
    }
}
```

`deliver` est appelée par le mécanisme d'événements, donc à travers le proxy Spring, et `@Async` s'applique. Cette version perd le mail si l'instance s'arrête ou si le serveur SMTP échoue.

### Aucun mail perdu : une file

**Étape 6. Écrire le mail dans l'outbox.** Le mail existe si et seulement si la transaction est validée, et il survit à une panne du broker (guide 20). La signature de `send` ne change pas, ni le listener de l'étape 4.

```java
@Service
@RequiredArgsConstructor
public class MailService {

    private final Outbox outbox;                            // guide 20

    @Transactional                                          // rejoint celle de l'appelant, ou en ouvre une
    public void send(MailMessage message) {
        outbox.enqueue(MailQueues.SEND, message);
    }
}
```

**Étape 7. Déclarer la file et sa dead letter.** On déclare `mail.send` durable, avec `mail.send.dead-letter` pour dead letter, comme dans l'exemple du guide 19. Les réessais se règlent sous `spring.rabbitmq.listener.simple.retry` (guide 19).

**Étape 8. Envoyer depuis le listener de la file.** Une panne SMTP lève une exception, donc des réessais. Un mail impossible à construire part aussitôt en dead letter.

```java
@Component
@RequiredArgsConstructor
class MailQueueListener {

    private final JavaMailSender mailSender;
    private final AppMailProperties properties;

    @RabbitListener(queues = MailQueues.SEND)
    void send(MailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(properties.from());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());

        try {
            mailSender.send(mail);
        } catch (MailParseException | MailPreparationException unbuildable) {
            throw new AmqpRejectAndDontRequeueException("Invalid email", unbuildable);   // guide 19
        }
    }
}
```

Le prédicat de réessai qui exclut `AmqpRejectAndDontRequeueException` se trouve à l'étape 8 du guide 19. Le package de `MailMessage` doit être de confiance pour le convertisseur JSON (étape 3).

**Étape 9. Vérifier avec Mailpit.** Un vrai serveur SMTP en conteneur reçoit le mail, et son API dit ce qui est arrivé. L'envoi est asynchrone, donc le test attend.

```java
@TestConfiguration(proxyBeanMethods = false)
class MailpitContainerConfiguration {

    @Bean
    GenericContainer<?> mailpit() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.2"))
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/livez").forPort(8025));
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
            registry.add("mailpit.api-url",
                    () -> "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025));
        };
    }
}
```

```java
@SpringBootTest
@Import(MailpitContainerConfiguration.class)        // plus Postgres et RabbitMQ (guide 30)
class OrderConfirmationMailTests {

    @Autowired OrderService orderService;           // guide 16
    @Value("${mailpit.api-url}") String mailpitUrl;

    @Test
    void placedOrderSendsAConfirmationEmail() {
        String email = UUID.randomUUID() + "@example.com";    // unique : la boîte est partagée

        orderService.place(email, new BigDecimal("42.00"));

        RestClient mailpit = RestClient.create(mailpitUrl);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode found = mailpit.get()
                    .uri("/api/v1/search?query={query}", "to:\"" + email + "\"")
                    .retrieve().body(JsonNode.class);
            assertThat(found.path("messages_count").asInt()).isEqualTo(1);
        });
    }
}
```

`JsonNode` vient de Jackson 3 (`tools.jackson.databind`), et `await` d'Awaitility, fourni par `spring-boot-starter-test`. L'interface web de Mailpit est sur http://localhost:8025.

### Référence rapide

Les réglages `spring.mail` :

| Réglage | Défaut | Rôle |
|---|---|---|
| `host` | aucun | serveur SMTP (sans lui, pas de `JavaMailSender`) |
| `port` | aucun | port SMTP (souvent 587 en production) |
| `username`, `password` | aucun | identifiants |
| `protocol` | `smtp` | protocole |
| `default-encoding` | UTF-8 | encodage des messages |
| `properties.*` | | propriétés JavaMail (`mail.smtp.auth`, `mail.smtp.starttls.enable`, délais) |
| `test-connection` | false | tester le serveur au démarrage |
| `management.health.mail.enabled` | true | indicateur de santé `mail` (Actuator) |

Limites de cette version : la file ne déduplique pas. Un mail peut partir deux fois (livraison au moins une fois, guide 19).

## Concepts avancés

### Sous le capot : la configuration de Boot

- `MailSenderAutoConfiguration` crée un `JavaMailSenderImpl` si aucun `MailSender` n'existe et si `spring.mail.host` (ou `spring.mail.jndi-name`) est réglé.
- L'indicateur de santé `mail` (`MailHealthIndicator`) appelle `testConnection()` du `JavaMailSenderImpl` et ouvre donc une connexion SMTP à chaque contrôle. Un `JavaMailSender` simulé n'est pas un `JavaMailSenderImpl`, d'où `management.health.mail.enabled` éteint dans le dépôt là où le SMTP est simulé (`@DeadLetterIntegrationTest`).
- Le groupe readiness ne contient que `readinessState` et `db`. Un serveur SMTP en panne ne fait que retarder les mails, qui attendent dans la file (guide 32).

### En production

- Port 587 avec STARTTLS et authentification, identifiants par variables d'environnement (`SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`).
- Le profil `prod` du dépôt règle `spring.mail.host: ${MAIL_HOST:}`, sans valeur par défaut, listé avec `mail.from` dans `deployment.required-properties` (guide 02). Il règle aussi `port: ${MAIL_PORT:587}`, `mail.smtp.auth: ${MAIL_SMTP_AUTH:true}`, ainsi que `mail.smtp.starttls.enable` et `mail.smtp.starttls.required` à `${MAIL_STARTTLS:true}`.

### Écrire un bon mail transactionnel

- Un lien vers une page de l'application, pas vers un téléchargement qui expire en quelques minutes (`DataExportEmailSender`).
- Jamais de secret ni d'URL complète d'un tiers, seulement l'hôte (`WebhookDeliveryService.notifyDisabled`).

### Plusieurs instances

Chaque instance consomme `mail.send`, et RabbitMQ répartit les mails (guide 19). Seul l'`@Async` de l'étape 5 reste local.

## Pièges à éviter

### Avec l'envoi

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Envoyer dans la requête | la réponse attend le serveur SMTP | Envoyer après le commit, hors de la requête |
| Envoyer depuis un `@EventListener` synchrone | un mail part pour un changement annulé | Envoyer après le commit, ou par l'outbox |
| Garder les délais JavaMail par défaut | un serveur muet bloque le thread pour toujours | Régler `connectiontimeout`, `timeout` et `writetimeout` |
| Oublier `spring.mail.host` | aucun `JavaMailSender` : le démarrage échoue sur l'injection | Le régler, obligatoire en production |
| Réessayer un mail mal formé | chaque essai échoue pareil, puis dead letter tardive | Rejeter `MailParseException` tout de suite |
| Compter sur `@Async` pour un mail important | un arrêt ou une panne SMTP le perd | Passer par l'outbox et une file |
| Mettre un lien qui expire vite ou une URL secrète | le lien ne marche plus, ou le secret fuit | Mettre un lien vers une page, l'hôte seulement |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Vérifier le mail tout de suite | l'envoi est asynchrone : le test échoue au hasard | Attendre, avec un délai maximal |
| Vérifier « aucun mail » tout de suite | le test passe à tort | Attendre un délai de grâce, puis compter |
| Réutiliser une adresse entre tests | la boîte Mailpit est partagée : comptes faux | Prendre une adresse unique par test |
| Simuler `JavaMailSender` avec l'indicateur de santé actif | l'indicateur attend un `JavaMailSenderImpl` | Régler `management.health.mail.enabled=false` |
| Supposer l'ordre de deux mails envoyés à la suite | la file peut les livrer dans l'autre ordre | Chercher le mail par son contenu |

## En résumé

**À retenir :** le mail est écrit dans la transaction, publié par l'outbox après le commit, puis envoyé par le listener de la file avec réessais et dead letter. Mailpit le reçoit en développement et en test.

- [ ] `spring-boot-starter-mail`, `spring.mail.host` et des délais SMTP réglés
- [ ] Une adresse d'expédition validée au démarrage
- [ ] Le contenu écrit dans un listener, dans la transaction
- [ ] L'envoi par l'outbox et une file, jamais dans la requête
- [ ] Échec SMTP réessayé, mail impossible rejeté tout de suite en dead letter
- [ ] Pas de secret ni de lien éphémère dans le texte
- [ ] Mailpit dans `compose.yaml` et en conteneur de test
- [ ] Tests qui attendent l'envoi, avec des adresses uniques
- [ ] STARTTLS, authentification et identifiants par variables en production

## Dans ce dépôt

Qui écrit des mails, et le trajet jusqu'au SMTP :

```
  VerificationEmailSender      EmailVerificationRequested (inscription, renvoi)
  PasswordResetEmailSender     PasswordResetRequested
  InactiveAccountEmailSender   InactiveAccountWarned (rétention RGPD)
  TaskNotificationSender       TaskAssigned, TaskCommentAdded, TaskDueSoon...
  DataExportEmailSender        DataExportCompleted, DataExportFailed
  WebhookDeliveryService       endpoint désactivé après 3 jours d'échecs
            |
            |  MailService.send(MailMessage)       dans la transaction
            v
  Outbox.enqueue("mail.send")  --> commit --> OutboxRelay (guide 20)
            |
            v
  MailQueueListener            JavaMailSender, From = mail.from
            |
            +-- échec SMTP --> 4 réessais (2 s, x3, 1 min au plus)
                               --> mail.send.dead-letter
```

| Fichier | Rôle |
|---|---|
| [`MailService`][mail-service] | le point d'entrée : `@Transactional`, écrit dans l'outbox |
| [`MailMessage`][mail-message] | `to`, `subject`, `text` ; texte brut seulement |
| [`MailProperties`][mail-props] | `mail.from`, `@NotBlank @Email` (appelée `AppMailProperties` dans le guide, pour éviter le nom de celle de Boot) |
| [`MailConfiguration`][mail-config] | enregistre `MailProperties` ; porte aussi `@EnableAsync` (guide 16) |
| [`MailQueues`][mail-queues] | `mail.send` et `mail.send.dead-letter` |
| [`MailQueueListener`][mail-listener] | envoie ; `MailParseException` et `MailPreparationException` deviennent `AmqpRejectAndDontRequeueException` |
| [`VerificationEmailSender`][verification-sender] | un exemple simple de mail écrit dans un `@EventListener` |
| [`TaskNotificationSender`][task-sender] | les règles des notifications par mail |
| [`DataExportEmailSender`][export-sender] | lien vers la page de l'export, jamais vers le fichier |

Les réglages :

| Réglage | Valeur | Rôle |
|---|---|---|
| `mail.from` | `${MAIL_FROM:no-reply@tasks.local}` | adresse d'expédition de tous les mails |
| `spring.mail.host` / `port` | `localhost` / `1025` (`MAIL_HOST`, `MAIL_PORT`) | Mailpit de `compose.yaml` |
| `spring.mail.port` en `prod` | `${MAIL_PORT:587}` | port de soumission |
| `mail.smtp.auth`, `starttls.enable`, `starttls.required` en `prod` | `true` (`MAIL_SMTP_AUTH`, `MAIL_STARTTLS`) | authentification et STARTTLS |

Ce qui est propre au dépôt :

- `TaskNotificationSender` : seul l'assigné concerné (ou la personne mentionnée) reçoit un mail. Jamais pour sa propre action, seulement si son compte est actif (`UserStatus.ACTIVE`, lu en base) et si l'interrupteur correspondant est allumé (`NotificationSettingsService.isEnabled`). Un assigné mentionné reçoit le mail de mention au lieu du mail de commentaire, sauf s'il a éteint les mentions.
- Les dates des mails sont en UTC, écrites avec « UTC ». Le mail « webhook désactivé » ne cite que l'hôte.
- En production, `spring.mail.host` et `mail.from` n'ont pas de valeur par défaut et sont vérifiés au démarrage (`deployment.required-properties`, guide 02).

Les tests :

- [`Mailpit`][mailpit] lit les mails par l'API de Mailpit. `latestTextTo` attend jusqu'à 5 s (sondage toutes les 100 ms), `countTo` compte, `latestVerificationTokenFor` extrait le jeton de 43 caractères.
- [`TestcontainersConfiguration`][containers] porte le conteneur Mailpit, réutilisé, et règle `spring.mail.host`, `spring.mail.port` et `mailpit.api-url` par un `DynamicPropertyRegistrar`.
- [`MailQueueTests`][mail-queue-tests] joue le trajet complet : un mail validé arrive, attend dans la file sans consommateur, et n'existe pas après un rollback (délai de grâce de 800 ms avant de compter).
- [`MailQueueListenerTest`][listener-test] teste le listener seul : envoi, réessais sur les échecs SMTP, rejet immédiat.
- Les réessais et la dead letter sont testés par `MailQueueDeadLetterTests` (guide 19).

Pour ajouter un mail : un événement publié par le service concerné, un `...EmailSender` avec un `@EventListener` qui appelle `MailService.send`, et un test qui lit le mail avec `Mailpit`.

## Voir aussi

- [16. Événements applicatifs et `@Async`](16-evenements-async.md) : les listeners qui écrivent les mails.
- [19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) : la file, les réessais et la dead letter.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : le mail n'existe qu'après le commit.
- [30. Testcontainers](30-testcontainers.md) : le conteneur Mailpit réutilisé.
- [Cas pratique : une notification de bout en bout](cas-pratiques/notification-bout-en-bout.md).
- Documentation officielle : [mails avec Spring Boot](https://docs.spring.io/spring-boot/reference/io/email.html), [API de Mailpit](https://mailpit.axllent.org/docs/api-v1/).

[Sommaire](README.md) | [< 22. Spring Batch](22-spring-batch.md) | [24. Flux de notifications (SSE) >](24-flux-notifications-sse.md)

[mail-service]: ../../src/main/java/io/julienmetral/tasks/mail/MailService.java
[mail-message]: ../../src/main/java/io/julienmetral/tasks/mail/MailMessage.java
[mail-props]: ../../src/main/java/io/julienmetral/tasks/mail/MailProperties.java
[mail-config]: ../../src/main/java/io/julienmetral/tasks/mail/MailConfiguration.java
[mail-queues]: ../../src/main/java/io/julienmetral/tasks/mail/MailQueues.java
[mail-listener]: ../../src/main/java/io/julienmetral/tasks/mail/MailQueueListener.java
[verification-sender]: ../../src/main/java/io/julienmetral/tasks/identity/mail/VerificationEmailSender.java
[task-sender]: ../../src/main/java/io/julienmetral/tasks/notification/mail/TaskNotificationSender.java
[export-sender]: ../../src/main/java/io/julienmetral/tasks/export/mail/DataExportEmailSender.java
[mailpit]: ../../src/test/java/io/julienmetral/tasks/support/Mailpit.java
[containers]: ../../src/test/java/io/julienmetral/tasks/TestcontainersConfiguration.java
[mail-queue-tests]: ../../src/test/java/io/julienmetral/tasks/mail/MailQueueTests.java
[listener-test]: ../../src/test/java/io/julienmetral/tasks/mail/MailQueueListenerTest.java
