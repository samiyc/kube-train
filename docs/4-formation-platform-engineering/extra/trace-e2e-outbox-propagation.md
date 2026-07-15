# Propagation de trace E2E : API → Outbox → Pub/Sub → Notification (GCP réel)

> Pendant de `otel-local-stack-runbook.md` et `otel-sans-gcp.md`, mais côté **GCP réel** :
> comment une trace unique relie `POST /reservations` (kube-train-api) jusqu'à l'envoi
> d'email (train-notification-service), **à travers un pattern Outbox asynchrone**.
>
> Données réelles capturées le **15/07/2026** sur le cluster GKE reconstruit.

---

## 1. Vue d'ensemble — deux frontières à franchir

L'agent OTel auto-instrumente HTTP, JDBC et **Kafka** (traceparent dans les headers).
Il n'instrumente PAS le client **Pub/Sub** bas-niveau, ni la table Outbox. Il a donc fallu
propager le contexte W3C `traceparent` **manuellement** sur deux frontières :

```
POST /reservations  (kube-train-api, trace A)
   │  Span racine — porte reservation.id
   ├─ INSERT reservations
   └─ INSERT outbox_events  ← ① le traceparent de la trace A est écrit DANS la ligne
        commit → la requête HTTP se termine ici. Rien n'est publié.
        ╎
        ╎  (~5 s plus tard, thread @Scheduled — une AUTRE trace par défaut)
        ╎
   OutboxPoller.processPendingEvents
        └─ restaure le traceparent de la ligne → makeCurrent()
             └─ publish() → ② le traceparent est réinjecté dans les attributs Pub/Sub
                  │
                  ▼
        notification-service (consumer)
             └─ extract(attributs) → span rattaché à la trace A
                  └─ notification process → notification send-email
```

- **Frontière ① HTTP → Outbox** : le contexte doit survivre au commit et au découplage
  temporel. Véhicule = **colonnes `traceparent`/`tracestate` de `outbox_events`** (migration V4).
- **Frontière ② Outbox → Pub/Sub → Consumer** : injection dans les attributs du message,
  extraction côté consumer.

Sans ① le contexte meurt au commit : le poller publierait dans **sa propre** trace, et
notification-service apparaîtrait dans une trace séparée. C'était le symptôme initial.

---

## 2. Le diagnostic — trace coupée au `commit`

Symptôme : la trace `POST /reservations` s'arrêtait à `Transaction.commit`. Les spans
`notification process` / `send-email` existaient bien (le collector loggait
`resource spans: 2`), mais dans une **trace distincte** — celle du scheduler.

Cause : le pattern Outbox **découple délibérément** l'écriture (requête HTTP) de la
publication (poller). `PubSubReservationEventPublisher.publish()` injectait
`Context.current()` — mais au moment du `publish()`, le contexte courant est celui du
`@Scheduled`, pas celui de la requête HTTP. La ligne Outbox était le seul lien possible.

---

## 3. La solution — contexte de trace dans la ligne Outbox

Migration `V4__outbox_trace_context.sql` :

```sql
ALTER TABLE outbox_events
    ADD COLUMN traceparent VARCHAR(64),   -- W3C "00-<traceId>-<spanId>-<flags>"
    ADD COLUMN tracestate  TEXT;          -- optionnel
```

Chaîne côté code (commit `6f37ba1`) :

| Étape | Fichier | Rôle |
|---|---|---|
| Capture | `TrainService.enqueueEvent` | `TracePropagation.currentTraceAttributes()` — ici `Context.current()` **est** la trace HTTP → persisté dans la ligne |
| Restauration | `OutboxPoller.processEvent` | `extract(traceAttributesOf(event))` + `parent.makeCurrent()` autour du `publish()` |
| Injection | `PubSubReservationEventPublisher.publish` | `traceparent` réinjecté dans les attributs Pub/Sub (inchangé) |
| Extraction | `PubSubReservationEventConsumer` | `extract(message.getAttributesMap())` → span rattaché |

**Dégradation propre** : sans agent OTel (tests, Minikube), `traceparent` reste `null`,
`extract` renvoie le contexte courant → comportement d'avant, aucune régression. 33 tests verts.

**Choix de conception assumé** : la notification devient **enfant** du span HTTP (déjà
terminé) → trace unique et continue dans Cloud Trace. OpenTelemetry recommanderait un
**span Link** pour une relation asynchrone, mais Cloud Trace afficherait alors deux traces
liées, pas une seule. Pour un projet de démonstration, la continuation parent est plus
lisible — et c'est une bonne discussion d'entretien.

---

## 4. Prérequis GCP — l'identité du collector (SA dédié)

Le pipeline `googlecloud` (traces + métriques) exige une identité GCP. L'OTel Collector
tournait sur le KSA `default` non annoté → `PermissionDenied` sur `cloudtrace.traces.patch`
et `monitoring.timeSeries.create` après **chaque** rebuild du cluster (fix manuel reperdu).

Solution durable (commit `d41965e`), 100 % déclarative :

- `infra/iam.tf` : GSA `otel-collector-sa` + `roles/cloudtrace.agent` + `roles/monitoring.metricWriter`
  + binding `workloadIdentityUser` sur `[default/otel-collector-sa]`
- `k8s/observability/otel-collector.yaml` : `ServiceAccount otel-collector-sa` annoté +
  `serviceAccountName` sur le pod

Résultat : le collector retrouve son identité tout seul après un `terraform apply` —
plus aucun `kubectl annotate`. Aligné sur la règle F4-J1 « un SA dédié par workload,
jamais `default` ».

Vérification (aucune erreur attendue) :
```bash
kubectl logs -l app=otel-collector -c otel-collector --tail=20 | grep -iE "permission|TracesExporter"
# → "TracesExporter ... resource spans: 2"  et  ZÉRO PermissionDenied
```

---

## 5. Bug d'idempotence découvert en validant les traces

En observant les traces E2E, découverte d'un doublon : `RES-867582A6` traité **deux fois**
(deux emails), visible dans la trace ET les logs.

Cause : le consumer Pub/Sub dédupliquait sur le **`messageId` Pub/Sub**. Or l'Outbox
republie le même événement métier au cycle de poll suivant s'il n'a pas été marqué
PROCESSED — avec un **nouveau `messageId`** à chaque fois. La dédup par `messageId` ne
pouvait structurellement rien voir.

Fix (commit `9fdc402`) : dédupliquer sur `event.eventId()` — un UUID figé dans le payload
à la création, **stable** à travers republications Outbox ET re-livraisons transport.
C'est ce que le consumer **Kafka** faisait déjà ; le Pub/Sub est désormais aligné.

```java
// PubSubReservationEventConsumer.handleMessage — désérialise AVANT de dédup
ReservationEvent event = objectMapper.readValue(payload, ReservationEvent.class);
if (!processedEventIds.add(event.eventId())) {   // clé métier, pas messageId
    log.warn("[PUBSUB-CONSUMER] Événement déjà traité, ignoré : eventId={}, ...", event.eventId());
    return;
}
```

> `messageId` = identité **transport** (unique par livraison Pub/Sub).
> `eventId` = identité **métier** (unique par événement, survit aux republications).
> Pour l'idempotence, toujours la clé métier.

> ⚠️ Le cache est un `ConcurrentHashMap` **en mémoire** → per-pod, perdu au restart.
> Suffisant en démo (1 pod). En prod : Redis ou table `processed_events(event_id PK)`.

---

## 6. `reservation.id` sur le span racine

Avant, l'ID métier n'était que sur le span `notification send-email` — il fallait
descendre dans l'arbre pour le voir. Ajout (commit `9fdc402`) dans
`TrainService.createReservation` :

```java
Span.current().setAttribute("reservation.id", reservationId);
```

`Span.current()` y est le span serveur `POST /reservations`. L'ID remonte au sommet de la
trace : visible d'emblée et **recherchable** sans drill-down.

---

## 7. Validation E2E — données réelles (15/07/2026)

Cluster GKE reconstruit (`terraform apply`), déploiement via CI/CD, LB `34.77.225.112`.

```bash
LB=$(kubectl get svc kube-train-service -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl -s -X POST http://$LB/reservations -H "Content-Type: application/json" \
  -d '{"passengerName":"Trace Test","trainId":"TGV-7042"}'
```

### Trace complète — livraison unique (cas nominal)

Trace `ed2aaffa8dfd4a4a242d03eca02ce722` — `RES-0BDE997D` — **11 segments** :

```
POST /reservations              kube-train-api   ← reservation.id = RES-0BDE997D
├─ ReservationRepository.save / SELECT / OutboxEventRepository.save
├─ Transaction.commit → INSERT reservations / INSERT outbox_events
└─ notification process         train-notification-service
   └─ notification send-email    train-notification-service
```

La branche notification est enfant du span HTTP → **propagation V4 confirmée**.

### La déduplication, VISIBLE dans la trace

Trace `86c7eccf374afb815421ad31d5652b2e` — `RES-E3791E7F` — **13 segments** :

```
POST /reservations              kube-train-api
├─ ... (spans DB)
├─ notification process         train-notification-service   ← livraison 1
│  └─ notification send-email    train-notification-service   ← email envoyé
└─ notification process         train-notification-service   ← livraison 2 (republiée)
                                                                  PAS de send-email
```

Le 2ᵉ `notification process` **sans** `send-email` = le fix d'idempotence rendu visible :
le span `process` est créé (extraction du contexte) **avant** le contrôle d'idempotence ;
le doublon est reconnu (`eventId` déjà vu) → `return` **avant** `sendEmailSpan`.

Preuve au span près : **14 segments avant le fix → 13 après**. Le span disparu est le
`send-email` du doublon — un seul email par `eventId`.

### Chercher / corréler dans Cloud Trace

| Objectif | Comment |
|---|---|
| Trouver la trace d'une réservation | Filtre `reservation.id = RES-0BDE997D` (barre « Ajouter un filtre ») |
| Voir l'ID sur le span racine | Span `POST /reservations` → onglet Attributs → `reservation.id` |
| Réduire le bruit (health checks, poller) | Filtre « Nom du segment » = `POST /reservations` |
| Trace → logs | Bouton « Afficher les journaux » d'un span (les logs portent `trace_id`) |

> Le filtre `reservation.id` renvoie **2 lignes** (span racine + span send-email portent
> tous deux l'attribut) : c'est la **même** trace, cliquer l'une ou l'autre ouvre l'arbre complet.

### Vérifier la dédup côté logs

```bash
kubectl logs -l app=notification-pod -c notification-container --tail=100 \
  | grep -iE "déjà traité|Email envoyé"
```
> Label du pod = `app=notification-pod` (pas `app=notification` — le sélecteur `-l app=notification`
> renvoie « No resources found »). Ou plus robuste : `kubectl logs deployment/notification-deployment -c notification-container`.
> Le `déjà traité` n'apparaît **que** si l'Outbox a republié — non déterministe.

---

## 8. Pièges rencontrés (récapitulatif)

| Piège | Symptôme | Cause / Fix |
|---|---|---|
| Trace coupée au commit | notification dans une autre trace | Contexte non propagé à travers l'Outbox → migration V4 |
| Collector `PermissionDenied` | traces/métriques non exportées après rebuild | KSA `default` non annoté → SA dédié `otel-collector-sa` déclaratif |
| Double email | `RES-...` traité 2× | Dédup sur `messageId` (transport) au lieu de `eventId` (métier) |
| Branche notification « absente » | trace incomplète juste après le POST | **Asynchrone** : elle arrive ~5-15 s plus tard (poller + Pub/Sub + ingestion) → rafraîchir |
| `No resources found` (logs) | grep vide | Mauvais label : `app=notification-pod`, ou `deployment/notification-deployment` |

---

## 9. À retenir

- Un **pattern Outbox** rompt la propagation de contexte automatique : le véhicule doit
  être **la ligne persistée** (colonnes traceparent), pas le contexte du thread.
- La branche asynchrone apparaît **décalée** dans le temps et **après** la fin du span
  racine — c'est la signature normale d'un traitement async dans un APM.
- **Idempotence = clé métier** (`eventId`), jamais la clé transport (`messageId`) : l'Outbox
  garantit *at-least-once*, le consumer idempotent transforme ça en *effectively-once*.
- Un **attribut métier sur le span racine** (`reservation.id`) fait le pont observabilité ↔
  métier : d'un ticket « ma résa RES-… » à la trace complète en un filtre.
- Sur GKE Autopilot, **toute** identité GCP passe par un **SA dédié + Workload Identity**
  déclaratif — sinon la dette manuelle revient à chaque rebuild.
