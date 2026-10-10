# 26. Clients HTTP et filtre SSRF

[Sommaire](README.md) | [< 25. WebSocket et STOMP](25-websocket-stomp.md) | [27. Résilience >](27-resilience.md)

## Contexte

- **Problème** : appeler une API externe avec des délais bornés. Quand l'URL vient d'un utilisateur (un webhook, un rappel), ne jamais laisser l'API appeler son propre réseau.
- **Quand l'utiliser** : `RestClient` pour tout appel HTTP synchrone, un client déclaratif (une interface) dès qu'une API a plusieurs opérations, le filtre d'adresses dès qu'un utilisateur choisit une URL.
- **Quand s'en passer** : un SDK officiel existe pour le service (AWS, Stripe...), le code est réactif de bout en bout (`WebClient`), ou l'appel est lent ou à réessayer longtemps (une file d'abord, guides 19 et 27).
- **Dans ce dépôt** : un seul appel sortant, la livraison des webhooks (`WebhookClient`), vers des URL déclarées par les utilisateurs.
- **Prérequis** : guides 01 (configuration) et 14 (transactions : appel hors transaction). Versions : Spring Boot 4.1, Spring Framework 7.0, Apache HttpClient 5.6.

Les clients HTTP de Spring :

| Client | Style | Quand le choisir |
|---|---|---|
| `RestClient` | appels enchaînés, synchrones | un appel ou deux, code impératif |
| Client déclaratif (`@HttpExchange`) | une interface, Spring écrit l'implémentation | une API avec plusieurs opérations |
| `WebClient` | réactif | application WebFlux |

## Concepts clés

### Vocabulaire

- **Client déclaratif** (HTTP service client) : une interface Java annotée (`@GetExchange`, `@PostExchange`). Spring crée l'objet qui fait les appels.
- **Groupe de clients** : des interfaces qui partagent le même `RestClient` et les mêmes réglages (URL de base, délais), nommé dans `@ImportHttpServices(group = ...)`.
- **SSRF** (Server-Side Request Forgery) : faire appeler par le serveur une adresse que l'attaquant ne peut pas joindre lui-même.
- **Résolution DNS** : la traduction d'un nom d'hôte en adresses IP, faite à chaque connexion.
- **DNS rebinding** : un nom qui résout vers une adresse publique au contrôle, puis vers une adresse interne à l'appel.

### L'attaque SSRF

L'attaquant déclare une URL qui n'a de sens que depuis l'intérieur du réseau :

```
 attaquant                      API (dans le réseau interne)
     |                                   |
     |-- déclare un webhook ------------>|
     |   http://169.254.169.254/latest/meta-data/
     |                                   |
     |                                   |-- appelle l'URL --> métadonnées
     |                                   |                      du cloud
     |                                   |<-- identifiants ---  (joignables
     |                                   |                      de l'intérieur)
```

- Le serveur voit ce que l'attaquant ne voit pas : métadonnées du cloud (`169.254.169.254`), base de données, `localhost`.
- Adresses à refuser : boucle locale (`127.0.0.0/8`, `::1`), réseaux privés (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7`), lien local (`169.254.0.0/16`, `fe80::/10`) et les plages réservées.

### Contrôler à la déclaration ne suffit pas

Un nom d'hôte peut changer d'adresse entre le contrôle et l'appel :

```
 lundi    déclaration   hooks.example.com --> 93.184.216.34  publique : ok
 mardi    livraison     hooks.example.com --> 10.0.0.5       interne !
                                    |
                                    v
          seul un contrôle à chaque connexion l'arrête
```

- Le contrôle à la déclaration donne un refus clair à l'utilisateur (422), mais la vraie barrière est à chaque appel.
- Vérifier soi-même l'adresse, puis laisser le client résoudre à nouveau, laisse une fenêtre : la seconde résolution peut donner une autre adresse.
- Le filtre doit donc vivre dans le client, qui résout, filtre, puis se connecte exactement aux adresses filtrées. Avec Apache, Spring Boot 4.1 enveloppe pour cela le résolveur DNS du client.

### Les valeurs par défaut à changer

Sans réglage, un client Boot a des comportements qu'on ne choisit pas :

| Comportement par défaut | Risque | Réglage |
|---|---|---|
| Premier client du classpath | une dépendance ajoutée change de client | `spring.http.clients.imperative.factory` |
| Délais d'Apache | une connexion attend plusieurs minutes | `connect-timeout`, `read-timeout` |
| Redirections suivies | le récepteur oriente l'appel suivant | `redirects: dont-follow` |
| Relances automatiques d'Apache | un POST renvoyé sur 429 ou 503 | `disableAutomaticRetries()` |
| Statut 4xx ou 5xx | `RestClient` lève une exception | un `defaultStatusHandler` |

La stratégie de relance d'Apache réessaie une fois sur les codes 429 et 503, même pour un POST, en respectant `Retry-After`. Boot choisit dans l'ordre Apache, Jetty, Reactor, JDK, puis le client simple.

## Implémentation avec Spring

Ce qu'on va créer, pour une boutique qui appelle l'API d'un transporteur et laisse ses marchands déclarer une URL de rappel :

```
pom.xml                                   dépendances (étape 1)
src/main/resources/
  application.yaml                        client, délais, groupes (2, 4, 7)
src/main/java/.../
  carrier/
    CarrierClient.java                    client déclaratif (étape 3)
    CarrierClientConfiguration.java       son groupe (étape 4)
  config/
    OutboundHttpProperties.java           les plages autorisées (étape 5)
    OutboundHttpConfiguration.java        filtre, relances (étapes 5, 6)
  callback/
    CallbackClient.java                   client d'une URL de marchand (étape 7)
    CallbackClientConfiguration.java      son groupe (étape 7)
    CallbackUrlPolicy.java                contrôle à la déclaration (étape 8)
src/test/java/.../
  OutboundHttpConfigurationTest.java      la vérification (étape 9)
```

### Appeler une API connue

**Étape 1. Ajouter les dépendances.** Le starter apporte `RestClient` et les clients déclaratifs. Apache HttpClient est déclaré pour être choisi exprès, pas parce qu'une autre bibliothèque l'apporte.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-restclient</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
</dependency>
```

Sans `spring-boot-starter-restclient`, les propriétés `spring.http.*` sont ignorées sans message.

**Étape 2. Choisir le client et borner les délais.** Ces réglages valent pour tous les clients que Boot construit.

```yaml
spring:
  http:
    clients:
      imperative:
        factory: http-components     # Apache, choisi explicitement
      connect-timeout: 5s
      read-timeout: 15s
```

**Étape 3. Déclarer le client.** Une interface décrit l'API, et Spring écrit les appels.

```java
public interface CarrierClient {

    @GetExchange("/parcels/{trackingNumber}")
    ParcelStatus status(@PathVariable String trackingNumber);
}

public record ParcelStatus(String trackingNumber, String state) {
}
```

**Étape 4. L'enregistrer dans un groupe.** Le groupe porte l'URL de base et ses délais. `CarrierClient` devient un bean injectable.

```java
@Configuration
@ImportHttpServices(group = "carrier", types = CarrierClient.class)
class CarrierClientConfiguration {
}
```

```yaml
spring:
  http:
    serviceclient:
      carrier:
        base-url: https://api.carrier.example
        read-timeout: 5s
```

### Appeler une URL choisie par un utilisateur

**Étape 5. Filtrer les adresses.** Un bean `InetAddressFilter` s'applique à tous les clients que Boot construit. Seules les adresses publiques passent, plus les plages listées pour le développement.

```java
@ConfigurationProperties(prefix = "outbound-http")
public record OutboundHttpProperties(@DefaultValue List<String> allowedAddresses) {
}
```

```java
@Configuration
@EnableConfigurationProperties(OutboundHttpProperties.class)
class OutboundHttpConfiguration {

    // IPv6 qui contient une adresse IPv4 : externalAddresses() les laisse passer
    private static final String[] EMBEDDED_IPV4 = {"::/96", "::ffff:0:0:0/96", "64:ff9b:1::/48"};

    @Bean
    InetAddressFilter outboundAddressFilter(OutboundHttpProperties properties) {
        InetAddressFilter publicOnly = InetAddressFilter.externalAddresses().andNot(EMBEDDED_IPV4);
        return properties.allowedAddresses().isEmpty()
                ? publicOnly
                : publicOnly.or(properties.allowedAddresses().toArray(String[]::new));
    }
}
```

`InetAddressFilter` (`org.springframework.boot.http.client`) existe depuis Boot 4.1.0. Une plage invalide comme `10.0.0.0/33` fait échouer le démarrage (`IllegalArgumentException`).

**Étape 6. Couper les relances automatiques d'Apache.** Sa stratégie par défaut renvoie un POST sur 429 ou 503 après avoir attendu le `Retry-After`. Réessayer est la décision de l'appelant (guide 27).

```java
// dans OutboundHttpConfiguration
@Bean
ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder>
        noAutomaticRetries() {
    return builder -> builder.withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries);
}
```

**Étape 7. Déclarer un client sans redirection, sans cookie, qui rend chaque statut.** Une redirection ou un cookie laisseraient le récepteur orienter l'appel suivant. Un 4xx ou un 5xx est un résultat à enregistrer, pas une exception.

```java
public interface CallbackClient {

    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> send(URI url, @RequestHeader Map<String, String> headers,
            @RequestBody byte[] body);
}
```

Un argument `URI` donne l'URL de l'appel, propre à chaque marchand. Le corps en `byte[]` envoie les octets exacts, par exemple ceux qu'on a signés.

```java
@Configuration
@ImportHttpServices(group = "callbacks", types = CallbackClient.class)
class CallbackClientConfiguration {

    @Bean
    RestClientHttpServiceGroupConfigurer callbacksGroup() {
        return groups -> groups.filterByName("callbacks").forEachClient((group, builder) -> builder
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                }));                                  // aucun statut ne lève d'exception
    }
}
```

```yaml
spring:
  http:
    serviceclient:
      callbacks:
        connect-timeout: 5s
        read-timeout: 15s
        redirects: dont-follow
        cookie-handling: disable
```

**Étape 8. Contrôler l'URL à la déclaration.** Le marchand reçoit un refus clair tout de suite, mais le filtre du client reste la vraie barrière.

```java
@Component
@RequiredArgsConstructor
public class CallbackUrlPolicy {

    private final InetAddressFilter outboundAddressFilter;

    public void check(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("URL invalide");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("HTTPS sur le port 443, sans identifiants");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!outboundAddressFilter.matches(address)) {
                    throw new IllegalArgumentException("L'URL doit viser une adresse publique");
                }
            }
        } catch (UnknownHostException unknown) {
            throw new IllegalArgumentException("Hôte inconnu");
        }
    }
}
```

Dans une vraie API, une exception dédiée devient un 422 (guide 05).

**Étape 9. Vérifier.** MockWebServer est un vrai serveur HTTP local, donc le test passe par le vrai client, le filtre et les délais.

```java
class OutboundHttpConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
            .withUserConfiguration(OutboundHttpConfiguration.class)
            .withPropertyValues("spring.http.clients.imperative.factory=http-components");

    private final MockWebServer server = new MockWebServer();

    @BeforeEach
    void start() throws IOException {
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);   // pas "localhost"
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void loopbackIsRefusedBeforeAnyRequestLeaves() {
        runner.run(context -> {
            RestClient client = context.getBean(RestClient.Builder.class).build();

            assertThatExceptionOfType(FilteredHostException.class).isThrownBy(() -> client.post()
                    .uri("http://127.0.0.1:{port}/callback", server.getPort())
                    .retrieve().toBodilessEntity());
            assertThat(server.getRequestCount()).isZero();
        });
    }

    @Test
    void allowedRangeIsReached() {
        server.enqueue(new MockResponse.Builder().code(204).build());

        runner.withPropertyValues("outbound-http.allowed-addresses=127.0.0.1/32").run(context -> {
            ResponseEntity<Void> response = context.getBean(RestClient.Builder.class).build()
                    .post().uri("http://127.0.0.1:{port}/callback", server.getPort())
                    .retrieve().toBodilessEntity();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        });
    }
}
```

`MockWebServer` et `MockResponse` viennent de `mockwebserver3` (artefact `com.squareup.okhttp3:mockwebserver3-junit5`, scope test). `RestClient` laisse passer `FilteredHostException` sans l'envelopper.

### Référence rapide

Les réglages :

| Réglage | Exemple | Rôle |
|---|---|---|
| `spring.http.clients.imperative.factory` | `http-components` | `http-components`, `jetty`, `reactor`, `jdk`, `simple` |
| `spring.http.clients.connect-timeout` | `5s` | délai de connexion, pour tous les clients |
| `spring.http.clients.read-timeout` | `15s` | délai de lecture, pour tous les clients |
| `spring.http.clients.redirects` | `dont-follow` | `follow-when-possible`, `follow`, `dont-follow` |
| `spring.http.clients.cookie-handling` | `disable` | `enable-when-possible`, `enable`, `disable` |
| `spring.http.serviceclient.<groupe>.base-url` | `https://api.example` | URL de base du groupe |
| `spring.http.serviceclient.<groupe>.*` | | mêmes délais, redirections, cookies, par groupe |

Les méthodes de `InetAddressFilter` :

| Méthode | Rôle |
|---|---|
| `externalAddresses()` | adresses routables, ni multicast, ni réservées (RFC 6890) |
| `and(...)`, `andNot(...)`, `or(...)` | combiner avec des plages ou d'autres filtres |
| `matches(address)` | tester une adresse |

Limites de cette version : un appel échoué n'est pas réessayé (guide 27).

## Concepts avancés

### Sous le capot : le filtre dans la résolution DNS

- Boot crée les réglages du client (`HttpClientSettings`) avec le bean `InetAddressFilter` s'il existe, et chaque client construit par Boot les reçoit.
- Avec Apache, Boot remplace le résolveur DNS par `HttpComponentsFilteredDnsResolver` : résolution, filtre, puis connexion aux seules adresses restantes. Si aucune ne reste, `FilteredHostException` est levée. Il n'y a pas de seconde résolution, donc le DNS rebinding n'a pas de fenêtre.
- `externalAddresses()` laisse passer trois formes IPv6 qui contiennent une adresse IPv4, loopback et métadonnées comprises (par exemple `::127.0.0.1` ou `::a9fe:a9fe`). Le dépôt les retire à l'étape 5 : `::/96` (compatible IPv4), `::ffff:0:0:0/96` (traduite IPv4) et `64:ff9b:1::/48` (NAT64 à usage local).

### Le piège du proxy sortant

Boot construit le client Apache avec `useSystemProperties()`. Si `http.proxyHost` ou `https.proxyHost` est réglé, le client se connecte au proxy, et le filtre vérifie l'adresse du proxy, pas celle de la cible. Il ne faut jamais régler ces propriétés. Si un proxy sortant est imposé, c'est lui qui doit filtrer.

### Surveiller sans fuite

Spring mesure et trace chaque appel (`http.client.requests`), avec l'hôte dans les tags et l'URL complète dans les traces. Avec des URL d'utilisateurs, les séries grossissent avec les abonnés, et une URL peut contenir un secret (Slack). On remplace donc la convention par un nom constant :

```java
class CallbackObservationConvention extends DefaultClientRequestObservationConvention {

    @Override
    protected KeyValue clientName(ClientRequestObservationContext context) {
        return KeyValue.of(LowCardinalityKeyNames.CLIENT_NAME, "callback");
    }

    @Override
    protected KeyValue requestUri(ClientRequestObservationContext context) {
        return KeyValue.of(HighCardinalityKeyNames.HTTP_URL, "callback");
    }
}
```

On la pose avec `builder.observationConvention(new CallbackObservationConvention())` dans le configurateur de groupe. Il ne faut jamais journaliser le message d'une exception du client, qui contient l'URL. On enregistre un type court (`Timeout`, `ConnectionFailed`, `DestinationNotAllowed`).

## Pièges à éviter

### Avec un client HTTP

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder les délais par défaut d'Apache | une connexion peut attendre plusieurs minutes | Régler `connect-timeout` et `read-timeout` |
| Laisser Boot choisir le client | une dépendance ajoutée change de client et de comportement | Fixer `spring.http.clients.imperative.factory` |
| Garder les relances automatiques d'Apache | un POST repart sur 429 ou 503, après le `Retry-After` | Appeler `disableAutomaticRetries()` |
| Laisser un 4xx ou 5xx lever une exception | le statut se perd dans une exception | Poser un `defaultStatusHandler` sur le groupe |

### Avec des URL fournies par les utilisateurs

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Contrôler l'URL seulement à la déclaration | un hôte qui change d'adresse atteint le réseau interne | Filtrer aussi dans le client, à chaque connexion |
| Résoudre et vérifier soi-même avant l'appel | le client résout à nouveau : la réponse peut changer | Mettre un `InetAddressFilter` dans le client |
| Se fier à `externalAddresses()` seul | trois formes IPv6 contenant une IPv4 passent | Ajouter `andNot` sur ces plages |
| Suivre les redirections | le récepteur oriente l'appel suivant | Régler `redirects: dont-follow` |
| Régler `http.proxyHost` | le filtre vérifie l'adresse du proxy | Ne pas utiliser ces propriétés |
| Journaliser le message d'exception du client | l'URL, parfois secrète, finit dans les logs | Garder un type d'erreur court |
| Lister une plage autorisée en production | toute URL d'utilisateur l'atteint | Laisser `allowed-addresses` vide en production |

### En test

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Tester avec `MockRestServiceServer` | il remplace le client : filtre et délais jamais testés | Utiliser MockWebServer, un vrai serveur local |
| Démarrer MockWebServer sur `localhost` | il peut écouter sur `::1` | Le démarrer sur `127.0.0.1` |

## En résumé

**À retenir :** un client choisi et borné (Apache, délais, sans relance automatique), déclaratif par groupe. Pour une URL d'utilisateur, un `InetAddressFilter` dans le client, sans redirection ni cookie, et un contrôle à la déclaration pour un refus clair.

- [ ] `spring-boot-starter-restclient`, Apache HttpClient et `factory: http-components`
- [ ] `connect-timeout` et `read-timeout` pour tous les clients, ajustés par groupe
- [ ] Relances automatiques d'Apache coupées
- [ ] Une interface `@HttpExchange` par API, un groupe par hôte ou par usage
- [ ] Un bean `InetAddressFilter` : adresses publiques, IPv6 à IPv4 intégrée refusées
- [ ] `redirects: dont-follow` et `cookie-handling: disable` pour les URL d'utilisateurs
- [ ] URL contrôlée à la déclaration : HTTPS, port 443, sans identifiants, adresses publiques
- [ ] Ni URL ni message d'exception dans les logs et les métriques
- [ ] Tests avec MockWebServer sur `127.0.0.1`, plage autorisée en test seulement

## Dans ce dépôt

Où chaque contrôle a lieu :

```
  POST /api/v1/users/{id}/webhooks
       |
       v
  WebhookUrlPolicy.check              HTTPS 443, sans identifiants,
       |                              adresses publiques
       |                              (refus : 422 webhook-url-not-allowed)
       v
  endpoint enregistré
       ...
  WebhookDeliveryService.deliver      hors transaction
       |
       v
  WebhookClient.send                  groupe "webhooks" : 5 s, 15 s,
       |                              ni redirection ni cookie
       |
       v
  Apache HttpClient                   résolution filtrée par InetAddressFilter
       |
       +-- adresse interne --> FilteredHostException
       |                       --> DestinationNotAllowed, FAILED
       |
       +-- adresse publique --> récepteur ; chaque statut est une réponse
```

La livraison complète est racontée au [cas pratique](cas-pratiques/livraison-webhook.md).

| Fichier | Rôle |
|---|---|
| [`OutboundHttpConfiguration`][outbound-config] | le filtre d'adresses et la coupure des relances d'Apache, pour tous les clients |
| [`OutboundHttpProperties`][outbound-props] | `outbound-http.allowed-addresses` |
| [`WebhookClient`][client] | l'interface `@PostExchange` : URL en paramètre, corps en octets signés ; son `@Retryable` est au guide 27 |
| [`WebhookConfiguration`][webhook-config] | `@ImportHttpServices(group = "webhooks")`, le gestionnaire de statut vide, la convention d'observation |
| [`WebhookObservationConvention`][observation] | nom de client constant `webhook`, ni hôte ni URL |
| [`WebhookUrlPolicy`][url-policy] | le contrôle à la déclaration ; `checkSlack` pour les URL Slack |
| [`WebhookDeliveryService`][delivery-service] | traduit les échecs en types courts (`DestinationNotAllowed`...) |

Les réglages :

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.http.clients.imperative.factory`, `connect-timeout` / `read-timeout` | `http-components`, 5s / 15s | Apache, tous les clients |
| `spring.http.serviceclient.webhooks.connect-timeout` / `read-timeout` | 5s / 15s (2s en test) | le client des webhooks |
| `spring.http.serviceclient.webhooks.redirects` | `dont-follow` | aucune redirection |
| `spring.http.serviceclient.webhooks.cookie-handling` | `disable` | aucun cookie |
| `outbound-http.allowed-addresses` | vide (`OUTBOUND_HTTP_ALLOWED_ADDRESSES`) ; `127.0.0.1/32` en test | plages joignables en plus des adresses publiques |
| `webhooks.require-https` | `true` (`WEBHOOK_REQUIRE_HTTPS`) ; `false` en test | HTTPS sur 443 exigé à la déclaration |

Ce qui est propre au dépôt :

- Le filtre vaut pour tout client construit par Boot. Le SDK AWS construit le sien.
- `WebhookUrlPolicy.check` refuse un schéma autre que http ou https, des identifiants dans l'URL, tout autre chose que HTTPS sur le port 443 (si `requireHttps` est vrai), un hôte qui ne se résout pas, et toute adresse que le même bean `InetAddressFilter` refuse.
- Slack (`checkSlack`) : seulement `https://hooks.slack.com/services/...`, 256 caractères au plus, sans requête, fragment ni identifiants. La destination ne se choisit pas, donc rien à résoudre. L'URL est un secret, stockée chiffrée et masquée.
- Une adresse refusée par le filtre à la livraison termine la livraison (`FAILED`, `DestinationNotAllowed`), car réessayer ne ferait que sonder l'adresse à nouveau.
- En développement, pour recevoir sur sa machine : `WEBHOOK_REQUIRE_HTTPS=false` et `OUTBOUND_HTTP_ALLOWED_ADDRESSES=127.0.0.1/32` (README, « Receive them on your machine »).

Les tests :

- [`OutboundHttpConfigurationTest`][outbound-test] : `ApplicationContextRunner` et MockWebServer. Boucle locale et 15 destinations privées refusées, plage autorisée qui n'ouvre rien d'autre, redirection vers une adresse privée refusée, POST répondu 429 ou 503 envoyé une seule fois, plages invalides refusées au démarrage.
- [`WebhookUrlPolicyTest`][url-policy-test] : 21 tests du contrôle à la déclaration, dont les URL Slack, ni résolues ni filtrées.
- [`WebhookDeliveryTests`][delivery-tests] : redirection non suivie, cookie non renvoyé.

Pour ajouter un appel sortant : une interface `@HttpExchange` dans un groupe de `@ImportHttpServices`, ses délais sous `spring.http.serviceclient.<groupe>`, l'appel hors transaction et un test avec MockWebServer. Si l'URL vient d'un utilisateur, un contrôle à la déclaration réutilise le bean `InetAddressFilter`.

## Voir aussi

- [27. Résilience](27-resilience.md) : réessais, disjoncteur et cloison autour de ce client.
- [14. Transactions](14-transactions.md) : l'appel HTTP hors transaction.
- [10. Rate limiting](10-rate-limiting.md) : les événements de test d'un webhook.
- [Cas pratique : la livraison d'un webhook](cas-pratiques/livraison-webhook.md) : une livraison de bout en bout.
- [README du dépôt, « Receive task notifications by webhook »](../../README.md#receive-task-notifications-by-webhook) : le contrat pour les récepteurs.
- Documentation officielle : [clients HTTP dans Spring Boot](https://docs.spring.io/spring-boot/reference/io/rest-client.html), [clients REST de Spring](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), [prévention du SSRF (OWASP)](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

[Sommaire](README.md) | [< 25. WebSocket et STOMP](25-websocket-stomp.md) | [27. Résilience >](27-resilience.md)

[outbound-config]: ../../src/main/java/io/julienmetral/tasks/config/OutboundHttpConfiguration.java
[outbound-props]: ../../src/main/java/io/julienmetral/tasks/config/OutboundHttpProperties.java
[client]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookClient.java
[webhook-config]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookConfiguration.java
[observation]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookObservationConvention.java
[url-policy]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookUrlPolicy.java
[delivery-service]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[outbound-test]: ../../src/test/java/io/julienmetral/tasks/config/OutboundHttpConfigurationTest.java
[url-policy-test]: ../../src/test/java/io/julienmetral/tasks/notification/webhook/WebhookUrlPolicyTest.java
[delivery-tests]: ../../src/test/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryTests.java
