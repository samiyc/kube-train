# Correction QCM J2 — OpenTelemetry, Observabilité & Distributed Tracing
> Date : 26/05/2026 | Score : **5.5 / 8** (69 %)

---

## Question 1 — Traces, spans et traceId ⭐

**Ta réponse : ✅ CORRECT**

> Bonne compréhension des concepts : trace = suivi d'un appel HTTP à travers les services, spans identifiés par spanId, traceId relie tout, parentSpanId pour la hiérarchie.

L'exemple est correct. Pour être précis en entretien :

| Concept | Définition |
|---|---|
| **Trace** | L'ensemble de tous les spans partageant le même traceId — représente le parcours complet d'une requête |
| **Span** | Une unité de travail avec un début et une fin (ex: un appel DB, un appel HTTP, un traitement métier) |
| **TraceId** | Identifiant unique 128-bit (32 hex chars) qui relie tous les spans d'une même requête |
| **SpanId** | Identifiant 64-bit (16 hex chars) unique à chaque span |
| **ParentSpanId** | Référence au span parent → construit l'arbre hiérarchique |

---

## Question 2 — Agent Java vs instrumentation SDK ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ "Pas de modification du code" et "Configuration simplifiée" → deux vrais avantages
> B) ⚠️ Tu cites "Plus de réglages/granularité possible" (✅), mais "il faut modifier le code" est un **inconvénient**, pas un avantage. Il manque un 2e avantage.
> C) ✅ Bon — "analyse très fine", "informations custom de suivi"

**Réponses attendues pour B (deux avantages SDK) :**

1. **Instrumentation sélective** — tu n'instrumentes que ce qui t'intéresse (pas de bruit des health probes, JDBC driver, etc.). L'agent capture TOUT par défaut.
2. **Pas d'overhead agent** — l'agent ajoute ~150MB de RAM (on l'a vu avec l'OOMKilled). Le SDK n'ajoute que ce que tu importes.
3. **Vérification à la compilation** — le code SDK est vérifié par le compilateur Java. L'agent peut crasher au runtime si incompatibilité de version.

---

## Question 3 — Rôle du OTel Collector ⭐⭐

**Ta réponse : ✅ CORRECT (3 raisons solides)**

> 1. Découplage du backend (Cloud Trace / Jaeger)
> 2. Centralisation de l'auth et la config
> 3. Retry si le backend est KO

Excellente réponse. Bonus qu'on pourrait ajouter :
- **Multi-export** : envoyer vers Cloud Trace ET un système on-premise en même temps
- **Sampling/filtering** : réduire le volume de données avant l'envoi (tail-based sampling)
- **Processing** : enrichissement des spans (ajout d'attributs k8s, batch regroupement)

---

## Question 4 — Lecture des logs Collector ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ Correct — 2 services actifs (kube-train-api + train-notification-service)
> B) ✅ Correct — spans d'initialisation JVM/Spring flushés d'un coup au premier batch
> C) ⚠️ "tracesExporter" → Imprécis. La bonne réponse est l'exporter **`debug`**

**Explication C** : Dans la config du Collector (`otel-collector.yaml`), le pipeline traces a deux exporters :
```yaml
exporters: [googlecloud, debug]
```
- `googlecloud` → envoie silencieusement vers Cloud Trace (pas de log)
- `debug` → affiche les stats dans stdout/stderr (ce qu'on voit dans les logs)

Le log `TracesExporter {"name": "debug", ...}` identifie explicitement que c'est l'exporter `debug` qui parle.

---

## Question 5 — W3C TraceContext et header `traceparent` ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) Format : Version ✅, TraceId ✅, SpanId ✅
> **Mais** : `01` = "Tag" ❌ — Le terme correct est **trace-flags** (drapeaux de trace)
> B) ✅ Correct — la trace ne peut plus être reconstituée

**Correction du dernier composant :**

```
traceparent: 00-9809bf57dbb2d0441ce355c53b57016e-5e3d2a1b9c4f8701-01
              │                                                     │
              version                                          trace-flags
```

| Valeur | Signification |
|---|---|
| `00` | Trace non sampled (on ne l'enregistre PAS) |
| `01` | **Sampled** — cette trace EST enregistrée par le backend |

En entretien, savoir que `01 = sampled` est important : c'est comme ça que le head-based sampling fonctionne. Le premier service décide (`01` ou `00`), et tous les services en aval respectent cette décision.

**Précision B** : Si un load balancer/API Gateway supprime le header, le service suivant **génère un nouveau traceId** → deux traces séparées, sans lien parent-enfant. C'est exactement ce qu'on observe avec Pub/Sub (Q6).

---

## Question 6 — Pub/Sub vs Kafka : limite de propagation ⭐⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.5/1)**

> "Kafka transfert automatiquement la trace via les entetes des messages" ✅
> "Sur pub/sub il faut le faire soi-même" ✅
> Manque le POURQUOI (A), le COMMENT (B), et la distinction OTel/Pub/Sub (C)

**Réponses attendues :**

**A) Pourquoi Kafka auto et pas Pub/Sub ?**

L'agent OTel Java contient des **instrumentations built-in** pour les librairies populaires. Pour Kafka, il hook automatiquement :
- `KafkaProducer.send()` → injecte `traceparent` dans les headers du message
- `KafkaConsumer.poll()` → extrait `traceparent` et crée un span enfant

Pour Pub/Sub (Google Cloud), l'agent instrumente le **client gRPC** (on voit les spans `google.pubsub.v1.Publisher/Publish`), mais il ne sait PAS qu'il faut injecter le contexte dans les **attributs du message Pub/Sub**. Ce sont deux niveaux différents : gRPC transport ≠ message-level propagation.

**B) Que faudrait-il implémenter ?**

```java
// Côté publisher (kube-train-api) :
Map<String, String> attributes = new HashMap<>();
GlobalOpenTelemetry.getPropagators()
    .getTextMapPropagator()
    .inject(Context.current(), attributes, Map::put);
// → ajouter ces attributs au message Pub/Sub

// Côté consumer (notification-service) :
Context extractedContext = GlobalOpenTelemetry.getPropagators()
    .getTextMapPropagator()
    .extract(Context.current(), message.getAttributesMap(), Map::get);
// → utiliser extractedContext comme parent du span consumer
```

**C) Limitation OTel ou Pub/Sub ?**

C'est une limitation de **l'agent OTel** (pas d'instrumentation auto pour Pub/Sub message attributes). Pub/Sub supporte parfaitement les attributs custom — c'est juste que personne n'a encore écrit l'instrumentation automatique.

---

## Question 7 — Lecture de flame graph ⭐⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.5/1)**

> A) ⚠️ "Vérifier via select qu'il n'y ai pas de conflit" → Imprécis
> B) ⚠️ "Le pod en hibernation démarre" → Incorrect (le pod est déjà running)
> C) ✅ "Atomicité du pattern Outbox" → Correct

**Corrections :**

**A) Session.merge vs Session.persist — la vraie différence :**

| Méthode | Comportement | Quand l'utiliser |
|---|---|---|
| `merge` (= JPA `save()` avec id potentiellement existant) | SELECT d'abord pour vérifier si l'entité **existe déjà en BDD**. Si oui → UPDATE. Si non → INSERT (= upsert) | Entités potentiellement détachées ou déjà persistées |
| `persist` | INSERT direct, **sans SELECT préalable**. Assume que l'entité est **nouvelle** | Entités toujours nouvelles (ex: OutboxEvent) |

Dans kube-train, `ReservationRepository.save()` appelle `merge` car Spring Data JPA utilise `merge` par défaut quand l'ID est déjà renseigné (UUID généré côté Java). C'est pourquoi on voit le SELECT de 2,155ms.
`OutboxEventRepository.save()` avec une entité annotée `@Id @GeneratedValue` côté Hibernate appelle `persist` → pas de SELECT → 274µs.

**B) Les 21 secondes manquantes :**

Ce n'est PAS le pod qui démarre — il est déjà `Running` (la requête HTTP est reçue, Spring est up). C'est le **Cloud SQL Auth Proxy** qui établit sa première connexion TCP vers l'instance `db-f1-micro` après une période d'inactivité :

1. Le proxy doit s'authentifier auprès de l'API Cloud SQL (IAM token refresh)
2. L'instance `db-f1-micro` (la plus petite) est en veille et doit réactiver sa stack réseau
3. Le connection pool HikariCP crée sa première connexion physique

**Preuve** : les requêtes suivantes prennent ~300ms → c'est bien un cold start de connexion DB, pas un cold start applicatif.

---

## Question 8 — Micrometer et export OTel ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.5/1)**

> A) ✅ Correct dans l'idée — l'agent bridge Micrometer vers OTLP
> B) Counter ✅, Gauge ⚠️ (vague), Histogram ❌ (définition incorrecte)

**Correction B — Les 3 types de métriques :**

| Type | Définition | Comportement | Exemple kube-train |
|---|---|---|---|
| **Counter** | Compteur **monotone croissant** (ne redescend jamais) | Toujours `+1` ou `+n` | `reservations.created` — nombre total de réservations depuis le démarrage |
| **Gauge** | Valeur **instantanée** qui monte ET descend | Set/increment/decrement | Nombre de connexions actives dans le HikariCP pool, nombre de threads actifs, taille de la queue Outbox (events PENDING) |
| **Histogram** | **Distribution de valeurs** → calcule percentiles (p50, p95, p99) | Enregistre chaque observation | Temps de réponse de `POST /reservations` → "p99 = 250ms" signifie que 99% des requêtes font < 250ms |

**Ta confusion Histogram** : un histogram n'est PAS un "historique des erreurs". C'est un outil statistique qui mesure la **distribution d'une valeur continue** (latence, taille, durée). Il répond à la question : "Combien de temps prennent mes requêtes dans 95% des cas ?"

---

## Récapitulatif

| Question | Thème | Note | Commentaire |
|---|---|---|---|
| Q1 ⭐ | Trace/Span/TraceId | ✅ 1/1 | Bonne compréhension |
| Q2 ⭐⭐ | Agent vs SDK | ⚠️ 0.75/1 | B : un seul avantage réel cité |
| Q3 ⭐⭐ | Rôle Collector | ✅ 1/1 | 3 raisons solides |
| Q4 ⭐⭐ | Logs Collector | ⚠️ 0.75/1 | C : `debug` pas `tracesExporter` |
| Q5 ⭐⭐ | W3C TraceContext | ⚠️ 0.75/1 | trace-flags ≠ "tag" |
| Q6 ⭐⭐⭐ | Pub/Sub vs Kafka | ⚠️ 0.5/1 | Manque le pourquoi + comment |
| Q7 ⭐⭐⭐ | Flame graph | ⚠️ 0.5/1 | B : c'est la connexion DB pas le pod |
| Q8 ⭐⭐ | Micrometer | ⚠️ 0.5/1 | Histogram ≠ historique d'erreurs |

**Score final : 5.75 / 8 (72 %)**

---

## Points à retenir pour la certification

1. **trace-flags `01` = sampled** — très courant en QCM CKA/GCP
2. **merge vs persist** — question classique d'entretien Spring/Hibernate
3. **Histogram = distribution de latences** (percentiles), Counter = monotone, Gauge = valeur instantanée
4. **Cold start DB ≠ cold start pod** — distinction importante en debugging K8s
5. **OTel agent = instrumentation auto des librairies connues** — si la librairie n'est pas supportée (Pub/Sub message attributes), pas de propagation automatique
