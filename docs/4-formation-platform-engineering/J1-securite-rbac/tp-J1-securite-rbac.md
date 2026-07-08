# TP J1 — Sécuriser kube-train : RBAC & Pod Security

**Durée estimée : 2-3h**
**Prérequis** : cluster Minikube ou GKE fonctionnel, kube-train déployé

> Les exemples ci-dessous utilisent le namespace `kube-train` pour isoler les politiques de sécurité. Si votre déploiement actuel tourne dans `default`, remplacez simplement `kube-train` par `default` — mais attention : PSS, quotas et limites impacteront alors tous les pods de ce namespace.

---

## Étape 1 — Ajouter un `securityContext` complet à `kube-train-api`

### Objectif
Durcir le pod API pour viser une posture proche de **PSS Restricted** : exécution non-root, système de fichiers racine en lecture seule, aucune capability Linux conservée.

### Contexte
Aujourd'hui, `kube-train-api` tourne correctement, mais le conteneur principal n'a pas encore un hardening complet. Le piège classique avec Spring Boot est le **répertoire temporaire** : avec `readOnlyRootFilesystem: true`, il faut prévoir un emplacement writable.

### Travail à faire
Modifie le `Deployment` de l'API pour ajouter :
- au niveau pod : `runAsNonRoot: true`, `runAsUser: 1000`, `fsGroup: 1000`, `seccompProfile: RuntimeDefault` ;
- au niveau conteneur : `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]` ;
- un volume `emptyDir` monté sur `/tmp`.

### YAML cible (extrait)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kube-train-deployment
  namespace: kube-train
spec:
  template:
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
        seccompProfile:
          type: RuntimeDefault
      volumes:
        - name: tmp
          emptyDir: {}
      containers:
        - name: api-container
          image: kube-train-api:v5
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop:
                - ALL
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-Djava.io.tmpdir=/tmp"
```

### Commandes utiles

```bash
kubectl apply -f k8s/workloads/deployment.yaml -n kube-train
kubectl rollout status deployment/kube-train-deployment -n kube-train
kubectl get pods -n kube-train
kubectl port-forward svc/kube-train-service 8080:80 -n kube-train
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/trains
```

### Ce qu'il faut vérifier
- le pod redémarre correctement ;
- `READY` passe à `1/1` (ou `2/2` si vous avez un sidecar) ;
- `/actuator/health` répond `UP` ;
- `/trains` reste accessible.

### Pièges fréquents
- oublier le volume `/tmp` → Spring Boot/Tomcat échoue au démarrage ;
- fixer `runAsNonRoot: true` sans `runAsUser` alors que l'image n'est pas explicitement rootless ;
- oublier les **init containers** et sidecars si vous passez ensuite en PSS `restricted`.

---

## Étape 2 — Créer un ServiceAccount dédié + RBAC minimaliste

### Objectif
Remplacer l'usage implicite de `default` par un **ServiceAccount dédié** `kube-train-api-sa`, puis lui accorder un droit minimal : **`get` sur les secrets du namespace, sans `list` ni `watch`**.

### Contexte
Dans kube-train, le pipeline GKE annote aujourd'hui le ServiceAccount `default` pour Workload Identity. C'est pratique mais trop large. On veut une identité dédiée par workload.

### Travail à faire
1. Crée un `ServiceAccount` `kube-train-api-sa`.
2. Crée un `Role` nommé `kube-train-api-secret-reader` avec `verbs: ["get"]` sur la ressource `secrets` dans le namespace.
3. Lie le tout avec un `RoleBinding`.
4. Mets à jour le `Deployment` pour utiliser `serviceAccountName: kube-train-api-sa`.

### YAML à écrire

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kube-train-api-sa
  namespace: kube-train
  annotations:
    iam.gke.io/gcp-service-account: 399291708401-compute@developer.gserviceaccount.com
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: kube-train-api-secret-reader
  namespace: kube-train
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: kube-train-api-secret-reader-binding
  namespace: kube-train
subjects:
  - kind: ServiceAccount
    name: kube-train-api-sa
    namespace: kube-train
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: kube-train-api-secret-reader
```

Puis dans le Deployment :

```yaml
spec:
  template:
    spec:
      serviceAccountName: kube-train-api-sa
      automountServiceAccountToken: false
```

### Vérifications

```bash
kubectl apply -f kube-train-rbac.yaml
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:kube-train:kube-train-api-sa \
  -n kube-train
kubectl auth can-i list secrets \
  --as=system:serviceaccount:kube-train:kube-train-api-sa \
  -n kube-train
kubectl get deployment kube-train-deployment -n kube-train -o jsonpath='{.spec.template.spec.serviceAccountName}'
```

### Résultat attendu
- `get secret/kube-train-secrets` = `yes` ;
- `list secrets` = `no` ;
- le Deployment utilise bien `kube-train-api-sa`.

> Variante plus stricte : ajoute `resourceNames: ["kube-train-secrets"]` si tu veux restreindre l'accès à un secret précis.

### Pièges fréquents
- utiliser `ClusterRoleBinding` alors qu'un simple `RoleBinding` suffit ;
- donner `list` ou `watch` alors qu'on veut juste `get` ;
- confondre **annotation Workload Identity** et **permissions RBAC Kubernetes** : ce sont deux sujets différents.

---

## Étape 3 — Ajouter un init container d'attente PostgreSQL

### Objectif
Empêcher l'API de démarrer tant que PostgreSQL n'est pas joignable.

### Contexte
En local Minikube, kube-train peut dépendre d'un `postgres-service`. Si l'API démarre trop tôt, le pod peut boucler, ou les migrations/initialisations peuvent échouer.

### Travail à faire
Ajoute un init container `wait-for-postgres` qui boucle tant que PostgreSQL n'est pas prêt.

### Option 1 — Avec `pg_isready` (recommandé)

```yaml
spec:
  template:
    spec:
      initContainers:
        - name: wait-for-postgres
          image: postgres:15
          command:
            - sh
            - -c
            - |
              until pg_isready -h postgres-service -p 5432; do
                echo "PostgreSQL indisponible, attente..."
                sleep 2
              done
```

### Option 2 — Avec `busybox` + test TCP

```yaml
spec:
  template:
    spec:
      initContainers:
        - name: wait-for-postgres
          image: busybox:1.36
          command:
            - sh
            - -c
            - |
              until nc -z postgres-service 5432; do
                echo "PostgreSQL indisponible, attente..."
                sleep 2
              done
```

### Vérifications

```bash
kubectl apply -f k8s/database/postgres-deployment.yaml -n kube-train
kubectl apply -f k8s/database/postgres-service.yaml -n kube-train
kubectl apply -f k8s/workloads/deployment.yaml -n kube-train
kubectl describe pod -n kube-train
kubectl logs deployment/kube-train-deployment -c wait-for-postgres -n kube-train
```

### Résultat attendu
- le pod reste en phase `Init:` tant que PostgreSQL n'est pas prêt ;
- dès que `postgres-service:5432` répond, le conteneur principal démarre ;
- l'application passe `Ready` après ses probes.

### Pièges fréquents
- **sur GKE**, si vous utilisez le **Cloud SQL Auth Proxy en sidecar du même pod**, n'attendez pas `127.0.0.1:5432` dans un init container : le sidecar ne démarre qu'après la fin des init containers → deadlock ;
- oublier que les init containers sont exécutés **séquentiellement** ;
- utiliser un check trop agressif sans `sleep`, ce qui spamme les logs.

---

## Étape 4 — Appliquer Pod Security Standards sur le namespace

### Objectif
Activer des garde-fous namespace-level avec :
- `enforce: baseline`
- `audit: restricted`

### Contexte
L'idée n'est pas de passer immédiatement tout le namespace en `restricted/enforce`, mais de faire une migration réaliste : on bloque les cas les plus dangereux, tout en observant l'écart restant vers le niveau `restricted`.

### Travail à faire
Labellise le namespace puis réapplique tes manifests.

### Commandes

```bash
kubectl label namespace kube-train \
  pod-security.kubernetes.io/enforce=baseline \
  pod-security.kubernetes.io/enforce-version=latest \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/audit-version=latest \
  --overwrite

kubectl apply -f k8s/workloads/deployment.yaml -n kube-train
kubectl get ns kube-train --show-labels
```

### Ce qu'il faut observer
- le déploiement continue à passer avec `baseline` **si** vous n'avez pas de privilèges interdits ;
- `kubectl apply` peut afficher des warnings liés à `restricted` (par exemple si un conteneur n'a pas encore `allowPrivilegeEscalation: false`, `seccompProfile`, `drop: [ALL]`, etc.) ;
- ces warnings servent de backlog de hardening.

### Que corriger ensuite
- compléter le `securityContext` du conteneur API ;
- faire la même chose sur les **init containers** ;
- vérifier les sidecars éventuels (`cloud-sql-proxy`) ;
- si un workload tiers du namespace n'est pas compatible `restricted`, documenter explicitement pourquoi.

### Vérifications

```bash
kubectl get events -n kube-train --sort-by=.lastTimestamp
kubectl describe deployment kube-train-deployment -n kube-train
```

### Pièges fréquents
- croire que `audit` bloque : non, il signale seulement ;
- appliquer ces labels sur `default` sans regarder les autres pods du namespace ;
- penser que `restricted` modifie automatiquement les manifests : il ne fait que refuser / avertir.

---

## Étape 5 — Ajouter `LimitRange` + `ResourceQuota` et tester les limites

### Objectif
Encadrer le namespace `kube-train` pour éviter :
- les pods sans requests/limits ;
- un scale incontrôlé au-delà de 2 pods ;
- une mémoire totale > 1Gi.

### Travail à faire
Crée un fichier `kube-train-quota.yaml` contenant un `LimitRange` et une `ResourceQuota`.

### YAML à écrire

```yaml
apiVersion: v1
kind: LimitRange
metadata:
  name: kube-train-default-limits
  namespace: kube-train
spec:
  limits:
    - type: Container
      defaultRequest:
        cpu: 100m
        memory: 256Mi
      default:
        cpu: 500m
        memory: 512Mi
      min:
        cpu: 50m
        memory: 128Mi
      max:
        cpu: "1"
        memory: 768Mi
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: kube-train-quota
  namespace: kube-train
spec:
  hard:
    pods: "2"
    limits.memory: 1Gi
```

### Test de validation

```bash
kubectl apply -f kube-train-quota.yaml
kubectl describe limitrange kube-train-default-limits -n kube-train
kubectl describe resourcequota kube-train-quota -n kube-train
kubectl scale deployment kube-train-deployment --replicas=2 -n kube-train
kubectl get pods -n kube-train
kubectl describe resourcequota kube-train-quota -n kube-train
```

### Résultat attendu
- si le namespace contient déjà `postgres` + `kube-train-api`, passer l'API à 2 replicas tente de créer un **3e pod** et doit échouer ;
- `ResourceQuota` indique la consommation courante (`Used`) et le plafond (`Hard`) ;
- les nouveaux pods sans ressources explicites héritent des defaults du `LimitRange`.

### Pièges fréquents
- tester les quotas dans `default` alors que d'autres pods consomment déjà le quota ;
- oublier que `ResourceQuota` peut bloquer aussi sur la mémoire totale, pas seulement sur le nombre de pods ;
- croire que `LimitRange` empêche un scale trop haut : c'est le rôle de `ResourceQuota`, pas du `LimitRange`.

---

## Livrables attendus en fin de TP

- un `Deployment` API durci avec `securityContext` complet ;
- un `ServiceAccount` dédié `kube-train-api-sa` ;
- un `Role`/`RoleBinding` minimalistes sur le secret applicatif ;
- un init container d'attente PostgreSQL fonctionnel ;
- des labels PSS appliqués sur le namespace ;
- un `LimitRange` et une `ResourceQuota` testés en conditions réelles.

## Questions de débrief

1. Pourquoi `baseline` est souvent une meilleure première étape qu'un `restricted/enforce` immédiat ?
2. Pourquoi un init container ne peut-il pas attendre un sidecar du même pod ?
3. Quelle est la différence entre identité GCP (Workload Identity) et permissions RBAC Kubernetes ?
4. Qu'est-ce qui relève du **hardening du pod** vs de la **gouvernance du namespace** ?
