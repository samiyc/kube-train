
# KUBE-TRAIN to GCP

## Deployment vers GKE
git push → GitHub Actions déclenché :  
 1. mvn test
 2. docker build + push → Artifact Registry
 3. kubectl apply → GKE Autopilot (moins chère)

Pour démarrer concrètement :
 1. Créer un Artifact Registry sur GCP (pour stocker tes images)
 2. Configurer l'auth entre GitHub et GCP (via Workload Identity Federation — pas de clé JSON qui traîne)
 3. Créer un workflow GitHub Actions qui build, push, et déploie
 4. Adapter tes manifests pour pointer vers l'image dans Artifact Registry au lieu de l'image locale


## Cloud shell: Init du projet GCP
```
gcloud projects create kube-train-project --name="Kube Train"  
gcloud config set project kube-train-project  
#=> Billing => Activer la facturation  
```

#### Configuration registry > container > repository
```
gcloud services enable \
   artifactregistry.googleapis.com \
   container.googleapis.com
gcloud artifacts repositories create kube-train-repo \
   --repository-format=docker \
   --location=europe-west1 \
   --description="Images Docker pour kube-train"
```

## WSL: Push de l'image docker
#### Install gcloud
```
curl https://sdk.cloud.google.com | bash
exec -l $SHELL  #restart shell
```

#### Config gcloud
```
gcloud auth login    # Login simple
gcloud init          # Login + sélection du projet
gcloud auth configure-docker europe-west1-docker.pkg.dev
```

#### Depuis /kube-train-api/ via WSL
```
docker build -t europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api:v4 .
docker push europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api:v4
```

#### Deployment vers GKE Autopilot
```
gcloud components install gke-gcloud-auth-plugin   # Installer AVANT de créer le cluster
gcloud container clusters create-auto kube-train-cluster --region=europe-west1
gcloud container clusters get-credentials kube-train-cluster --region=europe-west1
kubectl config current-context    # => gke_kube-train-project_europe-west1_kube-train-cluster
kubectl get nodes                 # Pas de node par default avec GKE Autopilot
```

#### Gestion du cluster (économiser les crédits)
GKE Autopilot n'a pas de start/stop natif — on scale les pods à 0 pour "éteindre"
```
# Nombre de pods => Start / Shutdown / turnoff / kill
kubectl scale deployment kube-train-deployment --replicas=0
kubectl scale deployment kube-train-deployment --replicas=1

# Vérifier l'état
kubectl get pods

# Supprimer le cluster complètement (destroy, ~10 min pour recréer)
gcloud container clusters delete kube-train-cluster --region=europe-west1

# --- Recréer le cluster (après suppression) ---
gcloud container clusters create-auto kube-train-cluster --region=europe-west1
gcloud container clusters get-credentials kube-train-cluster --region=europe-west1
kubectl create secret generic kube-train-secrets --from-literal=API_KEY=<ta-clé>
kubectl apply -f configmap.yaml && kubectl apply -f deployment-gke.yaml && kubectl apply -f service.yaml && kubectl apply -f hpa.yaml
```

#### Configuration k8S sur GCP
```
cd /mnt/c/DEVDIR/GITHUB/kube-train/cours/
kubectl apply -f configmap.yaml
openssl rand -base64 32           # Generation clef RND => API_KEY. ' kub..' => No history
 kubectl create secret generic kube-train-secrets --from-literal=API_KEY=<clef générée>
kubectl apply -f deployment-gke.yaml
kubectl apply -f service.yaml
kubectl apply -f hpa.yaml
kubectl get pods -w               # Wait
kubectl get pods
```
#### Test avec curl
```
kubectl get service kube-train-service   # Affiche l'ip public
curl http://<EXTERNAL-IP>/
 curl -H "X-API-KEY: <API_KEY>" http://<EXTERNAL-IP>/secure    # ' ' => bash Histo
```
#### K8s context
```
# liste les cluster disponible
kubectl config get-contexts

# Switcher vers GKE
kubectl config use-context gke_kube-train-project_europe-west1_kube-train-cluster

# Switcher vers Minikube
kubectl config use-context minikube
```
#### Github actions
```
# Créer du service account
gcloud iam service-accounts create github-actions-sa \
   --display-name="GitHub Actions - kube-train"

# Droits pour pusher sur Artifact Registry
gcloud projects add-iam-policy-binding kube-train-project \
   --member="serviceAccount:github-actions-sa@kube-train-project.iam.gserviceaccount.com" \
   --role="roles/artifactregistry.writer"
 
 # Droits pour déployer sur GKE
 gcloud projects add-iam-policy-binding kube-train-project \
   --member="serviceAccount:github-actions-sa@kube-train-project.iam.gserviceaccount.com" \
   --role="roles/container.developer"

# Verif des droits
gcloud projects get-iam-policy kube-train-project \
   --flatten="bindings[].members" \
   --filter="bindings.members:github-actions-sa" \
   --format="table(bindings.role)"
```
#### Workload Identity Federation
```
# Créer le Workload Identity Pool
gcloud iam workload-identity-pools create github-pool \
   --location=global \
   --display-name="GitHub Actions Pool"
 
# Ajouter GitHub comme provider OIDC
gcloud iam workload-identity-pools providers create-oidc github-provider \
   --location=global \
   --workload-identity-pool=github-pool \
   --display-name="GitHub Provider" \
   --issuer-uri="https://token.actions.githubusercontent.com" \
   --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
   --attribute-condition="assertion.repository=='samiyc/kube-train'"

# Retrouver le PROJECT_NUMBER (différent du project ID)
gcloud projects describe kube-train-project --format='value(projectNumber)'
 
# Lier le Service Account au pool
gcloud iam service-accounts add-iam-policy-binding \
   github-actions-sa@kube-train-project.iam.gserviceaccount.com \
   --role="roles/iam.workloadIdentityUser" \
   --member="principalSet://iam.googleapis.com/projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/github-pool/attribute.repository/samiyc/kube-train"

# Restreindre le Workload Identity au branch main seulement (sécurité)
gcloud iam workload-identity-pools providers update-oidc github-provider \
   --location=global \
   --workload-identity-pool=github-pool \
   --attribute-condition="assertion.repository=='samiyc/kube-train' && assertion.ref=='refs/heads/main'"
```
#### GCP Secret Manager
Les avantages par rapport aux Secrets K8s manuels :
- Audit trail (Cloud Audit Logs — qui, quand, quoi)
- Versioning (chaque modification = nouvelle version)
- Rotation programmable
- Indépendant du cluster (persiste même si GKE est détruit)
- Contrôle d'accès IAM granulaire (qui peut lire quel secret)
```
# Activer l'API Secret Manager
gcloud services enable secretmanager.googleapis.com --project=kube-train-project

# Créer le secret avec la valeur <API_KEY>
echo -n "<API_KEY>" | \
  gcloud secrets create api-key \
    --data-file=- \
    --project=kube-train-project \
    --labels=app=kube-train

# Vérifier
gcloud secrets versions access latest --secret=api-key --project=kube-train-project

# Trouver le PROJECT_NUMBER
gcloud projects describe kube-train-project --format='value(projectNumber)'

# Donner accès au secret
# Le SA (Service Account) utilisé par les pods par default est :
# => {PROJECT_NUMBER}-compute@developer.gserviceaccount.com
gcloud secrets add-iam-policy-binding api-key \
  --project=kube-train-project \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"

# Donne accès à github-actions-sa
gcloud secrets add-iam-policy-binding api-key \
   --project=kube-train-project \
   --member="serviceAccount:github-actions-sa@kube-train-project.iam.gserviceaccount.com" \
   --role="roles/secretmanager.secretAccessor"
```
Chemin de l'`API_KEY` du `Secret Manager` vers le code `Java`
```
GCP Secret Manager (api-key)
=> gcloud secrets versions access latest

GitHub Actions (github-actions-sa)
=> kubectl create secret --dry-run=client | kubectl apply

K8s Secret (kube-train-secrets)
=> secretKeyRef → TRAIN_API_KEY env var

Spring Boot @Value("${train.api.key}")
```
#### HTTPS sur GKE — Setup manuel unique (nginx-ingress + cert-manager + ClusterIssuer)

- GKE Autopilot bloque l'accès à `kube-system` → cert-manager doit être installé via Helm (pas raw YAML)  
- L'email Let's Encrypt est passé en dur dans la commande ci-dessous (jamais commité).  
Il n'y a pas de secret GitHub à créer pour ce setup. Le ClusterIssuer est appliqué manuellement.

```bash
# 1. nginx-ingress controller — crée un LoadBalancer public sur GKE
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/cloud/deploy.yaml

# 2. Récupérer l'IP externe du nginx-ingress (peut prendre 1-2 min)
kubectl get service ingress-nginx-controller -n ingress-nginx --watch
# → noter l'IP : sera l'URL nip.io (ex: api.34.78.39.236.nip.io)

# 3. Installer Helm
sudo snap install helm --classic

# 4. Installer cert-manager via Helm (leader election dans cert-manager, pas kube-system)
helm repo add jetstack https://charts.jetstack.io --force-update
helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --version v1.16.2 \
  --set crds.enabled=true \
  --set global.leaderElection.namespace=cert-manager

# 5. Attendre que cert-manager soit prêt
kubectl wait --namespace cert-manager \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/instance=cert-manager \
  --timeout=120s

# 6. Vérifier le cainjector (doit afficher "Updated object", pas d'erreur kube-system)
kubectl logs -n cert-manager -l app.kubernetes.io/component=cainjector --tail=5

# 7. Appliquer le ClusterIssuer Let's Encrypt (email jamais commité)
sed "s|LETSENCRYPT_EMAIL_PLACEHOLDER|ton-email@gmail.com|g" \
    cours/cluster-issuer.yaml | kubectl apply -f -

# Vérifier (READY=True + ACMEAccountRegistered = OK)
kubectl get clusterissuer letsencrypt-prod

# --- LANCER LA PIPELINE (git push) ---
# Le pipeline applique l'Ingress avec host api.<IP>.nip.io automatiquement

# Vérifier le certificat TLS après la pipeline (challenge ACME ~30-60s)
kubectl get ingress
kubectl get certificate
kubectl describe certificate kube-train-tls
```
#### Cloud SQL / PostgreSQL
```
# Activer l'API Cloud SQL
gcloud services enable sqladmin.googleapis.com --project=kube-train-project

# Créer l'instance PostgreSQL (db-f1-micro = la plus petite, ~3€/mois)
gcloud sql instances create kube-train-db \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=europe-west1 \
  --project=kube-train-project

⚠️ La création prend 5-10 minutes

# Créer la base de données
gcloud sql databases create kube_train \
  --instance=kube-train-db \
  --project=kube-train-project

# Créer l'utilisateur applicatif (pas root)
gcloud sql users create kube_train_user \
  --instance=kube-train-db \
  --password=kube_train_pass \
  --project=kube-train-project

# Autoriser le SA GKE à se connecter via Cloud SQL Auth Proxy (IAM)
gcloud projects add-iam-policy-binding kube-train-project \
  --member="serviceAccount:399291708401-compute@developer.gserviceaccount.com" \
  --role="roles/cloudsql.client"

⚠️ Gestion des MDP => *
# Random mdp : openssl rand -base64 32
# ' ' before mdp bash line => skip history
# Backup mdp sur KeePass

# Créer les secrets DB dans GCP Secret Manager *
echo -n "db_user" | gcloud secrets create db-username --data-file=- --project=kube-train-project
 echo -n "db_pass" | gcloud secrets create db-password  --data-file=- --project=kube-train-project

# Vérifier que les secrets sont bien pris en compte
gcloud secrets list --project=kube-train-project

# Update des mots de passe *
 echo -n "new-api-key" | gcloud secrets versions add api-key --data-file=- --project=kube-train-project 
 echo -n "nouveau_mdp" | gcloud secrets versions add db-password --data-file=- --project=kube-train-project
 echo -n "nouveau_usr" | gcloud secrets versions add db-username --data-file=- --project=kube-train-project

# Lister les versions
gcloud secrets versions list api-key --project=kube-train-project

# Suppression d'une ancienne version
gcloud secrets versions destroy 1 --secret=api-key --project=kube-train-project
```
