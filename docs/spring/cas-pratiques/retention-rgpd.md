# Cas pratique : La rétention RGPD d'un compte

[Sommaire](../README.md)

## Le scénario

Deux comptes quittent l'application. Un administrateur supprime celui de Bob. Carol, elle, ne se connecte plus depuis deux ans. Leurs données personnelles doivent disparaître après un délai connu, sans casser les tâches, les commentaires et l'historique qui les citent.

## Vue d'ensemble

Le chemin d'un compte, de son dernier usage à son effacement :

```
 Carol : dernière activité (connexion ou rafraîchissement du jeton)
      |
      |  2 ans sans activité          identity.retention.inactivity-period
      v
 run de 04:00 : avertissement     inactivity_warned_at posé, mail
      |
      +-- connexion avant l'échéance --> avertissement effacé, compte gardé
      |
      |  30 jours sans activité       identity.retention.deletion-notice
      v
 run de 04:00 : suppression       comme DELETE /api/v1/users/{id} :
      |                           deleted_at posé, sessions révoquées
      |
      |  <-- Bob arrive ici directement, supprimé par un administrateur
      |
      |  30 jours                     identity.retention.anonymize-after
      v
 run de 04:00 : effacement        profil "Deleted user", ligne users
                                  supprimée, email libéré
```

- Supprimer n'efface rien : `deleted_at` cache le compte, et ses données restent 30 jours. Effacer retire les données pour de bon.
- Le compte (`users`) et le profil (`user_profiles`) partagent le même id. L'effacement supprime le premier et garde le second, anonyme, pour ce qui le référence (guide 12).

## Pas à pas

**1. L'activité.** Un compte est actif quand il sert. `AuthService` appelle `User.markActive` à chaque connexion et à chaque rafraîchissement du jeton. La méthode pose `last_active_at` et efface un éventuel avertissement (`inactivity_warned_at = NULL`). Les anciennes lignes sans `last_active_at` se rabattent sur `last_login_at`, puis sur `created_at` (`COALESCE` dans [`UserRetentionQueries`][queries]).

**2. Le job.** [`UserRetentionJob`][job] tourne chaque jour à 04:00 (`identity.retention.cron`), sur une seule instance grâce au verrou ShedLock `user-retention` (guide 21). Il appelle [`UserRetentionService.apply`][service] et journalise les trois compteurs de [`UserRetentionReport`][report]. Les durées viennent de [`UserRetentionProperties`][properties], validées au démarrage (guide 01).

**3. Une transaction, trois passes.** `apply` lit l'heure du `Clock` injecté, puis enchaîne trois passes dans une seule transaction :

```
 apply()
   1. effacer    les comptes supprimés depuis plus de 30 jours
   2. supprimer  les comptes avertis depuis plus de 30 jours, encore inactifs
   3. avertir    les comptes inactifs depuis plus de 2 ans
```

L'ordre compte. Un compte supprimé à la passe 2 garde ses données 30 jours, comme Bob, et un run suivant l'efface. Un compte averti aujourd'hui n'est pas supprimé dans le même run.

**4. L'avertissement de Carol.** `warnUsersInactiveSince` est un seul `UPDATE ... RETURNING`. Il pose `inactivity_warned_at` sur les comptes non supprimés, pas encore avertis, sans activité depuis la date limite, et renvoie ceux qu'il a touchés. Le filtre `NOT_ADMIN` écarte les administrateurs : supprimer le dernier bloquerait toute l'administration. Pour chaque compte activé, le service publie `InactiveAccountWarned`. [`InactiveAccountEmailSender`][mail] écrit le mail dans l'outbox, donc il ne part qu'après le commit (guides 20 et 23). Il donne la date de suppression et invite à se connecter avant.

**5. Le compte désactivé.** Un compte désactivé est averti aussi, mais sans mail : il ne peut pas se connecter pour se garder. Il est supprimé après le même délai.

**6. La suppression de Carol.** Trente jours plus tard, `usersWarnedBefore` liste les comptes avertis avant la date limite, toujours sans le rôle ADMIN. Ce second filtre couvre un compte promu après l'avertissement. Une connexion entre-temps a effacé l'avertissement, donc le compte n'apparaît plus. Chaque id passe par `UserService.delete`, le même chemin qu'un `DELETE /api/v1/users/{id}` d'administrateur :

- verrou de la ligne du compte (`findByIdForUpdate`, guide 15) ;
- statut `DELETED` copié sur le profil, puis suppression logique (`deleted_at`, guide 12) ;
- `AccountStateChanged` publié : après le commit, le statut en cache est évincé, les autres instances sont prévenues, et les flux SSE et sessions STOMP du compte sont fermés (guides 17 et 24) ;
- tous les jetons de rafraîchissement révoqués (guide 09).

**7. Les 30 jours de Bob.** Le compte supprimé ne se connecte plus, et `@SoftDelete` le cache de toutes les requêtes JPA. Son profil reste visible avec le statut `DELETED`, sans photo (`UserProfile.visibleAvatar`). Son email reste pris : la contrainte unique `users_emailUQ` couvre aussi les lignes supprimées. Aucun endpoint ne restaure un compte supprimé.

**8. L'effacement.** `anonymizeUsersDeletedBefore` est du SQL natif, car JPA ne voit pas les lignes supprimées logiquement (guide 13). Il commence par un `UPDATE user_profiles ... FROM users ... RETURNING p.id` :

```sql
UPDATE user_profiles p
SET display_name = 'Deleted user', status = 'DELETED',
    avatar_media_id = NULL, pending_avatar_media_id = NULL,
    anonymized_at = :now, updated_at = :now
FROM users u
WHERE u.id = p.id AND u.deleted_at < :cutoff
RETURNING p.id
```

Pour les ids renvoyés, il supprime ensuite ce qui ne contenait que les données du compte : livraisons et abonnements des webhooks, webhooks, réglages de notification, les trois sortes de jetons, rôles. Il supprime enfin la ligne `users`, qui portait l'email, le hash du mot de passe et les dates d'activité.

**9. Ce qui reste.** Les tâches, commentaires, mentions et l'historique pointent vers `user_profiles`, jamais vers `users`. Ils se chargent toujours et affichent « Deleted user ». L'email est libre pour une nouvelle inscription, qui crée un autre compte avec un autre id. Un run suivant ne retouche pas ce profil : sa ligne `users` a disparu, donc la jointure ne le trouve plus.

**10. Les fichiers.** L'effacement détache les photos du profil, mais ne touche pas au stockage. [`MediaCleanupJob`][cleanup], chaque nuit à 03:30, purge les photos des comptes supprimés depuis 30 jours, puis les médias que plus rien ne référence, après un jour de grâce (guide 28).

## Quand ça se passe mal

| Situation | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Deux instances déclenchent le run | une seule prend le verrou `user-retention` | l'autre saute ce run |
| Instance arrêtée pendant le run | la transaction est annulée | le run du lendemain refait tout, rien n'est à moitié fait |
| Carol se connecte après l'avertissement | `markActive` efface `inactivity_warned_at` | `usersWarnedBefore` ne la trouve plus |
| Compte promu administrateur après l'avertissement | `NOT_ADMIN` est vérifié à nouveau | le compte est gardé |
| SMTP indisponible au moment du mail | le mail reste dans l'outbox ou la file | il part plus tard, l'échéance ne bouge pas |
| Run sauté un jour | les dates limites sont relatives à `now` | le run suivant rattrape tout |

Les trois passes sont des requêtes sur des dates limites, sans état intermédiaire. Relancer le run ne fait donc que ce qui reste à faire.

## À retenir

- Supprimer cache le compte, effacer retire ses données : 30 jours séparent les deux.
- Le compte et le profil sont deux lignes. On supprime le compte et on garde le profil, anonyme, pour ce qui le référence.
- Le SQL natif atteint les lignes que `@SoftDelete` cache à JPA.
- Toute activité efface l'avertissement, et les administrateurs ne sont jamais concernés.
- Un seul job, idempotent, verrouillé par ShedLock, avec ses durées en configuration.

## Voir aussi

- [09. Mots de passe et jetons](../09-mots-de-passe-jetons.md) : la révocation des jetons à la suppression.
- [12. Mapping Hibernate 7](../12-mapping-hibernate.md) : `@SoftDelete`, `@MapsId` et le sens de la clé entre compte et profil.
- [13. SQL avec JdbcTemplate](../13-sql-jdbctemplate.md) : `UPDATE ... RETURNING` et les lignes supprimées logiquement.
- [21. Jobs planifiés et ShedLock](../21-jobs-planifies-shedlock.md) : un run par jour, une instance à la fois.
- [Cas pratique : L'export RGPD et son PDF](export-rgpd-pdf.md) : l'autre droit du RGPD, l'accès aux données.
- [README du dépôt, « Background work »](../../../README.md#background-work) : les jobs et leurs réglages.

[job]: ../../../src/main/java/io/julienmetral/tasks/identity/services/UserRetentionJob.java
[service]: ../../../src/main/java/io/julienmetral/tasks/identity/services/UserRetentionService.java
[report]: ../../../src/main/java/io/julienmetral/tasks/identity/services/UserRetentionReport.java
[queries]: ../../../src/main/java/io/julienmetral/tasks/identity/repositories/UserRetentionQueries.java
[properties]: ../../../src/main/java/io/julienmetral/tasks/config/UserRetentionProperties.java
[mail]: ../../../src/main/java/io/julienmetral/tasks/identity/mail/InactiveAccountEmailSender.java
[cleanup]: ../../../src/main/java/io/julienmetral/tasks/media/services/MediaCleanupJob.java
