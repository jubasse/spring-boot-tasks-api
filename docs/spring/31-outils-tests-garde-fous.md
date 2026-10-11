# 31. Outils de test et tests garde-fous

[Sommaire](README.md) | [< 30. Testcontainers](30-testcontainers.md) | [32. Actuator, sondes et métriques >](32-actuator-metriques.md)

## Contexte

- **Problème** : certains comportements se testent mal tels quels : le temps qui passe, l'asynchrone, le nombre de requêtes SQL, un serveur HTTP distant. Certaines conventions (« chaque job a un verrou ») ne sont protégées par rien, et on les oublie sur la classe suivante.
- **Quand l'utiliser** : une horloge contrôlée dès qu'un service raisonne sur le temps, Awaitility pour tout résultat asynchrone, un compteur de requêtes sur les listes, un faux serveur HTTP pour un client sortant. Chaque convention que le compilateur ne voit pas gagne un test garde-fou.
- **Quand s'en passer** : un test unitaire qui reçoit déjà une `Clock` fixe (`Clock.fixed`, guide 01), ou une convention qu'un type ou une annotation obligatoire impose déjà.
- **Dans ce dépôt** : `TestClock`, `Mailpit`, `SqlStatementCounter`, MockWebServer, et des tests qui parcourent le code (`AccessDescriptionTest`, `ScheduledJobLocksTest`) ou les fichiers de configuration (`ProdProfileTest`).
- **Prérequis** : guides 29 (contextes) et 01 (`Clock`, `ApplicationContextRunner`). Versions : Spring Boot 4.1, JUnit 6, AssertJ 3, Awaitility 4.3, MockWebServer 5.5 (`mockwebserver3`), Hibernate 7.

Les outils et le risque qu'ils couvrent :

| Outil | Risque couvert | Coût |
|---|---|---|
| Horloge de test | règles de délai, d'expiration, de rétention | un bean `@Primary` |
| Awaitility | résultat produit sur un autre thread (file, mail, `@Async`) | aucun |
| Compteur de requêtes | requêtes N+1 réintroduites | une propriété Hibernate |
| MockWebServer | vrai client HTTP, délais, codes d'erreur | un serveur local par test |
| Test garde-fou | convention oubliée sur une nouvelle classe | un test sans Spring |

## Concepts clés

### Vocabulaire

- **Horloge injectée** : un bean `java.time.Clock` que les services lisent au lieu de `Instant.now()`. Un test peut le remplacer.
- **Attente active** (polling) : relire un état à intervalle court jusqu'à ce qu'il soit vrai ou qu'un délai expire, au lieu d'un `Thread.sleep` fixe.
- **Inspecteur de requêtes** (`StatementInspector`) : un point d'accroche de Hibernate qui voit chaque requête SQL avant son envoi.
- **Test garde-fou** : un test qui ne vérifie pas un comportement mais une règle sur le code lui-même (annotations, noms, dépendances).
- **Fixture** : une petite classe écrite dans le test pour enfreindre la règle exprès, et prouver que le garde-fou le voit.

### Le temps sous contrôle

Les services lisent l'horloge, et le test choisit laquelle :

```
 service : clock.instant()
             |
             v
 production : Clock.systemUTC()          tests : TestClock (@Primary)
                                              |
                     +------------------------+-----------------+
                     |                        |                 |
               rien d'appelé           set(2026-03-01)    advance(2 jours)
                     |                        |                 |
                     v                        v                 v
            heure système          heure figée          heure décalée
                                              \                 /
                                               v               v
                                      reset() après chaque test
```

- Une seule horloge sert tous les tests du contexte. La remise à zéro après chaque test évite qu'un test laisse l'heure figée au suivant.
- Remplacer `Clock` par `@MockitoBean` dans une classe créerait un contexte de plus (guide 29). Un bean de test toujours présent n'en crée aucun.
- `advance` ne déclenche pas les jobs planifiés : le test appelle le service lui-même.

### Attendre l'asynchrone

On relit l'état jusqu'à ce qu'il soit vrai, ou jusqu'à l'échec :

```
 test --> action (POST, envoi d'un mail)
            |
            v
   relire l'état toutes les 100 ms
            |
            +-- vrai --> on continue
            |
            +-- 5 s écoulées --> échec, avec le dernier message d'assertion
```

- Un `Thread.sleep(2000)` est trop long quand tout va bien et trop court sur une CI chargée. L'attente active s'arrête dès que la condition est vraie.
- Prouver qu'une chose n'arrive pas (« un seul mail ») demande au contraire un délai de grâce : on attend un peu, puis on compte. Le dépôt a une constante `NO_MAIL_GRACE_PERIOD` pour cela.

### Les tests garde-fous

Un garde-fou lit le code compilé et liste les écarts à une règle :

```
 classpath du projet
        |
        v
 scanner Spring : classes qui portent @Scheduled
        |
        v
 pour chaque méthode : règle respectée ?
        |
        +-- non --> message précis : "InvoicePurgeJob.run() n'a pas de verrou"
        |
        v
 liste vide --> test vert
```

- Il tourne en millisecondes, sans démarrer Spring.
- Il se teste lui-même deux fois : le scan trouve des classes (un scan vide passerait toujours), et une fixture fautive est signalée.
- Le message d'échec nomme la classe, la méthode et la parade. Il documente la convention au moment où on l'enfreint.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/test/java/com/example/invoices/
  support/
    TestClock.java                    l'horloge de test (étape 1)
    IntegrationTest.java              la brancher partout (étape 2)
    SqlStatementCounter.java          le compteur de requêtes (étape 4)
  InvoiceReminderTests.java           temps (étape 2)
  InvoiceQueryCountTests.java         nombre de requêtes (étape 5)
  PaymentClientTests.java             faux serveur HTTP (étape 6)
src/test/java/com/example/architecture/
  ScheduledJobsAreLockedTest.java     le garde-fou (étapes 7 et 8)
src/test/resources/config/application.yaml   l'inspecteur (étape 4)
```

### Le temps, l'asynchrone, le SQL

**Étape 1. Écrire une horloge de test.** Elle suit l'heure système tant qu'un test ne la fige pas, et se remet à zéro. Les champs sont `volatile`, car les threads des listeners la lisent.

```java
@TestComponent                                   // jamais pris par le scan de l'application
@Primary                                         // remplace le bean Clock de production
public class TestClock extends Clock {

    private volatile Instant pinned;
    private volatile Duration offset = Duration.ZERO;

    public void set(Instant instant) {
        pinned = instant;
        offset = Duration.ZERO;
    }

    public void advance(Duration duration) {
        Instant current = pinned;
        if (current != null) {
            pinned = current.plus(duration);
        } else {
            offset = offset.plus(duration);
        }
    }

    public void reset() {
        pinned = null;
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        Instant current = pinned;
        return current != null ? current : Instant.now().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }
}
```

**Étape 2. La brancher dans l'annotation d'intégration, et la remettre à zéro.** Importée par l'annotation de la famille, elle ne crée aucun contexte. Une extension JUnit la remet à l'heure après chaque test.

```java
public final class ResetClockAfterEach implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context)
                .getBeanProvider(TestClock.class)
                .ifAvailable(TestClock::reset);
    }
}
```

```java
// sur l'annotation d'intégration du guide 29
@SpringBootTest
@AutoConfigureMockMvc
@Import({ServiceContainers.class, TestClock.class})          // conteneurs : guide 30
@ExtendWith(ResetClockAfterEach.class)                       // une extension n'entre pas dans la clé du cache
public @interface IntegrationTest {
}
```

Un test l'utilise ainsi :

```java
@IntegrationTest
class InvoiceReminderTests {

    @Autowired TestClock clock;
    @Autowired InvoiceService invoiceService;               // le domaine d'exemple
    @Autowired InvoiceReminderService reminderService;

    @Test
    void invoiceOverdueByTwoDaysGetsAReminder() {
        clock.set(Instant.parse("2026-03-01T09:00:00Z"));
        UUID invoiceId = invoiceService.create("client-" + UUID.randomUUID(), Duration.ofDays(1)).getId();

        clock.advance(Duration.ofDays(2));

        assertThat(reminderService.sendOverdueReminders()).contains(invoiceId);
    }
}
```

**Étape 3. Attendre un résultat asynchrone avec Awaitility.** On relit l'état jusqu'à ce qu'il soit vrai, au lieu de dormir un temps fixe. Awaitility est déjà dans `spring-boot-starter-test`.

```java
// le mail part après le commit, par une file : il arrive « bientôt »
mockMvc.perform(post("/invoices/{id}/send", invoiceId).with(caller))
        .andExpect(status().isAccepted());

await().atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> assertThat(mailbox.countTo(customerEmail)).isEqualTo(1));
// import static org.awaitility.Awaitility.await ; mailbox : un client de Mailpit (guide 23)
```

**Étape 4. Compter les requêtes SQL sur le thread du test.** Hibernate appelle l'inspecteur pour chaque requête. Une propriété l'enregistre dans tous les contextes, sans bean ni contexte de plus.

```java
public class SqlStatementCounter implements StatementInspector {   // org.hibernate.resource.jdbc.spi

    // Par thread : les listeners et les jobs interrogent la base sur leurs propres threads
    private static final ThreadLocal<List<String>> RECORDING = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        List<String> recording = RECORDING.get();
        if (recording != null) {
            recording.add(sql);
        }
        return sql;
    }

    public static List<String> statementsDuring(Action action) throws Exception {
        List<String> recording = new ArrayList<>();
        RECORDING.set(recording);
        try {
            action.run();
        } finally {
            RECORDING.remove();
        }
        return List.copyOf(recording);
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }
}
```

```yaml
# src/test/resources/config/application.yaml
spring:
  jpa:
    properties:
      hibernate.session_factory.statement_inspector: com.example.invoices.support.SqlStatementCounter
```

**Étape 5. Comparer une liste courte et une liste longue.** Un nombre fixe casse au moindre changement. « Autant de requêtes pour 20 lignes que pour 2 » attrape le N+1, qui grandit avec la liste.

```java
@IntegrationTest
class InvoiceQueryCountTests {

    @Autowired MockMvc mockMvc;

    @Test
    void listingInvoicesRunsTheSameNumberOfQueriesWhateverTheirCount() throws Exception {
        String few = customerWithInvoices(2);             // outil du test : crée un client et ses factures
        String many = customerWithInvoices(20);

        List<String> fewQueries = SqlStatementCounter.statementsDuring(() -> list(few));
        List<String> manyQueries = SqlStatementCounter.statementsDuring(() -> list(many));

        assertThat(manyQueries).hasSameSizeAs(fewQueries).hasSizeLessThanOrEqualTo(2);
    }

    private void list(String customer) throws Exception {
        // size=50 : plus grand que les deux listes (voir les pièges)
        mockMvc.perform(get("/invoices?customer={c}&size=50", customer).with(jwt()))
                .andExpect(status().isOk());
    }
}
```

### Les appels HTTP sortants

**Étape 6. Simuler le serveur distant avec MockWebServer.** Le vrai client appelle un port local. On vérifie la requête envoyée, les délais et les codes d'erreur.

```java
class PaymentClientTests {

    private final MockWebServer server = new MockWebServer();     // mockwebserver3

    @BeforeEach
    void start() throws IOException {
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);   // IPv4 explicite
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void refusedPaymentIsReported() throws Exception {
        server.enqueue(new MockResponse.Builder().code(402).body("{\"reason\":\"declined\"}").build());
        PaymentClient client = new PaymentClient(RestClient.create(server.url("/").toString()));

        assertThatThrownBy(() -> client.charge("INV-1", 120))
                .isInstanceOf(PaymentDeclinedException.class);    // le domaine d'exemple

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getTarget()).isEqualTo("/payments");
        assertThat(request.getBody().utf8()).contains("INV-1");
    }
}
```

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver3-junit5</artifactId>
    <version>5.5.0</version>                                 <!-- non géré par Spring Boot -->
    <scope>test</scope>
</dependency>
```

### Un test garde-fou

**Étape 7. Écrire le garde-fou.** Un scanner Spring parcourt le classpath sans démarrer de contexte, et la règle produit une liste d'écarts lisibles.

```java
class ScheduledJobsAreLockedTest {                           // package com.example.architecture

    private static final String BASE_PACKAGE = "com.example.invoices";

    @Test
    void scanFindsTheJobs() {
        assertThat(scheduledMethods(BASE_PACKAGE)).isNotEmpty();      // un scan vide passerait toujours
    }

    @Test
    void everyScheduledMethodCarriesALock() {
        assertThat(unlocked(scheduledMethods(BASE_PACKAGE))).isEmpty();
    }

    static List<Method> scheduledMethods(String basePackage) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) {   // sans évaluer @Conditional
                return reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName());
            }

            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;                                                   // abstraites et internes aussi
            }
        };
        return scanner.findCandidateComponents(basePackage).stream()
                .<Class<?>>map(definition -> ClassUtils.resolveClassName(definition.getBeanClassName(), null))
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, Scheduled.class))
                .toList();
    }

    static List<String> unlocked(List<Method> methods) {
        return methods.stream()
                .filter(method -> !method.isAnnotationPresent(SchedulerLock.class))   // ShedLock, guide 21
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName()
                        + "() n'a pas de @SchedulerLock")
                .toList();
    }
}
```

**Étape 8. Vérifier le garde-fou lui-même.** Une fixture fautive prouve que la règle signale ce qu'elle doit signaler, avec le bon message.

```java
// dans ScheduledJobsAreLockedTest
@Test
void unlockedJobIsReported() {
    List<Method> fixture = Arrays.asList(UnlockedJob.class.getDeclaredMethods());

    assertThat(unlocked(fixture)).containsExactly("UnlockedJob.run() n'a pas de @SchedulerLock");
}

// Jamais @Component (le scan l'enregistrerait), et hors de BASE_PACKAGE (le garde-fou l'ignore)
static class UnlockedJob {

    @Scheduled(fixedDelay = 1000)
    public void run() {
    }
}
```

```bash
./mvnw test -Dtest=ScheduledJobsAreLockedTest     # quelques millisecondes, sans Docker
```

### Référence rapide

`TestClock` :

| Méthode | Effet |
|---|---|
| `set(instant)` | fige l'heure |
| `advance(duration)` | avance l'heure figée, ou décale l'heure système |
| `reset()` | revient à l'heure système (après chaque test) |

Awaitility :

| Méthode | Rôle |
|---|---|
| `await().atMost(Duration.ofSeconds(5))` | délai maximal |
| `.pollInterval(Duration.ofMillis(100))` | intervalle entre deux lectures |
| `.untilAsserted(() -> assertThat(...)...)` | attend qu'une assertion AssertJ passe |
| `.until(() -> condition)` | attend une condition booléenne |

MockWebServer 5 (`mockwebserver3`) :

| Méthode | Rôle |
|---|---|
| `start(InetAddress, 0)` | démarre sur un port libre de cette adresse |
| `enqueue(new MockResponse.Builder().code(..).body(..).build())` | la prochaine réponse |
| `MockResponse.Builder.headersDelay(...)`, `bodyDelay(...)` | simuler un serveur lent (délais de lecture) |
| `takeRequest(1, SECONDS)` | la requête reçue : `getMethod()`, `getTarget()`, `getHeaders()`, `getBody()` |
| `close()` | arrête le serveur |

Limites de cette version : le compteur ne voit pas le SQL de `JdbcTemplate`. Un garde-fou par scan ne contrôle que ce qu'on lui a appris à chercher.

## Concepts avancés

### Vérifier une configuration sans démarrer l'application

- `ApplicationContextRunner` démarre un petit contexte avec la seule configuration testée (guide 01).
- Pour un seul fichier YAML : `YamlPropertySourceLoader`, puis un `Binder` (`ProdProfileTest`).
- Il faut retirer les variables d'environnement et les propriétés système de l'`Environment` testé. La machine qui lance les tests peut exporter `MAIL_FROM` et fausser le résultat.

### ArchUnit, l'outil dédié

ArchUnit lit le bytecode et exprime les règles dans un DSL : dépendances entre packages, couches, annotations. Il est plus concis qu'un scanner écrit à la main dès qu'il y a plusieurs règles. Ce dépôt ne l'utilise pas encore. La dépendance est `com.tngtech.archunit:archunit-junit6` (version 1.5.1).

```java
@AnalyzeClasses(packages = "com.example.invoices")
class ArchitectureTest {

    @ArchTest
    static final ArchRule servicesAreNotCalledByRepositories = noClasses()   // ArchRuleDefinition.noClasses
            .that().resideInAPackage("..repositories..")
            .should().dependOnClassesThat().resideInAPackage("..services..");
}
```

### Sous le capot : pourquoi un `ThreadLocal`

- MockMvc traite la requête sur le thread du test, donc ce que le test mesure tombe sur ce thread.
- L'outbox, les listeners RabbitMQ et les jobs interrogent la base sur leurs threads. Un compteur global mélangerait leurs requêtes aux siennes.
- Hibernate crée lui-même l'instance de l'inspecteur à partir du nom de classe, d'où l'état `static`.

## Pièges à éviter

### Temps et asynchrone

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Appeler `Instant.now()` dans un service | le test ne peut ni figer ni avancer l'heure | Injecter `Clock` (guide 01) |
| Construire par `@InjectMocks` un service à `Clock` | Mockito passe `null` : NPE | Déclarer un `@Spy Clock clock = Clock.fixed(...)` |
| Remplacer `Clock` par `@MockitoBean` dans une classe | un contexte de plus, avec ses conteneurs | Importer un `TestClock` par l'annotation de la famille |
| Attendre avec `Thread.sleep` | test lent quand tout va bien, instable sur une CI chargée | Écrire `await().atMost(...).untilAsserted(...)` |
| Prouver une absence avec `await` | la condition est vraie tout de suite, avant l'arrivée du doublon | Attendre un délai de grâce, puis compter |

### Compteur de requêtes et MockWebServer

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Enregistrer l'inspecteur en bean ou en mock | un contexte de plus pour une classe | Passer par la propriété Hibernate |
| Compter sur un compteur global | les requêtes des listeners et des jobs s'ajoutent | Utiliser un `ThreadLocal`, le temps de l'action |
| Choisir une page trop petite | Spring Data saute le `count` d'une page incomplète : une requête d'écart | Prendre une page plus grande que les deux listes |
| Remplacer le client par `MockRestServiceServer` | le vrai client, ses délais et son filtre ne sont pas testés | Utiliser MockWebServer sur un port local |

### Tests garde-fous

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Ne pas tester le scan | un scan vide (mauvais package) laisse le test vert pour toujours | Ajouter un test « le scan trouve des classes connues » |
| Utiliser les filtres d'inclusion du scanner | ils évaluent `@Conditional` : un job éteint ici échappe au contrôle | Surcharger `isCandidateComponent` |
| Annoter une fixture `@Component` ou `@Controller` | le scan de l'application l'enregistre | Écrire une classe simple, hors du package scanné |
| Écrire un message d'échec vague | on ne sait pas quelle classe corriger ni comment | Nommer la classe, la méthode et la règle |

## En résumé

**À retenir :** ce qui dépend du temps, d'un autre thread, du nombre de requêtes ou d'un serveur distant se teste avec un outil dédié qui ne crée aucun contexte de plus. Chaque convention que le compilateur ne voit pas gagne un test garde-fou qui se teste lui-même.

- [ ] Les services lisent un bean `Clock`, les tests d'intégration un `TestClock` remis à zéro après chaque test
- [ ] `await()` pour l'asynchrone, un délai de grâce pour prouver une absence
- [ ] Un compteur de requêtes enregistré par propriété, limité au thread du test
- [ ] Des tests « liste courte contre liste longue » sur chaque liste paginée
- [ ] MockWebServer sur `127.0.0.1` pour chaque client HTTP sortant
- [ ] Un garde-fou par convention invisible au compilateur
- [ ] Chaque garde-fou vérifie que son scan trouve des classes, et signale une fixture
- [ ] Les messages d'échec nomment la classe et la parade

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`TestClock`][clock] | l'horloge `@TestComponent @Primary` ; `ResetAfterEach` la remet à l'heure après chaque test |
| [`ClearCachesAfterEach`][caches] | vide tous les caches après chaque test : un statut de compte modifié en SQL ne survit pas au test |
| [`Mailpit`][mailpit] | lit les mails capturés par l'API de Mailpit ; `latestTextTo` attend jusqu'à 5 s |
| [`SqlStatementCounter`][counter] | l'inspecteur Hibernate, par thread |
| [`TaskQueryCountTests`][task-count], [`UserQueryCountTests`][user-count] | les listes courtes contre longues |
| [`OutboundHttpConfigurationTest`][outbound-test] | MockWebServer devant le vrai client Apache et son filtre d'adresses |
| [`AccessDescriptionTest`][access-test] | chaque règle de sécurité d'un contrôleur porte une `@AccessDescription` |
| [`ScheduledJobLocksTest`][locks-test] | chaque `@Scheduled` a son verrou, ou est un poller autorisé (guide 21) |
| [`ProdProfileTest`][prod-test] | le profil `prod` lu par un `Binder`, sans démarrer l'application |
| [`RequiredPropertiesCheckTest`][required-test] | les réglages obligatoires, avec `ApplicationContextRunner` |

Les réglages de test, dans [`config/application.yaml`][test-yaml] :

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.jpa.properties.hibernate.session_factory.statement_inspector` | `io.julienmetral.tasks.support.SqlStatementCounter` | compter sans contexte de plus |
| `webhooks.require-https` | `false` | appeler MockWebServer en HTTP |
| `outbound-http.allowed-addresses` | `127.0.0.1/32` | laisser le filtre SSRF joindre le serveur local (guide 26) |
| `spring.http.serviceclient.webhooks.read-timeout` | `2s` | un serveur lent donne un `Timeout` sans bloquer 15 s |

Ce qui est propre au dépôt :

- `@IntegrationTest` importe `TestClock` et `Mailpit` et porte `@ExtendWith({TestClock.ResetAfterEach.class, ClearCachesAfterEach.class})`. Le contexte de `MonitoringTests` n'importe ni l'un ni l'autre.
- `ClockConfiguration` fournit `Clock.systemUTC()` en production, et une trentaine de classes de `src/main` injectent `Clock`. Seuls `JwtService` et l'audit de Spring Data lisent l'heure système : le décodeur JWT vérifie `exp` sur l'horloge système (guide 01), et des lignes datées par une horloge figée dans le futur passeraient en tête des listes triées de la base partagée (guide 12).
- `Mailpit.latestTextTo(email, containing)` : les mails passent par l'outbox et RabbitMQ, donc deux mails envoyés à la suite peuvent arriver dans le désordre. On cherche le plus récent qui contient un texte, pas le dernier de tous. La boucle est écrite à la main, avec une lecture toutes les 100 ms et une échéance de 5 s. `latestVerificationTokenFor(email)` extrait le jeton de 43 caractères.
- Délai de grâce : une constante `NO_MAIL_GRACE_PERIOD` dans les classes qui prouvent qu'un mail ne part pas, ou une seule fois. Elle vaut 800 ms le plus souvent (`MailQueueTests`, `OutboxTests`, `TaskNotificationApiTests`), 500 ms dans `RateLimitTests` et 1 s dans les tests de webhooks.
- Les bornes mesurées dans `TaskQueryCountTests` : 2 requêtes pour une page de tâches, 1 pour une tâche, 3 pour une page d'événements, 6 pour une page de commentaires, 2 pour la liste des pièces jointes, avec le statut de l'appelant déjà en cache (une requête de plus au premier appel, vérifiée par `callerStatusIsReadOnceWhileItIsCached`). La taille de page est 50, plus grande que toutes les listes.
- MockWebServer est démarré à la main sur `127.0.0.1` plutôt qu'avec `@StartStop`, qui se lie à ce que `localhost` résout d'abord, parfois `::1`. `WebhookDeliveryTests` vérifie la signature des webhooks avec la bibliothèque officielle Standard Webhooks.
- `AccessDescriptionTest` scanne les `@Controller` : une `@PreAuthorize` directe, une annotation de sécurité sans `@AccessDescription` ou un placeholder `{x}` qui ne nomme aucun attribut sont signalés. Ses fixtures ne sont jamais des `@Controller`.
- `ScheduledJobLocksTest` vérifie le verrou, le nom dans `ScheduledJobLocks.NAMES`, les durées, une méthode `public` non `final` et un nom de 64 caractères au plus. Les fixtures du test partagent le package : seules les classes de l'application sont retenues, comparées par l'emplacement de leur code (`getProtectionDomain().getCodeSource()`).
- `ProdProfileTest` vérifie qu'aucun profil n'est activé par le fichier, la liste `deployment.required-properties`, la documentation d'API éteinte, le driver `aws-s3` et les logs `ecs`.
- `OpenApiSpecTests` est le snapshot du document OpenAPI (guide 06).

Les garde-fous tournent sans Docker, alors que les tests de comptage sont des tests d'intégration. Pour ajouter un garde-fou, on écrit une classe `*Test` sans Spring, un test « le scan trouve des classes connues », la règle qui renvoie des messages, puis une fixture par cas signalé.

## Voir aussi

- [29. Couches de tests et contextes](29-tests-couches-contextes.md) : pourquoi ces outils évitent un contexte de plus.
- [01. Configuration et `@ConfigurationProperties`](01-configuration-proprietes.md) : le bean `Clock` et `ApplicationContextRunner`.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : le snapshot du document OpenAPI.
- [26. Clients HTTP et filtre SSRF](26-clients-http-ssrf.md) : le client que MockWebServer met à l'épreuve.
- Documentation officielle : [Awaitility](https://github.com/awaitility/awaitility/wiki/Usage), [MockWebServer](https://github.com/square/okhttp/tree/master/mockwebserver), [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html).

[Sommaire](README.md) | [< 30. Testcontainers](30-testcontainers.md) | [32. Actuator, sondes et métriques >](32-actuator-metriques.md)

[clock]: ../../src/test/java/io/julienmetral/tasks/support/TestClock.java
[caches]: ../../src/test/java/io/julienmetral/tasks/support/ClearCachesAfterEach.java
[mailpit]: ../../src/test/java/io/julienmetral/tasks/support/Mailpit.java
[counter]: ../../src/test/java/io/julienmetral/tasks/support/SqlStatementCounter.java
[task-count]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskQueryCountTests.java
[user-count]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/UserQueryCountTests.java
[outbound-test]: ../../src/test/java/io/julienmetral/tasks/config/OutboundHttpConfigurationTest.java
[access-test]: ../../src/test/java/io/julienmetral/tasks/shared/security/AccessDescriptionTest.java
[locks-test]: ../../src/test/java/io/julienmetral/tasks/config/ScheduledJobLocksTest.java
[prod-test]: ../../src/test/java/io/julienmetral/tasks/config/ProdProfileTest.java
[required-test]: ../../src/test/java/io/julienmetral/tasks/config/RequiredPropertiesCheckTest.java
[test-yaml]: ../../src/test/resources/config/application.yaml
