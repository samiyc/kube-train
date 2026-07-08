# Runbook F4-J1 — Sécurité Kubernetes & RBAC

> Extrait du runbook F4. Prérequis communs & debug multi-containers : voir [`../runbook.md`](../runbook.md).

```bash
# ── Prérequis image Minikube ──────────────────────────────────────────────────
eval $(minikube docker-env)                          # cibler le daemon Docker Minikube
docker build -t kube-train-api:v5 ./kube-train-api/ # construire l'image locale

# Vérifier ConfigMap + Secret (créer si absent)
kubectl get configmap kube-train-config 2>/dev/null || \
  kubectl create configmap kube-train-config --from-literal=TRAIN_MESSAGE="Bienvenue"
kubectl get secret kube-train-secrets 2>/dev/null || \
  kubectl create secret generic kube-train-secrets --from-literal=API_KEY=dev-secret-key

# Récupérer la valeur de l'API key stockée dans le secret
kubectl get secret kube-train-secrets -o jsonpath='{.data.API_KEY}' | base64 -d

# ── Minikube — apply dans l'ordre ─────────────────────────────────────────────
kubectl apply -f k8s/postgres-storage.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/postgres-service.yaml
kubectl apply -f k8s/rbac.yaml              # RBAC avant Deployment (SA reference)
kubectl apply -f k8s/namespace-pss.yaml
kubectl apply -f k8s/quota.yaml
kubectl apply -f k8s/deployment.yaml
kubectl rollout status deployment/kube-train-deployment

# ── Vérifications F4-J1 ───────────────────────────────────────────────────────
kubectl get pods
kubectl logs --tail=20 deployment/kube-train-deployment -c wait-for-postgres
kubectl describe pod -l app=kube-train-pod | grep -A8 "Security Context"
kubectl get deployment kube-train-deployment -o jsonpath='{.spec.template.spec.serviceAccountName}'
# → kube-train-api-sa

# Test RBAC (can-i)
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# → yes
kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
# → no

# PSS warnings (audit/warn=restricted)
kubectl get events --sort-by=.lastTimestamp | grep -i warning

# ResourceQuota — état actuel
kubectl describe resourcequota kube-train-quota

# Test quota : scale à 3 doit être bloqué (pods: 6, mais node-exporter+postgres = 2 déjà)
kubectl scale deployment kube-train-deployment --replicas=4
kubectl describe resourcequota kube-train-quota
kubectl scale deployment kube-train-deployment --replicas=2  # remettre à 2

# ── Tests fonctionnels ────────────────────────────────────────────────────────
kubectl port-forward svc/kube-train-service 8080:80 &
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/trains
# Récupérer la valeur API_KEY : kubectl get secret kube-train-secrets -o jsonpath='{.data.API_KEY}' | base64 -d
curl -H "X-API-KEY: <valeur_API_KEY>" http://127.0.0.1:8080/secure
curl -X POST http://127.0.0.1:8080/reservations \
  -H "Content-Type: application/json" \
  -d '{"passengerId":"Jean Dupont","trainId":"TGV-7042"}'

# ── GKE (CI/CD) ───────────────────────────────────────────────────────────────
# Le pipeline deploy.yml applique automatiquement rbac-gke.yaml
# et annote kube-train-api-sa + notification-sa pour Workload Identity.
# Vérification post-déploiement :
kubectl get serviceaccount kube-train-api-sa -o yaml
kubectl get serviceaccount notification-sa -o yaml
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa

# ── Dépannage rolling update bloqué ──────────────────────────────────────────
# Si rollout bloqué (ProgressDeadlineExceeded) :
kubectl describe deployment kube-train-deployment | tail -20
kubectl get events --sort-by=.lastTimestamp | tail -10
# Causes fréquentes : quota pods dépassé, image corrompue, init container en boucle
# Fix quota : kubectl apply -f k8s/quota.yaml (après avoir ajusté pods: N)
# Fix image : docker build --no-cache -t kube-train-api:vN ./kube-train-api/
#             puis kubectl rollout restart deployment/kube-train-deployment
```
