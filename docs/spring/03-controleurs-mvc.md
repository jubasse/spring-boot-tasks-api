# 03. Contrôleurs MVC

[Sommaire](README.md) | [< 02. Profil de production](02-profil-production.md) | [04. Versioning d'API >](04-versioning-api.md)

## Contexte

- **Problème** : exposer des données en JSON sans exposer les entités ni lancer de requêtes cachées pendant l'écriture de la réponse, avec des statuts et une pagination fiables.
- **Quand l'utiliser** : toute API REST servie par Spring MVC (le modèle servlet, un thread par requête).
- **Quand s'en passer** : une application réactive (Spring WebFlux) ; des événements poussés au client (guide 24).
- **Dans ce dépôt** : 11 contrôleurs sous `/api/v1` : tâches, commentaires, pièces jointes, historique, comptes, exports, webhooks, identicônes.
- **Prérequis** : guides 11 (Spring Data JPA) et 14 (transactions). Versions : Spring Boot 4.1, Spring Framework 7, Spring Data Commons 4.1, Jackson 3.

Où transformer l'entité en DTO :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Le contrôleur convertit | le service renvoie l'entité chargée, le contrôleur construit le record | cas courant ; choix de ce dépôt |
| Le service renvoie des DTO | la conversion a lieu dans la transaction | lectures complexes, services appelés seulement par l'API |
| MapStruct | un convertisseur généré à la compilation | beaucoup de champs copiés à l'identique |
| Renvoyer l'entité | Jackson sérialise l'entité JPA | à éviter : champs internes, chargements cachés, contrat lié aux tables |

## Concepts clés

### Vocabulaire

- **Contrôleur** : une classe `@RestController` dont chaque méthode répond à un endpoint.
- **DTO** (Data Transfer Object) : un objet qui ne sert qu'à transporter des données entre l'API et le client ; ici, un record.
- **Open-in-view** : un réglage qui garde la session Hibernate ouverte jusqu'à la fin de l'écriture de la réponse.
- **Association paresseuse** (LAZY) : une association chargée seulement quand on la lit, par une requête de plus.
- **Multipart** : le format d'un formulaire qui transporte des champs et des fichiers dans une seule requête.
- **ETag** : une étiquette de version d'une réponse, que le client renvoie pour savoir si sa copie est à jour.

### Le trajet d'une lecture

Le service ouvre la transaction, charge tout ce que la réponse lira, puis la valide. Le contrôleur convertit ensuite l'entité, hors transaction :

```
  Contrôleur              Service (@Transactional)          Base
      |                            |                          |
      |---- findById(id) --------->|                          |
      |                            |---- SELECT ... JOIN ---->|
      |                            |<--- facture et client ---|
      |                            |  commit, session fermée  |
      |<--- entité chargée --------|                          |
      |
      |  new InvoiceResponseDto(invoice)
      v
  JSON écrit par Jackson
```

- Le service reste réutilisable par un job ou un listener, sans HTTP.
- Le DTO ne lit que ce que le service a chargé : une association oubliée se voit tout de suite, à condition de couper open-in-view.

Spring Boot active open-in-view par défaut (avec un avertissement au démarrage). Le réglage décide de ce qui arrive quand le DTO lit une association non chargée :

```
 open-in-view: true (défaut)

  requête ---------------------------------------------------> réponse écrite
  |<------------------- session Hibernate ouverte ------------------->|
         |<-- transaction du service -->|
                                          le DTO lit customer --> SELECT
                                          (une requête par ligne de la page)

 open-in-view: false

  requête ---------------------------------------------------> réponse écrite
         |<-- transaction et session -->|
                                          le DTO lit customer non chargé
                                          --> LazyInitializationException
```

- Activé, il cache le problème N+1 : une page de 20 factures lance 20 requêtes de plus, hors transaction, sans erreur.
- Coupé, l'erreur part en test dès la première lecture oubliée. On la corrige dans le service, avec un `@EntityGraph` (guide 11).

### Les réponses : statut et en-têtes

Chaque opération a son code de succès, et certains codes demandent un en-tête :

| Code | Quand | En-tête | Corps |
|---|---|---|---|
| 200 OK | lecture, modification, action | | la ressource |
| 201 Created | création | `Location` : l'URL de la ressource créée | la ressource |
| 202 Accepted | travail lancé en arrière-plan | `Location` : où suivre le travail | son état |
| 204 No Content | suppression, action sans résultat | | aucun |
| 304 Not Modified | la copie du client est à jour | `ETag` | aucun |

Une action métier (archiver, annuler) est un `POST` sur une sous-ressource (`/tasks/{id}/archive`) ; une modification partielle est un `PATCH`.

### La pagination

Spring Data lit `page`, `size` et `sort` dans l'URL et les passe à la méthode sous la forme d'un `Pageable`. Ce qui part au client, en revanche, dépend de ce que la méthode renvoie :

| Type renvoyé | JSON produit | Stabilité |
|---|---|---|
| `Page<T>` | tous les champs de `PageImpl`, dont `pageable` et `sort` | aucune garantie, et Spring Data log un avertissement |
| `PagedModel<T>` | `content`, puis `page` : `size`, `number`, `totalElements`, `totalPages` | structure fixe, faite pour être exposée |

Les bornes s'appliquent sans erreur : une taille au-delà de `max-page-size` est ramenée à ce maximum, une valeur illisible ou négative est remplacée. Le client lit la taille utilisée dans `page.size`.

### Le cache HTTP

Une ressource qui ne change jamais peut rester dans le cache du navigateur. L'`ETag` sert quand le navigateur redemande quand même :

```
  Navigateur                                       API
      |                                             |
      |---- GET /invoices/42/pdf ------------------>|
      |<--- 200, ETag: "42",                        |
      |     Cache-Control: max-age=31536000, ...  --|
      |                                             |
      |  pendant un an : servi depuis le cache, sans appel
      |                                             |
      |---- GET, If-None-Match: "42" -------------->|  après max-age
      |<--- 304 Not Modified, sans corps -----------|
```

- **`max-age`** : la durée pendant laquelle la copie est utilisable sans demander.
- **`immutable`** : le navigateur ne revalide pas pendant `max-age`, même quand l'utilisateur recharge la page.
- **`public`** ou **`private`** : `public` autorise les caches partagés (reverse proxies, CDN) ; `private`, le navigateur seul.

## Implémentation avec Spring

Ce qu'on va créer :

```
src/main/java/.../
  invoice/
    Invoice.java, Customer.java       les entités (étape 1)
    CreateInvoiceDto.java             la requête, un record (étape 1)
    InvoiceResponseDto.java           la réponse, un record (étape 1)
    InvoiceRepository.java            les lectures et leurs jointures (étape 2)
    InvoiceService.java               les transactions (étape 2)
    InvoiceController.java            les endpoints (étapes 5, 6, 8, 10)
  security/
    CurrentUser.java                  l'appelant, lu dans le jeton (étape 4)
src/main/resources/
  application.yaml                    open-in-view, pages, uploads (étapes 3, 9)
src/test/java/.../
  InvoiceControllerTests.java         la vérification (étapes 7, 11)
```

### Un CRUD paginé

**Étape 1. Écrire les entités et les DTO.** Le DTO de réponse se construit depuis l'entité par un second constructeur.

```java
@Entity
@Getter
@Setter
public class Invoice {              // mapping JPA : guide 12

    @Id
    @GeneratedValue
    private UUID id;

    private String number;
    private BigDecimal amount;
    private UUID createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    private Customer customer;
}

@Entity
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue
    private UUID id;

    private String name;
}
```

```java
public record CreateInvoiceDto(
        @NotBlank @Size(max = 30) String number,        // validation : guide 05
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID customerId
) {
}

public record InvoiceResponseDto(UUID id, String number, BigDecimal amount, String customerName) {

    public InvoiceResponseDto(Invoice invoice) {
        this(invoice.getId(), invoice.getNumber(), invoice.getAmount(), invoice.getCustomer().getName());
    }
}
```

**Étape 2. Charger dans le service ce que le DTO lira.** Le DTO lit `customer` : le repository le charge dans la même requête.

```java
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = "customer")   // guide 11
    Page<Invoice> findAll(Pageable pageable);

    @EntityGraph(type = EntityGraphType.LOAD, attributePaths = "customer")
    Optional<Invoice> findById(UUID id);
}
```

```java
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;     // un JpaRepository<Customer, UUID>

    @Transactional
    public Invoice create(CreateInvoiceDto dto, UUID creatorId, List<MultipartFile> files) {
        Invoice invoice = new Invoice();
        invoice.setNumber(dto.number());
        invoice.setAmount(dto.amount());
        invoice.setCreatedBy(creatorId);
        invoice.setCustomer(customerRepository.findById(dto.customerId())   // chargé, pas un proxy
                .orElseThrow(() -> new CustomerNotFoundException(dto.customerId())));   // 404 : guide 05
        // files : étape 8 ; leur stockage : guide 28
        return invoiceRepository.save(invoice);
    }

    @Transactional(readOnly = true)
    public Page<Invoice> findAll(Pageable pageable) {
        return invoiceRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Invoice findById(UUID id) {
        return invoiceRepository.findById(id).orElseThrow(() -> new InvoiceNotFoundException(id));
    }

    @Transactional
    public void delete(UUID id) {
        invoiceRepository.delete(findById(id));
    }
}
```

**Étape 3. Couper open-in-view et borner les pages.** La session se ferme avec la transaction, et aucun client ne demande 2000 lignes d'un coup.

```yaml
spring:
  jpa:
    open-in-view: false          # une association non chargée échoue au lieu de lancer sa requête
  data:
    web:
      pageable:
        max-page-size: 100       # 2000 par défaut ; une taille plus grande est ramenée à 100
```

**Étape 4. Lire l'appelant depuis le jeton.** Un bean lit l'identifiant dans l'authentification courante ; le contrôleur et les services le partagent.

```java
@Component
public class CurrentUser {

    public Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return Optional.empty();                            // appel anonyme
        }
        return Optional.ofNullable(jwt.getToken().getClaimAsString("uid")).map(UUID::fromString);
    }
}
```

**Étape 5. Écrire le contrôleur.** Le chemin est relatif à l'API ; chaque méthode appelle le service, puis construit la réponse.

```java
@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final CurrentUser currentUser;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)                 // pour la documentation (guide 06)
    public ResponseEntity<InvoiceResponseDto> createInvoice(@Valid @RequestBody CreateInvoiceDto dto) {
        return created(invoiceService.create(dto, callerId(), List.of()));
    }

    @GetMapping("/{id}")
    public InvoiceResponseDto getInvoice(@PathVariable UUID id) {
        return new InvoiceResponseDto(invoiceService.findById(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteInvoice(@PathVariable UUID id) {
        invoiceService.delete(id);
    }

    private ResponseEntity<InvoiceResponseDto> created(Invoice invoice) {
        return ResponseEntity
                .created(URI.create("/invoices/" + invoice.getId()))   // statut 201 et en-tête Location
                .body(new InvoiceResponseDto(invoice));
    }

    private UUID callerId() {
        return currentUser.id().orElseThrow(() -> new AccessDeniedException("No user id in the token"));
    }
}
```

**Étape 6. Paginer la liste.** Le `Pageable` arrive tout fait ; la méthode renvoie un `PagedModel`, jamais le `Page` lui-même.

```java
// dans InvoiceController
@GetMapping
public PagedModel<InvoiceResponseDto> listInvoices(
        @ParameterObject @PageableDefault(size = 20, sort = "number") Pageable pageable) {
    return new PagedModel<>(invoiceService.findAll(pageable).map(InvoiceResponseDto::new));
}
```

`GET /invoices?page=0&size=2&sort=amount,desc` répond alors :

```json
{
  "content": [
    { "id": "0199a3c2-...", "number": "F-17", "amount": 900.00, "customerName": "ACME" },
    { "id": "0199a3b8-...", "number": "F-12", "amount": 450.00, "customerName": "Initech" }
  ],
  "page": { "size": 2, "number": 0, "totalElements": 31, "totalPages": 16 }
}
```

**Étape 7. Vérifier.** Un test de la couche web, le service simulé.

```java
@WebMvcTest(InvoiceController.class)
@Import({SecurityConfiguration.class, CurrentUser.class})   // la chaîne de filtres JWT (guide 07)
class InvoiceControllerTests {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InvoiceService invoiceService;

    @Test
    void creatingAnInvoiceAnswers201WithItsLocation() throws Exception {
        Invoice invoice = invoice();
        when(invoiceService.create(any(), any(), anyList())).thenReturn(invoice);

        mockMvc.perform(post("/invoices").with(caller())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"number": "F-1", "amount": 120.00, "customerId": "%s"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/invoices/" + invoice.getId()))
                .andExpect(jsonPath("$.customerName").value("ACME"));
    }

    @Test
    void pageSizeAboveTheMaximumIsClamped() throws Exception {
        when(invoiceService.findAll(any())).thenAnswer(call -> Page.empty(call.getArgument(0)));

        mockMvc.perform(get("/invoices").param("size", "500").with(caller()))
                .andExpect(jsonPath("$.page.size").value(100));
    }
```

```java
    private static RequestPostProcessor caller() {
        return jwt().jwt(token -> token.claim("uid", UUID.randomUUID().toString()));
    }

    private static Invoice invoice() {
        Customer customer = new Customer();
        customer.setName("ACME");
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setNumber("F-1");
        invoice.setCustomer(customer);
        return invoice;
    }
}
```

### Fichiers et cache HTTP

**Étape 8. Accepter un formulaire avec des fichiers.** Une seconde méthode sur la même URL, choisie par `consumes` : les champs remplissent le même record par `@ModelAttribute`, les fichiers arrivent par `@RequestPart`.

```java
// dans InvoiceController
@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
@ResponseStatus(HttpStatus.CREATED)
public ResponseEntity<InvoiceResponseDto> createInvoiceWithFiles(
        @Valid @ModelAttribute CreateInvoiceDto dto,
        @RequestPart(name = "files", required = false) List<MultipartFile> files) {
    return created(invoiceService.create(dto, callerId(), files == null ? List.of() : files));
}
```

Sans fichier, `files` vaut `null` et non une liste vide.

**Étape 9. Régler les limites d'upload.** Les valeurs par défaut (1 Mo par fichier, 10 Mo par requête) refusent le moindre PDF scanné.

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 25MB         # le plus gros fichier accepté par l'application
      max-request-size: 26MB      # tous les fichiers et champs d'une requête
```

Au-delà, Spring lève `MaxUploadSizeExceededException`, un 413 (guide 05). Une limite par usage se vérifie dans le service (guide 28).

**Étape 10. Servir une ressource immuable avec un cache HTTP.** Une facture émise ne change plus : son PDF peut rester un an chez le client, en cache privé puisqu'il appartient à un compte.

```java
// dans InvoiceController
@GetMapping(path = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
public ResponseEntity<byte[]> getInvoicePdf(@PathVariable UUID id) {
    return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .eTag(id.toString())                       // Spring ajoute les guillemets
            .body(invoiceService.pdf(id));             // byte[] : le PDF de la facture émise
}
```

Quand la requête porte `If-None-Match` avec cet `ETag`, Spring répond 304 sans corps.

**Étape 11. Vérifier.** Un formulaire multipart passe ses fichiers au service, et un `ETag` connu reçoit un 304.

```java
// dans InvoiceControllerTests
@Test
void aFormCarriesItsFieldsAndItsFiles() throws Exception {
    when(invoiceService.create(any(), any(), anyList())).thenReturn(invoice());

    mockMvc.perform(multipart("/invoices")
                    .file(new MockMultipartFile("files", "order.pdf", "application/pdf", new byte[] {1}))
                    .param("number", "F-1")
                    .param("amount", "120.00")
                    .param("customerId", UUID.randomUUID().toString())
                    .with(caller()))
            .andExpect(status().isCreated());

    verify(invoiceService).create(any(), any(), argThat(files -> files.size() == 1));
}

@Test
void aKnownEtagIsAnsweredNotModified() throws Exception {
    UUID id = UUID.randomUUID();
    when(invoiceService.pdf(id)).thenReturn(new byte[] {1});

    mockMvc.perform(get("/invoices/{id}/pdf", id).with(caller())
                    .header(HttpHeaders.IF_NONE_MATCH, "\"" + id + "\""))
            .andExpect(status().isNotModified());
}
```

### Référence rapide

Ce qu'une méthode de contrôleur peut recevoir :

| Paramètre | Source | Remarque |
|---|---|---|
| `@PathVariable UUID id` | un segment du chemin | converti ; une valeur illisible donne un 400 |
| `@RequestParam(required = false) Status status` | la query string | `null` si absent, ou `defaultValue` |
| `@Valid @RequestBody Dto dto` | le corps JSON | lu par Jackson 3 |
| `@Valid @ModelAttribute Dto dto` | les champs d'un formulaire | un record est rempli par son constructeur |
| `@RequestPart("file") MultipartFile file` | une part d'un multipart | `List<MultipartFile>` pour plusieurs |
| `Pageable` | `page`, `size`, `sort` | défauts par `@PageableDefault` |
| `JwtAuthenticationToken` | l'appelant authentifié | `null` pour un appel anonyme |

Les réponses de `ResponseEntity` :

| Appel | Statut | Usage |
|---|---|---|
| `ok(body)` | 200 | lecture, modification |
| `created(uri).body(body)` | 201 | création, avec `Location` |
| `accepted().location(uri).body(body)` | 202 | travail en arrière-plan |
| `noContent().build()` | 204 | suppression |

Les réglages :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.jpa.open-in-view` | `true`, avec un avertissement | session ouverte jusqu'à la fin de la réponse |
| `spring.data.web.pageable.max-page-size` | `2000` | taille maximale d'une page |
| `spring.data.web.pageable.serialization-mode` | `direct` | `via-dto` convertit tout `Page` renvoyé en `PagedModel` |
| `spring.servlet.multipart.max-file-size` | `1MB` | taille maximale d'un fichier |
| `spring.servlet.multipart.max-request-size` | `10MB` | taille maximale d'une requête multipart |

Limites de cette version : le contrat des erreurs (guide 05), la version dans l'URL (guide 04) et la documentation (guide 06) restent à ajouter.

## Concepts avancés

### Sous le capot : de la requête à la méthode, puis au JSON

Le `DispatcherServlet` choisit la méthode, prépare chaque argument, puis écrit ce qu'elle renvoie :

```
 requête HTTP
      |
      v
 DispatcherServlet     choisit la méthode : chemin, verbe, consumes, produces
      |
      v
 résolveurs d'arguments
      |   @PathVariable, @RequestParam  --> conversion de type
      |   @RequestBody                  --> JacksonJsonHttpMessageConverter
      |   @RequestPart, @ModelAttribute --> parts et champs du multipart
      |   Pageable                      --> résolveur de Spring Data
      |   JwtAuthenticationToken        --> request.getUserPrincipal()
      v
 méthode du contrôleur
      |
      v
 @ResponseStatus appliqué, puis le statut du ResponseEntity (il l'emporte)
      |
      v
 GET ou HEAD, statut 200, ETag connu ? -- oui --> 304 sans corps
      | non
      v
 corps écrit par Jackson
```

- `@ResponseStatus` et `ResponseEntity` peuvent cohabiter : le second fixe le statut en dernier. Le premier sert alors à la documentation.
- Le 304 automatique ne vaut que pour un `ResponseEntity` en 200, sur `GET` ou `HEAD`.
- Spring trouve le nom d'un paramètre (`id`) grâce à l'option `-parameters` du compilateur, que le parent Maven de Spring Boot active.

### Le 304 sans calculer le corps

À l'étape 10, le PDF est produit avant la comparaison de l'`ETag`. Quand le corps coûte cher, on compare d'abord :

```java
@GetMapping(path = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
public ResponseEntity<byte[]> getInvoicePdf(@PathVariable UUID id, WebRequest request) {
    if (request.checkNotModified(id.toString())) {
        return null;                                // Spring a déjà préparé le 304
    }
    return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
            .eTag(id.toString())
            .body(invoiceService.pdf(id));
}
```

Le filtre `ShallowEtagHeaderFilter` fait l'inverse : il calcule un `ETag` depuis le corps déjà produit. Il économise la bande passante, pas le travail du serveur.

### Spring Security et les en-têtes de cache

Spring Security ajoute à chaque réponse `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, avec `Pragma` et `Expires`. Il s'abstient quand la réponse porte déjà un de ces en-têtes : le `cacheControl(...)` du contrôleur l'emporte, endpoint par endpoint.

### Plusieurs instances

Un contrôleur sans état tourne à l'identique sur chaque instance. Deux points demandent de l'attention :

- **L'`ETag`** se calcule depuis le contenu, l'id ou la version de la ligne, jamais depuis un état propre à l'instance : sinon une requête qui change d'instance ne reçoit jamais de 304.
- **Une URL absolue** construite depuis la requête (`ServletUriComponentsBuilder`) reprend le schéma et l'hôte vus par l'application : derrière un reverse proxy, souvent `http` et une adresse interne, sauf si `server.forward-headers-strategy` est réglé (guide 10). Un `Location` relatif évite la question.

### Surveiller

- Micrometer mesure chaque requête dans `http.server.requests`, avec le modèle de l'URL (pas l'id réel), le statut et le nom de l'exception (guide 32).
- Le message `Serializing PageImpl instances as-is is not supported` dans les logs signale un `Page` renvoyé tel quel.

## Pièges à éviter

### Réponses et DTO

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Renvoyer une entité JPA | Jackson sérialise tout : champs internes, associations, boucles | Renvoyer un record construit depuis l'entité |
| Laisser open-in-view actif | chaque association lue pendant l'écriture du JSON lance sa requête | Mettre `spring.jpa.open-in-view: false` |
| Renvoyer une référence posée par `getReferenceById` | le DTO lit un proxy vide après la transaction | Charger l'entité avant de la renvoyer |
| Ajouter `reason` à `@ResponseStatus` | Spring répond par `sendError` et ignore le corps renvoyé | Garder `@ResponseStatus` sans `reason` |

### Pagination

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Renvoyer un `Page` | JSON sans structure garantie, et un avertissement dans les logs | Renvoyer `new PagedModel<>(page)` |
| Poser `@PageableDefault` sans `size` | la taille par défaut devient 10, pas `default-page-size` | Écrire `size` quand 10 ne convient pas |
| Laisser trier par une propriété inconnue | `PropertyReferenceException`, donc un 500 | La transformer en 400 (guide 05) |
| Trier sur une colonne non unique | deux lignes à égalité changent de page d'un appel à l'autre | Ajouter l'id en second critère de tri |

### Appelant et fichiers

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Prendre l'id de l'appelant dans le corps | un client agit au nom d'un autre | Le lire dans le jeton |
| Déclarer `JwtAuthenticationToken` sur un endpoint public | le paramètre vaut `null` pour un appel anonyme | Passer par un bean qui rend un `Optional` |
| Lire un formulaire avec `@RequestBody` | aucun convertisseur ne lit ce format : 415 | Utiliser `@ModelAttribute` et `@RequestPart` |

### Cache HTTP et plusieurs instances

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Poser `immutable` sur une ressource qui change | les navigateurs gardent l'ancienne version jusqu'à `max-age` | Changer d'URL quand le contenu change |
| Mettre `public` sur une réponse propre à un compte | un cache partagé la sert à d'autres utilisateurs | Mettre `private` |
| Calculer l'`ETag` depuis l'état de l'instance | chaque instance donne le sien : jamais de 304 | Le dériver du contenu, de l'id ou de la version |

## En résumé

**À retenir :** le service charge et renvoie l'entité dans sa transaction, le contrôleur la transforme en record et choisit le statut ; open-in-view coupé fait échouer en test toute lecture oubliée, et `PagedModel` donne aux listes une forme stable.

- [ ] Chemins relatifs dans `@RequestMapping`, la version ajoutée ailleurs (guide 04)
- [ ] Records pour les requêtes et les réponses, réponse construite depuis l'entité
- [ ] Services transactionnels qui renvoient des entités chargées
- [ ] `spring.jpa.open-in-view: false`
- [ ] Listes en `PagedModel`, `@PageableDefault` explicite, `max-page-size` réduit
- [ ] 201 avec `Location`, 202 pour un travail en arrière-plan, 204 sans corps
- [ ] Appelant lu dans le jeton, jamais dans la requête
- [ ] Limites multipart alignées sur le plus gros fichier accepté
- [ ] `Cache-Control` et `ETag` sur les ressources immuables, `private` si elles appartiennent à un compte
- [ ] Un test de la couche web par contrôleur, avec le service simulé

## Dans ce dépôt

Le trajet de `GET /api/v1/tasks?size=500` :

```
  filtres de sécurité                  jeton, compte actif (guides 07, 08)
       |
       v
  TaskController.listTasks             size ramené à 100, tri createdAt DESC
       |
       v
  TaskService.findAll                  transaction en lecture seule
       |
       v
  TaskRepository.findAll(spec, page)   @EntityGraph : tâche, comptes, photos
       |
       v
  commit, session fermée
       |
       v
  new TaskResponseDto(task, mediaUrls) lit seulement ce qui est chargé
       |
       v
  new PagedModel<>(...)                {content, page}, écrit par Jackson
```

| Fichier | Rôle |
|---|---|
| [`TaskController`][task-controller] | CRUD des tâches, liste paginée et filtrée, actions en `POST` |
| [`TaskCommentController`][comment-controller] | commentaire en JSON ou en multipart sur la même URL |
| [`DataExportController`][export-controller] | 202 avec `Location`, appelant lu par `CurrentUser` |
| [`UserController`][user-controller] | inscription en 201, photo de profil en multipart et en 202 |
| [`IdenticonController`][identicon-controller] | image publique, `Cache-Control` d'un an, `ETag` |
| [`CurrentUser`][current-user] | l'id du claim `uid`, en `Optional` |
| [`TaskResponseDto`][task-dto], [`UserProfileResponseDto`][profile-dto] | records construits depuis l'entité ; le compte tel que les autres le voient |
| [`ProfilesForDisplay`][profiles] | charge un profil et sa photo avant la fin de la transaction |
| [`TaskRepository`][task-repository] | les `@EntityGraph` qui nourrissent les réponses |

Les réglages, dans `application.yaml` :

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.jpa.open-in-view` | `false` | la session se ferme avec la transaction du service |
| `spring.data.web.pageable.max-page-size` | `100` | taille maximale d'une page |
| `spring.jpa.properties.hibernate.default_batch_fetch_size` | `50` | collections d'une page chargées par lots (guide 11) |
| `spring.servlet.multipart.max-file-size` | `25MB` | égal à `media.attachment-max-size`, la plus grosse limite par usage |
| `spring.servlet.multipart.max-request-size` | `26MB` | tous les fichiers d'une requête, champs compris |

Ce qui est propre au dépôt :

- Les constructeurs des DTO reçoivent [`MediaUrls`][media-urls], qui calcule l'URL de chaque photo de profil. MapStruct est dans le `pom.xml`, mais rien ne l'utilise.
- Les `Location` sont des chemins relatifs, préfixe compris (`/api/v1/tasks/...`). Les liens rendus dans les corps passent par `ApiVersioningConfiguration.CURRENT_PATH`, et `IdenticonController.urlOf` les rend absolus depuis la requête courante.
- `@PageableDefault` ne donne pas de taille : une page fait 10 éléments par défaut. Tris : tâches et exports par `createdAt` décroissant, commentaires par `createdAt` croissant, historique et livraisons de webhooks par date puis `id`, décroissants (l'id départage deux lignes créées au même instant).
- Un tri sur une propriété inconnue devient une `validation-error` sur le paramètre `sort` (guide 05). Un commentaire porte 5 fichiers au plus, vérifié par le service : au-delà, une erreur sur le paramètre `files`.
- La photo de profil répond 202 sans `Location` : le traitement se suit sur le compte lui-même (`avatarPending`).
- Les variables de chemin s'appellent `id`, parce que les annotations de sécurité lisent `#id` (guide 08).
- Chaque 201, 202 et 204 porte `@ResponseStatus` en plus du `ResponseEntity`, pour la documentation (guide 06).
- [`NotificationStreamController`][stream-controller] reçoit `JwtAuthenticationToken` : il a besoin de l'expiration du jeton (guide 24).

Les tests :

- [`TaskListApiTests`][list-tests] : structure de la page, tri par défaut, taille ramenée à 100, tri inconnu.
- [`TaskQueryCountTests`][task-count] et [`UserQueryCountTests`][user-count] : autant de requêtes SQL pour une longue liste que pour une courte (guide 31).
- [`TaskCommentControllerWebMvcTests`][comment-tests], [`IdenticonControllerWebMvcTests`][identicon-tests] (`Cache-Control`, `ETag`, 304) et [`DataExportControllerWebMvcTests`][export-tests] (202 et `Location`).

Pour ajouter un endpoint : un chemin relatif dans un contrôleur du package de la fonctionnalité, un service qui charge tout ce que le record lira, le `ResponseEntity` du bon statut, puis ce que la documentation demande (guide 06).

## Voir aussi

- [04. Versioning d'API](04-versioning-api.md) : d'où vient le préfixe `/api/v1`.
- [05. Validation et erreurs](05-validation-erreurs.md) : `@Valid`, les 400, 404 et 413.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : ce qu'un endpoint déclare pour sa documentation.
- [11. Spring Data JPA](11-spring-data-jpa.md) : `@EntityGraph`, chargement par lots, problème N+1.
- [28. Uploads et drivers de stockage](28-uploads-stockage.md) : type réel d'un fichier, antivirus, stockage S3.
- Documentation officielle : [contrôleurs annotés](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html), [cache HTTP dans Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-caching.html), [Spring Data et le web](https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html).

[Sommaire](README.md) | [< 02. Profil de production](02-profil-production.md) | [04. Versioning d'API >](04-versioning-api.md)

[task-controller]: ../../src/main/java/io/julienmetral/tasks/task/controllers/TaskController.java
[comment-controller]: ../../src/main/java/io/julienmetral/tasks/task/controllers/TaskCommentController.java
[export-controller]: ../../src/main/java/io/julienmetral/tasks/export/controllers/DataExportController.java
[user-controller]: ../../src/main/java/io/julienmetral/tasks/identity/controllers/UserController.java
[identicon-controller]: ../../src/main/java/io/julienmetral/tasks/identity/controllers/IdenticonController.java
[current-user]: ../../src/main/java/io/julienmetral/tasks/identity/security/CurrentUser.java
[task-dto]: ../../src/main/java/io/julienmetral/tasks/task/dtos/TaskResponseDto.java
[profile-dto]: ../../src/main/java/io/julienmetral/tasks/identity/dtos/UserProfileResponseDto.java
[profiles]: ../../src/main/java/io/julienmetral/tasks/identity/services/ProfilesForDisplay.java
[task-repository]: ../../src/main/java/io/julienmetral/tasks/task/repositories/TaskRepository.java
[media-urls]: ../../src/main/java/io/julienmetral/tasks/media/services/MediaUrls.java
[stream-controller]: ../../src/main/java/io/julienmetral/tasks/realtime/controllers/NotificationStreamController.java
[list-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskListApiTests.java
[task-count]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskQueryCountTests.java
[user-count]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/UserQueryCountTests.java
[comment-tests]: ../../src/test/java/io/julienmetral/tasks/task/controllers/TaskCommentControllerWebMvcTests.java
[identicon-tests]: ../../src/test/java/io/julienmetral/tasks/identity/controllers/IdenticonControllerWebMvcTests.java
[export-tests]: ../../src/test/java/io/julienmetral/tasks/export/controllers/DataExportControllerWebMvcTests.java
