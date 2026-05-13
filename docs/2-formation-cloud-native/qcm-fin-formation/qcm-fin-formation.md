# 🎓 QCM Final — Formation Cloud-Native 5 Jours

> 25 questions couvrant J1 à J5. Mix de QCM, questions ouvertes et questions d'architecture.
> Niveaux : ⭐ (basique), ⭐⭐ (intermédiaire), ⭐⭐⭐ (avancé/entretien)
>
> Répondre dans le fichier `reponse-au-qcm.md`.

---

## J1 — API-First, Swagger & Gestion des erreurs

### Question 1 — Richardson Maturity Model (J1) ⭐

À quel niveau du Richardson Maturity Model se situe une API qui utilise des ressources séparées (`/trains`, `/reservations`) avec les bons verbes HTTP (GET, POST, DELETE) mais sans liens HATEOAS ?

A) Niveau 0  
B) Niveau 1  
C) Niveau 2  
D) Niveau 3  

---

### Question 2 — ProblemDetail RFC 9457 (J1) ⭐⭐

En Spring Boot 4, quelle est la différence entre `@ControllerAdvice` et `@RestControllerAdvice` ? Pourquoi kube-train utilise-t-il `@RestControllerAdvice` ?

---

### Question 3 — Codes HTTP (J1) ⭐

Quel code HTTP doit-on renvoyer quand une ressource est créée avec succès (ex: `POST /reservations`) ?

A) 200 OK  
B) 201 Created  
C) 204 No Content  
D) 202 Accepted  

---

### Question 4 — Records Java (J1) ⭐⭐

Pourquoi utilise-t-on des `record` Java pour les DTOs dans kube-train plutôt que des classes classiques ? Citez au moins 2 avantages.

---

### Question 5 — Swagger/OpenAPI (J1) ⭐⭐⭐

Un développeur ajoute un endpoint `DELETE /trains/{id}` mais oublie l'annotation `@Operation(summary = "...")`. Quelles sont les conséquences ? L'endpoint est-il toujours visible dans Swagger UI ?

A) L'endpoint n'apparaît pas dans Swagger UI  
B) L'endpoint apparaît mais sans description — Swagger scanne les `@RequestMapping` automatiquement  
C) L'application ne compile pas  
D) L'endpoint apparaît uniquement si `@Tag` est présent sur la classe  

---

## J2 — Kafka, Idempotence & Patterns distribués

### Question 6 — Concepts Kafka (J2) ⭐

Dans Kafka, quel composant permet à plusieurs instances d'un même service de se répartir les messages d'un topic sans duplication ?

A) Le topic  
B) La partition  
C) Le consumer group  
D) L'offset  

---

### Question 7 — DLT / Dead Letter Topic (J2) ⭐⭐

Dans kube-train, après combien de tentatives échouées un message est-il envoyé dans la DLT ? Comment se nomme la DLT en Spring Kafka 4.x ?

A) 3 tentatives → `train-reservations.DLT`  
B) 5 tentatives → `train-reservations-dlt`  
C) 10 tentatives → `train-reservations.DLT`  
D) 5 tentatives → `train-reservations.DLT`  

---

### Question 8 — Idempotence (J2) ⭐⭐⭐

Expliquez pourquoi l'idempotence est nécessaire dans un consumer Kafka (ou Pub/Sub). Décrivez le mécanisme utilisé dans le `notification-service` de kube-train et ses limites en cas de redémarrage du pod.

---

### Question 9 — Saga Pattern (J2) ⭐⭐⭐

Quelle est la différence entre une Saga par **orchestration** et une Saga par **chorégraphie** ? Dans quel cas préférer l'une ou l'autre ?

---

### Question 10 — Outbox Pattern (J2) ⭐⭐

Quel problème résout le pattern Outbox ? Pourquoi un simple `save()` + `kafkaTemplate.send()` dans le même service ne suffit-il pas à garantir la cohérence ?

---

## J3 — Sécurité, Cloud SQL & Observabilité

### Question 11 — Secret Manager vs K8s Secrets (J3) ⭐⭐

Pourquoi stocker les secrets dans GCP Secret Manager plutôt que directement dans un `Secret` Kubernetes ? Citez au moins 2 raisons.

---

### Question 12 — cert-manager (J3) ⭐⭐

Décrivez le flux complet quand cert-manager provisionne un certificat TLS Let's Encrypt pour `api.34.78.39.236.nip.io`. Quels objets Kubernetes sont impliqués ?

---

### Question 13 — Cloud SQL Auth Proxy (J3) ⭐⭐⭐

Dans kube-train sur GKE, comment l'API se connecte-t-elle à Cloud SQL ? Pourquoi utiliser un sidecar Auth Proxy plutôt qu'une connexion directe IP + mot de passe ?

A) Connexion directe avec IP publique et certificat SSL  
B) Sidecar Cloud SQL Auth Proxy qui expose `localhost:5432`, authentifié via Workload Identity  
C) VPN entre le cluster et Cloud SQL  
D) Cloud SQL Connector Java intégré dans l'application  

---

### Question 14 — Workload Identity (J3) ⭐⭐⭐

Expliquez le mécanisme de Workload Identity sur GKE. Quel est le lien entre le ServiceAccount Kubernetes et le ServiceAccount GCP ? Que se passe-t-il si l'annotation est manquante ?

---

### Question 15 — SLI/SLO/Error Budget (J3) ⭐⭐

Un SLO définit que 99.5% des requêtes doivent répondre en < 500ms sur 30 jours. Ce mois-ci, vous avez eu 43 200 requêtes. Combien de requêtes lentes pouvez-vous "dépenser" avant d'épuiser l'error budget ?

A) 43  
B) 216  
C) 432  
D) 2 160  

---

## J4 — 12 Factors, GitOps & Observabilité

### Question 16 — 12 Factors (J4) ⭐⭐

Identifiez quel facteur du 12-Factor App est illustré par cette pratique : *"La configuration (URL base de données, clé API) est injectée via des variables d'environnement, jamais codée en dur dans le code source."*

A) Factor 1 — Codebase  
B) Factor 3 — Config  
C) Factor 5 — Build, release, run  
D) Factor 10 — Dev/Prod Parity  

---

### Question 17 — GitOps vs CI/CD push (J4) ⭐⭐

Quelle est la différence fondamentale entre le CI/CD push-based (GitHub Actions) utilisé dans kube-train et le GitOps pull-based (ArgoCD) ? Quel avantage majeur apporte le GitOps ?

---

### Question 18 — Micrometer/Prometheus (J4) ⭐⭐

Dans kube-train, quel est le rôle de Micrometer par rapport à Prometheus ? Complétez l'analogie : *"Micrometer est à Prometheus ce que ______ est à Logback."*

A) Jackson  
B) SLF4J  
C) Spring MVC  
D) JUnit  

---

### Question 19 — Conteneurs immuables (J4) ⭐⭐⭐

Un collègue fait un `kubectl exec -it pod -- vi /app/config.yaml` pour corriger un bug en production. Expliquez pourquoi c'est un anti-pattern et quelle est la bonne approche.

---

### Question 20 — OpenTelemetry (J4) ⭐⭐

Quels sont les 3 piliers de l'observabilité ? Pour chacun, donnez l'outil/technologie utilisé (ou prévu) dans kube-train.

---

## J5 — Pub/Sub, CI/CD Multi-service & Consolidation

### Question 21 — Pub/Sub vs Kafka (J5) ⭐⭐

Dans quel scénario Kafka est-il préférable à Pub/Sub, et inversement ? Justifiez le choix de Pub/Sub pour kube-train sur GKE.

---

### Question 22 — Spring Profiles (J5) ⭐⭐⭐

Dans kube-train, comment le switch entre Kafka (local) et Pub/Sub (GKE) est-il implémenté au niveau du code ? Quelle est la différence entre `@Profile("gcp")` et `@ConditionalOnProperty` ?

---

### Question 23 — Crash Alpine + gRPC (J5) ⭐⭐⭐

Après avoir ajouté `google-cloud-pubsub` comme dépendance, l'application crash au démarrage sur GKE sans aucun log Java. Quelle est la cause et la solution ?

A) La JVM manque de mémoire → augmenter les `resources.limits.memory`  
B) `netty-tcnative` (lib native gRPC) est compilé pour glibc mais Alpine utilise musl → changer pour `eclipse-temurin:21-jre-jammy`  
C) Le topic Pub/Sub n'existe pas → le créer avec `gcloud pubsub topics create`  
D) Le ServiceAccount n'a pas les permissions → ajouter `roles/pubsub.subscriber`  

---

### Question 24 — Workload Identity après recréation (J5) ⭐⭐⭐

Après avoir supprimé et recréé le cluster GKE, le Cloud SQL Auth Proxy renvoie `403 Permission Denied`. L'IAM binding GCP est toujours en place. Quel est le problème et comment le résoudre ?

---

### Question 25 — Architecture complète (J5) ⭐⭐⭐

Dessinez (ou décrivez) le flux complet d'une réservation dans kube-train en production sur GKE : depuis le `POST /reservations` jusqu'à la réception par le notification-service. Mentionnez : les profils Spring actifs, le mécanisme de messaging, la gestion des erreurs (DLQ), et comment l'idempotence est assurée.