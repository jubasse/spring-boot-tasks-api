# 06. OpenAPI avec springdoc

[Sommaire](README.md) | [< 05. Validation et erreurs](05-validation-erreurs.md) | [07. Chaîne de filtres et JWT >](07-securite-jwt.md)

## Contexte

- **Problème** : décrire l'API à ses clients (chemins, corps, réponses, erreurs), sans tenir à la main un document qui s'écarte du code.
- **Quand l'utiliser** : toute API HTTP appelée par un autre programme, surtout avec des clients générés.
- **Quand s'en passer** : une API interne à un seul client du même dépôt, ou des échanges par messages (AsyncAPI).
- **Dans ce dépôt** : le document servi à `/v3/api-docs` et Swagger UI, complétés par du code dans `config`, figés dans `docs/openapi.json` et contrôlés en CI.
- **Prérequis** : guides 03 (contrôleurs) et 05 (erreurs). Versions : Spring Boot 4.1, springdoc-openapi 3.1.1, OpenAPI 3.1.

Les façons de produire la description d'une API :

| Approche | Principe | Quand la choisir |
|---|---|---|
| Code d'abord (springdoc) | le document est déduit des contrôleurs | l'API naît dans le code, cas courant |
| Contrat d'abord (openapi-generator) | on écrit le YAML, puis on génère interfaces et DTO | plusieurs équipes s'accordent avant de coder |
| Spring REST Docs | la documentation naît des tests qui appellent l'API | documentation rédigée, chaque exemple prouvé par un test |
| Fichier tenu à la main | on décrit l'API à côté du code | à éviter : il s'écarte du code sans que rien ne le signale |

## Concepts clés

### Vocabulaire

- **OpenAPI** : un format standard, en JSON ou YAML, qui décrit une API HTTP (chemins, paramètres, corps, réponses, sécurité).
- **Opération** : une méthode HTTP sur un chemin, nommée par son `operationId`.
- **Composant** : un schéma, une réponse ou un schéma de sécurité décrit une fois, puis référencé par `$ref`.
- **Swagger UI** : une page web qui affiche le document et permet d'appeler l'API depuis le navigateur.
- **Rupture de contrat** (breaking change) : un changement du document qui fait échouer un client existant.

### La construction du document

springdoc construit le document à la première requête, couche par couche :

```
       1re requête sur /v3/api-docs
                 |
                 v
       bean OpenAPI                  la base écrite à la main : titre,
                 |                   sécurité, composants partagés
                 v
       chaque méthode de contrôleur  annotations Spring et Swagger
                 |                   --> une opération
                 v
       OperationCustomizer           une opération à la fois,
                 |                   avec la méthode Java qui la sert
                 v
       OpenApiCustomizer             le document entier, chemins compris
                 |
                 v
       document en cache --> JSON servi, lu par Swagger UI
```

- Un `OperationCustomizer` voit la méthode Java et ses annotations, un marqueur maison par exemple.
- Un `OpenApiCustomizer` voit tous les chemins. Il applique une règle de chemin ou touche tout le document.
- Le document est mis en cache, donc les customizers ne tournent pas à chaque requête.

### Ce que springdoc déduit, et ce qu'il ignore

springdoc lit les annotations et les types Java. Le reste, il faut le lui dire :

| Information | springdoc la lit dans | Remarque |
|---|---|---|
| Chemin et méthode HTTP | `@GetMapping`, `@PostMapping`... | |
| Paramètres et corps | `@PathVariable`, `@RequestParam`, `@RequestBody` | `@ParameterObject` développe un objet en paramètres |
| Schéma d'un corps | les records et leurs contraintes Bean Validation | `@NotBlank` rend le champ `required`, `@Size` donne `maxLength` |
| Code de succès | `@ResponseStatus`, sinon 200 | un `ResponseEntity.created(...)` ne se voit qu'à l'exécution |
| `operationId` | le nom de la méthode | suffixé `_1` si deux méthodes portent le même nom |
| Résumé, groupe | `@Operation(summary)`, `@Tag` | |
| Erreurs, en-têtes, endpoints publics | nulle part | à ajouter par du code |

### Un contrat figé dans le dépôt

Le document généré est aussi un fichier versionné :

```
       code des contrôleurs
                 |
                 v
       document servi (/v3/api-docs)
                 |
                 v
       test : identique au fichier commité ? -- non --> build en échec
                 | oui                                  (régénérer, relire)
                 v
       docs/openapi.json
                 |
                 v
       CI : lint (règles de style)
            + comparaison avec la branche cible -- rupture --> PR refusée
```

- Le diff d'une pull request montre ce qui change pour les clients. Un client se génère depuis le fichier, sans démarrer l'application.

| Changement | Effet |
|---|---|
| Ajouter un endpoint, un paramètre facultatif, un champ de réponse | compatible |
| Rendre obligatoire un champ de requête | rupture |
| Supprimer ou renommer un champ, un chemin | rupture |
| Ajouter une valeur à une énumération de réponse | rupture pour un client strict, sinon compatible |

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                               la dépendance (étape 1)
src/main/resources/
  application.yaml                    les réglages de springdoc (étape 2)
src/main/java/.../
  config/
    OpenApiConfiguration.java         le bean OpenAPI (étapes 3 et 7)
    SecurityConfiguration.java        ouvre la documentation (étapes 5 et 8)
    RateLimitDocumentation.java       un OperationCustomizer (étape 6)
    CommonResponses.java              un OpenApiCustomizer (étape 9)
  shared/
    RateLimited.java                  un marqueur (étape 6)
    PublicEndpoints.java              la liste partagée (étape 8)
  invoice/
    InvoiceController.java            un contrôleur annoté (étape 4)
docs/
  openapi.json                        le document figé (étape 10)
src/test/java/.../
  OpenApiSpecTests.java               la vérification (étape 10)
```

### Un document généré : springdoc seul

**Étape 1. Ajouter la dépendance.** Le starter sert le document et Swagger UI. Spring Boot ne gère pas sa version.

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

**Étape 2. Régler springdoc.** Ces réglages rendent le document plus juste et sa sortie stable.

```yaml
springdoc:
  paths-to-match: /api/**                        # l'API seulement, ni /error ni les sondes
  default-produces-media-type: application/json  # sinon */* dans chaque réponse
  allowed-locales: en                            # un seul document en cache (voir les pièges)
  override-with-generic-response: false          # voir les pièges
  writer-with-order-by-keys: true                # clés triées : un diff lisible
  writer-with-default-pretty-printer: true
```

**Étape 3. Déclarer le bean `OpenAPI`.** springdoc part de lui. On y met ce qu'aucun contrôleur ne dit.

```java
@Configuration
class OpenApiConfiguration {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Invoices API").version("1.0")
                        .description("Comment s'authentifier, les erreurs, les limites."))
                .servers(List.of(new Server().url("/")))            // relatif : vaut pour tout hôte
                .addSecurityItem(new SecurityRequirement().addList("bearer"))   // jeton exigé partout
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
```

**Étape 4. Annoter les contrôleurs.** Un `@Tag`, un résumé par opération, le succès en `@ResponseStatus`.

```java
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Les factures et leur paiement.")
public class InvoiceController {

    private final InvoiceService invoiceService;        // le service et les records : guide 03

    @Operation(summary = "Create an invoice")
    @ResponseStatus(HttpStatus.CREATED)                  // documente 201, pas 200
    @PostMapping
    public ResponseEntity<InvoiceResponse> createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        Invoice invoice = invoiceService.create(request);
        return ResponseEntity.created(URI.create("/api/invoices/" + invoice.getId()))
                .body(new InvoiceResponse(invoice));
    }

    @Operation(summary = "List invoices")
    @GetMapping
    public PagedModel<InvoiceResponse> listInvoices(@ParameterObject Pageable pageable) {  // page, size, sort
        return new PagedModel<>(invoiceService.findAll(pageable).map(InvoiceResponse::new));
    }
}
```

**Étape 5. Ouvrir la documentation dans la sécurité.** Sans cette règle, `/v3/api-docs` et Swagger UI répondent 401 (guide 07).

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**")
        .permitAll()
        // ... les autres règles
);
```

### Un document enrichi par du code

**Étape 6. Documenter une règle par un marqueur.** Une annotation sans effet, lue par un `OperationCustomizer`.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)       // lue par réflexion
public @interface RateLimited {
}
```

```java
@Component
class RateLimitDocumentation implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (handlerMethod.hasMethodAnnotation(RateLimited.class)) {
            operation.getResponses().putIfAbsent("429", new ApiResponse()
                    .description("Too many requests: wait for the seconds of Retry-After."));
        }
        return operation;
    }
}
```

**Étape 7. Décrire les erreurs à la main.** Le schéma tiré de `ProblemDetail` ne correspond pas au JSON envoyé (voir les pièges). `Problem` et les réponses partagées vont donc dans les composants du bean de l'étape 3.

```java
Schema<?> problem = new JsonSchema().types(Set.of("object"))
        .addProperty("type", new JsonSchema().types(Set.of("string")).format("uri"))
        .addProperty("title", new JsonSchema().types(Set.of("string")))
        .addProperty("status", new JsonSchema().types(Set.of("integer")))
        .addProperty("detail", new JsonSchema().types(Set.of("string")))
        .addProperty("instance", new JsonSchema().types(Set.of("string")).format("uri-reference"))
        .required(List.of("title", "status"));
// MediaType de io.swagger.v3.oas.models.media, pas celui de Spring
MediaType problemJson = new MediaType().schema(new JsonSchema().$ref("#/components/schemas/Problem"));

components
        .addSchemas("Problem", problem)
        .addResponses("Unauthorized", new ApiResponse().description("Missing or expired access token."))
        .addResponses("UnexpectedError", new ApiResponse()
                .description("An unexpected failure on the server.")
                .content(new Content().addMediaType("application/problem+json", problemJson)));
```

**Étape 8. Partager la liste des endpoints publics.** Sécurité et documentation lisent la même liste.

```java
public final class PublicEndpoints {

    public record Endpoint(HttpMethod method, String pattern) {
    }

    public static final List<Endpoint> ALL = List.of(
            new Endpoint(HttpMethod.POST, "/api/auth/login"),
            new Endpoint(HttpMethod.GET, "/api/invoices/*/pdf"));

    private PublicEndpoints() {
    }
}
```

```java
// dans SecurityConfiguration, avant anyRequest().authenticated()
PublicEndpoints.ALL.forEach(endpoint ->
        auth.requestMatchers(endpoint.method(), endpoint.pattern()).permitAll());
```

**Étape 9. Appliquer les règles de chemin.** Un `OpenApiCustomizer` retire le jeton des opérations publiques, ajoute un 401 aux autres, et une réponse `default` partout.

```java
@Component
class CommonResponses implements OpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            if (isPublic(method, path)) {
                operation.setSecurity(List.of());           // security: [] annule l'exigence globale
            } else {
                operation.getResponses().putIfAbsent("401", reference("Unauthorized"));
            }
            operation.getResponses().putIfAbsent("default", reference("UnexpectedError"));
        }));
    }

    private static boolean isPublic(PathItem.HttpMethod method, String path) {
        // le document écrit /api/invoices/{id}/pdf ; une valeur quelconque remplace {id}
        PathContainer concrete = PathContainer.parsePath(path.replaceAll("\\{[^}]+}", "x"));
        return PublicEndpoints.ALL.stream().anyMatch(endpoint ->
                endpoint.method().name().equals(method.name())
                && PathPatternParser.defaultInstance.parse(endpoint.pattern()).matches(concrete));
    }

    private static ApiResponse reference(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }
}
```

### Un document figé

**Étape 10. Vérifier.** Un test compare le document servi au fichier commité. Une propriété système réécrit ce fichier.

```java
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTests {

    private static final Path COMMITTED = Path.of("docs", "openapi.json");

    @Autowired
    MockMvc mockMvc;

    @Test
    void servedDocumentMatchesTheCommittedOne() throws Exception {
        String served = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        if (Boolean.getBoolean("openapi.update")) {        // -Dopenapi.update=true : réécrire le fichier
            Files.writeString(COMMITTED, served, StandardCharsets.UTF_8);
            return;
        }
        // comparaison JSON : l'ordre des clés ne compte pas
        JSONAssert.assertEquals(Files.readString(COMMITTED), served, JSONCompareMode.STRICT);
    }
}
```

Après un changement voulu, on régénère le fichier, on relit son diff, puis on le commite :

```bash
./mvnw test -Dtest=OpenApiSpecTests -Dopenapi.update=true
```

### Référence rapide

Les annotations de documentation :

| Annotation | Où | Rôle |
|---|---|---|
| `@Tag(name, description)` | classe du contrôleur | groupe les opérations dans Swagger UI |
| `@Operation(summary, description, operationId)` | méthode | résumé ; `operationId` remplace le nom de la méthode |
| `@ApiResponse(responseCode, description, ref)` | méthode, répétable | déclare une réponse (voir les pièges) |
| `@ResponseStatus` | méthode | code de succès documenté, et renvoyé |
| `@ParameterObject` | paramètre | développe un objet (`Pageable`) en paramètres |
| `@Hidden` | classe ou méthode | retire du document |

Les propriétés `springdoc.*` :

| Propriété | Par défaut | Rôle |
|---|---|---|
| `api-docs.enabled`, `swagger-ui.enabled` | `true` | servent `/v3/api-docs` et `/swagger-ui.html` |
| `paths-to-match` | tous les chemins | chemins documentés |
| `default-produces-media-type` | `*/*` | type des réponses sans `produces` |
| `override-with-generic-response` | actif | ajoute les réponses des `@ControllerAdvice` à chaque opération |
| `allowed-locales` | toutes | langues pour lesquelles un document est construit |

Limites de cette version : le document dit ce que l'API promet, pas ce qu'elle fait.

## Concepts avancés

### Sous le capot : un document par langue

springdoc construit un document par langue de `Accept-Language` (les textes peuvent être traduits) et garde chacun en cache.

- Avant springdoc 3.1.1, ce cache n'avait pas de limite. Un client qui variait l'en-tête faisait construire un document par requête, jusqu'à épuiser la mémoire.
- Depuis 3.1.1, le cache garde au plus `springdoc.cache.max-entries` documents (100 par défaut).
- Avec `allowed-locales: en`, toute langue est ramenée à `en`, d'où un seul document.

### Sous le capot : deux méthodes, une opération

Un endpoint JSON et multipart s'écrit en deux méthodes, distinguées par `consumes`. Avec le même `@Operation(operationId = ...)`, springdoc les fusionne en une opération à deux types de corps.

- Chaque méthode passe dans l'`OperationCustomizer`, qui doit compléter l'opération sans la remplacer.
- Un `@ModelAttribute` avec des `@RequestPart` est décrit sans ses fichiers : on reconstruit le schéma du formulaire.

### Contrôler le contrat en CI

Deux outils lisent le fichier commité dans la CI (guide 33) : Spectral pour le style, oasdiff pour la comparaison avec la branche cible :

```yaml
- name: Lint the OpenAPI document
  run: npx --yes @stoplight/spectral-cli@6.16.3 lint docs/openapi.json --fail-severity warn

- name: Detect breaking changes
  uses: oasdiff/oasdiff-action/breaking@<sha du commit>   # actions épinglées par SHA
  with:
    base: base-openapi.json          # extrait de la branche cible par git show
    revision: docs/openapi.json
    fail-on: ERR
```

- Spectral part du jeu `spectral:oas`, qu'un fichier `.spectral.yaml` ajuste.
- oasdiff classe chaque différence par niveau, et `fail-on: ERR` fait échouer le job sur une rupture. On peut abaisser un niveau quand l'API a annoncé le changement permis.

## Pièges à éviter

### Avec springdoc

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Déclarer un `@ApiResponse` sans `@ResponseStatus` | seules les réponses déclarées restent : le 200 disparaît | Déclarer aussi la réponse de succès |
| Renvoyer `ResponseEntity.created(...)` sans `@ResponseStatus` | le document annonce 200 | Poser `@ResponseStatus(HttpStatus.CREATED)` |
| Garder `override-with-generic-response` par défaut | chaque handler du `@ControllerAdvice` s'ajoute à chaque opération | Le mettre à `false` |
| Laisser toutes les langues avant springdoc 3.1.1 | un document gardé par valeur d'`Accept-Language` : mémoire épuisée | Fixer `allowed-locales` |
| Laisser springdoc décrire `ProblemDetail` | le schéma montre un objet `properties`, que le JSON aplatit | Écrire le schéma `Problem` à la main |
| Servir la documentation en production | toute la surface de l'API est exposée | L'éteindre sous `prod` |

### Avec un document figé

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Régénérer le fichier sans relire son diff | une rupture passe inaperçue | Relire le diff, et laisser oasdiff juger |
| Laisser la version du build dans `info.version` | le test échoue à chaque release | Remplacer la version par une valeur fixe avant de comparer |

### Dans ce dépôt

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire `@PreAuthorize` sur un contrôleur | la règle n'a pas de phrase : `AccessDescriptionTest` échoue | L'envelopper dans une annotation décrite |
| Poser une annotation de sécurité sur la classe | `OperationDocumentation` ne lit que la méthode : le test échoue | La poser sur chaque méthode |
| Ouvrir un endpoint hors de `PublicEndpoints` | le document exige un jeton : `OpenApiConformanceTests` échoue | L'ajouter à `PublicEndpoints` |
| Appeler `RateLimiter` sans `@RateLimited` | le 429 manque au document | Poser `@RateLimited` |
| Créer un `ProblemType` jamais documenté | `OpenApiDocumentTests` échoue : aucun exemple ne le montre | Le citer dans un `@DocumentedProblems` |

## En résumé

**À retenir :** springdoc déduit le document des contrôleurs. Ce qu'il ignore (erreurs, accès, endpoints publics) s'ajoute par des customizers, à partir de marqueurs et de listes partagées. Le document est figé dans le dépôt, comparé par un test et jugé en CI.

- [ ] `springdoc-openapi-starter-webmvc-ui`, avec `paths-to-match`, `allowed-locales` et `override-with-generic-response: false`
- [ ] Un bean `OpenAPI` : description, serveur relatif, sécurité bearer
- [ ] `@Tag`, `@Operation(summary)` et `@ResponseStatus` sur chaque contrôleur
- [ ] Un nom de méthode unique par opération
- [ ] Les règles répétées dans un `OperationCustomizer` ou un `OpenApiCustomizer`, jamais copiées sur chaque méthode
- [ ] Le schéma `Problem` écrit à la main
- [ ] Les endpoints publics dans une seule liste, lue par la sécurité et la documentation
- [ ] Le document commité et comparé par un test
- [ ] Spectral et oasdiff en CI
- [ ] Documentation éteinte en production

## Dans ce dépôt

Du contrôleur à la CI :

```
  contrôleurs                  @Tag, @Operation, @ResponseStatus,
       |                       @DocumentedProblems, @RateLimited,
       |                       annotations de sécurité
       v
  OpenApiConfiguration         bean OpenAPI : description, bearer,
       |                       schémas Problem, réponses partagées
       v
  OperationDocumentation       par méthode : accès et 403, problèmes typés,
       |                       fichiers (413, 415, 503), 429, Location
       v
  PathDocumentation            par chemin : /api/v1, public ou 401, 403,
       |                       400, 404, default, taille de page, pages
       v
  /v3/api-docs --> OpenApiSpecTests --> docs/openapi.json --> CI
```

| Fichier | Rôle |
|---|---|
| [`OpenApiConfiguration`][config] | le bean `OpenAPI` et les réponses partagées (`NotFound`, `Unauthorized`, `TooManyRequests`...) |
| [`OperationDocumentation`][operation] | `OperationCustomizer` : ce que disent les annotations de la méthode |
| [`PathDocumentation`][path] | `OpenApiCustomizer` : ce que dit le chemin, et les correctifs du document |
| [`ProblemDocumentation`][problem] | les schémas `Problem`, `ValidationProblem` et `InvalidValue`, un exemple par type |
| [`DocumentedProblems`][documented] | les `ProblemType` qu'une opération peut renvoyer |
| [`RateLimited`][rate-limited] | marque une opération qui appelle `RateLimiter` ; ne limite rien |
| [`AccessDescription`][access] | la phrase d'une annotation de sécurité, avec ses `{attributs}` |
| [`PublicEndpoints`][public] | les endpoints sans jeton, lus par la sécurité et `PathDocumentation` |
| [`openapi.json`][spec] | le document figé, source des clients générés |

Les réglages `springdoc` de `application.yaml` sont ceux de l'étape 2, avec `api-docs.version: openapi_3_1`. `api-docs.enabled` (`API_DOCS_ENABLED`) et `swagger-ui.enabled` (`SWAGGER_UI_ENABLED`) valent `true`, et `false` sous `prod`. `override-with-generic-response: false` écarte les réponses tirées d'`ApiExceptionHandler`.

Ce qui est propre au dépôt :

- **Accès** : `OperationDocumentation` lit l'`@AccessDescription` de chaque annotation de sécurité de la méthode et remplace ses `{attributs}`. Il l'ajoute en « **Access:** » à la description et documente un 403 (guide 08).
- **Problèmes** : `@DocumentedProblems` donne un exemple par `ProblemType`, sous son statut. Un 400 commence toujours par `validation-error` (guide 05).
- **Fichiers** : une méthode qui reçoit un `@RequestPart` ou un `MultipartFile` documente 413, 415, 503 et `infected-file`, avec le formulaire `@ModelAttribute` reconstruit.
- **Commentaires** : `addComment` et `addCommentWithFiles` partagent l'`operationId` `addComment`.
- **Version d'API** : `PathDocumentation` remplace d'abord le préfixe brut `/api/v{version}` par `/api/v1`. Sinon ses autres règles liraient `{version}` comme l'id d'une ressource (guide 04).
- **Pages** : springdoc ne marque pas `content` et `page` de `PagedModel` comme requis (springdoc-openapi #3360). `PathDocumentation` les marque, comme les quatre champs de `PageMetadata`, et donne au paramètre `size` le maximum de `spring.data.web.pageable.max-page-size` (guide 03).
- **Sans corps** : les 401 et 403 des contrôles d'accès n'ont qu'un en-tête `WWW-Authenticate`. Les autres erreurs sont des problèmes `application/problem+json`.
- **Version du document** : `info.version` vient de `build-info`, et `OpenApiSpecTests` la remplace par `development` avant de comparer.
- **CI** : le job « API contract » lance Spectral (`.spectral.yaml` coupe seulement `operation-description`), puis oasdiff. `.oasdiff.yaml` ramène au niveau info l'ajout d'une valeur à une énumération de réponse, car la description de l'API demande aux clients d'accepter l'inconnu.

Les tests :

- [`OpenApiSpecTests`][spec-tests] compare le document au fichier commité, et vérifie que la documentation se lit sans jeton.
- [`OpenApiDocumentTests`][document-tests] vérifie des `operationId` uniques sans suffixe, un tag et un résumé par opération, un 401 sur les seules opérations non publiques, une `Location` sur chaque 201 et un exemple par `ProblemType`.
- [`OpenApiConformanceTests`][conformance-tests] appelle chaque opération sans jeton : seules les publiques répondent autre chose que 401.
- [`PathDocumentationTest`][path-test] teste les règles de chemin sans démarrer Spring. [`AccessDescriptionTest`][access-test] refuse une règle de sécurité sans phrase (guide 31).

Pour ajouter un endpoint : le `@Tag` du contrôleur, un `@Operation(summary)`, un nom de méthode unique, `@ResponseStatus` pour 201, 202 ou 204, `@DocumentedProblems`, `@RateLimited` s'il appelle `RateLimiter`, et une entrée de `PublicEndpoints` s'il est public. Puis régénérer `docs/openapi.json`.

## Voir aussi

- [03. Contrôleurs MVC](03-controleurs-mvc.md) : `@ResponseStatus`, `PagedModel` et `@ParameterObject`.
- [04. Versioning d'API](04-versioning-api.md) : le préfixe `/api/v{version}` que `PathDocumentation` fixe.
- [05. Validation et erreurs](05-validation-erreurs.md) : `ProblemDetail`, `ProblemType` et `docs/problems.md`.
- [08. Autorisations](08-autorisations.md) : les annotations de sécurité qui portent `@AccessDescription`.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : les tests qui vérifient des conventions.
- [33. Image Docker, build et CI](33-docker-build-ci.md) : le pipeline et ses actions épinglées.
- Documentation officielle : [springdoc-openapi](https://springdoc.org/), [spécification OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0), [oasdiff](https://github.com/oasdiff/oasdiff).

[Sommaire](README.md) | [< 05. Validation et erreurs](05-validation-erreurs.md) | [07. Chaîne de filtres et JWT >](07-securite-jwt.md)

[config]: ../../src/main/java/io/julienmetral/tasks/config/OpenApiConfiguration.java
[operation]: ../../src/main/java/io/julienmetral/tasks/config/OperationDocumentation.java
[path]: ../../src/main/java/io/julienmetral/tasks/config/PathDocumentation.java
[problem]: ../../src/main/java/io/julienmetral/tasks/config/ProblemDocumentation.java
[documented]: ../../src/main/java/io/julienmetral/tasks/shared/openapi/DocumentedProblems.java
[rate-limited]: ../../src/main/java/io/julienmetral/tasks/shared/openapi/RateLimited.java
[access]: ../../src/main/java/io/julienmetral/tasks/shared/security/AccessDescription.java
[public]: ../../src/main/java/io/julienmetral/tasks/identity/security/PublicEndpoints.java
[spec]: ../openapi.json
[spec-tests]: ../../src/test/java/io/julienmetral/tasks/config/OpenApiSpecTests.java
[document-tests]: ../../src/test/java/io/julienmetral/tasks/config/OpenApiDocumentTests.java
[conformance-tests]: ../../src/test/java/io/julienmetral/tasks/config/OpenApiConformanceTests.java
[path-test]: ../../src/test/java/io/julienmetral/tasks/config/PathDocumentationTest.java
[access-test]: ../../src/test/java/io/julienmetral/tasks/shared/security/AccessDescriptionTest.java
