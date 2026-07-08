# Runbook F4-J2 — Helm & Packaging

> Extrait du runbook F4. Prérequis communs & debug multi-containers : voir [`../runbook.md`](../runbook.md).

> ⚠️ Toutes les commandes Helm/kubectl/minikube s'exécutent dans **WSL**.
> Chemin WSL du projet : `/mnt/c/DEVDIR/GITHUB/kube-train`

```bash
# ── Vérification pré-TP ───────────────────────────────────────────────────────
helm version                          # doit être >= 3.14
helm env                              # HELM_DATA_HOME, HELM_CACHE_HOME

# ── Étape 1 : Scaffolding ─────────────────────────────────────────────────────
cd /mnt/c/DEVDIR/GITHUB/kube-train
helm create kube-train-chart

# Nettoyage du scaffold (fichiers non utilisés dans ce TP)
rm kube-train-chart/templates/hpa.yaml
rm kube-train-chart/templates/ingress.yaml
rm kube-train-chart/templates/serviceaccount.yaml
rm -rf kube-train-chart/templates/tests/

# Vérification syntaxe du chart
helm lint kube-train-chart

# Rendu brut (sans connexion cluster)
helm template kube-train ./kube-train-chart
helm template kube-train ./kube-train-chart -s templates/deployment.yaml  # un seul template

# Dry-run complet (simule un install avec les warnings Kubernetes)
helm install kube-train ./kube-train-chart --dry-run --debug

# ── Étape 2 : Service + ConfigMap ─────────────────────────────────────────────
# Après ajout des templates service.yaml et configmap.yaml :
helm template kube-train ./kube-train-chart > /tmp/rendered.yaml
cat /tmp/rendered.yaml                # vérifier ConfigMap + Service + Deployment

# ── Étape 3 : Déploiement Minikube ────────────────────────────────────────────
eval $(minikube docker-env)
docker build -t kube-train-api:v5 ./kube-train-api/

# ConfigMap + Secret doivent exister avant helm install
kubectl get configmap kube-train-config 2>/dev/null || \
  echo "⚠️  ConfigMap manquant — sera créé par le chart"
kubectl get secret kube-train-secrets 2>/dev/null || \
  kubectl create secret generic kube-train-secrets \
    --from-literal=API_KEY=S3CR3T-K3Y-12345 \
    --from-literal=DB_USERNAME=postgres \
    --from-literal=DB_PASSWORD=postgres

# Appliquer les prérequis (RBAC, quota, PSS, postgres) qui ne sont PAS dans le chart
kubectl apply -f k8s/database/postgres-storage.yaml
kubectl apply -f k8s/database/postgres-deployment.yaml
kubectl apply -f k8s/database/postgres-service.yaml
kubectl apply -f k8s/security/rbac.yaml
kubectl apply -f k8s/security/namespace-pss.yaml
kubectl apply -f k8s/security/quota.yaml

# Installer le chart Minikube
helm upgrade --install kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml
kubectl rollout status deployment/kube-train

# ── Vérifications post-install ────────────────────────────────────────────────
helm list                             # toutes les releases du namespace
helm status kube-train                # état + NOTES.txt
helm get values kube-train            # valeurs actives (sans les defaults)
helm get values kube-train --all      # toutes les valeurs (avec defaults)
helm get manifest kube-train          # manifests rendus actuellement en prod
helm history kube-train               # historique des révisions

# Vérifier que le Deployment vient bien du chart (label Helm)
kubectl get deploy -l app.kubernetes.io/managed-by=Helm

# Test fonctionnel
kubectl port-forward svc/kube-train 8080:80 &
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/trains

# ── Étape 3 : Override avec values-gke.yaml ────────────────────────────────────
# Vérifier le rendu GKE sans déployer
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-gke.yaml

# Différences entre les deux overlays
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-minikube.yaml > /tmp/minikube.yaml
helm template kube-train ./kube-train-chart -f ./kube-train-chart/values-gke.yaml > /tmp/gke.yaml
diff /tmp/minikube.yaml /tmp/gke.yaml

# ── Étape 4 : CronJob ─────────────────────────────────────────────────────────
# Après ajout du template cronjob.yaml :
helm template kube-train ./kube-train-chart -s templates/cronjob.yaml \
  -f ./kube-train-chart/values-minikube.yaml

helm upgrade --install kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml

kubectl get cronjobs
kubectl get jobs --watch                    # attendre le premier job
kubectl logs job/<nom-du-job>               # logs du job

# Désactiver le CronJob via --set
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml \
  --set cronjob.enabled=false

# ── Étape 5 : Upgrade atomique + rollback ─────────────────────────────────────
# Upgrade normal
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml \
  --set image.tag=v6

# Simulation d'échec (image inexistante) → rollback automatique
helm upgrade kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-minikube.yaml \
  --set image.tag=does-not-exist \
  --atomic --timeout 2m
# → devrait échouer et rollback automatiquement

helm history kube-train                     # voir la révision failed + rollback
helm rollback kube-train 1                  # rollback manuel si nécessaire

# ── Debug / troubleshooting ───────────────────────────────────────────────────
# Erreur de parsing template Go
helm lint kube-train-chart
helm template kube-train ./kube-train-chart 2>&1 | head -30

# Voir le state Helm stocké en K8s (Secrets)
kubectl get secrets -l owner=helm
kubectl get secret sh.helm.release.v1.kube-train.v1 -o yaml

# Nettoyage complet
helm uninstall kube-train
kubectl delete -f k8s/database/postgres-deployment.yaml
kubectl delete -f k8s/database/postgres-service.yaml
kubectl delete -f k8s/database/postgres-storage.yaml
```
