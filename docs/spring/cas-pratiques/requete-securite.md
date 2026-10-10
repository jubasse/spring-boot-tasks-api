# Cas pratique : Une requête à travers la sécurité

[Sommaire](../README.md)

## Le scénario

Alice est assignée à une tâche. Son application envoie `PATCH /api/v1/tasks/{id}` avec son jeton d'accès et le corps `{"title": "Relire le contrat"}` (un `PATCH` ne porte que les champs à changer). La requête doit traverser l'authentification, deux contrôles d'autorisation et une transaction. Elle doit répondre 200 avec la tâche à jour.

## Vue d'ensemble

Le trajet, avec la sortie de chaque refus :

```
 Client : PATCH /api/v1/tasks/{id}, Authorization: Bearer <jeton>
      |
      v
 +----------------- chaîne de filtres Spring Security -------------------+
 |                                                                       |
 |  BearerTokenAuthenticationFilter                                      |
 |    JwtDecoder, puis JwtAuthenticationConverter --- invalide --> 401   |
 |                      |                                                |
 |                      v                                                |
 |  AuthorizationFilter, règle /api/*/tasks/**                           |
 |    ActiveUserAuthorizationManager --------------- inactif ---> 403    |
 |                                                                       |
 +-----------------------------------------------------------------------+
      |
      v
 DispatcherServlet : version lue dans /api/v1 ------ inconnue ---> 400 *
      |
      v
 arguments de updateTask : corps lu et validé ------ invalide ---> 400 *
      |
      v
 proxy Spring de sécurité : @AllowedRolesOrAssignedToOnly - refus -> 403
      |
      v
 proxy Spring transactionnel : début de la transaction
      |
      v
 TaskService.update ------------------------ tâche absente (admin) --> 404 *
      |
      v
 commit : UPDATE ... WHERE version = ? ------------- conflit ----> 409 *
      |
      v
 TaskController : 200, TaskResponseDto

 * problème RFC 9457 écrit par ApiExceptionHandler ; les autres refus
   viennent de Spring Security, sans corps
```

- Le corps est lu et validé avant l'appel de la méthode, donc avant l'annotation de sécurité.
- Les écritures tiennent dans une transaction, terminée avant que le contrôleur construise la réponse.

## Pas à pas

**1. La chaîne de filtres.** Tomcat passe la requête à la chaîne déclarée dans [`SecurityConfiguration`][security-config]. Elle est sans session (`STATELESS`), sans CSRF, sans formulaire ni authentification Basic. Chaque requête prouve donc son identité par son jeton (guide 07).

**2. Le jeton.** `BearerTokenAuthenticationFilter` lit l'en-tête `Authorization` par le bean `bearerTokenResolver`, qui l'ignore sur les endpoints publics. Un client qui garde un jeton expiré peut ainsi encore se connecter ou se rafraîchir. Le `JwtDecoder` de [`JwtConfiguration`][jwt-config] vérifie la signature HS256 avec la clé secrète, puis l'expiration (15 min, avec 60 s de tolérance) et l'émetteur `tasks-api`. Le `JwtAuthenticationConverter` transforme la revendication `roles` (`["ROLE_USER"]`) en autorités, avec un préfixe vide, puisque les valeurs portent déjà `ROLE_`. Le `JwtAuthenticationToken` obtenu est rangé dans le contexte de sécurité, sans accès à la base (guide 07).

**3. Le compte actif.** `AuthorizationFilter` applique la règle `/api/*/tasks/**`, écrite pour toutes les versions, qui délègue à [`ActiveUserAuthorizationManager`][active-manager]. Il lit l'id d'Alice dans la revendication `uid` ([`CurrentUser`][current-user]). Il demande son statut à [`UserStatusLookup`][status-lookup], qui passe par le cache Caffeine `userStatus` (30 s après écriture). Sans entrée, il lance une requête légère sur `enabled` et `email_verified_at`. Seul `ACTIVE` passe : compte activé, non supprimé, email vérifié. Un jeton reste valide jusqu'à son expiration, même après une désactivation. [`UserStatusCacheEviction`][status-eviction] compense en retirant l'entrée après le commit de chaque changement de compte (guides 08 et 17).

**4. La version.** Le `DispatcherServlet` cherche la méthode. [`ApiVersioningConfiguration`][versioning] a placé `/api/v{version}` devant les contrôleurs, qui déclarent seulement `/tasks`. Spring MVC lit la version dans le deuxième segment. [`PathVersionParser`][version-parser] n'en accepte qu'une écriture : `/api/v1.0/` est refusé au lieu d'être servi comme la version 1, hors des règles écrites pour `/api/v1/`. La requête aboutit à `TaskController.updateTask` (guide 04).

**5. Le corps.** Avant d'appeler la méthode, Spring résout ses arguments. L'id du chemin devient un `UUID`, et Jackson 3 lit le corps dans le record [`UpdateTaskDto`][update-dto]. Son constructeur compact retire les espaces et remplace un texte vide par `null`. `@Valid` lance Bean Validation, ici `@Size(max = 255)` sur le titre. Un corps invalide répond donc 400, même à quelqu'un qui n'est pas assigné (guides 03 et 05).

**6. La règle « propriétaire ».** Avec `@EnableMethodSecurity`, le contrôleur est enveloppé par un proxy Spring. L'annotation maison [`@AllowedRolesOrAssignedToOnly(UserRole.ADMIN)`][assigned-annotation] porte un `@PreAuthorize` dont le gabarit `{value}` devient `'ADMIN'` :

```
hasAnyRole('ADMIN')
  or @taskAuthorization.currentUserIsAssignedTo(#id, authentication)
```

Alice n'a pas le rôle `ADMIN`, donc le bean [`TaskAuthorization`][task-authorization] répond par une requête `existsByIdAndAssignedToId`. L'expression lit `#id`, d'où le nom imposé de la variable de chemin (guide 08).

**7. La transaction.** [`TaskService.update`][task-service] est `@Transactional` : un second proxy Spring ouvre la transaction. Le service charge la tâche avec son assigné, son créateur et leurs photos (graphe d'entités `LOAD`). Il change seulement les champs non nuls, puis appelle `TaskEventService.updated`, en propagation `MANDATORY`. Une ligne `UPDATED` s'ajoute à l'historique. L'événement `TaskEventRecorded` fait écrire par [`RealtimeBroadcasts`][broadcasts] un message pour la salle de la tâche dans l'outbox, toujours dans la même transaction. Il n'y a aucun `save()` : Hibernate repère seul les champs modifiés (dirty checking). Au commit, il envoie un `UPDATE` qui augmente `version` de 1, avec la condition `WHERE id = ? AND version = ?` sur la version lue (`@Version` sur [`Task`][task]). Après le commit, le relais de l'outbox publie le message (guides 11, 12, 14 et 20).

**8. La réponse.** De retour dans le contrôleur, la transaction est terminée et la session Hibernate fermée (`open-in-view: false`). Le record [`TaskResponseDto`][response-dto] ne lit que ce que le graphe d'entités a chargé, plus l'URL de chaque photo (`MediaUrls`). La réponse 200 porte la nouvelle `version` (guides 03 et 11).

## Quand ça se passe mal

Chaque refus sort à une étape précise :

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Jeton expiré | le `JwtDecoder` le refuse (étape 2) | 401 sans corps ; le client appelle `/auth/refresh` |
| Compte désactivé, jeton encore valide | le gestionnaire lit `DISABLED` (étape 3) | 403 sans corps ; le service ne tourne jamais |
| Alice n'est plus l'assignée | le bean répond `false` (étape 6) | 403 sans corps, rien n'est écrit |
| Tâche inconnue, pour Alice | le bean répond `false` (étape 6) | 403 : la réponse ne révèle pas si la tâche existe |
| Tâche inconnue, pour un admin | `TaskNotFoundException` dans le service | 404, problème `about:blank` |
| Titre de 300 caractères | Bean Validation échoue (étape 5) | 400 `validation-error`, `errors[0].pointer` vaut `#/title` |
| Priorité inconnue (`"CRITICAL"`) | Jackson ne lit pas la valeur (étape 5) | 400 `validation-error` qui pointe `#/priority` |
| Mise à jour concurrente validée avant | l'`UPDATE` ne touche aucune ligne | rollback de tout, 409 `version-conflict` ; le client recharge |
| Chemin `/api/v2/tasks/{id}` | aucune version 2 n'est supportée (étape 4) | 400 « This API version is not supported: use /api/v1 » |

Deux composants écrivent ces réponses :

| Origine | Quand | Forme |
|---|---|---|
| Point d'entrée bearer de Spring Security | jeton absent ou refusé | 401, `WWW-Authenticate: Bearer ...`, sans corps |
| Gestionnaire d'accès refusé de Spring Security | règle d'URL ou `@PreAuthorize` refusée | 403, `WWW-Authenticate: Bearer ...`, sans corps |
| [`ApiExceptionHandler`][exception-handler] | exception dans Spring MVC : validation, service, commit | problème RFC 9457 |

- `@PreAuthorize` lève `AccessDeniedException` pendant l'appel du contrôleur, donc à l'intérieur de Spring MVC. `ApiExceptionHandler.handleUnexpected` la voit passer et la relance : `ExceptionTranslationFilter`, plus haut dans la chaîne, répond alors 403. L'attraper ferait de chaque 403 un 500.
- Le conflit de version annule aussi la ligne d'historique et le message de la salle : rien n'a été publié, puisque l'outbox attend le commit.
- Le client n'envoie pas de version : le 409 protège de deux requêtes simultanées, pas d'un écrasement d'un changement jamais lu.
- Les types de problèmes sont décrits dans [`docs/problems.md`](../../problems.md).

## À retenir

- Les filtres refusent avant Spring MVC, sans corps. Ce qui échoue dans Spring MVC ressort en problème RFC 9457.
- Deux contrôles se suivent : la règle d'URL (compte actif), puis l'annotation de la méthode (rôle ou propriétaire).
- Un jeton valide ne suffit pas : le statut du compte est relu à chaque requête, à travers un cache de 30 s vidé après chaque changement.
- Le corps est validé avant `@PreAuthorize`, et un utilisateur reçoit 403 plutôt que 404 sur une tâche qui n'est pas la sienne.
- Tout ce que la requête écrit, historique et outbox compris, part ou s'annule avec la tâche.

## Voir aussi

- [04. Versioning d'API](../04-versioning-api.md) : le préfixe `/api/v{version}` et l'analyseur de version.
- [05. Validation et erreurs](../05-validation-erreurs.md) : Bean Validation et les problèmes RFC 9457.
- [07. Chaîne de filtres et JWT](../07-securite-jwt.md) : le décodage du jeton et les endpoints publics.
- [08. Autorisations](../08-autorisations.md) : les règles par URL et les annotations maison.
- [12. Mapping Hibernate 7](../12-mapping-hibernate.md) : le verrou optimiste `@Version`.
- [14. Transactions](../14-transactions.md) : le proxy transactionnel et `MANDATORY`.
- [17. Cache Caffeine](../17-cache-caffeine.md) : le cache de statut et son éviction après le commit.
- [Une notification de bout en bout](notification-bout-en-bout.md) : ce qui se passe quand un admin assigne une tâche.

[security-config]: ../../../src/main/java/io/julienmetral/tasks/identity/security/SecurityConfiguration.java
[jwt-config]: ../../../src/main/java/io/julienmetral/tasks/identity/security/JwtConfiguration.java
[active-manager]: ../../../src/main/java/io/julienmetral/tasks/identity/security/ActiveUserAuthorizationManager.java
[current-user]: ../../../src/main/java/io/julienmetral/tasks/identity/security/CurrentUser.java
[status-lookup]: ../../../src/main/java/io/julienmetral/tasks/identity/security/UserStatusLookup.java
[status-eviction]: ../../../src/main/java/io/julienmetral/tasks/identity/security/UserStatusCacheEviction.java
[versioning]: ../../../src/main/java/io/julienmetral/tasks/config/ApiVersioningConfiguration.java
[version-parser]: ../../../src/main/java/io/julienmetral/tasks/config/PathVersionParser.java
[update-dto]: ../../../src/main/java/io/julienmetral/tasks/task/dtos/UpdateTaskDto.java
[assigned-annotation]: ../../../src/main/java/io/julienmetral/tasks/task/security/AllowedRolesOrAssignedToOnly.java
[task-authorization]: ../../../src/main/java/io/julienmetral/tasks/task/security/TaskAuthorization.java
[task-service]: ../../../src/main/java/io/julienmetral/tasks/task/services/TaskService.java
[broadcasts]: ../../../src/main/java/io/julienmetral/tasks/realtime/messaging/RealtimeBroadcasts.java
[task]: ../../../src/main/java/io/julienmetral/tasks/task/entities/Task.java
[response-dto]: ../../../src/main/java/io/julienmetral/tasks/task/dtos/TaskResponseDto.java
[exception-handler]: ../../../src/main/java/io/julienmetral/tasks/shared/exceptions/ApiExceptionHandler.java
