# 30. Testcontainers

[Sommaire](README.md) | [< 29. Couches de tests et contextes](29-tests-couches-contextes.md) | [31. Outils de test et tests garde-fous >](31-outils-tests-garde-fous.md)

## Contexte

- **Problème** : tester contre une base H2 ou un faux broker laisse passer ce qui ne marche que sur le vrai service (SQL natif Postgres, `SKIP LOCKED`, `uuidv7()`, files RabbitMQ, dead letters). On veut le vrai service, sans l'installer sur chaque machine.
- **Quand l'utiliser** : slices de données et tests d'intégration ; lancer l'application en local sur des services jetables.
- **Quand s'en passer** : tests unitaires et slice web (aucun service) ; machine sans Docker (les tests qui en ont besoin ne peuvent pas tourner).
- **Dans ce dépôt** : Postgres, RabbitMQ, Mailpit, RustFS et ClamAV en conteneurs ; quatre sont réutilisés d'une exécution à l'autre, RabbitMQ jamais.
- **Prérequis** : guides 29 (contextes de test) et 18 (Liquibase). Versions : Spring Boot 4.1 (`spring-boot-testcontainers`), Testcontainers 2.0 (2.0.5 géré par Boot 4.1.1), Docker.

Les façons d'avoir un service en test :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Base embarquée (H2) | une base en mémoire dans la JVM | jamais pour du SQL propre à Postgres ; prototypes |
| Conteneurs en beans Spring (`@ServiceConnection`) | Spring Boot les démarre avec le contexte | cas courant ; partagés par les classes du contexte |
| `@Testcontainers` + `@Container` (JUnit) | JUnit démarre un conteneur par classe (champ `static`) | tests sans Spring, ou un conteneur propre à une classe |

## Concepts clés

### Vocabulaire

- **Conteneur** : un processus isolé lancé depuis une image Docker (`postgres:18`), dont les ports sont exposés sur un port aléatoire de la machine.
- **Port mappé** : le port aléatoire de la machine qui mène au port du conteneur ; on le lit avec `getMappedPort(...)`.
- **Stratégie d'attente** (wait strategy) : la condition qui dit qu'un conteneur est prêt (un port, une URL HTTP, une ligne de log).
- **Connexion de service** (`@ServiceConnection`) : Spring Boot lit l'hôte, le port et les identifiants du conteneur et les donne à l'auto-configuration, sans propriété à écrire.
- **Réutilisation** (reuse) : un conteneur marqué réutilisable survit à la JVM ; le suivant de même définition le reprend au lieu d'en démarrer un.
- **Ryuk** : le conteneur compagnon de Testcontainers qui supprime les conteneurs d'une session à la fin de la JVM.

### Un conteneur, du bean au test

Chaque contexte de test démarre ses conteneurs (guide 29 pour le cache des contextes) :

```
 contexte de test qui démarre
          |
          v
 bean PostgreSQLContainer  -- @ServiceConnection
          |  Spring Boot démarre le conteneur
          v
 docker run postgres:18 -->  port 5432 mappé sur 32771
          |  attente : la base répond
          v
 JdbcConnectionDetails (url, utilisateur, mot de passe)
          |
          v
 DataSource, Liquibase, JPA : l'application démarre
          |
          v
 contexte fermé --> conteneur arrêté (sauf s'il est réutilisable)
```

- Spring Boot démarre chaque bean conteneur avant les beans qui en dépendent, puis l'arrête quand le contexte se ferme. Exception : un conteneur réutilisable, avec la réutilisation activée, n'est jamais arrêté par Spring Boot.
- `@ServiceConnection` marche pour les conteneurs que Spring Boot connaît (Postgres par `JdbcDatabaseContainer`, `RabbitMQContainer`, ...). Pour une image quelconque (`GenericContainer`), on écrit les propriétés soi-même avec un `DynamicPropertyRegistrar`.
- Les conteneurs d'un contexte démarrent un par un par défaut ; `spring.testcontainers.beans.startup=parallel` les démarre en même temps.

### La réutilisation

Deux exécutions successives, et le même conteneur :

```
 exécution 1                         exécution 2
 bean postgres (withReuse)           bean postgres (withReuse)
      |                                   |
      v                                   v
 hash de la définition = A1          hash de la définition = A1
      |                                   |
      v                                   v
 aucun conteneur A1 --> créé         conteneur A1 trouvé --> repris
      |                                   (démarrage sauté)
      v
 fin des tests : le conteneur reste
```

- Le hash couvre toute la commande de création : image, variables, ports, labels, fichiers copiés. Une seule différence donne un autre conteneur : une slice qui déclare son propre Postgres, même avec un autre label, en démarre un second.
- La réutilisation demande deux accords : `withReuse(true)` dans le code, et `testcontainers.reuse.enable=true` dans `~/.testcontainers.properties` ou la variable `TESTCONTAINERS_REUSE_ENABLE=true`. Jamais dans un fichier du classpath du projet : Testcontainers ne le lit pas pour ce réglage.
- Sans l'accord de l'environnement, `withReuse(true)` est ignoré avec un warning, et le conteneur est jetable comme les autres.
- La variable d'environnement passe avant le fichier : `TESTCONTAINERS_REUSE_ENABLE=false` coupe la réutilisation pour une exécution.
- Un conteneur réutilisé n'est confié ni à Ryuk ni à Spring Boot : il reste après les tests, et on le supprime à la main (un label commun aide).

### Ce que la réutilisation change pour les tests

Une seule base sert tous les contextes, et toutes les exécutions :

```
 contexte A (intégration)   contexte B (slice JPA)   exécution d'hier
          \                        |                      /
           \                       v                     /
            +----------->  une seule base Postgres  <---+
                           (lignes validées de tous)
```

- Les tests qui valident (commit) laissent leurs lignes : la base garde les données de tous les contextes et de toutes les exécutions.
- Il faut donc des valeurs uniques (UUID aléatoires dans les emails et les références) et jamais d'assertion sur un total global. Une requête qui balaie toute la table se teste sur des lignes datées de 2000, pour que le seuil n'atteigne que celles du test.
- Liquibase tourne à chaque contexte sur la base déjà migrée : rien à appliquer. Une migration modifiée après coup donne une erreur de checksum : on supprime les conteneurs réutilisés.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                    dépendances (étape 1)
src/test/java/com/example/invoices/
  PostgresContainers.java                  Postgres en bean (étape 2)
  InvoiceRepositoryTests.java              un test (étapes 3 et 8)
  TestInvoicesApplication.java             l'application en local (étape 4)
  ServiceContainers.java                   RabbitMQ, Mailpit (étape 5)
  ContainersTests.java                     la vérification (étape 9)
src/test/resources/config/application.yaml démarrage parallèle (étape 6)
~/.testcontainers.properties               réutilisation (étape 7)
```

### Un service : Postgres

**Étape 1. Ajouter les dépendances.** Spring Boot gère les versions de Testcontainers ; il faut un module par service.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>   <!-- @ServiceConnection, cycle de vie -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>    <!-- Testcontainers 2 : préfixe testcontainers- -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-rabbitmq</artifactId>      <!-- pour l'étape 5 -->
    <scope>test</scope>
</dependency>
```

**Étape 2. Déclarer le conteneur en bean.** `@ServiceConnection` remplace `spring.datasource.*`, et la classe se partage entre toutes les classes de test.

```java
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainers {

    @Bean
    @ServiceConnection                                   // url, utilisateur, mot de passe : rien à écrire
    PostgreSQLContainer postgresContainer() {           // org.testcontainers.postgresql (Testcontainers 2)
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));   // même tag qu'en production
    }
}
```

**Étape 3. L'importer dans les tests.** Liquibase migre le conteneur au démarrage du contexte, et le test tourne contre le vrai Postgres. Dans un vrai projet, l'import va dans la méta-annotation de la famille de tests (guide 29).

```java
@DataJpaTest
@Import(PostgresContainers.class)                       // dans la méta-annotation de la famille (guide 29)
class InvoiceRepositoryTests {

    @Autowired InvoiceRepository invoiceRepository;      // Invoice : le domaine d'exemple
    @Autowired TestEntityManager entityManager;

    @Test
    void referencesAreUnique() {
        String reference = "INV-" + UUID.randomUUID();   // unique : la base peut être partagée (étape 8)
        entityManager.persistAndFlush(Invoice.unpaid(reference));

        assertThatThrownBy(() -> entityManager.persistAndFlush(Invoice.unpaid(reference)))
                .isInstanceOf(PersistenceException.class);   // jakarta.persistence
    }
}
```

**Étape 4. Lancer l'application en local sur les conteneurs.** Un `main` de test reprend celui de l'application et ajoute la configuration des conteneurs : aucun service à installer.

```java
public class TestInvoicesApplication {

    public static void main(String[] args) {
        SpringApplication.from(InvoicesApplication::main)    // le main de src/main
                .with(PostgresContainers.class)
                .run(args);
    }
}
```

```bash
./mvnw spring-boot:test-run      # démarre TestInvoicesApplication, avec le classpath de test
```

### Plusieurs services et réutilisation

**Étape 5. Ajouter un service sans connexion automatique.** Spring Boot ne connaît pas l'image Mailpit : un `DynamicPropertyRegistrar` écrit les propriétés à partir du conteneur, injecté pour être démarré avant.

```java
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresContainers.class)
public class ServiceContainers {

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {                // org.testcontainers.rabbitmq
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.6-management"));
    }

    @Bean
    GenericContainer<?> mailpitContainer() {             // capte les mails ; API HTTP sur 8025
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.2"))
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/livez").forPort(8025));
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add("spring.mail.host", mailpitContainer::getHost);
            registry.add("spring.mail.port", () -> mailpitContainer.getMappedPort(1025));
        };
    }
}
```

**Étape 6. Démarrer les conteneurs en parallèle.** Par défaut, Spring Boot les démarre l'un après l'autre ; en parallèle, le démarrage du contexte dure autant que le conteneur le plus lent.

```yaml
# src/test/resources/config/application.yaml
spring:
  testcontainers:
    beans:
      startup: parallel          # défaut : sequential
```

**Étape 7. Réutiliser les conteneurs entre les exécutions.** Le démarrage de Postgres et des autres est sauté, et tous les contextes partagent un seul exemplaire de chacun.

```java
@Bean
@ServiceConnection
PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
            .withLabel("com.example.invoices.test-container", "true")   // pour les retrouver et les supprimer
            .withReuse(true);
}
```

```properties
# ~/.testcontainers.properties : l'accord de la machine, jamais dans le dépôt
testcontainers.reuse.enable=true
```

```bash
# supprimer les conteneurs réutilisés (migration modifiée, base à vider)
docker rm -f $(docker ps -aq --filter label=com.example.invoices.test-container)
```

**Étape 8. Écrire des tests qui supportent une base partagée.** Avec la réutilisation, la base contient les lignes des autres contextes et des exécutions précédentes.

```java
@Test
void purgeDeletesOnlyInvoicesOlderThanTheCutoff() {
    // Daté de 2000 : avant tout ce que les autres tests écrivent, le seuil n'atteint que ces lignes
    Instant longAgo = Instant.parse("2000-06-01T12:00:00Z");
    UUID old = insertInvoice(longAgo);          // insertInvoice, exists, invoiceQueries : outils du test
    UUID recent = insertInvoice(longAgo.plus(Duration.ofDays(60)));

    invoiceQueries.deleteOlderThan(longAgo.plus(Duration.ofDays(30)));

    assertThat(exists(old)).isFalse();          // jamais assertThat(count()).isEqualTo(...)
    assertThat(exists(recent)).isTrue();
}
```

**Étape 9. Vérifier.** Un test qui démarre le contexte complet prouve que les conteneurs démarrent, que les propriétés arrivent et que Liquibase passe.

```java
@SpringBootTest
@Import(ServiceContainers.class)
class ContainersTests {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void applicationRunsOnPostgres18() {
        assertThat(jdbcTemplate.queryForObject("SHOW server_version", String.class)).startsWith("18");
    }
}
```

### Référence rapide

Les méthodes d'un conteneur :

| Méthode | Rôle |
|---|---|
| `withExposedPorts(1025, 8025)` | ports du conteneur à mapper sur la machine |
| `getHost()`, `getMappedPort(8025)` | où joindre le conteneur depuis les tests |
| `waitingFor(Wait.forHttp("/livez").forPort(8025))` | prêt quand l'URL répond |
| `waitingFor(Wait.forLogMessage(".*started.*", 1))` | prêt quand la ligne apparaît dans les logs |
| `withEnv("KEY", "value")` | variable d'environnement du conteneur |
| `withLabel(key, value)`, `withReuse(true)` | marquer, rendre réutilisable |

Les réglages :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.testcontainers.beans.startup` | `sequential` | `parallel` démarre les conteneurs d'un contexte ensemble |
| `testcontainers.reuse.enable` (`~/.testcontainers.properties`) | `false` | accord de la machine pour la réutilisation |
| `TESTCONTAINERS_REUSE_ENABLE` (variable) | absente | idem, et l'emporte sur le fichier |

Limites de cette version : chaque contexte de test démarre ses conteneurs non réutilisables, et une base réutilisée garde les données et le schéma de toutes les exécutions.

## Concepts avancés

### Sous le capot : qui démarre et qui arrête

- Spring Boot démarre les beans `Startable` (les conteneurs) dès leur création, en séquence ou en parallèle selon `spring.testcontainers.beans.startup`.
- À la fermeture du contexte, il appelle `close()` sur chaque conteneur, sauf un conteneur réutilisable dont la machine a activé la réutilisation.
- Pour la réutilisation, Testcontainers calcule un hash de la commande de création (et des fichiers copiés), cherche un conteneur en marche portant ce hash en label, et le reprend ; sinon il en crée un, marqué de ce hash. Un conteneur réutilisable n'est pas enregistré auprès de Ryuk.
- La définition doit donc être identique partout : une seule classe de configuration par conteneur, pas une copie par slice.

### Plusieurs contextes : un broker par contexte

Les services réutilisés sont uniques, mais chaque contexte a son broker :

```
 contexte 1 ----+                      +---- RabbitMQ 1 (jetable)
 contexte 2 ----+--> Postgres, Mailpit,|---- RabbitMQ 2 (jetable)
 contexte 3 ----+    RustFS, ClamAV    +---- RabbitMQ 3 (jetable)
                     (réutilisés, un seul de chaque)
```

- Un contexte qui fait échouer ses listeners exprès enverrait en dead letter les messages des autres : d'où un broker par contexte.
- La base, elle, est commune : un job planifié ou un poller actif dans un contexte agit sur les lignes de tous les autres (guide 29 pour les réglages qui les éteignent, et le cas pratique en fin de guide).

## Pièges à éviter

### Avec Testcontainers et Spring Boot

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Tester sur H2 ce qui tourne sur Postgres | le SQL natif et les verrous passent en test et cassent en production | Tester contre l'image de production |
| Laisser une image en `latest` | une nouvelle version change le comportement sans commit | Épingler le tag, le même que `compose.yaml` |
| Utiliser `@DynamicPropertySource` dans la classe | sa clé de cache est propre : un contexte de plus | Écrire un `DynamicPropertyRegistrar` en bean partagé |
| Oublier la stratégie d'attente d'un `GenericContainer` | le test part avant que le service réponde | Poser `waitingFor(...)` sur une URL ou une ligne de log |

### Avec la réutilisation

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Activer la réutilisation dans un fichier du dépôt | Testcontainers l'ignore : conteneurs recréés | Utiliser `~/.testcontainers.properties` ou la variable |
| Déclarer une seconde définition du même service | hash différent : un second conteneur démarre | Garder une seule classe par conteneur, importée partout |
| Asserter sur un total (`count()`) | les lignes des autres contextes le faussent | Asserter sur ses propres lignes, aux valeurs uniques |
| Modifier une migration déjà appliquée | Liquibase refuse la base réutilisée (checksum) | Supprimer les conteneurs par leur label |
| Rendre réutilisable un broker que des tests font échouer | ses listeners consomment les messages des autres contextes | Garder ce conteneur jetable |
| Changer de branche avec une base réutilisée | la branche tourne sur le schéma et les lignes d'une autre | Relancer avec `TESTCONTAINERS_REUSE_ENABLE=false` |

## En résumé

**À retenir :** les services de test sont de vrais conteneurs déclarés en beans, branchés par `@ServiceConnection` ou un `DynamicPropertyRegistrar` ; leur réutilisation accélère tout mais partage la base, donc chaque test écrit des données uniques.

- [ ] Les images de test aux mêmes tags que `compose.yaml` et la production
- [ ] Une classe de configuration par conteneur, importée par les annotations de test
- [ ] `@ServiceConnection` quand Spring Boot connaît le service, sinon `DynamicPropertyRegistrar`
- [ ] Une stratégie d'attente sur chaque `GenericContainer`
- [ ] `spring.testcontainers.beans.startup: parallel`
- [ ] `withReuse(true)` et un label commun sur les conteneurs partageables
- [ ] La réutilisation activée par la machine ou la CI, jamais par le dépôt
- [ ] Des valeurs uniques, aucune assertion sur un total global
- [ ] Un `main` de test et `spring-boot:test-run` pour lancer l'application en local

## Dans ce dépôt

Ce que démarre un contexte d'intégration complet :

```
 @IntegrationTest (guide 29)
      |
      v
 TestcontainersConfiguration -- @Import --> PostgresTestcontainersConfiguration
      |                                          postgres:18, réutilisé
      +--> rabbitmq:4.3.6-management   @ServiceConnection, jamais réutilisé
      +--> axllent/mailpit:v1.31.2     DynamicPropertyRegistrar, réutilisé
      +--> rustfs/rustfs:1.0.0         DynamicPropertyRegistrar, réutilisé
      +--> clamav/clamav:1.5.4-debian  DynamicPropertyRegistrar, réutilisé
```

| Fichier | Rôle |
|---|---|
| [`PostgresTestcontainersConfiguration`][postgres] | Postgres seul, pour les slices JDBC et JPA ; la seule définition du conteneur |
| [`TestcontainersConfiguration`][containers] | tous les services de l'intégration, le label `REUSABLE_LABEL`, la signature EICAR |
| [`TestTasksApplication`][test-app] | `SpringApplication.from(TasksApplication::main).with(TestcontainersConfiguration.class)` |
| [`config/application.yaml`][test-yaml] | `spring.testcontainers.beans.startup: parallel` |
| [`compose.yaml`][compose] | les mêmes images pour le développement |

Les conteneurs :

| Conteneur | Branchement | Réutilisé |
|---|---|---|
| `postgres:18` | `@ServiceConnection` | oui |
| `rabbitmq:4.3.6-management` | `@ServiceConnection` | non |
| `axllent/mailpit:v1.31.2` | `spring.mail.host`, `spring.mail.port`, `mailpit.api-url` ; attente `GET /livez` sur 8025 | oui |
| `rustfs/rustfs:1.0.0` | propriétés `storage.*` (endpoint, clés de test, bucket) ; attente `GET /health` sur 9000 | oui |
| `clamav/clamav:1.5.4-debian` | `antivirus.enabled`, `antivirus.host`, `antivirus.port` (3310) ; attente du log `socket found, clamd started` | oui |

Ce qui est propre au dépôt :

- Chaque conteneur réutilisable porte le label `io.julienmetral.tasks.test-container=true` (`TestcontainersConfiguration.REUSABLE_LABEL`). La commande `docker rm -f $(docker ps -aq --filter label=io.julienmetral.tasks.test-container)` (README, « Keep the test containers between runs ») ne touche donc aucun autre conteneur.
- RabbitMQ n'est jamais réutilisé : les tests de dead letter font échouer les collaborateurs des listeners, et sur un broker partagé ces listeners consommeraient (et enverraient en dead letter) les messages de tous les autres contextes. Chaque contexte d'intégration démarre donc son RabbitMQ.
- ClamAV charge seulement une signature EICAR (`eicar.ndb`, copiée avec `withCopyToContainer`, `CLAMD_CONF_DatabaseDirectory=/var/lib/clamav-test`), et freshclam est coupé (`CLAMAV_NO_FRESHCLAMD=true`) : environ 14 Mo au lieu d'environ 1 Go par clamd avec la base complète, et aucun accès réseau. Avec la base complète, un clamd par contexte avait saturé la mémoire de la machine. Le nom rapporté est `TestcontainersConfiguration.EICAR_THREAT`, `Eicar-Test-Signature.UNOFFICIAL` (le suffixe vient de la base non signée).
- Les tags de test sont ceux de `compose.yaml`. Dependabot met à jour `compose.yaml` (écosystème `docker-compose`), pas le code Java : on recopie le tag à la main.
- La CI active la réutilisation (`TESTCONTAINERS_REUSE_ENABLE: "true"` dans le job « Build and test » de `.github/workflows/ci.yml`) : les défauts d'isolation y apparaissent aussi.
- La base réutilisée sert toutes les branches et tous les worktrees de la machine. Une branche plus ancienne tourne alors sur le schéma et les lignes d'une plus récente. Quand une suite échoue seulement sur des tables qu'une autre branche ajoute, on relance ces classes avec `TESTCONTAINERS_REUSE_ENABLE=false` (conteneurs neufs) avant de suspecter la branche.
- `./mvnw spring-boot:test-run` lance `TestTasksApplication` sur ces conteneurs, les mêmes que ceux des tests si la réutilisation est activée.
- Les fixtures des requêtes qui balaient une table sont datées de 2000 (par exemple `RateLimitQueriesTests.WINDOW = 2000-06-01T12:00:00Z`). Les tests qui valident sur une autre connexion nettoient leurs lignes (`Transactions.inNewTransaction`, guide 29).

Les tests : [`TasksApplicationTests`][context-loads] (`contextLoads`) prouve que l'application démarre sur tous les conteneurs ; `MonitoringTests` vérifie que `db`, `rabbit`, `mail`, `storage` et `antivirus` sont UP (guide 32).

Pour ajouter un service : un bean dans `TestcontainersConfiguration`, au tag de `compose.yaml`, avec `@ServiceConnection` ou un `DynamicPropertyRegistrar`, une stratégie d'attente, et `withLabel(REUSABLE_LABEL, "true").withReuse(true)` sauf si des tests le font échouer exprès.

## Voir aussi

- [29. Couches de tests et contextes](29-tests-couches-contextes.md) : combien de contextes, donc combien de jeux de conteneurs.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : `Mailpit` pour lire les mails capturés, l'horloge de test.
- [18. Migrations Liquibase](18-migrations-liquibase.md) : les migrations appliquées au conteneur.
- [Cas pratique : pourquoi les contextes de test se multiplient](cas-pratiques/contextes-de-test.md).
- Documentation officielle : [Testcontainers avec Spring Boot](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html), [conteneurs réutilisables](https://java.testcontainers.org/features/reuse).

[Sommaire](README.md) | [< 29. Couches de tests et contextes](29-tests-couches-contextes.md) | [31. Outils de test et tests garde-fous >](31-outils-tests-garde-fous.md)

[postgres]: ../../src/test/java/io/julienmetral/tasks/PostgresTestcontainersConfiguration.java
[containers]: ../../src/test/java/io/julienmetral/tasks/TestcontainersConfiguration.java
[test-app]: ../../src/test/java/io/julienmetral/tasks/TestTasksApplication.java
[context-loads]: ../../src/test/java/io/julienmetral/tasks/TasksApplicationTests.java
[test-yaml]: ../../src/test/resources/config/application.yaml
[compose]: ../../compose.yaml
