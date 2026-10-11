# 07. Chaîne de filtres et JWT

[Sommaire](README.md) | [< 06. OpenAPI avec springdoc](06-openapi-springdoc.md) | [08. Autorisations >](08-autorisations.md)

## Contexte

- **Problème** : savoir qui envoie chaque requête d'une API REST, sans session côté serveur, tout en laissant quelques endpoints ouverts à tous (connexion, inscription).
- **Quand l'utiliser** : une API appelée par une application web ou mobile, qui envoie un jeton dans l'en-tête `Authorization`.
- **Quand s'en passer** : une application rendue côté serveur (une session et un cookie suffisent) ; des comptes gérés par un fournisseur d'identité (Keycloak, Auth0), qui émet lui-même les jetons.
- **Dans ce dépôt** : l'API émet ses propres JWT signés en HS256 à la connexion, puis les vérifie sur chaque requête, comme un serveur de ressources OAuth2.
- **Prérequis** : guides 01 (configuration) et 05 (erreurs). Versions : Spring Boot 4.1, Spring Security 7.1.

Les façons courantes d'authentifier les appels d'une API :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Session et cookie | le serveur garde l'état, le navigateur renvoie un cookie | application web classique, une seule origine |
| JWT émis par l'API (HS256) | l'API signe et vérifie avec le même secret | une API qui gère ses propres comptes, cas de ce dépôt |
| JWT d'un fournisseur (RS256) | le fournisseur signe, l'API vérifie avec sa clé publique | plusieurs services, connexion unique (SSO) |
| Jeton opaque et introspection | l'API demande au serveur d'autorisation si le jeton vaut encore | révocation immédiate, au prix d'un appel par requête |

## Concepts clés

### Vocabulaire

- **Chaîne de filtres** : la suite de filtres Servlet que Spring Security fait traverser à chaque requête, avant le contrôleur.
- **JWT** (JSON Web Token) : un jeton signé, en trois parties Base64url séparées par des points : en-tête, contenu, signature.
- **Claim** : un champ du contenu d'un JWT, standard (`sub`, `exp`) ou maison (`uid`).
- **Bearer token** : un jeton envoyé dans `Authorization: Bearer <jeton>` ; celui qui le porte a les droits qu'il donne.
- **Serveur de ressources** : dans le vocabulaire OAuth2, l'API qui reçoit les jetons et les vérifie.
- **Authority** : un droit de l'appelant, sous forme de texte (`ROLE_ADMIN`).

### La chaîne de filtres

Chaque requête traverse les filtres de Spring Security avant d'atteindre Spring MVC :

```
  requête HTTP
       |
       v
  +-------------------------------------------------------------+
  |  chaîne de filtres de Spring Security                       |
  |                                                             |
  |  BearerTokenAuthenticationFilter   lit et vérifie le jeton  |
  |       |                                                     |
  |       v                                                     |
  |  ExceptionTranslationFilter        un refus devient 401/403 |
  |       |                                                     |
  |       v                                                     |
  |  AuthorizationFilter               applique les règles      |
  +-------------------------------------------------------------+
       |
       v
  DispatcherServlet --> contrôleur
```

- Sans jeton, la requête continue en anonyme. Un jeton présent mais invalide (mal signé, expiré) l'arrête tout de suite avec un 401.
- `AuthorizationFilter` applique ensuite les règles par URL. Un refus donne 401 à un anonyme et 403 à un appelant authentifié.

### Le JWT signé

L'API signe le jeton à la connexion, puis vérifie la signature à chaque requête :

```
  connexion                               requête suivante
      |                                         |
      v                                         v
  l'API signe { uid, roles, exp }         Authorization: Bearer eyJ...
  avec le secret                                |
      |                                         v
      v                                   l'API recalcule la signature
  eyJhbGci... . eyJ1aWQi... . SflKxw      avec le même secret
   en-tête      contenu      signature          |
                                                v
                                  identique et non expiré ? -- non --> 401
                                                | oui
                                                v
                                        appelant authentifié
```

- Le contenu est encodé en Base64url, pas chiffré. La signature empêche de le modifier, pas de le lire.
- HS256 utilise un seul secret pour signer et vérifier, donc qui peut vérifier peut aussi émettre. RS256 sépare une clé privée (signer) et une clé publique (vérifier).
- Pour HS256, le secret doit faire au moins 256 bits (RFC 7518).

### Sans état, donc sans CSRF

Une API « sans état » (stateless) ne garde rien entre deux requêtes : tout est dans le jeton.

- Aucune session HTTP, donc rien à partager entre instances.
- Aucune déconnexion d'un jeton d'accès : il reste valable jusqu'à son expiration. On le garde court (15 min) et on le renouvelle par un jeton de rafraîchissement (guide 09). L'état du compte se vérifie à part (guide 08).
- Aucune protection CSRF nécessaire. Une attaque CSRF pousse le navigateur de la victime à envoyer, depuis un autre site, une requête où il ajoute tout seul ses cookies. Il n'ajoute jamais de lui-même un jeton `Authorization` : la requête forgée arrive sans droits.

### Public ne veut pas dire « sans jeton »

`permitAll()` autorise la requête, mais n'empêche pas le premier filtre de lire un jeton présent :

```
  POST /api/v1/auth/login, avec un vieux jeton expiré dans Authorization
                 |
                 v
  BearerTokenAuthenticationFilter : un jeton est présent
                 |
                 v
  jeton expiré --> 401, avant même que la règle permitAll soit lue
```

Un client qui a gardé son jeton expiré reçoit donc 401 de l'endpoint qui doit lui en donner un nouveau. La parade est un résolveur de jeton qui ignore l'en-tête sur les endpoints publics (étape 8).

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                  la dépendance (étape 1)
src/main/resources/
  application.yaml                       émetteur, durée, secret (étape 2)
src/main/java/.../
  security/
    JwtProperties.java                   propriétés typées (étape 2)
    JwtConfiguration.java                clé, encodeur, décodeur (étape 3)
    JwtService.java                      émet le jeton (étape 4)
    LoginConfiguration.java              l'AuthenticationManager (étape 5)
    PublicEndpoints.java                 les endpoints publics (étape 6)
    SecurityConfiguration.java           la chaîne de filtres (étapes 7 et 8)
  auth/
    AuthService.java                     la connexion (étape 5)
    AuthController.java                  POST /api/v1/auth/login (étape 5)
src/test/java/.../
  BearerTokenTests.java                  la vérification (étape 9)
```

### Une API sans session, avec ses propres JWT

**Étape 1. Ajouter la dépendance.** Le starter apporte Spring Security et Nimbus, qui signe et vérifie les JWT.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

**Étape 2. Déclarer l'émetteur, la durée et le secret.** Le secret vient d'une variable d'environnement.

```yaml
security:
  jwt:
    issuer: my-api
    ttl: 15m
    secret: ${JWT_SECRET}      # Base64, au moins 32 octets : openssl rand -base64 32
```

```java
@ConfigurationProperties(prefix = "security.jwt")
public record JwtProperties(String issuer, Duration ttl, String secret) {
}
```

**Étape 3. Créer la clé, l'encodeur et le décodeur.** Le décodeur vérifie la signature, l'expiration et l'émetteur.

```java
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] secret = Base64.getDecoder().decode(properties.secret());
        if (secret.length < 32) {
            // Nimbus refuserait de signer plus tard : on échoue dès le démarrage
            throw new IllegalStateException("JWT secret must contain at least 256 bits");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // expiration (60 s de tolérance) et émetteur ; remplace le validateur par défaut
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
```

**Étape 4. Émettre le jeton.** On n'y met que des identifiants et des rôles, puisque le contenu est lisible.

```java
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public String generate(UUID userId, String email, Collection<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(email)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .id(UUID.randomUUID().toString())                 // jti : identifiant unique du jeton
                .claim("uid", userId.toString())
                .claim("roles", roles.stream().map(role -> "ROLE_" + role).sorted().toList())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
```

**Étape 5. Brancher la connexion.** Un `AuthenticationManager` vérifie l'email et le mot de passe, puis le service émet le jeton.

```java
@Configuration
class LoginConfiguration {

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,   // guide 09
                                                PasswordEncoder passwordEncoder) {       // guide 09
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final AccountRepository accountRepository;   // entité Account : id, email, rôles (guide 11)
    private final JwtService jwtService;

    @Transactional
    public String login(String email, String password) {
        try {
            Authentication authenticated = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, password));
            Account account = accountRepository.findByEmailIgnoreCase(authenticated.getName()).orElseThrow();
            return jwtService.generate(account.getId(), account.getEmail(), account.getRoles());
        } catch (AuthenticationException refused) {
            // email inconnu, mauvais mot de passe ou compte désactivé : une seule réponse
            throw new InvalidCredentialsException();          // traduite en 401 (guide 05)
        }
    }
}
```

```java
@RestController
@RequiredArgsConstructor
class AuthController {

    private final AuthService authService;

    record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    @PostMapping("/api/v1/auth/login")
    Map<String, String> login(@Valid @RequestBody LoginRequest request) {
        return Map.of("accessToken", authService.login(request.email(), request.password()));
    }
}
```

**Étape 6. Lister les endpoints publics.** Une seule liste, lue par la chaîne, le résolveur et la documentation OpenAPI (guide 06).

```java
public final class PublicEndpoints {

    public record Endpoint(HttpMethod method, String pattern) {
    }

    public static final List<Endpoint> ALL = List.of(
            new Endpoint(HttpMethod.POST, "/api/v1/users"),           // inscription
            new Endpoint(HttpMethod.POST, "/api/v1/auth/login"),
            new Endpoint(HttpMethod.POST, "/api/v1/auth/refresh"));   // guide 09

    private PublicEndpoints() {
    }
}
```

**Étape 7. Écrire la chaîne de filtres.** Ni CSRF ni session, endpoints publics ouverts, le reste authentifié.

```java
@Configuration
@EnableMethodSecurity                                         // pour @PreAuthorize (guide 08)
public class SecurityConfiguration {

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");         // au lieu de "scope"
        authorities.setAuthorityPrefix("");                   // les valeurs portent déjà ROLE_
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // rendu d'une erreur (/error), fin d'une réponse asynchrone : déjà autorisés
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll();
                    PublicEndpoints.ALL.forEach(endpoint ->
                            auth.requestMatchers(endpoint.method(), endpoint.pattern()).permitAll());
                    auth.anyRequest().authenticated();        // toujours en dernier
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
```

CSRF est actif par défaut, il faut donc le couper. Couper `formLogin` et `httpBasic` rend l'intention explicite.

**Étape 8. Ignorer le jeton sur les endpoints publics.** Spring Security utilise un bean `BearerTokenResolver` à la place du sien. Renvoyer `null` veut dire « pas de jeton ».

```java
// dans SecurityConfiguration
@Bean
BearerTokenResolver bearerTokenResolver() {
    DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
    RequestMatcher publicEndpoint = new OrRequestMatcher(PublicEndpoints.ALL.stream()
            .<RequestMatcher>map(endpoint -> PathPatternRequestMatcher.withDefaults()
                    .matcher(endpoint.method(), endpoint.pattern()))
            .toList());
    return request -> publicEndpoint.matches(request) ? null : resolver.resolve(request);
}
```

**Étape 9. Vérifier.** De vrais en-têtes `Authorization` : décodeur, convertisseur et résolveur tournent pour de bon.

```java
@WebMvcTest                               // secret de test dans src/test/resources/config/application.yaml
@Import({SecurityConfiguration.class, JwtConfiguration.class})
class BearerTokenTests {

    @Autowired MockMvc mockMvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean AuthService authService;

    @Test
    void protectedEndpointWithoutTokenAnswers401WithABearerChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
    }

    @Test
    void expiredTokenIsRejectedOnAProtectedEndpointButIgnoredOnLogin() throws Exception {
        String expired = "Bearer " + tokenExpiringAt(Instant.now().minus(Duration.ofMinutes(10)));
        when(authService.login("a@example.com", "secret")).thenReturn("new-token");

        mockMvc.perform(get("/api/v1/tasks").header(HttpHeaders.AUTHORIZATION, expired))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, expired)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@example.com\", \"password\": \"secret\"}"))
                .andExpect(status().isOk());
    }

    private String tokenExpiringAt(Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("my-api").subject("a@example.com")
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15))).expiresAt(expiresAt).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
```

### Référence rapide

Les claims standard posées par `JwtClaimsSet.Builder` : `iss` (`issuer`, comparé au décodage), `sub` (`subject`), `iat` et `exp` (`issuedAt`, `expiresAt`), `jti` (`id`).

Les réponses d'un refus :

| Cas | Statut | En-tête `WWW-Authenticate` |
|---|---|---|
| aucun jeton sur un endpoint protégé | 401 | `Bearer` |
| jeton mal formé, mal signé, expiré ou d'un autre émetteur | 401 | `Bearer error="invalid_token", ...` |
| appelant authentifié, refusé par une règle | 403 | `Bearer error="insufficient_scope", ...` |

Limites de cette version : un jeton d'accès reste valable jusqu'à son expiration, même si le compte est désactivé entre-temps (guide 08). Changer le secret invalide tous les jetons d'accès d'un coup.

## Concepts avancés

### Sous le capot : la vérification d'un jeton

Ce que fait Spring Security quand un `Bearer` arrive :

```
  Authorization: Bearer eyJ...
            |
            v
  BearerTokenResolver            extrait le jeton (null : requête anonyme)
            |
            v
  JwtDecoder                     signature HS256, puis les validateurs :
            |                    exp et nbf (60 s de tolérance), iss
            v
  JwtAuthenticationConverter     claim "roles" --> authorities
            |
            v
  JwtAuthenticationToken         rangé dans le SecurityContext de la requête
            |
            v
  AuthorizationFilter            règles par URL, puis @PreAuthorize (guide 08)
```

- Avec HS256, le décodeur ne fait aucun appel réseau.
- Le principal d'un `JwtAuthenticationToken` est le `Jwt`, et son nom est la claim `sub`. On lit une claim maison avec `authentication.getToken().getClaimAsString("uid")`.
- Par défaut, le convertisseur lit la claim `scope` (ou `scp`) et préfixe chaque valeur par `SCOPE_`. D'où les deux réglages de l'étape 7.

### Sous le capot : la connexion

Le trajet de `authenticationManager.authenticate(...)` :

```
  UsernamePasswordAuthenticationToken(email, mot de passe)
            |
            v
  ProviderManager --> DaoAuthenticationProvider
            |
            v
  UserDetailsService.loadUserByUsername(email)
            |
            +-- email inconnu --> comparaison factice --> BadCredentials
            |
            v
  compte désactivé ? ---------- oui --> DisabledException
            | non
            v
  PasswordEncoder.matches ? --- non --> BadCredentialsException
            | oui
            v
  hachage à moderniser ? ------ oui --> UserDetailsPasswordService (09)
            |
            v
  authentification réussie
```

- Pour un email inconnu, le fournisseur compare quand même le mot de passe à un hachage factice. La durée de réponse reste la même, et l'existence du compte ne fuit pas.
- Le compte désactivé est refusé avant la comparaison. Le service traduit toutes ces exceptions en un seul 401.

### Sous le capot : les dispatchs internes

Une même requête peut traverser la chaîne plusieurs fois :

```
  POST /api/v1/auth/login, JSON mal formé
            |
            v
  chaîne de filtres : endpoint public --> autorisée
            |
            v
  Spring MVC : sendError(400) --> le conteneur rejoue la requête
                                  vers /error (dispatch ERROR)
            |
            v
  chaîne de filtres à nouveau : /error n'est pas public, appelant anonyme
            |
            +-- sans règle ERROR -----------> 401 à la place du 400
            |
            +-- ERROR autorisé -------------> le 400 arrive au client
```

- Le dispatch `ERROR` rend une erreur déjà produite par une requête autorisée : on le laisse passer.
- Le dispatch `ASYNC` termine une réponse asynchrone, comme un flux SSE (guide 24) : la requête a été autorisée à l'ouverture.

### Un second port pour Actuator

Quand Actuator écoute sur son propre port (`management.server.port`, guide 32), ses requêtes traversent la même chaîne. Ses pages d'erreur resteraient derrière l'authentification, donc on autorise tout ce qui arrive sur ce port :

```java
// local.management.port : publié par Spring Boot une fois le port de gestion démarré
private static RequestMatcher onManagementPort(Environment environment) {
    return request -> String.valueOf(request.getLocalPort())
            .equals(environment.getProperty("local.management.port"));
}

// dans authorizeHttpRequests, avant anyRequest()
auth.requestMatchers(onManagementPort(environment)).permitAll();
```

Ce port ne doit être joignable que depuis le réseau de supervision.

### Plusieurs instances

- Chaque instance vérifie seule, sans session collante (sticky). Toutes doivent recevoir le même `JWT_SECRET`, sinon un jeton émis par l'une est refusé par l'autre.
- Changer le secret invalide les jetons d'accès en cours. Le jeton de rafraîchissement, opaque, ne dépend pas du secret (guide 09).
- Dès que plusieurs services vérifient les jetons, on passe à RS256, car avec HS256 chacun pourrait en émettre.

### Surveiller

- `logging.level.org.springframework.security: TRACE` affiche chaque filtre et chaque décision. C'est utile en développement, trop bavard en production.
- Un 401 porte sa raison dans `WWW-Authenticate` (`error_description`).

## Pièges à éviter

### Dans la chaîne de filtres

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser CSRF actif sur une API à jetons | chaque POST, PUT, PATCH ou DELETE répond 403 | Désactiver CSRF quand aucun cookie n'authentifie |
| Désactiver CSRF avec une session par cookie | un autre site agit au nom de l'utilisateur connecté | Garder CSRF dès qu'un cookie porte la session |
| Oublier la règle du dispatch `ERROR` | un 400 d'un endpoint public répond 401 | Ajouter `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` |
| Écrire une règle après `anyRequest()` | Spring Security refuse de démarrer | Garder `anyRequest()` en dernier |
| Ne protéger que les endpoints du port de gestion | ses pages d'erreur répondent 401 | Autoriser tout ce qui arrive sur ce port |

### Avec les JWT

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder le préfixe `SCOPE_` du convertisseur | les rôles deviennent `SCOPE_ROLE_ADMIN` : `hasRole('ADMIN')` refuse tout | Mettre un préfixe vide |
| Garder le résolveur par défaut | un jeton expiré oublié dans l'en-tête fait répondre 401 à la connexion | Ignorer l'en-tête sur les endpoints publics |
| Valider avec `new JwtIssuerValidator(...)` seul | l'expiration n'est plus vérifiée | Partir de `JwtValidators.createDefaultWithIssuer(...)` |
| Choisir un secret court, lisible ou versionné | Nimbus refuse de signer, ou le secret se devine | Tirer 32 octets aléatoires, en variable d'environnement |
| Mettre des données sensibles dans les claims | tout le monde lit le contenu, encodé mais pas chiffré | Se limiter aux identifiants et aux rôles |
| Allonger la durée du jeton d'accès | un compte désactivé garde l'accès d'autant plus longtemps | Garder 15 min, avec un jeton de rafraîchissement (guide 09) |
| Distinguer « email inconnu » et « mauvais mot de passe » | on devine quels emails ont un compte | Répondre le même 401, avec le même message |

## En résumé

**À retenir :** une API à jetons coupe CSRF et les sessions, vérifie chaque `Bearer` avec un `JwtDecoder` (signature, expiration, émetteur), lit les rôles sans préfixe ajouté, et ignore l'en-tête `Authorization` sur ses endpoints publics.

- [ ] CSRF désactivé, `STATELESS`, `formLogin` et `httpBasic` coupés
- [ ] Secret Base64 d'au moins 32 octets, hors du dépôt, contrôlé au démarrage
- [ ] Un `JwtDecoder` validé par `createDefaultWithIssuer`
- [ ] Des claims minimales : `sub`, `exp`, `iss`, `jti`, un identifiant, les rôles
- [ ] Le convertisseur sur la claim des rôles, avec un préfixe vide
- [ ] Une liste unique d'endpoints publics, lue par la sécurité et la documentation
- [ ] Un `BearerTokenResolver` qui ignore l'en-tête sur ces endpoints
- [ ] Les dispatchs `ERROR` et `ASYNC` autorisés, `anyRequest()` en dernier
- [ ] Un jeton d'accès court, et l'état du compte vérifié à part (guide 08)
- [ ] Des tests avec de vrais en-têtes `Authorization`, pas seulement `jwt()`

## Dans ce dépôt

Les règles de [`SecurityConfiguration`][security], dans l'ordre où elles s'appliquent :

```
  requête
     |
     v
  dispatch ERROR ou ASYNC ? ----------------------------- oui --> autorisée
     | non
     v
  endpoint de PublicEndpoints ? ------------------------- oui --> autorisée
     | non
     v
  arrivée sur le port de gestion ? ---------------------- oui --> autorisée
     | non
     v
  GET de /v3/api-docs, /swagger-ui, /livez, /readyz, /ws  oui --> autorisée
     | non
     v
  /api/*/tasks/**, /notifications/**, /exports/** ? ----- oui --> compte actif
     | non                                                        (guide 08)
     v
  authentifiée ? ---------------------------------------- non --> 401
     | oui
     v
  contrôleur, puis @PreAuthorize (guide 08)
```

| Fichier | Rôle |
|---|---|
| [`SecurityConfiguration`][security] | la chaîne, le convertisseur, le résolveur, l'`AuthenticationManager` et le `PasswordEncoder` |
| [`JwtConfiguration`][jwt-config] | la clé (refusée sous 32 octets), l'encodeur et le décodeur |
| [`JwtProperties`][jwt-properties] | les réglages `security.jwt.*` |
| [`JwtService`][jwt-service] | émet le jeton d'accès, à l'heure système : le décodeur compare `exp` à cette heure (guide 01) |
| [`PublicEndpoints`][public] | la liste unique des endpoints publics, lue aussi par la documentation OpenAPI |
| [`AuthService`][auth-service] | connexion, rafraîchissement et déconnexion |
| [`DatabaseUserDetailsService`][user-details] | charge le compte par email pour `DaoAuthenticationProvider` |
| [`CurrentUser`][current-user] | lit la claim `uid` d'un `JwtAuthenticationToken` |

Les réglages, dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `security.jwt.issuer` | `tasks-api` | émetteur écrit dans le jeton, puis comparé au décodage |
| `security.jwt.ttl` | 15m | durée du jeton d'accès |
| `security.jwt.secret` | `${JWT_SECRET}` | secret HS256 en Base64 ; réglage obligatoire du profil `prod` (guide 02) |

Ce qui est propre au dépôt :

- Le jeton porte `sub` (l'email), `uid` (l'UUID du compte), `roles` (triés, avec `ROLE_`) et un `jti` aléatoire. `CurrentUser` identifie l'appelant par `uid`, jamais par `sub`.
- Le résolveur ignore aussi l'en-tête sur `/livez`, `/readyz` et la poignée de main `/ws` : le jeton des salons de tâches arrive dans la trame STOMP `CONNECT` (guide 25).
- Les endpoints publics sont écrits en `/api/v1/...`, les règles restrictives en `/api/*/...`, pour couvrir toutes les versions (guide 04).
- Le port de gestion est reconnu par `local.management.port`. Si Actuator partage le port de l'API, la propriété est absente et la règle ne correspond à rien.
- `JWT_SECRET` se règle dans `.env` en développement (voir `.env.example`) ; les tests ont leur secret dans `src/test/resources/config/application.yaml`.

Les tests : [`BearerTokenWebMvcTests`][bearer-tests] (vrais en-têtes : jeton modifié, autre clé, autre émetteur, expiration, rôles lus sans préfixe), [`JwtConfigurationTest`][jwt-config-test] (secret de moins de 32 octets refusé), [`RefreshTokenApiTests`][refresh-tests] (un jeton expiré laissé dans l'en-tête n'empêche pas le rafraîchissement), [`PublicEndpointErrorTests`][error-tests] (sur un vrai port, le 400 d'un endpoint public reste un 400), [`AuthControllerTests`][auth-tests] (la connexion de bout en bout).

Pour ajouter un endpoint public : une entrée dans `PublicEndpoints`, lue par la chaîne, le résolveur et la documentation OpenAPI.

## Voir aussi

- [08. Autorisations](08-autorisations.md) : les règles après l'authentification, et l'état du compte vérifié à chaque requête.
- [09. Mots de passe et jetons opaques](09-mots-de-passe-jetons.md) : le hachage des mots de passe et le jeton de rafraîchissement.
- [04. Versioning d'API](04-versioning-api.md) : pourquoi les règles restrictives s'écrivent `/api/*/`.
- [32. Actuator, sondes et métriques](32-actuator-metriques.md) : le port de gestion et les sondes.
- [Cas pratique : une requête à travers la sécurité](cas-pratiques/requete-securite.md).
- Documentation officielle : [serveur de ressources JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [architecture des filtres](https://docs.spring.io/spring-security/reference/servlet/architecture.html), [RFC 7519 (JWT)](https://www.rfc-editor.org/rfc/rfc7519).

[Sommaire](README.md) | [< 06. OpenAPI avec springdoc](06-openapi-springdoc.md) | [08. Autorisations >](08-autorisations.md)

[security]: ../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[jwt-config]: ../../src/main/java/io/julienmetral/tasks/identity/security/JwtConfiguration.java
[jwt-properties]: ../../src/main/java/io/julienmetral/tasks/identity/security/JwtProperties.java
[jwt-service]: ../../src/main/java/io/julienmetral/tasks/identity/services/JwtService.java
[public]: ../../src/main/java/io/julienmetral/tasks/identity/security/PublicEndpoints.java
[auth-service]: ../../src/main/java/io/julienmetral/tasks/identity/services/AuthService.java
[user-details]: ../../src/main/java/io/julienmetral/tasks/identity/services/DatabaseUserDetailsService.java
[current-user]: ../../src/main/java/io/julienmetral/tasks/identity/security/CurrentUser.java
[bearer-tests]: ../../src/test/java/io/julienmetral/tasks/identity/security/BearerTokenWebMvcTests.java
[jwt-config-test]: ../../src/test/java/io/julienmetral/tasks/identity/security/JwtConfigurationTest.java
[refresh-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/RefreshTokenApiTests.java
[error-tests]: ../../src/test/java/io/julienmetral/tasks/identity/security/PublicEndpointErrorTests.java
[auth-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/AuthControllerTests.java
