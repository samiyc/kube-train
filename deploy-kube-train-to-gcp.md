
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
gcloud init    # Login + sélection du projet (inclut déjà gcloud auth login)
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
