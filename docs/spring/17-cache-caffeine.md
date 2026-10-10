# 17. Cache Caffeine

[Sommaire](README.md) | [< 16. Événements applicatifs et `@Async`](16-evenements-async.md) | [18. Migrations Liquibase >](18-migrations-liquibase.md)

## Contexte

- **Problème** : une même lecture revient sans cesse (le statut d'un compte à chaque requête, une URL signée par fichier affiché) et coûte une requête SQL ou un calcul à chaque fois.
- **Quand l'utiliser** : une lecture fréquente, dont la valeur change rarement et peut avoir quelques secondes de retard sans danger.
- **Quand s'en passer** : une donnée qui doit être exacte au moment d'écrire (une règle métier, un contrôle avant modification) ; une lecture rare ; une valeur qui doit être la même sur toutes les instances à la seconde près.
- **Dans ce dépôt** : deux caches, `userStatus` (le statut d'un compte, vérifié à chaque requête sur les tâches) et `mediaDownloads` (l'URL signée de chaque fichier).
- **Prérequis** : guides 14 (transactions) et 16 (événements). Versions : Spring Boot 4.1, Spring Framework 7.0, Caffeine 3.2.

Où garder une valeur déjà calculée :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Caffeine | en mémoire de chaque instance, avec expiration et taille maximale | cas le plus courant, valeur qui tolère un retard borné |
| Redis | un serveur de cache partagé par toutes les instances | même valeur partout, ou cache trop gros pour la mémoire |
| `ConcurrentMapCacheManager` | une simple map, sans expiration ni limite | tests et prototypes seulement |

## Concepts clés

### Vocabulaire

- **Cache** : une table clé-valeur en mémoire, devant une lecture coûteuse.
- **Entrée** : un couple clé-valeur du cache.
- **Succès / défaut** (hit / miss) : la valeur était en cache, ou il a fallu la lire.
- **Expiration** (TTL) : la durée de vie d'une entrée, après laquelle elle disparaît d'elle-même.
- **Éviction** : retirer une entrée avant son expiration, parce que la valeur a changé. Caffeine parle aussi d'éviction quand il retire une entrée pour faire de la place.

### Le cache devant une méthode

`@Cacheable` sur une méthode : le proxy Spring calcule une clé à partir des paramètres, cherche l'entrée, et n'appelle la méthode qu'en cas de défaut. La clé par défaut est le paramètre lui-même s'il est seul, une clé composée sinon. Avec un objet en paramètre, on précise la clé : `key = "#media.id"`. Chaque instance a son propre cache.

```
 appel priceOf("A-1")
        |
        v
  proxy Spring (cache)
        |
        +-- entrée "A-1" présente ? -- oui --> renvoie la valeur (succès)
        |
        | non (défaut)
        v
  méthode réelle --> SELECT ... --> valeur rangée sous "A-1", renvoyée
```

### Les trois façons d'expirer

| Réglage Caffeine | Une entrée disparaît... | Risque |
|---|---|---|
| `expireAfterWrite(d)` | d après son écriture, qu'on la lise ou non | aucun : le retard est borné par d |
| `expireAfterAccess(d)` | d après sa dernière lecture | une entrée lue en boucle ne meurt jamais |
| `refreshAfterWrite(d)` | n'expire pas : rechargée en arrière-plan après d | ancienne valeur servie pendant le rechargement ; erreur seulement journalisée |

Avec une base en panne, un rechargement garde donc l'ancienne valeur. `maximumSize(n)` s'ajoute aux trois : au-delà, Caffeine retire des entrées pour faire de la place. Sans lui, le cache grossit sans limite.

### La course entre une lecture et une éviction

Sans précaution, une lecture commencée avant le commit range une valeur périmée après l'éviction :

```
 requête A (lit le statut)          admin (désactive le compte)
 -------------------------          ---------------------------
 défaut de cache
 SELECT --> ACTIVE
        |                           UPDATE enabled = false
        |                           COMMIT
        |                           éviction de l'entrée (rien à retirer)
        v
 range ACTIVE dans le cache   --> le compte désactivé garde l'accès jusqu'au TTL
```

`@Cacheable(sync = true)` règle ce cas : le chargement d'une clé se fait sous un verrou de cette clé. Une éviction arrivée pendant le chargement attend sa fin, puis retire ce qu'il a rangé. Bonus : plusieurs threads ne calculent plus la même valeur en même temps (par défaut, Spring ne verrouille rien).

Les limites de `sync = true` : pas d'attribut `unless`, un seul cache, et aucune autre annotation de cache sur la méthode.

Il faut aussi évincer au bon moment :

```
 évincer AVANT le commit                 évincer APRÈS le commit
 -----------------------                 -----------------------
 UPDATE (non validé)                     UPDATE
 éviction                                COMMIT
 une requête relit l'ancienne valeur     éviction
 et la remet en cache                    la relecture voit la nouvelle valeur
 COMMIT --> cache périmé jusqu'au TTL
```

On évince donc dans un `@TransactionalEventListener` (phase `AFTER_COMMIT`, guide 16).

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                  dépendances (étape 1)
src/main/resources/
  application.yaml                       type et noms des caches (étape 2)
src/main/java/.../
  config/
    CacheConfiguration.java              réglages de chaque cache (étape 3)
  product/
    ProductCatalog.java                  la lecture mise en cache (étape 4)
    ProductPriceChanged.java             l'événement (étape 5)
    ProductPriceCacheEviction.java       l'éviction après le commit (étape 5)
src/test/java/.../
  ProductCatalogCacheTest.java           la vérification (étape 7)
```

### Une instance : `@Cacheable` et Caffeine

**Étape 1. Ajouter les dépendances.** Le starter apporte l'abstraction de cache de Spring ; Caffeine fournit le cache en mémoire (version gérée par Boot).

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

**Étape 2. Choisir Caffeine et fermer la liste des caches.** Avec `cache-names`, un nom mal tapé n'obtient aucun cache et l'appel échoue, au lieu de créer en silence un cache sans limite ni métriques.

```yaml
spring:
  cache:
    type: caffeine
    cache-names: productPrices
```

**Étape 3. Régler chaque cache.** Chaque cache a sa durée et sa taille ; `recordStats()` alimente les métriques.

```java
@Configuration
@EnableCaching
public class CacheConfiguration {

    public static final String PRODUCT_PRICES = "productPrices";

    @Bean
    CacheManagerCustomizer<CaffeineCacheManager> applicationCaches() {
        return cacheManager -> {
            cacheManager.setAllowNullValues(false);
            cacheManager.registerCustomCache(PRODUCT_PRICES, Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(5))   // jamais expireAfterAccess (voir les pièges)
                    .maximumSize(10_000)
                    .recordStats()                             // sans lui, seule cache.size est mesurée
                    .build());
        };
    }
}
```

`CacheManagerCustomizer` vient de `org.springframework.boot.cache.autoconfigure`. Avec `setAllowNullValues(false)`, une méthode qui renvoie `null` échoue (`IllegalArgumentException`) : elle doit renvoyer une valeur, ou lever une exception.

**Étape 4. Mettre la lecture en cache.** `sync = true` évite deux lectures simultanées de la même clé, et la course de l'éviction.

```java
@Service
@RequiredArgsConstructor
public class ProductCatalog {

    private final ProductRepository productRepository;   // requête Spring Data (guide 11)

    @Cacheable(cacheNames = CacheConfiguration.PRODUCT_PRICES, sync = true)
    public BigDecimal priceOf(String sku) {
        return productRepository.findPriceBySku(sku).orElseThrow();   // Optional<BigDecimal>
    }
}
```

Une lecture en échec lève une exception et ne range rien.

**Étape 5. Vider l'entrée après le commit.** Évincer avant le commit laisse une requête concurrente remettre l'ancienne valeur en cache.

```java
public record ProductPriceChanged(String sku) {
}
```

```java
// dans la méthode @Transactional qui change le prix (events : l'ApplicationEventPublisher, guide 16)
events.publishEvent(new ProductPriceChanged(sku));
```

```java
@Component
@RequiredArgsConstructor
public class ProductPriceCacheEviction {

    private final CacheManager cacheManager;      // org.springframework.cache.CacheManager

    @TransactionalEventListener(fallbackExecution = true)   // après le commit (guide 16)
    public void evict(ProductPriceChanged event) {
        Cache cache = cacheManager.getCache(CacheConfiguration.PRODUCT_PRICES);
        if (cache != null) {
            cache.evictIfPresent(event.sku());
        }
    }
}
```

### Plusieurs instances : prévenir les autres caches

**Étape 6. Diffuser l'éviction.** Chaque instance a son propre cache. Celle qui a fait le changement prévient les autres par un exchange fanout, et le TTL borne le retard si le message se perd.

```java
// dans un listener de la file propre à l'instance (guide 24)
@RabbitListener(queues = "#{instanceQueue.name}")
void onPriceChanged(ProductPriceChanged event) {
    productPriceCacheEviction.evict(event);   // appel direct : vide l'entrée de cette instance
}
```

Côté publication, un `@EventListener` écrit `ProductPriceChanged` dans l'outbox en diffusion (`Outbox.broadcast`, guide 20), dans la même transaction.

**Étape 7. Vérifier.** Un contexte Spring minimal et un repository simulé : une seule lecture pour deux appels, une nouvelle lecture après l'éviction.

```java
@SpringJUnitConfig
class ProductCatalogCacheTest {

    @Configuration
    @EnableCaching
    @Import({ProductCatalog.class, ProductPriceCacheEviction.class})
    static class Caching {

        @Bean
        CacheManager cacheManager() {
            return new CaffeineCacheManager(CacheConfiguration.PRODUCT_PRICES);
        }
    }

    @MockitoBean ProductRepository productRepository;
    @Autowired ProductCatalog catalog;
    @Autowired ProductPriceCacheEviction eviction;

    @Test
    void priceIsReadOnceThenServedFromTheCacheUntilEvicted() {
        when(productRepository.findPriceBySku("A-1")).thenReturn(Optional.of(BigDecimal.TEN));

        catalog.priceOf("A-1");
        catalog.priceOf("A-1");
        verify(productRepository, times(1)).findPriceBySku("A-1");

        eviction.evict(new ProductPriceChanged("A-1"));
        catalog.priceOf("A-1");
        verify(productRepository, times(2)).findPriceBySku("A-1");
    }
}
```

Entre deux tests, les entrées survivent dans le contexte partagé : clés distinctes par test, ou caches vidés après chacun.

### Référence rapide

Les annotations :

| Annotation | Rôle | Attributs utiles |
|---|---|---|
| `@EnableCaching` | active les proxys de cache | |
| `@Cacheable` | lit le cache, appelle la méthode en cas de défaut | `cacheNames`, `key`, `sync`, `unless` |

Les réglages de Spring Boot :

| Réglage | Exemple | Rôle |
|---|---|---|
| `spring.cache.type` | `caffeine` | choisit le fournisseur (détecté sinon) |
| `spring.cache.cache-names` | `productPrices` | crée ces caches et ferme la liste |
| `spring.cache.caffeine.spec` | `maximumSize=500,expireAfterWrite=5m` | un réglage commun à tous les caches |

Les méthodes du builder Caffeine : `expireAfterWrite`, `expireAfterAccess`, `expireAfter(Expiry)`, `refreshAfterWrite`, `maximumSize`, `recordStats`.

Limites de cette version : l'éviction ne vaut que pour l'instance qui l'a faite ; sans l'étape 6, les autres servent l'ancienne valeur jusqu'au TTL.

## Concepts avancés

### Sous le capot : le proxy et le chargement synchronisé

- `@EnableCaching` place un proxy Spring devant le bean. Un appel interne `this.priceOf(...)` le contourne : pas de cache.
- Avec `sync = true`, Spring appelle `Cache.get(key, valueLoader)`. `CaffeineCache` délègue à Caffeine, qui calcule la valeur une fois par clé pendant que les autres appelants de cette clé attendent.
- Sur un `@CacheEvict` posé sur une méthode `@Transactional`, rien ne garantit que l'éviction passe après le commit : les deux conseillers ont l'ordre `LOWEST_PRECEDENCE` par défaut. D'où le listener d'après commit de l'étape 5.

### Une durée par entrée

`expireAfter(Expiry.creating((key, value) -> duration))` donne à chaque entrée sa propre durée, calculée depuis la valeur. Ce dépôt s'en sert pour les URL signées de téléchargement : une entrée vit au plus la moitié de la validité de l'URL.

### Plusieurs instances

```
 instance A (désactive le compte)
     |
     +--> évince son entrée, après le commit
     |
     +--> outbox --> exchange fanout --> file de B --> B évince son entrée
                                    --> file de C --> C évince son entrée

 broker en panne --> B et C gardent l'entrée jusqu'au TTL
```

Le TTL est la vraie borne : on le choisit comme le retard le plus long acceptable si une éviction se perd. Si toutes les instances doivent voir la même valeur, Redis est l'alternative.

### Surveiller

Boot lie à Micrometer les caches présents au démarrage :

| Métrique | Sens |
|---|---|
| `cache.gets` (`result=hit` ou `miss`) | succès et défauts ; leur rapport est le taux de succès |
| `cache.puts` | valeurs rangées |
| `cache.evictions` | entrées retirées pour la taille ou l'expiration (pas les évictions manuelles) |
| `cache.size` | nombre d'entrées |

- Sans `recordStats()`, Micrometer n'enregistre que `cache.size` et journalise un avertissement.
- Un cache créé dynamiquement après le démarrage n'a pas de métriques.

## Pièges à éviter

### Avec un cache local

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Expirer après accès (`expireAfterAccess`) | une entrée lue en boucle ne meurt jamais | `expireAfterWrite` |
| Rafraîchir en arrière-plan (`refreshAfterWrite`) une donnée de sécurité | l'ancienne valeur reste servie, même quand la base échoue | Expirer, puis relire |
| Oublier `maximumSize` | le cache grossit sans limite | Fixer une taille maximale |
| Laisser la liste des caches ouverte | un nom mal tapé crée un cache sans limite ni métriques | Lister les noms dans `cache-names` |
| Appeler la méthode `@Cacheable` depuis sa classe | l'appel contourne le proxy : pas de cache | Passer par un autre bean |
| Garder la clé par défaut avec un objet en paramètre | la clé dépend de l'égalité de l'objet, souvent jamais égale | Préciser `key = "#objet.id"` |
| Combiner `sync = true` et `unless` | Spring lève `IllegalStateException` | Mettre en cache toutes les valeurs, et évincer à chaque changement |
| Mettre en cache sans `sync` une valeur qu'on évince | une lecture en cours remet l'ancienne valeur après l'éviction | `sync = true` |

### Avec les transactions

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Évincer avant le commit (listener synchrone, `@CacheEvict` dans la transaction) | une requête concurrente remet l'ancienne valeur | Évincer après le commit |
| Lire le cache pour une décision d'écriture | la décision repose sur une valeur en retard | Lire la base au moment d'écrire |
| Changer la donnée sans publier l'événement (SQL natif compris) | aucune éviction : valeur périmée jusqu'au TTL | Publier dans chaque chemin |

### Avec plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Évincer seulement en local | les autres instances servent l'ancienne valeur jusqu'au TTL | Diffuser l'éviction |
| Choisir un TTL long en comptant sur la diffusion | un broker en panne prolonge le retard d'autant | Un TTL égal au retard acceptable |

## En résumé

**À retenir :** un cache Caffeine nommé et borné, `expireAfterWrite` pour borner le retard, `@Cacheable(sync = true)` pour la lecture, et l'éviction après le commit, diffusée aux autres instances.

- [ ] `spring.cache.type: caffeine` et la liste fermée `cache-names`
- [ ] Chaque cache réglé : `expireAfterWrite`, `maximumSize`, `recordStats`
- [ ] Jamais `expireAfterAccess` ni `refreshAfterWrite` pour une donnée de sécurité
- [ ] `@Cacheable(sync = true)` sur une méthode d'un autre bean, clé explicite pour un objet
- [ ] Un événement publié par chaque chemin qui change la donnée
- [ ] Éviction dans un `@TransactionalEventListener`, avec `fallbackExecution`
- [ ] Éviction diffusée aux autres instances, TTL égal au retard acceptable
- [ ] Les décisions d'écriture lisent la base, pas le cache
- [ ] Caches vidés entre les tests
- [ ] Taux de succès suivi avec `cache.gets`

## Dans ce dépôt

Le cache du statut, de la requête à l'éviction :

```
  requête GET /api/v1/tasks
       |
       v
  ActiveUserAuthorizationManager
       |  UserStatusLookup.statusOf(id)        @Cacheable(sync = true), 30 s
       |      |
       |      +-- défaut --> findAccountStateById --> statut rangé
       v
  ACTIVE ? -- non --> 403

  UserService.disable(id)                      transaction
       |  AccountStateChanged
       +--> UserStatusCacheEviction            après le commit : évince ici
       +--> RealtimeBroadcasts --> outbox --> AccountStatusChanged
                                                   |
                         chaque instance : RealtimeListener --> évince chez elle
```

| Fichier | Rôle |
|---|---|
| [`CacheConfiguration`][cache-config] | `@EnableCaching`, les deux caches, `setAllowNullValues(false)`, `reuseWindow` |
| [`StatusCacheProperties`][status-props] | `identity.status-cache`, durée bornée de 1 s à 1 min |
| [`UserStatusLookup`][lookup] | `@Cacheable(cacheNames = "userStatus", sync = true)` ; un compte introuvable vaut `DELETED` |
| [`UserStatusCacheEviction`][eviction] | l'éviction après le commit, et `evict(UUID)` appelé à la réception d'une diffusion |
| [`ActiveUserAuthorizationManager`][auth-manager] | lit le statut en cache à chaque requête sur les tâches, notifications et exports (guide 08) |
| [`RealtimeListener`][realtime-listener] | évince à la réception de `AccountStatusChanged` (guide 24) |
| [`MediaService`][media-service] | `@Cacheable(cacheNames = "mediaDownloads", key = "#media.id")` sur `downloadUrl` |
| [`MediaUrls`][media-urls] | appelle `MediaService`, jamais l'inverse |

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.cache.type` | `caffeine` | Caffeine pour tous les caches |
| `spring.cache.cache-names` | `userStatus, mediaDownloads` | liste fermée |
| `identity.status-cache.ttl` | 30s (`IDENTITY_STATUS_CACHE_TTL`) | retard maximal d'un statut, entre 1 s et 1 min |
| `identity.status-cache.maximum-size` | 10000 | statuts gardés au plus |
| `storage.presigned-url-ttl` | 10m | validité d'une URL signée ; réutilisée 5 min au plus |

Ce qui est propre au dépôt :

- `userStatus` expire après écriture, jamais après accès : un client qui appelle en boucle avec le jeton d'un compte désactivé garderait sinon son entrée `ACTIVE` jusqu'à l'expiration du jeton.
- `sync = true` : sans lui, une requête qui avait lu `ACTIVE` juste avant le commit d'une désactivation rangeait cette valeur après l'éviction, et le compte gardait l'accès jusqu'au TTL. Spring refusant `unless` avec `sync`, tous les statuts sont mis en cache, refusés compris ; chaque changement de compte doit donc évincer.
- `UserStatusCacheEviction` est un `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`, en `@Order(Ordered.HIGHEST_PRECEDENCE)`, avec `evictIfPresent`. Spring ne fait que journaliser son exception : le TTL reste la borne.
- Les contrôles au moment d'écrire lisent le compte, pas le cache : l'assigné dans `TaskService` (`AssigneeNotActiveException`), les destinataires dans `TaskNotificationPublisher`.
- `mediaDownloads` : l'URL signée est la même pour chaque requête pendant sa fenêtre de réutilisation, pour que le navigateur réutilise le fichier déjà téléchargé. L'URL porte `Cache-Control: private, max-age=<validité>, immutable` (`S3ObjectStorage`, guide 28).
- Le `@Cacheable` est sur `MediaService` et non sur `MediaUrls` : `MediaUrls.avatarOf` appelle `of` dans sa propre classe et contournerait le proxy.

Les tests :

- [`CacheConfigurationTest`][cache-config-test] : expiration après écriture et taille, jamais après accès ni rafraîchissement, fenêtre de réutilisation, bornes du TTL au démarrage, nom mal tapé sans cache (`mistypedCacheNameGetsNoCache`).
- [`UserStatusLookupTest`][lookup-test] : la course lecture contre éviction (`lookupInFlightDuringAnEvictionDoesNotCacheTheStatusItReadBefore`), statut refusé caché aussi, lecture en échec qui ne range rien.
- [`TaskAccessStatusCacheApiTests`][cache-api-tests] : de bout en bout, refus dès la requête suivante, éviction seulement au commit, rien après un rollback.
- [`ClearCachesAfterEach`][clear-caches] : vide tous les caches après chaque test d'intégration, car les tests qui changent un compte en SQL ne publient aucun `AccountStateChanged`.
- [`MonitoringTests`][monitoring-tests] vérifie `cache.gets` (succès et défauts) pour les deux caches.

Pour ajouter un cache : son nom dans `spring.cache.cache-names` et une constante, son réglage dans `CacheConfiguration` (`expireAfterWrite`, `maximumSize`, `recordStats`), `@Cacheable(sync = true)` sur une méthode appelée depuis un autre bean, et une éviction après le commit pour chaque chemin qui change la donnée.

## Voir aussi

- [08. Autorisations](08-autorisations.md) : la règle qui lit le statut en cache à chaque requête.
- [16. Événements applicatifs et `@Async`](16-evenements-async.md) : `@TransactionalEventListener` et `fallbackExecution`.
- [24. Flux de notifications (SSE)](24-flux-notifications-sse.md) : la diffusion à toutes les instances.
- [28. Uploads et drivers de stockage](28-uploads-stockage.md) : les URL signées mises en cache.
- Documentation officielle : [cache dans Spring](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html), [cache dans Spring Boot](https://docs.spring.io/spring-boot/reference/io/caching.html), [Caffeine](https://github.com/ben-manes/caffeine).

[Sommaire](README.md) | [< 16. Événements applicatifs et `@Async`](16-evenements-async.md) | [18. Migrations Liquibase >](18-migrations-liquibase.md)

[cache-config]: ../../src/main/java/io/julienmetral/tasks/config/CacheConfiguration.java
[status-props]: ../../src/main/java/io/julienmetral/tasks/config/StatusCacheProperties.java
[lookup]: ../../src/main/java/io/julienmetral/tasks/identity/security/UserStatusLookup.java
[eviction]: ../../src/main/java/io/julienmetral/tasks/identity/security/UserStatusCacheEviction.java
[auth-manager]: ../../src/main/java/io/julienmetral/tasks/identity/security/ActiveUserAuthorizationManager.java
[realtime-listener]: ../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeListener.java
[media-service]: ../../src/main/java/io/julienmetral/tasks/media/services/MediaService.java
[media-urls]: ../../src/main/java/io/julienmetral/tasks/media/services/MediaUrls.java
[cache-config-test]: ../../src/test/java/io/julienmetral/tasks/config/CacheConfigurationTest.java
[lookup-test]: ../../src/test/java/io/julienmetral/tasks/identity/security/UserStatusLookupTest.java
[cache-api-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskAccessStatusCacheApiTests.java
[clear-caches]: ../../src/test/java/io/julienmetral/tasks/support/ClearCachesAfterEach.java
[monitoring-tests]: ../../src/test/java/io/julienmetral/tasks/MonitoringTests.java
