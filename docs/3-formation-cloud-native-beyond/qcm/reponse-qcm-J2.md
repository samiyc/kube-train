# Template réponses QCM — Formation 3

> Copier ce fichier sous le nom `reponse-qcm-Jx.md` (ex: `reponse-qcm-J1.md`)
> avant de remplir les réponses. Ne pas commiter les réponses.
>
> Format :
> - QCM (A/B/C/D) : indiquer uniquement la lettre
> - Question ouverte : réponse libre, 2-5 lignes suffisent

---

## QCM J2 — OpenTelemetry, Observabilité & Distributed Tracing

---

### Question 1

**Réponse :**
Dans OpenTelemetry, une trace permet de suivre le process d'un appel HTTP vers les services sous-jacent avec notamment les appel bdd et les message kafta.
Une trace conctient plusieurs span identifié par des span ID et un ID unique de trace pérmettant de relier tous les span entre eux, le traceId.
il y a aussi le parentSpanId qui permet des relier les span entre eux parents/enfants.

exemple:
```
POST /reservations (trace root / trace id)
--> (span) création de la réservation
--> (span) création de l'event dans la table outbox pour l'envoi de message via kafka
--> (span) submit de la transaction. les deux updates en même temps => atomicité
```
---

### Question 2

**Réponse :**
- A) Cite deux avantages de l'approche agent  
Pas de modification du code. Configuration simplifié
- B) Cite deux avantages de l'approche SDK manuel  
Plus de réglages/granularité possible. Mais il faut modifier le code de l'application
- C) Dans quel cas recommanderais-tu le SDK manuel ?  
Quand il y a besoin d'une analyse très fine des temps de reponse, pour les spans. Quand on veut passer d'autre informations custom de suivi dans le parenttrace en complétment des ids. comme des versions, tags etc.
---

### Question 3

**Réponse :**
Ca permet de découpler le backend du système cloud trace ou jaeger.
Ca permet de centraliser l'authentification et la configuration. 
Ca permet de faire des retry, si le gestionnaire cloud des trace est KO.

---

### Question 4

**Réponse :**  
- A) Que signifie resource spans: 2 ? Pourquoi passe-t-on de 1 à 2 ?  
Il y a deux services actif. Le service des apis et celui des notifications.
- B) Pourquoi le premier batch contient-il 119 spans alors que la cadence normale est 5-15 ?  
C'est les spans lié au lancement de l'application springboot.
- C) Ces logs correspondent à quel exporter dans la config du Collector ?  
tracesExporter
---

### Question 5

**Réponse :**
```
# Signification de chaque composant
traceparent: 00-9809bf57dbb2d0441ce355c53b57016e-5e3d2a1b9c4f8701-01

00 => Version
9809bf57dbb2d0441ce355c53b57016e => TraceId
5e3d2a1b9c4f8701 => SpanId
01 => Tag
```
Si ce header est supprimer on ne pourrais pas reconstituer la suite de la trace comme par exemple en J2, le passage du service des apis vers le service des notifications.

---

### Question 6

**Réponse :**
Kafka transfert automatiquement la trace via les attributes/entete des messages alors que sur pub/sub il faut le faire soit même. ce qui implique des modifications du code.
Une fois le traceParent transferé au sous-service via pub/sub, la flamme/trace complète peut-être reconstituée.

---

### Question 7

**Réponse :**
- A) Pourquoi Session.merge fait-il un SELECT avant l'INSERT ? En quoi Session.persist est-il différent ?  
Ca permet de vérifier via select qu'il n'y ai pas de conflit avant de faire l'insert.
- B) La requête prend 25 secondes au total, mais les spans visibles ne totalisent que ~4 secondes. Où sont passées les ~21 secondes restantes ?  
C'est le temps que le pods en hibernation démarre, une fois démarré les transactions s'execute très vite.
- C) Quel pattern architectural est confirmé par la présence des deux INSERTs sous le même Transaction.commit ?  
Ca correspond à l'atomicité du pattern outbox

---

### Question 8

**Réponse :**  
A) Cette métrique est visible dans Cloud Monitoring sans aucun code supplémentaire après l'ajout de l'agent OTel. Pourquoi ?  
Otel expose les metrics custom géré en interne de l'appli exposé via micrometer.
B) Quelle est la différence entre un counter, un gauge et un histogram dans Micrometer ? Donne un exemple d'usage pour chacun dans le contexte de kube-train.  
```
Counter => nombre de réservations sur un train
Gauge => nombre d'utilisateur
Histogram => historiques des erreur lors de la réservation, pour identifier les pics
```

---

*Score estimé : 6/8 — à faire corriger par Copilot*
