# Cas pratique : Pourquoi les contextes de test se multiplient

[Sommaire](../README.md)

## Le scénario

Une développeuse écrit un test d'intégration sur les délais du client de webhooks. Le récepteur doit répondre plus lentement que le délai de lecture, et les 15 s livrées par défaut feraient attendre le test. Elle est tentée d'ajouter `@TestPropertySource(properties = "spring.http.serviceclient.webhooks.read-timeout=2s")` sur la classe. Le test passerait, mais la suite complète durerait plus longtemps et prendrait plus de mémoire. Voici ce que cette ligne déclenche, puis ce que le dépôt fait à la place.

## Vue d'ensemble

Une propriété posée sur une seule classe change la clé du cache, et la classe obtient son propre contexte, avec ses conteneurs :

```
 classe WebhookTimeoutTests
   @IntegrationTest + @TestPropertySource(read-timeout=2s)
        |
        v
 clé de cache calculée (MergedContextConfiguration)
        |  différente de celle de @IntegrationTest
        v
 cache des contextes : absent ---------------------> nouveau contexte
        |                                                 |
        |                       +-------------------------+
        |                       v
        |          TestcontainersConfiguration
        |             RabbitMQ : un conteneur neuf (jamais réutilisé)
        |             Postgres, Mailpit, RustFS, ClamAV :
        |                réutilisation active --> repris
        |                sinon               --> 4 conteneurs neufs
        |                       |
        |                       v
        |          Liquibase sur la base partagée (rien à appliquer)
        |          listeners, relais d'outbox, pool de connexions
        |                       |
        v                       v
 cache : un contexte de plus, ouvert jusqu'à la fin de la JVM
        |
        +-- 12 contextes atteints --> le moins récent est fermé
                                      (son RabbitMQ s'arrête ;
                                       il redémarre si on le redemande)
```

## Pas à pas

**1. La clé change.** Pour chaque classe de test, Spring calcule une configuration fusionnée (`MergedContextConfiguration` : classes de configuration, propriétés, personnalisations comme `@MockitoBean`). `@TestPropertySource` ajoute une source de propriétés : la clé n'est plus celle que partagent les classes annotées `@IntegrationTest` seul, et le cache ne trouve rien. La Javadoc de [`IntegrationTest`][integration] le dit : ajouter une propriété, un mock ou un import à une seule classe crée un autre contexte (guide 29, table de la clé).

**2. Un contexte complet démarre.** Toute l'application redémarre : chaque bean, le pool de connexions, les listeners, et MockMvc. `@IntegrationTest` importe [`TestcontainersConfiguration`][containers], `Mailpit` et `TestClock` (guide 29).

**3. Ses conteneurs.** Spring Boot démarre les beans conteneurs du nouveau contexte, en parallèle (`spring.testcontainers.beans.startup: parallel` dans le [`application.yaml`][test-yaml] de test). RabbitMQ n'est jamais réutilisé : les tests de dead letter font échouer les listeners, et sur un broker partagé ces listeners consommeraient les messages des autres contextes. Un broker neuf démarre donc pour ce contexte. Postgres, Mailpit, RustFS et ClamAV sont retrouvés par le hash de leur définition quand la réutilisation est activée (`~/.testcontainers.properties` ou `TESTCONTAINERS_REUSE_ENABLE`, positionnée en CI) ; sinon quatre conteneurs de plus démarrent (guide 30).

**4. La base partagée.** Liquibase tourne au démarrage du contexte sur le Postgres commun, déjà migré : rien à appliquer (guide 18). Ce contexte écrit désormais des lignes validées dans la même base que tous les autres. D'où trois règles :

- chaque test prend des valeurs uniques et n'asserte jamais sur un total global ;
- les jobs planifiés sont éteints en test (`task.reminders.enabled`, `identity.retention.enabled`, `media.cleanup.enabled`, `exports.purge-enabled`, `exports.recovery-enabled`, `webhooks.retry-enabled` et `webhooks.purge-enabled`, tous à `false`) : un job actif dans un contexte agirait sur les données de tous les autres, et son verrou dans `scheduler_locks` ferait sauter le même job partout ailleurs ;
- le poller de l'outbox tourne toutes les heures (`messaging.outbox.poll-interval: PT1H`) : un poller fréquent publierait les lignes d'un autre contexte vers son propre broker, où personne ne les attend. Les messages partent quand même juste après leur commit, depuis le contexte qui les a écrits.

Un test qui sait partager la table des verrous : [`ScheduledJobLockingTests`][locking-tests], dont la ligne de purge des compteurs de limite est partagée avec tous les autres contextes (guides 20 et 21).

**5. Le plafond du cache.** [`spring.properties`][spring-properties] fixe `spring.test.context.cache.maxSize=12`. Au-delà, Spring ferme le contexte utilisé le moins récemment : ses beans sont détruits et Spring Boot arrête ses conteneurs non réutilisés (son RabbitMQ, et tous les autres sans réutilisation). Une classe qui le redemande le redémarre en entier. Le commentaire du fichier raconte pourquoi : le plafond par défaut de 32 avait gardé une dizaine de contextes complets en vie et épuisé la mémoire. Les contextes d'intégration (environ cinq) sont les lourds, alors que les slices ne tiennent qu'un Postgres.

**6. Ce que ça a coûté une fois.** Quand ClamAV chargeait sa base complète, chaque clamd prenait environ 1 Go, un par contexte de test, et une exécution complète a épuisé la mémoire de la machine. La base réduite à la signature EICAR ramène cela à environ 14 Mo. Pour la même raison, on ne lance jamais deux suites complètes en même temps sur une machine.

**7. La sortie : un réglage commun.** Le délai de lecture est allé dans le [`application.yaml`][test-yaml] de test, chargé par tous les contextes : `spring.http.serviceclient.webhooks.read-timeout: 2s`, avec le commentaire que [`WebhookDeliveryTests`][webhook-tests] répond plus lentement pour obtenir un `Timeout`, et que le réglage est là plutôt que sur la classe, qui démarrerait un autre contexte. Aucune nouvelle clé, aucun nouveau contexte.

**8. Les autres façons d'éviter un contexte.** Le dépôt applique la même idée ailleurs :

- un bean de test toujours présent plutôt qu'un mock : `TestClock` au lieu de `@MockitoBean Clock` (guide 31) ;
- un vrai outil plutôt qu'un mock : Mailpit lit les emails envoyés (guide 31) ;
- un réglage enregistré par propriété : `SqlStatementCounter` passe par `hibernate.session_factory.statement_inspector` (guide 31) ;
- une annotation de famille quand une configuration différente est inévitable : [`@DeadLetterIntegrationTest`][dead-letter] porte ses propriétés, son mock de `JavaMailSender` et son espion d'`ObjectStorage` pour trois classes (`MailQueueDeadLetterTests`, `AvatarQueueDeadLetterTests`, `DataExportFailureTests`), soit un contexte et non trois ;
- les mêmes annotations recopiées à l'identique : [`MonitoringTests`][monitoring], `PublicEndpointErrorTests` et `TaskRoomsWebSocketTests` portent les trois mêmes annotations (`@Import(TestcontainersConfiguration.class)`, `@AutoConfigureMetrics`, `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "management.server.port=0")`) et partagent un contexte ;
- la slice JDBC importe toutes les classes `*Queries` par un filtre à expression régulière plutôt que la seule classe testée ([`JdbcSliceTest`][jdbc]).

Au total, une quarantaine de classes d'intégration partagent cinq contextes d'intégration (`@IntegrationTest`, `@DeadLetterIntegrationTest`, `OutboxBrokerFailureTests` avec son espion de `RabbitTemplate`, `RateLimitTests` avec ses limites actives, et le serveur réel de `MonitoringTests`), plus trois slices (web, JDBC, JPA).

## Quand ça se passe mal

| Panne | Ce qui se passe | Comment le système s'en sort |
|---|---|---|
| Une classe ajoute un mock ou une propriété | un contexte de plus, avec son RabbitMQ | réglage dans `config/application.yaml` ou une annotation de famille |
| Plus de 12 configurations | les moins récents sont fermés, puis redémarrés | le plafond de `spring.properties`, ajusté aux familles |
| Réutilisation désactivée | chaque contexte démarre ses cinq conteneurs | ClamAV ne charge que la signature EICAR ; réutilisation activée en local et en CI |
| Deux suites complètes en même temps | deux fois les contextes : la mémoire sature | une seule suite complète à la fois ; sinon `-Dtest=...` |
| Un test compte toutes les lignes d'une table | les lignes des autres contextes le font échouer | valeurs uniques ; fixtures des purges datées de 2000 |
| Un job planifié actif dans un contexte | il agit sur les données des autres et prend leur verrou | jobs éteints en test ; les tests appellent les services |

## À retenir

- Une annotation, une propriété ou un mock posé sur une seule classe change la clé du cache : un contexte complet de plus, avec son broker.
- Le coût se paie en démarrage, puis en mémoire jusqu'à la fin de la JVM, puis en redémarrages quand le cache déborde.
- Un réglage commun va dans `config/application.yaml` ; une configuration différente inévitable va dans une annotation de famille.
- Des outils toujours présents (horloge de test, Mailpit, compteur de requêtes) remplacent les mocks par classe.
- Les contextes partagent la base : données uniques, jobs éteints, poller lent.

## Voir aussi

- [29. Couches de tests et contextes](../29-tests-couches-contextes.md) : la clé du cache, les annotations de famille, la pause des contextes.
- [30. Testcontainers](../30-testcontainers.md) : ce que démarre chaque contexte, la réutilisation.
- [31. Outils de test et tests garde-fous](../31-outils-tests-garde-fous.md) : les outils qui évitent un contexte.
- [21. Jobs planifiés et ShedLock](../21-jobs-planifies-shedlock.md) : pourquoi les jobs sont éteints en test.
- [20. Outbox transactionnelle](../20-outbox-transactionnelle.md) : le poller et la publication après le commit.

[integration]: ../../../src/test/java/io/julienmetral/tasks/support/IntegrationTest.java
[dead-letter]: ../../../src/test/java/io/julienmetral/tasks/support/DeadLetterIntegrationTest.java
[jdbc]: ../../../src/test/java/io/julienmetral/tasks/support/JdbcSliceTest.java
[containers]: ../../../src/test/java/io/julienmetral/tasks/TestcontainersConfiguration.java
[monitoring]: ../../../src/test/java/io/julienmetral/tasks/MonitoringTests.java
[test-yaml]: ../../../src/test/resources/config/application.yaml
[spring-properties]: ../../../src/test/resources/spring.properties
[webhook-tests]: ../../../src/test/java/io/julienmetral/tasks/notification/webhook/WebhookDeliveryTests.java
[locking-tests]: ../../../src/test/java/io/julienmetral/tasks/config/ScheduledJobLockingTests.java
