# 33. Image Docker, build et CI

[Sommaire](README.md) | [< 32. Actuator, sondes et métriques](32-actuator-metriques.md) | [34. Formatage et vérification de la nullité >](34-formatage-nullite.md)

## Contexte

- **Problème** : livrer l'API sous la forme d'une image qui démarre vite, tourne sans droits et se reconstruit en quelques secondes quand seul le code change. Elle ne part en production qu'après les tests et un scan de vulnérabilités.
- **Quand l'utiliser** : toute application Spring Boot déployée en conteneur (Kubernetes, ECS, un serveur avec Docker).
- **Quand s'en passer** : une plateforme qui construit l'image elle-même (Buildpacks via `spring-boot:build-image`, Cloud Foundry). On garde alors la CI.
- **Dans ce dépôt** : un `Dockerfile` en deux étapes, un cache AOT entraîné pendant le build, `compose.yaml` pour le développement, `compose.production.yaml` pour l'image durcie, une CI GitHub Actions.
- **Prérequis** : guides 32 (sondes, port de gestion) et 02 (profil `prod`). Versions : Java 25 (Temurin), Spring Boot 4.1, Docker avec BuildKit, Docker Compose 2.24.4 ou plus (pour `!reset` et `!override`).

Les façons de construire l'image :

| Solution | Principe | Quand la choisir |
|---|---|---|
| `Dockerfile` multi-étapes | on écrit chaque étape : build, découpage, utilisateur, JVM | contrôle complet (cache AOT, durcissement) |
| Buildpacks (`./mvnw spring-boot:build-image`) | Spring Boot produit l'image sans `Dockerfile` | image standard, peu de réglages |

## Concepts clés

### Vocabulaire

- **Étape de build** (stage) : une image intermédiaire dans un `Dockerfile`. L'image finale ne garde que ce qu'on y copie.
- **Couche** (layer) : le résultat d'une instruction du `Dockerfile`. Docker la réutilise tant que ses entrées ne changent pas.
- **Digest** : l'empreinte `sha256:...` d'une image, qui ne change jamais, contrairement à un tag.
- **Cache AOT** : un fichier (`app.aot`) où la JVM range les classes chargées et liées pendant un démarrage d'entraînement. Le vrai démarrage le relit au lieu de tout recharger (Java 25 et plus).
- **SBOM** : la liste des composants d'un logiciel et de leurs versions (format CycloneDX ici).
- **Attestation de provenance** : une preuve signée de quel workflow, à partir de quel commit, a construit l'image.

### Une image en couches

Le jar est découpé en quatre dossiers, copiés dans autant de couches :

```
 étape build (JDK)                         image finale (JRE)
   mvnw + pom.xml                            +------------------------+
   dependency:go-offline   (couche stable)   | dependencies/          |  rare
   src/main/ --> package                     | spring-boot-loader/    |
   jar --> extract --layers ---------------> | snapshot-dependencies/ |
                                             | application/           |  souvent
                                             +------------------------+
                                               + app.aot, utilisateur 10001
```

- Docker reconstruit une couche, et toutes celles qui suivent, dès qu'une entrée change. On place donc en premier ce qui change rarement : le pom seul, puis les dépendances.
- `java -Djarmode=tools -jar app.jar extract --layers` découpe le jar en quatre dossiers. Une nouvelle version ne change en général que `application/`, la seule couche à retélécharger.
- L'image finale part d'un JRE, sans Maven, sans JDK et sans sources.

### Le cache AOT

L'entraînement a lieu pendant le build, le profit à chaque démarrage :

```
 build de l'image
      |
      v
 java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar ...
      |  le contexte se crée (beans, configuration) ...
      |  ... puis la JVM s'arrête avant serveur web et listeners
      v
 app.aot : classes chargées et liées
      |
      v
 démarrage en production : java -XX:AOTCache=app.aot -jar ...
      |
      v
 un tiers de temps en moins (mesuré ici : 8,1 s au lieu de 12,8 s)
```

- `spring.context.exit=onRefresh` arrête la JVM à la fin du rafraîchissement du contexte, avant le démarrage des composants `SmartLifecycle` (serveur web, listeners RabbitMQ) et des `ApplicationRunner`.
- L'entraînement a lieu pendant le `docker build`, donc aucun service n'est joignable. Un bean qui se connecte pendant la création du contexte fait échouer le build. Une connexion dans un `ApplicationRunner` ou au démarrage d'un `SmartLifecycle` ne gêne pas.
- Le cache ne sert qu'avec la forme extraite du jar, le même jar et la même version de Java. Avec Java 24 ou moins, on utilise CDS, son prédécesseur.

### Du commit à l'image publiée

Quatre contrôles en parallèle, et une publication réservée aux tags :

```
 push / pull request
      |
      +--> tests (./mvnw verify) ----------+
      +--> scan des secrets (gitleaks)     |
      +--> contrat d'API (guide 06)        |
      +--> image : build + scan (Grype) ---+
                                           |
                     tag v* seulement      v
                     publication : registre + provenance + SBOM + attestation
```

## Implémentation avec Spring

Ce qu'on va créer :

```
Dockerfile                    étapes de build et d'exécution (étapes 1 à 5)
.dockerignore                 ce qui entre dans le build (étape 6)
compose.production.yaml       l'image lancée comme en production (étape 7)
.github/workflows/ci.yml      tests, image, scan (étape 8)
```

### L'image

**Étape 1. Construire le jar dans une étape JDK.** Le pom et le téléchargement des dépendances forment une couche à part, refaite seulement quand le pom change. Dans un `Dockerfile`, un commentaire tient sur sa propre ligne, car un `#` en fin d'instruction en ferait partie.

```dockerfile
# syntax=docker/dockerfile:1
# image épinglée par digest
FROM eclipse-temurin:25-jdk-noble@sha256:<digest> AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
# la couche des dépendances
RUN ./mvnw --batch-mode --quiet dependency:go-offline
COPY src/main/ src/main/
RUN ./mvnw --batch-mode --quiet -Dmaven.test.skip=true package \
    && cp target/invoices-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted
```

`-Dmaven.test.skip=true` saute la compilation et l'exécution des tests. La CI les a déjà lancés, et `src/test` n'est même pas dans le contexte de build.

**Étape 2. Copier les couches dans une image JRE.** Une couche par rythme de changement. Une nouvelle version ne change en général que la dernière.

```dockerfile
FROM eclipse-temurin:25-jre-noble@sha256:<digest>
WORKDIR /application
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
```

**Étape 3. Créer un utilisateur sans droits.** Un processus compromis dans le conteneur n'est pas root.

```dockerfile
RUN groupadd --system --gid 10001 invoices \
    && useradd --system --uid 10001 --gid invoices --no-create-home \
       --home-dir /nonexistent --shell /usr/sbin/nologin invoices
# ... (étapes 4 et 5)
# après l'entraînement de l'étape 5
USER invoices
# API, port de gestion (guide 32)
EXPOSE 8080 8081
```

**Étape 4. Régler la JVM pour un conteneur.** Par défaut, le tas ne prend que 25 % de la mémoire du conteneur. Sous 2 CPU ou 1792 Mo, la JVM choisit le ramasse-miettes Serial, qui arrête toute l'application à chaque passage.

```dockerfile
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
```

Avec `ExitOnOutOfMemoryError`, la JVM s'arrête au premier `OutOfMemoryError`. L'orchestrateur redémarre alors un conteneur sain plutôt que de garder un processus à moitié cassé. Changer une option oblige à redéfinir toute la variable.

**Étape 5. Entraîner le cache AOT pendant le build.** Un démarrage sans service, arrêté à la fin du rafraîchissement du contexte, enregistre les classes que le vrai démarrage relira.

```dockerfile
RUN JWT_SECRET="$(head -c 32 /dev/urandom | base64)" \
    java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -Dspring.datasource.url=jdbc:postgresql://localhost/training \
        -Dspring.liquibase.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -jar application.jar

# après l'entraînement, qui ne doit pas exiger les réglages de production
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
```

- Liquibase est coupé : il migrerait la base.
- Hibernate n'accède plus aux métadonnées JDBC et reçoit un dialecte explicite, sinon il se connecte pour les lire.
- Chaque réglage que le démarrage exige reçoit une clé jetable.
- Le profil `prod` est posé ensuite, sinon l'entraînement s'arrêterait sur les réglages obligatoires absents (guide 02).

**Étape 6. Limiter le contexte de build par une liste blanche.** Rien d'autre n'entre dans le build, `.env` et ses secrets en premier.

```
# .dockerignore : tout exclure, puis réautoriser
*
!mvnw
!.mvn/wrapper/maven-wrapper.properties
!pom.xml
!src/main/
```

**Étape 7. Lancer l'image comme en production, à côté du développement.** Un second fichier Compose réutilise les services de `compose.yaml` sous un autre nom de projet. Il durcit le conteneur comme le ferait la plateforme.

```yaml
# compose.production.yaml, lancé avec :
#   docker compose -p invoices-prod -f compose.yaml -f compose.production.yaml up -d --build
services:
  api:
    build: .
    depends_on:
      postgres: {condition: service_healthy}
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/invoices
      JWT_SECRET: ${JWT_SECRET:?set JWT_SECRET in .env}      # refuse de démarrer sans
    ports:
      - "127.0.0.1:18080:8080"            # port de dev + 10000, sur la machine seulement
      - "127.0.0.1:18081:8081"
    read_only: true
    tmpfs:
      - /tmp:size=256m                    # seul endroit inscriptible
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    mem_limit: 1g
    cpus: 2
    pids_limit: 512
    stop_grace_period: 30s                # plus que l'arrêt propre de l'application
  postgres:
    ports: !reset []                      # pas de port publié pour la copie de production
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \"$$POSTGRES_USER\" -d \"$$POSTGRES_DB\""]
      interval: 2s
      retries: 30
```

### La CI

**Étape 8. Tester, construire et scanner à chaque push.** L'image n'est publiée que si les tests et le scan passent. Les actions sont épinglées par SHA, et la publication ne vise que les tags :

```yaml
  publish:
    if: startsWith(github.ref, 'refs/tags/v')
    needs: [build, image]
    # login, puis build-push-action avec provenance: mode=max et sbom: true, puis actions/attest
```

Les jobs de la CI :

| Job | Rôle | Échoue quand |
|---|---|---|
| tests | `spotless:check`, puis `./mvnw verify` | un fichier mal formaté, ou un test qui échoue |
| secrets | gitleaks sur tout l'historique poussé | un secret est commité |
| contrat d'API | Spectral, puis oasdiff (guide 06) | une rupture du contrat |
| image | build, scan Grype, résultats dans l'onglet Security | une faille haute ou critique a un correctif |
| publication (tag `v*`) | push, provenance, SBOM, attestation signée | tests ou scan en échec |

**Étape 9. Vérifier.** L'image démarre sans `.env`, en utilisateur 10001, et répond sur ses sondes.

```bash
docker build -t invoices-api .
docker compose -p invoices-prod -f compose.yaml -f compose.production.yaml up -d --build
docker compose -p invoices-prod -f compose.yaml -f compose.production.yaml exec api id -u   # 10001
curl -s localhost:18080/readyz                                                              # {"status":"UP"}
docker compose -p invoices-prod -f compose.yaml -f compose.production.yaml down -v          # -p, toujours
```


### Référence rapide

Les options de la JVM :

| Option | Rôle |
|---|---|
| `-XX:+UseG1GC` | ramasse-miettes G1, même sur un petit conteneur |
| `-XX:MaxRAMPercentage=75` | tas maximal à 75 % de la mémoire du conteneur |
| `-XX:+ExitOnOutOfMemoryError` | arrêt au premier manque de mémoire |
| `-XX:AOTCacheOutput=app.aot` | entraînement : écrit le cache AOT (Java 25) |
| `-XX:AOTCache=app.aot` | démarrage : lit le cache |
| `-Dspring.context.exit=onRefresh` | arrête la JVM après le rafraîchissement du contexte |

Limites de cette version : le cache AOT ne vaut que pour ce jar et cette version de Java. Le scan ne voit que les failles connues le jour du build.

## Concepts avancés

### Sous le capot : ce que l'entraînement interdit

- Un bean qui se connecte pendant le rafraîchissement du contexte casse le build de l'image. Ici, le dépôt de jobs JDBC de Spring Batch lit les métadonnées de la base à ce moment. L'entraînement exclut donc `BatchJdbcAutoConfiguration` (`spring.autoconfigure.exclude`), et Batch prend un dépôt en mémoire pour l'entraînement seulement.
- Un nouveau réglage exigé au démarrage doit recevoir une valeur jetable dans le `RUN` d'entraînement (ici `JWT_SECRET` et `WEBHOOK_ENCRYPTION_KEY`, 32 octets aléatoires en Base64).

### Plusieurs environnements

Une seule image sert tous les environnements, et seules les variables changent (guide 02). En production, on désigne l'image par son digest. Les tags (`1.4.2`, `1.4`, `latest`) ne sont que des alias pratiques.

### Surveiller les dépendances

- Dependabot ouvre chaque semaine des pull requests pour Maven, les images du `Dockerfile` (tag et digest ensemble), `compose.yaml` et les actions (SHA et commentaire de version), vers `develop`.
- Pour une faille corrigée que Spring Boot ne livre pas encore, on surcharge la version dans le pom, avec la raison et la condition de retrait.

## Pièges à éviter

### L'image

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Copier tout le dépôt avant `mvnw package` | chaque changement de code retélécharge les dépendances | Copier le pom, les dépendances, puis les sources |
| Épingler l'image de base par tag seulement | le tag change, le build n'est plus reproductible | Épingler par digest, mis à jour par Dependabot |
| Lancer en root | un processus compromis a tous les droits du conteneur | Un utilisateur dédié et `USER` |
| Garder les réglages mémoire par défaut | tas à 25 % de la mémoire, Serial sous 2 CPU | `MaxRAMPercentage` et `UseG1GC` explicites |
| Oublier `.dockerignore` | `.env`, `target/` et l'historique Git entrent dans le build | Une liste blanche |
| Ajouter un fichier de build sans le réautoriser | `./mvnw verify` passe, le build de l'image échoue (`.mvn/jvm.config` d'Error Prone) | Une ligne `!fichier` dans `.dockerignore` |

### Le cache AOT

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser un bean se connecter pendant le rafraîchissement | l'entraînement échoue, et le build avec | Se connecter dans un `ApplicationRunner` |
| Ajouter un réglage obligatoire sans valeur d'entraînement | le build échoue au démarrage d'entraînement | Une valeur jetable dans le `RUN` |
| Poser `SPRING_PROFILES_ACTIVE=prod` avant l'entraînement | l'entraînement exige les réglages de production | Le poser après |

### Compose et CI

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Lancer le fichier de production sans `-p` | il rejoint le projet de développement : `down -v` efface ses données | Toujours `-p` avec un nom dédié |
| Nommer le fichier `compose.override.yaml` | Compose le charge tout seul : le développement démarre l'image | Garder un autre nom |
| Référencer une action par tag (`@v4`) | un tag déplacé exécute un autre code dans la CI | Épingler par SHA |
| Passer un secret en `build-arg` | il finit dans la provenance publiée | Le passer à l'exécution |

## En résumé

**À retenir :** une image en deux étapes et en couches, épinglée par digest, sans droits, avec une JVM réglée pour le conteneur et un cache AOT entraîné sans service. Elle n'est publiée, avec sa provenance et son SBOM, qu'après les tests et le scan.

- [ ] Bases épinglées par digest, mises à jour par Dependabot
- [ ] Une couche de dépendances avant les sources
- [ ] Le jar extrait en quatre couches
- [ ] Un utilisateur non root, un système de fichiers en lecture seule possible
- [ ] `JAVA_TOOL_OPTIONS` : G1, `MaxRAMPercentage`, `ExitOnOutOfMemoryError`
- [ ] Un entraînement AOT sans service, le profil `prod` posé après
- [ ] `.dockerignore` en liste blanche
- [ ] Un fichier Compose qui lance l'image durcie, toujours avec `-p`
- [ ] CI : tests, scan des secrets, scan de l'image, actions épinglées par SHA
- [ ] Publication sur tag seulement, avec provenance, SBOM et attestation

## Dans ce dépôt

Le contenu du `Dockerfile` :

```
 Dockerfile
   build : eclipse-temurin:25-jdk-noble@sha256:...
     mvnw, pom.xml --> dependency:go-offline
     src/main/     --> package (tests sautés) --> extract --layers
   finale : eclipse-temurin:25-jre-noble@sha256:...
     utilisateur tasks, uid et gid 10001
     4 COPY : dependencies, spring-boot-loader,
              snapshot-dependencies, application
     JAVA_TOOL_OPTIONS
     RUN entraînement AOT --> app.aot
     SPRING_PROFILES_ACTIVE=prod, USER tasks, EXPOSE 8080 8081
     ENTRYPOINT java -XX:AOTCache=app.aot -jar application.jar
```

| Fichier | Rôle |
|---|---|
| [`Dockerfile`][dockerfile] | deux étapes, couches, utilisateur 10001, options JVM, entraînement AOT |
| [`.dockerignore`][dockerignore] | la liste blanche : `mvnw`, `.mvn/wrapper`, `.mvn/jvm.config`, `lombok.config`, `pom.xml`, `src/main/` |
| [`compose.yaml`][compose] | les services du développement : Postgres, Mailpit, RustFS, ClamAV, RabbitMQ |
| [`compose.production.yaml`][compose-prod] | l'image durcie sur sa propre copie des services, projet `tasks-prod` |
| [`pom.xml`][pom] | `build-info`, CycloneDX, JaCoCo, surcharges de versions |
| [`ci.yml`][ci] | tests, gitleaks, contrat d'API, image et scan, publication |
| [`dependabot.yml`][dependabot] | mises à jour hebdomadaires vers `develop` |

Les réglages de l'image :

| Réglage | Valeur | Rôle |
|---|---|---|
| `JAVA_TOOL_OPTIONS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | JVM adaptée au conteneur |
| `SPRING_PROFILES_ACTIVE` | `prod`, après l'entraînement | sinon l'image démarrerait sur les défauts de développement |
| Entraînement : `spring.liquibase.enabled` | `false` | ne pas migrer |
| Entraînement : `hibernate.boot.allow_jdbc_metadata_access` | `false`, dialecte `PostgreSQLDialect` | ne pas se connecter |
| Entraînement : `storage.driver` | `aws-s3` | le driver de production (le défaut de développement est `rustfs`) |
| Entraînement : `JWT_SECRET`, `WEBHOOK_ENCRYPTION_KEY` | 32 octets aléatoires en Base64 | réglages exigés au démarrage |

Ce qui est propre au dépôt :

- Le gain du cache AOT est la médiane de cinq démarrages alternés : 8,1 s au lieu de 12,8 s.
- L'image n'écrit que dans `/tmp` (uploads en cours de vérification), donc elle tourne en lecture seule. Le conteneur doit disposer d'au moins 1 Go.
- `compose.production.yaml` impose le projet `tasks-prod` (`-p`). Ses ports sont publiés sur `127.0.0.1` au port de développement plus 10000 (18080, 18081, 18025 pour Mailpit, 19000 pour RustFS). Postgres, ClamAV et RabbitMQ n'en publient aucun. Des healthchecks portent sur Postgres (`pg_isready`) et RabbitMQ (`rabbitmq-diagnostics -q ping`).
- `S3_PUBLIC_ENDPOINT: http://localhost:19000`, car les liens de téléchargement s'ouvrent hors du réseau Compose. Le durcissement ajoute `read_only`, `tmpfs /tmp:size=256m`, `cap_drop: [ALL]`, `no-new-privileges`, 1 Go, 2 CPU, 512 processus et `stop_grace_period: 30s`, au-dessus des 20 s d'arrêt propre du profil `prod`.
- Le pom surcharge quatre versions, chacune avec sa raison et sa condition de retrait : `tomcat.version` 11.0.26, `rabbit-amqp-client.version` 5.36.0, `jackson-bom.version` 3.1.7 et `jackson-2-bom.version` 2.21.7.
- La CI se déclenche sur `main`, `develop`, `features/**`, `release/**`, les tags `v*` et les pull requests vers `develop` et `main`. Une nouvelle exécution sur la même branche annule la précédente (`concurrency`, `cancel-in-progress`).
- Le job de tests vérifie d'abord le formatage (`./mvnw spotless:check`, google-java-format en style AOSP, 100 colonnes) : l'échec arrive en quelques secondes, avant les tests. Il lance ensuite `./mvnw verify` avec `TESTCONTAINERS_REUSE_ENABLE: "true"` (guide 30) et garde le rapport JaCoCo en artefact. Gitleaks lit tout l'historique (`fetch-depth: 0`).
- Le job d'image utilise Buildx avec un cache `type=gha`, puis Grype (`severity-cutoff: high`, `only-fixed: true`). Le SARIF part même si le scan échoue.
- La publication sur tag `v*` pousse vers `ghcr.io` les tags `{{version}}`, `{{major}}.{{minor}}`, le SHA court et `latest`, avec `provenance: mode=max`, `sbom: true`, puis `actions/attest` (signature Sigstore). Le fichier rappelle qu'un build arg finit dans la provenance : jamais de secret.

Aucun test automatique ne vise l'image elle-même : le job « Image » de la CI la construit (donc lance l'entraînement AOT) et la scanne à chaque push. En local, `compose.production.yaml` la fait tourner.

Pour ajouter un réglage exigé au démarrage, on met une valeur jetable dans le `RUN` d'entraînement, une variable dans `compose.production.yaml`, et une ligne dans le tableau du README (`deployment.required-properties`, guide 02).

## Voir aussi

- [32. Actuator, sondes et métriques](32-actuator-metriques.md) : les deux ports de l'image et ce que servent `build-info` et le SBOM.
- [02. Profil de production](02-profil-production.md) : le profil `prod` et les réglages obligatoires.
- [30. Testcontainers](30-testcontainers.md) : les conteneurs du job de tests.
- [34. Formatage et vérification de la nullité](34-formatage-nullite.md) : l'étape de formatage et la compilation avec NullAway.
- [06. OpenAPI avec springdoc](06-openapi-springdoc.md) : le job du contrat d'API.
- [README du dépôt, « Deploy the API »](../../README.md#deploy-the-api) : variables, image publiée, vérification de l'attestation.
- Documentation officielle : [cache AOT](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html), [Dockerfiles pour Spring Boot](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html), [builds multi-étapes](https://docs.docker.com/build/building/multi-stage/).

[Sommaire](README.md) | [< 32. Actuator, sondes et métriques](32-actuator-metriques.md) | [34. Formatage et vérification de la nullité >](34-formatage-nullite.md)

[dockerfile]: ../../Dockerfile
[dockerignore]: ../../.dockerignore
[compose]: ../../compose.yaml
[compose-prod]: ../../compose.production.yaml
[pom]: ../../pom.xml
[ci]: ../../.github/workflows/ci.yml
[dependabot]: ../../.github/dependabot.yml
