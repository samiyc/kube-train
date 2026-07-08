# TP J4 — Istio Service Mesh & Progressive Delivery

**Durée estimée : 2h30-3h**
**Prérequis** : cluster GKE Autopilot opérationnel, `kubectl` et `istioctl` installés, `kube-train-api` et `train-notification-service` déployés

> Les exemples ci-dessous utilisent le namespace `kube-train`. Si vos workloads tournent encore dans `default`, remplacez simplement le namespace — mais évitez de mesh-er tout `default` en production.

> **⚠️ Adaptation kube-train (Cloud Service Mesh managé)**
>
> Ce TP a été écrit pour un Istio auto-hébergé (`istioctl install`). Sur kube-train, le mesh est assuré par **Cloud Service Mesh (Fleet managé)** — le control plane tourne chez Google (Traffic Director), pas dans le cluster. Les adaptations appliquées :
>
> | TP (théorique) | Réalité kube-train |
> |---|---|
> | `namespace: kube-train` | `namespace: default` (workloads déjà en place) |
> | `istioctl install --set profile=demo -y` | CSM déjà activé via Fleet — rien à installer |
> | `kubectl label ns ... istio-injection=enabled` | `kubectl label ns default meshconfig.io/proxy-version=asm-managed-rapid` |
> | `istioctl proxy-status` | Non disponible — vérifier via `kubectl get pods` (2/2 ou 3/3) |
> | Propagation config ~1s | **30 à 90 secondes** (Traffic Director distribue depuis Google Cloud) |
> | Access logs `kubectl logs -c istio-proxy` | Logs HTTP dans **Cloud Logging** — utiliser les logs app ou `pilot-agent request GET clusters` |
>
> Pour toutes les commandes réelles, voir le **runbook** section F4-J4 et les **notes-J4** section 10 (incidents TP).

---

## Étape 1 — Installer Istio et activer l'injection sidecar

### Objectif
Installer Istio, activer l'injection automatique de sidecar sur le namespace `kube-train` et vérifier qu'Envoy apparaît bien dans les pods applicatifs.

### Contexte
Istio ajoute un proxy Envoy dans chaque pod meshé. L'injection a lieu **à l'admission du pod** via un mutating webhook : un pod déjà créé ne reçoit pas magiquement un sidecar, il faut le redémarrer.

### Commandes

```bash
# Installation simple pour le TP
istioctl install --set profile=demo -y

kubectl create namespace kube-train
kubectl label namespace kube-train istio-injection=enabled --overwrite

# Réappliquer ou redémarrer les workloads
kubectl apply -f k8s/workloads/service.yaml -n kube-train
kubectl apply -f k8s/workloads/deployment-gke.yaml -n kube-train
kubectl apply -f k8s/workloads/notification-deployment-gke.yaml -n kube-train
kubectl rollout restart deployment/kube-train-deployment -n kube-train
kubectl rollout restart deployment/notification-deployment -n kube-train
```

### Service interne pour le consumer
Le service de notification n'est pas exposé par défaut : ajoutez un `ClusterIP` interne pour pouvoir démontrer mTLS et AuthorizationPolicy.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: notification-service
  namespace: kube-train
spec:
  selector:
    app: notification-pod
  ports:
    - name: http
      port: 8081
      targetPort: 8081
```

### Vérifications

```bash
kubectl get pods -n kube-train
kubectl get pod -n kube-train -l app=kube-train-pod -o jsonpath='{.items[0].spec.containers[*].name}'
kubectl get pod -n kube-train -l app=notification-pod -o jsonpath='{.items[0].spec.containers[*].name}'
istioctl proxy-status
```

### Ce que vous devez vérifier
- chaque pod applicatif contient maintenant `istio-proxy` ;
- `READY` passe typiquement de `1/1` à `2/2` pour l'API et de `1/1` à `2/2` pour le consumer ;
- `istioctl proxy-status` retourne des sidecars **SYNCED** ;
- le service `notification-service` existe et résout bien en DNS interne.

### Pièges fréquents
- labelliser le namespace après le déploiement sans redémarrer les pods ;
- oublier de créer un Service pour `notification-service`, ce qui complique les tests mesh ;
- injecter Istio dans `default` alors qu'il contient d'autres composants non prévus pour le lab.

---

## Étape 2 — Forcer le mTLS avec `PeerAuthentication: STRICT`

### Objectif
Activer le mTLS strict sur le namespace `kube-train` et démontrer qu'un client meshé peut parler au consumer, tandis qu'un client non meshé est rejeté.

### YAML à appliquer

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default-strict
  namespace: kube-train
spec:
  mtls:
    mode: STRICT
```

### Pods de test
Un pod meshé avec l'identité de l'API :

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: mesh-client
  namespace: kube-train
spec:
  serviceAccountName: kube-train-api-sa
  containers:
    - name: curl
      image: curlimages/curl:8.8.0
      command: ["sh", "-c", "sleep 3600"]
```

Un pod **hors mesh** :

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: plain-client
  namespace: kube-train
  annotations:
    sidecar.istio.io/inject: "false"
spec:
  containers:
    - name: curl
      image: curlimages/curl:8.8.0
      command: ["sh", "-c", "sleep 3600"]
```

### Commandes

```bash
kubectl apply -f peer-authentication-strict.yaml
kubectl apply -f mesh-client.yaml
kubectl apply -f plain-client.yaml

kubectl exec -n kube-train mesh-client -c curl -- \
  curl -s http://notification-service:8081/actuator/health

kubectl exec -n kube-train plain-client -c curl -- \
  curl -sv http://notification-service:8081/actuator/health

istioctl proxy-status
```

### Ce que vous devez vérifier
- `mesh-client` arrive à appeler `notification-service` ;
- `plain-client` échoue ou reçoit un refus lié à l'absence de mTLS ;
- les sidecars restent en état **SYNCED** dans `istioctl proxy-status` ;
- le trafic inter-services n'est plus en clair dès lors que les workloads sont meshés.

### Pièges fréquents
- tester avec un pod sans sidecar et conclure à tort que le service est down ;
- oublier que `PeerAuthentication STRICT` s'applique à la destination, pas au client ;
- utiliser l'API pod comme client de test alors que l'image ne contient pas forcément `curl`.

---

## Étape 3 — Déployer une v2 de l'API et faire un canary 90/10

### Objectif
Déployer une seconde version de `kube-train-api`, définir les subsets Istio et router 10% du trafic vers la v2.

### Contexte
Pour visualiser facilement le canary sans toucher à beaucoup de code, vous pouvez utiliser une image v2 réelle **ou**, pour le lab, garder le même binaire avec une variable `TRAIN_MESSAGE` distinctive côté v2.

### Déploiement v1/v2
Ajoutez un label de version sur v1 puis créez une v2.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kube-train-deployment
  namespace: kube-train
spec:
  selector:
    matchLabels:
      app: kube-train-pod
      version: v1
  template:
    metadata:
      labels:
        app: kube-train-pod
        version: v1
```

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kube-train-deployment-v2
  namespace: kube-train
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kube-train-pod
      version: v2
  template:
    metadata:
      labels:
        app: kube-train-pod
        version: v2
    spec:
      serviceAccountName: kube-train-api-sa
      containers:
        - name: api-container
          image: europe-west1-docker.pkg.dev/kube-train-project/kube-train-repo/kube-train-api:canary-v2
          env:
            - name: TRAIN_MESSAGE
              value: "CANARY-V2"
```

### `DestinationRule` + `VirtualService`

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: kube-train-api
  namespace: kube-train
spec:
  host: kube-train-service
  subsets:
    - name: v1
      labels:
        version: v1
    - name: v2
      labels:
        version: v2
---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: kube-train-api
  namespace: kube-train
spec:
  hosts:
    - kube-train-service
  http:
    - route:
        - destination:
            host: kube-train-service
            subset: v1
          weight: 90
        - destination:
            host: kube-train-service
            subset: v2
          weight: 10
```

### Commandes

```bash
kubectl apply -f kube-train-v2.yaml
kubectl apply -f destination-rule.yaml
kubectl apply -f virtual-service.yaml
kubectl rollout status deployment/kube-train-deployment-v2 -n kube-train

kubectl port-forward svc/kube-train-service 8080:80 -n kube-train

for i in $(seq 1 30); do
  curl -s http://127.0.0.1:8080/
done
```

### Ce que vous devez vérifier
- les deux versions v1 et v2 sont prêtes et sélectionnées par le même Service ;
- le `VirtualService` envoie bien une petite partie du trafic vers la v2 ;
- au bout de plusieurs `curl`, vous voyez apparaître la signature `CANARY-V2` ;
- le traffic split reste contrôlé au niveau mesh, sans changer le Service Kubernetes.

### Pièges fréquents
- oublier les labels `version`, ce qui rend les subsets inopérants ;
- créer une v2 sans changer aucun signal visible, rendant le canary impossible à observer ;
- confondre répartition de trafic Istio et nombre de replicas Kubernetes.

---

## Étape 4 — Ajouter `AuthorizationPolicy` et une faute injectée sur `/reservations`

### Objectif
Restreindre `notification-service` aux seuls appels provenant de l'identité de l'API, puis injecter une latence de 500 ms sur `/reservations` pour tester la résilience.

### `AuthorizationPolicy`

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: notification-allow-only-api
  namespace: kube-train
spec:
  selector:
    matchLabels:
      app: notification-pod
  action: ALLOW
  rules:
    - from:
        - source:
            principals:
              - cluster.local/ns/kube-train/sa/kube-train-api-sa
      to:
        - operation:
            ports: ["8081"]
```

> Dès qu'une policy `ALLOW` existe sur un workload, les requêtes qui ne matchent pas sont refusées par défaut.

### Mise à jour du `VirtualService` avec fault injection

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: kube-train-api
  namespace: kube-train
spec:
  hosts:
    - kube-train-service
  http:
    - match:
        - uri:
            prefix: /reservations
      fault:
        delay:
          fixedDelay: 0.5s
          percentage:
            value: 100
      route:
        - destination:
            host: kube-train-service
            subset: v1
          weight: 90
        - destination:
            host: kube-train-service
            subset: v2
          weight: 10
    - route:
        - destination:
            host: kube-train-service
            subset: v1
          weight: 90
        - destination:
            host: kube-train-service
            subset: v2
          weight: 10
```

### Commandes

```bash
kubectl apply -f authorization-policy.yaml
kubectl apply -f virtual-service.yaml

# Le client API (mesh-client avec le bon SA) doit passer
kubectl exec -n kube-train mesh-client -c curl -- \
  curl -s http://notification-service:8081/actuator/health

# Un autre pod meshé avec un autre SA doit être refusé
kubectl run other-client -n kube-train --image=curlimages/curl:8.8.0 \
  --serviceaccount=default --restart=Never --command -- sh -c "sleep 3600"

kubectl exec -n kube-train other-client -c other-client -- \
  curl -sv http://notification-service:8081/actuator/health

# Vérifier la latence injectée
curl -s -o /dev/null -w '%{time_total}\n' \
  http://127.0.0.1:8080/reservations/UNKNOWN-ID
```

### Ce que vous devez vérifier
- `mesh-client` avec `kube-train-api-sa` peut appeler `notification-service` ;
- `other-client` est refusé ;
- les requêtes sur `/reservations` prennent environ 500 ms de plus ;
- le reste des routes continue à fonctionner sans latence artificielle.

### Pièges fréquents
- oublier de donner une identité distincte au client autorisé ;
- empiler plusieurs `VirtualService` sur le même host au lieu de fusionner les règles ;
- laisser la faute injectée active après le test et conclure à tort que l'application est lente.

---

## Résultat attendu en fin de TP

À la fin de J4, vous devez avoir :
- un namespace `kube-train` meshé avec sidecars Envoy ;
- du mTLS strict entre workloads ;
- un canary 90/10 contrôlé par Istio ;
- une `AuthorizationPolicy` qui protège `notification-service` ;
- une fault injection réversible pour tester les timeouts et l'observabilité.

> Bonus senior : ajoutez ensuite `Gateway` + `HTTPRoute`/`Gateway API`, activez les métriques Istio dans Cloud Monitoring et automatisez la promotion v2 → v1 à partir d'un SLO canary.
