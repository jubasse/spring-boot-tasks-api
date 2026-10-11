# 01. Configuration et `@ConfigurationProperties`

[Sommaire](README.md) | [02. Profil de production >](02-profil-production.md)

## Contexte

- **Problème** : sortir du code ce qui change d'un poste à l'autre (adresses, secrets, délais, limites). Typer ces valeurs, et refuser une valeur fausse au démarrage plutôt qu'à la première requête.
- **Quand l'utiliser** : pour tout réglage. Dès qu'une fonctionnalité en a plusieurs, un record `@ConfigurationProperties` par bloc.
- **Quand s'en passer** : une constante qui ne change jamais reste dans le code. Un réglage modifiable sans redémarrage relève de la base ou d'un service de configuration.
- **Dans ce dépôt** : un bloc YAML par fonctionnalité dans `application.yaml`, un record validé par bloc, les secrets locaux dans `.env`, et des beans choisis par configuration (driver de stockage, jobs, antivirus).
- **Prérequis** : aucun. Versions : Spring Boot 4.1, Hibernate Validator 9.1.

Trois façons de lire un réglage :

| Façon | Ce qu'on obtient | Quand la choisir |
|---|---|---|
| `@Value("${...}")` | une valeur convertie, sans validation | un réglage isolé, lu par un seul bean |
| Record `@ConfigurationProperties` | un bloc typé, validé au démarrage, documenté dans l'IDE | un groupe de réglages, le cas courant |
| `Environment.getProperty(...)` | une chaîne lue à la demande | du code d'infrastructure qui lit des clés calculées |

## Concepts clés

### Vocabulaire

- **Propriété** : une clé (`storage.bucket`) et sa valeur.
- **Source de propriétés** : un endroit où Spring lit des propriétés : fichier YAML, variable d'environnement, option `-D`, argument de ligne de commande.
- **Placeholder** : `${NOM:défaut}`, remplacé par la valeur de `NOM`, ou par le défaut quand `NOM` n'existe pas.
- **Binding** : la conversion de toutes les propriétés d'un préfixe en un objet Java typé.
- **Bean conditionnel** : un bean que Spring ne crée que si une condition est remplie, par exemple une propriété qui vaut `true`.

### D'où viennent les valeurs

Spring empile les sources. Pour une même clé, la plus haute gagne :

```
  arguments de ligne de commande     --storage.bucket=media     la plus forte
               |
               v
  propriétés système Java            -Dstorage.bucket=media
               |
               v
  variables d'environnement          STORAGE_BUCKET=media
               |
               v
  application-prod.yaml              fichier d'un profil actif (guide 02)
               |
               v
  application.yaml                   les valeurs par défaut     la plus faible
```

- Un fichier importé par `spring.config.import` passe devant celui qui l'importe.
- `classpath:/config/application.yaml` passe devant `classpath:/application.yaml`. Un fichier de test surcharge ainsi le principal sans le remplacer.
- Une variable d'environnement atteint une propriété de deux façons.
  - Le binding souple : `SPRING_RABBITMQ_HOST` remplit `spring.rabbitmq.host` (majuscules, points changés en `_`, tirets retirés).
  - Un placeholder (`host: ${MAIL_HOST:localhost}`), qui donne un nom court et un défaut.

### Les placeholders

Ce que donne chaque écriture :

| Écriture dans le YAML | Variable absente | Variable vide |
|---|---|---|
| `${MAIL_HOST:localhost}` | `localhost` | une chaîne vide |
| `${MAIL_HOST:}` | une chaîne vide | une chaîne vide |
| `${MAIL_HOST}`, lu par un record | le texte `${MAIL_HOST}`, sans erreur | une chaîne vide |
| `${MAIL_HOST}`, lu par `@Value` | échec au démarrage | une chaîne vide |

Le binding garde tel quel un placeholder qu'il ne sait pas résoudre (troisième ligne). Le guide 02 arrête alors le démarrage.

### Les durées et les tailles

Conversions en `Duration` et en `DataSize` :

| Écriture | Type | Sens |
|---|---|---|
| `30s`, `15m`, `24h`, `7d` | `Duration` | un nombre puis une unité : `ns`, `us`, `ms`, `s`, `m`, `h`, `d` |
| `PT5S`, `PT1H`, `P7D` | `Duration` | le format ISO-8601 |
| `500` | `Duration` | des millisecondes, sauf `@DurationUnit` sur le composant |
| `16KB`, `25MB` | `DataSize` | unités `B`, `KB`, `MB`, `GB`, `TB`, par puissances de 1024 |
| `1024` | `DataSize` | des octets, sauf `@DataSizeUnit` sur le composant |

### Le binding vers un record

Chaque clé devient un composant du record, les tirets devenant du camelCase :

```
  application.yaml                      RateLimitProperties (record)

  rate-limit:
    enabled: true            -------->  boolean enabled
    purge-cron: 0 20 * * * * -------->  String purgeCron
    login-per-ip:            -------->  Limit loginPerIp
      requests: 30           -------->    int requests
      window: PT1M           -------->    Duration window
```

- Spring appelle le constructeur du record (un objet immuable), convertit chaque valeur, puis valide l'ensemble. Une contrainte violée arrête le démarrage.
- Une clé absente prend la valeur de `@DefaultValue`, sinon `null` pour un objet, `0` ou `false` pour un type primitif.

### Les beans conditionnels

Une propriété choisit l'implémentation qui existera :

```
                     storage.driver
                           |
          +----- rustfs ---+--- aws-s3 -----+
          |                                 |
          v                                 v
  RustFsStorageConfiguration      AwsS3StorageConfiguration
  endpoint, clés statiques        région, rôle IAM
          |                                 |
          +----------------+----------------+
                           |
                           v
              un seul bean ObjectStorage
```

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/resources/
  application.yaml                     un bloc par fonctionnalité (étape 1)
.env.example                           les clés à copier dans .env (étape 2)
pom.xml                                Docker Compose, métadonnées (étapes 3, 8)
src/main/java/.../
  invoice/
    InvoiceProperties.java             le record validé (étape 4)
    InvoiceConfiguration.java          l'enregistre (étape 5)
    InvoiceService.java                l'utilise (étape 6)
    InvoicePurgeJob.java               un job qu'on peut éteindre (étape 10)
  config/
    ClockConfiguration.java            l'horloge injectable (étape 7)
  storage/
    StorageConfiguration.java          les propriétés du stockage (étape 9)
    LocalStorageConfiguration.java     un driver par valeur (étape 9)
    S3StorageConfiguration.java
  antivirus/
    AntivirusConfiguration.java        un remplacement sans condition (étape 11)
src/test/java/.../
  InvoicePropertiesTest.java           la vérification (étape 12)
```

### Un bloc de réglages typé

**Étape 1. Écrire un bloc par fonctionnalité.** Un commentaire au-dessus du bloc dit à quoi il sert. Ce qui change selon l'environnement passe par un placeholder.

```yaml
# Factures : purge des factures payées après leur durée de conservation
invoices:
  purge-enabled: ${INVOICE_PURGE_ENABLED:true}
  purge-cron: 0 30 3 * * *
  retention: 365d
  max-attachment-size: 10MB
  pdf:
    timeout: 30s
mail:
  from: ${MAIL_FROM:no-reply@example.local}
spring:
  config:
    import: optional:file:.env[.properties]     # étape 2
```

**Étape 2. Mettre les secrets locaux dans `.env`.** Le fichier reste hors de git. `.env.example`, versionné, liste les clés à remplir.

```properties
# .env.example : copier en .env (ignoré par git), puis remplir
JWT_SECRET=
MAIL_FROM=no-reply@example.local
```

- `optional:` : sans fichier, le démarrage continue.
- `[.properties]` : `.env` n'a pas d'extension connue, Spring le lit donc comme un fichier `.properties`.
- `file:.env` est relatif au dossier courant, d'où le lancement depuis la racine du projet.
- Docker Compose lit le même fichier pour les `${...}` de `compose.yaml`.

**Étape 3. Laisser Docker Compose fournir les services en développement.** `./mvnw spring-boot:run` démarre `compose.yaml` et branche l'application sur ses conteneurs.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-docker-compose</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>
```

- Spring Boot lit les ports et identifiants des conteneurs reconnus (Postgres, RabbitMQ...) et en tire les détails de connexion.
- Le plugin Maven retire cette dépendance du jar final, et elle reste éteinte pendant les tests.
- Si des services du projet tournent déjà, elle ne lance pas `docker compose up`. Un service ajouté plus tard demande donc un `docker compose up -d` à la main.

**Étape 4. Écrire le record.** `@Validated` active les contraintes. Chaque `@param` documente la clé dans l'IDE.

```java
/**
 * @param purgeEnabled      lancer la purge planifiée sur cette instance
 * @param purgeCron         quand la purge passe (cron Spring, six champs)
 * @param retention         durée de conservation d'une facture payée
 * @param maxAttachmentSize plus grosse pièce jointe acceptée
 * @param pdf               le rendu des PDF
 */
@Validated
@ConfigurationProperties(prefix = "invoices")
public record InvoiceProperties(
        @DefaultValue("true") boolean purgeEnabled,
        @NotBlank String purgeCron,
        @NotNull @DurationMin(days = 1) @DurationMax(days = 3650) Duration retention,
        @NotNull DataSize maxAttachmentSize,
        @DefaultValue @Valid @NotNull Pdf pdf
) {

    /**
     * @param timeout durée maximale d'un rendu
     */
    public record Pdf(
            @DefaultValue("30s") @NotNull @DurationMin(seconds = 1) Duration timeout
    ) {
    }
}
```

- `@DefaultValue` sans valeur sur `pdf` crée un `Pdf` rempli de ses défauts quand le bloc manque. `@Valid` fait descendre la validation dedans.
- `@DurationMin` et `@DurationMax` viennent de Hibernate Validator (`spring-boot-starter-validation`).

**Étape 5. Enregistrer le record dans la configuration de la fonctionnalité.**

```java
@Configuration
@EnableConfigurationProperties(InvoiceProperties.class)
class InvoiceConfiguration {
}
```

**Étape 6. Injecter le record.** Le service lit des valeurs déjà converties et validées.

```java
@Service
@RequiredArgsConstructor
class InvoiceService {

    private final InvoiceProperties properties;
    private final InvoiceRepository invoiceRepository;   // un repository Spring Data (guide 11)
    private final Clock clock;                           // étape 7

    @Transactional
    public int purge() {
        Instant cutoff = clock.instant().minus(properties.retention());
        return invoiceRepository.deletePaidBefore(cutoff);
    }
}
```

**Étape 7. Injecter une horloge plutôt qu'appeler `Instant.now()`.** Un test fixe ou avance alors l'heure, sans attendre.

```java
@Configuration
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

```java
// dans un test unitaire : une heure figée
Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
InvoiceService service = new InvoiceService(properties, invoiceRepository, fixed);
```

**Étape 8. Générer les métadonnées.** À la compilation, le processeur écrit `META-INF/spring-configuration-metadata.json`. L'IDE complète alors les clés et affiche le `@param` et le défaut.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-configuration-processor</artifactId>
            </path>
            <path>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

Une entrée du fichier généré :

```json
{
  "name": "invoices.pdf.timeout",
  "type": "java.time.Duration",
  "description": "durée maximale d'un rendu",
  "sourceType": "com.example.invoice.InvoiceProperties$Pdf",
  "defaultValue": "30s"
}
```

### Choisir les beans par configuration

**Étape 9. Choisir une implémentation par une propriété.** Les propriétés s'enregistrent dans une configuration toujours chargée. La validation tourne ainsi même si la valeur ne correspond à aucun driver.

```java
@Validated
@ConfigurationProperties(prefix = "storage")
record StorageProperties(
        @NotNull @Pattern(regexp = "local|s3") String driver, String bucket, Path directory) {
}

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {
}

@Configuration
@ConditionalOnProperty(name = "storage.driver", havingValue = "s3")
class S3StorageConfiguration {

    @Bean
    FileStorage fileStorage(StorageProperties properties) {     // FileStorage : une interface du projet
        return new S3FileStorage(properties.bucket());
    }
}

@Configuration
@ConditionalOnProperty(name = "storage.driver", havingValue = "local")
class LocalStorageConfiguration {

    @Bean
    FileStorage fileStorage(StorageProperties properties) {
        return new LocalFileStorage(properties.directory());
    }
}
```

**Étape 10. Rendre un job désactivable.** Le job tourne par défaut. Une propriété l'éteint sur une instance ou dans les tests (guide 21).

```java
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty(name = "invoices.purge-enabled", matchIfMissing = true)
class InvoicePurgeJob {

    private final InvoiceService invoiceService;

    @Scheduled(cron = "${invoices.purge-cron}")
    public void run() {
        invoiceService.purge();
    }
}
```

```yaml
# src/test/resources/config/application.yaml : les tests appellent le service eux-mêmes
invoices:
  purge-enabled: false
```

**Étape 11. Remplacer une implémentation sans condition quand il faut prévenir.** Un `if` dans la méthode `@Bean` garde un bean toujours présent. Il laisse aussi une trace au démarrage quand une protection est coupée.

```java
@Slf4j
@Configuration
@EnableConfigurationProperties(AntivirusProperties.class)
class AntivirusConfiguration {

    @Bean
    VirusScanner virusScanner(AntivirusProperties properties) {      // VirusScanner : une interface du projet
        if (!properties.enabled()) {
            log.warn("Antivirus scanning is disabled: uploads are stored unscanned");
            return content -> Optional.empty();                      // aucune menace trouvée
        }
        return new ClamAvScanner(properties.host(), properties.port(), properties.timeout());
    }
}
```

**Étape 12. Vérifier.** `ApplicationContextRunner` démarre un petit contexte avec la seule configuration testée, sans base.

```java
class InvoicePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InvoiceConfiguration.class)
            .withPropertyValues("invoices.purge-cron=0 30 3 * * *", "invoices.retention=365d",
                    "invoices.max-attachment-size=10MB");

    @Test
    void bindsTheBlockWithItsDefaults() {
        runner.run(context -> {
            InvoiceProperties properties = context.getBean(InvoiceProperties.class);
            assertThat(properties.purgeEnabled()).isTrue();
            assertThat(properties.retention()).isEqualTo(Duration.ofDays(365));
            assertThat(properties.maxAttachmentSize()).isEqualTo(DataSize.ofMegabytes(10));
            assertThat(properties.pdf().timeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void retentionUnderADayStopsTheStartup() {
        runner.withPropertyValues("invoices.retention=1h")
                .run(context -> assertThat(context).hasFailed());
    }
}
```

### Référence rapide

Annotations d'un record de propriétés :

| Annotation | Rôle |
|---|---|
| `@ConfigurationProperties(prefix = "...")` | le bloc lu par le record |
| `@EnableConfigurationProperties(X.class)` | enregistre le record comme bean |
| `@Validated` | active les contraintes au démarrage |
| `@Valid` | valide aussi un record imbriqué |
| `@DefaultValue("...")` | valeur d'une clé absente ; sans valeur, un record imbriqué rempli de ses défauts |
| `@DurationUnit`, `@DataSizeUnit` | unité d'un nombre écrit sans unité |
| `@DurationMin`, `@DurationMax` | bornes d'une `Duration` (Hibernate Validator) |

Conditions les plus utiles :

| Condition | Crée le bean quand... |
|---|---|
| `@ConditionalOnProperty(name, havingValue)` | la propriété vaut `havingValue`, sans tenir compte de la casse |
| `@ConditionalOnBooleanProperty(name)` | la propriété vaut `true` ; `matchIfMissing = true` l'accepte absente |
| `@ConditionalOnMissingBean` | aucun bean du même type n'existe, surtout dans une auto-configuration |
| `@ConditionalOnClass` | une classe est sur le classpath |
| `@Profile("prod")` | le profil est actif (guide 02) |

Limites de cette version : une valeur se lit au démarrage, la changer demande un redémarrage.

## Concepts avancés

### Sous le capot : du fichier au record

Du disque au bean :

```
  fichiers YAML, .env, variables, -D, arguments
                     |
                     v
  Environment : une pile de sources, la plus forte en haut
                     |
                     v
  Binder : lit toutes les clés du préfixe, résout les ${...},
           convertit (Duration, DataSize, listes, records)
                     |
                     v
  validation (@Validated) -- échec --> APPLICATION FAILED TO START
                     |
                     v
  le record, enregistré comme bean
```

- En cas d'échec, Spring Boot affiche un rapport. Il donne la propriété, la valeur refusée, son origine (fichier et ligne, ou variable) et la raison.
- Une liste s'écrit en YAML ou en une chaîne séparée par des virgules : `allowed-origins: https://a.example,https://b.example` remplit un `List<String>`.
- Le binding accepte plusieurs écritures d'une clé (`max-page-size`, `maxPageSize`, `MAX_PAGE_SIZE`). Dans les fichiers, on écrit la forme en tirets.

### Sous le capot : quand les conditions sont évaluées

Spring lit les classes de configuration avant de créer les beans, et décide alors lesquelles garder. Une condition sur une propriété est sûre, car l'`Environment` est complet. `@ConditionalOnBean` et `@ConditionalOnMissingBean` dépendent des définitions déjà lues. Spring Boot les réserve donc aux auto-configurations, lues après les configurations du projet.

### Plusieurs instances

- Toutes les instances lisent les mêmes fichiers ; seules leurs variables d'environnement diffèrent (guide 02).
- Un interrupteur par instance (`invoices.purge-enabled`) réserve un job à certaines instances. ShedLock (guide 21) garantit déjà un seul passage par tour : l'interrupteur sert à répartir la charge.

## Pièges à éviter

### Fichiers et variables

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire un secret dans `application.yaml` | il part dans git et le jar | Écrire `${JWT_SECRET}`, valeur dans `.env` |
| Mettre des guillemets dans `.env` | les guillemets restent dans la valeur | Écrire `CLE=valeur` sans guillemets |
| Lancer l'application depuis un autre dossier | `.env` est introuvable, sans erreur (`optional:`) | Lancer depuis la racine du projet |
| Nommer la variable `STATUS_CACHE_TTL` | le binding retire les tirets : la clé reste vide | Écrire `STATUSCACHE_TTL`, ou un placeholder |
| Écrire `500` pour une durée | Spring lit 500 millisecondes | Toujours écrire l'unité : `500s`, `PT5M` |
| Ajouter un service à `compose.yaml` | Boot ne relance pas `docker compose up` si d'autres services tournent | Lancer `docker compose up -d` une fois |

### Records et validation

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Oublier `@Validated`, ou `@Valid` sur un imbriqué | les contraintes sont ignorées | Les mettre sur le record et sur le composant imbriqué |
| Laisser un `boolean` de protection sans défaut | une clé absente vaut `false`, la protection s'éteint | Ajouter `@DefaultValue("true")` |
| Placeholder sans défaut dans un record | la clé reçoit le texte `${VAR}`, sans erreur | Donner un défaut, ou vérifier au démarrage (guide 02) |
| Lister `annotationProcessorPaths` sans le processeur | plus de métadonnées, plus de complétion | Ajouter `spring-boot-configuration-processor` |

### Beans conditionnels

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Enregistrer les propriétés dans une configuration conditionnelle | une valeur inconnue donne un bean manquant | Les enregistrer sans condition |
| Utiliser `@ConditionalOnBean` dans une configuration du projet | le résultat dépend de l'ordre de lecture | Le réserver aux auto-configurations |
| Oublier `matchIfMissing = true` sur un job | une clé absente l'éteint sans bruit | Écrire `matchIfMissing = true` |
| Couper une protection sans trace | personne ne sait que l'antivirus est éteint | Logger un WARN au démarrage |

## En résumé

**À retenir :** un bloc YAML par fonctionnalité, lu par un record `@ConfigurationProperties` validé et documenté, des secrets hors de git, et des beans choisis par une propriété dont la validation tourne toujours.

- [ ] Un bloc commenté par fonctionnalité, `${VAR:défaut}` pour ce qui varie
- [ ] Secrets dans `.env` (ignoré par git), clés listées dans `.env.example`
- [ ] Un record par bloc : `@Validated`, contraintes, `@DefaultValue`, `@Valid` sur les imbriqués
- [ ] Un `@param` par composant, et le processeur de métadonnées dans le `pom.xml`
- [ ] Durées et tailles toujours écrites avec leur unité
- [ ] `@EnableConfigurationProperties` dans la configuration de la fonctionnalité
- [ ] Un bean `Clock` injecté à la place de `Instant.now()`
- [ ] Drivers choisis par `@ConditionalOnProperty`, propriétés enregistrées sans condition
- [ ] Jobs désactivables par `@ConditionalOnBooleanProperty(matchIfMissing = true)`
- [ ] Un test `ApplicationContextRunner` par record

## Dans ce dépôt

Le trajet de `storage.driver` :

```
  .env (poste de développement)        variables du déploiement
            |                                      |
            +------------------+-------------------+
                               |
                               v
  application.yaml    driver: ${STORAGE_DRIVER:rustfs}
                               |
                               v
  StorageProperties   validé au démarrage, @Pattern(rustfs|aws-s3)
                               |
                               v
                       storage.driver ?
                               |
          +----- rustfs -------+------- aws-s3 -----+
          |                                         |
          v                                         v
  RustFsStorageConfiguration           AwsS3StorageConfiguration
          |                                         |
          +--------------------+--------------------+
                               |
                               v
                  ObjectStorage (S3ObjectStorage)
```

| Fichier | Rôle |
|---|---|
| [`application.yaml`][yaml] | un bloc commenté par fonctionnalité, défauts de développement |
| [`.env.example`][env-example] | les clés de `.env`, dont `JWT_SECRET` et `WEBHOOK_ENCRYPTION_KEY` |
| [`RateLimitProperties`][rate-limit] | records imbriqués, bornes `@DurationMin` et `@DurationMax` |
| [`RealtimeProperties`][realtime] | `@DefaultValue` partout, `DataSize`, liste d'origines |
| [`ExportProperties`][export] | durées bornées, interrupteurs des jobs d'export |
| [`WebhookProperties`][webhook] | un record imbriqué rempli par `@DefaultValue` |
| [`StorageProperties`][storage-props] | le driver contrôlé par `@Pattern`, un sous-record par driver |
| [`StorageConfiguration`][storage-config] | enregistre les propriétés du stockage, quel que soit le driver |
| [`RustFsStorageConfiguration`][rustfs], [`AwsS3StorageConfiguration`][aws] | une configuration par valeur de `storage.driver` |
| [`AntivirusConfiguration`][antivirus] | un scanner qui accepte tout quand `antivirus.enabled=false`, avec un WARN |
| [`ClockConfiguration`][clock] | le bean `Clock`, en UTC |
| [`CacheConfiguration`][cache] | les caches nommés par `spring.cache.cache-names` (guide 17) |

Les réglages transverses :

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.config.import` | `optional:file:.env[.properties]` | lit `.env` s'il existe |
| `spring.cache.cache-names` | `userStatus, mediaDownloads` | un nom mal écrit n'obtient aucun cache |
| `spring.jackson.datatype.enum.fail-on-numbers-for-enums` | `true` | `"kind": 1` avait créé un webhook Slack |
| `storage.driver` | `rustfs`, et `aws-s3` sous le profil `prod` | choisit la configuration du stockage |
| `antivirus.enabled` | `true` | `false` stocke les fichiers sans les analyser |

Ce qui est propre au dépôt :

- Chaque fonctionnalité enregistre ses records dans sa propre configuration (`ExportConfiguration`, `WebhookConfiguration`, `RealtimeConfiguration`...), sans `@ConfigurationPropertiesScan`.
- Le `@param` par composant est la seule Javadoc exigée, car il documente la clé YAML.
- `StorageConfiguration` répare un défaut mesuré. Quand les propriétés étaient enregistrées dans les seules configurations des drivers, une valeur inconnue ne chargeait rien. La validation ne tournait pas, et le démarrage échouait sur un bean manquant.
- Sept jobs portent `@ConditionalOnBooleanProperty(matchIfMissing = true)` : rappels, rétention, nettoyage des médias, réessais et purge des webhooks, purge et reprise des exports. La configuration de test les éteint ([`config/application.yaml`][test-yaml]).
- Les services qui lisent l'heure injectent `Clock`, pour leurs règles de temps (rétention, rappels, exports, outbox, rate limiting) comme pour de simples horodatages (jetons, tâches, commentaires, médias). L'audit de Spring Data date `created_at` et `updated_at` avec la même horloge, par le `DateTimeProvider` de `JpaAuditingConfiguration`. En intégration, [`TestClock`][test-clock] le remplace (guide 31). Seul `JwtService` garde l'heure système : le décodeur JWT compare `exp` à l'horloge système, et un jeton émis depuis une horloge figée serait déjà expiré, ou valide trop longtemps.

Les tests : [`RateLimitConfigurationTest`][rate-limit-test], [`StorageConfigurationTest`][storage-test], [`RealtimeConfigurationTest`][realtime-test], [`ExportConfigurationTest`][export-test] et [`AntivirusConfigurationTest`][antivirus-test] démarrent chacun un `ApplicationContextRunner`. Ils vérifient les défauts de `application.yaml`, l'arrêt du démarrage sur une valeur hors bornes, et les beans présents ou absents selon les interrupteurs.

Pour ajouter un réglage : la clé dans le bloc, le composant du record avec `@param`, contrainte et défaut, puis un cas de test.

## Voir aussi

- [02. Profil de production](02-profil-production.md) : les réglages sans défaut en production, vérifiés au démarrage.
- [17. Cache Caffeine](17-cache-caffeine.md) : les caches déclarés par `spring.cache.cache-names`.
- [21. Jobs planifiés et ShedLock](21-jobs-planifies-shedlock.md) : les jobs éteints par une propriété.
- [28. Uploads et drivers de stockage](28-uploads-stockage.md) : les deux drivers de `storage.driver`.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : l'horloge contrôlée en test.
- Documentation officielle : [configuration externalisée](https://docs.spring.io/spring-boot/reference/features/external-config.html), [services de développement (Docker Compose)](https://docs.spring.io/spring-boot/reference/features/dev-services.html).

[Sommaire](README.md) | [02. Profil de production >](02-profil-production.md)

[yaml]: ../../src/main/resources/application.yaml
[env-example]: ../../.env.example
[rate-limit]: ../../src/main/java/io/julienmetral/tasks/config/RateLimitProperties.java
[realtime]: ../../src/main/java/io/julienmetral/tasks/realtime/RealtimeProperties.java
[export]: ../../src/main/java/io/julienmetral/tasks/export/ExportProperties.java
[webhook]: ../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookProperties.java
[storage-props]: ../../src/main/java/io/julienmetral/tasks/config/StorageProperties.java
[storage-config]: ../../src/main/java/io/julienmetral/tasks/config/StorageConfiguration.java
[rustfs]: ../../src/main/java/io/julienmetral/tasks/config/RustFsStorageConfiguration.java
[aws]: ../../src/main/java/io/julienmetral/tasks/config/AwsS3StorageConfiguration.java
[antivirus]: ../../src/main/java/io/julienmetral/tasks/config/AntivirusConfiguration.java
[clock]: ../../src/main/java/io/julienmetral/tasks/config/ClockConfiguration.java
[cache]: ../../src/main/java/io/julienmetral/tasks/config/CacheConfiguration.java
[test-yaml]: ../../src/test/resources/config/application.yaml
[test-clock]: ../../src/test/java/io/julienmetral/tasks/support/TestClock.java
[rate-limit-test]: ../../src/test/java/io/julienmetral/tasks/config/RateLimitConfigurationTest.java
[storage-test]: ../../src/test/java/io/julienmetral/tasks/config/StorageConfigurationTest.java
[realtime-test]: ../../src/test/java/io/julienmetral/tasks/realtime/RealtimeConfigurationTest.java
[export-test]: ../../src/test/java/io/julienmetral/tasks/export/ExportConfigurationTest.java
[antivirus-test]: ../../src/test/java/io/julienmetral/tasks/config/AntivirusConfigurationTest.java
