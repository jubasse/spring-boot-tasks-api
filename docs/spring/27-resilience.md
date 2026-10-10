# 27. Résilience

[Sommaire](README.md) | [< 26. Clients HTTP et filtre SSRF](26-clients-http-ssrf.md) | [28. Uploads et drivers de stockage >](28-uploads-stockage.md)

## Contexte

- **Problème** : un service distant tombe, ralentit ou refuse. L'application doit réessayer ce qui peut l'être, sans doublon et sans s'acharner sur un hôte en panne. Un hôte lent ne doit pas bloquer les appels vers les autres.
- **Quand l'utiliser** : tout appel réseau sortant dont l'échec ne doit pas être définitif (webhooks, API de partenaires).
- **Quand s'en passer** : une lecture interne rapide (la base) ; un appel dont l'utilisateur attend le résultat tout de suite ; une opération non idempotente qu'on ne sait pas dédupliquer.
- **Dans ce dépôt** : la livraison des webhooks. Un réessai immédiat, un disjoncteur et une cloison par hôte, puis un calendrier de réessais en base sur environ 17 h.
- **Prérequis** : guides 15 (verrous, `SKIP LOCKED`), 21 (jobs planifiés) et 26 (client HTTP). Versions : Spring Framework 7.0 (`@Retryable`), Resilience4j 2.4.

Les outils, du plus simple au plus durable :

| Outil | Ce qu'il fait | Où vit l'état |
|---|---|---|
| `@Retryable` (Spring 7) | réessaie la méthode, avec délai et gigue | sur le thread de l'appel |
| `@ConcurrencyLimit` (Spring 7) | limite les appels simultanés d'une méthode | en mémoire |
| Resilience4j | disjoncteur et cloison, par clé choisie dans le code | en mémoire de chaque instance |
| Réessais du listener RabbitMQ (guide 19) | rejoue le message, puis dead letter | sur le thread du consommateur |
| Calendrier en base | un prochain essai daté, repris par un poller | en base, partagé, survit aux arrêts |

## Concepts clés

### Vocabulaire

- **Réessai** (retry) : un nouvel essai après un échec.
- **Backoff** (délai croissant) : une attente entre deux essais qui grandit.
- **Gigue** (jitter) : une part aléatoire ajoutée à l'attente.
- **Disjoncteur** (circuit breaker) : il compte les échecs vers un service et, au-delà d'un seuil, refuse les appels pendant un temps.
- **Cloison** (bulkhead) : une limite d'appels simultanés vers un service, pour qu'il ne monopolise pas tous les threads.

### Que réessayer ?

La question est de savoir si la requête a pu arriver :

```
 l'appel échoue
      |
      v
 la connexion a-t-elle été établie ?
      |
      +-- non (refusée, délai de connexion) --> la requête n'est pas partie
      |                                         réessai immédiat sans risque
      |
      +-- oui --> réponse reçue ?
                    |
                    +-- non (délai de lecture) --> peut-être traitée
                    |                              doublon possible
                    |
                    +-- 5xx, 429 --> passager : réessayer plus tard
                    +-- 410 --> définitif : arrêter
```

On ne réessaie tout de suite que ce qui n'a pas pu arriver. Le reste attend, et le récepteur écarte les doublons grâce à un identifiant stable (`webhook-id`).

### Backoff et gigue

Sans gigue, des échecs simultanés reviennent tous au même instant :

```
 sans gigue : 500 livraisons échouent à 10:00:00

 10:00:05  ##################   500 essais en même temps
 10:05:05  ##################   500 encore

 avec plus ou moins 20 %

 10:00:04 .. 10:00:06   ####    étalés sur 2 s
 10:04:05 .. 10:06:05   ####    étalés sur 2 min
```

Un `Retry-After` plus long que l'attente prévue l'emporte, dans la limite d'un plafond.

### Le disjoncteur

Il a trois états :

```
            échecs >= seuil (sur la fenêtre)
 FERMÉ  ----------------------------------->  OUVERT
   ^                                            |
   |  l'appel de test réussit                   |  après l'attente
   |                                            v
   +---------------------------------------  DEMI-OUVERT
                                              (un appel de test)
            l'appel de test échoue --> OUVERT à nouveau
```

- Fermé : tout passe, et les résultats sont comptés sur une fenêtre.
- Ouvert : tout est refusé sans appel. L'hôte souffle, et les threads ne l'attendent plus.
- Demi-ouvert : quelques appels de test passent, et leur succès referme le disjoncteur. Avec Resilience4j, le passage d'ouvert à demi-ouvert a lieu à la première demande après l'attente.
- Un disjoncteur par hôte : un récepteur en panne n'arrête pas les autres.
- Les défauts de Resilience4j 2.4 : taux d'échec de 50 %, fenêtre et minimum de 100 appels, 60 s d'ouverture, 10 appels de test. Un hôte peu sollicité n'atteint jamais 100 appels. Il faut donc régler la fenêtre et le minimum.

### La cloison

Un hôte lent ne peut pas occuper tous les consommateurs :

```
 4 consommateurs de livraisons
   |  |  |  |
   v  v  v  v
 hôte lent : au plus 2 appels en cours --> les 2 autres consommateurs
                                           servent les autres hôtes
```

Avec une attente nulle, une cloison pleine refuse tout de suite.

### En mémoire ou en base

- Un réessai en mémoire dort sur un thread et disparaît avec l'instance. Il convient pour quelques centaines de ms.
- Un calendrier en base survit aux arrêts et se partage entre instances.
- Disjoncteur et cloison restent en mémoire. Les perdre au redémarrage ne coûte qu'un peu de prudence.

## Implémentation avec Spring

Ce qu'on va créer, dans la suite du client de rappel du guide 26 :

```
pom.xml                                   Resilience4j (étape 3)
src/main/java/.../
  callback/
    CallbackClient.java                   le client du guide 26 (étape 2)
    CallbackClientConfiguration.java      @EnableResilientMethods (étape 1)
    HostGuards.java                       disjoncteur, cloison par hôte (4)
    CallbackSender.java                   un essai, le calendrier (5 et 6)
    CallbackRetryJob.java                 reprend les essais dus (étape 7)
  repositories/
    CallbackDeliveryQueries.java          réserve les essais dus (étape 7)
src/test/java/.../
  HostGuardsTest.java                     la vérification (étape 8)
```

### Un réessai immédiat, avec Spring seul

**Étape 1. Activer les méthodes résilientes.** Une annotation sur une configuration active `@Retryable` et `@ConcurrencyLimit` dans toute l'application.

```java
@Configuration
@EnableResilientMethods
@ImportHttpServices(group = "callbacks", types = CallbackClient.class)
class CallbackClientConfiguration {
}
```

`EnableResilientMethods` vient de `org.springframework.resilience.annotation`.

**Étape 2. Réessayer seulement ce qui n'a pas pu arriver.** Sans connexion, la requête n'est jamais partie, donc la renvoyer ne crée pas de doublon. Tout le reste est laissé au calendrier.

```java
public interface CallbackClient {

    @Retryable(includes = {ConnectException.class, ConnectTimeoutException.class},
            maxRetries = 1, delay = 200, jitter = 100)
    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> send(URI url, @RequestHeader Map<String, String> headers,
            @RequestBody byte[] body);
}
```

- `ConnectException` vient de `java.net`, et `ConnectTimeoutException` de `org.apache.hc.client5.http`.
- `includes` se compare à l'exception levée et à ses causes. Le réessai s'applique donc même quand `RestClient` enveloppe la `ConnectException` dans une `ResourceAccessException`.
- Avec `delay = 200` et `jitter = 100`, l'attente est entre 200 et 300 ms. La gigue ne descend jamais sous le délai de base.

### Un disjoncteur et une cloison par hôte

**Étape 3. Ajouter Resilience4j.** Les annotations de Spring ne savent pas choisir un disjoncteur selon l'hôte de l'URL. On utilise donc les modules de base de Resilience4j depuis le code.

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.4.0</version>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-bulkhead</artifactId>
    <version>2.4.0</version>
</dependency>
```

Boot ne gère pas ces versions. Le dépôt n'utilise pas le starter de Resilience4j, dont la prise en charge de Boot 4.1 n'est pas confirmée.

**Étape 4. Créer un disjoncteur et une cloison par hôte.** Le registre crée l'instance d'une clé à la première demande. On demande un passage avant l'appel et on le rend après, avec le résultat.

```java
@Component
public class HostGuards {

    public record Permit(CircuitBreaker breaker, Bulkhead bulkhead, long startNanos) {
    }

    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                    .slidingWindow(60, 5, SlidingWindowType.TIME_BASED)  // 60 s, dès 5 appels
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .build());

    private final BulkheadRegistry bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(2)
            .maxWaitDuration(Duration.ZERO)                         // refuser plutôt qu'attendre
            .build());

    public Optional<Permit> tryAcquire(String host) {
        String key = host.toLowerCase(Locale.ROOT);                 // HOOKS.example = hooks.example
        CircuitBreaker breaker = breakers.circuitBreaker(key);
        Bulkhead bulkhead = bulkheads.bulkhead(key);
        if (!breaker.tryAcquirePermission()) {
            return Optional.empty();
        }
        if (!bulkhead.tryAcquirePermission()) {
            breaker.releasePermission();
            return Optional.empty();
        }
        return Optional.of(new Permit(breaker, bulkhead, System.nanoTime()));
    }

    public void release(Permit permit, Integer statusCode) {      // null : pas de réponse
        long elapsed = System.nanoTime() - permit.startNanos();
        try {
            if (statusCode == null || statusCode >= 500) {
                permit.breaker().onError(elapsed, TimeUnit.NANOSECONDS,
                        new IllegalStateException("host failure"));
            } else {
                permit.breaker().onSuccess(elapsed, TimeUnit.NANOSECONDS);   // l'hôte répond
            }
        } finally {
            permit.bulkhead().onComplete();
        }
    }
}
```

**Étape 5. Demander un passage avant chaque appel.** Un refus reporte la livraison sans consommer d'essai. Le résultat est toujours rendu, même sur exception.

```java
// dans CallbackSender (client : CallbackClient ; hostGuards : HostGuards)
enum Outcome { DELIVERED, FAILED_ATTEMPT, POSTPONED }

Outcome attempt(URI url, Map<String, String> headers, byte[] body) {
    Optional<HostGuards.Permit> permit = hostGuards.tryAcquire(url.getHost());
    if (permit.isEmpty()) {
        return Outcome.POSTPONED;                 // disjoncteur ouvert ou cloison pleine
    }
    Integer status = null;
    try {
        status = client.send(url, headers, body).getStatusCode().value();
        return status / 100 == 2 ? Outcome.DELIVERED : Outcome.FAILED_ATTEMPT;
    } catch (ResourceAccessException noAnswer) {
        return Outcome.FAILED_ATTEMPT;            // délai dépassé ou connexion impossible
    } finally {
        hostGuards.release(permit.get(), status);
    }
}
```

Une livraison reportée reçoit pour prochain essai l'heure actuelle plus la durée d'ouverture plus 1 s, sans compter d'essai.

### Des réessais durables, en base

**Étape 6. Planifier l'essai suivant.** Chaque échec date le prochain essai, avec une gigue de 20 %. Le `Retry-After` du serveur l'emporte s'il est plus long. Après le dernier délai, la livraison est abandonnée.

```java
static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(5), Duration.ofMinutes(5),
        Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(5), Duration.ofHours(10));

static Duration nextDelay(int attempts, Duration retryAfter) {
    Duration delay = RETRY_DELAYS.get(attempts - 1);
    double factor = ThreadLocalRandom.current().nextDouble(0.8, 1.2);      // plus ou moins 20 %
    Duration jittered = Duration.ofMillis((long) (delay.toMillis() * factor));
    return retryAfter != null && retryAfter.compareTo(jittered) > 0 ? retryAfter : jittered;
}

// CallbackDelivery : une entité JPA du lecteur (guide 12) ; DeliveryStatus : son enum
void recordFailure(CallbackDelivery delivery, Duration retryAfter, Instant now) {
    delivery.setAttempts(delivery.getAttempts() + 1);
    if (delivery.getAttempts() > RETRY_DELAYS.size()) {
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setNextAttemptAt(null);
    } else {
        delivery.setNextAttemptAt(now.plus(nextDelay(delivery.getAttempts(), retryAfter)));
    }
}
```

**Étape 7. Reprendre les essais dus.** Un poller sur chaque instance réserve un lot avec `SKIP LOCKED` et repousse la date des essais d'un bail. Un essai interrompu par un arrêt redevient dû à la fin du bail (guides 15 et 21).

```sql
UPDATE callback_deliveries
SET    next_attempt_at = :leaseEnd                 -- maintenant + 5 min : le bail de l'essai
WHERE  id IN (
    SELECT id FROM callback_deliveries
    WHERE  status = 'PENDING' AND next_attempt_at <= :now
    ORDER  BY next_attempt_at
    LIMIT  :limit
    FOR UPDATE SKIP LOCKED
)
RETURNING id;
```

```java
@Component
@RequiredArgsConstructor
class CallbackRetryJob {

    private final CallbackSender sender;

    @Scheduled(fixedDelayString = "${callbacks.retry-poll-interval}")   // sans ShedLock (guide 21)
    void sendDueRetries() {
        sender.enqueueDue();   // réserve le lot (requête ci-dessus) et met chaque id en file (guide 20)
    }
}
```

**Étape 8. Vérifier.** Le disjoncteur d'un hôte s'ouvre après cinq échecs sans toucher aux autres, et la gigue reste dans ses bornes.

```java
class HostGuardsTest {

    private final HostGuards guards = new HostGuards();

    @Test
    void fiveFailuresOpenTheBreakerOfThatHostOnly() {
        for (int i = 0; i < 5; i++) {
            guards.release(guards.tryAcquire("down.example").orElseThrow(), 503);
        }

        assertThat(guards.tryAcquire("down.example")).isEmpty();
        assertThat(guards.tryAcquire("DOWN.example")).isEmpty();        // même clé
        assertThat(guards.tryAcquire("up.example")).isPresent();
    }

    @Test
    void retryDelayStaysWithinTwentyPercentUnlessRetryAfterIsLonger() {
        assertThat(CallbackSender.nextDelay(1, null))
                .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
        assertThat(CallbackSender.nextDelay(1, Duration.ofHours(1))).isEqualTo(Duration.ofHours(1));
    }
}
```

### Référence rapide

Les attributs de `@Retryable` :

| Attribut | Défaut | Rôle |
|---|---|---|
| `includes` / `value` | vide : toute exception | exceptions à réessayer, causes imbriquées comprises |
| `excludes` | vide | exceptions à ne pas réessayer |
| `maxRetries` | 3 | réessais après le premier essai |
| `delay` | 1000 ms | attente de base |
| `jitter` | 0 | gigue |
| `multiplier` | 1.0 | facteur du délai croissant |

`@ConcurrencyLimit(n)` limite à n les appels simultanés d'une méthode.

Limites de cette version : l'état du disjoncteur est propre à chaque instance, et chacune découvre seule la panne d'un hôte.

## Concepts avancés

### Sous le capot : `@Retryable`

`@EnableResilientMethods` place un proxy Spring devant les beans annotés. Un appel depuis l'intérieur de la classe le contourne. Le réessai dort sur le thread de l'appelant, ce qui est acceptable pour 200 ms.

### Ce qui compte comme un échec de l'hôte

Les règles du dépôt pour le disjoncteur :

| Résultat | Pour le disjoncteur |
|---|---|
| 5xx ou pas de réponse | échec |
| tout autre statut, 429 compris | succès : l'hôte répond |
| appel refusé par le filtre d'adresses (guide 26) | ni succès ni échec, le passage est rendu |

Un 429 limite un client (un webhook Slack), pas l'hôte entier.

### Plusieurs instances

- Le poller tourne sur chaque instance sans ShedLock. `SKIP LOCKED` répartit les lignes, et le bail rattrape un essai interrompu (guides 15 et 21).
- La livraison est « au moins une fois » : l'identifiant stable permet au récepteur d'écarter un doublon.

### Abandonner un récepteur mort

- Un 410 Gone désactive l'endpoint (raison `GONE`) et termine la livraison.
- Des échecs pendant `webhooks.disable-after` (3 jours depuis `failing_since`, que tout succès efface) désactivent l'endpoint (raison `FAILING`). Son propriétaire est prévenu par mail.

### Surveiller

| Métrique | Sens |
|---|---|
| `webhook.deliveries` (tag `outcome` : `delivered`, `retried`, `failed`, `postponed`) | issue de chaque essai |
| `webhook.circuit.breakers.open` | hôtes dont le disjoncteur refuse les appels |

Les disjoncteurs n'ont qu'une série agrégée, pour ne pas grandir avec les abonnés.

## Pièges à éviter

### Avec les réessais

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Réessayer après un délai de lecture dépassé | le récepteur a peut-être traité la requête : doublon | Ne réessayer vite que les échecs de connexion |
| Garder `@Retryable` sans `includes` | toute exception est réessayée, bug compris | Lister les exceptions passagères |
| Réessayer sans gigue | les échecs simultanés reviennent ensemble | Ajouter une part aléatoire |
| Réessayer longtemps en mémoire | le thread dort, et un arrêt efface l'essai | Dater le prochain essai en base |
| Garder les réessais du listener pour un appel lent | ils dorment sur le thread consommateur | Prendre une fabrique sans réessais (guide 19) |
| Empiler les réessais à chaque couche | Apache, `@Retryable`, listener et calendrier se multiplient | Donner une couche à chaque type d'échec |
| Ignorer `Retry-After` | on rappelle un serveur qui a demandé d'attendre | L'honorer, avec un plafond |

### Avec le disjoncteur et la cloison

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder un seul disjoncteur pour tous les hôtes | un récepteur en panne bloque tous les autres | Créer un disjoncteur par hôte |
| Garder les défauts de Resilience4j | il faut 100 appels pour ouvrir : un hôte calme n'ouvre jamais | Régler la fenêtre et le minimum |
| Consommer un essai quand le disjoncteur refuse | les livraisons meurent pendant la panne | Reporter sans compter l'essai |
| Laisser la cloison attendre une place | les threads attendent l'hôte lent | Mettre `maxWaitDuration` à zéro, et reporter |
| Garder la casse de l'hôte dans la clé | `HOOKS.example` a son propre disjoncteur | Mettre la clé en minuscules |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lancer un poller sans `SKIP LOCKED` ni bail | deux instances envoient le même essai, ou un essai se perd | Réserver avec `SKIP LOCKED` et un bail |

## En résumé

**À retenir :** réessayer tout de suite seulement ce qui n'a pas pu arriver, dater le reste en base avec un délai croissant et une gigue, et protéger chaque hôte par un disjoncteur et une cloison qui reportent sans consommer d'essai.

- [ ] `@EnableResilientMethods`, `@Retryable` limité aux échecs de connexion, un réessai court
- [ ] Relances automatiques du client HTTP coupées (guide 26)
- [ ] Un calendrier de délais croissants, avec gigue et `Retry-After` plafonné
- [ ] Prochain essai, nombre d'essais et statut en base
- [ ] Poller sur chaque instance, `SKIP LOCKED` et bail plus long que les délais HTTP
- [ ] Un disjoncteur et une cloison par hôte, clé en minuscules, réglages explicites
- [ ] Ce qui compte comme échec défini : 5xx et absence de réponse
- [ ] Un refus du disjoncteur reporte sans consommer d'essai
- [ ] 410 et échecs prolongés désactivent l'endpoint, avec un mail au propriétaire
- [ ] Un identifiant stable par livraison, pour les doublons côté récepteur

## Dans ce dépôt

Chaque couche traite un type d'échec :

```
  WebhookDeliveryService.deliver
       |
       v
  WebhookResilience.tryAcquire(hôte)     disjoncteur + cloison (mémoire)
       |
       +-- refus --> postpone : next_attempt_at = maintenant + 1 min + 1 s
       |              (HostUnavailable, essai non consommé)
       v
  WebhookClient.send                     @Retryable : 1 réessai après 200 à
       |                                 300 ms, connexion impossible seulement
       v
  record                                 2xx --> DELIVERED
       |                                 410 --> endpoint GONE, FAILED
       |                                 autre --> prochain essai du calendrier
       v
  WebhookRetryJob (30 s, chaque instance) --> claimDue (SKIP LOCKED, bail 5 min)
       |
       v
  outbox "webhook.deliver" --> nouvel essai
```

La [livraison de bout en bout](cas-pratiques/livraison-webhook.md) donne le calendrier essai par essai.

| Fichier | Rôle |
|---|---|
| [`WebhookConfiguration`][webhook-config] | `@EnableResilientMethods`, le bean `WebhookResilience` |
| [`WebhookClient`][client] | `@Retryable(includes = {ConnectException.class, ConnectTimeoutException.class}, maxRetries = 1, delay = 200, jitter = 100)` |
| [`WebhookResilience`][resilience] | registres Resilience4j, `tryAcquire`, `release`, la jauge agrégée |
| [`WebhookDeliveryService`][delivery-service] | `RETRY_DELAYS`, `nextDelay`, `postpone`, `record`, `trackFailure`, le `Retry-After` |
| [`WebhookDeliveryQueries`][queries] | `claimDue`, la réservation avec bail |
| [`WebhookRetryJob`][retry-job] | le poller : `fixedDelay`, sans verrou, désactivable (`webhooks.retry-enabled`) |
| [`WebhookQueues`][queues] | la fabrique sans réessais de listener, 4 consommateurs, un message à la fois |
| [`WebhookProperties`][properties] | tous les réglages `webhooks.*` |

Les réglages, sous `webhooks` dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `circuit-breaker.failure-rate-threshold` | 50 | pourcentage d'échecs qui ouvre le disjoncteur |
| `circuit-breaker.minimum-calls` | 5 | appels dans la fenêtre avant de compter |
| `circuit-breaker.window` | 60s | fenêtre de comptage (par durée) |
| `circuit-breaker.open-duration` | 1m | durée d'ouverture, puis un appel de test |
| `concurrent-calls-per-host` | 2 | cloison : appels simultanés vers un hôte, par instance |
| `concurrent-deliveries` | 4 | consommateurs de `webhook.deliver` par instance |
| `delivery-lease` | 5m | bail d'un essai, au-dessus des délais HTTP |
| `retry-poll-interval` | PT30S | fréquence du poller |
| `retry-batch-size` | 100 | livraisons réservées par tour |
| `disable-after` | 3d | échecs continus avant la désactivation |

Ce qui est propre au dépôt :

- Le calendrier est 5 s, 5 min, 30 min, 2 h, 5 h, 10 h, à 20 % près. Après le septième essai raté, la livraison est `FAILED`.
- Un report (`postpone`) pose `next_attempt_at` à maintenant plus `open-duration` plus 1 s, avec `last_error = HostUnavailable` et le compteur `postponed`, sans incrémenter `attempts`.
- Ne se réessaient pas : un 410 (endpoint `GONE`), `DestinationNotAllowed`, et pour Slack les 403 et 404 (`GONE`) et le 400.
- Les tests appellent `WebhookResilience.forgetAllHosts()`, car tous leurs récepteurs partagent `127.0.0.1`.
- La fabrique de listeners de `webhook.deliver` n'a pas de réessais. Le réessai global dormirait sur le thread consommateur, et un récepteur qui expire le retiendrait pendant des minutes.

Les tests :

- [`WebhookResilienceTest`][resilience-test] : 23 tests du disjoncteur, de la cloison et de la jauge.
- [`WebhookDeliveryTests`][delivery-tests] : calendrier complet, `Retry-After`, réessai rapide d'une connexion refusée, report sans essai consommé.

Pour protéger un autre appel sortant, on combine `@Retryable` limité aux échecs de connexion, un `tryAcquire` et un `release` par hôte si plusieurs destinations partagent les consommateurs, et un calendrier en base si l'échec doit être rattrapé.

## Voir aussi

- [26. Clients HTTP et filtre SSRF](26-clients-http-ssrf.md) : le client, ses délais, et pourquoi Apache ne relance plus.
- [15. Concurrence et verrous](15-concurrence-verrous.md) : `SKIP LOCKED` et la réservation avec bail.
- [19. RabbitMQ avec Spring AMQP](19-rabbitmq-spring-amqp.md) : la fabrique de listeners sans réessais.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : le poller qui tourne sans verrou.
- [Cas pratique : la livraison d'un webhook](cas-pratiques/livraison-webhook.md) : le calendrier essai par essai.
- [README du dépôt, « Answer, and what happens when you cannot »](../../README.md#answer-and-what-happens-when-you-cannot) : la promesse faite aux récepteurs.
- Documentation officielle : [résilience dans Spring Framework](https://docs.spring.io/spring-framework/reference/core/resilience.html), [Resilience4j](https://resilience4j.readme.io/docs).

[Sommaire](README.md) | [< 26. Clients HTTP et filtre SSRF](26-clients-http-ssrf.md) | [28. Uploads et drivers de stockage >](28-uploads-stockage.md)

[webhook-config]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookConfiguration.java
[client]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookClient.java
[resilience]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookResilience.java
[delivery-service]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[queries]: ../../src/main/java/io/julienmetral/tasks/notification/repositories/WebhookDeliveryQueries.java
[retry-job]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookRetryJob.java
[queues]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookQueues.java
[properties]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookProperties.java
[resilience-test]: ../../src/test/java/io/julienmetral/tasks/notification/webhook/WebhookResilienceTest.java
[delivery-tests]: ../../src/test/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryTests.java
