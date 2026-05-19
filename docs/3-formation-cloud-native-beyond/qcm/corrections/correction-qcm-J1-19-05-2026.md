# Correction QCM J1 — Flyway, Outbox Pattern & Spring Cloud Contract
> Date : 19/05/2026 | Score : **6.5 / 8** (81 %)

---

## Question 1 — Règle des migrations Flyway ⭐

**Ta réponse : B ✅ CORRECT**

> Créer un nouveau fichier `V3__add_missing_grant.sql` avec le `GRANT` manquant.

C'est la règle d'or de Flyway : **un fichier `Vn__` appliqué est immuable**. Flyway calcule un checksum au moment de l'application. Si tu modifies le fichier ensuite, le checksum ne correspond plus → Flyway lève une `FlywayValidateException` au démarrage de l'application. En production, ça empêche le démarrage du service.

Les options A, C, D sont des anti-patterns graves :
- A : modifie un script déjà exécuté → checksum invalide → application ne démarre plus
- C : manipuler `flyway_schema_history` à la main → perte de traçabilité, opération risquée
- D : `validate-on-migrate=false` masque le problème sans le corriger

---

## Question 2 — Flyway et profils Spring ⭐⭐

**Ta réponse : ✅ CORRECT (bonne compréhension)**

> "il n'y aurait pas de sens à lancer flyway sans bdd, ça serait d'ailleurs source d'erreur, car la datasource n'existe pas"

C'est exactement ça. Sans le profil `postgres`, aucune `DataSource` n'est configurée (`spring.datasource.*` absent). Spring Boot ne peut pas autoconfigurer JPA ni Flyway → Spring Boot désactive Flyway par absence de bean `DataSource`.

**Précision technique** : dans `application.properties` (profil par défaut), on peut aussi écrire explicitement `spring.flyway.enabled=false` pour être explicite. Mais l'absence de DataSource suffit à tout désactiver.

---

## Question 3 — Table `flyway_schema_history` ⭐⭐

**Ta réponse : ✅ CORRECT (partiel sur les détails)**

> "l'historique des scripts exécutés, le checksum permet de comparer que les scripts ne sont pas en décalage"

Bonne réponse sur le fond. Quelques précisions utiles pour un entretien :

**Colonnes clés de `flyway_schema_history`** :
```
installed_rank | version | description         | type | script              | checksum   | success
1              | 1       | create reservations | SQL  | V1__create_...sql   | 1234567890 | true
2              | 2       | grant privileges    | SQL  | V2__grant_...sql    | -987654321 | true
3              | 3       | outbox table        | SQL  | V3__outbox_table.sql| 567890123  | true
```

**Conséquence concrète d'un checksum invalide** : au prochain démarrage, Flyway compare le checksum du fichier sur disque avec celui en base. Si différent → `FlywayValidateException` → **l'application ne démarre pas**. C'est un garde-fou volontairement bloquant.

---

## Question 4 — Dual-write problem ⭐⭐

**Ta réponse : ⚠️ PARTIELLE (manque la propriété ACID)**

> "Si il y a un bug kafka/pub-sub, la réservation est créée mais l'événement n'est pas garanti d'être envoyé"

Le scénario de défaillance est correct. Il manque la réponse à la question explicite : **"Quelle propriété ACID est en jeu ?"**

**Réponse complète :**

La propriété ACID en jeu est l'**Atomicité** : les deux opérations (save en base + publish sur Kafka) doivent réussir ou échouer ensemble. Or Kafka ne participe pas aux transactions SQL → il n'existe pas de transaction distribuée native entre un RDBMS et Kafka.

Scénario de défaillance exact :
```
1. reservationRepository.save(reservation)  → ✅ commit en base
2. eventPublisher.publish(event)             → 💥 Kafka/Pub Sub down ou timeout

Résultat : réservation en base MAIS event perdu
→ le consumer notification ne reçoit jamais rien
→ l'utilisateur reçoit sa confirmation mais pas son email
```

**Mémo entretien** : *"Sans Outbox, je fais un dual-write non atomique. L'Atomicité ACID garantit que les deux opérations forment une unité indivisible — impossible entre SQL et Kafka sans pattern spécifique."*

---

## Question 5 — Outbox Pattern en pratique ⭐⭐⭐

**Ta réponse : A ✅ CORRECT**

> Publier puis PROCESSED → at-least-once, doublons possibles, consumer doit être idempotent.

C'est l'approche correcte et celle implémentée dans `OutboxPoller`. Le trade-off est clairement énoncé dans l'option A :

| Approche | Sémantique | Risque |
|---|---|---|
| **A : publish → PROCESSED** | at-least-once | Doublon possible si crash entre publish et update |
| B : PROCESSED → publish | at-most-once | Perte de message si crash entre update et publish |
| C : @Transactional résout tout | ❌ faux | @Transactional ne couvre pas Kafka |
| D : Transaction XA | Possible en théorie | Très lourd, rare en pratique, non supporté par tous les brokers |

Notre `ConcurrentHashMap` dans `PubSubReservationEventConsumer` gère les doublons côté consumer → l'at-least-once est acceptable.

---

## Question 6 — Outbox et idempotence ⭐⭐⭐

**Ta réponse : ✅ BONNE (mais un point de correction important)**

> "l'update de la table est bloqué via les transactions"

⚠️ **Ce point est inexact**. Les transactions SQL standard ne bloquent **pas** les lectures concurrentes sur des lignes non encore modifiées. Deux pollers qui démarrent simultanément liront la même liste de `PENDING` avant qu'aucun des deux ne commence à mettre à jour. Le `@Transactional` sur `processEvent()` ne suffit pas à prévenir la lecture concurrente.

**Ce qui se passe réellement avec 2 replicas** :
```
Poller A : SELECT * WHERE status='PENDING'  → lit RES-001, RES-002
Poller B : SELECT * WHERE status='PENDING'  → lit RES-001, RES-002  (même résultat !)
Poller A : publish(RES-001) puis UPDATE status='PROCESSED'
Poller B : publish(RES-001) puis UPDATE status='PROCESSED'  (doublon publié sur Pub/Sub)
```

**La vraie solution technique** : `SELECT ... FOR UPDATE SKIP LOCKED`
```sql
SELECT * FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at
FOR UPDATE SKIP LOCKED  -- PostgreSQL : prend un verrou, SKIP = ignore les lignes déjà verrouillées
LIMIT 100;
```
Chaque poller obtient ainsi un lot distinct de lignes → pas de doublon au niveau outbox.

**Tes deux solutions proposées sont valides en architecture** :
- Micro-service dédié single-instance → simple mais single point of failure
- La scalabilité du poller est effectivement le vrai problème → `SKIP LOCKED` est la réponse standard

---

## Question 7 — body vs matchers ⭐⭐

**Ta réponse : ✅ PARTIELLE (bonne intuition, nuance clé manquante)**

> "le body contient des données réelles, le matchers contient des regex"

Correct sur le principe, mais la nuance essentielle est la **cible de chaque section** :

| Section | Pour qui | Quand | Valeur |
|---|---|---|---|
| `body:` | **Stub WireMock** → consumer | Tests consumer (pas l'API réelle) | Valeur fixe d'exemple retournée par le mock |
| `matchers:` | **Tests générés** → producer | `mvn verify` sur le producer | Pattern regex/type vérifié sur la VRAIE réponse |

**Exemple concret avec `reservationId`** :
```yaml
response:
  body:
    reservationId: "RES-EFB30294"    # WireMock retourne cette valeur fixe au consumer
  matchers:
    body:
      - path: $.reservationId
        type: by_regex
        value: 'RES-[A-Z0-9]{8}'    # vérifié sur la vraie réponse du producer
```

Sans `body:` → le stub WireMock ne retourne pas le champ → le consumer test ne peut pas vérifier sa valeur → c'est le bug qu'on a corrigé lors de J1 !

Le format exact : `RES-[A-Z0-9]{8}` (8 caractères alphanumériques en majuscules).

---

## Question 8 — Valeur du Contract Testing ⭐⭐⭐

**Ta réponse : ✅ BONNE (arguments valides, présentation à structurer)**

> "Le contract testing permet à deux équipes indépendantes de travailler chacune de leur côté"

Bonne réponse, bons arguments. Pour un entretien, voici comment les présenter de façon plus percutante avec **2 avantages distincts des tests E2E** :

**Avantage 1 — Feedback en secondes, pas en minutes**
- Tests E2E : nécessitent un environnement déployé, des services actifs, une vraie base → 5-30 minutes en CI
- Contract tests : démarrent un WireMock local (stub) → 10-30 secondes
- Conséquence : détection d'une breaking change en CI avant même le déploiement

**Avantage 2 — Consumer-Driven : c'est le consumer qui définit ses besoins**
- En E2E, le producer définit son API et les consumers s'y adaptent
- En Contract Testing, le **consumer écrit ses attentes** dans un contrat que le producer DOIT respecter
- Si le producer veut changer son API, il voit immédiatement quels consumers seraient cassés
- Les équipes peuvent déployer indépendamment (découplage fort)

**Bonus — Isolation totale** : le test consumer ne nécessite pas que le producer soit déployé ou accessible. Un bug d'infra ne fait pas échouer les tests contract.

L'analogie Swagger est intéressante mais inexacte : Swagger est **descriptif** (documente ce qui existe), le Contract Testing est **prescriptif** (définit ce qui DOIT exister, et le vérifie automatiquement en CI).

---

## Score final

| Question | Niveau | Résultat | Points |
|---|---|---|---|
| Q1 — Règle Flyway | ⭐ | ✅ | 1 / 1 |
| Q2 — Profils Spring | ⭐⭐ | ✅ | 1 / 1 |
| Q3 — flyway_schema_history | ⭐⭐ | ✅ partiel | 0.75 / 1 |
| Q4 — Dual-write ACID | ⭐⭐ | ⚠️ | 0.5 / 1 |
| Q5 — at-least-once | ⭐⭐⭐ | ✅ | 1 / 1 |
| Q6 — Double poller | ⭐⭐⭐ | ✅ partiel | 0.75 / 1 |
| Q7 — body vs matchers | ⭐⭐ | ✅ partiel | 0.75 / 1 |
| Q8 — Valeur Contract Testing | ⭐⭐⭐ | ✅ partiel | 0.75 / 1 |
| **TOTAL** | | | **6.5 / 8 (81 %)** |

### Points forts
- Excellente maîtrise des concepts Flyway (Q1, Q2, Q3)
- Bonne compréhension at-least-once vs at-most-once (Q5)
- Raisonnement solide sur la scalabilité du poller (Q6)

### Points à retravailler avant entretien
- **Nommer la propriété ACID** en jeu : **Atomicité** (Q4) → c'est le mot technique attendu
- **`SELECT FOR UPDATE SKIP LOCKED`** : solution standard PostgreSQL pour les workers concurrents (Q6) — très apprécié en entretien senior
- **Distinction body (WireMock) vs matchers (producer tests)** dans Spring Cloud Contract (Q7) — subtilité clé
