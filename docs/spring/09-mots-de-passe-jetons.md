# 09. Mots de passe et jetons opaques

[Sommaire](README.md) | [< 08. Autorisations](08-autorisations.md) | [10. Rate limiting >](10-rate-limiting.md)

## Contexte

- **Problème** : stocker les mots de passe pour qu'une base volée ne les livre pas, changer d'algorithme sans obliger chacun à réinitialiser le sien, et donner aux clients une session longue (le jeton de rafraîchissement) qu'on peut révoquer et dont le vol se détecte.
- **Quand l'utiliser** : dès que l'application tient ses propres comptes et mots de passe, ou envoie des liens secrets (vérification d'e-mail, réinitialisation).
- **Quand s'en passer** : des comptes gérés par un fournisseur d'identité (Keycloak, Auth0, Google), qui stocke les mots de passe et émet les jetons de rafraîchissement lui-même.
- **Dans ce dépôt** : Argon2id pour les nouveaux hachages, BCrypt gardé pour vérifier les anciens, qui sont modernisés à la connexion suivante. Les jetons de rafraîchissement opaques (30 jours) tournent par famille, avec détection de réutilisation. Les jetons de vérification d'e-mail (24 h) et de réinitialisation (1 h) sont à usage unique. Seul le SHA-256 des jetons est stocké.
- **Prérequis** : guide 07 (connexion, JWT). Versions : Spring Boot 4.1, Spring Security 7.1, Bouncy Castle 1.86.

Les algorithmes de hachage de mots de passe proposés par Spring Security :

| Algorithme | Point fort | Quand le choisir |
|---|---|---|
| Argon2id | coûteux en mémoire, résiste aux cartes graphiques | nouveau projet, recommandation de l'OWASP |
| BCrypt | éprouvé, sans dépendance | existant, ou faute de Bouncy Castle |
| PBKDF2 | algorithme validé FIPS-140 | contrainte réglementaire FIPS |

## Concepts clés

### Vocabulaire

- **Hachage lent** : une fonction à sens unique rendue exprès coûteuse (temps, mémoire), pour qu'essayer des milliards de candidats coûte trop cher.
- **Sel** : des octets aléatoires mêlés à chaque hachage, pour que deux mots de passe identiques donnent deux hachages différents et que les tables précalculées ne servent à rien.
- **Jeton opaque** : une chaîne aléatoire sans contenu lisible. Seul le serveur en connaît le sens, en la cherchant dans sa base.
- **Rotation** : chaque usage d'un jeton de rafraîchissement le révoque et en donne un nouveau.
- **Famille** : tous les jetons de rafraîchissement nés d'une même connexion. Révoquer la famille termine cette session partout.
- **Usage unique** : un jeton marqué utilisé dès son premier usage, refusé ensuite.

### Hacher un mot de passe

Voici la valeur stockée, champ par champ :

```
 {argon2id}$argon2id$v=19$m=19456,t=2,p=1$<sel>$<hash>
     |          |       |    |      |   |
     |          |       |    |      |   +-- fils en parallèle
     |          |       |    |      +------ passes (temps)
     |          |       |    +------------- mémoire, en Kio (19 Mio)
     |          |       +------------------ version d'Argon2
     |          +-------------------------- variante
     +---- encodeur choisi par DelegatingPasswordEncoder
```

- `DelegatingPasswordEncoder` préfixe chaque hachage par `{id}` et choisit l'encodeur selon cet id pour vérifier. Il hache toujours les nouveaux mots de passe avec son id par défaut.
- Tous les paramètres sont dans le hachage, donc les changer plus tard ne casse pas les hachages existants.
- Minimum de l'OWASP pour Argon2id : 19 Mio de mémoire, 2 itérations, parallélisme 1.
- L'`Argon2PasswordEncoder` de Spring demande Bouncy Castle dans le classpath (`bcprov-jdk18on`).

### Changer d'algorithme sans réinitialiser

Le mot de passe saisi, en clair à cet instant, sert à refaire le hachage :

```
  connexion réussie avec un hachage {bcrypt}...
        |
        v
  DelegatingPasswordEncoder.upgradeEncoding ?
        |
        +-- id différent de argon2id -----------+
        +-- argon2id, mais m ou t plus faibles --+
        |                                        |
        | non                                    v
        v                         le mot de passe saisi, en clair à cet
  rien à faire                    instant, est haché en {argon2id}
                                         |
                                         v
                       UserDetailsPasswordService.updatePassword
                       (la nouvelle valeur est écrite en base)
```

- La modernisation ne peut se faire qu'à la connexion, seul moment où le serveur a le mot de passe en clair.
- `Argon2PasswordEncoder.upgradeEncoding` renvoie vrai quand la mémoire ou les itérations stockées sont sous les valeurs actuelles. Relever les paramètres plus tard refait donc les hachages progressivement.
- Un compte qui ne se reconnecte jamais garde son ancien hachage.

### Le jeton opaque

Le client reçoit le jeton une fois, et la base ne garde que son empreinte :

```
  32 octets de SecureRandom (256 bits)
        |
        v
  Base64url sans padding : 43 caractères  --> envoyé au client, une seule fois
        |
        v
  SHA-256 en hexadécimal : 64 caractères  --> seul stocké, colonne unique
```

- Un hachage rapide suffit ici, contrairement aux mots de passe. Le jeton a 256 bits d'entropie, donc tester des candidats est sans espoir quelle que soit la vitesse du hachage. Un mot de passe, choisi par un humain, se devine.
- Un hachage sans sel se cherche par sa valeur (`WHERE token_hash = ?`, index unique). Un hachage lent salé (Argon2, BCrypt) obligerait à tester chaque ligne.
- Le jeton d'accès JWT (guide 07) se vérifie sans la base mais ne se révoque pas. Le jeton de rafraîchissement opaque se vérifie en base à chaque usage, donc il se révoque.

### Rotation et détection de réutilisation

Chaque rafraîchissement remplace le jeton. Représenter un ancien jeton révèle une fuite :

```
  connexion            --> R1  (famille F)
  rafraîchissement R1  --> R1 révoqué, R2 (famille F)
  rafraîchissement R2  --> R2 révoqué, R3 (famille F)

  quelqu'un présente R1 à nouveau
        |
        v
  R1 déjà révoqué : il a fuité --> toute la famille F révoquée
                                   (R3 aussi) --> 401
```

- Le premier rejeu d'un ancien jeton termine la session du voleur et celle du vrai client, qui se reconnecte.
- La déconnexion révoque toute la famille. Désactiver ou supprimer un compte révoque toutes ses familles.
- Un jeton d'accès déjà émis reste valable jusqu'à son expiration (15 min), car la révocation agit sur les jetons de rafraîchissement. La règle « compte actif » du guide 08 comble le trou pour les endpoints des tâches.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                Bouncy Castle (étape 1)
src/main/java/.../
  security/
    PasswordConfiguration.java         l'encodeur (étape 2)
    OpaqueTokens.java                  génération et hachage (étape 5)
  account/
    AccountService.java                inscription (étape 3)
    AccountDetailsService.java         modernisation des hachages (étape 4)
  token/
    RefreshToken.java                  l'entité (étape 6)
    RefreshTokenRepository.java        verrou et révocation (étape 6)
    RefreshTokenService.java           émission, rotation (étape 7)
src/test/java/.../
  RefreshTokenRotationTests.java       la vérification (étape 8)
```

### Les mots de passe

L'exemple utilise `Account` (id, email, passwordHash, enabled) et `AccountRepository` (guide 11).

**Étape 1. Ajouter Bouncy Castle.** L'encodeur Argon2 de Spring Security s'appuie sur son implémentation, et Spring Boot n'en gère pas la version.

```xml
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>1.86</version>
</dependency>
```

**Étape 2. Déclarer l'encodeur.** Argon2id pour tout nouveau hachage, BCrypt seulement pour vérifier ceux déjà stockés, aucun autre id accepté.

```java
@Configuration
class PasswordConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        String current = "argon2id";
        return new DelegatingPasswordEncoder(current, Map.of(
                // sel 16 octets, hachage 32 octets, 1 fil, 19 456 Kio (19 Mio), 2 passes : minimum OWASP
                current, new Argon2PasswordEncoder(16, 32, 1, 19_456, 2),
                "bcrypt", new BCryptPasswordEncoder()));      // vérifie les anciens, n'en crée plus
    }
}
```

L'ordre du constructeur est `(sel, hachage, parallélisme, mémoire, itérations)`. `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` vaut `(16, 32, 1, 16384, 2)`, soit 16 Mio, sous le minimum de l'OWASP. D'où les valeurs explicites.

**Étape 3. Hacher à l'inscription.** Le mot de passe en clair n'atteint jamais la base ni les logs.

```java
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Account signUp(String email, String password) {
        Account account = new Account();
        account.setEmail(email.trim().toLowerCase(Locale.ROOT));
        account.setPasswordHash(passwordEncoder.encode(password));   // "{argon2id}$argon2id$v=19$..."
        account.setEnabled(true);
        return accountRepository.save(account);
    }
}
```

**Étape 4. Moderniser les anciens hachages à la connexion.** `DaoAuthenticationProvider` passe le mot de passe ré-encodé à un `UserDetailsPasswordService` quand le hachage stocké est dépassé.

```java
@Service
@RequiredArgsConstructor
public class AccountDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final AccountRepository accountRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        Account account = accountRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        // User : org.springframework.security.core.userdetails.User, pas l'entité du projet
        return User.withUsername(account.getEmail())
                .password(account.getPasswordHash())
                .disabled(!account.isEnabled())
                .roles("USER")
                .build();
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newEncodedPassword) {
        // newEncodedPassword est déjà haché en argon2id
        accountRepository.findByEmailIgnoreCase(user.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"))
                .setPasswordHash(newEncodedPassword);
        return User.withUserDetails(user).password(newEncodedPassword).build();
    }
}
```

```java
// dans la configuration de la connexion (guide 07)
@Bean
DaoAuthenticationProvider authenticationProvider(AccountDetailsService accounts,
                                                 PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
    provider.setPasswordEncoder(passwordEncoder);
    provider.setUserDetailsPasswordService(accounts);   // sans cette ligne, rien n'est modernisé
    return provider;
}
```

### Les jetons de rafraîchissement

**Étape 5. Générer et hacher un jeton opaque.** 256 bits aléatoires pour le client, un SHA-256 pour la base.

```java
public final class OpaqueTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OpaqueTokens() {
    }

    public static String generate() {                    // 43 caractères, sûrs dans une URL
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {            // 64 caractères hexadécimaux
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {   // toute JVM fournit SHA-256
            throw new IllegalStateException(impossible);
        }
    }
}
```

**Étape 6. Stocker le jeton dans une famille.** L'id de famille relie tous les jetons d'une connexion : un seul `UPDATE` les révoque.

```java
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;                  // jamais le jeton lui-même

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private UUID familyId;                     // une famille par connexion

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;
}
```

```java
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)      // deux rafraîchissements du même jeton : le second attend
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
```

**Étape 7. Faire tourner le jeton, et révoquer la famille s'il revient.** La révocation doit survivre à l'exception qui répond 401, d'où `noRollbackFor`.

```java
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    public record Rotation(UUID accountId, String refreshToken) {
    }

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    @Transactional
    public String issue(UUID accountId) {                 // à la connexion : une nouvelle famille
        return create(accountId, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)   // garder la révocation
    public Rotation rotate(String presented) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByTokenHash(OpaqueTokens.hash(presented))
                .orElseThrow(InvalidRefreshTokenException::new);

        if (current.getRevokedAt() != null) {           // déjà utilisé : il a fuité
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw new InvalidRefreshTokenException();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException();
        }
        current.setRevokedAt(now);
        return new Rotation(current.getAccountId(),
                create(current.getAccountId(), current.getFamilyId()));
    }

    private String create(UUID accountId, UUID familyId) {
        String value = OpaqueTokens.generate();
        RefreshToken token = new RefreshToken();
        token.setTokenHash(OpaqueTokens.hash(value));
        token.setAccountId(accountId);
        token.setFamilyId(familyId);
        token.setExpiresAt(clock.instant().plus(Duration.ofDays(30)));
        refreshTokens.save(token);
        return value;
    }
}
```

`InvalidRefreshTokenException` est une `RuntimeException` du projet, convertie en 401 (guide 05). Le dépôt refuse aussi un compte désactivé ou supprimé à la rotation, et révoque alors la famille.

**Étape 8. Vérifier.** Le test n'est pas transactionnel, donc chaque appel valide ; la dernière assertion prouve que la révocation a survécu à l'exception.

```java
@SpringBootTest
class RefreshTokenRotationTests {

    @Autowired RefreshTokenService refreshTokenService;

    @Test
    void replayingAUsedTokenRevokesTheWholeFamily() {
        String first = refreshTokenService.issue(UUID.randomUUID());
        String second = refreshTokenService.rotate(first).refreshToken();

        assertThatThrownBy(() -> refreshTokenService.rotate(first))
                .isInstanceOf(InvalidRefreshTokenException.class);
        // sans noRollbackFor, la révocation serait annulée et second marcherait encore
        assertThatThrownBy(() -> refreshTokenService.rotate(second))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
```

### Référence rapide

Les classes de Spring Security :

| Classe ou méthode | Rôle |
|---|---|
| `DelegatingPasswordEncoder(id, encoders)` | hache avec `id`, vérifie selon le préfixe `{id}` du hachage |
| `Argon2PasswordEncoder(sel, hachage, fils, mémoire en Kio, passes)` | Argon2id, avec Bouncy Castle |
| `BCryptPasswordEncoder()` | BCrypt |
| `PasswordEncoderFactories.createDelegatingPasswordEncoder()` | encodeur prêt : défaut `bcrypt`, accepte `noop` |
| `upgradeEncoding(hash)` | vrai si le hachage doit être refait |
| `UserDetailsPasswordService.updatePassword` | enregistre le hachage modernisé |
| `setDefaultPasswordEncoderForMatches(encoder)` | vérifie les hachages sans préfixe `{id}` |

Limites de cette version : un jeton d'accès émis avant une révocation reste valable jusqu'à son expiration, et les jetons de rafraîchissement expirés restent dans la table (ici, jusqu'à l'effacement du compte).

## Concepts avancés

### Sous le capot : pourquoi `noRollbackFor` deux fois

Deux méthodes transactionnelles sont sur le chemin de l'exception :

```
  AuthController.refresh
        |
        v
  AuthService.refresh          @Transactional(noRollbackFor = Invalid...)
        |  (transaction ouverte)
        v
  RefreshTokenService.rotate   @Transactional(noRollbackFor = Invalid...)
        |  (rejoint la même transaction)
        +-- revokeFamily(...)
        +-- throw InvalidRefreshTokenException
        |
        v
  sans la règle ici    --> transaction partagée "rollback only"
  sans la règle là-haut --> l'exception qui la traverse l'annule
  avec les deux        --> commit de la révocation, puis 401
```

Une méthode qui rejoint une transaction existante et lève une exception de rollback marque la transaction partagée « rollback only », et la méthode extérieure ne peut plus que l'annuler. Chaque méthode transactionnelle sur le chemin de l'exception a donc besoin de la règle (guide 14).

### Deux rafraîchissements en même temps

`findByTokenHash` prend un verrou de ligne (`SELECT ... FOR UPDATE`). Avec deux rafraîchissements simultanés du même jeton, le second attend la validation du premier. Il lit alors le jeton révoqué et le traite comme un rejeu, donc la famille est révoquée. Un client doit sérialiser ses rafraîchissements : un onglet rafraîchit, les autres attendent.

### Les jetons à usage unique

La réinitialisation de mot de passe suit le même principe, avec une colonne `used_at` :

```
  POST /auth/password-reset/request {email}
        |
        +-- compte inconnu ou désactivé --> rien
        +-- compte actif --> anciens jetons non utilisés supprimés,
        |                    nouveau jeton (1 h), e-mail après commit
        v
  202, dans tous les cas

  POST /auth/password-reset/confirm {token, newPassword}
        |
        +-- jeton inconnu, utilisé ou expiré --> 400 invalid-token
        v
  jeton marqué utilisé, mot de passe haché, e-mail marqué vérifié,
  autres jetons supprimés, tous les jetons de rafraîchissement révoqués
```

- La génération et le hachage sont ceux des jetons de rafraîchissement (`OpaqueTokens`). Émettre un nouveau jeton supprime les jetons non utilisés.
- La demande répond 202 que l'e-mail existe ou non, donc l'endpoint ne révèle aucun compte. Sa limite de débit compte tous les e-mails (guide 10).
- Suivre le lien reçu par e-mail prouve la possession de l'adresse, d'où l'e-mail marqué vérifié.
- La vérification d'e-mail suit le même schéma avec un jeton de 24 h, émis à l'inscription et par `POST /auth/verify-email/resend` (qui répond 409 `email-already-verified` une fois l'e-mail vérifié).

### Plusieurs instances

Hachages et jetons vivent dans la base. N'importe quelle instance vérifie n'importe quel mot de passe et fait tourner n'importe quel jeton. Le verrou de ligne sérialise aussi les rafraîchissements concurrents entre instances.

## Pièges à éviter

### Mots de passe

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Garder `PasswordEncoderFactories` en production | un hachage `{noop}secret` est accepté, en clair | Construire la table des encodeurs sans `noop` |
| Importer des hachages BCrypt sans préfixe | la vérification lève une exception : le hachage n'a pas de préfixe `{id}` | Préfixer `{bcrypt}` |
| Garder les paramètres par défaut d'Argon2 | 16 Mio, sous le minimum de l'OWASP | Écrire 19 Mio, 2 passes, 1 fil |
| Oublier `setUserDetailsPasswordService` | les anciens hachages restent pour toujours | Brancher le service sur le `DaoAuthenticationProvider` |

### Jetons

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Stocker le jeton en clair | une fuite de la base donne des sessions utilisables | Ne stocker que son SHA-256 |
| Hacher le jeton avec Argon2 ou BCrypt | le sel interdit de retrouver la ligne par sa valeur | SHA-256 suffit pour 256 bits aléatoires |
| Oublier `noRollbackFor` sur une méthode du chemin | la révocation est annulée avec l'exception | Le poser sur chaque méthode traversée |
| Lire le jeton sans verrou | deux rafraîchissements simultanés font deux successeurs | `@Lock(PESSIMISTIC_WRITE)` sur la recherche |
| Révoquer par une requête JPQL qui joint l'utilisateur | `@SoftDelete` filtre l'utilisateur : rien n'est révoqué | SQL natif sur la clé étrangère |
| Répondre 404 à une demande de réinitialisation inconnue | on apprend quels e-mails ont un compte | Répondre 202 dans tous les cas |

## En résumé

**À retenir :** des mots de passe en Argon2id derrière un `DelegatingPasswordEncoder` qui modernise les anciens hachages à la connexion, et des jetons opaques de 256 bits dont on ne stocke que le SHA-256 : à usage unique pour les liens envoyés par e-mail, en rotation par famille pour le rafraîchissement.

- [ ] `DelegatingPasswordEncoder` : Argon2id par défaut, anciens algorithmes en vérification seule, pas de `noop`
- [ ] Paramètres Argon2id au moins au minimum de l'OWASP
- [ ] `UserDetailsPasswordService` branché sur le `DaoAuthenticationProvider`
- [ ] Jetons de 32 octets de `SecureRandom`, en Base64url
- [ ] Seul le SHA-256 du jeton en base, sur une colonne unique
- [ ] Rotation à chaque rafraîchissement, une famille par connexion
- [ ] Réutilisation d'un jeton révoqué : toute la famille révoquée, avec `noRollbackFor`
- [ ] Déconnexion, désactivation et suppression révoquent les familles
- [ ] Liens par e-mail à usage unique et courts ; demande de réinitialisation toujours en 202

## Dans ce dépôt

Le trajet du rafraîchissement :

```
  POST /api/v1/auth/refresh {refreshToken}   (public)
        |
        v
  AuthService.refresh                          transaction, noRollbackFor
        |
        v
  RefreshTokenService.rotate
        |
        +-- inconnu, expiré ----------------------> 401
        +-- déjà révoqué ----> famille révoquée --> 401
        +-- compte désactivé ou supprimé --> famille révoquée --> 401
        v
  ancien révoqué, successeur dans la famille, compte marqué actif
        |
        v
  200 : nouveau jeton d'accès (15 min) et de rafraîchissement (30 j)
```

| Fichier | Rôle |
|---|---|
| [`SecurityConfiguration`][security] | `passwordEncoder` (Argon2id, BCrypt) et le provider avec `setUserDetailsPasswordService` |
| [`DatabaseUserDetailsService`][details] | charge le compte, écrit le hachage modernisé |
| [`OpaqueTokens`][opaque] | 32 octets en Base64url, SHA-256 en hexadécimal |
| [`RefreshTokenService`][refresh] | émission, rotation, déconnexion, révocation de tous les jetons d'un compte |
| [`RefreshTokenRepository`][refresh-repo] | verrou, `revokeFamily` en JPQL, `revokeAllForUser` en SQL natif |
| [`AuthService`][auth] | connexion et rafraîchissement, `noRollbackFor` sur `refresh` |
| [`EmailVerificationService`][verification] | jeton de vérification, renvoi, validation |
| [`PasswordResetService`][reset] | demande silencieuse, confirmation |
| [`RefreshToken`][refresh-entity] | `token_hash` unique (`refresh_tokens_token_hashUQ`), index sur `user_id` et `family_id` |

| Réglage | Valeur | Rôle |
|---|---|---|
| `security.refresh-token.ttl` | `30d` | durée d'un jeton de rafraîchissement |
| `identity.email-verification.ttl` | `24h` | durée du lien de vérification |
| `identity.password-reset.ttl` | `1h` | durée du lien de réinitialisation |
| `identity.email-verification.verify-url` | `${EMAIL_VERIFICATION_URL:http://localhost:3000/verify-email}` | page du front ; obligatoire en `prod` |
| `identity.password-reset.reset-url` | `${PASSWORD_RESET_URL:http://localhost:3000/reset-password}` | idem pour la réinitialisation |

Ce qui est propre au dépôt :

- `{noop}` n'est pas enregistré : les tests qui stockent des utilisateurs directement hachent avec le bean `PasswordEncoder`.
- `revokeAllForUser` est en SQL natif exprès : quand les jetons pointaient vers `User`, le chemin JPQL `t.user.id` joignait `users`, que `@SoftDelete` filtre, et rien n'était révoqué pour un utilisateur supprimé.
- `UserService.disable`, `UserService.delete` et `PasswordResetService.confirm` appellent `revokeAllForUser`. Réactiver un compte ne rend pas les anciens jetons (test `disablingUserRevokesRefreshTokensEvenAfterReEnabling`).
- `rotate` recharge le compte avec `findById` (qui ignore les utilisateurs supprimés) et refuse un compte désactivé en révoquant la famille. Un compte désactivé hors du service ne peut donc pas rafraîchir non plus.
- `PasswordResetService.request` ignore les comptes inconnus ou désactivés. `confirm` verrouille la ligne du compte (`findByIdForUpdate`), marque l'e-mail vérifié si besoin et publie `AccountStateChanged` (éviction du cache, guide 17).
- Les tables sont `refresh_tokens`, `email_verification_tokens` et `password_reset_tokens`. Ces lignes ne sont supprimées qu'à l'effacement du compte (`UserRetentionQueries`). Aucun job ne purge les jetons de rafraîchissement expirés.

Les tests :

- [`AuthControllerTests`][auth-tests] : `loginUpgradesLegacyBcryptHashToArgon2id` stocke `"{bcrypt}" + new BCryptPasswordEncoder().encode("password")`, se connecte, puis vérifie le préfixe `{argon2id}`.
- [`RefreshTokenApiTests`][refresh-tests] : `refreshTokensAreStoredOnlyAsSha256Hashes`, `replayingUsedRefreshTokenRevokesWholeFamily`, `reuseDetectionDoesNotAffectOtherFamiliesOfSameUser`, `deletingUserRevokesRefreshTokens`, `logoutRevokesWholeFamily`, `refreshIgnoresAnExpiredAccessTokenLeftInTheAuthorizationHeader`.
- `PasswordResetApiTests`, `EmailVerificationApiTests` et les tests unitaires `OpaqueTokensTest`, `RefreshTokenServiceTest`, `PasswordResetServiceTest`, `DatabaseUserDetailsServiceTest`.

Pour ajouter un type de jeton par e-mail : une table avec `token_hash` (unique), `expires_at` et `used_at` ; `OpaqueTokens.generate` et `hash` ; une recherche par empreinte avec verrou, un usage unique, les jetons non utilisés supprimés à l'émission d'un nouveau ; l'e-mail envoyé par un listener après le commit (guide 23).

## Voir aussi

- [07. Chaîne de filtres et JWT](07-securite-jwt.md) : connexion, jeton d'accès, endpoints publics.
- [10. Rate limiting](10-rate-limiting.md) : limites sur la connexion et la réinitialisation.
- [14. Transactions](14-transactions.md) : propagation et règles de rollback.
- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : `@SoftDelete` et pourquoi la révocation est en SQL natif.
- Documentation officielle : [stockage des mots de passe (Spring Security)](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html), [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

[Sommaire](README.md) | [< 08. Autorisations](08-autorisations.md) | [10. Rate limiting >](10-rate-limiting.md)

[security]: ../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[details]: ../../src/main/java/io/julienmetral/tasks/identity/services/DatabaseUserDetailsService.java
[opaque]: ../../src/main/java/io/julienmetral/tasks/identity/security/OpaqueTokens.java
[refresh]: ../../src/main/java/io/julienmetral/tasks/identity/services/RefreshTokenService.java
[refresh-repo]: ../../src/main/java/io/julienmetral/tasks/identity/repositories/RefreshTokenRepository.java
[auth]: ../../src/main/java/io/julienmetral/tasks/identity/services/AuthService.java
[verification]: ../../src/main/java/io/julienmetral/tasks/identity/services/EmailVerificationService.java
[reset]: ../../src/main/java/io/julienmetral/tasks/identity/services/PasswordResetService.java
[refresh-entity]: ../../src/main/java/io/julienmetral/tasks/identity/entities/RefreshToken.java
[auth-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/AuthControllerTests.java
[refresh-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/RefreshTokenApiTests.java
