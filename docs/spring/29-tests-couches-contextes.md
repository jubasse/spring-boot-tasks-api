# 29. Couches de tests et contextes

[Sommaire](README.md) | [< 28. Uploads et drivers de stockage](28-uploads-stockage.md) | [30. Testcontainers >](30-testcontainers.md)

## Contexte

- **Problème** : un test unitaire ne voit ni la sécurité, ni le JSON, ni le SQL. Un test qui démarre toute l'application voit tout, mais coûte des secondes et des conteneurs.
- **Quand l'utiliser** : dans tout projet Spring. Pour chaque risque, on choisit la couche la plus légère qui le couvre, et on partage les contextes entre les classes.
- **Quand s'en passer** : une classe sans Spring (un calcul, une règle métier) se teste en JUnit pur.
- **Dans ce dépôt** : quatre couches, chacune derrière une annotation : tests unitaires (`*Test`), `@WebLayerTest`, `@JdbcSliceTest` et `@RepositoryTest`, `@IntegrationTest`.
- **Prérequis** : guides 03 (contrôleurs), 07 (sécurité) et 11 (Spring Data JPA). Versions : Spring Boot 4.1, Spring Framework 7, JUnit 6, Mockito 5, AssertJ 3.

Les couches, de la plus légère à la plus complète :

| Couche | Ce qui démarre | Ce qu'elle prouve |
|---|---|---|
| Unitaire | aucun Spring : la classe et des mocks | la logique d'une classe |
| Web (`@WebMvcTest`) | contrôleurs, sécurité, gestionnaire d'erreurs, sans Docker | validation, statuts HTTP, 401 et 403, JSON |
| Données (`@DataJpaTest`, `@JdbcTest`) | JPA ou JDBC, une vraie base | requêtes, contraintes, migrations |
| Intégration (`@SpringBootTest`) | toute l'application, tous les conteneurs | le trajet complet, l'asynchrone |

## Concepts clés

### Vocabulaire

- **Contexte de test** : l'`ApplicationContext` démarré pour une classe de test, puis gardé pour les suivantes.
- **Slice** : un contexte réduit à une couche (web, JPA, JDBC), le reste étant absent ou en mocks.
- **Mock** : un faux objet programmé par le test, qui enregistre les appels.
- **Spy** : un vrai bean enveloppé, dont le test observe les appels et remplace quelques méthodes.
- **Clé de cache** : la configuration complète d'une classe de test. Même clé, même contexte.
- **Méta-annotation** : une annotation maison qui applique d'un coup d'autres annotations.

### La pyramide

Beaucoup de tests rapides en bas, peu de complets en haut :

```
                    /\
                   /  \          intégration : le trajet complet
                  /----\         (peu nombreux, lents, avec conteneurs)
                 /      \
                /--------\       slices : web, JPA, JDBC
               /          \      (une couche, vraie sécurité ou vraie base)
              /------------\
             /              \    unitaires : une classe, des mocks
            /----------------\   (nombreux, instantanés)
```

- On teste chaque règle au plus bas niveau possible : un calcul en unitaire, un 400 en web, une contrainte unique en slice.
- L'intégration vérifie ce qu'aucune couche ne voit seule : transactions, événements après commit, files, mails.

### Le cache des contextes

Spring garde chaque contexte démarré dans un cache statique, par clé. Une configuration identique reprend le contexte, et une seule différence en démarre un nouveau :

```
 classe A  @IntegrationTest              --> clé 1 : démarre le contexte 1
 classe B  @IntegrationTest              --> clé 1 : reprend le contexte 1
 classe C  @IntegrationTest
           + @TestPropertySource("x=1")   --> clé 2 : démarre le contexte 2
 classe D  @IntegrationTest
           + @MockitoBean MailSender      --> clé 3 : démarre le contexte 3
```

Ce qui entre dans la clé :

| Élément | Exemple | Nouveau contexte ? |
|---|---|---|
| Classes de configuration et `@Import` | `@Import(FakeStorage.class)` | oui |
| Propriétés de test | `@TestPropertySource`, `@SpringBootTest(properties = ...)` | oui |
| Beans remplacés | `@MockitoBean`, `@MockitoSpyBean` | oui |
| Profils actifs | `@ActiveProfiles("demo")` | oui |
| Propriétés dynamiques | `@DynamicPropertySource` | oui |
| Environnement web | `@SpringBootTest(webEnvironment = RANDOM_PORT)` | oui |
| Extension JUnit | `@ExtendWith(...)` | non |
| Héritage, classe `@Nested` | une classe de base annotée, une classe interne | non : même configuration |

### Quand le cache déborde

Le cache a une taille maximale (32 par défaut). Au-delà, Spring ferme le contexte le moins récemment utilisé :

```
 cache plein, une classe demande une clé nouvelle
                 |
                 v
 fermer le contexte le moins récemment utilisé (LRU)
                 |  beans détruits, conteneurs non réutilisés arrêtés
                 v
 démarrer le nouveau contexte
                 |
                 v
 une classe redemande la clé fermée --> le contexte redémarre en entier
```

- Chaque contexte ouvert garde sa mémoire, ses pools et ses conteneurs (guide 30). Un plafond trop haut sature la machine, et un plafond trop bas fait redémarrer des contextes. On le règle au nombre de configurations voulues, plus une marge.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                   dépendances de test (étape 1)
src/test/java/.../
  invoice/
    InvoiceServiceTest.java               test unitaire (étape 2)
    InvoiceControllerWebMvcTests.java     test web (étape 4)
    InvoiceRepositoryTests.java           slice JPA (étape 5)
    InvoiceApiTests.java                  intégration (étape 7)
  support/
    WebLayerTest.java                     annotation de la slice web (étape 3)
    IntegrationTest.java                  annotation d'intégration (étape 6)
src/test/resources/
  config/application.yaml                 réglages de test (étape 8)
  spring.properties                       taille du cache (étape 9)
```

### Les quatre couches

**Étape 1. Ajouter les dépendances.** Spring Boot 4 fournit un starter de test par slice.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>           <!-- JUnit, Mockito, AssertJ -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>    <!-- @WebMvcTest, MockMvc -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa-test</artifactId>  <!-- @DataJpaTest -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>  <!-- jwt(), user() -->
    <scope>test</scope>
</dependency>
```

**Étape 2. Écrire un test unitaire.** Pas de Spring : Mockito crée les mocks et les injecte.

```java
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    InvoiceRepository invoiceRepository;       // Invoice, InvoiceService... : le domaine d'exemple

    @InjectMocks
    InvoiceService invoiceService;

    @Test
    void cancellingAPaidInvoiceIsRefused() {
        Invoice paid = Invoice.paid("INV-1");
        when(invoiceRepository.findById(paid.getId())).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> invoiceService.cancel(paid.getId()))
                .isInstanceOf(InvoiceAlreadyPaidException.class);
        verify(invoiceRepository, never()).delete(any());
    }
}
```

**Étape 3. Créer l'annotation de la slice web.** Une seule annotation pour tous les tests web donne un seul contexte. Il contient tous les contrôleurs, la vraie sécurité et des services en mocks.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@WebMvcTest                                         // tous les contrôleurs, exprès
@Import(SecurityConfiguration.class)                // la vraie chaîne de filtres (guide 07)
@MockitoBean(types = {InvoiceService.class, CustomerService.class})
public @interface WebLayerTest {
}
```

**Étape 4. Écrire un test web.** MockMvc traverse la sécurité et le contrôleur. Le service mocké prouve que la requête est refusée avant lui.

```java
@WebLayerTest
class InvoiceControllerWebMvcTests {

    @Autowired MockMvc mockMvc;
    @Autowired InvoiceService invoiceService;       // le mock déclaré par @WebLayerTest

    @Test
    void invoiceWithoutAmountIsRejected() throws Exception {
        mockMvc.perform(post("/invoices")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(invoiceService);
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/invoices")).andExpect(status().isUnauthorized());
    }
}
```

**Étape 5. Écrire une slice JPA.** `@DataJpaTest` démarre JPA seul et annule chaque test (rollback). Contre une vraie base, on vérifie requêtes natives et contraintes.

```java
@DataJpaTest
@Import(PostgresTestcontainersConfiguration.class)   // le conteneur Postgres (guide 30)
class InvoiceRepositoryTests {

    @Autowired InvoiceRepository invoiceRepository;
    @Autowired TestEntityManager entityManager;

    @Test
    void unpaidInvoicesExcludeThePaidOnes() {
        Invoice unpaid = entityManager.persist(Invoice.unpaid("INV-" + UUID.randomUUID()));
        Invoice paid = entityManager.persist(Invoice.paid("INV-" + UUID.randomUUID()));

        assertThat(invoiceRepository.findUnpaid())   // contains, pas containsExactly : base partagée
                .contains(unpaid)
                .doesNotContain(paid);
    }
}
```

**Étape 6. Créer l'annotation d'intégration.** Elle démarre toute l'application avec MockMvc et les conteneurs. Chaque classe d'intégration la porte seule.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)          // tous les services en conteneurs (guide 30)
public @interface IntegrationTest {
}
```

**Étape 7. Écrire un test d'intégration.** Les données sont validées comme en production, donc on les rend uniques pour ne pas gêner les autres tests.

```java
@IntegrationTest
class InvoiceApiTests {

    @Autowired MockMvc mockMvc;

    @Test
    void createdInvoiceCanBeReadBack() throws Exception {
        String reference = "INV-" + UUID.randomUUID();
        var caller = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));

        String location = mockMvc.perform(post("/invoices").with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\": \"" + reference + "\", \"amount\": 120}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        mockMvc.perform(get(location).with(caller))
                .andExpect(jsonPath("$.reference").value(reference));
    }
}
```

**Étape 8. Écrire la configuration de test.** Spring Boot lit `classpath:/config/application.yaml` par-dessus `classpath:/application.yaml`. Il ne remplace que les clés nommées.

```yaml
# src/test/resources/config/application.yaml
security:
  jwt:
    secret: dGVzdC1vbmx5LXNlY3JldC1vZi1hdC1sZWFzdC0zMi1ieXRlcw== # gitleaks:allow (clé de test, sans .env)
invoices:
  purge-enabled: false        # les tests appellent le service ; un job planifié les gênerait
```

**Étape 9. Borner le cache.** Spring lit `spring.properties` à la racine du classpath.

```properties
# src/test/resources/spring.properties
spring.test.context.cache.maxSize=12
```

**Étape 10. Vérifier.** Lancer les classes, puis lire les statistiques du cache (debug).

```yaml
# à ajouter le temps de la vérification
logging:
  level:
    org.springframework.test.context.cache: debug
```

```bash
./mvnw test -Dtest='Invoice*'
# Spring test ApplicationContext cache statistics: [DefaultContextCache@... size = 3,
#   maxSize = 12, ..., hitCount = ..., missCount = 3, failureCount = 0]
```

`missCount` compte les contextes démarrés, `hitCount` les reprises. Ici, il y a trois contextes (web, JPA, intégration), quel que soit le nombre de classes.

### Référence rapide

Les annotations de test :

| Annotation | Charge | Starter de test |
|---|---|---|
| `@SpringBootTest` | toute l'application | `spring-boot-starter-test` |
| `@WebMvcTest` | contrôleurs, `@ControllerAdvice`, filtres, convertisseurs, MockMvc | `spring-boot-starter-webmvc-test` |
| `@DataJpaTest` | entités, repositories, `TestEntityManager`, migrations ; rollback | `spring-boot-starter-data-jpa-test` |
| `@JdbcTest` | `DataSource`, `JdbcTemplate`, migrations ; rollback | `spring-boot-starter-jdbc-test` |
| `@SpringJUnitConfig` | les seules classes nommées, sans Spring Boot | `spring-boot-starter-test` |

Les remplacements de beans de Spring Framework 7 (`@MockBean` n'existe plus) :

| Annotation | Effet | Remise à zéro |
|---|---|---|
| `@MockitoBean` sur un champ | remplace le bean par un mock | après chaque test (`reset = AFTER`) |
| `@MockitoBean(types = ...)` sur une classe | idem, sans champ : utilisable en méta-annotation | idem |
| `@MockitoSpyBean` | enveloppe le vrai bean | idem |

Les réglages du cache (`spring.properties` ou propriété système) :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.test.context.cache.maxSize` | 32 | nombre de contextes gardés |
| `spring.test.context.cache.pause` | `on_context_switch` | met en pause les contextes inactifs (Spring Framework 7.0.3) |

Limites de cette version : rien n'empêche une classe d'ajouter un mock ou une propriété, et donc de créer un contexte de plus en silence.

## Concepts avancés

### Sous le capot : la clé et les contextes en pause

Pour chaque classe, Spring calcule une configuration fusionnée (`MergedContextConfiguration`) : classes, initialiseurs, profils, propriétés, et « customizers » de Spring Boot et des remplacements de beans. Elle sert de clé au cache.

Depuis Spring Framework 7.0.3, le contexte quitté est mis en pause. Ses composants `SmartLifecycle` s'arrêtent (les listeners RabbitMQ, par exemple) et repartent à sa reprise. Il ne consomme plus de messages, mais garde sa mémoire et ses conteneurs.

Ce dépôt désactive cette pause (`spring.test.context.cache.pause=never`, valeurs acceptées : `always`, `on_context_switch`, `never`). Chaque conteneur de listener RabbitMQ attend, pour s'arrêter, la fin du `receiveTimeout` de ses consommateurs, soit 1 s : 22,5 s par exécution de la CI, mesurées le 2026-10-12. Chaque contexte de test a son propre broker, donc un contexte resté actif en cache ne consomme rien qui appartienne à un autre. Le risque inverse existerait avec un broker partagé.

### Composer des méta-annotations

Une méta-annotation regroupe la configuration d'une famille de tests et peut en empiler une autre. Les classes qui la portent seule ont la même clé :

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@IntegrationTest                                    // reprend toute l'intégration
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.retry.max-retries=2")
@MockitoBean(types = JavaMailSender.class)          // un mock commun à toute la famille
public @interface DeadLetterIntegrationTest {
}
```

- Les mocks et les propriétés d'une famille vont dans son annotation, jamais dans une seule classe.
- `@Inherited` transmet l'annotation aux sous-classes, donc une classe de base peut porter la configuration.

### Plusieurs exécutions en même temps

Un `./mvnw test` tourne dans une seule JVM, ce qui permet au cache de servir. Deux exécutions simultanées doublent contextes, mémoire et conteneurs sans rien partager. Pour une vérification ciblée, on lance seulement les classes concernées (`-Dtest=...`).

### Surveiller

- **Les statistiques du cache** (étape 10) : `missCount` ne doit pas grandir sans raison.
- **Le temps par classe** : `target/surefire-reports`. La première classe d'un contexte porte son démarrage.

## Pièges à éviter

### Avec Spring seul

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Ajouter `@MockitoBean` dans une seule classe | un nouveau contexte démarre, avec ses conteneurs | Déclarer le mock dans l'annotation partagée |
| Ajouter `@TestPropertySource` pour un réglage | un nouveau contexte démarre | Mettre le réglage dans `config/application.yaml` des tests |
| Nommer les contrôleurs dans `@WebMvcTest(XController.class)` | un contexte par jeu de contrôleurs | Charger tous les contrôleurs dans une seule annotation |
| Créer `src/test/resources/application.yaml` | il masque celui de `main` : tout disparaît | Écrire `src/test/resources/config/application.yaml` |
| Utiliser `@DirtiesContext` | le contexte est fermé, puis redémarré par la classe suivante | Remettre l'état à zéro (horloge, caches) après chaque test |
| Rendre `@Transactional` un test sur un vrai port | la requête tourne sur un autre thread : rien n'est annulé | Rendre les données uniques, ou nettoyer |
| Rendre `@Transactional` un test MockMvc d'intégration | les listeners après le commit ne se déclenchent jamais | Laisser valider, avec des données uniques |
| Écrire `when(spy.send(...))` sur un spy | la vraie méthode s'exécute pendant le stubbing | Écrire `doAnswer(...).when(spy).send(...)` |
| Stubber dans `@BeforeEach` ce qu'un test n'utilise pas | `MockitoExtension` refuse le stub inutile : échec | Stubber dans le test, ou marquer `lenient()` |
| Tester la page `/error` avec MockMvc | MockMvc ne fait pas le renvoi du conteneur vers `/error` | Tester sur un vrai port (`RANDOM_PORT`) |

### Avec les annotations maison et les conteneurs de ce dépôt

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Importer seulement la classe testée dans une slice JDBC | un contexte par classe de test | Inclure toutes les classes `*Queries` dans l'annotation |
| Appeler un endpoint de tâches en test web sans préparer le compte | 403 : le gestionnaire lit un statut vide | Appeler `WebCallers.everyAccountIsActive` |
| Fixer un profil actif en dehors du déploiement | les tests chargent `prod` et s'arrêtent | Ne l'activer qu'au déploiement (guide 02) |
| Lancer deux suites complètes à la fois | les contextes et leurs conteneurs saturent la mémoire | Une suite à la fois, ou seulement les classes concernées |

## En résumé

**À retenir :** chaque risque se teste dans la couche la plus légère qui le voit. Chaque famille de tests porte une seule annotation maison, pour un contexte par famille et non par classe.

- [ ] Les règles métier en tests unitaires, sans Spring ; des slices JPA et JDBC contre une vraie base
- [ ] Une annotation pour la slice web : tous les contrôleurs, vraie sécurité, services en mocks
- [ ] Une annotation d'intégration, portée seule ; mocks et propriétés d'une famille déclarés dans son annotation
- [ ] Réglages de test dans `src/test/resources/config/application.yaml`
- [ ] Un plafond de cache dans `spring.properties`, ajusté au nombre de familles
- [ ] Des données uniques dans les tests qui valident
- [ ] `missCount` vérifié après l'ajout d'une famille de tests
- [ ] Une seule suite complète à la fois sur une machine

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`IntegrationTest`][integration] | `@SpringBootTest`, MockMvc, les conteneurs, `Mailpit`, `TestClock` ; remet l'horloge et vide les caches après chaque test |
| [`WebLayerTest`][web] | `@WebMvcTest` de tous les contrôleurs, la vraie sécurité, les services et `UserRepository` en mocks |
| [`WebCallers`][callers] | les appelants des tests web : `user`, `admin`, `withoutUid`, et `everyAccountIsActive` |
| [`JdbcSliceTest`][jdbc] | `@JdbcTest`, toutes les classes `*Queries` (filtre par expression régulière), Postgres |
| [`RepositoryTest`][jpa] | `@DataJpaTest`, Postgres, et `JpaAuditingConfiguration` pour `created_at` et `updated_at` |
| [`DeadLetterIntegrationTest`][dead-letter] | l'intégration avec 2 réessais rapides, SMTP en mock, stockage et antivirus en spy |
| [`Transactions`][transactions] | `inNewTransaction` : valider sur une autre connexion, comme une autre instance |
| [`spring.properties`][spring-properties] | `spring.test.context.cache.maxSize=12` et `spring.test.context.cache.pause=never`, et pourquoi |
| [`config/application.yaml`][test-yaml] | les réglages de test, chargés par-dessus ceux de `main` |

Nommage : `*Test` pour les tests unitaires (et sans contexte partagé), `*WebMvcTests` pour la slice web, `*Tests` pour les slices de données et l'intégration. Surefire lance les trois par défaut.

`@WebLayerTest` remplace les beans de propriétaire (`taskAuthorization`, `taskAttachmentAuthorization`, `taskCommentAuthorization`) par des mocks de même nom, que la SpEL appelle par leur nom (guide 08). `ActiveUserAuthorizationManager`, `UserStatusLookup` et `CurrentUser` tournent pour de vrai.

Les contextes d'intégration qui diffèrent exprès (chacun a son RabbitMQ) :

| Contexte | Ce qui change | Pourquoi |
|---|---|---|
| `@IntegrationTest` | rien : c'est la référence | toutes les autres classes d'intégration |
| `@DeadLetterIntegrationTest` | 2 réessais en 50 à 100 ms, SMTP en mock, stockage et antivirus en spy | une dead letter atteinte en moins d'une seconde |
| `OutboxBrokerFailureTests` | `RabbitTemplate` en spy | faire échouer la publication à la demande |
| `RateLimitTests` | limites allumées et basses | les autres tests se connectent en boucle depuis une adresse |
| `MonitoringTests`, `PublicEndpointErrorTests`, `TaskRoomsWebSocketTests` | vrai serveur, port de gestion séparé | deux ports, `/error`, WebSocket |

S'y ajoutent les trois slices et un mini contexte `@SpringJUnitConfig` dans `UserStatusLookupTest`, qui vérifie le proxy Spring de `@Cacheable` sans démarrer l'application. Le plafond de 12 suffit. Le défaut de 32 avait gardé une douzaine de contextes complets ouverts et saturé la mémoire.

Réglages de `src/test/resources/config/application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `security.jwt.secret`, `webhooks.encryption-key` | clés de test | démarrer sans `.env`, en CI comme en local |
| `task.reminders.enabled`, `identity.retention.enabled`, `identity.token-purge.enabled` | `false` | les tests appellent les services (guide 21) |
| `media.cleanup.enabled` | `false` | idem |
| `rate-limit.purge-enabled`, `messaging.outbox.purge-enabled` | absents, donc `true` | purges horaires inoffensives (guide 21) |
| `exports.purge-enabled`, `exports.recovery-enabled`, `webhooks.retry-enabled`, `webhooks.purge-enabled` | `false` | idem pour exports et webhooks |
| `rate-limit.enabled` | `false` | `RateLimitTests` le rallume dans son contexte |
| `messaging.outbox.poll-interval` | `PT1H` | ne pas publier les lignes d'un autre contexte (guide 30) |
| `webhooks.require-https`, `outbound-http.allowed-addresses` | `false`, `127.0.0.1/32` | appeler MockWebServer en HTTP local (guide 31) |
| `spring.http.serviceclient.webhooks.read-timeout` | `2s` | ici et non sur la classe, qui créerait un contexte |
| `spring.testcontainers.beans.startup` | `parallel` | démarrer les conteneurs d'un contexte en parallèle (guide 30) |
| `hibernate.session_factory.statement_inspector` | `SqlStatementCounter` | compter les requêtes sans nouveau contexte (guide 31) |

Les tests d'intégration ne sont pas `@Transactional` : ils valident leurs données. Les slices annulent chaque test. Celles qui doivent valider (verrous vus d'une autre connexion, concurrence) passent par `Transactions.inNewTransaction` ou `@Transactional(propagation = NOT_SUPPORTED)`, puis nettoient leurs lignes.

Pour ajouter une classe de test, on choisit la couche, on pose son annotation seule et on crée des données uniques. Un réglage différent va dans l'annotation d'une famille.

## Voir aussi

- [30. Testcontainers](30-testcontainers.md) : les conteneurs des contextes et leur réutilisation.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : horloge, Mailpit, compteur de requêtes.
- [08. Autorisations](08-autorisations.md) : les beans de propriétaire appelés par la SpEL.
- [Cas pratique : pourquoi les contextes de test se multiplient](cas-pratiques/contextes-de-test.md).
- Documentation officielle : [cache des contextes](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html), [tests avec Spring Boot](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html), [`@MockitoBean`](https://docs.spring.io/spring-framework/reference/testing/annotations/integration-spring/annotation-mockitobean.html).

[Sommaire](README.md) | [< 28. Uploads et drivers de stockage](28-uploads-stockage.md) | [30. Testcontainers >](30-testcontainers.md)

[integration]: ../../src/test/java/io/julienmetral/tasks/support/IntegrationTest.java
[web]: ../../src/test/java/io/julienmetral/tasks/support/WebLayerTest.java
[callers]: ../../src/test/java/io/julienmetral/tasks/support/WebCallers.java
[jdbc]: ../../src/test/java/io/julienmetral/tasks/support/JdbcSliceTest.java
[jpa]: ../../src/test/java/io/julienmetral/tasks/support/RepositoryTest.java
[dead-letter]: ../../src/test/java/io/julienmetral/tasks/support/DeadLetterIntegrationTest.java
[transactions]: ../../src/test/java/io/julienmetral/tasks/support/Transactions.java
[spring-properties]: ../../src/test/resources/spring.properties
[test-yaml]: ../../src/test/resources/config/application.yaml
