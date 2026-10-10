# 32. Actuator, sondes et métriques

[Sommaire](README.md) | [< 31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) | [33. Image Docker, build et CI >](33-docker-build-ci.md)

## Contexte

- **Problème** : savoir de l'extérieur si l'application vit, si elle peut recevoir du trafic, et ce qu'elle fait (files en retard, erreurs, temps de réponse), sans lire les logs.
- **Quand l'utiliser** : toute application déployée derrière un orchestrateur (Kubernetes, ECS) ou un load balancer, et suivie par Prometheus ou un outil équivalent.
- **Quand s'en passer** : un outil en ligne de commande ou un batch lancé et terminé. On garde alors seulement les logs.
- **Dans ce dépôt** : Actuator sur le port 8081, sondes `/livez` et `/readyz` sur le port de l'API, deux indicateurs de santé maison, des jauges sur l'outbox et les dead letters.
- **Prérequis** : guides 07 (chaîne de filtres) et 01 (configuration). Versions : Spring Boot 4.1 (modules `spring-boot-health`, `spring-boot-micrometer-metrics`), Micrometer 1.17, `micrometer-registry-prometheus`.

Les trois questions, et qui les pose :

| Question | Endpoint | Qui l'appelle | Échec |
|---|---|---|---|
| Le processus vit-il ? | sonde de vie (liveness) | l'orchestrateur | redémarre le conteneur |
| Peut-il recevoir du trafic ? | sonde de disponibilité (readiness) | l'orchestrateur, le load balancer | retire l'instance du trafic |
| Que fait-il ? | `/actuator/prometheus` | Prometheus, toutes les N secondes | une alerte |

## Concepts clés

### Vocabulaire

- **Endpoint Actuator** : une URL de supervision fournie par Spring Boot (`health`, `info`, `metrics`, `prometheus`, `sbom`).
- **Port de gestion** : un second port HTTP, réservé aux endpoints Actuator (`management.server.port`).
- **Sonde** (probe) : une URL que l'orchestrateur appelle à intervalle régulier. Un 200 veut dire oui, un 503 non.
- **Indicateur de santé** (health indicator) : un composant qui vérifie une dépendance (base, broker, stockage) et répond UP ou DOWN.
- **Groupe de santé** : une sélection d'indicateurs exposée sous son propre chemin, avec ses propres réglages (`liveness`, `readiness`, ou un groupe maison).
- **Jauge, compteur** : une jauge monte et descend (taille d'une file), et un compteur ne fait que monter (messages publiés). Une **série** est une métrique avec une combinaison de tags.

### Vie et disponibilité

Deux sondes, deux conséquences différentes :

```
 orchestrateur
      |
      +-- GET /livez  --> livenessState seul
      |                     DOWN --> redémarrer le conteneur
      |
      +-- GET /readyz --> readinessState + base de données
                            DOWN --> retirer du load balancer,
                                     sans redémarrer
```

- La sonde de vie ne regarde que l'état interne. Avec la base, une panne de base ferait redémarrer toutes les instances en boucle, sans rien réparer.
- La sonde de disponibilité inclut ce sans quoi aucune requête ne peut aboutir. Une dépendance qui ne bloque qu'une partie de l'API (mails, fichiers) reste hors de la sonde. Sa panne apparaît dans la santé globale, et l'instance sert le reste.
- `readinessState` passe à « refuse le trafic » dès le début de l'arrêt, donc le load balancer cesse d'envoyer des requêtes avant l'arrêt du serveur.

### Deux ports

L'API et la supervision n'écoutent pas au même endroit :

```
                 +---------------------------------+
 internet ---->  | port 8080 : l'API               |
 load balancer   |   /api/v1/...   (JWT)           |
 orchestrateur   |   /livez, /readyz  (sans détail)|
                 +---------------------------------+
                 | port 8081 : Actuator            |
 Prometheus ---> |   /actuator/health  (détails)   |
 réseau de       |   /actuator/prometheus, info,   |
 supervision     |   metrics, sbom                 |
                 +---------------------------------+
```

- Le port de gestion n'a pas d'authentification. Une règle réseau le limite à la supervision.
- Une sonde sur le seul port de gestion peut réussir alors que le port de l'API ne répond plus (threads saturés, connecteur arrêté). Les sondes sont donc servies aussi sur le port de l'API, sans détails.

### Métriques et cardinalité

Le code et Spring alimentent un même registre, lu par Prometheus :

```
 code : Gauge, Counter, Timer          Spring : http.server.requests,
        (Micrometer)                            jvm.*, hikaricp.*, cache.*
             \                                   /
              v                                 v
                   MeterRegistry (Prometheus)
                            |
                            v
          GET /actuator/prometheus (texte, à chaque scrape)
                            |
                            v
                  Prometheus --> alertes
```

- Une jauge est lue à chaque scrape. Elle peut interroger la base ou le broker, à condition d'être rapide et de ne jamais lever d'exception (elle renvoie `NaN` si la source ne répond pas).
- Chaque combinaison de tags est une série que Prometheus garde. Un tag par utilisateur, par URL complète ou par hôte de client fait grandir les séries sans fin. On agrège donc : une jauge « nombre de disjoncteurs ouverts » plutôt qu'une par hôte.
- Les points des noms Micrometer deviennent des tirets bas, un compteur gagne `_total` et une unité gagne son suffixe. Ainsi `outbox.messages.oldest.pending.age` devient `outbox_messages_oldest_pending_age_seconds`.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                             dépendances, build-info, SBOM (étapes 1, 8)
src/main/resources/application.yaml port, endpoints, sondes (étapes 2, 3)
src/main/java/com/example/invoices/
  config/SecurityConfiguration.java ports et sondes publics (étape 4)
  archive/InvoiceArchiveHealthIndicator.java
                                    un indicateur maison (étape 5)
  invoice/InvoiceMetrics.java       une jauge (étape 6)
  invoice/InvoiceSender.java        un compteur (étape 7)
src/test/java/com/example/invoices/
  MonitoringTests.java              la vérification (étape 9)
```

### Santé et sondes

**Étape 1. Ajouter Actuator et le registre Prometheus.** Actuator fournit les endpoints, et le registre ajoute `/actuator/prometheus`.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>     <!-- version gérée par Spring Boot -->
</dependency>
```

**Étape 2. Mettre Actuator sur son propre port.** Le port de gestion reste interne, et on choisit les endpoints exposés (par défaut, seul `health`).

```yaml
management:
  server:
    port: ${MANAGEMENT_PORT:8081}          # jamais ouvert sur internet
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus, sbom
  endpoint:
    health:
      show-details: always                 # sur le port de gestion seulement
```

**Étape 3. Définir les sondes et les publier sur le port de l'API.** On crée deux groupes à soi, avec un chemin sur le port de l'API et sans détails. La disponibilité ne dépend que de la base. L'ancre YAML (`&readiness` puis `*readiness`) réutilise la même liste d'indicateurs.

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true                      # groupes liveness et readiness (défaut : true)
      group:
        readiness:
          include: &readiness readinessState, db
        livez:
          include: livenessState
          show-details: never
          additional-path: server:/livez   # server: = port de l'API
        readyz:
          include: *readiness              # ancre YAML : la même liste que readiness
          show-details: never
          additional-path: server:/readyz
```

**Étape 4. Ouvrir le port de gestion et les sondes dans la sécurité.** La chaîne de filtres s'applique aussi au port de gestion. Elle doit laisser passer tout ce qui y arrive, et les sondes du port de l'API, sans jeton.

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) {
    // local.management.port : publié au démarrage du port de gestion, avec son numéro réel
    RequestMatcher onManagementPort = request -> String.valueOf(request.getLocalPort())
            .equals(environment.getProperty("local.management.port"));

    http.authorizeHttpRequests(auth -> auth
            .requestMatchers(onManagementPort).permitAll()                    // privé par le réseau
            .requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
            .anyRequest().authenticated());
    // ... JWT, session sans état (guide 07)
    return http.build();
}
```

Un client de sonde qui garde un vieux jeton dans son en-tête `Authorization` recevrait 401. Un `BearerTokenResolver` qui renvoie `null` sur ces deux chemins ignore alors l'en-tête (guide 07).

**Étape 5. Écrire un indicateur de santé.** `AbstractHealthIndicator` transforme toute exception en DOWN avec son message. Le nom du bean sans le suffixe `HealthIndicator` devient le nom du composant (`invoiceArchive`).

```java
@Component
@RequiredArgsConstructor
public class InvoiceArchiveHealthIndicator extends AbstractHealthIndicator {   // spring-boot-health

    private final S3Client s3Client;                // client AWS SDK v2 (guide 28)
    private final ArchiveProperties properties;     // record avec bucket() (guide 01)

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        s3Client.headBucket(request -> request.bucket(properties.bucket()));   // exception si absent

        builder.up().withDetail("bucket", properties.bucket());
    }
}
```

Un indicateur doit répondre vite, avec un délai court, car il est appelé à chaque appel de `/actuator/health`.

### Métriques

**Étape 6. Publier une jauge lue à chaque scrape.** Un `MeterBinder` en bean est branché par Spring Boot sur le registre, après la création de tous les beans.

```java
@Component
@RequiredArgsConstructor
public class InvoiceMetrics implements MeterBinder {

    private final InvoiceRepository invoiceRepository;   // le domaine d'exemple

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("invoices.unsent", invoiceRepository, InvoiceRepository::countBySentAtIsNull)
                .description("Factures validées pas encore envoyées au client")
                .register(registry);
    }
}
```

**Étape 7. Compter un événement avec un tag borné.** On fait un compteur par résultat, et le tag ne prend que quelques valeurs connues.

```java
@Service
@RequiredArgsConstructor
public class InvoiceSender {

    private final MeterRegistry meterRegistry;
    private final MailService mailService;            // le service d'envoi (guide 23)

    public void send(Invoice invoice) {
        String outcome = "sent";
        try {
            mailService.send(invoice.toMail());       // toMail : le domaine d'exemple
        } catch (MailException failure) {             // org.springframework.mail.MailException
            outcome = "failed";
            throw failure;
        } finally {
            meterRegistry.counter("invoices.sent", "outcome", outcome).increment();   // jamais l'id du client
        }
    }
}
```

**Étape 8. Publier la version et la liste des dépendances.** `/actuator/info` affiche la version construite, et `/actuator/sbom/application` les bibliothèques du jar, pour vérifier une alerte de sécurité.

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>build-info</goal>            <!-- META-INF/build-info.properties -->
            </goals>
        </execution>
    </executions>
</plugin>
<plugin>
    <groupId>org.cyclonedx</groupId>
    <artifactId>cyclonedx-maven-plugin</artifactId>   <!-- configuré par le parent Spring Boot -->
</plugin>
```

Le parent Spring Boot fait écrire `META-INF/sbom/application.cdx.json` par CycloneDX, et Actuator le trouve tout seul.

**Étape 9. Vérifier.** Seul un vrai serveur a deux ports, car MockMvc les ignore. `@AutoConfigureMetrics` est nécessaire. Sans lui, les tests ne gardent qu'un registre en mémoire et `/actuator/prometheus` n'existe pas. La dépendance de test est `spring-boot-starter-actuator-test`.

```java
@AutoConfigureMetrics   // org.springframework.boot.micrometer.metrics.test.autoconfigure
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")   // 0 : port libre
class MonitoringTests {

    @LocalServerPort int apiPort;
    @LocalManagementPort int managementPort;     // org.springframework.boot.test.web.server

    @Test
    void probeOnTheApiPortShowsNoDetails() {
        String body = RestClient.create().get().uri("http://localhost:" + apiPort + "/readyz")
                .retrieve().body(String.class);

        assertThat(body).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test
    void prometheusExposesTheUnsentInvoices() {
        String scrape = RestClient.create().get()
                .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .retrieve().body(String.class);

        assertThat(scrape).contains("invoices_unsent");
    }
}
```

### Référence rapide

Les réglages :

| Réglage | Défaut | Rôle |
|---|---|---|
| `management.server.port` | celui de l'API | port de gestion séparé |
| `management.endpoints.web.exposure.include` | `health` | endpoints exposés en HTTP |
| `management.endpoint.health.show-details` | `never` | `always`, `when-authorized`, `never` |
| `management.endpoint.health.probes.enabled` | `true` | crée les groupes `liveness` et `readiness` |
| `management.endpoint.health.probes.add-additional-paths` | `false` | publie `/livez` et `/readyz` sur le port de l'API |
| `management.endpoint.health.group.<nom>.additional-path` | aucun | un chemin pour un groupe à soi : `server:/chemin` ou `management:/chemin` |
| `management.info.build.enabled` | `true` | `/actuator/info` montre `build-info` s'il existe |

Les endpoints :

| Endpoint | Contenu |
|---|---|
| `/actuator/health` | statut global et chaque composant |
| `/actuator/metrics/{nom}?tag=k:v` | une métrique, en JSON, pour un humain |
| `/actuator/prometheus` | toutes les métriques, en texte pour Prometheus (406 si on demande du JSON) |
| `/actuator/sbom/application` | dépendances du jar, format CycloneDX |

Limites de cette version : une jauge qui interroge la base à chaque scrape ajoute des requêtes.

## Concepts avancés

### Sous le capot : les sondes et `add-additional-paths`

Avec `add-additional-paths: true`, Spring Boot publie les groupes `liveness` et `readiness` eux-mêmes sur `/livez` et `/readyz`. Un seul groupe sert alors les deux ports, et masquer les détails sur `/readyz` les masque aussi sur le port de gestion. Deux groupes à soi avec `additional-path` gardent des réglages séparés.

### Sous le capot : le port de gestion

- Spring Boot démarre un second serveur web dans un contexte enfant, et publie son port réel dans `local.management.port`, même aléatoire (`management.server.port=0` en test). Si la gestion partage le port de l'API, la propriété est absente et la règle ne correspond à rien.
- La chaîne de filtres de sécurité s'applique aussi à ce port. N'autoriser que les chemins des endpoints laisse la page d'erreur derrière l'authentification, et chaque 404 ou 406 du port de gestion répondait 401. D'où une règle sur le port entier.

### Surveiller

- Une requête de 15 minutes (flux SSE) fausse `http.server.requests`, donc un `ObservationPredicate` l'exclut (guide 24).
- Une jauge par hôte de webhook grandirait avec les abonnés. On publie `webhook.circuit.breakers.open`, agrégée (guide 27).
- Les métriques des jobs : guide 21. Celles des caches : guide 17.

## Pièges à éviter

### Avec Actuator seul

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Mettre la base dans la sonde de vie | une panne de base fait redémarrer toutes les instances en boucle | Garder `livenessState` seul |
| Mettre le broker ou le stockage dans la disponibilité | leur panne retire toutes les instances du trafic | Garder seulement l'indispensable |
| Ouvrir le port de gestion sur internet | santé détaillée et métriques lisibles par tous | Le limiter au réseau de supervision |
| Sonder seulement le port de gestion | la sonde réussit alors que l'API ne répond plus | Publier les sondes sur le port de l'API |
| Utiliser `add-additional-paths` et cacher les détails | ils disparaissent aussi du port de gestion | Écrire deux groupes à soi, avec `additional-path` |

### Avec les métriques

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Mettre un id, un email ou une URL en tag | une série par valeur : la mémoire de Prometheus explose | Choisir des tags à quelques valeurs |
| Laisser une jauge lever une exception | le scrape échoue ou la valeur manque | Attraper l'erreur et renvoyer `NaN` |
| Passer un objet temporaire à `Gauge.builder` | la jauge le tient faiblement : après un GC, elle vaut `NaN` | Passer un bean, ou un `Supplier` |

### Avec la sécurité et les tests de ce dépôt

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Autoriser seulement les endpoints du port de gestion | ses pages d'erreur répondent 401 | Autoriser tout le port (`local.management.port`) |
| Laisser la sonde lire l'en-tête `Authorization` | un jeton expiré donne 401 | Ignorer l'en-tête sur `/livez` et `/readyz` |
| Oublier `@AutoConfigureMetrics` | `/actuator/prometheus` n'existe pas en test | Ajouter l'annotation |

## En résumé

**À retenir :** Actuator sur un port privé, des sondes publiques et sans détail sur le port de l'API (vie = état interne, disponibilité = la base seule), des indicateurs de santé rapides pour chaque dépendance, et des métriques aux tags bornés.

- [ ] `management.server.port` à part, joignable seulement par la supervision
- [ ] Endpoints exposés listés un par un
- [ ] `/livez` (état interne) et `/readyz` (dépendances indispensables) sur le port de l'API, sans détails
- [ ] Le port de gestion et les sondes autorisés dans la chaîne de filtres
- [ ] Un indicateur de santé par dépendance, avec un délai court
- [ ] Des jauges en `MeterBinder`, qui ne lèvent jamais d'exception
- [ ] Aucun identifiant ni URL en tag
- [ ] `build-info` et le SBOM dans le jar
- [ ] Un test sur un vrai port, avec `@AutoConfigureMetrics`
- [ ] Des alertes sur les métriques qui annoncent une panne silencieuse (file, dead letter, job)

## Dans ce dépôt

Ce qui répond sur chaque port :

```
 port 8080 (API)                 port 8081 (gestion)
   /livez   -> groupe livez        /actuator/health  db, rabbit, mail,
   /readyz  -> groupe readyz                         diskSpace, storage,
   /actuator/** -> 401 ou 404                        antivirus, ...
                                   /actuator/prometheus, metrics, info,
                                   sbom, health/liveness, health/readiness
```

| Fichier | Rôle |
|---|---|
| [`application.yaml`][yaml] | port, endpoints exposés, groupes `readiness`, `livez`, `readyz` |
| [`SecurityConfiguration`][security] | tout le port de gestion permis, sondes `GET` permises, en-tête `Authorization` ignoré sur les sondes |
| [`StorageHealthIndicator`][storage-health] | `storage` : un `headBucket`, détails `driver` et `bucket` |
| [`AntivirusHealthIndicator`][antivirus-health] | `antivirus` : `PING` de clamd, 5 s au plus ; UP avec `scanning: disabled` si l'antivirus est éteint |
| [`OutboxMetrics`][outbox-metrics] | jauges `outbox.messages.pending` et `outbox.messages.oldest.pending.age` (secondes), lues en base |
| [`DeadLetterQueueMetrics`][dlq-metrics] | `rabbitmq.dead.letter.messages{queue}`, demandé au broker, `NaN` s'il ne répond pas |
| [`MessagingConfiguration`][messaging-config] | déclare `DeadLetterQueueMetrics` pour les quatre dead letters (mail, avatar, webhook, export) |
| [`OutboxRelay`][relay] | compteurs `outbox.messages.published` et `outbox.publish.failures` par file |
| [`RealtimeConfiguration`][realtime-config] | exclut le flux SSE de `http.server.requests` |
| [`pom.xml`][pom] | `build-info`, `cyclonedx-maven-plugin`, `micrometer-registry-prometheus` |

Les réglages sont ceux de l'étape 3, avec l'ancre nommée `&readiness-components` et `MANAGEMENT_PORT` pour le port.

Ce qui est propre au dépôt :

- Readiness = `db` seul. RabbitMQ en panne retarde seulement les mails et les photos (l'outbox les garde, guide 20), et le stockage ou l'antivirus en panne bloque seulement les fichiers.
- `SecurityConfiguration` permet toute requête arrivée sur `local.management.port`, page d'erreur comprise. N'autoriser que les endpoints faisait répondre 401 à chaque erreur de ce port. Elle permet `GET /livez` et `GET /readyz`, et son `BearerTokenResolver` ignore l'en-tête `Authorization` sur eux (un `POST /livez` reste 401). Les endpoints Actuator n'existent pas sur 8080 (401 ou 404).
- `AntivirusHealthIndicator` envoie `zPING\0`, attend `PONG`, et plafonne son délai à `min(antivirus.timeout, 5 s)`, car le délai d'un scan (une minute) ne convient pas à une sonde.
- L'image expose 8080 et 8081 (guide 33). `compose.production.yaml` publie 8081 sur `127.0.0.1:18081` seulement.

Les métriques à surveiller (README, « Monitor the API ») et ce qui doit alerter :

| Métrique | Alerte |
|---|---|
| `outbox_messages_pending` et `..._oldest_pending_age_seconds` | elles grandissent |
| `rabbitmq_dead_letter_messages{queue}` | au-dessus de 0 |
| `outbox_publish_failures_total` | monte |
| `cache_gets_total{cache="userStatus"}` | la part de `hit` baisse |
| `tasks_scheduled_execution_seconds_count{outcome="ERROR"}` | un job échoue |
| `shedlock_lock_acquired_total{lock_name}` | ne bouge plus au-delà de la période du job (25 h pour un job quotidien) |
| `webhook_deliveries_total{outcome="failed"}` | monte |
| `webhook_circuit_breakers_open` | au-dessus de 0 longtemps |

Les valeurs du tag `outcome` d'un job planifié sont `SUCCESS`, `ERROR` et `UNKNOWN`.

Les tests : [`MonitoringTests`][monitoring-tests] démarre un vrai serveur (`RANDOM_PORT`, `management.server.port=0`, `@AutoConfigureMetrics`). Il vérifie la santé UP de `db`, `rabbit`, `mail`, `storage` et `antivirus`, une readiness limitée à la base, le SBOM, les jauges de l'outbox, les sondes de 8080 sans détails (même avec un jeton invalide), les endpoints absents de 8080 et le 406 de `/actuator/prometheus` demandé en JSON. `PublicEndpointErrorTests` et `TaskRoomsWebSocketTests` partagent sa configuration, donc son contexte (guide 29).

Pour ajouter un indicateur de santé, on écrit une classe `...HealthIndicator` qui étend `AbstractHealthIndicator`, avec un délai court. Il entre rarement dans `readiness`. Pour ajouter une métrique, on choisit un nom en points et des tags bornés, puis on ajoute une ligne au tableau d'alertes du README.

## Voir aussi

- [24. Flux de notifications (SSE)](24-flux-notifications-sse.md) : la jauge des flux et l'`ObservationPredicate`.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : les métriques des jobs et des verrous.
- [20. Outbox transactionnelle](20-outbox-transactionnelle.md) : ce que mesurent les jauges de l'outbox.
- [33. Image Docker, build et CI](33-docker-build-ci.md) : les deux ports de l'image, `build-info` et le SBOM.
- [README du dépôt, « Monitor the API »](../../README.md#monitor-the-api) : les URL et les alertes conseillées.
- Documentation officielle : [endpoints Actuator et sondes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [métriques](https://docs.spring.io/spring-boot/reference/actuator/metrics.html), [Micrometer](https://docs.micrometer.io/micrometer/reference/).

[Sommaire](README.md) | [< 31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) | [33. Image Docker, build et CI >](33-docker-build-ci.md)

[yaml]: ../../src/main/resources/application.yaml
[security]: ../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[storage-health]: ../../src/main/java/io/julienmetral/tasks/media/health/StorageHealthIndicator.java
[antivirus-health]: ../../src/main/java/io/julienmetral/tasks/media/health/AntivirusHealthIndicator.java
[outbox-metrics]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxMetrics.java
[dlq-metrics]: ../../src/main/java/io/julienmetral/tasks/messaging/services/DeadLetterQueueMetrics.java
[messaging-config]: ../../src/main/java/io/julienmetral/tasks/config/MessagingConfiguration.java
[relay]: ../../src/main/java/io/julienmetral/tasks/messaging/services/OutboxRelay.java
[realtime-config]: ../../src/main/java/io/julienmetral/tasks/realtime/RealtimeConfiguration.java
[pom]: ../../pom.xml
[monitoring-tests]: ../../src/test/java/io/julienmetral/tasks/MonitoringTests.java
