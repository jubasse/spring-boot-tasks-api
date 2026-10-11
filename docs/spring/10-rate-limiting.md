# 10. Rate limiting

[Sommaire](README.md) | [< 09. Mots de passe et jetons opaques](09-mots-de-passe-jetons.md) | [11. Spring Data JPA >](11-spring-data-jpa.md)

## Contexte

- **Problème** : empêcher un client d'essayer des milliers de mots de passe, de créer des comptes en boucle ou de faire envoyer des e-mails sans fin, sur des endpoints que tout le monde peut appeler.
- **Quand l'utiliser** : les endpoints publics (connexion, inscription, réinitialisation de mot de passe), et tout endpoint dont l'appel coûte hors de l'API (un e-mail, un appel HTTP sortant).
- **Quand s'en passer** : des endpoints internes authentifiés, où les quotas sont une question de produit, ou une passerelle devant l'API qui limite déjà par adresse.
- **Dans ce dépôt** : `RateLimiter` limite la connexion (par adresse et par e-mail), l'inscription (par adresse), la demande de réinitialisation (par adresse et par e-mail) et sa confirmation (par adresse), le renvoi de vérification (par utilisateur) et les événements de test des webhooks (par utilisateur). Les fenêtres fixes sont comptées dans une table PostgreSQL, sans Redis. Au-delà d'une limite, la réponse est un 429 avec `Retry-After`.
- **Prérequis** : guides 13 (SQL avec JdbcTemplate) et 14 (transactions). Versions : Spring Boot 4.1, PostgreSQL 18.

Les façons de limiter les appels :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Compteur en base (ce guide) | un `INSERT ... ON CONFLICT` atomique par requête | une base est déjà là, plusieurs instances, trafic modéré |
| Redis | `INCR` et une expiration par clé | très fort trafic, Redis déjà présent |
| Bucket4j | seau à jetons, en mémoire ou dans un stockage partagé | des limites fines (rafales, débit régulier) |
| Reverse proxy ou passerelle | limite par adresse avant l'application | protection brute contre le volume ; ne connaît ni l'e-mail ni le compte |

## Concepts clés

### Vocabulaire

- **Rate limiting** : refuser les requêtes d'un appelant au-delà d'un nombre par période.
- **Fenêtre fixe** (fixed window) : une période au début fixe (chaque heure pile). Le compte repart à zéro à la suivante.
- **Clé de comptage** : ce que le compte concerne, soit une adresse, un e-mail ou un utilisateur, pour un endpoint (`login:ip:<hash>`).
- **429 Too Many Requests** : le statut HTTP d'une requête refusée (RFC 6585). L'en-tête `Retry-After` donne le nombre de secondes à attendre.
- **Upsert** : une seule instruction SQL qui insère une ligne, ou la modifie si elle existe.

### Les algorithmes

La fenêtre fixe compte par période, sans mémoire de la précédente. C'est le plus simple, et celui de ce guide. La fenêtre glissante compte les requêtes des N dernières secondes. Le seau à jetons rend des jetons à débit constant. Les deux demandent plus d'état partagé.

L'effet de bord d'une fenêtre fixe :

```
  limite : 10 par heure

  10:00                     11:00                     12:00
    |-------- fenêtre 1 -------|-------- fenêtre 2 -------|
                       10 requêtes   10 requêtes
                       à 10:59       à 11:00
                            \_________/
                     20 requêtes en une minute
```

Cet effet est acceptable ici.

### Le compteur atomique

Lire puis écrire laisse passer trop de requêtes, alors qu'une seule instruction les compte bien :

```
  lire puis écrire (deux requêtes)        une seule requête
  A : SELECT count --> 9                  A : INSERT ... ON CONFLICT
  B : SELECT count --> 9                      DO UPDATE ... RETURNING --> 10
  A : UPDATE 10, accepte                  B : même requête, attend la ligne,
  B : UPDATE 10, accepte                      puis RETURNING --> 11
       --> 11 requêtes passent                  --> B est refusée
```

- Postgres sérialise les deux upserts sur la ligne. Chaque requête reçoit son propre compte, sur une instance comme sur plusieurs, sans Redis.
- Le compte inclut la requête courante, et `count > requests` refuse.
- L'application calcule le début de fenêtre, `floor(now / window) * window` depuis l'époque. Toutes les instances obtiennent la même clé, sans horloge de la base.

### Ce qui compte, et ce qui ne compte pas

Voici le trajet d'une connexion :

```
  POST /auth/login
        |
        v
  Bean Validation ---------- corps invalide --> 400 (rien n'est compté)
        |
        v
  RateLimiter.login         transaction à part (REQUIRES_NEW)
        |  +1 adresse, +1 email
        +-- au-delà d'une limite --> exception : transaction annulée,
        |                            les +1 aussi --> 429
        v
  commit des compteurs
        |
        v
  AuthService.login --------- mauvais mot de passe --> 401 (le +1 reste)
```

- `REQUIRES_NEW` valide le compte avant le traitement de la requête. Une connexion ratée compte donc quand même, bien que sa propre transaction soit annulée.
- Une requête refusée ne consomme rien : l'exception annule les incréments de cette transaction à part.
- Le limiteur est appelé dans le contrôleur, après la validation des arguments. Un corps invalide n'est jamais compté.
- La limite par e-mail compte tous les e-mails, qu'un compte les utilise ou non, pour qu'un 429 ne révèle pas quels e-mails existent.

### L'adresse du client

L'adresse vue par l'application derrière un reverse proxy dépend d'un réglage :

```
  client 203.0.113.7
        |
        v
  reverse proxy 10.0.0.5     ajoute X-Forwarded-For: 203.0.113.7
        |
        v
  Tomcat : getRemoteAddr()
        |
        +-- forward-headers-strategy none   --> 10.0.0.5 : tous les clients
        |                                       partagent un compteur
        +-- native, derrière ce proxy       --> 203.0.113.7
        |
        +-- framework, sans proxy fiable    --> ce que le client écrit
                                                dans l'en-tête : contournement
```

- `HttpServletRequest.getRemoteAddr()` donne le pair TCP, donc le proxy lui-même derrière un reverse proxy.
- `server.forward-headers-strategy` vaut `native` (le support du conteneur : la `RemoteIpValve` de Tomcat), `framework` (le `ForwardedHeaderFilter` de Spring) ou `none` (en-têtes ignorés).
- `native` ne lit l'en-tête que si le pair TCP est un proxy de confiance (`server.tomcat.remoteip.internal-proxies`, les plages privées par défaut). `framework` le lit pour toute requête.
- Si le réglage n'est pas défini, Spring Boot choisit `native` quand il détecte une plateforme cloud (Kubernetes...). L'écrire à `none` coupe cette détection.
- Un client IPv6 détient en général tout un /64 : on compte le /64, pas chaque adresse.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/resources/
  db/changelog/...                      la table des compteurs (étape 1)
  application.yaml                      les limites (étape 3)
src/main/java/.../ratelimit/
  RateLimitCounters.java                l'incrément atomique (étapes 2 et 8)
  RateLimitProperties.java              les limites typées (étape 3)
  RateLimiter.java                      le limiteur (étape 4)
  RateLimitExceededException.java       le refus (étape 4)
  RateLimitKeys.java                    des clés sans donnée perso (étape 7)
  RateLimitPurgeJob.java                la purge (étape 8)
src/main/java/.../quote/
  QuoteRequestController.java           l'appel (étape 5)
src/main/java/.../shared/
  ApiExceptionHandler.java              le 429 (étape 6)
src/test/java/.../
  RateLimitTests.java                   la vérification (étape 9)
```

L'exemple est un formulaire public `POST /quote-requests` (une demande de devis), limité par adresse et par e-mail.

### Un compteur partagé en base

**Étape 1. Créer la table des compteurs.** Une ligne par clé et par fenêtre. La clé primaire est ce sur quoi l'upsert entre en conflit (migration, guide 18).

```sql
CREATE TABLE rate_limit_counters (
    bucket_key   VARCHAR(200)             NOT NULL,   -- "quote:ip:<sha-256>"
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    count        INTEGER                  NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);
```

**Étape 2. Écrire l'incrément atomique.** Une instruction insère la première requête ou en ajoute une, et renvoie le nouveau compte.

```java
@Repository
@RequiredArgsConstructor
public class RateLimitCounters {

    private final NamedParameterJdbcTemplate jdbc;

    public int increment(String key, Instant windowStart) {
        Integer count = jdbc.queryForObject("""
                        INSERT INTO rate_limit_counters (bucket_key, window_start, count)
                        VALUES (:key, :windowStart, 1)
                        ON CONFLICT (bucket_key, window_start)
                        DO UPDATE SET count = rate_limit_counters.count + 1
                        RETURNING count
                        """,
                new MapSqlParameterSource()
                        .addValue("key", key)
                        .addValue("windowStart", Timestamp.from(windowStart)),
                Integer.class);
        return count == null ? 0 : count;
    }
}
```

**Étape 3. Écrire les limites en configuration.** Un record typé et validé (guide 01). La fenêtre est bornée pour ne jamais valoir zéro ni survivre à la purge.

```java
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Valid @NotNull Limit quotePerIp,
        @Valid @NotNull Limit quotePerEmail
) {
    public record Limit(
            @Positive int requests,
            @NotNull @DurationMin(seconds = 1) @DurationMax(days = 1) Duration window  // purge après un jour
    ) {
    }
}
```

```yaml
rate-limit:
  enabled: ${RATE_LIMIT_ENABLED:true}
  quote-per-ip: {requests: 20, window: PT1H}
  quote-per-email: {requests: 3, window: PT1H}
```

Le record s'enregistre avec `@EnableConfigurationProperties(RateLimitProperties.class)`.

**Étape 4. Écrire le limiteur.** Sa transaction à part valide le compte avant le reste de la requête. L'exception porte le temps d'attente.

```java
@Service
@RequiredArgsConstructor
public class RateLimiter {

    private final RateLimitCounters counters;
    private final RateLimitProperties properties;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)    // compté même si la requête échoue ensuite
    public void quoteRequest(String clientAddress, String email) {
        consume(RateLimitKeys.address("quote", clientAddress), properties.quotePerIp());
        consume(RateLimitKeys.email("quote", email), properties.quotePerEmail());
    }

    private void consume(String key, RateLimitProperties.Limit limit) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        long windowMillis = limit.window().toMillis();
        Instant windowStart = Instant.ofEpochMilli(
                Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis);

        if (counters.increment(key, windowStart) > limit.requests()) {
            throw new RateLimitExceededException(
                    Duration.between(now, windowStart.plus(limit.window())));
        }
    }
}
```

```java
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Too many requests");
        this.retryAfter = retryAfter;
    }

    public long retryAfterSeconds() {          // arrondi au-dessus : attendre moins serait refusé à nouveau
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
```

**Étape 5. Appeler le limiteur dans le contrôleur.** Après `@Valid`, pour ne jamais compter un corps invalide, et avant le service.

```java
@RestController
@RequiredArgsConstructor
class QuoteRequestController {

    private final RateLimiter rateLimiter;
    private final QuoteService quoteService;            // le service métier

    record QuoteRequest(@NotBlank @Email String email, @NotBlank String message) {
    }

    @PostMapping("/quote-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestQuote(@Valid @RequestBody QuoteRequest request, HttpServletRequest http) {
        rateLimiter.quoteRequest(http.getRemoteAddr(), request.email());
        quoteService.submit(request.email(), request.message());
    }
}
```

**Étape 6. Répondre 429 avec `Retry-After`.** Le client sait quand réessayer (gestionnaire global du guide 05).

```java
// dans le @RestControllerAdvice du guide 05
@ExceptionHandler(RateLimitExceededException.class)
ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
}
```

### Des clés sûres, et l'entretien

**Étape 7. Construire des clés sans donnée personnelle.** Un SHA-256 garde une longueur fixe et aucune adresse ni e-mail en clair. Un client IPv6 compte pour son /64. `InetAddress.ofLiteral` existe depuis Java 22 et ne fait pas de requête DNS.

```java
public final class RateLimitKeys {

    private RateLimitKeys() {
    }

    public static String address(String endpoint, String clientAddress) {
        return endpoint + ":ip:" + sha256(client(clientAddress));
    }

    public static String email(String endpoint, String email) {
        return endpoint + ":email:" + sha256(email.trim().toLowerCase(Locale.ROOT));
    }

    private static String client(String clientAddress) {
        try {
            InetAddress address = InetAddress.ofLiteral(clientAddress);
            if (address instanceof Inet6Address) {                  // les 8 premiers octets : le /64
                return HexFormat.of().formatHex(Arrays.copyOf(address.getAddress(), 8)) + "/64";
            }
            return address.getHostAddress();
        } catch (IllegalArgumentException notALiteral) {
            return clientAddress;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
```

**Étape 8. Purger les vieilles fenêtres.** Les lignes s'accumulent, une par clé et par fenêtre. Une fenêtre dure au plus un jour, donc les lignes plus anciennes ne servent plus (ShedLock, guide 21).

```java
// dans RateLimitCounters
public int deleteWindowsStartedBefore(Instant cutoff) {
    return jdbc.update("DELETE FROM rate_limit_counters WHERE window_start < :cutoff",
            new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
}
```

```java
@Component
@RequiredArgsConstructor
class RateLimitPurgeJob {

    private final RateLimitCounters counters;
    private final Clock clock;

    @Scheduled(cron = "0 20 * * * *")                      // chaque heure, à la minute 20
    @SchedulerLock(name = "rate-limit-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void deleteOldWindows() {
        counters.deleteWindowsStartedBefore(clock.instant().minus(Duration.ofDays(1)));
    }
}
```

**Étape 9. Vérifier.** Les limites sont basses et actives dans cette seule classe. Chaque test prend sa propre adresse, car les compteurs survivent d'une exécution à l'autre sur une base réutilisée.

```java
@SpringBootTest(properties = {
        "rate-limit.enabled=true",
        "rate-limit.quote-per-ip.requests=2",
        "rate-limit.quote-per-ip.window=PT1H"})
@AutoConfigureMockMvc
class RateLimitTests {

    @Autowired MockMvc mockMvc;

    @Test
    void thirdRequestFromOneAddressIsRefusedWithRetryAfter() throws Exception {
        String address = "2001:db8:%x:%x::1".formatted(              // un /64 neuf à chaque test
                ThreadLocalRandom.current().nextInt(0x10000),
                ThreadLocalRandom.current().nextInt(0x10000));

        quote(address).andExpect(status().isAccepted());
        quote(address).andExpect(status().isAccepted());
        quote(address).andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    private ResultActions quote(String address) throws Exception {
        return mockMvc.perform(post("/quote-requests")
                .with(request -> {
                    request.setRemoteAddr(address);                  // l'adresse vue par getRemoteAddr()
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s@example.com\", \"message\": \"Devis\"}"
                        .formatted(UUID.randomUUID())));
    }
}
```

### Référence rapide

Les réglages de l'adresse du client :

| Réglage | Valeur | Effet |
|---|---|---|
| `server.forward-headers-strategy` | `none` | `getRemoteAddr()` est le pair TCP ; `X-Forwarded-*` ignorés |
| | `native` | Tomcat lit `X-Forwarded-For` (venant des `internal-proxies`) |
| | `framework` | le filtre `ForwardedHeaderFilter` de Spring les applique |
| | non défini | `native` si Spring Boot détecte une plateforme cloud, sinon ignorés |

Les statuts et en-têtes :

| Élément | Sens |
|---|---|
| `429 Too Many Requests` | limite dépassée (RFC 6585) |
| `Retry-After: 37` | secondes à attendre avant de réessayer |

Limites de cette version : une fenêtre fixe autorise une rafale à la frontière. Chaque requête comptée coûte aussi une écriture en base.

## Concepts avancés

### Sous le capot : la fenêtre alignée sur l'époque

- `floorDiv(now, window) * window` aligne les fenêtres sur 1970-01-01T00:00Z (UTC).
- `Retry-After` est le temps restant dans la fenêtre, arrondi au-dessus, et vaut au moins 1.
- Une fenêtre de zéro diviserait par zéro (`floorDiv` par 0). Une fenêtre plus longue que le jour de la purge serait supprimée alors qu'elle compte encore. D'où `@DurationMin(seconds = 1)` et `@DurationMax(days = 1)`.

### Plusieurs instances

- La table est l'état partagé, donc les limites tiennent entre instances sans service de plus. Sous un très fort trafic, elle devient un point chaud : on passe alors à Redis ou à une passerelle.
- Chaque instance doit voir la même adresse de client, donc la même `forward-headers-strategy` partout.

## Pièges à éviter

### Le comptage

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lire le compteur, puis l'écrire | deux requêtes simultanées lisent la même valeur et passent | Un seul `INSERT ... ON CONFLICT ... RETURNING` |
| Compter dans la transaction de la requête | un mot de passe faux annule son propre comptage | Compter dans une transaction à part (`REQUIRES_NEW`) |
| Garder les compteurs en mémoire avec plusieurs instances | chaque instance compte à part : N fois la limite | Un stockage partagé : la base, ou Redis |
| Tronquer `Retry-After` | le client revient trop tôt et reçoit un nouveau 429 | Arrondir à la seconde supérieure |

### Les clés et l'adresse

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire l'e-mail ou l'adresse en clair dans la clé | des données personnelles en table ; un e-mail long déborde la colonne | Hacher en SHA-256 |
| Compter chaque adresse IPv6 à part | un client change d'adresse dans son /64 | Compter le /64 |
| Ne compter que les e-mails des comptes existants | un 429 révèle quels e-mails ont un compte | Compter chaque e-mail |
| Activer `framework` sans reverse proxy qui écrase l'en-tête | chaque client choisit son adresse et échappe aux limites | Garder `none` sans proxy |
| Garder `none` derrière un reverse proxy | tous les clients partagent l'adresse du proxy | `native` derrière le proxy |
| Laisser la stratégie non définie | Spring Boot la déduit de la plateforme détectée | L'écrire explicitement |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser les limites actives partout | les tests qui se connectent en boucle reçoivent 429 | Les éteindre, les rallumer dans une seule classe |

## En résumé

**À retenir :** un compteur par clé et par fenêtre fixe, incrémenté par un seul upsert Postgres dans sa propre transaction, avec des clés hachées et l'adresse réelle du client. Au-delà de la limite, la réponse est un 429 avec `Retry-After` arrondi au-dessus.

- [ ] Une table de compteurs, clé primaire `(clé, début de fenêtre)`
- [ ] Un seul `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` par requête comptée
- [ ] Le limiteur en `REQUIRES_NEW`, appelé après la validation
- [ ] Limites en configuration, fenêtre bornée entre 1 seconde et 1 jour
- [ ] Clés hachées en SHA-256, IPv6 compté par /64, e-mails comptés qu'ils aient un compte ou non
- [ ] 429 avec `Retry-After` arrondi à la seconde supérieure
- [ ] `server.forward-headers-strategy` choisi selon le reverse proxy, écrit explicitement
- [ ] Une purge planifiée des fenêtres de plus d'un jour
- [ ] Limites éteintes en test, sauf dans une classe dédiée

## Dans ce dépôt

Le trajet d'une requête limitée est celui du schéma « Ce qui compte, et ce qui ne compte pas ».

| Fichier | Rôle |
|---|---|
| [`RateLimiter`][limiter] | une méthode `REQUIRES_NEW` par endpoint limité |
| [`RateLimitQueries`][queries] | l'upsert `increment`, la purge `deleteWindowsStartedBefore` |
| [`RateLimitKeys`][keys] | clés `endpoint:ip:`, `endpoint:email:` (SHA-256), `endpoint:user:<uuid>` |
| [`RateLimitExceededException`][exception] | délai restant, arrondi au-dessus |
| [`RateLimitCleanupJob`][job] | purge horaire, verrou `rate-limit-purge`, désactivable par instance |
| [`RateLimitProperties`][properties] | les limites, bornes `@DurationMin(seconds = 1)` et `@DurationMax(days = 1)` |
| [`RateLimitCounter`][entity] | entité `@Immutable` : la table reste dans le modèle Hibernate, jamais écrite par JPA |
| [`ApiExceptionHandler`][handler] | `handleRateLimitExceeded` : 429, `Retry-After`, problème `about:blank` |
| [`RateLimited`][annotation] | documente le 429 d'une opération ; ne limite rien (guide 06) |
| [`015-rate-limit-counters.yaml`][changeset] | `bucket_key VARCHAR(400)`, `window_start TIMESTAMP(6) WITH TIME ZONE`, `count INTEGER` |

Les limites, sous `rate-limit` dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `enabled` | `${RATE_LIMIT_ENABLED:true}` | `false` coupe tout comptage |
| `purge-enabled` | `${RATE_LIMIT_PURGE_ENABLED:true}` | `false` retire la purge de cette instance |
| `purge-cron` | `0 20 * * * *` | purge chaque heure, à la minute 20 |
| `login-per-ip` | 30 par `PT1M` | connexions par adresse |
| `login-per-email` | 10 par `PT15M` | connexions par e-mail, toutes adresses confondues |
| `sign-up-per-ip` | 20 par `PT1H` | inscriptions par adresse |
| `password-reset-per-ip` | 20 par `PT1H` | demandes et confirmations de réinitialisation, par adresse |
| `password-reset-per-email` | 3 par `PT1H` | demandes par e-mail, chacune envoie un e-mail |
| `verification-resend-per-user` | 3 par `PT1H` | renvois de l'e-mail de vérification |
| `webhook-test-per-user` | 10 par `PT1H` | événements de test, chacun un appel HTTP vers une URL choisie |
| `server.forward-headers-strategy` | `${FORWARD_HEADERS_STRATEGY:none}` | `native` derrière un reverse proxy |

Ce qui est propre au dépôt :

- Les appels sont dans `AuthController` (connexion, renvoi de vérification, demande et confirmation de réinitialisation) et `UserController.signUp` (inscription), tous avec `request.getRemoteAddr()` après `@Valid`. `WebhookEndpointService.sendTest` appelle le limiteur après avoir trouvé l'endpoint, donc un test sur un webhook inconnu ne compte pas. Un test envoyé par un admin compte pour le propriétaire. L'endpoint de rafraîchissement n'est pas limité.
- La demande et la confirmation de réinitialisation partagent la clé `password-reset:ip:...`.
- La connexion vérifie d'abord l'adresse, puis l'e-mail. Refusée sur l'adresse, elle ne compte pas l'e-mail. Refusée sur l'e-mail, l'incrément de l'adresse est annulé avec lui.
- Les clés sont hachées à cause d'un échec mesuré : un e-mail long, ou que la mise en minuscules allonge, débordait la colonne et la requête échouait en 409. Les e-mails passent en minuscules avec `Locale.ROOT`.
- `RateLimitCleanupJob` garde un jour, plus que toute fenêtre configurée. Son verrou est `rate-limit-purge`, avec `lockAtMostFor = "PT30M"` et `lockAtLeastFor = "PT1M"` (guide 21).
- Les tests éteignent les limites (`rate-limit.enabled: false` dans `src/test/resources/config/application.yaml`), sauf `RateLimitTests`. Cette classe a son propre contexte, avec des limites basses, une horloge figée (`TestClock` en 2100) et un cron de purge qui ne se déclenche jamais pendant l'exécution.

Les tests :

- [`RateLimitTests`][it] : chaque endpoint limité, le 429 avec `Retry-After`, les connexions ratées qui comptent, les requêtes refusées qui ne comptent pas, des requêtes parallèles qui acceptent exactement la limite.
- [`RateLimiterTest`][unit] (fenêtres, arrondi, ordre des contrôles), [`RateLimitKeysTest`][keys-test] (longueur fixe, /64 IPv6) et [`RateLimitQueriesTests`][queries-test] (`concurrentRequestsNeverReadTheSameCount`).
- [`RateLimitConfigurationTest`][config-test] : une fenêtre ou un nombre de requêtes à zéro fait échouer le démarrage, et `purge-enabled=false` retire `RateLimitCleanupJob`.

Pour limiter un nouvel endpoint : une `Limit` dans `RateLimitProperties` avec son `@param` et ses valeurs dans `application.yaml`, une méthode `REQUIRES_NEW` dans `RateLimiter` avec un nouveau préfixe de clé, l'appel après la validation, `@RateLimited` sur la méthode du contrôleur, et un cas dans `RateLimitTests` (avec sa limite basse dans le `@TestPropertySource` de cette classe).

## Voir aussi

- [13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) : l'upsert et les autres instructions Postgres atomiques.
- [14. Transactions](14-transactions.md) : `REQUIRES_NEW` et le rollback.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : la purge horaire et son verrou.
- [09. Mots de passe et jetons opaques](09-mots-de-passe-jetons.md) : la connexion et la réinitialisation, les endpoints limités.
- [02. Profil de production](02-profil-production.md) : `FORWARD_HEADERS_STRATEGY` parmi les variables du déploiement.
- Documentation officielle : [RFC 6585, statut 429](https://www.rfc-editor.org/rfc/rfc6585), [Bucket4j](https://github.com/bucket4j/bucket4j).

[Sommaire](README.md) | [< 09. Mots de passe et jetons opaques](09-mots-de-passe-jetons.md) | [11. Spring Data JPA >](11-spring-data-jpa.md)

[limiter]: ../../src/main/java/io/julienmetral/tasks/ratelimit/services/RateLimiter.java
[queries]: ../../src/main/java/io/julienmetral/tasks/ratelimit/repositories/RateLimitQueries.java
[keys]: ../../src/main/java/io/julienmetral/tasks/ratelimit/services/RateLimitKeys.java
[exception]: ../../src/main/java/io/julienmetral/tasks/ratelimit/exceptions/RateLimitExceededException.java
[job]: ../../src/main/java/io/julienmetral/tasks/ratelimit/services/RateLimitCleanupJob.java
[properties]: ../../src/main/java/io/julienmetral/tasks/config/RateLimitProperties.java
[entity]: ../../src/main/java/io/julienmetral/tasks/ratelimit/entities/RateLimitCounter.java
[handler]: ../../src/main/java/io/julienmetral/tasks/shared/exceptions/ApiExceptionHandler.java
[annotation]: ../../src/main/java/io/julienmetral/tasks/shared/openapi/RateLimited.java
[changeset]: ../../src/main/resources/db/changelog/changes/015-rate-limit-counters.yaml
[it]: ../../src/test/java/io/julienmetral/tasks/ratelimit/RateLimitTests.java
[unit]: ../../src/test/java/io/julienmetral/tasks/ratelimit/services/RateLimiterTest.java
[keys-test]: ../../src/test/java/io/julienmetral/tasks/ratelimit/services/RateLimitKeysTest.java
[queries-test]: ../../src/test/java/io/julienmetral/tasks/ratelimit/repositories/RateLimitQueriesTests.java
[config-test]: ../../src/test/java/io/julienmetral/tasks/config/RateLimitConfigurationTest.java
