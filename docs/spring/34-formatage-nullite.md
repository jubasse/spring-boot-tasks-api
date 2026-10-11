# 34. Formatage et vérification de la nullité

[Sommaire](README.md) | [< 33. Image Docker, build et CI](33-docker-build-ci.md)

## Contexte

- **Problème** : sans règle de mise en page, chaque éditeur reformate à sa façon, et les diffs mêlent changements réels et espaces. Sans vérification de la nullité, un `NullPointerException` ne se voit qu'à l'exécution, souvent en production, sur le cas que personne n'a testé.
- **Quand l'utiliser** : tout projet à plusieurs mains, ou relu par des agents de code. Le formatage se vérifie en quelques secondes ; la nullité, dès que le code manipule des colonnes facultatives, des réponses partielles ou des API de bibliothèques.
- **Quand s'en passer** : un prototype jetable. Pour la nullité, un code qui n'a presque aucune valeur facultative y gagne peu, mais Spring 7 déclare déjà ses API : le coût d'entrée baisse.
- **Dans ce dépôt** : Spotless avec google-java-format (style AOSP, 100 colonnes), vérifié par `./mvnw verify` et par une étape de la CI ; NullAway, lancé par Error Prone à chaque compilation, sur un code entièrement `@NullMarked` (JSpecify).
- **Prérequis** : guide 33 (le pipeline et l'image). Versions : Java 25, Spring Boot 4.1 (Spring Framework 7), Spotless 3.10.4, google-java-format 1.36.1, Error Prone 2.50.0, NullAway 0.14.2, JSpecify 1.0.1, Lombok 1.18.46.

Les outils possibles :

| Outil | Rôle | Quand le choisir |
|---|---|---|
| google-java-format | formateur sans réglage, 100 colonnes | un style standard, identique dans tous les IDE |
| palantir-java-format | variante à 120 colonnes | quand il lira toute la syntaxe Java 25 (pas encore `_` dans un motif de record) |
| Formateur Eclipse | profil XML détaillé | garder un style maison au plus près de l'existant |
| NullAway | vérifie la nullité à la compilation, rapide | le choix courant, celui de Spring |
| Checker Framework | même but, analyse plus complète | quand on accepte une compilation bien plus lente et plus d'annotations |

## Concepts clés

### Vocabulaire

- **Formateur** : un outil qui réécrit le code dans une mise en page unique. Il ne change que les espaces, les retours à la ligne et l'ordre des imports.
- **Spotless** : un plugin Maven qui applique un formateur (`spotless:apply`) ou vérifie qu'un fichier est déjà formaté (`spotless:check`).
- **Error Prone** : un plugin du compilateur `javac` de Google, qui ajoute des vérifications pendant la compilation.
- **NullAway** : une vérification d'Error Prone dédiée à la nullité.
- **JSpecify** : les annotations standard de nullité (`@NullMarked`, `@Nullable`), adoptées par Spring 7.
- **Code `@NullMarked`** : du code où un type sans annotation veut dire « jamais null ».
- **Annotation de type** (type-use) : une annotation qui porte sur un type, à l'endroit où il est écrit, pas sur la déclaration.

### Un seul format, vérifié partout

Le développeur formate, la machine vérifie :

```
 IDE (.editorconfig)        ./mvnw spotless:apply
        |                          |
        v                          v
   code saisi ----------> code formaté --> commit
                                             |
                    CI : spotless:check      v   (quelques secondes)
                    puis ./mvnw verify  <--- push
                         (spotless:check encore)
```

- Le formateur décide de tout : deux personnes qui écrivent la même chose obtiennent le même fichier.
- `spotless:check` ne modifie rien : il échoue et liste les fichiers à reformater.
- Le premier passage reformate tout le dépôt en un commit. `git blame` l'ignore grâce à `.git-blame-ignore-revs`.

### Rien n'est null, sauf ce qui le dit

Dans un package `@NullMarked`, la nullité fait partie du type :

```
 @NullMarked package ...dtos;

 String                 jamais null
 @Nullable String       peut être null
 List<UUID>             liste non nulle, éléments non nuls
 List<@Nullable UUID>   liste non nulle, éléments peut-être nuls
 @Nullable List<UUID>   la liste elle-même peut être null
```

NullAway suit chaque valeur pendant la compilation :

```
 @Nullable Instant dueAt = task.getDueAt();
 dueAt.isBefore(now)              --> erreur : déréférence d'un @Nullable
 if (dueAt != null) {
     dueAt.isBefore(now)          --> accepté : le test prouve la valeur
 }
 return dueAt;                    --> erreur si le retour n'est pas @Nullable
```

- NullAway refuse quatre choses : déréférencer une valeur `@Nullable`, la passer ou la retourner là où le type ne l'accepte pas, laisser un champ non nul sans valeur à la fin du constructeur, et redéfinir une méthode d'une façon qui contredit sa signature (un paramètre annoncé `@Nullable` par Spring, traité comme non nul).
- L'analyse est rapide parce qu'elle est locale : chaque méthode est vérifiée seule, d'après les signatures des méthodes qu'elle appelle.
- Le code hors `@NullMarked` n'est pas vérifié (option `OnlyNullMarked`). Un package oublié compile donc sans erreur, et sans vérification.

### Les bibliothèques

Trois cas, selon ce que la bibliothèque déclare :

| Bibliothèque | Ce que NullAway en sait | Ce qu'on fait |
|---|---|---|
| Annotée JSpecify (Spring 7, JSpecify) | la vraie nullité de chaque paramètre et retour | rien : NullAway applique ses annotations |
| Non annotée (Hibernate, SDK AWS, Tika) | rien : tout est supposé non nul | lire sa documentation ; un retour qui peut être null va dans une variable `@Nullable`, puis un `if` |
| Le JDK | quelques modèles intégrés à NullAway | pareil que pour une bibliothèque non annotée |

Java n'a pas d'opérateur de navigation sûre (`a?.b`) : on écrit le test, ou on passe par `Optional.ofNullable(valeur).map(...)` quand la chaîne est longue.

## Implémentation avec Spring

Ce qu'on va créer :

```
pom.xml                         Spotless, Error Prone, NullAway, JSpecify (étapes 1, 4, 5)
.editorconfig                   le même format dans l'IDE (étape 2)
.git-blame-ignore-revs          le commit de reformatage (étape 3)
.mvn/jvm.config                 les accès de javac pour Error Prone (étape 5)
lombok.config                   le code Lombok exclu de NullAway (étape 6)
src/main/java/**/package-info.java   @NullMarked (étape 7)
```

### Le formatage

**Étape 1. Brancher Spotless sur `verify`.** Le but `check` s'exécute par défaut dans la phase `verify` : `./mvnw verify` échoue sur un fichier mal formaté.

```xml
<plugin>
    <groupId>com.diffplug.spotless</groupId>
    <artifactId>spotless-maven-plugin</artifactId>
    <version>${spotless.version}</version>
    <configuration>
        <java>
            <includes>
                <include>src/main/java/**/*.java</include>
                <include>src/test/java/**/*.java</include>
            </includes>
            <googleJavaFormat>
                <version>${google-java-format.version}</version>
                <style>AOSP</style>
            </googleJavaFormat>
            <removeUnusedImports/>
            <forbidWildcardImports/>
        </java>
    </configuration>
    <executions>
        <execution>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
</plugin>
```

- `AOSP` donne 4 espaces d'indentation au lieu de 2. La largeur reste 100 colonnes, sans réglage possible.
- `forbidWildcardImports` refuse les `import ...*` : google-java-format ne les développe pas.
- On épingle la version du formateur : une nouvelle version peut reformater des fichiers déjà conformes.

**Étape 2. Donner le même format à l'IDE.** `.editorconfig` aligne l'indentation et la largeur, et empêche IntelliJ de regrouper les imports en `*` :

```ini
[*.java]
indent_style = space
indent_size = 4
max_line_length = 100
ij_java_class_count_to_use_import_on_demand = 999
ij_java_names_count_to_use_import_on_demand = 999
```

**Étape 3. Reformater une fois, puis cacher ce commit à `git blame`.** Avant le reformatage, on développe les imports `*` et on rejoint les longs commentaires `//` (voir les pièges). Ensuite, un commit qui ne contient que le reformatage :

```bash
./mvnw spotless:apply
git commit -am "style: reformat with google-java-format"
git rev-parse HEAD >> .git-blame-ignore-revs     # avec un commentaire au-dessus
git config blame.ignoreRevsFile .git-blame-ignore-revs   # une fois par clone
```

GitHub lit `.git-blame-ignore-revs` tout seul. En local, chaque clone doit le configurer. Le commit doit arriver par un merge, jamais par un squash, sinon son SHA disparaît.

### La nullité

**Étape 4. Ajouter JSpecify.** La dépendance porte les annotations. Spring Boot gère sa version :

```xml
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
</dependency>
```

**Étape 5. Lancer NullAway dans `javac`.** Error Prone et NullAway passent par les processeurs d'annotations du compilateur, après Lombok :

```xml
<compilerArgs>
    <arg>-XDcompilePolicy=simple</arg>
    <arg>--should-stop=ifError=FLOW</arg>
    <arg>-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR
        -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:JSpecifyMode=true
        -XepOpt:NullAway:ExternalInitAnnotations=jakarta.persistence.Entity,
            jakarta.persistence.MappedSuperclass
        -XepExcludedPaths:.*/(src/test|target/generated-[a-z-]+)/.*</arg>
</compilerArgs>
<annotationProcessorPaths>
    <path><!-- lombok --></path>
    <path>
        <groupId>com.google.errorprone</groupId>
        <artifactId>error_prone_core</artifactId>
        <version>${error-prone.version}</version>
    </path>
    <path>
        <groupId>com.uber.nullaway</groupId>
        <artifactId>nullaway</artifactId>
        <version>${nullaway.version}</version>
    </path>
</annotationProcessorPaths>
```

Dans le pom, l'argument `-Xplugin:ErrorProne ...` tient sur une seule ligne, sans espace dans la liste des annotations : `javac` le reçoit comme un seul argument. Il est coupé ici pour la lecture.

| Option | Rôle |
|---|---|
| `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW` | exigées par Error Prone, qui analyse après l'étape FLOW de `javac` |
| `-XepDisableAllChecks -Xep:NullAway:ERROR` | NullAway seul, en erreur ; les autres vérifications d'Error Prone restent coupées |
| `OnlyNullMarked=true` | ne vérifie que le code `@NullMarked` |
| `JSpecifyMode=true` | lit `@Nullable` dans les génériques (`List<@Nullable UUID>`) et les tableaux |
| `ExternalInitAnnotations` | les entités sont remplies par Hibernate, pas par leur constructeur |
| `-XepExcludedPaths` | ni les tests, ni le code généré (MapStruct, configuration) |

Depuis Java 16, `jdk.compiler` n'ouvre plus ses packages internes, dont Error Prone a besoin. Maven lit `.mvn/jvm.config` au lancement :

```
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
...
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

**Étape 6. Exclure le code Lombok.** Lombok marque ce qu'il génère de `@Generated`, et NullAway l'ignore :

```properties
# lombok.config
config.stopBubbling = true
lombok.addLombokGeneratedAnnotation = true
```

Lombok copie le `@Nullable` d'un champ sur son getter et son setter : `private @Nullable Instant dueAt;` donne `@Nullable Instant getDueAt()`.

**Étape 7. Marquer chaque package.** Un `package-info.java` par package :

```java
@NullMarked
package io.example.invoices.dtos;

import org.jspecify.annotations.NullMarked;
```

**Étape 8. Annoter ce qui peut être null, et seulement cela.** Les cas courants :

```java
// entité : une colonne facultative
@Column(name = "due_at")
private @Nullable Instant dueAt;

// DTO : un champ absent de certaines réponses
public record SecretDto(String secret, @Nullable Instant previousSecretExpiresAt) {}

// recherche qui peut ne rien trouver
public @Nullable UUID currentAssigneeId() { ... }

// valeur jamais nulle ici, mais NullAway ne peut pas le voir
String destination = exchange != null ? exchange : queue;
return Objects.requireNonNull(destination, "every message has a queue or an exchange");
```

- `@Nullable` est une annotation de type : elle se place juste avant le type. Pour un tableau, `String @Nullable []` ; pour un type qualifié, `Map.@Nullable Entry`.
- `Objects.requireNonNull` avec un message dit pourquoi la valeur ne peut pas être nulle, et échoue clairement si on se trompe.
- `@SuppressWarnings("NullAway")` reste le dernier recours, avec la raison en commentaire.

**Étape 9. Vérifier.** Une déréférence volontaire doit casser la compilation :

```bash
./mvnw spotless:check       # quelques secondes
./mvnw compile              # NullAway : erreur sur une déréférence ajoutée exprès
```

```
[ERROR] InvoiceService.java:[42,20] [NullAway] dereferenced expression invoice.getDueAt() is @Nullable
```

### Référence rapide

Les messages de NullAway :

| Message | Cause | Correction |
|---|---|---|
| `dereferenced expression ... is @Nullable` | appel sur une valeur peut-être nulle | un test, une variable locale, ou `requireNonNull` |
| `returning @Nullable expression from method with @NonNull return type` | retour d'une valeur peut-être nulle | annoter le retour, ou garantir la valeur |
| `passing @Nullable parameter ... where @NonNull is required` | argument peut-être nul | tester avant l'appel, ou annoter le paramètre |
| `@NonNull field ... not initialized` | champ sans valeur après le constructeur | l'initialiser, ou l'annoter `@Nullable` |
| `parameter ... is @NonNull, but parameter in superclass method ... is @Nullable` | redéfinition plus stricte que la signature de Spring | accepter `@Nullable` et traiter le cas |

Limites de cette version : NullAway est volontairement incomplet. Il fait confiance aux bibliothèques non annotées, ne suit pas une valeur à travers un champ modifié par un autre thread, et ne vérifie pas les tests.

## Concepts avancés

### Sous le capot : une analyse locale

NullAway vérifie chaque méthode d'après les signatures de celles qu'elle appelle, sans lire leur corps. C'est ce qui le rend rapide, et c'est pourquoi les annotations des signatures comptent : une méthode qui retourne null sans `@Nullable` trompe tous ses appelants. Un test sur un champ (`if (this.media != null)`) suffit à NullAway dans la même méthode, mais un autre thread peut changer le champ entre le test et l'usage : une copie dans une variable locale reste sûre jusqu'au bout.

### Les génériques de Spring 7

En mode JSpecify, NullAway lit la nullité des paramètres de type. `JdbcTemplate.queryForList(sql, params, UUID.class)` retourne `List<@Nullable UUID>`, car une ligne peut porter `NULL`. Pour une colonne `NOT NULL`, on passe un `RowMapper` : `query(sql, params, (row, n) -> row.getObject(1, UUID.class))` retourne une `List<UUID>` (guide 13).

### Les beans créés tôt

Un `BeanFactoryPostProcessor` est créé avant toute injection. Un champ rempli par `EnvironmentAware` reste vide jusqu'à l'appel du setter, et NullAway refuse ce champ non initialisé. On passe l'`Environment` en paramètre de la méthode `@Bean` statique, puis au constructeur (guide 02).

### Surveiller le coût

Le formatage ajoute quelques secondes à la CI, en tête du job de tests. NullAway ajoute quelques secondes à chaque compilation. Les autres vérifications d'Error Prone restent coupées : elles ajoutent environ 10 s pour des alertes de peu de valeur sur ce code.

## Pièges à éviter

### Le formatage

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Laisser un commentaire `//` de plus de 100 colonnes | le formateur le coupe en fragments sans les rejoindre à la ligne suivante | Écrire dans les 100 colonnes, ou rejoindre le paragraphe avant le reformatage |
| Laisser des imports `*` | `forbidWildcardImports` échoue, et le formateur ne les développe pas | Les développer à la main, régler l'IDE à 999 |
| Monter la version du formateur sans essai | un nouveau format sur tout le dépôt, ou une erreur dans Spotless | Épingler la version, la monter dans sa propre pull request |
| Squasher le commit de reformatage | son SHA disparaît, `git blame` ne peut plus l'ignorer | Un commit de merge |
| Mélanger reformatage et changements | une revue impossible | Un commit qui ne fait que reformater |

### La nullité

| Piège | Ce qui se passe | Parade |
|---|---|---|
| Oublier un `package-info.java` | le package compile sans aucune vérification | Un test garde-fou qui exige `@NullMarked` partout |
| Annoter `@Nullable` partout pour faire taire NullAway | les tests de null se multiplient et la vérification ne prouve plus rien | `@Nullable` seulement là où null est un vrai cas |
| Croire une bibliothèque non annotée | NullAway la suppose non nulle : un retour null passe | Variable `@Nullable` et test, d'après sa documentation |
| `queryForList(..., X.class)` vers une `List<X>` | erreur de NullAway sur `List<@Nullable X>` | Un `RowMapper` pour une colonne `NOT NULL` |
| `@SuppressWarnings("NullAway")` par habitude | un vrai défaut passe inaperçu | Rendre la preuve visible, ou `requireNonNull` avec la raison |
| Oublier `.mvn/jvm.config` dans l'image Docker | `./mvnw verify` passe, le build de l'image échoue (`IllegalAccessError`) | Le réautoriser dans `.dockerignore` (guide 33) |

## En résumé

**À retenir :** un formateur sans réglage, appliqué avant chaque commit et vérifié par le build. Un code où rien n'est null sauf ce qui porte `@Nullable`, vérifié par NullAway à chaque compilation, avec les annotations de Spring 7 en prime.

- [ ] Spotless et google-java-format, version épinglée, `check` dans `verify`
- [ ] Une étape `spotless:check` en tête de la CI
- [ ] `.editorconfig` aligné sur le formateur
- [ ] Le commit de reformatage seul, dans `.git-blame-ignore-revs`
- [ ] JSpecify, Error Prone et NullAway dans le compilateur, tests exclus
- [ ] `.mvn/jvm.config` pour les accès de `javac`
- [ ] `lombok.addLombokGeneratedAnnotation = true`
- [ ] `@NullMarked` sur chaque package, gardé par un test
- [ ] `@Nullable` seulement où null est un vrai cas

## Dans ce dépôt

| Fichier | Rôle |
|---|---|
| [`pom.xml`][pom] | Spotless 3.10.4, google-java-format 1.36.1, Error Prone 2.50.0, NullAway 0.14.2, JSpecify |
| [`.editorconfig`][editorconfig] | 4 espaces, 100 colonnes, LF, seuils d'import à 999 |
| [`.git-blame-ignore-revs`][blame] | le commit `style: reformat with google-java-format` |
| [`.mvn/jvm.config`][jvm-config] | les `--add-exports` et `--add-opens` de `jdk.compiler` |
| [`lombok.config`][lombok] | `addLombokGeneratedAnnotation` |
| [`ci.yml`][ci] | l'étape « Check formatting » avant « Build and test » |
| [`NullMarkedPackagesTest`][test] | échoue si un package de `src/main/java` n'a pas de `package-info.java` `@NullMarked` |

Ce qui est propre au dépôt :

- 65 packages `@NullMarked` et environ 210 `@Nullable` : colonnes facultatives des entités, champs absents de certaines réponses, paramètres facultatifs, recherches.
- L'arrivée de NullAway n'a demandé aucune suppression et n'a révélé aucun défaut réel. Elle a forcé des preuves explicites : `Objects.requireNonNull` avec une raison (`OutboxMessage`, `SlackMessages`, `DatabaseUserDetailsService`), des `RowMapper` à la place de `queryForList`, et l'`Environment` passé au constructeur de `RequiredPropertiesCheck`.
- Coût mesuré : `./mvnw compile` passe de 10 à 14 s à environ 14,5 à 16 s.
- Avant le reformatage, un script a rejoint 265 blocs de commentaires et les imports `*` ont été développés. Un contrôle par jetons a montré un code identique, à 5 imports inutilisés près ; un second `spotless:apply` ne change rien.
- google-java-format 1.37.0 échoue dans Spotless 3.10.4 (`InvocationTargetException`) : le pom épingle 1.36.1 et le dit.
- `.dockerignore` réautorise `.mvn/jvm.config` et `lombok.config` : le build de l'image compile avec NullAway lui aussi.
- `AGENTS.md` porte les règles (sections Layout et Nullness), et `NullMarkedPackagesTest` suit le principe des tests garde-fous du guide 31 : il lit `src/main/java` sans démarrer Spring.

## Voir aussi

- [33. Image Docker, build et CI](33-docker-build-ci.md) : le pipeline où tournent ces contrôles, et la liste blanche de l'image.
- [31. Outils de test et tests garde-fous](31-outils-tests-garde-fous.md) : le principe de `NullMarkedPackagesTest`.
- [13. SQL avec JdbcTemplate](13-sql-jdbctemplate.md) : `query` avec un `RowMapper` au lieu de `queryForList`.
- [02. Profil de production](02-profil-production.md) : un post-processeur qui reçoit l'`Environment` par son constructeur.
- [12. Mapping Hibernate 7](12-mapping-hibernate.md) : les colonnes facultatives des entités.
- Documentation officielle : [JSpecify](https://jspecify.dev/docs/user-guide/), [NullAway](https://github.com/uber/NullAway/wiki), [nullité dans Spring](https://docs.spring.io/spring-framework/reference/core/null-safety.html), [Spotless pour Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven), [google-java-format](https://github.com/google/google-java-format).

[Sommaire](README.md) | [< 33. Image Docker, build et CI](33-docker-build-ci.md)

[pom]: ../../pom.xml
[editorconfig]: ../../.editorconfig
[blame]: ../../.git-blame-ignore-revs
[jvm-config]: ../../.mvn/jvm.config
[lombok]: ../../lombok.config
[ci]: ../../.github/workflows/ci.yml
[test]: ../../src/test/java/io/julienmetral/tasks/NullMarkedPackagesTest.java
