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

### 📺 Saison 4 : Configuration & Stockage (EN COURS ▶️)

[x] Épisode 1 : ConfigMap (Variables d'env)

[ ] Épisode 2 : Secrets (Mots de passe)

[ ] Épisode 3 : Volumes (Persistance)

### 📺 Saison 5 : L'Heure de Pointe (Scaling)

[ ] Épisode 1 : Probes (Liveness/Readiness)

[ ] Épisode 2 : HPA (Autoscaling)

[ ] Épisode 3 : Stress Test (Python Locust)

&nbsp;  

# 📝 Cheat Sheet / Mémo Commandes

#### Minikube  
Avant chaque utilisation lancer : ```minikube start```
```
## Installation de Minikube 
# 1) Télécharger Minikube
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64

# 2) L'installer
sudo install minikube-linux-amd64 /usr/local/bin/minikube

# 3) Start
minikube start

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
