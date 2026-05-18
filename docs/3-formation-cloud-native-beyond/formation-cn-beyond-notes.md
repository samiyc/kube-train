# Formation 3 — Cloud Native Beyond — Notes de révision

## J1 — Flyway, Outbox Pattern & Spring Cloud Contract

### Flyway — Migrations versionnées

**Principe** : Flyway applique des scripts SQL versionnés (`V1__`, `V2__`...) dans l'ordre, une seule fois par environnement. L'historique est stocké dans `flyway_schema_history`.

**Règle d'or** : Ne JAMAIS modifier un fichier `Vn__` déjà appliqué → créer un `V(n+1)__` pour toute correction.

```
V1__create_reservations.sql  → table reservations
V2__grant_privileges.sql     → droits PostgreSQL 15 pour kube_train_user
V3__outbox_table.sql         → table outbox_events (Outbox Pattern)
```

**Config Spring Boot** (via `application.properties`) :
```properties
spring.flyway.enabled=true           # activé par défaut quand flyway-core est dans le classpath
spring.flyway.locations=classpath:db/migration
```

---

### Transactional Outbox Pattern

**Problème** : Sans outbox, si Kafka/Pub Sub est temporairement indisponible AU MOMENT de la réservation, l'événement est perdu. La réservation est en base mais le consumer notification ne reçoit jamais rien.

**Solution** : Dans la MÊME transaction SQL que la réservation, on écrit l'événement dans une table `outbox_events` (status=PENDING). Un scheduler (`OutboxPoller`) lit les événements en attente et les publie.

```
POST /reservations
    │
    ├── @Transactional
    │   ├── reservations.save(...)     → table reservations
    │   └── outbox_events.save(...)    → table outbox_events (status=PENDING)
    │
    └── Réponse 201 immédiate (avant publication Kafka)

OutboxPoller (@Scheduled, toutes les 5s)
    │
    ├── findByStatus("PENDING")
    ├── eventPublisher.publish(deserialize(payload))
    └── outbox_events.setStatus("PROCESSED")
```

**Garantie at-least-once** : on publie PUIS on marque PROCESSED. Si le pod crashe entre les deux, l'événement sera republié au redémarrage → doublon possible. Le consumer doit être idempotent (notre `ConcurrentHashMap` y veille).

**Fichiers concernés** :
- `V3__outbox_table.sql` — DDL de la table
- `OutboxEvent.java` — entité JPA (`@Profile("postgres")`)
- `OutboxEventRepository.java` — `findByStatusOrderByCreatedAtAsc("PENDING")`
- `OutboxPoller.java` — `@Scheduled` + `@Transactional` + `@Profile("postgres")`
- `TrainService.java` — `@Transactional createReservation()` → écrit dans l'outbox si disponible

---

### Spring Cloud Contract — Côté Producer

**Concept** : Les contrats YAML dans `src/test/resources/contracts/` définissent le comportement attendu de l'API. Le plugin Maven génère automatiquement des tests JUnit qui vérifient que le producer respecte ces contrats.

**Deux couches du contrat** :
- `body:` → valeurs d'exemple retournées par le **stub WireMock** (pour les consumers)
- `matchers:` → patterns regex vérifiés par les **tests auto-générés** du producer (valeur réelle)

```yaml
response:
  body:
    reservationId: "RES-EFB30294"    # valeur exemple → retournée par le stub WireMock
  matchers:
    body:
      - path: $.reservationId
        type: by_regex
        value: 'RES-[A-Z0-9]{8}'    # pattern → vérifié sur la vraie réponse API
```

**Sans `body:` pour un champ** → le stub WireMock ne retourne PAS ce champ dans la réponse.

**Pipeline de génération** :
```
mvn install
  → génère les tests JUnit à partir des contrats YAML
  → les tests vérifient que l'API retourne les bonnes valeurs/formats
  → génère le stubs JAR (kube-train-api-0.0.1-SNAPSHOT-stubs.jar)
  → installe le JAR dans ~/.m2 → disponible pour les consumers
```

---

### Spring Cloud Contract — Côté Consumer

**Concept** : Le consumer (`train-notification-service`) utilise le stubs JAR généré par le producer pour démarrer un serveur WireMock local. Les tests consumer vérifient que le consumer peut appeler la "vraie" API selon les contrats, sans démarrer le vrai service.

**Dépendance** :
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-contract-stub-runner</artifactId>
    <scope>test</scope>
</dependency>
```

**Annotation** :
```java
@AutoConfigureStubRunner(
    ids = "com.kubetrain:kube-train-api:0.0.1-SNAPSHOT:stubs:8181",
    stubsMode = StubRunnerProperties.StubsMode.LOCAL  // cherche dans ~/.m2
)
```

**Pré-requis CI** : `mvn install` (producer) doit s'exécuter AVANT `mvn test` (consumer).

**Valeur** :
- Garantit la compatibilité inter-services sans démarrer l'API réelle
- Si le producer change son API de manière incompatible → le test producer casse → fail CI avant tout déploiement
- Les équipes peuvent travailler en parallèle (consumer travaille avec les stubs, pas l'API en prod)

---

### Points clés entretien

| Question | Réponse |
|----------|---------|
| Différence Outbox vs publish direct | Outbox = atomicité SQL (réservation + événement dans la même TX). Publish direct = 2 opérations séparées, risque de perte si Kafka down |
| Pourquoi at-least-once et non at-most-once ? | On publie PUIS on marque PROCESSED → si crash entre les deux, on re-publie. Préférable à la perte de message. Nécessite idempotence côté consumer |
| body: vs matchers: dans les contrats | body = exemple concret pour le stub (valeur fixe) ; matchers = regex/pattern vérifié sur la vraie réponse du producer |
| Pourquoi @Profile("postgres") sur OutboxPoller ? | L'outbox nécessite JPA + DataSource. En local/tests sans profil postgres, il n'y a pas de table outbox → le poller ne s'instancie pas |
| Comment faire communiquer producer/consumer de contrats en CI ? | mvn install sur le producer génère le stubs JAR et l'installe en local. mvn test du consumer le consomme via StubRunner (StubsMode.LOCAL) |
