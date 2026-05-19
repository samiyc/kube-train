# 🛠️ Cheat-Sheet — Infrastructure & Commandes GCP/K8s

> **Principe fondamental** : Toute commande nécessaire pour reproduire l'infrastructure à partir de
> zéro doit être documentée ici ou dans le code. Zéro "tribal knowledge" stocké uniquement dans la
> tête d'un développeur. C'est le principe de l'**Infrastructure as Code (IaC)** et du **GitOps** :
> Git est la source de vérité unique. Quelqu'un qui clone ce repo doit pouvoir tout reconstruire
> depuis un compte GCP vide.
>
> Voir aussi : `docs/2-formation-cloud-native/deploy-kube-train-to-gcp.md` pour le détail complet
> de la Formation 2 (projet GCP, cluster GKE, HTTPS, Cloud SQL, CI/CD, Pub/Sub).

---

## 🏗️ Setup from scratch — Ordre des opérations

Pour recréer l'infrastructure complète depuis un compte GCP vide :

```
1. Projet GCP + APIs            → deploy-kube-train-to-gcp.md § "Init du projet GCP"
2. Artifact Registry            → deploy-kube-train-to-gcp.md § "Configuration registry"
3. GitHub Actions SA + Droits   → deploy-kube-train-to-gcp.md § "Github actions"
4. Workload Identity Federation → deploy-kube-train-to-gcp.md § "Workload Identity Federation"
5. GKE Cluster                  → deploy-kube-train-to-gcp.md § "Deployment vers GKE Autopilot"
6. Cloud SQL                    → deploy-kube-train-to-gcp.md § "Cloud SQL / PostgreSQL"
7. Secret Manager               → deploy-kube-train-to-gcp.md § "GCP Secret Manager"
8. Pub/Sub                      → ci-dessous § "Pub/Sub"
9. HTTPS (nginx + cert-manager) → deploy-kube-train-to-gcp.md § "HTTPS sur GKE"
10. git push → CI/CD déploie tout automatiquement
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

# --- Logs ---
kubectl logs -f deployment/kube-train-deployment -c api-container
kubectl logs -f deployment/notification-deployment
kubectl logs -f deployment/otel-collector                           # F3-J2+
kubectl rollout restart deployment/kube-train-deployment

# --- Etat du cluster ---
kubectl get pods
kubectl get services
kubectl get deployments
kubectl describe pod <nom-du-pod>    # debug crash/OOMKilled
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
# À compléter lors de J3
# Installation ArgoCD sur GKE :
# kubectl create namespace argocd
# kubectl apply -n argocd -f https://raw.githubusercontent.com/.../install.yaml
```

### F3-J4: Sécurité (OAuth2, NetworkPolicies, Trivy)
```bash
# À compléter lors de J4
# Keycloak local → docker compose up -d keycloak
# NetworkPolicies → kubectl apply -f k8s/network-policy-*.yaml
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

