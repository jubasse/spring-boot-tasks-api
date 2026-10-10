# 20. Outbox transactionnelle

[Sommaire](README.md) | [< 19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) | [21. Jobs planifiés et ShedLock >](21-jobs-planifies-shedlock.md)

## Contexte

- **Problème** : un changement en base et le message qui l'annonce doivent exister tous les deux, ou aucun des deux, alors que la base et le broker n'ont pas de transaction commune.
- **Quand l'utiliser** : dès qu'un message découle d'une écriture en base : le mail de vérification d'un compte créé, le traitement d'une photo enregistrée.
- **Quand s'en passer** : un message qui ne dépend d'aucune écriture ; une perte rare acceptable (`@TransactionalEventListener` suffit, guide 16).
- **Dans ce dépôt** : chaque message RabbitMQ (mails, photos, webhooks, exports, temps réel) passe par la classe `Outbox`.
- **Prérequis** : guides 14 (transactions), 15 (`SKIP LOCKED`) et 19 (RabbitMQ). Versions : Spring Boot 4.1, Spring AMQP 4.1, PostgreSQL 18.

Les façons d'envoyer un message lié à une écriture :

| Solution | Principe | Limite |
|---|---|---|
| Publier dans la transaction | `convertAndSend` avant le commit | le message part pour un changement annulé |
| Publier après le commit | `@TransactionalEventListener`, puis `RabbitTemplate` | perdu si le broker ne répond pas, ou si l'instance s'arrête |
| Outbox et relais (ce guide) | une ligne écrite dans la transaction, publiée ensuite | une table, un poller, des doublons possibles |
| Capture des changements (CDC) | Debezium lit le journal de Postgres et publie | un service de plus à déployer et à surveiller |
| Spring Modulith | enregistre en base chaque événement à traiter, le relance s'il échoue | une bibliothèque et ses conventions en plus |

## Concepts clés

### Vocabulaire

- **Double écriture** (dual write) : écrire dans deux systèmes, la base et le broker, sans transaction commune.
- **Outbox** : la table où l'on écrit les messages dans la transaction métier, avant de les publier.
- **Relais** : le composant qui lit l'outbox, publie vers le broker et marque les lignes publiées.
- **Confirmation** : l'accusé du broker pour un message publié (guide 19).
- **Poller** : un job qui relit la table à intervalle régulier pour rattraper ce qui n'est pas parti.

### Le problème de la double écriture

Les deux ordres possibles échouent, chacun à sa façon :

```
 publier, puis valider                  valider, puis publier
 ---------------------                  ---------------------
 INSERT INTO users ...                  INSERT INTO users ...
 publier le mail --> parti              COMMIT
 COMMIT --> échoue, rollback            publier le mail --> broker injoignable
        |                                      |
        v                                      v
 mail pour un compte inexistant         compte créé, mail perdu
```

- Aucune transaction ne couvre les deux : RabbitMQ ne gère pas les transactions distribuées (XA).
- Réessayer en mémoire ne suffit pas : un arrêt de l'instance efface les réessais.

### L'outbox

Le message devient une ligne, écrite par la même transaction que le changement :

```
 transaction métier
 +-------------------------------------------+
 |  INSERT INTO users ...                    |
 |  INSERT INTO outbox_messages ...          |
 +-------------------------------------------+
                       |
                     COMMIT : les deux lignes, ou aucune
                       |
                       v
 relais : lit les lignes non publiées --> publie --> attend la confirmation
                       |
                       v
               published_at = maintenant
```

- Un rollback efface le message avec le changement. Un broker en panne ne perd rien : la ligne attend.
- Le relais publie juste après le commit, pour la vitesse ; un poller rattrape les échecs, pour la sûreté.

### Au moins une fois

Le relais marque la ligne publiée après la confirmation, dans sa propre transaction. Un arrêt entre les deux republie le message :

```
 relais : publie --> confirmation reçue --> s'arrête avant son COMMIT
 table  : published_at toujours vide
 poller : publie la ligne de nouveau            (message reçu deux fois)
```

L'outbox échange « parfois perdu » contre « parfois en double ». Les consommateurs doivent donc être idempotents (guide 19).

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/resources/
  db/changelog/...                     la table outbox_messages (étape 1)
  application.yaml                     les confirmations (étape 5)
src/main/java/.../messaging/
  OutboxMessage.java                   l'entité (étape 2)
  OutboxMessageRepository.java         les lectures avec SKIP LOCKED (étape 3)
  Outbox.java                          l'écriture dans la transaction (étape 4)
  OutboxRelay.java                     la publication (étapes 6 et 7)
  OutboxRelayJob.java                  le poller et la purge (étape 8)
src/test/java/.../
  OutboxTests.java                     la vérification (étape 9)
```

### Une outbox publiée juste après le commit

**Étape 1. Créer la table.** Par une migration (guide 18). L'index sert au poller, qui cherche les lignes non publiées et dues.

```sql
CREATE TABLE outbox_messages (
    id              UUID          PRIMARY KEY DEFAULT uuidv7(),   -- Postgres 18 (guide 12)
    queue           VARCHAR(255)  NOT NULL,
    type            VARCHAR(255)  NOT NULL,      -- classe du message, pour l'en-tête __TypeId__
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    last_error      VARCHAR(1000),
    published_at    TIMESTAMPTZ
);

CREATE INDEX outbox_messages_pendingIDX ON outbox_messages (published_at, next_attempt_at);
```

**Étape 2. Mapper l'entité.** Le contenu est une `Map` rangée en `jsonb` : le relais n'a pas besoin de connaître la classe du message.

```java
@Entity
@Table(name = "outbox_messages")
@Getter
@Setter
@NoArgsConstructor
public class OutboxMessage {

    @Id
    @Generated                                   // l'id vient de la base (guide 12)
    @ColumnDefault("uuidv7()")
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String queue;

    @Column(nullable = false, updatable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> payload;

    private Instant createdAt;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant publishedAt;
}
```

**Étape 3. Écrire les lectures avec verrou.** `FOR UPDATE SKIP LOCKED` saute la ligne qu'un autre thread publie déjà (guide 15).

```java
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE id IN (:ids) AND published_at IS NULL
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockUnpublished(@Param("ids") Collection<UUID> ids);

    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE published_at IS NULL AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM outbox_messages WHERE published_at < :cutoff", nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
```

**Étape 4. Écrire le message dans la transaction de l'appelant.** `MANDATORY` refuse un appel sans transaction ; la publication est demandée pour l'après-commit.

```java
@Service
@RequiredArgsConstructor
public class Outbox {

    private final OutboxMessageRepository repository;
    private final OutboxRelay relay;
    private final JsonMapper jsonMapper;          // Jackson 3
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String queue, Object message) {
        OutboxMessage row = new OutboxMessage();
        row.setQueue(queue);
        row.setType(message.getClass().getName());
        row.setPayload(jsonMapper.convertValue(message, new TypeReference<Map<String, Object>>() {}));
        row.setCreatedAt(clock.instant());
        row.setNextAttemptAt(clock.instant());   // due tout de suite
        UUID id = repository.save(row).getId();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {          // jamais appelé après un rollback
                relay.publishNow(List.of(id));
            }
        });
    }
}
```

**Étape 5. Activer les confirmations.** Comme au guide 19 : un accusé par message, et le retour d'un message qu'aucune file ne reçoit.

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
```

**Étape 6. Publier sur un autre thread, dans une nouvelle transaction.** `@Async` rend la main à la requête ; la ligne n'est marquée publiée qu'au commit du relais.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelay {

    public static final int BATCH_SIZE = 100;

    private final OutboxMessageRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Async                                                // demande @EnableAsync (guide 16)
    @Transactional
    public void publishNow(Collection<UUID> ids) {
        publish(repository.lockUnpublished(ids));
    }

    @Transactional
    public int publishDue() {
        return publish(repository.lockDue(clock.instant(), BATCH_SIZE));
    }

    @Transactional
    public int deletePublished() {
        return repository.deletePublishedBefore(clock.instant().minus(Duration.ofDays(7)));
    }

    private int publish(List<OutboxMessage> messages) {
        int published = 0;
        for (OutboxMessage message : messages) {
            try {
                send(message);                            // étape 7
                message.setPublishedAt(clock.instant());  // écrit au commit du relais
                published++;
            } catch (RuntimeException exception) {        // une ligne en échec n'arrête pas les autres
                scheduleRetry(message, exception);
            }
        }
        return published;
    }
}
```

Le délai entre deux tentatives double à partir de 5 s, jusqu'à un plafond :

```java
// dans OutboxRelay
private void scheduleRetry(OutboxMessage message, RuntimeException exception) {
    int attempts = message.getAttempts() + 1;
    Duration delay = Duration.ofSeconds(5).multipliedBy(1L << Math.min(attempts - 1, 20));
    Duration capped = delay.compareTo(Duration.ofMinutes(10)) > 0 ? Duration.ofMinutes(10) : delay;
    String error = String.valueOf(exception.getMessage());

    message.setAttempts(attempts);
    message.setNextAttemptAt(clock.instant().plus(capped));
    message.setLastError(error.substring(0, Math.min(error.length(), 1_000)));   // taille de la colonne
    log.warn("Outbox message {} not published (attempt {}), retrying in {}",
            message.getId(), attempts, capped, exception);
}
```

**Étape 7. Envoyer et attendre la confirmation.** Seul un `ack` sans retour compte comme publié.

```java
// dans OutboxRelay
private void send(OutboxMessage message) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(message.getId().toString());          // le même id pour un doublon
    properties.setHeader(DefaultJacksonJavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, message.getType());

    Message amqpMessage = new Message(jsonMapper.writeValueAsBytes(message.getPayload()), properties);
    CorrelationData correlation = new CorrelationData(message.getId().toString());
    rabbitTemplate.send("", message.getQueue(), amqpMessage, correlation);

    CorrelationData.Confirm confirm = awaitConfirm(correlation);   // get(5 s), comme au guide 19
    if (!confirm.ack()) {
        throw new AmqpException("Refused by the broker: " + confirm.reason());
    }
    if (correlation.getReturned() != null) {
        throw new AmqpException("No queue " + message.getQueue());
    }
}
```

**Étape 8. Rattraper avec un poller, puis purger.** Le poller tourne sur chaque instance, sans ShedLock : `SKIP LOCKED` partage les lignes (guide 21). La purge est verrouillée.

```java
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private final OutboxRelay relay;

    @Scheduled(fixedDelayString = "PT5S")
    public void publishDue() {
        int published;
        do {
            published = relay.publishDue();
        } while (published == OutboxRelay.BATCH_SIZE);    // un lot plein : il en reste peut-être
    }

    @Scheduled(cron = "0 0 * * * *")                       // chaque heure
    @SchedulerLock(name = "outbox-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deletePublished() {
        relay.deletePublished();
    }
}
```

**Étape 9. Vérifier.** Contre de vrais Postgres et RabbitMQ (guide 30).

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)      // Postgres et RabbitMQ en conteneurs (guide 30)
class OutboxTests {

    @Autowired Outbox outbox;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void messageOfACommittedTransactionIsPublished() {
        UUID orderId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status ->
                outbox.enqueue("invoice.generate", new InvoiceRequested(orderId)));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> rows(orderId, "published_at IS NOT NULL") == 1);
    }

    @Test
    void messageOfARolledBackTransactionIsNeverWritten() {
        UUID orderId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> {
            outbox.enqueue("invoice.generate", new InvoiceRequested(orderId));
            status.setRollbackOnly();
        });

        assertThat(rows(orderId, "TRUE")).isZero();
    }

    @Test
    void enqueueOutsideATransactionIsRefused() {
        InvoiceRequested message = new InvoiceRequested(UUID.randomUUID());

        assertThatThrownBy(() -> outbox.enqueue("invoice.generate", message))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
```

```java
// dans OutboxTests ; InvoiceRequested(UUID orderId) est le message du guide 19
private long rows(UUID orderId, String condition) {
    return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_messages WHERE payload ->> 'orderId' = ? AND " + condition,
            Long.class, orderId.toString());
}
```

### Référence rapide

Le délai avant la tentative suivante :

| Tentatives ratées | Délai |
|---|---|
| 1, 2, 3, 4 | 5 s, 10 s, 20 s, 40 s |
| 5, 6, 7 | 80 s, 160 s, 320 s |
| 8 et plus | 10 min, le plafond, sans limite de tentatives |

Limites de cette version : l'ordre des messages n'est pas garanti, la transaction du relais reste ouverte pendant l'attente des confirmations, et un message peut partir deux fois.

## Concepts avancés

### Sous le capot : le rappel après le commit

Deux threads, deux transactions :

```
 thread de la requête                       thread virtuel (@Async)
 --------------------                       -----------------------
 service @Transactional
   |  INSERT métier
   |  Outbox.enqueue : INSERT outbox
   |  rappel afterCommit enregistré
   v
 COMMIT
   |
   v
 afterCommit() -- relay.publishNow(ids) --> nouvelle transaction
   |              (rend la main aussitôt)   SELECT ... FOR UPDATE SKIP LOCKED
   v                                        publie, attend la confirmation
 réponse HTTP                               UPDATE published_at, COMMIT
```

- Pendant `afterCommit()`, les ressources de la transaction terminée sont encore liées au thread : une méthode `@Transactional` ordinaire appelée là rejoint cette transaction finie, et ses écritures ne sont jamais validées. La Javadoc de Spring conseille `REQUIRES_NEW` ; `@Async` change de thread.
- Une exception levée dans `afterCommit()` remonte à l'appelant, alors que le changement est déjà validé.
- Si l'instance s'arrête entre le commit et la publication, la ligne reste due : le poller la publie.

### Sous le capot : de la ligne au message

Le relais ne reconstruit jamais l'objet Java : il envoie le JSON tel quel.

| Colonne | Dans le message AMQP |
|---|---|
| `payload` | le corps, en JSON |
| `type` | l'en-tête `__TypeId__`, lu par le convertisseur des consommateurs (guide 19) |
| `id` | `message-id`, identique pour un doublon |
| `queue` | la clé de routage, sur l'exchange par défaut |

### Plusieurs instances

Chaque instance fait tourner le poller, et `publishNow` peut viser la même ligne au même moment :

```
 ligne 42, due
      |
      +--> publishNow (instance A) : FOR UPDATE --> verrou obtenu --> publie
      |
      +--> poller (instance B)     : SKIP LOCKED --> ligne sautée
      |
      +--> poller, tour suivant    : published_at rempli --> ignorée
```

- Pas de ShedLock sur le poller : une seule instance publierait pendant que le retard grossit (guide 21).
- L'ordre n'est pas garanti : `publishNow` tourne sur des threads parallèles, et une ligne en échec repart après ses voisines. Un consommateur qui en dépend compare des dates ou des versions.

### Surveiller

Deux jauges lues en base à chaque collecte (les deux compteurs sont incrémentés par le relais) :

```java
// dans OutboxMessageRepository
long countByPublishedAtIsNull();

@Query(value = "SELECT min(created_at) FROM outbox_messages WHERE published_at IS NULL", nativeQuery = true)
Optional<Instant> oldestUnpublishedCreatedAt();
```

```java
@Component
@RequiredArgsConstructor
public class OutboxMetrics implements MeterBinder {

    private final OutboxMessageRepository repository;
    private final Clock clock;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.messages.pending", repository,
                        OutboxMessageRepository::countByPublishedAtIsNull)
                .register(registry);
        Gauge.builder("outbox.messages.oldest.pending.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .register(registry);
    }

    private double oldestPendingAgeSeconds() {
        return repository.oldestUnpublishedCreatedAt()
                .map(createdAt -> Duration.between(createdAt, clock.instant()).toMillis() / 1000.0)
                .orElse(0.0);
    }
}
```

| Métrique | Sens |
|---|---|
| `outbox.messages.pending` | lignes pas encore confirmées par le broker |
| `outbox.messages.oldest.pending.age` | âge de la plus ancienne, en secondes : l'alerte la plus utile |
| `outbox.messages.published` | messages confirmés, par file |
| `outbox.publish.failures` | tentatives ratées, par file |

## Pièges à éviter

### Écrire dans l'outbox

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire le message en `REQUIRES_NEW` ou `REQUIRED` | il est validé même si le changement est annulé, ou hors transaction | Le déclarer `MANDATORY` |
| Appeler une méthode `@Transactional` dans `afterCommit()` | elle rejoint la transaction finie : rien n'est validé | Passer par `@Async`, ou `REQUIRES_NEW` |
| Mettre une entité JPA dans le message | le JSON suit le schéma de la base et charge les associations | Un record dédié, avec des ids |
| Renommer la classe d'un message | les lignes en attente gardent l'ancien `__TypeId__` | Garder le nom tant que des lignes l'utilisent |

### Publier

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Marquer la ligne publiée sans attendre la confirmation | un message perdu par le broker compte comme parti | Attendre l'`ack` |
| Ignorer le retour d'un message sans file | RabbitMQ le confirme : ligne marquée, message perdu | Activer `mandatory`, lire `getReturned()` |
| Réessayer à intervalle court, ou abandonner vite | rafale à chaque tour, ou messages perdus après une panne | Doubler le délai, plafonner, ne pas abandonner |
| Ranger le message d'erreur entier | il dépasse la colonne et fait échouer le commit | Le tronquer à la taille de la colonne |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Poser ShedLock sur le poller | une seule instance publie, le retard grossit | Laisser `SKIP LOCKED` partager les lignes |
| Croire que l'outbox évite les doublons | un arrêt entre confirmation et commit republie | Rendre les consommateurs idempotents |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser le poller tourner toutes les 5 s | sur une base partagée, un contexte publie les lignes d'un autre | Espacer le poller, appeler `publishDue()` |

## En résumé

**À retenir :** le message est une ligne écrite dans la transaction du changement ; un relais la publie après le commit, attend la confirmation du broker et la marque publiée, un poller rattrape les échecs, et les consommateurs tolèrent les doublons.

- [ ] Table `outbox_messages` créée par migration, avec l'index des lignes dues
- [ ] `enqueue` en `MANDATORY`, jamais en `REQUIRES_NEW`
- [ ] Publication demandée dans `afterCommit()`, exécutée par une méthode `@Async`
- [ ] Lectures en `FOR UPDATE SKIP LOCKED`, avec `published_at IS NULL`
- [ ] `published_at` rempli seulement après un `ack` sans retour
- [ ] Délai qui double, plafonné, sans abandon ; erreur tronquée
- [ ] Poller sur chaque instance, sans ShedLock ; purge verrouillée
- [ ] Messages petits : des ids, pas des entités
- [ ] Consommateurs idempotents
- [ ] Alerte sur l'âge du plus vieux message en attente

## Dans ce dépôt

Le trajet d'un message, de la transaction métier au broker :

```
  service @Transactional              transaction de la requête
       |  MailService, AvatarService, RealtimeBroadcasts...
       v
  Outbox.enqueue / broadcast          INSERT outbox_messages (MANDATORY)
       |
       v
  COMMIT
       |
       v
  OutboxRelay.publishNow              @Async, nouvelle transaction
       |
       +-- ack, pas de retour --> published_at
       |
       +-- échec --> attempts, last_error, next_attempt_at
                          |
                          v
  OutboxRelayJob.publishDue           toutes les 5 s, sur chaque instance
                          |
                          v
  OutboxRelayJob.deletePublished      chaque heure, une seule instance
```

| Fichier | Rôle |
|---|---|
| [`Outbox`][outbox] | `enqueue` vers une file, `broadcast` vers un exchange, dans la transaction de l'appelant |
| [`OutboxRelay`][relay] | publication, attente de la confirmation, contrôle du retour, réessais, compteurs |
| [`OutboxRelayJob`][job] | le poller sans verrou, la purge verrouillée |
| [`OutboxMessage`][entity] | la ligne : file ou exchange, type, contenu `jsonb`, tentatives |
| [`OutboxMessageRepository`][repository] | `lockUnpublished`, `lockDue`, la purge, les lectures des jauges |
| [`OutboxMetrics`][metrics] | les deux jauges |
| [`OutboxProperties`][properties] | les réglages `messaging.outbox` |
| [`014-outbox.yaml`][changeset], [`022-outbox-broadcasts.yaml`][changeset-broadcasts] | la table, puis la colonne `exchange` |
| [`MailConfiguration`][mail-config] | porte le `@EnableAsync` dont dépend `publishNow` |

Les réglages, sous `messaging.outbox` dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `poll-interval` | PT5S | délai entre deux tours du poller (PT1H en test) |
| `batch-size` | 100 | lignes par lot du poller |
| `max-retry-delay` | PT10M | plafond du délai entre deux tentatives |
| `confirm-timeout` | PT5S | attente maximale de la confirmation |
| `retention` | P7D | conservation des lignes publiées |
| `purge-cron` | `0 0 * * * *` | la purge, chaque heure |

Ce qui est propre au dépôt :

- Une ligne vise une file (par l'exchange par défaut) ou un exchange de diffusion (clé vide), jamais les deux. Une diffusion qu'aucune file ne reçoit est marquée publiée : aucune instance n'écoute.
- `payload` est une `Map<String, Object>` et non un `JsonNode` : Hibernate mappe les colonnes JSON avec Jackson 2, alors que l'application utilise Jackson 3. Le `JsonMapper` de Jackson 3 convertit le message, et écrit les UUID en texte.
- L'id de la ligne devient le `message-id` AMQP : les événements temps réel s'en servent comme id d'événement, le même sur toutes les instances (guide 24).
- `MailService.send` est `@Transactional` sans propagation particulière : hors transaction, il en ouvre une. `RealtimeBroadcasts` fait de même avec un `TransactionTemplate`, pour un changement de compte publié hors transaction.
- La classe d'un message doit appartenir à un paquet de confiance du convertisseur (guide 19).
- En test, `poll-interval` vaut une heure : les conteneurs réutilisés partagent une base, et un poller publierait les lignes d'un autre contexte vers son propre broker.

Les tests :

- [`OutboxTest`][outbox-test] : la ligne écrite, la publication au commit seulement.
- [`OutboxRelayTest`][relay-test] : confirmation, refus, retour, délai qui double, erreur tronquée, compteurs, diffusions.
- [`OutboxTests`][outbox-tests] : contre les vrais conteneurs ; rollback sans ligne, `SKIP LOCKED` vu d'une autre connexion, file inexistante (`NO_ROUTE`), purge.
- [`OutboxBrokerFailureTests`][broker-failure-tests] : un espion du `RabbitTemplate` simule la panne ; la ligne garde l'erreur, réessaie à 5 s puis 10 s, et le mail part une seule fois au retour du broker. L'espion lui donne un contexte à part.
- [`RealtimeBroadcastTests`][broadcast-tests] : une diffusion sans file est marquée publiée, sans réessai.

Pour ajouter un message : un record dans un paquet de confiance, un appel à `outbox.enqueue(file, message)` dans la transaction du changement, et un listener idempotent sur la file (guide 19).

## Voir aussi

- [14. Transactions](14-transactions.md) : propagation, et travail après le commit.
- [15. Concurrence et verrous](15-concurrence-verrous.md) : `FOR UPDATE SKIP LOCKED`.
- [19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) : files, confirmations, consommateurs idempotents.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : pourquoi le poller tourne sans verrou.
- [23. Envoi de mails](23-envoi-mails.md) : le premier utilisateur de l'outbox.
- [Cas pratique : une notification de bout en bout](cas-pratiques/notification-bout-en-bout.md).
- Documentation officielle : [le patron Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html), [l'outbox avec Debezium](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html), [les événements de Spring Modulith](https://docs.spring.io/spring-modulith/reference/events.html).

[Sommaire](README.md) | [< 19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) | [21. Jobs planifiés et ShedLock >](21-jobs-planifies-shedlock.md)

[outbox]: ../../src/main/java/io/julienmetral/tasks/messaging/services/Outbox.java
[relay]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelay.java
[job]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelayJob.java
[entity]: ../../src/main/java/io/julienmetral/tasks/messaging/entities/OutboxMessage.java
[repository]: ../../src/main/java/io/julienmetral/tasks/messaging/repositories/OutboxMessageRepository.java
[metrics]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxMetrics.java
[properties]: ../../src/main/java/io/julienmetral/tasks/config/OutboxProperties.java
[changeset]: ../../src/main/resources/db/changelog/changes/014-outbox.yaml
[changeset-broadcasts]: ../../src/main/resources/db/changelog/changes/022-outbox-broadcasts.yaml
[mail-config]: ../../src/main/java/io/julienmetral/tasks/mail/MailConfiguration.java
[outbox-test]: ../../src/test/java/io/julienmetral/tasks/messaging/services/OutboxTest.java
[relay-test]: ../../src/test/java/io/julienmetral/tasks/messaging/services/OutboxRelayTest.java
[outbox-tests]: ../../src/test/java/io/julienmetral/tasks/messaging/OutboxTests.java
[broker-failure-tests]: ../../src/test/java/io/julienmetral/tasks/messaging/OutboxBrokerFailureTests.java
[broadcast-tests]: ../../src/test/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcastTests.java
