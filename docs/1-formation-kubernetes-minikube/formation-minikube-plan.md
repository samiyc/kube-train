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
