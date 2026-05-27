# 🛠️ Cheat-Sheet — Infrastructure & Commandes GCP/K8s

> **Principe fondamental** : Toute commande nécessaire pour reproduire l'infrastructure à partir de
> zéro doit être documentée ici ou dans le code. Zéro "tribal knowledge" stocké uniquement dans la
> tête d'un développeur. C'est le principe de l'**Infrastructure as Code (IaC)** et du **GitOps** :
> Git est la source de vérité unique. Quelqu'un qui clone ce repo doit pouvoir tout reconstruire
> depuis un compte GCP vide.
>
> Voir aussi : `docs/2-formation-cloud-native/runbook.md` pour le détail complet
> de la Formation 2 (projet GCP, cluster GKE, HTTPS, Cloud SQL, CI/CD, Pub/Sub).

---

## 🏗️ Setup from scratch — Ordre des opérations

Pour recréer l'infrastructure complète depuis un compte GCP vide :

```
1. Projet GCP + APIs            → runbook.md (F2) § "Init du projet GCP"
2. Artifact Registry            → runbook.md (F2) § "Configuration registry"
3. GitHub Actions SA + Droits   → runbook.md (F2) § "Github actions"
4. Workload Identity Federation → runbook.md (F2) § "Workload Identity Federation"
5. GKE Cluster                  → runbook.md (F2) § "Deployment vers GKE Autopilot"
6. Cloud SQL                    → runbook.md (F2) § "Cloud SQL / PostgreSQL"
7. Secret Manager               → runbook.md (F2) § "GCP Secret Manager"
8. Pub/Sub                      → ci-dessous § "Pub/Sub"
9. HTTPS (nginx + cert-manager) → runbook.md (F2) § "HTTPS sur GKE"
10. ArgoCD (optionnel, F3-J3)   → ci-dessous § "F3-J3: ArgoCD & GitOps"
11. Network Policies (F3-J4)    → ci-dessous § "F3-J4: Sécurité"
12. git push → CI/CD build + Trivy scan + commit tags → ArgoCD sync auto
```

---

## 📡 Pub/Sub (Formation 2)

```bash
# Créer les topics et la subscription
gcloud pubsub topics create train-reservations --project=kube-train-project
gcloud pubsub topics create train-reservations-dlq --project=kube-train-project
gcloud pubsub subscriptions create notification-subscription \
  --topic=train-reservations \
  --project=kube-train-project

# Droits IAM pour publier/consommer (compute SA = SA par défaut des pods GKE)
gcloud pubsub topics add-iam-policy-binding train-reservations \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/pubsub.publisher" --project=kube-train-project

gcloud pubsub subscriptions add-iam-policy-binding notification-subscription \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/pubsub.subscriber" --project=kube-train-project
```

---

## 🔄 Commandes quotidiennes

```bash
# --- Contexte K8s ---
kubectl config get-contexts                                         # lister les clusters
kubectl config use-context gke_kube-train-project_europe-west1_kube-train-cluster
kubectl config use-context minikube

# --- Scale up/down (économiser les crédits) ---
kubectl scale deployment kube-train-deployment --replicas=0        # éteindre l'API
kubectl scale deployment kube-train-deployment --replicas=1        # rallumer
kubectl scale deployment notification-deployment --replicas=0
kubectl scale deployment notification-deployment --replicas=1
kubectl scale deployment otel-collector --replicas=0               # éteindre le Collector (F3-J2+)
kubectl scale deployment otel-collector --replicas=1

# --- Recréer le cluster après suppression ---
gcloud container clusters create-auto kube-train-cluster --region=europe-west1
gcloud container clusters get-credentials kube-train-cluster --region=europe-west1
# ⚠️ Ré-annoter le K8s SA après recréation (perdu avec le cluster)
kubectl annotate serviceaccount default \
  iam.gke.io/gcp-service-account=399291708401-compute@developer.gserviceaccount.com \
  --namespace=default
# Puis déclencher la CI/CD :
git commit --allow-empty -m "chore: redeploy après recréation cluster" && git push

# --- Logs (live follow) ---
kubectl logs -f deployment/kube-train-deployment -c api-container
kubectl logs -f deployment/notification-deployment
kubectl logs -f deployment/otel-collector                           # F3-J2+

# --- Logs (dernières lignes / debug) ---
kubectl logs deployment/kube-train-deployment -c api-container --tail=50
kubectl logs deployment/kube-train-deployment -c cloud-sql-proxy --tail=30
kubectl logs <pod-name> -c api-container --previous    # logs du restart précédent (crash debug)
kubectl logs deployment/notification-deployment --tail=50

# --- Restart ---
kubectl rollout restart deployment/kube-train-deployment

# --- Etat du cluster ---
kubectl get pods
kubectl get services
kubectl get deployments
kubectl describe pod <nom-du-pod>    # debug crash/OOMKilled
```

---

## 🏠 Développement local (IntelliJ + Docker Compose)

```bash
# --- Prérequis ---
# 1. Minikube (pour PostgreSQL K8s local ou tests kubectl)
minikube start --driver=docker

# 2. Docker Compose (Kafka, Keycloak, PostgreSQL, Jaeger)
docker compose up -d
# Services exposés :
#   - Kafka        : localhost:9092
#   - PostgreSQL   : localhost:5432  (user: postgres, pass: postgres)
#   - Keycloak     : localhost:8180  (admin/admin, realm kube-train auto-importé)
#   - Jaeger UI    : localhost:16686

# --- Lancement via IntelliJ (recommandé) ---
# Configs disponibles dans .run/ (auto-détectées par IntelliJ) :
#   KubeTrainApi (dev)      : profil postgres, KAFKA_ENABLED=true, TRAIN_API_KEY=dev-key
#   KubeTrainApi (secured)  : profil postgres+secured (OAuth2 Keycloak), TRAIN_API_KEY=dev-key
#   NotificationService     : pas de profil, Kafka toujours actif → port 8081

# --- Port-forward Minikube (si app déployée dans Minikube) ---
kubectl config use-context minikube
kubectl port-forward service/kube-train-service 8080:80
# ⚠️ N'utilise PAS le port-forward en même temps qu'IntelliJ sur le même port 8080
#    → curl depuis WSL irait sur GKE, pas sur l'app locale

# --- Tests OAuth2 — PowerShell (Windows) ---
# Obtenir un token JWT
$resp = Invoke-RestMethod -Method POST `
  -Uri "http://localhost:8180/realms/kube-train/protocol/openid-connect/token" `
  -Body "grant_type=password&client_id=kube-train-api&client_secret=kube-train-secret&username=testuser&password=test123"
$token = $resp.access_token

# Endpoints publics (sans token)
Invoke-RestMethod -Uri "http://localhost:8080/trains"
Invoke-RestMethod -Uri "http://localhost:8080/"

# GET /secure (JWT + X-API-KEY requis)
Invoke-RestMethod -Uri "http://localhost:8080/secure" `
  -Headers @{"Authorization"="Bearer $token"; "X-API-KEY"="dev-key"}

# POST /reservations (JWT requis avec profil secured)
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/reservations" `
  -Headers @{"Authorization"="Bearer $token"; "Content-Type"="application/json"} `
  -Body '{"passengerName":"Jean Dupont","trainId":"TGV-7042"}'

# Décoder le payload JWT (voir sub, exp, preferred_username)
$payload = $token.Split('.')[1]
$padded = $payload + ('=' * ((4 - $payload.Length % 4) % 4))
[System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($padded)) | ConvertFrom-Json

# --- Tests — WSL/bash (si port-forward désactivé, app tournant dans IntelliJ) ---
TOKEN=$(curl -s -X POST http://localhost:8180/realms/kube-train/protocol/openid-connect/token \
  -d "grant_type=password&client_id=kube-train-api&client_secret=kube-train-secret&username=testuser&password=test123" \
  | jq -r .access_token)
curl http://localhost:8080/secure -H "Authorization: Bearer $TOKEN" -H "X-API-KEY: dev-key"

# --- Arrêter ---
docker compose down          # arrêter tous les services Docker
minikube stop                # arrêter Minikube (ne détruit pas les données)
```

---

## 🗓️ Setup par journée de formation (F3)

### F3-J1: Flyway, Outbox Pattern & Spring Cloud Contract
```
Pas de commandes GCP à lancer.
Tout est du code (migrations SQL, OutboxPoller, contracts YAML).
```

### F3-J2: OpenTelemetry & Observabilité distribuée
```bash
# Activer l'API Cloud Trace (une seule fois)
gcloud services enable cloudtrace.googleapis.com --project=kube-train-project

# Donner le rôle cloudtrace.agent au compute SA (normalement déjà dans roles/editor)
gcloud projects add-iam-policy-binding kube-train-project \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/cloudtrace.agent"

# Vérifier que l'OTel Collector tourne après déploiement
kubectl get pods -l app=otel-collector
kubectl logs -f deployment/otel-collector

# Voir les traces dans Cloud Trace
# GCP Console → Cloud Trace → Liste des traces → filtrer kube-train-api
# Ou directement : https://console.cloud.google.com/traces/list?project=kube-train-project
```

### F3-J3: ArgoCD & GitOps
```bash
# ─── Installation ArgoCD sur GKE (une seule fois) ───

# 1. Créer le namespace et installer ArgoCD (manifests officiels)
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 2. Attendre que tous les pods ArgoCD soient Ready (~2-3 min sur Autopilot)
kubectl wait --for=condition=available deployment -l app.kubernetes.io/part-of=argocd -n argocd --timeout=300s
kubectl get pods -n argocd

# 3. Récupérer le mot de passe admin initial
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d
# Username: admin

# 4. Exposer l'UI ArgoCD via port-forward (accès local)
kubectl port-forward svc/argocd-server -n argocd 8443:443
# → Ouvrir https://localhost:8443

# 5. Appliquer l'Application (déclenche le premier sync)
kubectl apply -f k8s/argocd/application.yaml

# ─── CLI ArgoCD (optionnel, pour rollback/debug) ───

# Installer la CLI : https://argo-cd.readthedocs.io/en/stable/cli_installation/
# Login :
argocd login localhost:8443 --username admin --password <password> --insecure

# Vérifier l'état de l'app :
argocd app get kube-train
argocd app diff kube-train          # voir le diff Git ↔ cluster
argocd app sync kube-train          # sync manuel si besoin
argocd app history kube-train       # historique des déploiements
argocd app rollback kube-train <id> # rollback à un déploiement précédent

# ─── Suppression ArgoCD (économie de crédits post-J3) ───
kubectl delete -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl delete namespace argocd
# ⚠️ Après suppression, les déploiements continuent de tourner (ArgoCD ne détruit pas les apps)
# Mais il n'y a plus de self-heal ni de sync auto → revenir au modèle push si besoin
```

### F3-J4: Sécurité (OAuth2, NetworkPolicies, Trivy)
```bash
# ─── Local : OAuth2 avec Keycloak ───

# 1. Démarrer Keycloak (port 8180, realm importé automatiquement)
docker compose up -d keycloak
# → Admin UI : http://localhost:8180 (admin/admin)
# → Realm kube-train créé automatiquement avec users et clients

# 2. Lancer l'API avec le profil "secured"
cd kube-train-api
SPRING_PROFILES_ACTIVE=postgres,secured ./mvnw spring-boot:run

# 3. Obtenir un token JWT (password grant, pour les tests manuels)
TOKEN=$(curl -s -X POST http://localhost:8180/realms/kube-train/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=kube-train-api" \
  -d "client_secret=kube-train-secret" \
  -d "username=testuser" \
  -d "password=test123" | jq -r .access_token)

# 4. Tester un endpoint protégé
curl http://localhost:8080/secure -H "Authorization: Bearer $TOKEN"
# → 200 OK (avec token valide)
curl http://localhost:8080/secure
# → 401 Unauthorized (sans token)

# 5. Les endpoints publics restent accessibles sans token
curl http://localhost:8080/trains
# → 200 OK (pas besoin de token)

# ─── GKE : Network Policies (déployées par la CI automatiquement) ───

# Vérifier les NetworkPolicies appliquées
kubectl get networkpolicies
# → default-deny-ingress, allow-ingress-api, allow-ingress-otel-collector

# Tester la connectivité (depuis un pod de debug)
kubectl run debug --rm -it --image=alpine -- sh
# Dans le pod : apk add curl && curl kube-train-service:80/trains
# → Devrait marcher (intra-namespace autorisé pour l'API)

# ─── CI : Trivy scan ───
# Automatique : intégré dans le job "build" de deploy.yml
# Si une image a une CVE CRITICAL → le build échoue
# Ignorer un faux positif : créer .trivyignore à la racine du service
```

### F3-J5: Qualité (Cucumber, SonarCloud)
```bash
# À compléter lors de J5
# Connexion SonarCloud → https://sonarcloud.io → import GitHub repo
```

---

## 🔍 Cloud Logging — Requêtes utiles

```
# Tous les logs applicatifs de l'API
resource.type="k8s_container"
resource.labels.container_name="api-container"
jsonPayload.log.logger=~"com.kubetrain"

# Logs du OTel Collector (voir si les spans arrivent)
resource.type="k8s_container"
resource.labels.container_name="otel-collector"

# Filtrer par reservationId
resource.type="k8s_container"
jsonPayload.message=~"RES-XXXXXXXX"

# Erreurs uniquement
severity="ERROR" OR severity="WARNING"
```

