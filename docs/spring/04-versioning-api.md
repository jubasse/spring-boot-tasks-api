# 04. Versioning d'API

[Sommaire](README.md) | [< 03. Contrôleurs MVC](03-controleurs-mvc.md) | [05. Validation et erreurs >](05-validation-erreurs.md)

## Contexte

- **Problème** : changer le format d'un endpoint sans casser les clients déjà en production, qui attendent l'ancien.
- **Quand l'utiliser** : une API publique, ou des clients qu'on ne met pas à jour avec le serveur (application mobile, partenaires).
- **Quand s'en passer** : un front livré avec l'API, ou des évolutions toujours compatibles ; un seul préfixe `v1` suffit alors pour toujours.
- **Dans ce dépôt** : toutes les routes sous `/api/v1/...`, une seule version supportée, des contrôleurs écrits sans préfixe.
- **Prérequis** : guides 03 (contrôleurs) et 08 (autorisations). Versions : Spring Boot 4.1, Spring Framework 7.0 (7.0.6 au moins pour le filtre de chemin).

Où le client indique la version :

| Stratégie | Requête | Quand la choisir |
|---|---|---|
| Segment de chemin | `GET /api/v1/orders` | API publique : version visible, liens partageables, caches simples |
| En-tête | `GET /orders` et `API-Version: 1` | URLs stables, clients maîtrisés |
| Paramètre du type de média | `Accept: application/json;version=1` | API qui versionne ses formats plutôt que ses routes |
| Paramètre de requête | `GET /orders?version=1` | essais rapides ; il se mélange aux filtres |

Spring Framework 7 sait lire les quatre. Avant lui, on écrivait la version à la main dans chaque `@RequestMapping`.

## Concepts clés

### Vocabulaire

- **Changement cassant** : une modification qui fait échouer un client existant (champ renommé ou retiré, type changé, champ devenu obligatoire).
- **Version supportée** : une version que le serveur accepte ; toute autre répond 400.
- **Parseur de version** : le code qui transforme le texte `v1` en une valeur comparable.
- **Version de base** (baseline) : `"2+"`, une méthode valable pour la version 2 et toutes les suivantes.
- **Dépréciation** : l'annonce qu'une version va disparaître, portée par les en-têtes `Deprecation` et `Sunset`.

### Ce qui demande une nouvelle version

| Changement | Cassant | Nouvelle version |
|---|---|---|
| Ajouter un champ à une réponse | non, si le client ignore les champs inconnus | non |
| Ajouter un endpoint, un paramètre facultatif | non | non |
| Renommer ou retirer un champ | oui | oui |
| Rendre obligatoire un champ de la requête | oui | oui |
| Changer le sens d'un code de statut | oui | oui |

On versionne une opération, pas toute l'API : une v2 de `GET /orders/{id}` laisse les autres routes inchangées, et elles répondent aux deux versions.

### Le trajet d'une requête versionnée

La version n'est lue qu'une fois la sécurité passée :

```
 GET /api/v1/orders/42
        |
        v
 +-----------------------------+
 |  filtres de sécurité        |   règles écrites sur /api/*/orders/**
 +-----------------------------+
        |
        v
 +-----------------------------+
 |  DispatcherServlet          |
 +-----------------------------+
        |
        v
 lire le segment 1 : "v1"
        |
        v
 le parseur le comprend ? ------- non --> 400
        | oui
        v
 version supportée ? ------------ non --> 400
        | oui
        v
 choisir la méthode selon le chemin et la version
```

- Les segments se comptent à partir de 0 : dans `/api/v1/orders`, `api` est le segment 0, `v1` le segment 1.
- La sécurité voit le chemin brut, sans rien savoir des versions : ses règles doivent couvrir toutes les écritures acceptées.

### Choisir la méthode selon la version

Une méthode sans attribut `version` répond à toutes les versions supportées. Quand deux méthodes servent le même chemin (v1, v2 et v3 supportées), la méthode appelée :

| Méthodes déclarées | Requête v1 | Requête v2 | Requête v3 |
|---|---|---|---|
| une seule, sans version | celle-ci | celle-ci | celle-ci |
| sans version, et `version = "2"` | sans version | `"2"` | 400 |
| sans version, et `version = "2+"` | sans version | `"2+"` | `"2+"` |

- Spring préfère la méthode versionnée la plus haute qui ne dépasse pas la version demandée.
- Une version fixe (`"2"`) refuse toute autre version : en v3, la méthode `"2"` est choisie, puis rejetée.

### Une seule écriture par version

Les règles de sécurité, les reverse proxies et les journaux comparent le texte du chemin. Si le serveur accepte plusieurs écritures d'une même version, une règle écrite pour l'une ignore les autres :

```
 règle : /api/v1/orders/**  --> rôle CUSTOMER exigé

 /api/v1/orders/42    --> règle appliquée --> 403 sans le rôle
 /api/v1.0/orders/42  --> règle ignorée   --> le parseur lit la version 1
                                          --> 200, la commande
```

- Le parseur par défaut, `SemanticApiVersionParser`, saute les caractères non numériques en tête et lit les versions mineures : `v1`, `v1.0`, `v01`, `vv1` et `1.0.0` désignent tous la version 1.0.0.
- Deux parades, à cumuler : un parseur strict, et des règles sur toutes les versions (`/api/*/...`).

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  order/
    OrderController.java              le contrôleur (étapes 1 et 7)
  config/
    ApiVersioningConfiguration.java   préfixe et version (étapes 2, 3, 8)
    PathVersionParser.java            une écriture par version (étape 4)
    SecurityConfiguration.java        règles de sécurité (étape 5)
src/test/java/.../
  ApiVersioningWebMvcTests.java       la vérification (étapes 6 et 9)
```

### Une version dans le chemin

**Étape 1. Écrire les contrôleurs sans préfixe.** Un seul endroit ajoute `/api` et la version.

```java
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
class OrderController {

    private final OrderService orderService;   // un service de l'application (guide 03)

    @GetMapping("/{id}")
    OrderResponse getOrder(@PathVariable UUID id) {   // OrderResponse : un record de réponse (guide 03)
        return new OrderResponse(orderService.find(id));
    }
}
```

**Étape 2. Ajouter le préfixe aux contrôleurs de l'application.** Le prédicat exclut ceux des bibliothèques (springdoc, `/error`).

```java
@Configuration
public class ApiVersioningConfiguration implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Application : la classe @SpringBootApplication, à la racine des paquets
        configurer.addPathPrefix("/api/v{version}",
                HandlerTypePredicate.forBasePackageClass(Application.class));
    }
}
```

**Étape 3. Lire la version dans le chemin.** Même classe : le segment 1, sous `/api/` seulement.

```java
@Override
public void configureApiVersioning(ApiVersionConfigurer configurer) {
    configurer
            .usePathSegment(1, path -> path.pathWithinApplication().value().startsWith("/api/"))
            .setVersionParser(new PathVersionParser())   // étape 4
            .setVersionRequired(false)                   // hors de /api/, pas de version, pas de 400
            .addSupportedVersions("1");                  // aucun mapping n'écrit "1" : le déclarer ici
}
```

**Étape 4. N'accepter qu'une écriture par version.** `v1` dans un chemin, `1` dans la configuration, rien d'autre.

```java
class PathVersionParser implements ApiVersionParser<Integer> {

    private static final Pattern VERSION = Pattern.compile("v?([1-9][0-9]{0,2})");

    @Override
    public Integer parseVersion(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) {
            // Spring transforme l'exception en InvalidApiVersionException : 400
            throw new IllegalArgumentException("Not an API version: " + version);
        }
        return Integer.valueOf(matcher.group(1));
    }
}
```

**Étape 5. Écrire les règles de sécurité pour toutes les versions.** `*` couvre tout un segment.

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/*/orders/**").hasRole("CUSTOMER")
        .anyRequest().authenticated());
```

**Étape 6. Vérifier.** Un test de la couche web (guide 29) : versions refusées, puis chemin sans préfixe.

```java
@WebMvcTest(OrderController.class)       // charge aussi les WebMvcConfigurer, donc les étapes 2 et 3
@Import(SecurityConfiguration.class)     // les règles de l'étape 5 (guide 07)
class ApiVersioningWebMvcTests {

    private static final RequestPostProcessor CUSTOMER =
            jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderService orderService;

    @ParameterizedTest
    @ValueSource(strings = {"v2", "v0", "v1.0", "v01", "vv1"})
    void otherVersionsAndSpellingsAreRefused(String version) throws Exception {
        mockMvc.perform(get("/api/{version}/orders/{id}", version, UUID.randomUUID()).with(CUSTOMER))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    void controllerPathWithoutThePrefixIsNotServed() throws Exception {
        mockMvc.perform(get("/orders/{id}", UUID.randomUUID()).with(CUSTOMER))
                .andExpect(status().isNotFound());
    }
}
```

### Publier une version 2

**Étape 7. Ajouter la méthode de la version 2.** Même chemin, autre méthode ; l'ancienne continue de servir la v1.

```java
// dans OrderController, à côté de getOrder
@GetMapping(path = "/{id}", version = "2+")      // la v2 et les suivantes
OrderResponseV2 getOrderV2(@PathVariable UUID id) {   // OrderResponseV2 : le nouveau format
    return new OrderResponseV2(orderService.find(id));
}
```

**Étape 8. Annoncer la fin de la version 1.** Le gestionnaire ajoute `Deprecation` et `Sunset` aux réponses v1, avec le même parseur que les requêtes.

```java
@Override
public void configureApiVersioning(ApiVersionConfigurer configurer) {
    PathVersionParser parser = new PathVersionParser();
    StandardApiVersionDeprecationHandler deprecations = new StandardApiVersionDeprecationHandler(parser);
    deprecations.configureVersion("1")
            .setDeprecationDate(ZonedDateTime.parse("2027-01-01T00:00:00Z"))
            .setSunsetDate(ZonedDateTime.parse("2027-07-01T00:00:00Z"))
            .setSunsetLink(URI.create("https://example.com/docs/migration-v2"));

    configurer
            .usePathSegment(1, path -> path.pathWithinApplication().value().startsWith("/api/"))
            .setVersionParser(parser)
            .setVersionRequired(false)
            .addSupportedVersions("1", "2")
            .setDeprecationHandler(deprecations);
}
```

Les en-têtes reçus par un client v1 :

```
Deprecation: @1798761600
Sunset: Thu, 1 Jul 2027 00:00:00 GMT
Link: <https://example.com/docs/migration-v2>; rel="sunset"; type="text/html"
```

**Étape 9. Vérifier.** Dans la même classe de test, la v1 annonce sa fin, et la v2 passe par la nouvelle méthode.

```java
@Test
void versionOneAnnouncesItsEnd() throws Exception {
    when(orderService.find(any())).thenReturn(ORDER);    // ORDER : une commande de test

    mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()).with(CUSTOMER))
            .andExpect(status().isOk())
            .andExpect(header().string("Deprecation", "@1798761600"))
            .andExpect(header().exists("Sunset"));
}

@Test
void versionTwoIsServedByItsOwnMethod() throws Exception {
    when(orderService.find(any())).thenReturn(ORDER);

    mockMvc.perform(get("/api/v2/orders/{id}", UUID.randomUUID()).with(CUSTOMER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statusLabel").exists())    // statusLabel : un champ du seul format v2
            .andExpect(header().doesNotExist("Deprecation"));
}
```

### Référence rapide

Les méthodes de `ApiVersionConfigurer` :

| Méthode | Rôle |
|---|---|
| `usePathSegment(index)` | lit la version dans un segment du chemin, pour toutes les requêtes |
| `usePathSegment(index, predicate)` | idem, seulement pour les chemins retenus (7.0.6) |
| `useRequestHeader(name)` | lit la version dans un en-tête |
| `useQueryParam(name)` | lit la version dans un paramètre de requête |
| `useMediaTypeParameter(type, name)` | lit un paramètre du type de média de `Accept` ou `Content-Type` |
| `setVersionParser(parser)` | remplace `SemanticApiVersionParser` |
| `addSupportedVersions(...)` | versions acceptées, en plus de celles écrites dans les mappings |
| `detectSupportedVersions(false)` | n'accepte que les versions déclarées par `addSupportedVersions` |
| `setVersionRequired(...)` | requête sans version : 400 si `true` (le défaut, sauf avec une version par défaut), acceptée si `false` |
| `setDefaultVersion(version)` | version donnée aux requêtes qui n'en portent pas |
| `setDeprecationHandler(handler)` | ajoute les en-têtes de dépréciation |

Les exceptions, toutes des `ResponseStatusException` en 400 :

| Exception | Cause |
|---|---|
| `InvalidApiVersionException` | version illisible par le parseur, ou non supportée |
| `MissingApiVersionException` | requête sans version, alors qu'elle est exigée |
| `NotAcceptableApiVersionException` | méthode à version fixe, choisie pour une autre version |

Sans prédicat de chemin, Spring Boot configure la même chose par propriétés sous `spring.mvc.apiversion` (`use.path-segment`, `use.header`, `use.query-parameter`, `use.media-type-parameter`, `supported`, `required`, `default`, `detect-supported`). Il reprend aussi un bean `ApiVersionParser`, `ApiVersionResolver` ou `ApiVersionDeprecationHandler` s'il en trouve un.

Limites de cette version : rien ne mesure qui appelle encore une version dépréciée.

## Concepts avancés

### Sous le capot : la version est jugée après le chemin

Spring valide la version en cherchant la méthode, et ne lève l'erreur que si une méthode correspond au chemin :

```
 /api/v2/orders/42  --> une méthode correspond --> version refusée --> 400
 /api/v2/unknown    --> aucune méthode         --> 404, version non jugée
 /api/V1/orders/42  --> "V1" ne suit pas le motif v{version} --> 404
```

- La méthode est choisie avant la lecture du corps : une version refusée répond 400 sans erreurs de validation.
- La sécurité passe avant : un appel anonyme vers `/api/v2/...` reçoit 401, pas 400.
- `{version}` est une variable de chemin comme une autre, que les contrôleurs ignorent.

### Sous le capot : les versions détectées

Au démarrage, Spring ajoute à la liste supportée les versions écrites dans les mappings. Deux conséquences :

- La version initiale, écrite dans aucun mapping, n'est jamais détectée : sans `addSupportedVersions("1")`, toute l'API répond 400.
- Une seule méthode `version = "2+"` ouvre `/api/v2/` à toutes les routes sans version. C'est le but, mais tout ce qui énumère des chemins `/api/v1` (endpoints publics, liens, documentation) doit suivre.

Avec `detectSupportedVersions(false)`, une v2 se prépare dans le code sans être ouverte.

### Sous le capot : pourquoi le prédicat de chemin

`usePathSegment(1)` seul lit le segment 1 de toutes les requêtes. Sur `/v3/api-docs`, il trouve `api-docs`, que le parseur refuse : la documentation répond 400. Le prédicat déclare ces chemins sans version, et `setVersionRequired(false)` les laisse passer.

### Plusieurs instances

Pendant un déploiement progressif, seules les nouvelles instances connaissent la v2 :

```
 client v2 --> reverse proxy --+--> nouvelle instance --> 200
                               |
                               +--> ancienne instance --> 400, v2 inconnue
```

- Annoncer une version seulement quand toutes les instances la servent.
- Retirer une version dans l'ordre inverse : les clients d'abord (`Deprecation`, `Sunset`, puis mesure), le code ensuite.

### Surveiller

La métrique `http.server.requests` porte le motif de la route dans son tag `uri` : `/api/v{version}/orders/{id}`, une seule série pour toutes les versions. Pour savoir qui appelle encore la v1 avant de la retirer, on lit le chemin réel dans les journaux d'accès du reverse proxy.

## Pièges à éviter

### Avec le versioning de Spring

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder le parseur par défaut | `v1.0`, `v01` et `vv1` désignent aussi la v1 | Fournir un parseur qui n'accepte qu'une écriture |
| Écrire les règles de sécurité sur `/api/v1/...` | une autre écriture ou une autre version y échappe | Écrire les règles sur `/api/*/...` |
| Appeler `usePathSegment(1)` sans prédicat | les chemins hors de l'API, `/v3/api-docs` compris, répondent 400 | Limiter la lecture à `/api/` |
| Oublier `addSupportedVersions("1")` | la v1, écrite dans aucun mapping, répond 400 partout | Déclarer la version initiale |
| Écrire `/api/v1` dans un contrôleur | la route devient `/api/v{version}/api/v1/...` | Écrire le chemin sans préfixe |
| Donner au gestionnaire de dépréciation un autre parseur | les versions ne sont jamais égales : aucun en-tête | Lui passer le parseur des requêtes |

### Dans ce dépôt, en ajoutant une version 2

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser `PublicEndpoints` en `/api/v1` seulement | le login en v2 exige un jeton : 401 | Ajouter les chemins publics de la v2 |
| Garder les `Location` écrits avec `/api/v1` | un client v2 reçoit un lien vers la v1 | Bâtir le lien depuis la requête (`fromCurrentRequestUri`) |
| Oublier `PathDocumentation` | ses règles ne connaissent que `/api/v1` | Les étendre, puis relire `docs/openapi.json` (guide 06) |

## En résumé

**À retenir :** les contrôleurs s'écrivent sans préfixe, `/api/v{version}` s'ajoute une fois par `addPathPrefix`, Spring Framework 7 lit la version dans le chemin avec un parseur strict, et les règles de sécurité valent pour toutes les versions.

- [ ] Contrôleurs sans `/api` ni version dans leurs chemins
- [ ] `addPathPrefix` limité aux contrôleurs de l'application
- [ ] `usePathSegment` avec un prédicat sur `/api/`, et `setVersionRequired(false)`
- [ ] Un parseur qui n'accepte qu'une écriture par version
- [ ] La version initiale déclarée dans `addSupportedVersions`
- [ ] Règles de sécurité écrites sur `/api/*/...`
- [ ] Une nouvelle version seulement pour un changement cassant, sur la seule opération concernée
- [ ] `"2+"` plutôt que `"2"` pour une méthode qui doit durer
- [ ] `Deprecation` et `Sunset` envoyés avant de retirer une version, avec le même parseur
- [ ] Des tests sur les versions refusées, les autres écritures et le chemin sans préfixe

## Dans ce dépôt

Le trajet de `GET /api/v1/tasks` :

```
  SecurityConfiguration                /api/*/tasks/** : compte actif exigé,
       |                               quelle que soit la version
       v
  DispatcherServlet
       |
       v
  ApiVersioningConfiguration           chemin sous /api/ : segment 1 = "v1"
       |
       v
  PathVersionParser                    "v1" --> 1
       |
       v
  versions supportées : {1}            sinon 400, détail de messages.properties
       |
       v
  TaskController.listTasks             mappé "/tasks", sous /api/v{version}
```

| Fichier | Rôle |
|---|---|
| [`ApiVersioningConfiguration`][versioning] | préfixe, segment 1 sous `/api/`, version 1, gestionnaire de dépréciation sans date pour l'instant |
| [`PathVersionParser`][parser] | `v1` ou `1`, de 1 à 999, aucune autre écriture |
| [`SecurityConfiguration`][security] | règles `/api/*/tasks/**`, `/api/*/notifications/**` et `/api/*/exports/**` |
| [`PublicEndpoints`][public] | les endpoints publics, écrits pour `/api/v1` seulement |
| [`IdenticonController`][identicon] | construit ses liens avec `ApiVersioningConfiguration.CURRENT_PATH` |
| [`PathDocumentation`][path-doc] | remplace `/api/v{version}/` par `/api/v1/` dans la documentation (guide 06) |
| [`messages.properties`][messages] | le détail du 400 : « This API version is not supported: use /api/v1 » |

Réglages de `ApiVersioningConfiguration` : préfixe `/api/v{version}` pour le paquet de `TasksApplication`, segment 1 sous `/api/` seulement, `PathVersionParser`, `setVersionRequired(false)`, version `1` (`CURRENT_VERSION`) et un `StandardApiVersionDeprecationHandler(parser)` prêt pour les dates de retrait de la v1.

Ce qui est propre au dépôt :

- Avant `PathVersionParser`, le parseur par défaut servait `/api/v1.0/` et `/api/v01/` comme la v1, alors que les règles de sécurité étaient écrites pour `/api/v1/` : un compte désactivé listait les tâches par `/api/v1.0/tasks`. Deux corrections depuis : le parseur strict, et les règles en `/api/*/`.
- Une version refusée répond un problème `about:blank` en 400 ; un chemin qui ne mène à aucune méthode répond 404, « No endpoint matches this path » (guide 05).
- Les `Location` des créations écrivent `/api/v1/...` à la main (`TaskController`, `UserController`, `DataExportController` et d'autres).
- Les endpoints publics ne le sont que sous `/api/v1` : `POST /api/v2/auth/login` répond 401, et un test le vérifie.

Les tests :

- [`ApiVersioningWebMvcTests`][web-tests] : la v1 servie, avec son `Location` et sans en-tête de dépréciation ; `v2`, `v0`, `v1.5`, `vx` et `v` refusés en 400 avant le service ; `v1.0`, `v1.0.0`, `v01` et `vv1` fermés à un compte désactivé ou non vérifié ; `/tasks`, `/api/V1/tasks` et `/api/v2/unknown` en 404.
- [`ApiVersioningTests`][tests] : chaque route commence par `/api/v{version}/` sans répéter `/api/` ou `/vN/`, springdoc et `/error` gardent leurs chemins, et la documentation ne montre que `/api/v1/...`, sans paramètre `version`.

Pour ajouter une version 2 : une méthode `version = "2+"` à côté de l'ancienne, `"2"` dans `addSupportedVersions`, les chemins v2 dans `PublicEndpoints` et dans les règles de `PathDocumentation`, puis les dates de retrait de la v1 dans le gestionnaire de dépréciation (le Javadoc de `configureApiVersioning` les décrit).

## Voir aussi

- [03. Contrôleurs MVC](03-controleurs-mvc.md) : les contrôleurs sans préfixe et l'en-tête `Location`.
- [05. Validation et erreurs](05-validation-erreurs.md) : le problème 400 et les détails reformulés dans `messages.properties`.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : le préfixe brut ramené à `/api/v1` dans la documentation.
- [08. Autorisations](08-autorisations.md) : les règles par URL et le compte actif.
- [29. Couches de tests et contextes](29-tests-couches-contextes.md) : `@WebLayerTest` et les tests de la couche web.
- Documentation officielle : [API Versioning (Spring Framework)](https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html), [RFC 9745, l'en-tête Deprecation](https://datatracker.ietf.org/doc/html/rfc9745).

[Sommaire](README.md) | [< 03. Contrôleurs MVC](03-controleurs-mvc.md) | [05. Validation et erreurs >](05-validation-erreurs.md)

[versioning]: ../../src/main/java/io/julienmetral/tasks/config/ApiVersioningConfiguration.java
[parser]: ../../src/main/java/io/julienmetral/tasks/config/PathVersionParser.java
[security]: ../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[public]: ../../src/main/java/io/julienmetral/tasks/identity/security/PublicEndpoints.java
[identicon]: ../../src/main/java/io/julienmetral/tasks/identity/controllers/IdenticonController.java
[path-doc]: ../../src/main/java/io/julienmetral/tasks/config/PathDocumentation.java
[messages]: ../../src/main/resources/messages.properties
[web-tests]: ../../src/test/java/io/julienmetral/tasks/config/ApiVersioningWebMvcTests.java
[tests]: ../../src/test/java/io/julienmetral/tasks/config/ApiVersioningTests.java
