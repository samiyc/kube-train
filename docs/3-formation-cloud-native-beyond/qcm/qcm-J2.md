# 🎓 QCM J2 — OpenTelemetry, Observabilité & Distributed Tracing

> 8 questions couvrant les sujets de J2.
> Niveaux : ⭐ (basique), ⭐⭐ (intermédiaire), ⭐⭐⭐ (avancé/entretien)
>
> Répondre dans le fichier `template-reponse-qcm.md` (copie en `reponse-qcm-J2.md`).

---

## OpenTelemetry — Concepts fondamentaux

### Question 1 — Traces, spans et traceId ⭐

Dans OpenTelemetry, quelle est la différence entre une **trace**, un **span** et un **traceId** ?

Donne un exemple concret avec l'appel `POST /reservations` de kube-train.

---

### Question 2 — Agent Java vs instrumentation SDK ⭐⭐

Dans kube-train, l'agent OTel Java est attaché via `-javaagent` dans le Dockerfile. Un collègue propose de le remplacer par une instrumentation manuelle avec le SDK OTel directement dans le code Spring Boot.

A) Cite deux avantages de l'approche **agent**  
B) Cite deux avantages de l'approche **SDK manuel**  
C) Dans quel cas recommanderais-tu le SDK manuel ?

---

## OTel Collector & Architecture

### Question 3 — Rôle du OTel Collector ⭐⭐

Dans kube-train sur GKE, les services exportent leurs spans vers `http://otel-collector-service:4317` plutôt que directement vers Cloud Trace.

Pourquoi ce niveau d'indirection ? Cite trois raisons concrètes justifiant l'usage du Collector.

---

### Question 4 — Lecture des logs Collector ⭐⭐

On observe ces logs dans le pod OTel Collector :

```
2026-05-19T14:22:19.533Z   TracesExporter   resource spans: 1, spans: 119
2026-05-19T14:22:29.534Z   TracesExporter   resource spans: 2, spans: 58
```

A) Que signifie `resource spans: 2` ? Pourquoi passe-t-on de 1 à 2 ?  
B) Pourquoi le premier batch contient-il 119 spans alors que la cadence normale est 5-15 ?  
C) Ces logs correspondent à quel exporter dans la config du Collector ?

---

## Propagation de contexte

### Question 5 — W3C TraceContext et header `traceparent` ⭐⭐

Le header W3C `traceparent` propagé entre services HTTP a ce format :

```
traceparent: 00-9809bf57dbb2d0441ce355c53b57016e-5e3d2a1b9c4f8701-01
```

A) Décris la signification de chaque composant  
B) Que se passe-t-il si un service intermédiaire (load balancer, API Gateway) supprime ce header ?

---

### Question 6 — Pub/Sub vs Kafka : limite de propagation ⭐⭐⭐

Dans Cloud Trace, les traces de `kube-train-api` et `train-notification-service` apparaissent comme deux traces **séparées** (sans relation parent-enfant), alors qu'avec Kafka elles seraient liées.

A) Pourquoi l'agent OTel propage-t-il automatiquement le contexte avec Kafka mais pas avec Pub/Sub ?  
B) Que faudrait-il implémenter pour relier les deux traces dans Pub/Sub ?  
C) Est-ce une limitation d'OTel ou de Pub/Sub ?

---

## Flame graph & Analyse de performance

### Question 7 — Lecture de flame graph ⭐⭐⭐

Voici un extrait de la flame graph réelle capturée sur GKE pour `POST /reservations` :

```
POST /reservations                          25,093ms
├── ReservationRepository.save               3,808ms
│   └── Session.merge                        3,311ms
│       └── SELECT kube_train.reservations   2,155ms
├── OutboxEventRepository.save                 745µs
│   └── Session.persist                        274µs
└── Transaction.commit                          11ms
    ├── INSERT kube_train.reservations         2,43ms
    └── INSERT kube_train.outbox_events       2,277ms
```

A) Pourquoi `Session.merge` fait-il un SELECT avant l'INSERT ? En quoi `Session.persist` est-il différent ?  
B) La requête prend 25 secondes au total, mais les spans visibles ne totalisent que ~4 secondes. Où sont passées les ~21 secondes restantes ?  
C) Quel pattern architectural est confirmé par la présence des deux INSERTs sous le même `Transaction.commit` ?

---

## Métriques custom

### Question 8 — Micrometer et export OTel ⭐⭐

Dans `TrainService.java`, une métrique est incrémentée via :

```java
meterRegistry.counter("reservations.created", "train_id", train.id()).increment();
```

A) Cette métrique est visible dans Cloud Monitoring sans aucun code supplémentaire après l'ajout de l'agent OTel. Pourquoi ?  
B) Quelle est la différence entre un **counter**, un **gauge** et un **histogram** dans Micrometer ? Donne un exemple d'usage pour chacun dans le contexte de kube-train.
