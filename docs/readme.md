# kube-train — Roadmap formations & cheat-sheet CLI

> Point d'entrée des docs. Roadmap des formations F1→F5, carte du dépôt, et
> cheat-sheet des commandes usuelles. **À consulter avant de suggérer des commandes.**

---

## 🗺️ Roadmap des formations

| # | Formation | Focus | Statut | Plan / Bilan |
|---|-----------|-------|--------|--------------|
| F1 | Kubernetes / Minikube | Déploiement K8s local de base | ✅ | `1-formation-kubernetes-minikube/formation-minikube-plan.md` |
| F2 | Cloud Native GKE | GKE, CI/CD, HTTPS, Pub/Sub, 12 facteurs | ✅ | `2-formation-cloud-native/` (+ `bilan.md`) |
| F3 | Beyond | OTel, ArgoCD, OAuth2, NetworkPolicies, Trivy | ✅ | `3-formation-cloud-native-beyond/formation-cn-beyond-plan.md` |
| F4 | Platform Engineering | RBAC/PSS, Helm, Terraform, Istio, SRE | ✅ **clôturée** | `4-formation-platform-engineering/` (+ `bilan.md`) |
| F5 | Préparation CKAD | Drills CKAD, local-first Minikube, examens blancs | 🚧 en cours | `5-formation-ckad-prep/formation-ckad-prep-plan.md` |

**Format pédagogique** (F3→) : théorie → TP → QCM/examen ouvert → correction → notes → runbook.
Chaque jour `Jx-*/` contient `notes-Jx.md`, `tp-Jx-*.md`, `qcm-Jx.md`, `examen-ouvert-Jx.md`, `corrections/`.

**Docs transverses utiles** :
- `4-formation-platform-engineering/extra/` — runbook rebuild E2E, OTel local/sans-GCP, **trace-e2e-outbox-propagation**, Helm vs Terraform, roadmap certifs
- `3-formation-cloud-native-beyond/extra/` — outils de révision (NotebookLM, Anki, Killer.sh), roadmap CKAD/GCP

---

## 🧭 Carte du dépôt

```
kube-train-api/            API Spring Boot 4 (REST, JPA, Pub/Sub, outbox, OTel)
train-notification-service/ Consumer Pub/Sub (email simulé, idempotent)
k8s/                       Manifests par concern :
  workloads/  security/  network/  istio/  observability/  database/  argocd/
kube-train-chart/          Helm chart (values-minikube.yaml / values-gke.yaml)
infra/                     Terraform — infra applicative (destroy/apply quotidien)
  bootstrap/               Terraform — identité & secrets (JAMAIS destroy)
.github/workflows/deploy.yml  CI/CD : test → build → deploy GKE
docs/                      Cette doc
```

---

## ⚡ Cheat-sheet CLI

> Exécuter depuis **WSL** (`minikube`, `docker`, `kubectl`, `gcloud`, `helm`, `terraform`).
> Chemin WSL : `/mnt/c/DEVDIR/GITHUB/kube-train`.

### Minikube (local, 0 €)
```bash
minikube start --driver=docker
eval $(minikube docker-env)                     # cibler le daemon Docker de Minikube
docker build -t kube-train-api:vN ./kube-train-api/
# Ordre Postgres : storage → deployment → service, puis security, puis workloads
kubectl apply -f k8s/database/  -f k8s/security/  -f k8s/workloads/
```

### GKE (après un rebuild Terraform)
```bash
# ⚠️ Après un destroy/apply : le kubeconfig est périmé → toujours re-générer
gcloud container clusters get-credentials kube-train-cluster \
  --region=europe-west1 --project=kube-train-project
kubectl config use-context gke_kube-train-project_europe-west1_kube-train-cluster
```

### Debug pods multi-containers (GKE Autopilot)
```bash
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'
kubectl logs deployment/notification-deployment -c notification-container --tail=50
# Containers : api-container / cloud-sql-proxy / istio-proxy · notification-container · otel-collector
```

### Helm
```bash
helm lint kube-train-chart
helm template kube-train ./kube-train-chart -f kube-train-chart/values-minikube.yaml
helm upgrade --install kube-train ./kube-train-chart -f kube-train-chart/values-gke.yaml --set image.tag=$SHA --atomic
helm history kube-train
```

### Terraform
```bash
# Infra applicative (cycle quotidien)
cd infra && terraform apply          # ~10 min (GKE Autopilot + Cloud SQL)
cd infra && terraform destroy        # fin de session budget

# Identité & secrets (une fois par projet — NE JAMAIS destroy)
cd infra/bootstrap && terraform apply
```

### Budget GCP (≤ 5 €/jour)
```bash
# Stopper Cloud SQL sans détruire l'infra
gcloud sql instances patch kube-train-db --activation-policy=NEVER --project=kube-train-project
gcloud sql instances patch kube-train-db --activation-policy=ALWAYS  # redémarrer
# Teardown complet en fin de session
cd infra && terraform destroy
```

### CI/CD
```bash
gh run list --workflow deploy.yml --limit 3
gh run watch <id> --exit-status
# Pas de workflow_dispatch → relancer via re-run du dernier run, ou un nouveau push
```

---

## 🔑 Repères clés

- **Label pod API** : `app: kube-train-pod` · **notification** : `app: notification-pod`
- **Profils Spring** : `gcp` → Pub/Sub ; `!gcp` → Kafka. GKE = `postgres,gcp`.
- **Secrets** : GCP Secret Manager (`api-key`, `db-username`, `db-password`) → K8s Secret par la CI.
- **Piège rebuild** : kubeconfig périmé (timeout sur l'ancienne IP) + `https://` sur l'IP du LB (port 80 only). Voir `extra/terraform-e2e-rebuild-runbook.md`.
- **Certifs visées** : CKAD puis GCP Professional Cloud DevOps Engineer. Voir `3-.../extra/roadmap-certifications-ckad-gcp.md`.
