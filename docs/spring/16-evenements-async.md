# 16. Événements applicatifs et `@Async`

[Sommaire](README.md) | [< 15. Concurrence et verrous](15-concurrence-verrous.md) | [17. Cache Caffeine >](17-cache-caffeine.md)

## Contexte

- **Problème** : une fonctionnalité doit réagir à ce qu'une autre a fait (une commande passée crée une facture, envoie un mail, vide un cache) sans que la première connaisse la seconde.
- **Quand l'utiliser** : découpler deux paquets d'une même application, agir seulement si la transaction est validée, ou rendre la main à la requête pendant un travail court.
- **Quand s'en passer** : un seul appelant et un seul appelé dans le même paquet (un appel direct est plus lisible). Un travail qui ne doit pas se perdre si l'instance s'arrête (file, guide 19, outbox, guide 20). Un événement qui doit atteindre les autres instances (guide 24).
- **Dans ce dépôt** : le paquet `task` publie `TaskAssigned`, `TaskCommentAdded`... et ne connaît pas `notification`. Mails, webhooks et temps réel écoutent dans la transaction. Le cache de statut se vide après le commit. L'outbox publie sur un thread `@Async`.
- **Prérequis** : guide 14 (transactions). Versions : Spring Boot 4.1, Spring Framework 7.0, Java 25 (threads virtuels).

Les façons de faire réagir une autre partie du code :

| Solution | Ce qui se passe | Quand la choisir |
|---|---|---|
| Appel direct | le service appelle l'autre service | un seul réacteur, même paquet |
| `@EventListener` | appelé tout de suite, même thread, même transaction | écrire en base avec le changement |
| `@TransactionalEventListener` | appelé après le commit (par défaut) | agir sur un changement validé : cache, notification |
| `@Async` | un autre thread de la même instance | travail court, perte acceptable si l'instance s'arrête |
| Outbox et broker (guides 20 et 19) | message durable, réessayé | travail qui ne doit pas se perdre, plusieurs instances |

## Concepts clés

### Vocabulaire

- **Événement applicatif** : un objet (souvent un record) qui dit ce qui s'est passé, publié dans le contexte Spring. Depuis Spring 4.2, tout objet peut être publié sans étendre `ApplicationEvent`.
- **Publieur** : le code qui appelle `ApplicationEventPublisher.publishEvent(...)`.
- **Phase** : le moment de la transaction où un `@TransactionalEventListener` est appelé (`BEFORE_COMMIT`, `AFTER_COMMIT`, `AFTER_ROLLBACK`, `AFTER_COMPLETION`).
- **Exécuteur** (executor) : le composant qui fournit les threads des méthodes `@Async`.

### Synchrone par défaut

- `publishEvent` appelle chaque `@EventListener` tout de suite, sur le thread du publieur, l'un après l'autre. Le multicaster par défaut n'a pas d'exécuteur.
- Le listener tourne donc dans la transaction du publieur, et ses écritures sont validées ou annulées avec elle.
- Une exception levée par un listener remonte au publieur. La transaction est annulée et l'appelant reçoit l'exception.
- Un listener peut publier à son tour un événement, et la chaîne reste synchrone.
- L'ordre entre listeners n'est pas garanti. `@Order` sur la méthode le fixe.

Tout se passe sur un thread, dans une transaction :

```
 thread de la requête
 --------------------
 OrderService.place          @Transactional
   |  INSERT orders
   |  publishEvent(OrderPlaced)
   |      |
   |      +--> InvoiceCreator.onOrderPlaced     INSERT invoices
   |      |
   |      +--> StockReserver.onOrderPlaced      UPDATE stock
   |      |
   |  <---+   (retour après le dernier listener)
   v
 COMMIT  --> commande, facture et stock validés ensemble
```

Un listener lent retarde la réponse et garde la connexion à la base.

### Après le commit : `@TransactionalEventListener`

Il est lié à une phase, `AFTER_COMMIT` par défaut. Spring garde l'événement de côté et appelle le listener quand la transaction se termine, donc rien ne part pour un changement annulé.

```
 transaction
     |
     |  publishEvent(OrderPlaced)   --> mis de côté
     |
     +-- BEFORE_COMMIT    encore dans la transaction
     |
  COMMIT ou ROLLBACK
     |
     +-- AFTER_COMMIT     seulement si validée (par défaut)
     +-- AFTER_ROLLBACK   seulement si annulée
     +-- AFTER_COMPLETION dans les deux cas
```

- Publié hors transaction, l'événement est ignoré, sauf avec `fallbackExecution = true`. Le listener est alors appelé tout de suite.
- Une exception levée après le commit ne remonte pas. Spring la journalise (ERROR) et continue. Le changement est déjà validé, donc il faut prévoir ce cas (TTL, réessai).
- Écrire en base depuis un listener d'après commit est un piège : le code d'accès aux données rejoint la transaction finie et n'est jamais validé. Depuis Spring 6.1, un `@Transactional` sur un tel listener est refusé au démarrage (`IllegalStateException`), sauf en `REQUIRES_NEW` ou `NOT_SUPPORTED`.

### `@Async` : rendre la main

- `@EnableAsync` s'écrit une fois. Sur une méthode `public` d'un bean, `@Async` fait déposer l'appel par le proxy Spring sur un exécuteur, qui rend la main aussitôt.
- Le type de retour est `void`, `Future` ou `CompletableFuture`.
- L'exception d'une méthode `void` n'a pas de destinataire, et Spring la journalise en ERROR. Avec un `CompletableFuture`, l'appelant la reçoit.
- Un nouveau thread n'a pas de transaction, donc une méthode `@Async @Transactional` ouvre la sienne.
- Le travail vit en mémoire. Un arrêt de l'instance le perd, et Boot n'attend pas les tâches en cours par défaut.

```
 thread de la requête              thread virtuel (applicationTaskExecutor)
 --------------------              ----------------------------------------
 appelle mailer.send(...)
   |  (proxy Spring)
   +-- dépose l'appel ------------> send(...) s'exécute
   |                                  |
   v                                  v
 continue, répond                   fin ; exception seulement journalisée
```

Boot fournit l'exécuteur, le bean `applicationTaskExecutor` :

- avec `spring.threads.virtual.enabled: true`, un `SimpleAsyncTaskExecutor` qui crée un thread virtuel par appel, sans limite par défaut ;
- sinon, un `ThreadPoolTaskExecutor` de 8 threads de base et à file non bornée, qui ne grandit donc jamais au-delà de 8.

Boot ne le crée que si aucun bean `Executor` n'existe déjà, ou avec `spring.task.execution.mode=force`.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  order/
    OrderPlaced.java                  l'événement (étape 1)
    OrderService.java                 le publie (étape 2)
  billing/
    InvoiceCreator.java               écoute dans la transaction (étape 3)
  notification/
    ConfirmationMailer.java           l'envoi du mail (étape 4)
    OrderConfirmationListener.java    écoute après le commit (étapes 4 et 5)
  config/
    AsyncConfiguration.java           active @Async (étape 5)
src/main/resources/
  application.yaml                    threads virtuels (étape 5)
src/test/java/.../
  OrderEventsTests.java               la vérification (étape 6)
```

### Un événement dans la même application

**Étape 1. Décrire l'événement.** Un record immuable, nommé au passé, qui porte des identifiants et des valeurs, jamais l'entité. Le listener peut en effet tourner après la fermeture de la session, ou sur un autre thread.

```java
public record OrderPlaced(UUID orderId, String customerEmail, BigDecimal total) {
}
```

**Étape 2. Publier depuis le service.** Le service annonce ce qui s'est passé, sans savoir qui écoute.

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;   // entité Order et repository JPA (guide 11)
    private final ApplicationEventPublisher events;

    @Transactional
    public Order place(String customerEmail, BigDecimal total) {
        Order order = orderRepository.save(new Order(customerEmail, total));
        events.publishEvent(new OrderPlaced(order.getId(), customerEmail, total));
        return order;
    }
}
```

**Étape 3. Écouter dans la transaction.** La facture doit exister si et seulement si la commande existe. Le listener écrit en base, sans appel réseau.

```java
@Component
@RequiredArgsConstructor
class InvoiceCreator {

    private final InvoiceRepository invoiceRepository;   // entité Invoice et repository JPA (guide 11)

    @EventListener
    public void onOrderPlaced(OrderPlaced event) {
        // même thread, même transaction : facture et commande sont validées ou annulées ensemble
        invoiceRepository.save(new Invoice(event.orderId(), event.total()));
    }
}
```

**Étape 4. Agir après le commit.** Un mail pour une commande annulée serait faux. `fallbackExecution` couvre une publication hors transaction.

```java
public interface ConfirmationMailer {          // implémenté au guide 23

    void sendConfirmation(String email, UUID orderId);
}
```

```java
@Component
@RequiredArgsConstructor
class OrderConfirmationListener {

    private final ConfirmationMailer mailer;

    @TransactionalEventListener(fallbackExecution = true)   // AFTER_COMMIT par défaut
    public void onOrderPlaced(OrderPlaced event) {
        mailer.sendConfirmation(event.customerEmail(), event.orderId());
    }
}
```

**Étape 5. Passer le travail lent sur un autre thread.** Après le commit, le listener tourne encore sur le thread de la requête. `@Async` rend la main tout de suite.

```java
@Configuration
@EnableAsync
class AsyncConfiguration {
}
```

```yaml
spring:
  threads:
    virtual:
      enabled: true     # @Async et les requêtes sur des threads virtuels
```

```java
// dans OrderConfirmationListener
@Async
@TransactionalEventListener(fallbackExecution = true)
public void onOrderPlaced(OrderPlaced event) {
    mailer.sendConfirmation(event.customerEmail(), event.orderId());
}
```

**Étape 6. Vérifier.** `@RecordApplicationEvents` capture les événements publiés. Un mock du mailer prouve l'envoi après un commit et l'absence d'envoi après un rollback. Il faut une vraie base (guide 30).

```java
@SpringBootTest
@RecordApplicationEvents
class OrderEventsTests {

    @Autowired OrderService orderService;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ApplicationEvents events;
    @MockitoBean ConfirmationMailer mailer;

    @Test
    void placingAnOrderPublishesTheEventAndMailsAfterCommit() {
        Order order = orderService.place("ana@example.com", new BigDecimal("42.00"));

        assertThat(events.stream(OrderPlaced.class)).hasSize(1);
        verify(mailer, timeout(2_000)).sendConfirmation("ana@example.com", order.getId());   // @Async
    }

    @Test
    void rolledBackOrderSendsNoMail() {
        transactionTemplate.executeWithoutResult(status -> {
            orderService.place("bob@example.com", BigDecimal.TEN);
            status.setRollbackOnly();
        });

        verify(mailer, after(500).never()).sendConfirmation(eq("bob@example.com"), any());
    }
}
```

### Référence rapide

Les annotations :

| Annotation | Attribut | Rôle |
|---|---|---|
| `@EventListener` | `classes`, `condition` | écoute un type, `condition` étant une expression SpEL (`#event.total > 100`) |
| `@TransactionalEventListener` | `phase` | `AFTER_COMMIT` par défaut |
| `@TransactionalEventListener` | `fallbackExecution` | appelle aussi le listener hors transaction (false par défaut) |
| `@Order` | valeur | ordre entre listeners, plus petit d'abord |
| `@EnableAsync` | | active `@Async` dans l'application |
| `@Async` | `value` | nom du bean exécuteur à utiliser (sinon celui par défaut) |

Les réglages de l'exécuteur :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.threads.virtual.enabled` | false | un thread virtuel par appel `@Async` |
| `spring.task.execution.mode` | auto | `force` : créer l'exécuteur même si un autre `Executor` existe |
| `spring.task.execution.shutdown.await-termination` | false | attendre les tâches en cours à l'arrêt |

Limites de cette version : le mail d'après commit vit en mémoire. Un arrêt de l'instance entre le commit et l'envoi le perd, sans réessai (guides 19 et 20 pour ne rien perdre).

## Concepts avancés

### Sous le capot : la synchronisation de transaction

`@TransactionalEventListener` n'appelle rien au moment de `publishEvent` : il enregistre une `TransactionSynchronization` sur la transaction du thread. Sans transaction active, le listener est ignoré, sauf `fallbackExecution`.

- `AFTER_COMMIT`, `AFTER_ROLLBACK` et `AFTER_COMPLETION` tournent dans le `afterCompletion(status)` de la synchronisation ; `BEFORE_COMMIT` dans son `beforeCommit`.
- Spring appelle tous les `afterCommit()`, puis tous les `afterCompletion()`. Un listener `AFTER_COMMIT` passe donc après le rappel `afterCommit()` de l'outbox (guide 20) de la même transaction.
- Les synchronisations sont triées par ordre (`@Order`), et leurs exceptions sont journalisées une par une.

### Sous le capot : le proxy Spring de `@Async`

`@EnableAsync` place un proxy Spring devant le bean. L'appel passe par un intercepteur, qui le confie à l'exécuteur. Un appel `this.method()` depuis la même classe contourne le proxy et s'exécute tout de suite, sur le même thread. `@Async("nomDuBean")` choisit un exécuteur précis.

### Spring Data : `@DomainEvents`

Spring Data publie les événements d'un agrégat (`@DomainEvents`, `AbstractAggregateRoot`) seulement lors d'un appel `save...` ou `delete...` du repository. Une modification par dirty checking ne publie rien. Ce dépôt publie donc avec `ApplicationEventPublisher`.

### Plusieurs instances

Un événement applicatif reste dans la JVM qui l'a publié, et les autres instances ne le voient pas. Pour les prévenir, le listener écrit un message dans l'outbox vers un exchange fanout (guides 20 et 24).

Dans ce dépôt, `AccountStateChanged` reste local. `RealtimeBroadcasts` le transforme en diffusion `AccountStatusChanged`, et chaque instance vide son cache et ferme les flux du compte.

## Pièges à éviter

### Avec `@EventListener` (synchrone)

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Faire un appel réseau dans un listener synchrone | la transaction et sa connexion restent ouvertes | Écrire en base (outbox), appeler après le commit |
| Laisser un listener lever une exception | la transaction du publieur est annulée | Ne lever que pour refuser le changement entier |
| Compter sur `@DomainEvents` avec le dirty checking | rien n'est publié sans `save()` | Publier avec `ApplicationEventPublisher` |

### Avec `@TransactionalEventListener`

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Publier hors transaction sans `fallbackExecution` | le listener n'est jamais appelé | Ajouter `fallbackExecution = true` |
| Écrire en base dans un listener d'après commit | l'écriture rejoint la transaction finie et n'est jamais validée | `REQUIRES_NEW`, ou `@Async` |
| Poser `@Transactional` (REQUIRED) sur ce listener | Spring refuse de démarrer | `REQUIRES_NEW` ou `NOT_SUPPORTED` |
| Compter sur l'exception d'un listener d'après commit | elle est seulement journalisée | Prévoir le rattrapage (TTL, réessai) |

### Avec `@Async`

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Appeler la méthode `@Async` depuis sa propre classe | l'appel contourne le proxy : synchrone | Passer par un autre bean |
| Ignorer l'exception d'une méthode `void` | elle est journalisée, puis perdue | Un `AsyncUncaughtExceptionHandler`, ou `CompletableFuture` |
| Déclarer son propre bean `Executor` | Boot ne crée plus `applicationTaskExecutor` | Nommer l'exécuteur dans `@Async`, ou `mode: force` |
| Confier à `@Async` un travail à ne pas perdre | un arrêt de l'instance l'efface | Une file (guide 19) et l'outbox (guide 20) |

### Dans ce dépôt

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Changer un compte sans publier `AccountStateChanged` | un compte désactivé garde l'accès jusqu'à 30 s | Publier l'événement dans chaque chemin |
| Importer `notification` depuis `task` | le découplage disparaît | Publier un événement de `task.events` |

## En résumé

**À retenir :** `@EventListener` agit dans la transaction du publieur, `@TransactionalEventListener` après le commit, `@Async` sur un autre thread. Ce qui ne doit pas se perdre passe par une file.

- [ ] Des records au passé, avec des identifiants, jamais d'entité
- [ ] `ApplicationEventPublisher` dans le service, pas de dépendance vers les réacteurs
- [ ] Dans la transaction : seulement des écritures en base, sans appel réseau
- [ ] Après le commit : `@TransactionalEventListener`, `fallbackExecution` si l'on publie hors transaction
- [ ] Pas d'écriture en base après le commit sans `REQUIRES_NEW` ou `@Async`
- [ ] `@EnableAsync`, threads virtuels, méthodes `@Async` appelées depuis un autre bean
- [ ] Les exceptions asynchrones journalisées et surveillées
- [ ] Le travail à ne pas perdre confié à une file, pas à `@Async`
- [ ] Les autres instances prévenues par un broker, pas par un événement local

## Dans ce dépôt

Qui écoute quoi, tout dans la transaction de la requête :

```
  TaskService.assign                         transaction de la requête
       |  publishEvent(TaskAssigned)
       |
       +--> TaskNotificationSender           @EventListener --> mail (outbox)
       |
       +--> TaskNotificationPublisher        @EventListener
                |  publishEvent(TaskNotificationCreated)
                +--> WebhookNotificationSender   livraison + outbox
                +--> RealtimeBroadcasts          outbox (diffusion)

  UserService.disable                        transaction de la requête
       |  publishEvent(AccountStateChanged)
       +--> RealtimeBroadcasts               @EventListener --> outbox
       +--> UserStatusCacheEviction          AFTER_COMMIT --> vide le cache
```

| Fichier | Rôle |
|---|---|
| [`TaskAssigned`][task-assigned] | un des records de `task.events` (`TaskUnassigned`, `TaskCancelled`, `TaskDeleted`, `TaskCommentAdded`...) |
| [`TaskService`][task-service] | publie après chaque modification, sans connaître les réacteurs |
| [`TaskNotificationPublisher`][publisher] | un listener qui publie à son tour `TaskNotificationCreated`, une par destinataire |
| [`TaskNotificationSender`][mail-sender] | écrit les mails, dans la transaction |
| [`RealtimeBroadcasts`][broadcasts] | écrit les diffusions dans l'outbox, avec un `TransactionTemplate` hors transaction |
| [`AccountStateChanged`][account-event] | publié par chaque changement d'état d'un compte |
| [`UserStatusCacheEviction`][eviction] | le seul `@TransactionalEventListener` (`AFTER_COMMIT`, `fallbackExecution`, `@Order(HIGHEST_PRECEDENCE)`) |
| [`MailConfiguration`][mail-config] | porte `@EnableAsync` |
| [`OutboxRelay`][relay] | `publishNow`, la seule méthode `@Async` (guide 20) |
| [`NotificationStreams`][streams] | écoute `ContextClosedEvent` pour fermer les flux (guide 24) |

Le seul réglage propre à ce guide est `spring.threads.virtual.enabled: true`, qui met les requêtes, les méthodes `@Async` (`OutboxRelay.publishNow`), les jobs `@Scheduled` et les listeners RabbitMQ sur des threads virtuels.

Ce qui est propre au dépôt :

- Tous les listeners métier sont des `@EventListener` synchrones, qui n'écrivent qu'en base (lignes de livraison, outbox). Mails, webhooks et diffusions partent après le commit, par l'outbox (guide 20), jamais depuis le listener. Le paquet `task` n'importe rien de `notification`.
- Publient des événements : `TaskService`, `TaskCommentService`, `TaskReminderService`, `TaskEventService` (`TaskEventRecorded`, un par ligne d'historique), `UserService`, `EmailVerificationService`, `PasswordResetService`, `UserRetentionService` et `DataExportService`.
- `AccountStateChanged` est publié par `UserService.verifyEmail`, `enable`, `disable`, `delete`, par `EmailVerificationService.verify` et par la confirmation de `PasswordResetService` quand elle marque l'email vérifié, toujours dans une méthode `@Transactional`. Tout nouveau chemin qui change un compte (SQL natif compris) doit le publier. Sinon, le statut en cache vit jusqu'à son TTL de 30 s (guide 17).
- `UserStatusCacheEviction` porte `fallbackExecution = true`, car un changement publié hors transaction ne viderait sinon jamais le cache. Spring ne fait que journaliser son exception, donc le TTL reste la borne.
- `@EnableAsync` est sur `MailConfiguration`. La seule méthode `@Async` est `OutboxRelay.publishNow`, appelée depuis le `afterCommit()` de l'outbox.

Les tests :

- [`TaskReminderTests`][reminder-tests] : `@RecordApplicationEvents` et `ApplicationEvents` vérifient les `TaskDueSoon` et `TaskOverdue` publiés.
- [`TaskNotificationSenderTest`][sender-test] : les règles du listener, en test unitaire.
- [`TaskAccessStatusCacheApiTests`][cache-api-tests] : `statusIsEvictedOnlyOnceTheChangeCommits`, `rolledBackChangeKeepsTheCachedStatus` et `changePublishedOutsideATransactionStillEvictsTheStatus`.

Pour ajouter un événement : un record dans le paquet `events` de la fonctionnalité qui publie, `publishEvent` dans la méthode `@Transactional` du service, et dans l'autre paquet un `@EventListener` qui n'écrit qu'en base, ou un `@TransactionalEventListener` pour un effet après le commit.

## Voir aussi

- [14. Transactions](14-transactions.md) : propagation, `REQUIRES_NEW`, travail après le commit.
- [17. Cache Caffeine](17-cache-caffeine.md) : l'éviction après le commit.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : l'appel `@Async` après le commit, et ne rien perdre.
- [24. Flux de notifications (SSE)](24-flux-notifications-sse.md) : prévenir toutes les instances.
- [Cas pratique : une notification de bout en bout](cas-pratiques/notification-bout-en-bout.md).
- Documentation officielle : [événements liés aux transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html), [`@Async`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html).

[Sommaire](README.md) | [< 15. Concurrence et verrous](15-concurrence-verrous.md) | [17. Cache Caffeine >](17-cache-caffeine.md)

[task-assigned]: ../../src/main/java/io/julienmetral/tasks/task/events/TaskAssigned.java
[task-service]: ../../src/main/java/io/julienmetral/tasks/task/services/TaskService.java
[publisher]: ../../src/main/java/io/julienmetral/tasks/notification/services/TaskNotificationPublisher.java
[mail-sender]: ../../src/main/java/io/julienmetral/tasks/notification/mail/TaskNotificationSender.java
[broadcasts]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcasts.java
[account-event]: ../../src/main/java/io/julienmetral/tasks/identity/events/AccountStateChanged.java
[eviction]: ../../src/main/java/io/julienmetral/tasks/identity/security/UserStatusCacheEviction.java
[mail-config]: ../../src/main/java/io/julienmetral/tasks/mail/MailConfiguration.java
[relay]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelay.java
[streams]: ../../src/main/java/io/julienmetral/tasks/realtime/sse/NotificationStreams.java
[reminder-tests]: ../../src/test/java/io/julienmetral/tasks/task/services/TaskReminderTests.java
[sender-test]: ../../src/test/java/io/julienmetral/tasks/notification/mail/TaskNotificationSenderTest.java
[cache-api-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskAccessStatusCacheApiTests.java
