# 🚄 Projet Kube-Train

Formation pratique Kubernetes : De zéro à la mise en production.
L'objectif est de déployer une architecture micro-services résiliente capable d'encaisser une forte charge (simulation de vente de billets).

## 🛠️ La Stack Technique

Orchestrateur : Kubernetes (via Minikube sur WSL)

Backend : Java 21 LTS / Spring Boot 3.x

Base de données : PostgreSQL

Load Testing : Python (Locust)

Outils CLI : kubectl, docker, k9s

## 🗺️ Roadmap de la Formation

### 📺 Saison 1 : Les fondations du terminal (TERMINÉ ✅)

[x] Épisode 1 : Installation Minikube/Kubectl

[x] Épisode 2 : Le Pod (Capsule de survie)

[x] Épisode 3 : Le Debug (Logs, Describe, Exec)

### 📺 Saison 2 : L'application Java entre en gare (TERMINÉ ✅)

[x] Épisode 1 : Dockerisation (Spring Boot)

[x] Épisode 2 : Deployment & ReplicaSet (L'armée des clones)

[x] Épisode 3 : Rolling Update (Mise à jour sans coupure)

### 📺 Saison 3 : Ouvrir les vannes (TERMINÉ ✅)

[x] Épisode 1 : Service ClusterIP (Le standardiste)

[x] Épisode 2 : Service NodePort/LoadBalancer (Le guichet public)

[x] Épisode 3 : Ingress (La porte royale)

### 📺 Saison 4 : Configuration & Stockage (TERMINÉ ✅)

[x] Épisode 1 : ConfigMap (Variables d'env)

[x] Épisode 2 : Secrets (Mots de passe)

[x] Épisode 3 : Volumes (Persistance)

### 📺 Saison 5 : L'Heure de Pointe. Scaling ! (TERMINÉ ✅)

[X] Épisode 1 : Probes (Liveness/Readiness)

[X] Épisode 2 : HPA (Autoscaling)

[X] Épisode 3 : Stress Test (Python Locust)

(EN COURS ▶️)

## ✅ Compétences Validées
Architecture Pods/Nodes, Minikube, Kubectl CLI  

Dockerisation Java, ReplicaSets, Rolling Updates  

Services (ClusterIP, NodePort), Ingress Controller, DNS

ConfigMaps, Secrets (Base64), Variables d'Env

PersistentVolumes, Claims (PVC), Réparation BDD

Health Probes (Liveness/Readiness), HPA Autoscaling


## 🚀 Prochaines Étapes Suggérées

1\) Le Cloud Réel : Essaie de déployer ce projet sur un cluster managé gratuit/cheap (ex: OVH Managed K8s ou Google GKE autopilot) pour voir la différence avec Minikube (notamment les vrais LoadBalancers).

2\) Helm : Tu as vu que copier-coller des YAML c'est long. Helm est le "package manager" de K8s pour templater tout ça.

3\) CI/CD : Automatiser le docker build et kubectl apply via GitLab CI ou GitHub Actions.

&nbsp;  

# 📝 Cheat Sheet / Mémo Commandes

#### Minikube  
Avant chaque utilisation lancer : ```minikube start --driver=docker```
```
# 1) Installation de kubectl
curl -LO https://dl.k8s.io/release/v1.31.0/bin/linux/amd64/kubectl
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
kubectl version --client

# 2) Installation de Minikube 
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube
minikube start

# 3) Minikube ok ? clean up
rm minikube-linux-amd64
rm kubectl

# 4) Alias
echo "alias k='kubectl'" >> ~/.bashrc
source ~/.bashrc
```


#### Watch
```
k get nodes
k get pods
watch k get pods
k get deployments
k get services
k9s

## Installer k9s
curl -sS https://webinstall.dev/k9s | bash
source ~/.config/envman/PATH.env

## Information de config
k get configmap -o yaml
k get secret kube-train-secrets -o yaml
```

#### Logs & Debug
```
k logs nom-du-pod
k logs -f nom-du-pod                  # Suivre les logs en direct
k describe pod nom-du-pod             # Voir les événements système (Crash, ImagePullBackOff)
k exec -it nom-du-pod -- /bin/sh      # Entrer dans le pod
# Dans k9s : L(logs), S(shell), D(describe)
```

#### Création / Suppression
```
k apply -f deployment.yaml
k run mon-pod --image=nginx --restart=Never --dry-run=client -o yaml > generated.yaml
k delete pod nom-du-pod-a-tuer
```

#### Services & Réseau
```
k apply -f service.yaml
k get services
minikube service kube-train-service --url   # Obtenir l'URL d'accès (WSL)
minikube tunnel                             # Pour les services LoadBalancer
```

#### Test interne (Pod espion)
```
## Lancer un pod curl temporaire
k run espion --image=curlimages/curl -i --tty --rm --restart=Never -- sh
# Dans le shell :
curl http://kube-train-service
exit
```

#### Scaling
```
k scale deployment kube-train-deployment --replicas=10
```

#### Livraison & Mise à jour (Rolling Update)
```
eval $(minikube docker-env)   # ⚠️ CRUCIAL : Pointer vers le Docker de Minikube
docker build -t kube-train-api:v2 .
docker image ls | grep kube-train

## Mettre à jour l'image
k set image deployment/kube-train-deployment api-container=kube-train-api:v2

# Annuler la mise à jour (Rollback)
kubectl rollout undo deployment/kube-train-deployment

# Forcer le redémarrage (pour prendre en compte une ConfigMap/Secret)
k rollout restart deployment/kube-train-deployment
```

#### Commande Ingress (Accés externes)
```
# Installation
minikube addons enable ingress
kubectl get pods -n ingress-nginx

# Tunnel de secours si minikube tunnel KO
kubectl port-forward -n ingress-nginx service/ingress-nginx-controller 8081:80

# Test avec header Host
curl -H "Host: api.kube-train.local" http://127.0.0.1:8081

# Basic forward
POD_NAME=$(kubectl get pods -l app=kube-train-pod -o jsonpath="{.items[0].metadata.name}")
kubectl port-forward $POD_NAME 8080:8080
curl http://localhost:8080/
```

#### Secret
```
# Création
kubectl create secret generic kube-train-secrets \
  --from-literal=API_KEY=S3CR3T-K3Y-12345 \
  --from-literal=DB_PASSWORD=root

# Lister les secrets
k get secrets
k get secret kube-train-secrets -o yaml

# Base 64 decode
echo "UzNDUjNULUszWS0xMjM0NQ==" | base64 --decode

# Maj mdp & Restart
k apply -f deployment.yaml
k rollout restart deployment/kube-train-deployment
curl -H "Host: api.kube-train.local" http://127.0.0.1:8081/secure
```

#### Volume persistant & BDD Postgres
```
# Initialisation
k apply -f postgres-storage.yaml
k get pvc
k apply -f postgres-deployment.yaml
k get deployments
k apply -f postgres-service.yaml
k get services

# Connection a la base Postgres
POSTGRES_POD=$(kubectl get pods -l app=postgres -o jsonpath="{.items[0].metadata.name}")
sami@HOP008007:~/projets/kube-train/cours$ kubectl exec -it $POSTGRES_POD -- psql -U postgres

# --- Reset du volume si BDD corrompu (stop brutal) ---
# 1. Delete
k delete service postgres-service
k delete deployment postgres-deployment
k delete pvc postgres-pvc-claim
# <!> Supprimer le ticket de stockage (PVC) Sur Minikube, cela supprime aussi physiquement les données (le PV)

# 2. RESTART ! On recrée le tout (Disque neuf + BDD neuve)
k apply -f postgres-storage.yaml
k apply -f postgres-deployment.yaml
k apply -f postgres-service.yaml
```

#### Hpa. Métriques
```
# init
minikube addons enable metrics-server
kubectl get pods -n kube-system | grep metrics

# watch
kubectl top nodes
kubectl top pods

# -- Test de perf --
# On lance un pod qui fait une boucle infinie de requêtes (wget) vers notre service
kubectl run -i --tty load-generator --rm --image=busybox --restart=Never -- /bin/sh -c "while sleep 0.01; do wget -q -O- http://kube-train-service; done"

# Restart du minikub si "kubectl top" KO (warning bdd)
minikube stop && minikube start
```

#### Locust
```
# Activation du venv
source venv/bin/activate

# Check 
locust -V

# Lancement du script
kubectl port-forward service/kube-train-service 8080:80
locust -f locustfile.py
=> http://localhost:8089
=> 5000 Users 50 ramp up

# Installation
sudo apt install python3-venv -y
python3 -m venv venv
source venv/bin/activate
pip install locust

```
