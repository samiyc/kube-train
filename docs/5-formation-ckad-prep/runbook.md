# Runbook — Formation F5 CKAD Prep

> **Local-first** : toutes les commandes visent **Minikube** (driver Docker, WSL). GCP réactivé ponctuellement seulement (voir encart budget). Chemin WSL du projet : `/mnt/c/DEVDIR/GITHUB/kube-train`.

---

## 🔧 Prérequis & setup commun (à faire une fois par session)

```bash
# Démarrer Minikube
minikube start --driver=docker
kubectl config use-context minikube

# Alias & raccourcis examen (à internaliser)
alias k=kubectl
export do="--dry-run=client -o yaml"
export now="--force --grace-period=0"
source <(kubectl completion bash)
complete -o default -F __start_kubectl k

# metrics-server (pour k top / drills observability J5)
minikube addons enable metrics-server

# Ingress (pour drills networking J4)
minikube addons enable ingress
```

**Réflexe open-book** : `k explain <resource>.spec.<champ>` remplace la recherche doc pour 80 % des cas.

---

## 💰 Encart budget — réactivation GCP ponctuelle

> Par défaut on reste sur Minikube (0 €). GCP seulement pour les labs haut-ROI avant fin d'essai.

```bash
# Réactiver l'infra GCP (depuis infra/, WSL)
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra && terraform apply
# Redémarrer Cloud SQL si stoppée
gcloud sql instances patch kube-train-db --activation-policy=ALWAYS

# ⚠️ TOUJOURS en fin de session GCP :
terraform destroy
gcloud sql instances patch kube-train-db --activation-policy=NEVER
```

---

## F5-J1 : Core workloads & vitesse kubectl

```bash
# (à compléter pendant le drill J1)
# Impératif → YAML
# k run / k create deployment / k create job --dry-run=client -o yaml
```

---

## F5-J2 : Config & Security

```bash
# (à compléter pendant le drill J2)
# ConfigMap/Secret, SA, RBAC, securityContext
```

---

## F5-J3 : Multi-container & Application Design

```bash
# (à compléter pendant le drill J3)
# init containers, sidecar/ambassador/adapter, Jobs, CronJobs
```

---

## F5-J4 : Services & Networking

```bash
# (à compléter pendant le drill J4)
# Services (tous types), Ingress, NetworkPolicies, port-forward
```

---

## F5-J5 : Observability & Maintenance

```bash
# (à compléter pendant le drill J5)
# probes, logs, k debug, troubleshooting
```

---

## F5-J6 : Deployment & Packaging (Helm + Kustomize)

```bash
# (à compléter pendant le drill J6)
# rollout undo/history, Helm, Kustomize (k apply -k)
```

---

## F5-J7 / J8 : Examens blancs chronométrés

```bash
# Setup / reset d'un examen blanc reproductible
# Voir templates/template-examen-blanc-chrono.md pour la procédure complète
# k create namespace ckad-mock1  (namespaces jetables)
# k delete namespace ckad-mock1  (reset entre deux tentatives)
```

---

*Squelette — chaque section se remplit au fil de la journée correspondante.*
