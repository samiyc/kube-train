
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
