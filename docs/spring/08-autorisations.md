# 08. Autorisations

[Sommaire](README.md) | [< 07. Chaîne de filtres et JWT](07-securite-jwt.md) | [09. Mots de passe et jetons opaques >](09-mots-de-passe-jetons.md)

## Contexte

- **Problème** : une fois l'appelant identifié (guide 07), décider ce qu'il peut faire : selon son rôle, selon qu'une ressource lui appartient, et seulement tant que son compte reste actif.
- **Quand l'utiliser** : toute API où les utilisateurs n'ont pas tous les mêmes droits, ou où l'on agit sur ses propres ressources.
- **Quand s'en passer** : une API où tout appelant authentifié peut tout faire ; `anyRequest().authenticated()` suffit.
- **Dans ce dépôt** : des règles par URL dans `SecurityConfiguration` (les endpoints des tâches, des notifications et des exports sont réservés aux comptes actifs), puis des méta-annotations maison autour de `@PreAuthorize` sur les méthodes des contrôleurs, appuyées par des beans « propriétaire ».
- **Prérequis** : guide 07 (chaîne de filtres, JWT, authorities). Versions : Spring Boot 4.1, Spring Security 7.1.

Où placer une règle d'accès :

| Où | Ce qu'on peut tester | Quand la choisir |
|---|---|---|
| Règle par URL (`authorizeHttpRequests`) | chemin, méthode HTTP, appelant | une règle commune à tout un groupe d'endpoints |
| `@PreAuthorize` sur la méthode | les paramètres de l'appel, des beans | rôles par opération, règles « propriétaire » |
| Contrôle dans le service | tout, données chargées comprises | règle métier qui dépend de l'état (une tâche archivée) |

## Concepts clés

### Vocabulaire

- **Autorisation** : décider si un appelant identifié peut faire une action. L'authentification répond à « qui », l'autorisation à « a-t-il le droit ».
- **Règle par URL** : une règle de la chaîne de filtres, testée avant le contrôleur.
- **Sécurité des méthodes** (method security) : des règles écrites sur les méthodes (`@PreAuthorize`), contrôlées par un proxy Spring autour du bean.
- **Règle « propriétaire »** : « l'appelant peut agir sur cette ressource parce qu'elle est à lui » (son compte, la tâche qui lui est assignée).
- **SpEL** : Spring Expression Language, le langage de `@PreAuthorize("...")`.
- **Méta-annotation** : une annotation du projet qui porte `@PreAuthorize`, pour qu'une règle ait un nom et soit écrite une seule fois.

### Deux niveaux de contrôle

Une requête authentifiée passe par les règles d'URL, puis par la règle de la méthode :

```
  requête authentifiée (guide 07)
        |
        v
  AuthorizationFilter : règles par URL --- refus --> 401 ou 403
  (chemin, méthode, appelant)
        |
        v
  Spring MVC : arguments lus et validés --- invalide --> 400
        |
        v
  proxy Spring : @PreAuthorize ------------ refus --> 403
  (rôles, #id, beans propriétaires)
        |
        v
  méthode du contrôleur, puis service
```

- La première règle d'URL qui correspond décide ; `anyRequest()` vient en dernier (guide 07).
- Une règle d'URL ne voit que la requête. Une règle de méthode lit les arguments par leur nom (`#id`, `#dto`) et peut appeler des beans.
- Le corps est lu et validé avant `@PreAuthorize` : un corps invalide répond 400, même à un appelant qui serait refusé.

### 401 ou 403

- 401 : pas de jeton, ou un jeton invalide. Aussi pour un appelant anonyme refusé par une règle : le `ExceptionTranslationFilter` demande alors de s'authentifier.
- 403 : un appelant authentifié refusé par une règle d'URL ou par `@PreAuthorize`.
- Un non-propriétaire reçoit aussi 403 sur une ressource qui n'existe pas : répondre 404 lui apprendrait quels identifiants existent. Seul un appelant dont le rôle passe outre la propriété atteint le service et son 404.

### Un jeton valide ne suffit pas

Un jeton survit à la désactivation du compte, sauf si une règle relit l'état du compte :

```
 10:00  connexion --> jeton valable jusqu'à 10:15
 10:05  un admin désactive le compte
 10:06  requête avec le même jeton
          |
          +-- règle "authentifié" seule --> acceptée jusqu'à 10:15
          |
          +-- règle "compte actif" -------> statut relu --> 403
```

- Un JWT est sans état : rien ne le révoque avant son expiration. Une règle qui relit le statut du compte à chaque requête ferme ce trou ; un cache court (30 s ici), évincé après chaque changement, la rend peu chère (guide 17).
- « Actif » dans ce dépôt : activé, non supprimé, e-mail vérifié (`UserStatus.ACTIVE`).

### Les règles « propriétaire »

- Un contrôle de rôle compare des chaînes du jeton ; un contrôle de propriété demande une donnée : une méthode de bean qui répond `true` ou `false` avec une requête (`existsByIdAndAssignedToId`).
- La SpEL appelle le bean par son nom : `@taskAuthorization.currentUserIsAssignedTo(#id, authentication)`, où `authentication` est l'`Authentication` courante.
- Avec le rôle admin écrit en premier (`hasAnyRole('ADMIN') or ...`), le bean n'est pas appelé pour un admin.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  security/
    SecurityConfiguration.java        méthodes, gabarits, règle d'URL (1, 5, 7)
    CallerIds.java                    l'id de l'appelant (étape 3)
    ActiveAccountAuthorizationManager.java   compte actif (étape 7)
    Role.java                         les rôles (étape 6)
    AllowedRolesOrOwnerOnly.java      la méta-annotation (étape 6)
  invoice/
    InvoiceAuthorization.java         le bean propriétaire (étape 3)
    InvoiceController.java            les règles posées (étapes 2, 4, 6)
src/test/java/.../
  InvoiceControllerWebMvcTests.java   la vérification (étape 8)
```

L'exemple : des factures qui appartiennent à un utilisateur, que les admins gèrent toutes. L'id de l'appelant est la claim `uid` du JWT (guide 07).

### Rôles et propriétaires avec `@PreAuthorize`

**Étape 1. Activer la sécurité des méthodes.** Sans elle, `@PreAuthorize` est ignoré, sans aucune erreur.

```java
@Configuration
@EnableMethodSecurity                 // @PreAuthorize et @PostAuthorize actifs
public class SecurityConfiguration {
    // ... la chaîne de filtres du guide 07
}
```

**Étape 2. Réserver une opération à un rôle.** C'est la règle la plus simple : un contrôle sur les authorities du jeton.

```java
@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;     // le service métier

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")               // ROLE_ADMIN dans les authorities
    public void cancelInvoice(@PathVariable UUID id) {
        invoiceService.cancel(id);
    }
}
```

**Étape 3. Écrire le bean propriétaire.** Une requête répond à « cette facture est-elle à l'appelant ? » ; le bean a un nom que la SpEL peut appeler.

```java
public final class CallerIds {

    private CallerIds() {
    }

    // l'id du compte, lu dans la claim uid du JWT (guide 07)
    public static Optional<UUID> of(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaimAsString("uid") instanceof String uid) {
            return Optional.of(UUID.fromString(uid));
        }
        return Optional.empty();
    }
}
```

```java
@Component("invoiceAuthorization")
@RequiredArgsConstructor
public class InvoiceAuthorization {

    // existsByIdAndOwnerId : requête dérivée (guide 11)
    private final InvoiceRepository invoiceRepository;

    public boolean currentUserOwns(UUID invoiceId, Authentication authentication) {
        return CallerIds.of(authentication)
                .map(userId -> invoiceRepository.existsByIdAndOwnerId(invoiceId, userId))
                .orElse(false);
    }
}
```

**Étape 4. Combiner rôle et propriétaire.** L'admin passe par son rôle ; tout autre doit posséder la facture. `#id` est le paramètre nommé `id`.

```java
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@PreAuthorize("hasRole('ADMIN') or @invoiceAuthorization.currentUserOwns(#id, authentication)")
public void deleteInvoice(@PathVariable UUID id) {
    invoiceService.delete(id);
}
```

### Des règles nommées, et le compte actif

**Étape 5. Publier les gabarits d'expressions.** Ce bean laisse une méta-annotation injecter ses attributs dans la SpEL avec des placeholders `{nom}`.

```java
// dans SecurityConfiguration
@Bean
static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
    return new AnnotationTemplateExpressionDefaults();
}
```

**Étape 6. Écrire une méta-annotation par règle.** La règle reçoit un nom, s'écrit une fois, et les rôles deviennent un attribut typé. L'enum se rend elle-même entre guillemets dans la SpEL.

```java
public enum Role implements ExpressionTemplateValueProvider {
    USER,
    ADMIN;

    @Override
    public String getExpressionTemplateValue() {
        return "'" + name() + "'";                  // ADMIN devient 'ADMIN' dans la SpEL
    }
}
```

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasAnyRole({value}) or @invoiceAuthorization.currentUserOwns(#id, authentication)")
public @interface AllowedRolesOrOwnerOnly {

    Role[] value();
}
```

```java
@DeleteMapping("/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@AllowedRolesOrOwnerOnly(Role.ADMIN)    // hasAnyRole('ADMIN') or @invoiceAuthorization...
public void deleteInvoice(@PathVariable UUID id) {
    invoiceService.delete(id);
}
```

Un attribut tableau est joint par `, ` : `@AllowedRolesOrOwnerOnly({Role.ADMIN, Role.USER})` donne `hasAnyRole('ADMIN', 'USER')`.

**Étape 7. Refuser les comptes inactifs par une règle d'URL.** Un `AuthorizationManager` relit le compte à chaque requête vers un groupe d'endpoints, quelles que soient les annotations des méthodes.

```java
@Component
@RequiredArgsConstructor
public class ActiveAccountAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    // existsByIdAndActiveTrue : requête dérivée (guide 11)
    private final AccountRepository accountRepository;

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        boolean active = CallerIds.of(authentication.get())
                .map(accountRepository::existsByIdAndActiveTrue)
                .orElse(false);
        return new AuthorizationDecision(active);
    }
}
```

```java
// dans la chaîne de filtres du guide 07, avant anyRequest()
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/invoices/**").access(activeAccountAuthorizationManager)
        .anyRequest().authenticated());
```

Cette règle lit la base à chaque requête ; le guide 17 y ajoute le cache.

**Étape 8. Vérifier.** Un test de la couche web, avec la vraie sécurité et des beans simulés, prouve que le refus arrive avant le service et qu'un admin saute la requête de propriété. Le mock garde le nom du bean, car la SpEL l'appelle par ce nom (guide 29).

```java
@WebLayerTest                       // guide 29 : contrôleurs, vraie sécurité, le reste en mocks
class InvoiceControllerWebMvcTests {

    @Autowired MockMvc mockMvc;
    @Autowired InvoiceService invoiceService;
    @Autowired InvoiceAuthorization invoiceAuthorization;   // mock nommé "invoiceAuthorization"
    @Autowired AccountRepository accountRepository;

    private final UUID invoiceId = UUID.randomUUID();

    @BeforeEach
    void everyAccountIsActive() {
        when(accountRepository.existsByIdAndActiveTrue(any())).thenReturn(true);
    }

    @Test
    void nonOwnerIsForbiddenBeforeTheServiceRuns() throws Exception {
        when(invoiceAuthorization.currentUserOwns(eq(invoiceId), any())).thenReturn(false);

        mockMvc.perform(delete("/invoices/{id}", invoiceId).with(caller("ROLE_USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(invoiceService);
    }

    @Test
    void adminSkipsTheOwnershipCheck() throws Exception {
        mockMvc.perform(delete("/invoices/{id}", invoiceId).with(caller("ROLE_ADMIN")))
                .andExpect(status().isNoContent());

        verifyNoInteractions(invoiceAuthorization);
    }

    private static JwtRequestPostProcessor caller(String role) {
        return jwt().jwt(token -> token.claim("uid", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
```

### Référence rapide

Les expressions les plus utiles dans `@PreAuthorize` :

| Expression | Vraie quand... |
|---|---|
| `hasRole('ADMIN')` | l'appelant a l'authority `ROLE_ADMIN` |
| `hasAnyRole('ADMIN', 'USER')` | l'appelant a l'un de ces rôles |
| `hasAuthority('ROLE_ADMIN')` | l'authority exacte, sans préfixe ajouté |
| `isAuthenticated()` | l'appelant n'est pas anonyme |
| `#id`, `#dto.assignedTo()` | lit un paramètre de la méthode, par son nom |
| `@bean.method(#id, authentication)` | appelle un bean par son nom |
| `authentication` | l'`Authentication` courante |

Limites de cette version : la règle « compte actif » lit la base à chaque requête (le guide 17 ajoute un cache), et une règle de propriété ne voit que ce que sa requête vérifie ; un état métier comme « archivée » relève du service.

## Concepts avancés

### Sous le capot : le proxy de sécurité

Le proxy Spring du contrôleur évalue la SpEL avant d'appeler la méthode :

```
  DispatcherServlet
        |
        v
  proxy Spring du contrôleur
        |
        +-- lit @PreAuthorize (gabarit déjà résolu)
        +-- évalue la SpEL avec les arguments de l'appel
        |
        +-- faux --> AccessDeniedException
        |              |
        |              v
        |            remonte à travers Spring MVC jusqu'au
        |            ExceptionTranslationFilter --> 403 (401 si anonyme)
        |
        +-- vrai --> méthode du contrôleur
```

- La sécurité des méthodes enveloppe le bean dans un proxy Spring, comme `@Transactional` : un appel depuis la même classe (`this.deleteInvoice(...)`) n'est pas contrôlé.
- L'`AccessDeniedException` est levée dans l'appel du contrôleur, donc dans Spring MVC. Un `@ExceptionHandler(Exception.class)` global la voit et doit la relancer, sinon chaque 403 devient un 500 (voir le [cas pratique](cas-pratiques/requete-securite.md)).
- La SpEL lit les noms de paramètres dans le fichier de classe, ce qui demande l'option `-parameters` du compilateur, que `spring-boot-starter-parent` active. Sans elle, `#id` vaut `null` et la règle échoue pour tout le monde. `@P("id")` est l'alternative.

### Sous le capot : le gabarit `{value}`

- Spring Security lit la méta-annotation, remplace chaque `{attribut}` par la valeur de cet attribut sur la méthode annotée, puis analyse la SpEL.
- Une valeur `String` est insérée telle quelle : les guillemets vont dans les valeurs (`"'ADMIN'"`), pas autour de `{value}`. Une enum `ExpressionTemplateValueProvider` écrit ses propres guillemets.

### Plusieurs instances

La règle d'URL lit le statut par un cache propre à chaque instance : l'éviction suit la diffusion `AccountStatusChanged`, et le TTL de 30 s borne le délai si RabbitMQ est coupé (guide 17).

## Pièges à éviter

### Règles par URL

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire une règle large avant une règle précise | la précise n'est jamais lue | Ranger du plus précis au plus large |
| Écrire `/api/v1/tasks/**` | un autre chemin de version y échappe (guide 04) | Écrire `/api/*/tasks/**` |
| Se fier au jeton pour l'état du compte | un compte désactivé garde l'accès jusqu'à l'expiration | Relire le statut à chaque requête, avec un cache court |
| Garder le statut en cache sans éviction | la désactivation attend la fin du cache | Évincer après le commit de chaque changement (guide 17) |

### Sécurité des méthodes

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Oublier `@EnableMethodSecurity` | les `@PreAuthorize` sont ignorés, tout passe | Activer la sécurité des méthodes et tester un refus |
| Appeler la méthode protégée depuis sa propre classe | l'appel contourne le proxy : aucun contrôle | Passer par un autre bean |
| Nommer la variable de chemin autrement que la SpEL | `#id` vaut `null` : tout non-admin reçoit 403 | Nommer le paramètre `id`, ou poser `@P("id")` |
| Mettre des guillemets autour de `{value}` pour une liste | la SpEL obtenue est invalide | Mettre les guillemets dans les valeurs, ou dans l'enum |
| Attraper `AccessDeniedException` dans un gestionnaire global | chaque 403 devient un 500 | La relancer |
| Répondre 404 à un non-propriétaire | il apprend quels identifiants existent | Répondre 403 ; le 404 seulement à qui a le rôle |

### Avec les annotations de ce dépôt

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Écrire `@PreAuthorize` directement sur un contrôleur | `AccessDescriptionTest` échoue | Créer une méta-annotation avec `@AccessDescription` |
| Poser la règle sur la classe du contrôleur | la documentation ne lit que les méthodes : le test échoue | Annoter chaque méthode |

## En résumé

**À retenir :** une règle d'URL pour ce qui vaut pour tout un groupe d'endpoints (ici, un compte encore actif), puis une méta-annotation nommée par opération, qui combine rôles et bean propriétaire appelé par la SpEL ; le refus arrive avant le service.

- [ ] `@EnableMethodSecurity` et un test qui prouve un refus
- [ ] Règles d'URL du plus précis au plus large, `anyRequest()` en dernier
- [ ] Une règle « compte actif » qui relit le statut à chaque requête, avec un cache court
- [ ] Une méta-annotation par règle, jamais de SpEL recopiée sur les méthodes
- [ ] Bean `AnnotationTemplateExpressionDefaults` et enum de rôles `ExpressionTemplateValueProvider`
- [ ] Beans propriétaires nommés, une requête `exists...` chacun
- [ ] Variables de chemin nommées comme dans la SpEL (`id`)
- [ ] 403 pour un non-propriétaire, même sur une ressource absente
- [ ] Tests web : refus avant le service, l'admin ne consulte pas le bean propriétaire

## Dans ce dépôt

Le guide 07 dessine les règles de `SecurityConfiguration` et le [cas pratique](cas-pratiques/requete-securite.md) suit une requête de bout en bout. Voici le chemin d'une requête sur une tâche :

```
  /api/*/tasks/**, /api/*/notifications/**, /api/*/exports/**
        |
        v
  ActiveUserAuthorizationManager --> UserStatusLookup (cache userStatus, 30 s)
        |                                   |
        |                                   v
        |                     UserRepository.findAccountStateById
        v
  @AllowedRolesOrAssignedToOnly(UserRole.ADMIN), @AdminOnly, ...
        |
        v
  hasAnyRole('ADMIN') or @taskAuthorization
    .currentUserIsAssignedTo(#id, authentication)
```

| Fichier | Rôle |
|---|---|
| [`SecurityConfiguration`][security] | `@EnableMethodSecurity`, le bean `static` des gabarits, la règle `.access(...)` d'URL |
| [`ActiveUserAuthorizationManager`][active] | `AuthorizationManager<RequestAuthorizationContext>` : seul `ACTIVE` passe |
| [`UserStatusLookup`][lookup] | le statut en cache (guide 17) ; un compte introuvable vaut `DELETED` |
| [`CurrentUser`][current-user] | l'UUID de la claim `uid`, vide si absente ou malformée |
| [`UserRole`][role] | `USER`, `ADMIN`, rendus `'ADMIN'` dans la SpEL |
| [`AdminOnly`][admin-only], [`AllowedRolesOrSelfOnly`][self] | règles de rôle et de « soi-même » |
| [`AllowedRolesOrAssignedToOnly`][assigned] | l'assigné de la tâche, ou le rôle |
| [`UserAuthorization`][user-authz], [`TaskAuthorization`][task-authz] | beans propriétaires `userAuthorization`, `taskAuthorization` |
| [`AccessDescription`][access] | la phrase de la documentation pour chaque règle |
| [`AccessDescriptionTest`][access-test] | refuse une règle non décrite |

Les annotations et ce qu'elles laissent passer (le texte de leur `@AccessDescription`) :

| Annotation | SpEL | Qui passe |
|---|---|---|
| `@AdminOnly` | `hasRole({adminRole})`, `adminRole` vaut `ADMIN` par défaut | le rôle admin |
| `@AllowedRoles(...)` | `hasAnyRole({value})` | les rôles listés |
| `@SelfOnly` | `@userAuthorization.currentUserIsSelf(#id, authentication)` | l'utilisateur lui-même |
| `@AllowedRolesOrSelfOnly(...)` | rôles ou `currentUserIsSelf(#id, ...)` | l'utilisateur, ou le rôle |
| `@AllowedRolesOrAssignedToOnly(...)` | rôles ou `currentUserIsAssignedTo(#id, ...)` | l'assigné, ou le rôle |
| `@AllowedRolesOrUploaderOnly(...)` | rôles ou `currentUserUploaded(#attachmentId, ...)` | qui a déposé la pièce, ou le rôle |
| `@CommentAuthorOnly` | `@taskCommentAuthorization.currentUserWrote(#commentId, ...)` | l'auteur seul, admin compris |
| `@AllowedRolesOrCommentAuthorOnly(...)` | rôles ou `currentUserWrote(#commentId, ...)` | l'auteur, ou le rôle |
| `@AllowedRolesOrWithoutAssigneeOnly(...)` | `hasAnyRole({value}) or #dto.assignedTo() == null` | tout compte actif ; assigner demande le rôle |

| Réglage | Valeur | Rôle |
|---|---|---|
| `identity.status-cache.ttl` | `${IDENTITY_STATUS_CACHE_TTL:30s}` (1 s à 1 min) | durée du statut en cache pour la règle « compte actif » |
| `identity.status-cache.maximum-size` | `10000` | nombre de comptes en cache |

Ce qui est propre au dépôt :

- Les endpoints des utilisateurs et de l'authentification sont seulement `authenticated()` : un admin désactivé peut encore les utiliser (test `disabledAdminCanStillUseUserEndpoints`).
- Chaque règle de méthode est une méta-annotation avec `@AccessDescription` ; `OperationDocumentation` l'écrit comme « Access: » dans la description OpenAPI (guide 06). `AccessDescriptionTest` parcourt chaque `@Controller` et échoue sur un `@PreAuthorize` ou `@PostAuthorize` écrit sur un handler, une annotation de sécurité sans `@AccessDescription`, une règle sur la classe du contrôleur, ou un `{placeholder}` qui ne nomme aucun attribut.
- Les règles des pièces jointes et des commentaires lisent `#attachmentId` et `#commentId` au lieu de `#id`.
- `@CommentAuthorOnly` n'a pas de contournement par rôle, exprès : un admin peut supprimer un commentaire, jamais modifier ce que son auteur a écrit.

Les tests :

- [`TaskControllerWebMvcTests`][web-tests] vérifie `nonAssignedUserIsForbiddenBeforeTheServiceRuns` (avec `verifyNoInteractions(taskService)`), `assigneeCheckIsAskedAboutTheTaskOfThePath` et `adminSkipsTheAssigneeCheck` (avec `verifyNoInteractions(taskAuthorization)`).
- [`TaskAccessRulesApiTests`][api-tests] refuse les comptes non vérifiés, désactivés, supprimés ou inconnus, et couvre `stillValidTokenStopsWorkingOnceUserIsDisabled`.

Pour ajouter une règle : un bean `@Component("nom")` avec une méthode booléenne qui prend l'id et l'`Authentication`, une méta-annotation qui porte `@PreAuthorize` et `@AccessDescription`, le paramètre nommé comme la SpEL le lit, et un `@MockitoBean(name = "nom", ...)` ajouté à `@WebLayerTest`.

## Voir aussi

- [07. Chaîne de filtres et JWT](07-securite-jwt.md) : authentification, authorities et ordre des règles d'URL.
- [17. Cache Caffeine](17-cache-caffeine.md) : le cache `userStatus` et son éviction après le commit.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : `@AccessDescription` dans la documentation.
- [29. Couches de tests et contextes](29-tests-couches-contextes.md) : `@WebLayerTest` et les beans propriétaires simulés.
- [Cas pratique : une requête à travers la sécurité](cas-pratiques/requete-securite.md).
- Documentation officielle : [sécurité des méthodes](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html), [autoriser les requêtes HTTP](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).

[Sommaire](README.md) | [< 07. Chaîne de filtres et JWT](07-securite-jwt.md) | [09. Mots de passe et jetons opaques >](09-mots-de-passe-jetons.md)

[security]: ../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[active]: ../../src/main/java/io/julienmetral/tasks/identity/security/ActiveUserAuthorizationManager.java
[lookup]: ../../src/main/java/io/julienmetral/tasks/identity/security/UserStatusLookup.java
[current-user]: ../../src/main/java/io/julienmetral/tasks/identity/security/CurrentUser.java
[role]: ../../src/main/java/io/julienmetral/tasks/identity/entities/UserRole.java
[admin-only]: ../../src/main/java/io/julienmetral/tasks/shared/security/AdminOnly.java
[self]: ../../src/main/java/io/julienmetral/tasks/shared/security/AllowedRolesOrSelfOnly.java
[assigned]: ../../src/main/java/io/julienmetral/tasks/task/security/AllowedRolesOrAssignedToOnly.java
[user-authz]: ../../src/main/java/io/julienmetral/tasks/identity/security/UserAuthorization.java
[task-authz]: ../../src/main/java/io/julienmetral/tasks/task/security/TaskAuthorization.java
[access]: ../../src/main/java/io/julienmetral/tasks/shared/security/AccessDescription.java
[access-test]: ../../src/test/java/io/julienmetral/tasks/shared/security/AccessDescriptionTest.java
[web-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskControllerWebMvcTests.java
[api-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskAccessRulesApiTests.java
