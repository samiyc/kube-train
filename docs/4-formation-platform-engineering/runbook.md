# Runbook — Formation F4 Platform Engineering

> Commandes essentielles et procédures opérationnelles pour chaque journée.
> Complété au fur et à mesure de la formation.

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

## F4-J1: Sécurité Kubernetes & RBAC

```bash
# ── Minikube ──────────────────────────────────────────────────────────────────
# Prérequis : postgres-service déployé
kubectl apply -f k8s/postgres-storage.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml

# RBAC (doit exister avant le Deployment qui référence kube-train-api-sa)
kubectl apply -f k8s/rbac.yaml

# PSS namespace labels
kubectl apply -f k8s/namespace-pss.yaml

# LimitRange + ResourceQuota
kubectl apply -f k8s/quota.yaml

# Deployment durci (securityContext + SA + init container)
kubectl apply -f k8s/deployment.yaml
kubectl rollout status deployment/kube-train-deployment

# Vérifications
kubectl get pods
kubectl logs deployment/kube-train-deployment -c wait-for-postgres
kubectl port-forward svc/kube-train-service 8080:80
curl http://127.0.0.1:8080/actuator/health

# Test RBAC (can-i)
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# → yes

kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# → no

# Observer les warnings PSS restricted (audit/warn)
kubectl get events --sort-by=.lastTimestamp

# Tester la ResourceQuota (3e pod doit être bloqué si postgres est là)
kubectl scale deployment kube-train-deployment --replicas=3
kubectl describe resourcequota kube-train-quota

# ── GKE (CI/CD) ───────────────────────────────────────────────────────────────
# Le pipeline deploy.yml applique automatiquement rbac-gke.yaml
# et annote kube-train-api-sa + notification-sa pour Workload Identity.
# Vérification post-déploiement :
kubectl get serviceaccount kube-train-api-sa -o yaml
kubectl get serviceaccount notification-sa -o yaml
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
```

---

## F4-J2: Helm & Packaging

```bash
# À compléter lors de J2
```

---

## F4-J3: Terraform IaC

```bash
# À compléter lors de J3
```

---

## F4-J4: Istio Service Mesh

```bash
# À compléter lors de J4
```

---

## F4-J5: SRE & Observabilité Production

```bash
# À compléter lors de J5
```
