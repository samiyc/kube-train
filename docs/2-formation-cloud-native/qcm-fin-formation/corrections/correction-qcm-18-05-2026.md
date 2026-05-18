# 📋 Correction QCM — Formation Cloud-Native 5J
**Date :** 18 mai 2026  
**Score :** ~20/25

---

## Résumé

| Résultat | Questions |
|----------|-----------|
| ✅ Correct | Q1, Q3, Q4, Q7, Q8, Q9, Q10, Q11, Q14, Q15, Q16, Q17, Q18, Q19, Q20, Q21, Q22, Q23, Q24 |
| ⚠️ Partiel | Q2 (`@ResponseBody` ≠ `@RequestBody`), Q8 (limite in-memory), Q17 (ArgoCD ne build pas) |
| ❌ Faux | Q5 (Swagger scanne auto), Q6 (consumer group ≠ offset), Q12 (cert-manager flow), Q13 (coché C au lieu de B), Q25 (Kafka vs Pub/Sub sur GKE) |

---

## Corrections détaillées

### ✅ Q1 — Richardson Maturity Model
Bonne réponse : **C — Niveau 2**. kube-train utilise des ressources séparées + bons verbes HTTP, mais pas de liens HATEOAS → niveau 2. Commentaire correct : 99% des API en prod s'arrêtent au niveau 2, le swagger à jour est déjà très bien.

---

### ⚠️ Q2 — @RestControllerAdvice
`@RestControllerAdvice` = `@ControllerAdvice` + **`@ResponseBody`** (pas `@RequestBody`).  
- `@RequestBody` : désérialise le corps d'une **requête entrante**  
- `@ResponseBody` : sérialise la **valeur de retour** dans la réponse HTTP → c'est ce qui permet de renvoyer un `ProblemDetail` en JSON automatiquement sans `ResponseEntity`.

---

### ✅ Q3 — 201 Created
Correct.

---

### ✅ Q4 — Records Java
Correct. Nuance avancée : `@Builder` de Lombok sur un `record` nécessite `@RecordBuilder` (plugin séparé) ou une config spéciale — l'essentiel (immutabilité, equals/hashCode/toString auto, compacité) est bien compris.

---

### ❌ Q5 — Swagger sans @Operation
**Réponse correcte : B**  
Springdoc OpenAPI scanne automatiquement tous les `@RequestMapping`, `@GetMapping`, `@PostMapping`... L'endpoint **apparaît quand même** dans Swagger UI, mais sans description/summary. `@Operation` n'est utile que pour la documentation, pas pour la visibilité.

---

### ❌ Q6 — Consumer Group
**Réponse correcte : C — Le consumer group**  
- **Offset** = curseur de position d'un consumer dans une partition (où il en est dans sa lecture)  
- **Consumer group** = groupe d'instances qui se répartissent les partitions sans duplication → c'est le mécanisme de scaling horizontal de Kafka.

---

### ✅ Q7 — DLT Spring Kafka 4.x
Correct : **B — 5 tentatives → `train-reservations-dlt`**  
Rappel : avant Spring Kafka 4.x le format était `{topic}.DLT` (point + majuscules). Depuis 4.x : `{topic}-dlt` (tiret + minuscules).

---

### ✅ Q8 — Idempotence (limite manquante)
Mécanisme bien décrit. **Limite importante à retenir :** la `ConcurrentHashMap` est **en mémoire**. Si le pod redémarre, elle est vidée → le notification-service retraitera des messages déjà envoyés (at-least-once delivery). La solution durable = persister les IDs traités en base de données (sujet formation 3 : Outbox Pattern).

---

### ✅ Q9 — Saga Pattern
Correct. Complément :
- **Chorégraphie** : services communiquent entre eux via events → découplé, mais difficile à debugger ("qui a échoué ?")
- **Orchestration** : un SagaManager central pilote les services → visibilité et rollback centralisés, mais couplage fort sur le manager
- Préférer orchestration pour les flux complexes (>3 services, compensations critiques)

---

### ✅ Q10 — Outbox Pattern
Excellente réponse. Précision sur le mécanisme complet :
1. `save(reservation)` + `insert(outbox_table, message_to_send)` dans la **même transaction JDBC**
2. Un poller/scheduler lit l'outbox et envoie le message Kafka
3. Il marque l'entrée outbox comme `SENT`
4. Kafka n'est jamais dans la transaction BDD → pas de double validation distribuée

Un CDC (Change Data Capture avec Debezium) peut remplacer le poller pour une solution encore plus robuste.

---

### ✅ Q11 — Secret Manager vs K8s Secrets
Correct. Bonus entretien : les K8s Secrets sont **base64** (pas chiffrés par défaut dans etcd). Le Secret Manager offre en plus : rotation automatique, audit trail complet, une source de vérité unique pour tous les environnements.

---

### ❌ Q12 — cert-manager / TLS
**Deux erreurs :**
1. **TLS = Transport Layer Security** (pas Transaction)
2. La question demandait le flux de provisionnement du certificat, pas le flux réseau

**Flux cert-manager complet :**
1. cert-manager détecte l'annotation `cert-manager.io/cluster-issuer` sur l'**Ingress**
2. Crée un objet **Certificate** → **CertificateRequest** → **Order** → **Challenge** (HTTP-01)
3. Let's Encrypt vérifie le domaine en appelant `/.well-known/acme-challenge/{token}`
4. cert-manager stocke le certificat obtenu dans un **Secret** Kubernetes
5. Le contrôleur Ingress (nginx) lit ce Secret pour la terminaison TLS

Objets K8s impliqués : `Ingress`, `Certificate`, `CertificateRequest`, `Order`, `Challenge`, `Secret`.

---

### ❌ Q13 — Cloud SQL Auth Proxy
**Réponse correcte : B**  
L'explication textuelle était juste (sidecar, évite d'exposer IP/mot de passe), mais la réponse cochée **C (VPN)** est incorrecte.

> Sidecar Cloud SQL Auth Proxy → expose `localhost:5432` dans le pod → l'appli se connecte en local → le sidecar établit un tunnel TLS vers Cloud SQL **authentifié via Workload Identity** (pas de mot de passe, pas d'IP publique à gérer).

---

### ✅ Q14 — Workload Identity (incomplet)
Bonne compréhension. **Solution concrète manquante :**
```bash
kubectl annotate serviceaccount default \
  --namespace default \
  iam.gke.io/gcp-service-account=399291708401-compute@developer.gserviceaccount.com \
  --overwrite
```
Cette commande est maintenant dans le pipeline CI/CD (idempotent grâce à `--overwrite`).

---

### ✅ Q15 — Error Budget
Correct : **B — 216 requêtes**  
Calcul : 43 200 × 0,5% = 216 requêtes "lentes" autorisées sur 30 jours.

---

### ✅ Q16 — 12 Factors / Factor 3
Correct : **B — Factor 3 — Config**.

---

### ✅ Q17 — GitOps vs CI/CD push (nuance)
Globalement correct. **Correction importante :** ArgoCD ne **build** pas — la construction de l'image reste dans le CI (GitHub Actions). ArgoCD **déploie** : il surveille un repo Git contenant les manifests K8s et les applique dans le cluster (pull-based).

> Avantage clé du GitOps : le cluster tire ses instructions depuis Git → état du cluster = état du repo → rollback = `git revert`.

---

### ✅ Q18 — Micrometer / SLF4J
Correct : **B — SLF4J**.  
Analogie complète : Micrometer est à Prometheus ce que SLF4J est à Logback → façade d'abstraction permettant de changer le backend (Prometheus, Datadog, CloudWatch...) sans changer le code applicatif.

---

### ✅ Q19 — Conteneurs immuables
Correct. Un pod modifié manuellement → changement perdu au prochain déploiement + dérive par rapport à Git → source de vérité = Git + Secret Manager uniquement.

---

### ✅ Q20 — 3 piliers de l'observabilité (outils manquants)
Piliers corrects. **Outils dans kube-train :**
- **Logs** → Cloud Logging + Logback format JSON ECS (via `logstash-logback-encoder`)
- **Métriques** → Micrometer + Prometheus (`/actuator/prometheus` + Cloud Monitoring)
- **Traces** → **non implémenté** (prévu en formation 3 avec `micrometer-tracing-bridge-otel`)

---

### ✅ Q21 — Pub/Sub vs Kafka
Correct et bien justifié. Récap :
- **Pub/Sub** → serverless, IAM natif, DLQ intégrée, idéal GCP cloud-native
- **Kafka** → replay infini, event sourcing, multi-cloud, forte volumétrie avec SLA strict

---

### ✅ Q22 — Spring Profiles
Correct. Rappel précis du code :
- `@Profile("gcp")` → active `PubSubReservationEventPublisher` (bean entier)
- `@Profile("!gcp")` → active `KafkaReservationEventPublisher` (bean entier)
- `@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")` → active les beans Kafka seulement si la property est à `true`

---

### ✅ Q23 — Crash Alpine + gRPC
Correct : **B**. Résumé :

| Image | libc | Taille | Compatible gRPC |
|-------|------|--------|----------------|
| `21-jre-alpine` | musl | ~80 MB | ❌ SIGSEGV |
| `21-jre-jammy` | glibc | ~200 MB | ✅ |

---

### ✅ Q24 — Workload Identity après recréation (solution manquante)
Bonne analyse. Compléter avec la solution :
```bash
kubectl annotate serviceaccount default \
  iam.gke.io/gcp-service-account=... --overwrite
```
Maintenant intégré dans le CI/CD — déclenché à chaque déploiement.

---

### ❌ Q25 — Architecture E2E (erreur messaging)
**Erreur principale : Kafka mentionné à la place de Pub/Sub.**  
Sur GKE avec profil `gcp`, c'est **Pub/Sub** uniquement. Kafka n'existe qu'en local.

**Flux correct en production GKE :**
```
POST /reservations
  → kube-train-api (profils: postgres, gcp)
  → PubSubReservationEventPublisher
  → Pub/Sub topic "train-reservations"
  → subscription "notification-subscription"
  → PubSubReservationEventConsumer (profil: gcp)
      ├─ Idempotence : ConcurrentHashSet sur messageId
      ├─ Succès : consumer.ack() → message supprimé
      └─ Échec  : consumer.nack() → retry → DLQ "train-reservations-dlq" après 5 tentatives
```

---

## Points à retenir pour la prochaine fois

1. **Q5** — Swagger scanne les annotations `@RequestMapping` automatiquement, `@Operation` n'est qu'un enrichissement documentaire
2. **Q6** — Consumer group = répartition sans doublon (scaling). Offset = curseur de lecture (position)
3. **Q12** — TLS = **Transport** Layer Security. Connaître le flux cert-manager (Certificate → Challenge → Secret)
4. **Q13** — Toujours cocher B (sidecar Auth Proxy) et non C (VPN)
5. **Q25** — Sur GKE = **Pub/Sub**. Kafka = local Docker uniquement. Ne pas confondre les deux dans le discours entretien
