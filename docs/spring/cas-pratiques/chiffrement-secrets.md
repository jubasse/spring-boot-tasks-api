# Cas pratique : Le chiffrement des secrets de webhook

[Sommaire](../README.md)

## Le scénario

Dana déclare un webhook. Elle reçoit un secret, avec lequel son serveur vérifie la signature de chaque notification. Quelques mois plus tard, elle remplace ce secret sans rien perdre pendant la bascule. Elle ajoute aussi un canal Slack, dont l'URL est elle-même un secret. La base ne doit contenir aucun de ces secrets en clair.

## Vue d'ensemble

Le chemin d'un secret, de sa création à la signature d'une livraison :

```
 POST /api/v1/users/{id}/webhooks
      |
      v
 WebhookSecrets.generate          "whsec_" + Base64(32 octets aléatoires)
      |
      +--> réponse 201 : le secret en clair, montré une seule fois
      |
      v
 WebhookSecrets.encrypt           AES-256-GCM, clé WEBHOOK_ENCRYPTION_KEY
      |
      v
 webhook_endpoints.secret         "v1:" + Base64(IV | chiffré | tag)
      |
      |  à chaque tentative de livraison
      v
 WebhookSecrets.decrypt           secret actuel, et le précédent s'il est
      |                           encore valable
      v
 WebhookSigner                    HMAC-SHA256 de "id.timestamp.corps",
      |                           une signature par secret
      v
 en-tête webhook-signature: v1,<sig> [v1,<sig du précédent>]
```

- Chiffrer est réversible avec la clé. Hacher ne l'est pas. Les jetons opaques sont seulement hachés en SHA-256 (guide 09), car le serveur compare sans jamais les relire. Un secret de signature, lui, sert à calculer un HMAC à chaque livraison : il faut le retrouver, donc le chiffrer.
- La clé de chiffrement vit hors de la base, dans la configuration. Une copie de la base seule ne suffit donc pas à lire les secrets.

## Pas à pas

**1. La création.** [`WebhookEndpointService.create`][service] appelle `WebhookSecrets.generate`. Le secret suit le format Standard Webhooks : `whsec_`, puis le Base64 de 32 octets tirés par `SecureRandom`. Le récepteur décode la partie après le préfixe pour obtenir sa clé HMAC.

**2. Montré une seule fois.** La réponse 201 porte le secret en clair (`NewWebhookEndpointDto`). Aucune lecture ultérieure ne le renvoie : la liste et le détail des webhooks n'ont pas de champ secret. Un secret perdu se remplace par une rotation.

**3. Le chiffrement.** [`WebhookSecrets`][secrets] délègue à `AesGcmBytesEncryptor` de Spring Security (`spring-security-crypto`). Chaque chiffrement tire un IV aléatoire de 16 octets. Le résultat est l'IV, suivi du texte chiffré et d'une étiquette d'authentification de 16 octets :

```
 "v1:" + Base64( IV 16 octets | texte chiffré | tag GCM 16 octets )
```

- L'IV aléatoire donne deux valeurs différentes pour le même secret chiffré deux fois. Aucun motif ne se répète dans la base.
- GCM authentifie en plus de chiffrer. Une valeur modifiée ou tronquée échoue au déchiffrement au lieu de produire un faux secret.
- Le préfixe `v1:` nomme la clé qui a chiffré. Une valeur sans préfixe, ou avec un préfixe inconnu, est refusée.

**4. La clé.** Le bean est créé dans [`WebhookConfiguration`][configuration] à partir de `webhooks.encryption-key` (`WEBHOOK_ENCRYPTION_KEY`). Le constructeur exige le Base64 d'au moins 32 octets, et n'en garde que les 32 premiers pour AES-256. Une clé absente, trop courte ou mal encodée fait échouer le démarrage avec la commande pour en générer une : `openssl rand -base64 32`.

- En production, la clé figure dans `deployment.required-properties` : son absence arrête le démarrage avant la création des beans (guide 02).
- En développement, elle vient du `.env`, jamais commité (guide 01).
- Les tests ont leur propre clé dans `src/test/resources/config/application.yaml`.
- Le build de l'image lui donne une valeur jetable pendant l'entraînement AOT (guide 33).

**5. La signature d'une livraison.** À chaque tentative, [`WebhookDeliveryService`][delivery] déchiffre le secret actuel, et le précédent tant qu'il est valable. [`WebhookSigner`][signer] calcule un HMAC-SHA256 de `id.timestamp.corps` par secret. Il les place tous dans l'en-tête `webhook-signature`, séparés par une espace. Les secrets en clair ne vivent qu'en mémoire, le temps de la tentative.

**6. La rotation.** `POST /api/v1/users/{id}/webhooks/{webhookId}/secret` génère un nouveau secret et le renvoie une seule fois. L'ancien, toujours chiffré, passe dans `previous_secret`, avec `previous_secret_expires_at` fixé à 24 h (`webhooks.previous-secret-validity`). Pendant ces 24 h, chaque livraison porte deux signatures. Le récepteur qui n'a pas encore changé de secret en valide une, celui qui a changé valide l'autre. Passé ce délai, l'ancien secret n'est plus lu.

**7. Le canal Slack.** Un webhook Slack ne signe rien : son URL `https://hooks.slack.com/services/...` est elle-même le secret. Elle est chiffrée avec la même clé. Les réponses montrent seulement `WebhookUrls.SLACK_MASK` (`https://hooks.slack.com/services/****`), et elle n'est déchiffrée qu'au moment d'envoyer. Une rotation sur un webhook Slack répond 422 `webhook-not-signed`.

**8. Rien dans les journaux.** Le message d'une exception du client HTTP contient l'URL, donc la clé Slack. Il n'est jamais journalisé ni stocké : `last_error` garde un type court comme `Timeout` (cas pratique livraison-webhook).

## Quand ça se passe mal

| Situation | Ce qui se passe | Comment s'en sortir |
|---|---|---|
| Clé absente ou trop courte | le bean `WebhookSecrets` refuse de se créer | générer une clé, la définir, redémarrer |
| Clé changée en production | les valeurs `v1:` ne se déchiffrent plus, les livraisons échouent | remettre l'ancienne clé, ou recréer les webhooks |
| Rotation juste après un changement de clé | l'ancien chiffré illisible devient `previous_secret` pour 24 h | attendre 24 h, ou recréer le webhook |
| Valeur modifiée directement en base | le tag GCM ne correspond plus | déchiffrement refusé, aucun faux secret utilisé |
| Copie de la base volée | les secrets restent chiffrés | la clé, hors de la base, reste nécessaire |
| Secret perdu par le client | il n'est plus affiché | faire une rotation |

Trois limites assumées dans cette version :

- Une seule clé. Le préfixe `v1:` prépare une rotation de clé, mais aucun code ne lit encore de `v2:` ni ne rechiffre les valeurs existantes.
- Pas de données associées dans le chiffrement GCM : une valeur copiée d'une ligne à une autre se déchiffre encore. Seul un accès en écriture à la base permet cette copie.
- La clé est une variable d'environnement, pas un service de gestion de clés (KMS), qui ajouterait l'audit des accès et la rotation gérée.

## À retenir

- Hacher ce qu'on compare, chiffrer ce qu'on doit relire.
- AES-GCM avec un IV aléatoire : deux chiffrements diffèrent, et une valeur altérée est refusée.
- La clé vit hors de la base, et l'application refuse de démarrer sans elle.
- Le secret en clair n'apparaît qu'une fois, dans la réponse de création ou de rotation.
- Pendant une rotation, deux signatures par livraison : le récepteur bascule sans rien perdre.

## Voir aussi

- [09. Mots de passe et jetons opaques](../09-mots-de-passe-jetons.md) : ce qui est haché, et pourquoi.
- [01. Configuration et `@ConfigurationProperties`](../01-configuration-proprietes.md) : la clé lue depuis `.env`.
- [02. Profil de production](../02-profil-production.md) : les réglages obligatoires vérifiés au démarrage.
- [26. Clients HTTP et filtre SSRF](../26-clients-http-ssrf.md) : le client qui envoie les livraisons signées.
- [Cas pratique : La livraison d'un webhook](livraison-webhook.md) : une livraison de bout en bout.
- [README du dépôt, « Check the signature »](../../../README.md#check-the-signature) : la vérification côté récepteur.
- Documentation officielle : [chiffrement dans Spring Security](https://docs.spring.io/spring-security/reference/features/integrations/cryptography.html), [Standard Webhooks](https://www.standardwebhooks.com/).

[service]: ../../../src/main/java/io/julienmetral/tasks/notification/services/WebhookEndpointService.java
[secrets]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookSecrets.java
[configuration]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookConfiguration.java
[delivery]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryService.java
[signer]: ../../../src/main/java/io/julienmetral/tasks/notification/webhook/WebhookSigner.java
