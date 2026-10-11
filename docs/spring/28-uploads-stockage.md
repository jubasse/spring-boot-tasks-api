# 28. Uploads et drivers de stockage

[Sommaire](README.md) | [< 27. Résilience](27-resilience.md) | [29. Couches de tests et contextes >](29-tests-couches-contextes.md)

## Contexte

- **Problème** : accepter des fichiers de clients sans croire ce qu'ils annoncent (type, nom, contenu). Les garder hors de la base. Les servir sans faire passer chaque téléchargement par l'API.
- **Quand l'utiliser** : pièces jointes, photos, documents déposés par les utilisateurs, et fichiers produits par l'application (exports).
- **Quand s'en passer** : quelques kilo-octets qui tiennent dans une colonne, ou des fichiers de travail qui ne quittent jamais le serveur.
- **Dans ce dépôt** : `MediaService.store` contrôle la taille, le type réel (Apache Tika) et les virus (ClamAV). Il range le fichier sous `<usage>/<uuid>` dans un stockage compatible S3 et enregistre une ligne `media` avec son SHA-256. `storage.driver` choisit le driver (`rustfs` ou `aws-s3`).
- **Prérequis** : guides 01 (beans conditionnels), 03 (multipart) et 14 (travail après le commit). Versions : Spring Boot 4.1, AWS SDK for Java 2.55, Apache Tika 4.0, ClamAV 1.5.

Où garder les fichiers :

| Solution | Principe | Quand la choisir |
|---|---|---|
| Stockage objet S3 (ou compatible) | des objets par clé, servis par URL signée | le cas courant, plusieurs instances |
| Disque local | un dossier du serveur | une seule instance, sans conteneur jetable |
| Colonne `bytea` en base | les octets dans la ligne | petits fichiers rares ; alourdit sauvegardes et requêtes |

## Concepts clés

### Vocabulaire

- **Stockage objet** : un service qui garde des fichiers (des objets) par clé, dans un bucket, par HTTP. Amazon S3 en est l'exemple, RustFS un serveur compatible.
- **Clé d'objet** : le nom de l'objet dans le bucket (`task-attachment/0192...`), choisi par l'application.
- **URL présignée** : une URL signée avec les identifiants du stockage, valable un temps limité, qui laisse un navigateur télécharger un objet directement.
- **Type réel** : le type lu dans les premiers octets du fichier (des signatures appelées « magic bytes »), par opposition au type que le client déclare.
- **Fail closed** : quand une protection ne peut pas tourner, on refuse l'opération au lieu de la laisser passer.

### Le trajet d'un upload

Chaque contrôle a son statut de refus, du moins coûteux au plus coûteux :

```
  client : POST multipart
        |
        v
  Tomcat : parties écrites dans un fichier temporaire
        |-- au-delà de max-file-size ------------> 413 (avant l'application)
        v
  service
        |-- fichier vide ------------------------> 400
        |-- au-delà de la limite de l'usage -----> 413
        |-- type réel (Tika) hors de la liste ---> 415
        |-- antivirus : menace trouvée ----------> 422
        |-- antivirus injoignable ---------------> 503
        v
  stockage objet : clé <usage>/<uuid>, SHA-256 calculé au passage
        |
        v
  ligne media dans la transaction
        |
        +-- commit ----> le fichier existe
        +-- rollback --> l'objet est supprimé
```

- Le fichier est lu trois fois (détection, analyse, envoi), et `MultipartFile.getInputStream()` le rouvre à chaque appel.
- Tomcat écrit toute partie dans un fichier temporaire (`file-size-threshold` vaut `0B`). Un conteneur en lecture seule a donc besoin d'un dossier temporaire inscriptible.

### Ne jamais croire le client

- Le `Content-Type` de la partie et le nom du fichier sont choisis par le client. Un exécutable renommé `.pdf` annonce `application/pdf`.
- Apache Tika lit les premiers octets. Le nom ne fait qu'affiner un type que les octets montrent déjà. Un ZIP ordinaire nommé `.docx` devient un document Word, mais un exécutable nommé `.pdf` reste un exécutable.
- La clé de stockage ne vient jamais du nom du client. Ce nom ne sert qu'à l'affichage, sans ses segments de chemin (certains clients envoient `C:\Users\...\report.pdf`).

### Le téléchargement par URL présignée

L'API signe une URL en local, et le navigateur télécharge directement auprès du stockage :

```
  navigateur                  API                       stockage objet
      |                        |                              |
      |-- GET /tasks/{id} ---->|                              |
      |                        |  signe une URL en local      |
      |<-- 200 {url signée} ---|  (aucun appel au stockage)   |
      |                                                       |
      |-- GET url signée ------------------------------------>|
      |<-- le fichier, en pièce jointe -----------------------|
```

- L'API ne transmet jamais le fichier : ses threads restent libres.
- La signature couvre les en-têtes de réponse demandés. `Content-Disposition: attachment; filename*=UTF-8''...` force un téléchargement sous le nom d'origine, si bien qu'une page HTML ou SVG déposée ne s'exécute jamais dans le navigateur. Viennent aussi `Content-Type` et `Cache-Control: private, max-age=<validité>, immutable`.
- Signer ne demande aucun appel au stockage, donc une liste d'URL ne coûte rien.
- Une URL est valable `storage.presigned-url-ttl` (10 min ici). Elle l'est moins longtemps si des identifiants temporaires (un rôle IAM) expirent avant.

### Un driver choisi par configuration

Le code dépend d'une interface. Une classe de configuration par valeur de `storage.driver` construit le client S3 à sa façon (mécanisme au guide 01). Un serveur auto-hébergé reçoit un endpoint explicite, des clés statiques et des URL en path-style. Amazon S3 reçoit une région et la chaîne d'identifiants par défaut d'AWS. Les deux partagent la même implémentation.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                                  AWS SDK, Tika (étape 1)
src/main/java/.../document/
  FileStorage.java                       l'interface (étape 2)
  S3FileStorage.java                     l'implémentation (étapes 3 et 8)
  StorageConfiguration.java              un driver par valeur (étape 4)
  DocumentUsage.java                     types et tailles par usage (étape 5)
  DocumentService.java                   contrôles et stockage (étapes 5 et 7)
  VirusScanner.java, ClamAvScanner.java  l'antivirus (étape 6)
src/test/java/.../
  DocumentUploadTests.java               la vérification (étape 9)
```

L'exemple gère des documents rattachés à des dossiers clients, avec deux usages : `LOGO` et `ATTACHMENT`. Les exceptions des extraits (`EmptyFileException`, `FileTooLargeException`, `UnsupportedFileTypeException`, `InfectedFileException`, `AntivirusUnavailableException`, `StorageUnavailableException`) sont des `RuntimeException` que le gestionnaire global convertit en statuts (guide 05).

### Un fichier vérifié, stocké dans S3

**Étape 1. Ajouter les dépendances.** Le BOM du SDK AWS aligne les modules S3, et `tika-core` seul suffit à détecter un type depuis les octets.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>bom</artifactId>
            <version>2.55.6</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>s3</artifactId>               <!-- client et signature d'URL -->
    </dependency>
    <dependency>
        <groupId>org.apache.tika</groupId>
        <artifactId>tika-core</artifactId>
        <version>4.0.0</version>
    </dependency>
</dependencies>
```

**Étape 2. Déclarer l'interface du stockage.** Les services ne voient jamais S3. Un autre driver est une autre implémentation.

```java
public interface FileStorage {

    void put(String key, InputStream content, long length, String contentType);

    InputStream open(String key);

    void delete(String key);

    URL downloadUrl(String key, String filename, String contentType);   // étape 8
}
```

**Étape 3. L'implémenter avec le client S3.** Une seule implémentation sert tous les serveurs compatibles S3, et toute panne du stockage devient une exception du projet (503).

```java
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration urlValidity;

    @Override
    public void put(String key, InputStream content, long length, String contentType) {
        try {
            s3.putObject(request -> request.bucket(bucket).key(key)
                            .contentType(contentType).contentLength(length),
                    RequestBody.fromInputStream(content, length));   // en flux, sans tout charger
        } catch (SdkException e) {
            throw new StorageUnavailableException(e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return s3.getObject(request -> request.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageUnavailableException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(request -> request.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageUnavailableException(e);
        }
    }

    // downloadUrl : étape 8
}
```

**Étape 4. Choisir le driver par configuration.** Le même code parle à un serveur S3 local en développement et à Amazon S3 en production (`@ConditionalOnProperty`, guide 01).

```java
@Configuration
@ConditionalOnProperty(name = "storage.driver", havingValue = "aws-s3")
class AwsS3StorageConfiguration {

    @Bean
    FileStorage fileStorage(StorageProperties properties) {    // @ConfigurationProperties (guide 01)
        Region region = Region.of(properties.region());
        return new S3FileStorage(
                S3Client.builder().region(region).build(),      // identifiants : chaîne par défaut d'AWS
                S3Presigner.builder().region(region).build(),
                properties.bucket(), properties.urlValidity());
    }
}
```

```java
@Configuration
@ConditionalOnProperty(name = "storage.driver", havingValue = "s3-compatible")
class S3CompatibleStorageConfiguration {

    @Bean
    FileStorage fileStorage(StorageProperties properties) {
        URI endpoint = URI.create(properties.endpoint());
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();  // /bucket/clé
        return new S3FileStorage(
                S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                        .credentialsProvider(credentials).serviceConfiguration(pathStyle).build(),
                S3Presigner.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                        .credentialsProvider(credentials).serviceConfiguration(pathStyle).build(),
                properties.bucket(), properties.urlValidity());
    }
}
```

Un serveur auto-hébergé sert ses buckets sous `/bucket`, d'où le path-style. RustFS ignore la région. `StorageProperties` doit être enregistrée dans une configuration toujours chargée (guide 01).

**Étape 5. Contrôler la taille et le type réel.** Les contrôles les moins chers passent d'abord. Tika lit ensuite les octets, et le nom ne fait qu'affiner.

```java
public enum DocumentUsage {

    LOGO(DataSize.ofMegabytes(2), Set.of("image/png", "image/jpeg", "image/webp")),
    ATTACHMENT(DataSize.ofMegabytes(25),
            Set.of("application/pdf", "image/png", "image/jpeg", "text/plain"));

    private final DataSize maxSize;
    private final Set<String> allowedTypes;

    DocumentUsage(DataSize maxSize, Set<String> allowedTypes) {
        this.maxSize = maxSize;
        this.allowedTypes = allowedTypes;
    }

    public DataSize maxSize() {
        return maxSize;
    }

    public boolean allows(String contentType) {
        return allowedTypes.contains(contentType);
    }
}
```

```java
// dans DocumentService
private final Tika tika = new Tika();

String validate(MultipartFile file, DocumentUsage usage) throws IOException {
    if (file.isEmpty()) {
        throw new EmptyFileException();                                    // 400
    }
    if (file.getSize() > usage.maxSize().toBytes()) {
        throw new FileTooLargeException(usage.maxSize());                  // 413
    }
    String contentType;
    try (InputStream content = file.getInputStream()) {
        contentType = tika.detect(content, file.getOriginalFilename());   // le Content-Type envoyé est ignoré
    }
    if (!usage.allows(contentType)) {
        throw new UnsupportedFileTypeException(contentType);               // 415
    }
    try (InputStream content = file.getInputStream()) {                   // relu depuis le fichier temporaire
        virusScanner.findThreat(content).ifPresent(threat -> {
            throw new InfectedFileException(threat);                       // 422
        });
    }
    return contentType;
}
```

**Étape 6. Analyser le fichier avec ClamAV.** La commande `INSTREAM` de clamd reçoit le fichier par morceaux sur TCP, sans bibliothèque cliente. Si l'antivirus est injoignable, l'upload est refusé.

```java
public interface VirusScanner {

    Optional<String> findThreat(InputStream content);    // nom de la menace, ou vide si sain
}
```

```java
@RequiredArgsConstructor
public class ClamAvScanner implements VirusScanner {

    private final String host;
    private final int port;
    private final int timeoutMillis;

    @Override
    public Optional<String> findThreat(InputStream content) {
        String reply;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);           // ne borne que les lectures (Concepts avancés)
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[64 * 1024];
            for (int read; (read = content.read(chunk)) != -1; ) {
                out.writeInt(read);                       // longueur sur 4 octets, big-endian
                out.write(chunk, 0, read);
            }
            out.writeInt(0);                              // longueur nulle : fin du fichier
            out.flush();
            reply = readUntilNullByte(socket.getInputStream());   // "stream: OK" ou "stream: <nom> FOUND"
        } catch (IOException e) {
            throw new AntivirusUnavailableException(e);   // 503 : jamais stocké sans analyse
        }
        if (reply.equals("stream: OK")) {
            return Optional.empty();
        }
        if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) {
            return Optional.of(reply.substring("stream: ".length(), reply.length() - " FOUND".length()));
        }
        throw new AntivirusUnavailableException("Unexpected clamd reply: " + reply);
    }

    // readUntilNullByte : lit les octets jusqu'à 0 ou la fin du flux, en ASCII, sans espaces autour
}
```

**Étape 7. Stocker sous une clé neutre, et nettoyer après un rollback.** L'objet part avant le commit. Si la transaction est annulée, rien ne doit rester dans le bucket.

```java
@Transactional
public Document store(MultipartFile file, DocumentUsage usage) throws IOException {
    String contentType = validate(file, usage);
    String key = usage.name().toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID();   // jamais le nom du client

    try (InputStream content = file.getInputStream()) {
        storage.put(key, content, file.getSize(), contentType);
    }
    deleteIfRolledBack(key);

    return documentRepository.save(new Document(key, usage, displayName(file.getOriginalFilename()),
            contentType, file.getSize()));
}

private void deleteIfRolledBack(String key) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
                storage.delete(key);                     // aucune ligne ne pointe vers cet objet
            }
        }
    });
}

private static String displayName(String sent) {          // "C:\\Users\\a\\devis.pdf" -> "devis.pdf"
    String name = StringUtils.getFilename(StringUtils.cleanPath(String.valueOf(sent).replace('\\', '/')));
    return StringUtils.hasText(name) && !"null".equals(name) ? name : "file";
}
```

`Document` est l'entité JPA de l'exemple.

**Étape 8. Servir le fichier par une URL présignée.** Les en-têtes signés forcent un téléchargement sous le nom d'origine.

```java
// dans S3FileStorage
@Override
public URL downloadUrl(String key, String filename, String contentType) {
    String disposition = ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)              // accents encodés (filename*=UTF-8'')
            .build()
            .toString();

    return presigner.presignGetObject(request -> request
                    .signatureDuration(urlValidity)
                    .getObjectRequest(get -> get
                            .bucket(bucket)
                            .key(key)
                            .responseContentType(contentType)
                            .responseContentDisposition(disposition)  // jamais affiché dans la page
                            .responseCacheControl("private, max-age=" + urlValidity.toSeconds()
                                    + ", immutable")))
            .url();
}
```

**Étape 9. Vérifier.** Contre de vrais conteneurs (guide 30), un exécutable déguisé et le fichier de test EICAR sont refusés, et rien n'est stocké.

```java
@SpringBootTest
@AutoConfigureMockMvc
class DocumentUploadTests {                       // stockage S3 et ClamAV en conteneurs (guide 30)

    // la chaîne de test standard des antivirus, inoffensive
    static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepository;

    @Test
    void executableNamedAsPdfIsRejected() throws Exception {
        String name = UUID.randomUUID() + ".pdf";
        upload(new MockMultipartFile("file", name, "application/pdf", new byte[] {'M', 'Z', 0, 0}))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(documentRepository.existsByDisplayName(name)).isFalse();
    }

    @Test
    void eicarFileIsRejectedAsInfected() throws Exception {
        byte[] eicar = EICAR.getBytes(StandardCharsets.US_ASCII);
        upload(new MockMultipartFile("file", "note.txt", "text/plain", eicar))
                .andExpect(status().isUnprocessableContent());
    }

    private ResultActions upload(MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/documents").file(file).with(jwt()));   // guide 07 pour jwt()
    }
}
```

### Référence rapide

Les refus et leurs statuts :

| Cas | Statut | `HttpStatus` (Spring 7) |
|---|---|---|
| fichier au-delà de `max-file-size` (avant l'application) | 413 | `CONTENT_TOO_LARGE` (guide 05) |
| fichier vide | 400 | `BAD_REQUEST` |
| au-delà de la limite de l'usage | 413 | `CONTENT_TOO_LARGE` |
| type réel refusé | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| menace trouvée | 422 | `UNPROCESSABLE_CONTENT` |
| antivirus ou stockage injoignable | 503 | `SERVICE_UNAVAILABLE` |

Les réglages de Spring Boot et de Tomcat pour les uploads :

| Réglage | Défaut | Rôle |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | `1MB` | taille d'un fichier (guide 03) |
| `spring.servlet.multipart.max-request-size` | `10MB` | taille de la requête entière |
| `spring.servlet.multipart.file-size-threshold` | `0B` | seuil d'écriture sur disque : toujours |
| `server.tomcat.max-swallow-size` | `2MB` | corps jeté après un refus ; au-delà, connexion coupée |

Limites de cette version : un objet peut rester orphelin si le processus meurt avant le commit. Une URL distribuée ne se révoque pas.

## Concepts avancés

### Sous le capot : le protocole INSTREAM

Le préfixe `z` rend la commande et la réponse terminées par un octet nul :

```
  application                                clamd (port 3310)
      |-- "zINSTREAM\0" ---------------------------->|
      |-- longueur (4 octets) + morceau de 64 Kio -->|
      |-- longueur + morceau, et ainsi de suite ---->|
      |-- longueur 0 (4 octets nuls) --------------->|  fin du fichier
      |<-- "stream: OK\0" ou "stream: <nom> FOUND\0" |
```

- clamd refuse un flux au-delà de son `StreamMaxLength` par une réponse d'erreur, que le scanner traite comme une indisponibilité (503).
- `setSoTimeout` ne borne que les lectures. Un clamd qui cesse de lire bloque l'écriture pour toujours (reproduit avec un fichier de 16 Mo). Le scanner du dépôt lance un thread virtuel qui ferme la socket à l'échéance.

### Sous le capot : un fichier, deux mondes

- La ligne est transactionnelle, l'objet ne l'est pas. Un envoi est défait par `afterCompletion` si le statut n'est pas « validé ». Une suppression se fait dans `afterCommit`, pour qu'un rollback garde le fichier vers lequel la ligne pointe encore.
- Si le processus meurt entre l'envoi et le commit, aucun callback ne tourne. Un balayage périodique supprime alors les objets sans ligne, après un délai de grâce (1 jour ici) qui épargne les uploads en cours.

### Plusieurs instances

- Le bucket est partagé : n'importe quelle instance sert n'importe quelle URL.
- Chaque instance a son propre cache d'URL signées (guide 17). Le nettoyage tourne sur une seule instance par passage (guide 21).

## Pièges à éviter

### Réception du fichier

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Régler `max-file-size` sous la plus grande limite métier | Spring refuse avant l'application, en 413 générique | Le fixer au moins à la plus grande limite |
| Croire le `Content-Type` ou l'extension | un exécutable renommé `.pdf` est stocké et servi | Détecter le type sur les octets (Tika) |
| Construire la clé avec le nom du client | chemins (`../`), collisions, fichiers écrasés | Clé `<usage>/<uuid>`, le nom gardé pour l'affichage |
| Stocker quand l'antivirus ne répond pas | un fichier infecté passe pendant la panne | Refuser en 503 (fail closed) |
| Ne borner l'antivirus que par `setSoTimeout` | un clamd qui ne lit plus bloque l'écriture | Fermer la socket à l'échéance |
| Laisser `StreamMaxLength` de clamd sous la limite d'upload | les gros fichiers échouent en 503 | L'aligner sur la plus grande limite |

### Stockage et téléchargement

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Envoyer l'objet sans nettoyage au rollback | un objet sans ligne reste dans le bucket | Le supprimer dans `afterCompletion`, et balayer les orphelins |
| Supprimer l'ancien objet avant le commit | un rollback laisse une ligne sans objet | Le supprimer dans `afterCommit` |
| Servir le fichier en `inline` | une page HTML ou SVG déposée s'exécute dans le navigateur | `Content-Disposition: attachment`, signé dans l'URL |

## En résumé

**À retenir :** on ne croit rien de ce que le client annonce. On contrôle la taille, le type réel lu dans les octets et les virus (fail closed). On choisit la clé dans un stockage objet, on nettoie si la transaction échoue, et on sert les téléchargements par URL présignée en pièce jointe.

- [ ] Limites multipart au moins égales à la plus grande limite métier
- [ ] Type détecté sur les octets (Tika), jamais sur le `Content-Type` envoyé
- [ ] Analyse antivirus, refus en 503 si elle ne peut pas tourner
- [ ] Clé `<usage>/<uuid>`, nom d'origine nettoyé pour l'affichage
- [ ] Objet supprimé après un rollback ; anciens objets supprimés après le commit
- [ ] Téléchargement par URL présignée, `attachment`, durée courte
- [ ] Une interface de stockage, un driver choisi par configuration
- [ ] Tests contre un vrai stockage S3 et un vrai clamd (EICAR)

## Dans ce dépôt

Le trajet d'un fichier, de la requête à l'URL de téléchargement :

```
  TaskAttachmentController, TaskCommentController, UserController (photo)
        |
        v
  MediaService.store(file, MediaUsage, uploaderId)        @Transactional
        |
        +-- validate : vide, taille (MediaProperties), ContentTypeDetector,
        |              VirusScanner (ClamAvScanner)
        +-- ObjectStorage.put : clé <usage>/<uuid>, SHA-256
        +-- suppression de l'objet si rollback
        +-- ligne media
        |
        v
  réponse : MediaUrls.of(media)
        --> MediaService.downloadUrl (cache mediaDownloads)
        --> S3ObjectStorage.presignDownload
```

| Fichier | Rôle |
|---|---|
| [`MediaService`][media-service] | `store`, `storeGenerated`, `validate`, `delete`, `downloadUrl` en cache |
| [`MediaUsage`][usage] | types acceptés par usage, préfixe de clé (`task-attachment`) |
| [`ContentTypeDetector`][detector] | Tika : les octets d'abord, le nom affine |
| [`VirusScanner`][scanner], [`ClamAvScanner`][clamav] | l'interface, et `INSTREAM` sur TCP avec échéance |
| [`AntivirusConfiguration`][antivirus-config] | `ClamAvScanner`, ou un scanner qui accepte tout avec un WARN (guide 01) |
| [`ObjectStorage`][storage], [`S3ObjectStorage`][s3] | l'interface, et l'implémentation commune aux deux drivers |
| [`RustFsStorageConfiguration`][rustfs] | endpoint, clés statiques, path-style, bucket créé au démarrage |
| [`AwsS3StorageConfiguration`][aws] | région, identifiants de la chaîne par défaut d'AWS |
| [`MediaCleanupService`][cleanup] | purge des fichiers supprimés et des orphelins |
| [`StorageHealthIndicator`][storage-health], [`AntivirusHealthIndicator`][antivirus-health] | `storage` et `antivirus` dans `/actuator/health` |

Les usages :

| Usage | Types acceptés | Taille maximale |
|---|---|---|
| `AVATAR_UPLOAD` | JPEG, PNG, WebP | `media.avatar-max-size`, 5MB |
| `AVATAR` | JPEG, PNG, WebP | écrit par le worker (`storeGenerated`), sans limite, détection ni antivirus |
| `TASK_ATTACHMENT` | images, PDF, texte, CSV, Markdown, RTF, Office, OpenDocument, ZIP | `media.attachment-max-size`, 25MB |
| `EXPORT` | CSV, ZIP | écrit par l'application (`storeGenerated`, guide 22), sans détection ni antivirus |

`AVATAR` est lui aussi écrit par l'application : la photo ré-encodée par le worker, dont l'original a déjà été analysé à l'upload.

Ces limites sont des réglages, et non des constantes d'énumération comme dans l'exemple.

| Réglage | Valeur | Rôle |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | `25MB` | égal à `media.attachment-max-size` |
| `storage.driver` | `${STORAGE_DRIVER:rustfs}` ; `aws-s3` sous `prod` | le driver |
| `storage.bucket` | `${S3_BUCKET:tasks-media}` | le bucket, obligatoire en `prod` |
| `storage.presigned-url-ttl` | `10m` | validité d'une URL de téléchargement |
| `antivirus.enabled` | `${ANTIVIRUS_ENABLED:true}` | `false` stocke sans analyser (poste de développement) |
| `media.cleanup.cron`, `retention`, `orphan-grace-period` | `0 30 3 * * *`, `30d`, `1d` | la purge nocturne |

Ce qui est propre au dépôt :

- L'ordre de `validate` : vide (400), taille (413), Tika (415), antivirus (422 pour le problème typé `infected-file`, 503 quand clamd échoue). Les 400, 413, 415 et 503 sont des problèmes `about:blank` (`ApiExceptionHandler`).
- `S3ObjectStorage.presignDownload` résout lui-même les identifiants et renvoie `expiresAt`, la plus proche des deux expirations. `CacheConfiguration.reuseWindow` redistribue une URL pendant la moitié de sa validité au plus (guide 17). Le `@Cacheable` est sur `MediaService` et non sur `MediaUrls`, dont `avatarOf` appelle `of` sur lui-même et contournerait le proxy Spring.
- Avec `aws-s3`, le bucket est créé par l'infrastructure, jamais par l'application. Ce driver n'est pas couvert par les tests d'intégration.
- Les photos de profil passent par le même `store` (`AVATAR_UPLOAD`), puis un 202 et un worker qui les ré-encode. Le worker stocke son résultat par `storeGenerated` (variante en `byte[]`), sans seconde analyse : l'original a déjà été analysé, et le résultat n'est fait que de pixels ré-encodés. Avec `store`, une panne de clamd pendant le worker envoyait en dead letter une photo déjà analysée. Le [cas pratique](cas-pratiques/upload-photo-profil.md) détaille le trajet.
- `MediaCleanupJob` (verrou `media-cleanup`) purge les pièces jointes des tâches et les photos des utilisateurs supprimés depuis 30 jours. Il purge aussi les lignes `media` sans référence et les objets sans ligne plus vieux d'un jour. Il utilise du SQL natif (`MediaCleanupQueries`), car les lignes supprimées logiquement sont invisibles pour JPA. Les objets partent après le commit, et un échec est balayé au passage suivant.

Les tests :

- [`MediaStorageTests`][storage-tests] : nom non ASCII, `Cache-Control` signé, type déclaré ignoré, exécutable déguisé refusé, rollback qui retire ligne et objet.
- [`AntivirusScanningTests`][antivirus-tests] : EICAR refusé dans un texte et dans un ZIP.
- `TestcontainersConfiguration` démarre RustFS et ClamAV (`clamav/clamav:1.5.4-debian`, freshclam coupé, une seule signature EICAR : environ 14 Mo au lieu d'environ 1 Go).

Pour ajouter un usage : une constante de `MediaUsage` avec ses types, sa limite dans `MediaProperties` et le `switch` `maxSize` de `MediaService`, `max-file-size` relevé si besoin, et un test des types acceptés et refusés.

## Voir aussi

- [03. Contrôleurs MVC](03-controleurs-mvc.md) : le contrôleur multipart et ses limites.
- [01. Configuration et `@ConfigurationProperties`](01-configuration-proprietes.md) : le driver choisi par `storage.driver`.
- [17. Cache Caffeine](17-cache-caffeine.md) : le cache des URL présignées.
- [30. Testcontainers](30-testcontainers.md) : les conteneurs RustFS et ClamAV en test.
- [Cas pratique : l'upload d'une photo de profil](cas-pratiques/upload-photo-profil.md).
- Documentation officielle : [multipart dans Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/multipart.html), [URL présignées S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ShareObjectPreSignedURL.html), [Apache Tika](https://tika.apache.org/).

[Sommaire](README.md) | [< 27. Résilience](27-resilience.md) | [29. Couches de tests et contextes >](29-tests-couches-contextes.md)

[media-service]: ../../src/main/java/io/julienmetral/tasks/media/services/MediaService.java
[usage]: ../../src/main/java/io/julienmetral/tasks/media/model/MediaUsage.java
[detector]: ../../src/main/java/io/julienmetral/tasks/media/services/ContentTypeDetector.java
[scanner]: ../../src/main/java/io/julienmetral/tasks/media/services/VirusScanner.java
[clamav]: ../../src/main/java/io/julienmetral/tasks/media/services/ClamAvScanner.java
[antivirus-config]: ../../src/main/java/io/julienmetral/tasks/config/AntivirusConfiguration.java
[storage]: ../../src/main/java/io/julienmetral/tasks/media/services/ObjectStorage.java
[s3]: ../../src/main/java/io/julienmetral/tasks/media/services/S3ObjectStorage.java
[rustfs]: ../../src/main/java/io/julienmetral/tasks/config/RustFsStorageConfiguration.java
[aws]: ../../src/main/java/io/julienmetral/tasks/config/AwsS3StorageConfiguration.java
[cleanup]: ../../src/main/java/io/julienmetral/tasks/media/services/MediaCleanupService.java
[storage-health]: ../../src/main/java/io/julienmetral/tasks/media/health/StorageHealthIndicator.java
[antivirus-health]: ../../src/main/java/io/julienmetral/tasks/media/health/AntivirusHealthIndicator.java
[storage-tests]: ../../src/test/java/io/julienmetral/tasks/media/services/MediaStorageTests.java
[antivirus-tests]: ../../src/test/java/io/julienmetral/tasks/media/services/AntivirusScanningTests.java
