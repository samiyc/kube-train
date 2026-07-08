# Runbook — Formation F4 Platform Engineering (index)

> Commandes essentielles et procédures opérationnelles.
> **Ce fichier est un index** : les prérequis communs et le debug multi-containers sont ci-dessous ; les commandes par journée sont dans les runbooks dédiés de chaque jour.

## 📚 Runbooks par journée

| Jour | Sujet | Runbook |
|------|-------|---------|
| J1 | Sécurité Kubernetes & RBAC | [`J1-securite-rbac/runbook-J1.md`](J1-securite-rbac/runbook-J1.md) |
| J2 | Helm & Packaging | [`J2-helm-packaging/runbook-J2.md`](J2-helm-packaging/runbook-J2.md) |
| J3 | Terraform IaC | [`J3-terraform-iac/runbook-J3.md`](J3-terraform-iac/runbook-J3.md) |
| J4 | Istio Service Mesh | [`J4-istio-mesh/runbook-J4.md`](J4-istio-mesh/runbook-J4.md) |
| J5 | SRE & Observabilité | [`J5-sre-observabilite/runbook-J5.md`](J5-sre-observabilite/runbook-J5.md) |

---

## 🔧 Prérequis & setup commun

```bash
# Outils nécessaires (en plus de F3)
# Terraform
terraform version  # >= 1.7

# Helm
helm version  # >= 3.14

# Istio CLI
istioctl version

# Vérifier accès GCP
gcloud auth list
gcloud config get-value project  # kube-train-project
```

---

## 🔍 Debug — Navigation pods multi-containers (GKE Autopilot)

Sur GKE Autopilot les pods ont systématiquement plusieurs containers (`istio-proxy`, `cloud-sql-proxy`...).

```bash
# Lister les containers de tous les pods
kubectl get pods -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'

# -- Exemple de retour (f4-j5) --
# NAME                           CONTAINERS
# kube-train-deployment-1234     api-container,cloud-sql-proxy,istio-proxy
# notification-deployment-1234   notification-container,istio-proxy
# otel-collector-1234            otel-collector,istio-proxy

# Lister les containers d'un pod spécifique
kubectl get pod <pod-name> -o='custom-columns=NAME:.metadata.name,CONTAINERS:.spec.containers[*].name'

# Logs d'un container spécifique
kubectl logs <pod-name> -c <container-name>

# Logs de tous les containers (avec préfixe container — indispensable pour distinguer les lignes)
kubectl logs <pod-name> --all-containers=true --prefix=true | tail -20

# Suivre les logs en temps réel (tous containers)
kubectl logs <pod-name> --all-containers=true --prefix=true -f
```
