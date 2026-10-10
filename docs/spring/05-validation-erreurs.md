# 05. Validation et erreurs

[Sommaire](README.md) | [< 04. Versioning d'API](04-versioning-api.md) | [06. OpenAPI avec springdoc >](06-openapi-springdoc.md)

## Contexte

- **Problème** : refuser une requête invalide avant qu'elle touche aux données. Répondre à toute erreur dans un format unique, que le client sait lire.
- **Quand l'utiliser** : toute API REST, dès le premier endpoint.
- **Quand s'en passer** : une application à pages HTML (Thymeleaf), qui affiche les erreurs dans le formulaire.
- **Dans ce dépôt** : `ApiExceptionHandler`, 14 types documentés dans `docs/problems.md`, une liste `errors` qui situe chaque valeur invalide.
- **Prérequis** : guide 03 (contrôleurs MVC). Versions : Spring Boot 4.1, Spring Framework 7, Hibernate Validator 9.1, Jackson 3.

Les façons de répondre à une erreur :

| Solution | Principe | Quand la choisir |
|---|---|---|
| `ProblemDetail` et un advice global | le format de la RFC 9457, rempli par du code | cas général, API utilisée par d'autres équipes |
| `spring.mvc.problemdetails.enabled` | Boot traduit les exceptions de Spring MVC, sans code | point de départ : les exceptions métier restent des 500 |
| Format maison `{code, message}` | un objet propre à l'API | à éviter : chaque client doit l'apprendre |
| Page d'erreur de Boot (`/error`) | `timestamp`, `status`, `error`, `path` | un prototype : le client ne sait pas quoi corriger |

## Concepts clés

### Vocabulaire

- **Bean Validation** : le standard Jakarta de validation par annotations (`@NotBlank`, `@Size`), implémenté par Hibernate Validator. Une violation de contrainte produit un message.
- **Problème** : le corps d'erreur de la RFC 9457, servi en `application/problem+json`.
- **Type de problème** : une URI qui nomme une catégorie d'erreur, que le client compare pour réagir.
- **JSON Pointer** : le chemin d'une valeur dans un document JSON, comme `#/items/0/name` (RFC 6901).
- **Advice** : une classe `@RestControllerAdvice`, dont les méthodes `@ExceptionHandler` valent pour tous les contrôleurs.

### Le format d'un problème

Un problème est un objet JSON aux membres fixés par la RFC :

| Membre | Rôle | Exemple |
|---|---|---|
| `type` | URI de la catégorie, `about:blank` si absent | `.../problems.md#email-taken` |
| `title` | résumé fixe, le même pour chaque occurrence | `Email already in use` |
| `status` | le code HTTP, répété | `409` |
| `detail` | explication de cette occurrence | `User already exists with email: ...` |
| `instance` | l'occurrence, ici le chemin de la requête | `/api/v1/users` |
| extension | tout autre membre, propre au type | `errors` |

Une erreur de validation, avec `errors` :

```
{
  "type": "https://.../docs/problems.md#validation-error",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more values of the request are invalid: see errors.",
  "instance": "/api/v1/users",
  "errors": [
    { "detail": "must be a well-formed email address", "pointer": "#/email" },
    { "detail": "size must be between 8 and 128", "pointer": "#/password" }
  ]
}
```

- `pointer` situe une valeur du corps JSON. `parameter` nomme un paramètre de requête, de chemin ou de formulaire, ou une part multipart.
- Le client affiche `detail` sans le parser : sa formulation peut changer.

### Typé ou `about:blank`

Toute erreur ne mérite pas un type :

```
                    une erreur à renvoyer
                              |
                              v
          le client peut-il réagir autrement qu'avec
          le seul code HTTP ? ------------ non --> about:blank : pas de type,
                              |                    title = phrase du statut
                              | oui                (404 Not Found, 503...)
                              v
          un type à soi : URI stable, titre fixe,
          une section dans la documentation des clients
          (409 email-taken, 422 invalid-mention...)
```

- Un 404 ou un 503 se suffisent. Deux 409, « email déjà pris » et « modifié en même temps », demandent deux types.
- L'URI d'un type pointe vers sa documentation. Type et titre forment un contrat : les renommer casse les clients qui les comparent.

### Le trajet d'une erreur

Toute étape peut lever une exception, qui finit dans l'advice :

```
  requête
     |
     v
  lecture du corps (Jackson) --- HttpMessageNotReadableException -->+
     |                                                              |
     v                                                              |
  validation (@Valid) ---------- MethodArgumentNotValidException -->+
     |                                                              |
     v                                                              |
  sécurité de méthode ---------- AccessDeniedException ------------>+
     |                                                              |
     v                                                              |
  contrôleur, puis service ----- exception métier ----------------->+
     |                                                              |
     v                                                              v
  réponse 2xx                                 advice : le @ExceptionHandler
                                              au type le plus proche
                                                            |
                                                            v
                                              ProblemDetail, servi en
                                              application/problem+json
```

- `ResponseEntityExceptionHandler`, une classe de Spring, traite déjà les vingt exceptions de Spring MVC (405, 415, corps illisible, validation...). On l'étend, et on surcharge ses méthodes pour changer une réponse.
- Les messages de Bean Validation suivent `Accept-Language` (« ne doit pas être vide »).
- Jackson appelle le constructeur compact d'un record avant la validation, qui voit donc la valeur nettoyée.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  invoice/
    CreateInvoiceDto.java             le DTO et ses contraintes (étape 1)
    InvoiceController.java            @Valid sur le corps (étape 2)
    InvoiceNotFoundException.java     une exception métier (étape 4)
  shared/
    ApiExceptionHandler.java          le gestionnaire global (étapes 3, 4, 7)
    ProblemType.java                  les types de problèmes (étape 6)
    InvalidValue.java                 une valeur invalide (étape 7)
src/main/resources/
  application.yaml                    enums stricts (étape 5)
  messages.properties                 détails de Spring reformulés (étape 8)
docs/problems.md                      la documentation des types (étape 6)
src/test/java/.../
  InvoiceValidationTests.java         la vérification (étape 9)
```

### Un gestionnaire global

**Étape 1. Poser les contraintes sur le record.** Le constructeur compact nettoie avant la validation.

```java
public record CreateInvoiceDto(
        @NotBlank @Size(max = 30) String reference,
        @NotNull @Positive BigDecimal amount,
        @Email @Size(max = 320) String contactEmail
) {

    public CreateInvoiceDto {
        // "  " devient null, que @NotBlank refuse ; " A-1 " devient "A-1"
        reference = StringUtils.hasText(reference) ? reference.trim() : null;
    }
}
```

**Étape 2. Demander la validation dans le contrôleur.** Sans `@Valid`, les contraintes sont ignorées.

```java
@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
class InvoiceController {

    private final InvoiceService invoiceService;     // service et DTO de réponse : voir le guide 03

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceResponseDto create(@Valid @RequestBody CreateInvoiceDto dto) {
        return new InvoiceResponseDto(invoiceService.create(dto));
    }
}
```

Pour un formulaire ou un multipart, on écrit `@Valid @ModelAttribute` (guide 03).

**Étape 3. Créer le gestionnaire global.** Son dernier recours ne dit rien de l'exception au client.

```java
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;            // laissée à Spring Security : 403 ou 401, pas 500 (concepts avancés)
        }
        log.error("Unexpected error while handling a request", ex);

        // jamais ex.getMessage() : il peut porter du SQL, un hôte interne, un nom de classe
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
```

**Étape 4. Traduire chaque exception métier.** Elle reste une `RuntimeException` sans HTTP, avec un message écrit pour le client.

```java
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID id) {
        super("Invoice not found with id: " + id);
    }
}
```

```java
// dans ApiExceptionHandler
@ExceptionHandler(InvoiceNotFoundException.class)
public ProblemDetail handleNotFound(InvoiceNotFoundException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
}

// filet de sécurité d'une course entre une vérification et l'insertion (guide 15)
@ExceptionHandler(DataIntegrityViolationException.class)
public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    // son message porte le SQL et le nom de la contrainte : on ne le renvoie pas
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
}
```

**Étape 5. Refuser les nombres à la place des enums.** Par défaut, Jackson lit `"kind": 1` comme la deuxième constante.

```yaml
spring:
  jackson:
    datatype:
      enum:
        fail-on-numbers-for-enums: true     # un enum se donne par son nom, jamais par son rang
```

### Des erreurs que le client peut traiter

**Étape 6. Déclarer les types de problèmes.** Un enum garde ensemble statut, URI et titre. Chaque type a une section dans la documentation des clients.

```java
public enum ProblemType {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "validation-error", "Invalid request"),
    INVOICE_ALREADY_PAID(HttpStatus.CONFLICT, "invoice-already-paid", "Invoice already paid");

    private static final String DOCUMENT = "https://example.com/docs/problems.md#";

    private final HttpStatus status;
    private final URI type;
    private final String title;

    ProblemType(HttpStatus status, String slug, String title) {
        this.status = status;
        this.type = URI.create(DOCUMENT + slug);
        this.title = title;
    }

    public ProblemDetail problem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
```

```java
// dans ApiExceptionHandler ; InvoiceAlreadyPaidException est une RuntimeException, comme à l'étape 4
@ExceptionHandler(InvoiceAlreadyPaidException.class)
public ProblemDetail handleAlreadyPaid(InvoiceAlreadyPaidException ex) {
    return ProblemType.INVOICE_ALREADY_PAID.problem(ex.getMessage());
}
```

**Étape 7. Situer chaque valeur invalide.** Spring répond « Invalid request content. » sans dire quoi corriger. On surcharge donc sa méthode.

```java
// Jackson 3 garde ses annotations dans com.fasterxml.jackson.annotation
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvalidValue(String detail, String pointer, String parameter) {
}
```

```java
// dans ApiExceptionHandler
@Override
protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    // un corps JSON se situe par un pointeur ; un formulaire ou un multipart, par le nom du champ
    boolean jsonBody = ex.getParameter().hasParameterAnnotation(RequestBody.class);

    List<InvalidValue> errors = ex.getBindingResult().getFieldErrors().stream()
            .sorted(Comparator.comparing(FieldError::getField))     // l'ordre des violations varie
            .map(error -> jsonBody
                    ? new InvalidValue(error.getDefaultMessage(), pointer(error.getField()), null)
                    : new InvalidValue(error.getDefaultMessage(), null, error.getField()))
            .toList();

    ProblemDetail problem = ProblemType.VALIDATION_ERROR.problem("Some values are invalid: see errors.");
    problem.setProperty("errors", errors);            // devient un membre de premier niveau du JSON
    return handleExceptionInternal(ex, problem, headers, status, request);
}

private static String pointer(String field) {         // items[0].name --> #/items/0/name
    return "#/" + field.replaceAll("\\[(\\d+)]", ".$1").replace('.', '/');
}
```

**Étape 8. Reformuler les détails de Spring.** Certains parlent d'implémentation (« No static resource... »). Une clé `problemDetail.<classe complète>` dans `messages.properties` les remplace.

```properties
problemDetail.org.springframework.web.servlet.resource.NoResourceFoundException=No endpoint matches this path
problemDetail.org.springframework.web.multipart.MaxUploadSizeExceededException=The request exceeds the maximum upload size
```

**Étape 9. Vérifier.** Un corps invalide : forme du problème, et service jamais appelé.

```java
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceValidationTests {

    @Autowired MockMvc mockMvc;
    @MockitoBean InvoiceService invoiceService;

    @Test
    void blankReferenceIsAValidationErrorPointingToIt() throws Exception {
        mockMvc.perform(post("/invoices")
                        .with(jwt())                                   // un appelant authentifié (guide 07)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")    // les messages suivent la langue
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\": \"  \", \"amount\": 10}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(endsWith("#validation-error")))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/reference"))
                .andExpect(jsonPath("$.errors[0].detail").value("must not be blank"));

        verifyNoInteractions(invoiceService);
    }
}
```

### Référence rapide

Contraintes courantes de `jakarta.validation.constraints` :

| Contrainte | Refuse | Remarque |
|---|---|---|
| `@NotNull` | `null` | tout type |
| `@NotBlank` | `null`, `""`, `"  "` | chaînes seulement ; préférer à `@NotNull` pour un texte |
| `@NotEmpty` | `null`, vide | chaînes, collections, maps |
| `@Size(min, max)` | une longueur hors bornes | accepte `null` |
| `@Email`, `@Pattern(regexp)` | un texte mal formé | acceptent `null` |
| `@Min`, `@Max`, `@Positive` | un nombre hors bornes | acceptent `null` |
| `@Past`, `@Future` | une date du mauvais côté | acceptent `null` |
| `@Valid` sur un champ | rien par lui-même | fait valider l'objet ou la liste imbriqués |

Méthodes de `ResponseEntityExceptionHandler` les plus surchargées :

| Méthode | Cause | Statut |
|---|---|---|
| `handleMethodArgumentNotValid` | `@Valid` sur un corps ou un `@ModelAttribute` | 400 |
| `handleHandlerMethodValidationException` | une contrainte sur un paramètre | 400 |
| `handleHttpMessageNotReadable` | JSON illisible, ou valeur du mauvais type | 400 |
| `handleTypeMismatch` | paramètre de requête ou de chemin mal formé | 400 |
| `handleMissingServletRequestParameter`, `...Part` | paramètre ou part manquants | 400 |
| `handleNoResourceFoundException` | aucun endpoint pour ce chemin | 404 |
| `handleHttpRequestMethodNotSupported` | méthode HTTP refusée | 405 |
| `handleMaxUploadSizeExceededException` | upload au-delà de la limite | 413 |
| `handleHttpMediaTypeNotSupported` | `Content-Type` refusé | 415 |

Pour les exceptions de Spring, `messages.properties` lit `problemDetail.<classe complète>` pour le `detail`, `problemDetail.title.<classe complète>` pour le `title` et `problemDetail.type.<classe complète>` pour le `type`.

Limites de cette version : les erreurs d'un filtre (la sécurité) n'atteignent pas l'advice, et les messages métier ignorent la langue de la requête.

## Concepts avancés

### Sous le capot : le choix du handler

Spring MVC essaie trois résolveurs, dans cet ordre :

1. `ExceptionHandlerExceptionResolver` : les `@ExceptionHandler` du contrôleur, puis des advices.
2. `ResponseStatusExceptionResolver` : les exceptions annotées `@ResponseStatus`.
3. `DefaultHandlerExceptionResolver` : les exceptions de Spring MVC sans advice.

- Dans un advice, Spring retient le `@ExceptionHandler` au type le plus proche de l'exception. `Exception.class` passe en dernier.
- Spring n'examine la cause d'une exception que si rien ne correspond à l'exception elle-même. Avec un handler `Exception`, quelque chose correspond toujours.
- Deux méthodes pour la même exception empêchent le contexte de démarrer.

Un proxy Spring autour du contrôleur vérifie `@PreAuthorize`. Son refus sort donc du contrôleur et passe par l'advice :

```
  requête
     |
     v
  ExceptionTranslationFilter            filtre de Spring Security
     |
     v
  DispatcherServlet
     |
     v
  proxy Spring de @PreAuthorize -- refus --> AccessDeniedException
                                                    |
                                                    v
                                   advice : le handler Exception la relance
                                                    |
                                                    v
                                   aucun autre résolveur ne la traite :
                                   elle sort du DispatcherServlet
                                                    |
                                                    v
                                   ExceptionTranslationFilter --> 403
                                   (401 si l'appelant est anonyme)
```

- Relancer l'exception laisse Spring passer au résolveur suivant. Y répondre transformerait le refus en 500.

### Sous le capot : d'où viennent les membres

- **`title`** : sans titre posé, `ProblemDetail.getTitle()` renvoie la phrase du statut (« Not Found »).
- **`detail`** des exceptions de Spring : cherché d'abord dans le `MessageSource`, sous `problemDetail.<classe>`. Boot charge `messages.properties` sans réglage.
- **`instance`** et **`Content-Type`** : le chemin de la requête et `application/problem+json` sont posés à l'écriture.

### Une contrainte sur un paramètre

Depuis Spring Framework 6.1, une contrainte posée sur un paramètre déclenche la validation de méthode :

```java
@GetMapping
PagedModel<InvoiceResponseDto> list(@RequestParam @Max(100) int size) { ... }
```

- L'erreur est une `HandlerMethodValidationException`, pas une `MethodArgumentNotValidException`. Les `@Valid` de la même méthode passent aussi par là.
- Sa réponse par défaut dit seulement « Validation failure ». Sans la surcharge de `handleHandlerMethodValidationException`, la liste `errors` disparaît.

### Une alternative : `ErrorResponseException`

Une exception qui étend `ErrorResponseException` porte son propre `ProblemDetail`, sans handler à écrire. Mais le code métier dépend alors de HTTP, ce qui n'a pas de sens dans un listener ou un job. Ce dépôt garde des `RuntimeException` simples, et un seul endroit décide du statut.

### Surveiller

- Le dernier recours journalise en ERROR, avec la trace. Les handlers 4xx ne journalisent rien.
- Une alerte sur le tag `outcome=SERVER_ERROR` de `http.server.requests` signale les 500.

## Pièges à éviter

### Avec Bean Validation

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Oublier `@Valid` sur un corps, un `@ModelAttribute` ou un champ objet | les contraintes sont ignorées | Mettre `@Valid` à chacun de ces endroits |
| Poser `@Size` ou `@Email` seuls | `null` passe : ces contraintes l'acceptent | Ajouter `@NotNull` ou `@NotBlank` |
| Poser une contrainte sur un paramètre | une autre exception est levée : la liste `errors` disparaît | Surcharger `handleHandlerMethodValidationException` |
| Choisir `@NotNull` pour un texte obligatoire | `"  "` passe, puis s'enregistre vide | Choisir `@NotBlank`, et nettoyer dans le constructeur compact |

### Avec le gestionnaire d'erreurs

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Déclarer un `@ExceptionHandler` déjà traité par la classe mère | deux méthodes pour une exception : le démarrage échoue | Surcharger sa méthode `handle...` |
| Attraper `Exception` sans relancer `AccessDeniedException` | chaque refus de `@PreAuthorize` devient un 500 | Relancer les exceptions de sécurité |
| Renvoyer `ex.getMessage()` au client | il lit du SQL, un nom de contrainte, un hôte interne | Journaliser l'exception, renvoyer un détail fixe |
| Créer une exception métier sans son handler | elle tombe dans le dernier recours : 500 | Écrire le handler en même temps que l'exception |
| Lever l'exception métier enveloppée dans une autre | l'enveloppe correspond au handler `Exception` : 500 | Lever l'exception métier telle quelle |
| Annoter une exception `@ResponseStatus` | le handler `Exception` passe avant le résolveur de l'annotation : 500 | Traduire l'exception dans l'advice |

### Pour les clients

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Renommer un type ou changer son titre | les clients qui les comparent ne reconnaissent plus l'erreur | Traiter le renommage comme un changement cassant |
| Ajouter un type sans le documenter | le client reçoit une URI qui ne mène nulle part | Écrire sa section avant de livrer |

## En résumé

**À retenir :** des contraintes sur des records et `@Valid` refusent l'entrée invalide. Un seul advice, qui étend `ResponseEntityExceptionHandler`, traduit chaque exception en `ProblemDetail`. Le type est stable quand le client peut réagir, `about:blank` sinon, et le message d'une exception imprévue n'est jamais renvoyé.

- [ ] Contraintes sur les records d'entrée, `@Valid` sur chaque `@RequestBody` et `@ModelAttribute`
- [ ] Un `@RestControllerAdvice` qui étend `ResponseEntityExceptionHandler`
- [ ] Les méthodes de la classe mère surchargées, jamais redéclarées en `@ExceptionHandler`
- [ ] Un dernier recours qui relance les exceptions de sécurité et cache le message
- [ ] Une liste `errors` avec `pointer` ou `parameter` pour chaque valeur invalide
- [ ] Des types stables et documentés pour les règles métier, `about:blank` pour le reste
- [ ] `fail-on-numbers-for-enums` activé, détails de Spring reformulés dans `messages.properties`
- [ ] Un handler et un test pour chaque nouvelle exception métier

## Dans ce dépôt

Le sort d'une exception dans [`ApiExceptionHandler`][handler] :

```
  exception levée pendant la requête
            |
            v
  ApiExceptionHandler
            |
            +-- valeur invalide : corps, paramètre, part, tri
            |      --> 400 validation-error, avec la liste errors
            |
            +-- règle métier que le client peut traiter
            |      --> un ProblemType : 400, 409 ou 422
            |
            +-- condition HTTP générique (introuvable, trop gros...)
            |      --> about:blank : 400, 401, 404, 405, 409, 413, 415,
            |          429, 503
            |
            +-- exception de sécurité
            |      --> relancée : Spring Security répond 401 ou 403
            |
            +-- tout le reste
                   --> 500, détail fixe, trace journalisée en ERROR
```

| Fichier | Rôle |
|---|---|
| [`ApiExceptionHandler`][handler] | les handlers métier, et les surcharges de `ResponseEntityExceptionHandler` |
| [`ProblemType`][problem-type] | les 14 types : statut, titre, URI vers leur section de `docs/problems.md` |
| [`InvalidValue`][invalid-value] | un élément de `errors`, et les messages dans les termes du client |
| [`messages.properties`][messages] | les détails reformulés des exceptions de Spring MVC |
| [`docs/problems.md`][problems] | la documentation des erreurs pour les clients |
| [`CreateTaskDto`][create-task] | un record validé, nettoyé dans son constructeur compact |
| [`TaskCommentController`][comment-controller] | le même DTO en JSON (`@RequestBody`) et en multipart (`@ModelAttribute`) |

Les traductions à expliquer :

| Exception | Réponse | Pourquoi |
|---|---|---|
| `OptimisticLockingFailureException` | 409 `version-conflict` | deux requêtes ont modifié la même ligne (guide 15) |
| `DataIntegrityViolationException` | 409 sans type, détail générique | course entre une vérification et l'insertion (guide 15) |
| `TooManyCommentAttachmentsException` | 400 `validation-error` sur `files` | erreur d'entrée, même détectée par le service |
| `PropertyReferenceException` | 400 `validation-error` sur `sort` | tri sur une propriété inconnue |
| `RateLimitExceededException` | 429 et `Retry-After` | un `ResponseEntity<ProblemDetail>` pose l'en-tête |
| `AntivirusUnavailableException` | 503, détail fixe | la cause nomme l'hôte de ClamAV |

Les réglages :

| Réglage | Valeur | Rôle |
|---|---|---|
| `fail-on-numbers-for-enums` (`spring.jackson.datatype.enum`) | `true` | `"kind": 1` créait un webhook Slack |
| `max-request-size` (`spring.servlet.multipart`) | `26MB` | au-delà, 413 (détail de `messages.properties`) |

[`messages.properties`][messages] reformule aussi `InvalidApiVersionException` : « This API version is not supported: use /api/v1 » (guide 04).

Ce qui est propre au dépôt :

- Le dépôt surcharge aussi `handleHttpMessageNotReadable`, `handleTypeMismatch`, `handleMissingServletRequestParameter` et `handleMissingServletRequestPart`. Une valeur du mauvais type donne un pointeur et un message simple (« must be a UUID », « must be one of LOW, MEDIUM, HIGH, URGENT »). Un JSON illisible garde « Failed to read request », sans `errors`.
- Pour un enum du corps JSON, la liste donne les noms du JSON (`task.assigned`, lus sur `@JsonValue`), pas ceux des constantes.
- `errors` est trié par pointeur ou paramètre, puis par détail. Une erreur sur l'objet entier, non sur un champ, a le pointeur `#`.
- Le `detail` d'un problème typé reste en anglais. Seuls les messages de Bean Validation suivent `Accept-Language` : les tests vérifient `pointer` et `parameter`, ou fixent la langue.
- Les 401 et 403 des filtres de sécurité viennent de Spring Security, sans corps (guide 07).

Les tests :

- [`ApiExceptionHandlerTest`][handler-test] appelle chaque handler : statut, type, titre, aucun détail interne (SQL, hôte, classe).
- [`ApiExceptionHandlerWebMvcTests`][handler-web-test] passe par la vraie chaîne : 404, 405, 415, et un 500 sans message.
- [`ProblemTypeTest`][problem-type-test] vérifie que chaque type pointe vers une section de `docs/problems.md` de même statut et titre, et inversement.
- [`InvalidValueTest`][invalid-value-test] couvre les pointeurs (index, échappement de `~` et `/`).
- [`Problems`][problems-matchers] fournit les matchers `typedProblem`, `untypedProblem`, `invalidBodyValue`, `invalidParameter` et `withoutJavaTypeNames`. Les types y sont écrits en clair : un renommage casse un test.
- [`UserControllerWebMvcTests`][user-web-test] vérifie l'ordre de `errors` et des messages en français (`Accept-Language: fr`).

Pour ajouter une exception métier : l'exception dans le paquet `exceptions` de sa fonctionnalité, son handler dans `ApiExceptionHandler`, puis un test du handler et un test web avec les matchers de `Problems`. Si le client peut réagir, ajouter aussi une constante de `ProblemType`, une section de `docs/problems.md` qui commence par `**Status NNN, title "...".**`, et `@DocumentedProblems` sur l'endpoint (guide 06).

## Voir aussi

- [03. Contrôleurs MVC](03-controleurs-mvc.md) : les DTO en records et le multipart avec `@ModelAttribute`.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : `@DocumentedProblems` et le schéma `Problem` écrit à la main.
- [08. Autorisations](08-autorisations.md) : les annotations de sécurité dont le refus traverse l'advice.
- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : le verrou optimiste derrière `version-conflict`.
- [28. Uploads et drivers de stockage](28-uploads-stockage.md) : les 413, 415 et 422 des fichiers.
- Documentation officielle : [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457), [réponses d'erreur dans Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html), [validation dans Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html).

[Sommaire](README.md) | [< 04. Versioning d'API](04-versioning-api.md) | [06. OpenAPI avec springdoc >](06-openapi-springdoc.md)

[handler]: ../../src/main/java/io/julienmetral/tasks/shared/exceptions/ApiExceptionHandler.java
[problem-type]: ../../src/main/java/io/julienmetral/tasks/shared/exceptions/ProblemType.java
[invalid-value]: ../../src/main/java/io/julienmetral/tasks/shared/exceptions/InvalidValue.java
[messages]: ../../src/main/resources/messages.properties
[problems]: ../problems.md
[create-task]: ../../src/main/java/io/julienmetral/tasks/task/dtos/CreateTaskDto.java
[comment-controller]: ../../src/main/java/io/julienmetral/tasks/task/controllers/TaskCommentController.java
[handler-test]: ../../src/test/java/io/julienmetral/tasks/shared/exceptions/ApiExceptionHandlerTest.java
[handler-web-test]: ../../src/test/java/io/julienmetral/tasks/shared/exceptions/ApiExceptionHandlerWebMvcTests.java
[problem-type-test]: ../../src/test/java/io/julienmetral/tasks/shared/exceptions/ProblemTypeTest.java
[invalid-value-test]: ../../src/test/java/io/julienmetral/tasks/shared/exceptions/InvalidValueTest.java
[problems-matchers]: ../../src/test/java/io/julienmetral/tasks/support/Problems.java
[user-web-test]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/UserControllerWebMvcTests.java
