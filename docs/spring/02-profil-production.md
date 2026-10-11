# 02. Profil de production

[Sommaire](README.md) | [< 01. Configuration et `@ConfigurationProperties`](01-configuration-proprietes.md) | [03. Contrôleurs MVC >](03-controleurs-mvc.md)

## Contexte

- **Problème** : déployer le même jar en recette et en production, sans qu'un défaut `localhost` se glisse dans un déploiement. Arrêter le démarrage quand un réglage manque, plutôt qu'au premier mail ou au premier upload.
- **Quand l'utiliser** : dès que l'application tourne ailleurs que sur un poste de développement.
- **Quand s'en passer** : une démonstration jetable, ou un outil qui ne tourne qu'en local.
- **Dans ce dépôt** : un seul profil `prod` (`application-prod.yaml`) pour tout environnement déployé, recette comprise. Les environnements ne diffèrent que par leurs variables. `RequiredPropertiesCheck` vérifie les réglages obligatoires au démarrage. Les logs sont en JSON ECS, Swagger UI est coupé, l'arrêt propre dure 20 s au plus.
- **Prérequis** : guide 01 (configuration, placeholders). Versions : Spring Boot 4.1, Spring Framework 7.

Les façons de faire varier la configuration d'un environnement à l'autre :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Un profil par environnement (`staging`, `prod`) | un fichier par environnement | à éviter : les fichiers divergent |
| Un profil `prod` et des variables | un seul fichier pour tout déploiement, les valeurs viennent de l'environnement | le cas courant, celui du dépôt |
| Fichiers montés (`configtree:`) | Kubernetes monte ConfigMap et Secret, un fichier par clé | secrets gérés par la plateforme |

## Concepts clés

### Vocabulaire

- **Profil** : un nom (`prod`) qui active `application-prod.yaml` et les beans marqués `@Profile("prod")`.
- **Réglage obligatoire** : un réglage sans valeur sûre en production (secret, URL de base, hôte SMTP). Il doit venir de l'environnement.
- **Arrêt propre** (graceful shutdown) : à l'arrêt, le serveur refuse les nouvelles requêtes et laisse finir celles en cours, dans une limite de temps.
- **Période de grâce** : le temps que l'orchestrateur (Kubernetes, Docker) attend après SIGTERM avant de tuer le processus (SIGKILL).
- **Logs structurés** : un objet JSON par ligne de log, lu par une plateforme de logs plutôt que par un humain.

### Un profil, des variables

Le même jar sert partout. Seules les variables changent d'un environnement à l'autre :

```
              application.jar (le même partout)
     application.yaml          défauts de développement
     application-prod.yaml     défauts vides, réglages de production
                     |
        +------------+-------------+
        |                          |
        v                          v
   recette                    production
   SPRING_PROFILES_ACTIVE=prod  SPRING_PROFILES_ACTIVE=prod
   MAIL_HOST=smtp.staging...    MAIL_HOST=smtp.example.com
   API_DOCS_ENABLED=true        (rien : documentation coupée)
```

- Un fichier propre à un profil l'emporte toujours sur les fichiers sans profil. `application-prod.yaml` passe donc devant `application.yaml` et devant le `classpath:/config/application.yaml` des tests. Le profil ne doit jamais être actif en test (voir les pièges).
- Un profil par environnement duplique les réglages, et la recette cesse de tester ce que la production exécute.

### Le réglage manquant

Voici ce qui arrive à `mail.from` quand sa variable manque, selon la façon de l'écrire :

```
  MAIL_FROM absente
        |
        +-- défaut localhost ---------> démarre, envoie vers rien
        |
        +-- ${MAIL_FROM} sans défaut --> démarre, l'adresse vaut "${MAIL_FROM}"
        |   (lu par un record)            (guide 01)
        |
        +-- ${MAIL_FROM:} + contrôle --> arrêt au démarrage :
                                         "Required settings without a value:
                                          mail.from, ..."
```

- Dans le profil `prod`, un réglage sans valeur sûre reçoit un défaut vide (`${MAIL_FROM:}`) et une entrée dans la liste des obligatoires. Un défaut `localhost` laisserait un déploiement démarrer contre rien.
- Le contrôle est un `BeanFactoryPostProcessor`. Il passe une fois la configuration lue, avant la création de tout bean ordinaire. La base, le broker et Liquibase ne sont donc pas encore touchés.
- Il signale tous les réglages manquants dans un seul message, pour corriger un déploiement en un passage.
- `ConfigurableEnvironment.setRequiredProperties(...)` puis `validateRequiredProperties()`, fournis par Spring Framework, ne refusent qu'une clé absente (`null`). Un `${VAR:}` vide passe. D'où le contrôle maison, qui refuse aussi les valeurs vides et non résolues.

### L'arrêt propre

La chronologie d'un arrêt, du signal à la fin du processus :

```
  t = 0     orchestrateur --> SIGTERM
              |
              v
            Tomcat refuse les nouvelles connexions
            les requêtes en cours continuent
              |
  t <= 20 s   requêtes finies, ou délai de l'application écoulé
              |
              v
            le contexte Spring se ferme (pools, broker, jobs)
              |
  t = 30 s  orchestrateur --> SIGKILL si le processus vit encore
```

- L'arrêt propre est actif par défaut dans Spring Boot 4 (`server.shutdown` vaut `graceful`). Tomcat cesse d'accepter au niveau du réseau, et refuse aussi les requêtes sur les connexions inactives déjà ouvertes.
- `spring.lifecycle.timeout-per-shutdown-phase` (`30s` par défaut) borne l'attente de chaque phase d'arrêt. Le serveur web s'arrête dans la première.
- Règle : le délai de l'application reste sous la période de grâce de l'orchestrateur. Kubernetes attend 30 s par défaut (`terminationGracePeriodSeconds`). Avec deux valeurs égales, le kill peut tomber pendant que le contexte se ferme encore.
- Un flux SSE retiendrait l'arrêt propre jusqu'à son délai. Le guide 24 ferme donc les flux sur `ContextClosedEvent`.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/resources/
  application.yaml                      défauts de développement (guide 01)
  application-prod.yaml                 le profil de production (étapes 1, 3, 6)
src/main/java/.../config/
  RequiredSettingsCheck.java            le contrôle au démarrage (étape 4)
  StartupChecksConfiguration.java       le déclare (étape 5)
Dockerfile ou manifeste du déploiement  active le profil (étape 2)
src/test/java/.../config/
  RequiredSettingsCheckTest.java        la vérification (étape 7)
```

### Un profil vérifié au démarrage

**Étape 1. Écrire le profil de production.** Il ne surcharge que ce qui diffère du développement : des défauts vides pour ce que chaque déploiement fournit, les outils de développement coupés, des logs JSON.

```yaml
# src/main/resources/application-prod.yaml
# Le profil de tout environnement déployé, recette comprise : seules les variables changent.
spring:
  main:
    banner-mode: off                  # stdout ne porte que des lignes JSON
  mail:
    host: ${MAIL_HOST:}               # vide : obligatoire (étape 3)
    port: ${MAIL_PORT:587}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.required: true

invoices:
  pdf-service-url: ${PDF_SERVICE_URL:}

springdoc:
  swagger-ui:
    enabled: ${SWAGGER_UI_ENABLED:false}
  api-docs:
    enabled: ${API_DOCS_ENABLED:false}

logging:
  structured:
    format:
      console: ecs                    # une ligne JSON par log (Elastic Common Schema)
    ecs:
      service:
        environment: ${DEPLOYMENT_ENVIRONMENT:production}
```

La bannière est déjà coupée avec des logs structurés. L'écrire dit l'intention.

**Étape 2. Activer le profil au déploiement, et seulement là.** C'est une variable du déploiement, jamais une clé dans un fichier du jar.

```dockerfile
# Dockerfile : dans l'image, jamais dans application.yaml
ENV SPRING_PROFILES_ACTIVE=prod
```

```yaml
# ou dans le manifeste Kubernetes du déploiement
env:
  - name: SPRING_PROFILES_ACTIVE
    value: prod
```

**Étape 3. Lister les réglages obligatoires.** Une seule liste, dans le profil `prod`, nomme chaque réglage qui a un défaut vide.

```yaml
# application-prod.yaml
app:
  required-settings:
    - spring.datasource.url
    - spring.datasource.password
    - spring.mail.host
    - invoices.pdf-service-url
```

**Étape 4. Écrire le contrôle.** Un `BeanFactoryPostProcessor` passe avant tout bean ordinaire. Il lit la liste avec le `Binder` et refuse une valeur vide ou non résolue.

```java
class RequiredSettingsCheck implements BeanFactoryPostProcessor {

    static final String REQUIRED_SETTINGS = "app.required-settings";

    private final Environment environment;

    RequiredSettingsCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> missing = Binder.get(environment)
                .bind(REQUIRED_SETTINGS, Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(name -> !hasValue(name))
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Required settings without a value: " + String.join(", ", missing));
        }
    }

    private boolean hasValue(String name) {
        try {
            String value = environment.getProperty(name);
            // un ${VAR} non résolu peut rester tel quel : on le refuse aussi
            return StringUtils.hasText(value) && !value.contains("${");
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return false;                     // getProperty échoue sur un ${VAR} sans défaut
        }
    }
}
```

**Étape 5. Le déclarer par une méthode `static`.** Une méthode `@Bean` non statique qui renvoie un `BeanFactoryPostProcessor` force la création de sa classe de configuration avant les autres post-processeurs.

```java
@Configuration
public class StartupChecksConfiguration {

    @Bean
    static RequiredSettingsCheck requiredSettingsCheck(Environment environment) {
        return new RequiredSettingsCheck(environment);
    }
}
```

**Étape 6. Régler l'arrêt propre sous la période de grâce.** Les requêtes en cours finissent avant que l'orchestrateur tue le processus.

```yaml
# application-prod.yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s   # sous les 30 s de Kubernetes
```

**Étape 7. Vérifier.** Un `ApplicationContextRunner` démarre la seule configuration, en quelques millisecondes. Retirer les variables du poste rend le test indépendant du shell.

```java
class RequiredSettingsCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StartupChecksConfiguration.class)
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    // ignorer les variables du poste
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withPropertyValues("app.required-settings=spring.mail.host,invoices.pdf-service-url");

    @Test
    void everyMissingSettingIsNamedInOneMessage() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("spring.mail.host")
                    .hasMessageContaining("invoices.pdf-service-url");
        });
    }

    @Test
    void unresolvedPlaceholderCountsAsMissing() {
        runner.withPropertyValues("spring.mail.host=${MAIL_HOST}",
                        "invoices.pdf-service-url=https://pdf.example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void settingsWithValuesLetTheContextStart() {
        runner.withPropertyValues("spring.mail.host=smtp.example.com",
                        "invoices.pdf-service-url=https://pdf.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
```

En manuel, lancer le jar avec le profil et sans variable. Le démarrage s'arrête sur une ligne qui liste tous les réglages manquants.

```bash
SPRING_PROFILES_ACTIVE=prod java -jar target/app.jar
# IllegalStateException: Required settings without a value: spring.datasource.url, ...
```

### Référence rapide

Les réglages d'exploitation de Spring Boot :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.profiles.active` | aucun | profils actifs ; à poser par `SPRING_PROFILES_ACTIVE` au déploiement |
| `server.shutdown` | `graceful` | `immediate` coupe les requêtes en cours |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | attente maximale de chaque phase d'arrêt |
| `logging.structured.format.console` | aucun | `ecs`, `logstash` ou `gelf` : logs JSON sur la console |
| `logging.structured.ecs.service.environment` | aucun | champ `service.environment` des logs ECS |
| `logging.structured.ecs.service.name` | `spring.application.name` | champ `service.name` |
| `spring.main.banner-mode` | `off` avec des logs structurés, `console` sinon | la bannière au démarrage |
| `server.forward-headers-strategy` | aucun (détection de la plateforme) | lire `X-Forwarded-For` (guide 10) |

Limites de cette version : le contrôle prouve qu'une valeur existe, pas qu'elle fonctionne. Un mauvais mot de passe échoue encore à la première connexion, ce que couvrent les sondes de santé du guide 32.

## Concepts avancés

### Sous le capot : quand tourne le contrôle

Le contrôle s'exécute entre la lecture des définitions de beans et la création des beans :

```
  SpringApplication.run
        |
        v
  Environment complet : fichiers, profil prod, variables
        |
        v
  lecture des classes de configuration (définitions de beans)
        |
        v
  BeanFactoryPostProcessor  <-- RequiredSettingsCheck : échec ici
        |
        v
  création des beans : DataSource, Liquibase, RabbitMQ, Tomcat...
```

- Le post-processeur est créé très tôt, avant toute injection par champ : la méthode `@Bean` statique reçoit l'`Environment` en paramètre et le passe au constructeur. Avec `EnvironmentAware`, le champ restait vide jusqu'à l'appel du setter, et NullAway le signale comme non initialisé.
- Avec une méthode `@Bean` non statique, Spring logue en INFO que la méthode renvoie un `BeanFactoryPostProcessor`. Dans sa classe de configuration, `@Autowired`, `@Resource` et `@PostConstruct` échouent alors.
- `Binder.get(environment).bind(..., Bindable.listOf(String.class))` lit une liste YAML ou une chaîne séparée par des virgules.
- `environment.getProperty` résout les placeholders imbriqués et lève `IllegalArgumentException` sur un `${VAR}` non résolu. Le test `contains("${")` couvre un environnement configuré pour ignorer les placeholders non résolvables. Les deux sont nécessaires.

### Plusieurs instances

- Chaque instance exécute le même jar et le même `application-prod.yaml`. Seules les variables diffèrent. Un secret partagé (le secret JWT, les clés de chiffrement) doit être identique partout, comme le dit le guide 07 pour `JWT_SECRET`.

### Surveiller

- Les lignes JSON ECS portent `service.name` (issu de `spring.application.name`) et `service.environment`. Une seule plateforme de logs sépare ainsi la recette de la production.

## Pièges à éviter

### Profils et réglages

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire `spring.profiles.active` dans un fichier du jar | les tests chargent `prod` et s'arrêtent | Activer le profil par la variable du déploiement |
| Exporter `SPRING_PROFILES_ACTIVE` dans son shell ou son IDE | les tests exigent les variables de production | Ne la poser que dans le déploiement |
| Créer un profil par environnement | les fichiers divergent, la recette ne teste plus la production | Un profil `prod`, des variables |
| Donner un défaut `localhost` en production | l'application démarre contre rien | Un défaut vide, et le réglage dans la liste |
| Compter sur `setRequiredProperties` | une valeur vide passe le contrôle | Refuser aussi les valeurs vides et non résolues |
| Déclarer le contrôle par une méthode non `static` | sa classe de configuration est créée trop tôt | Déclarer la méthode `@Bean` en `static` |

### Arrêt et exploitation

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Égaler ou dépasser la période de grâce | l'orchestrateur tue le processus pendant l'arrêt | Rester dessous : 20 s pour 30 s |
| Laisser Swagger UI et `/v3/api-docs` en production | toute la surface de l'API est publiée | Les couper dans `prod`, rallumer par variable |
| Activer `framework` sans reverse proxy qui écrase l'en-tête | les clients choisissent leur adresse (guide 10) | Garder `none` sans reverse proxy fiable |
| Garder les logs texte en production | la plateforme de logs ne sait pas découper les lignes | Activer `ecs` dans le profil |

## En résumé

**À retenir :** un seul profil `prod` pour tout déploiement, activé par une variable. Des défauts vides pour ce que chaque déploiement fournit, vérifiés avant le premier bean. Un arrêt propre plus court que la période de grâce.

- [ ] Un fichier `application-prod.yaml`, aucun profil par environnement
- [ ] `SPRING_PROFILES_ACTIVE=prod` posé par le déploiement, jamais dans un fichier du jar
- [ ] Défauts vides (`${VAR:}`) pour tout réglage sans valeur sûre en production
- [ ] Liste des réglages obligatoires, vérifiée par un `BeanFactoryPostProcessor` déclaré `static`
- [ ] Un seul message qui nomme tous les réglages manquants
- [ ] Logs JSON (ECS) et bannière coupée sous `prod`
- [ ] Swagger UI et le document OpenAPI coupés par défaut
- [ ] `timeout-per-shutdown-phase` sous la période de grâce de l'orchestrateur
- [ ] Un test du contrôle et un test du profil (aucun profil activé, liste complète)

## Dans ce dépôt

Le démarrage d'une instance déployée :

```
  image Docker : ENV SPRING_PROFILES_ACTIVE=prod
        |
        v
  application.yaml, puis application-prod.yaml par-dessus
        |
        v
  RequiredPropertiesCheck : 13 réglages lus
        |
        +-- un manque --> arrêt : "Required settings without a value: ..."
        |
        v
  Liquibase, base, RabbitMQ, Tomcat sur 8080, Actuator sur 8081
```

| Fichier | Rôle |
|---|---|
| [`application-prod.yaml`][prod-yaml] | le profil de tout environnement déployé, la liste `deployment.required-properties` |
| [`RequiredPropertiesCheck`][check] | le contrôle au démarrage, avant tout bean |
| [`DeploymentConfiguration`][deployment] | déclare le contrôle par une méthode `static` |
| [`Dockerfile`][dockerfile] | `ENV SPRING_PROFILES_ACTIVE=prod`, posé après l'entraînement AOT (guide 33) |
| [`compose.production.yaml`][compose-prod] | `stop_grace_period: 30s`, au-dessus des 20 s du profil |

Ce que change le profil `prod` (`application-prod.yaml`) :

| Réglage | Valeur sous `prod` | Rôle |
|---|---|---|
| `deployment.required-properties` | 13 clés (liste ci-dessous) | vérifiées par `RequiredPropertiesCheck` |
| `storage.driver` | `${STORAGE_DRIVER:aws-s3}` | Amazon S3 par défaut (guide 28) |
| `storage.rustfs.create-bucket` | `${S3_CREATE_BUCKET:false}` | le bucket est créé par l'infrastructure |
| `springdoc.*.enabled` | `${API_DOCS_ENABLED:false}`, `${SWAGGER_UI_ENABLED:false}` | documentation coupée, rallumable en recette |
| `spring.main.banner-mode` | `off` | stdout ne porte que du JSON |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | sous les 30 s de Kubernetes |
| `spring.mail.port` | `${MAIL_PORT:587}` | SMTP avec authentification et STARTTLS exigés (`true` par défaut) |
| `logging.structured.format.console` | `ecs` | une ligne JSON par log |
| `logging.structured.ecs.service.environment` | `${DEPLOYMENT_ENVIRONMENT:production}` | sépare recette et production dans les logs |

Les 13 clés obligatoires : `security.jwt.secret`, `webhooks.encryption-key`, `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `spring.rabbitmq.host`, `spring.mail.host`, `mail.from`, `identity.email-verification.verify-url`, `identity.password-reset.reset-url`, `exports.download-page-url`, `storage.bucket`, `antivirus.host`.

Leurs variables sont décrites dans la section « Deploy the API » du README.

Ce qui est propre au dépôt :

- Le fichier `prod` porte en tête un avertissement : jamais `spring.profiles.active` dans un fichier du jar, jamais `SPRING_PROFILES_ACTIVE` exporté globalement. Sinon `application-prod.yaml` passe devant `src/test/resources/config/application.yaml` dans les tests.
- La Javadoc de `RequiredPropertiesCheck` garde l'échec mesuré. Sans le contrôle, l'application démarrait avec un hôte de mail littéralement nommé `${MAIL_HOST}` (un `${VAR}` non résolu est lié comme texte, issue Spring Boot 18816).
- `server.forward-headers-strategy: ${FORWARD_HEADERS_STRATEGY:none}` est dans `application.yaml`, pas dans `prod`. Écrit explicitement à `none`, il coupe aussi la détection de plateforme de Spring Boot. Sans valeur, Tomcat utilise la stratégie native sur une plateforme cloud détectée, comme Kubernetes. Le guide 10 explique pourquoi.
- `SPRING_PROFILES_ACTIVE=prod` est posé dans l'image après l'entraînement AOT, qui ne doit pas exiger les réglages de production. Sans lui, l'image prendrait les défauts de développement, `localhost` compris.
- `NotificationStreams` termine les flux SSE sur `ContextClosedEvent`, avant que l'arrêt propre les attende (guide 24).
- Aucune classe du dépôt n'utilise `@Profile` : le profil ne change que des valeurs.

Les tests :

- [`ProdProfileTest`][prod-test] vérifie cinq points. Le fichier `prod` n'active aucun profil (`spring.profiles.active` et `spring.profiles.include` absents). Sa liste égale les 13 clés attendues, et le profil sans variables échoue en nommant les 13. La documentation est coupée. `storage.driver` vaut `aws-s3` et le format de console est `ecs`.
- [`RequiredPropertiesCheckTest`][check-test] couvre l'absence de liste (le contexte démarre) et les valeurs présentes (il démarre). Il couvre aussi un `${MAIL_FROM}` manquant, vide ou non résolu (échec), `${MAIL_FROM:default}` (démarre), le message unique « Required settings without a value: mail.from, spring.mail.host. » et l'échec avant tout autre bean. Les deux retirent les sources des variables et des propriétés système, car le poste peut exporter `MAIL_FROM` ou `S3_BUCKET`.

Pour ajouter un réglage obligatoire : un défaut vide dans `application-prod.yaml`, sa clé dans `deployment.required-properties`, sa clé dans `ProdProfileTest.REQUIRED_PROPERTIES`, sa variable dans le tableau « Deploy the API » du README.

## Voir aussi

- [01. Configuration et `@ConfigurationProperties`](01-configuration-proprietes.md) : placeholders, sources de propriétés, `${VAR}` gardé tel quel.
- [10. Rate limiting](10-rate-limiting.md) : `server.forward-headers-strategy` et l'adresse du client.
- [32. Actuator, sondes et métriques](32-actuator-metriques.md) : sondes de vie et de disponibilité pendant le démarrage et l'arrêt.
- [33. Image Docker, build et CI](33-docker-build-ci.md) : le profil posé dans l'image, `compose.production.yaml`.
- Documentation officielle : [profils](https://docs.spring.io/spring-boot/reference/features/profiles.html), [arrêt propre](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html), [logs structurés](https://docs.spring.io/spring-boot/reference/features/logging.html).

[Sommaire](README.md) | [< 01. Configuration et `@ConfigurationProperties`](01-configuration-proprietes.md) | [03. Contrôleurs MVC >](03-controleurs-mvc.md)

[prod-yaml]: ../../src/main/resources/application-prod.yaml
[check]: ../../src/main/java/io/julienmetral/tasks/config/RequiredPropertiesCheck.java
[deployment]: ../../src/main/java/io/julienmetral/tasks/config/DeploymentConfiguration.java
[dockerfile]: ../../Dockerfile
[compose-prod]: ../../compose.production.yaml
[prod-test]: ../../src/test/java/io/julienmetral/tasks/config/ProdProfileTest.java
[check-test]: ../../src/test/java/io/julienmetral/tasks/config/RequiredPropertiesCheckTest.java
