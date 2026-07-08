# Notes J4 — Istio Service Mesh & Progressive Delivery

> Formation F4 — Platform Engineering  
> Objectif : ajouter un service mesh pour le mTLS automatique, le traffic management et le canary deployment.

---

## Glossaire — Acronymes J4

| Acronyme | Signification | Contexte |
|---|---|---|
| mTLS | mutual Transport Layer Security | TLS bidirectionnel : client et serveur s'authentifient mutuellement |
| TLS | Transport Layer Security | Chiffrement de la couche transport (successeur de SSL) |
| CRD | Custom Resource Definition | Extension de l'API K8s — Istio ajoute ses propres types d'objets |
| VS | VirtualService | CRD Istio : règles de routage du trafic (canary, retry, timeout) |
| DR | DestinationRule | CRD Istio : politiques de connexion vers un service (mTLS, pool, circuit breaker) |
| PA | PeerAuthentication | CRD Istio : politique mTLS entre services (STRICT / PERMISSIVE / DISABLE) |
| AP | AuthorizationPolicy | CRD Istio : qui peut appeler qui (niveau L7, par source/destination) |
| GW | Gateway | CRD Istio : point d'entrée du trafic externe vers le mesh |
| CSM | Cloud Service Mesh | Service mesh managé GKE (anciennement Anthos Service Mesh) |
| ASM | Anthos Service Mesh | Ancien nom de Cloud Service Mesh — encore utilisé dans les labels (asm-managed-rapid) |
| xDS | Discovery Service APIs | Famille de protocoles Envoy pour la distribution de config (LDS, RDS, CDS, EDS…) |
| SPIFFE | Secure Production Identity Framework For Everyone | Standard d'identité cryptographique pour les workloads (URI format) |
| SVID | SPIFFE Verifiable Identity Document | Le certificat mTLS concret — contient l'identité SPIFFE en Subject Alternative Name |
| EDS | Endpoint Discovery Service | Protocole xDS pour la découverte des endpoints (IP des pods) |
| LDS | Listener Discovery Service | Protocole xDS pour les règles d'écoute réseau d'Envoy |
| RDS | Route Discovery Service | Protocole xDS pour les règles de routage — VirtualService est traduit ici |
| CDS | Cluster Discovery Service | Protocole xDS pour les clusters Envoy — DestinationRule est traduit ici |
| SA | ServiceAccount | Identité K8s du pod — base de l'identité SPIFFE dans Istio |
| NEG | Network Endpoint Group | Groupe d'endpoints GKE exposé via Traffic Director (Cloud Service Mesh) |

---

## 1. Architecture Istio — Data plane et Control plane

### Vue d'ensemble

```
┌───────────────────────────────────────────────────────────────────────┐
│  CONTROL PLANE                                                        │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  Istiod (auto-hébergé) OU Traffic Director (Cloud Service Mesh) │  │
│  │  • Distribue les certificats mTLS (SPIFFE SVID)                 │  │
│  │  • Traduit les CRDs K8s (VS, DR, PA, AP) → config xDS           │  │
│  │  • Pousse la config aux proxies via gRPC xDS                    │  │
│  └─────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ xDS (gRPC longue durée)
┌──────────────────────────────▼────────────────────────────────────────┐
│  DATA PLANE — Pods meshés                                             │
│                                                                       │
│  ┌────────────────────────────────────┐                               │
│  │  Pod kube-train-api                │                               │
│  │  ┌──────────────┐ ┌──────────────┐ │                               │
│  │  │ api-container│ │ istio-proxy  │ │ ← sidecar Envoy               │
│  │  │ (port 8080)  │ │ (port 15001) │ │   intercepte tout le trafic   │
│  │  └──────────────┘ └──────────────┘ │   via iptables                │
│  └────────────────────────────────────┘                               │
└───────────────────────────────────────────────────────────────────────┘
```

**Règle d'or** :
- **Control plane** = le cerveau (Istiod / Traffic Director) — calcule et distribue la configuration
- **Data plane** = les muscles (proxies Envoy) — interceptent et routent chaque paquet réel

### Injection du sidecar

L'injection est gérée par un **Mutating Admission Webhook** K8s. Quand un pod est créé, le webhook intercepte la requête et ajoute le conteneur `istio-proxy` avant que K8s crée réellement le pod.

Conséquences :
- Un pod **déjà existant** ne reçoit pas le sidecar → il faut le redémarrer (`kubectl rollout restart`)
- Le sidecar est injecté **à l'admission** — labeller un namespace sans redémarrer ne fait rien

---

## 2. Cloud Service Mesh vs Istio auto-hébergé

> ⚠️ kube-train utilise **Cloud Service Mesh (managed ASM)** sur GKE, pas `istioctl install`. Les différences sont importantes à comprendre.

| Critère | Istio auto-hébergé (`istioctl install`) | Cloud Service Mesh (GKE Fleet) |
|---|---|---|
| Control plane | `Istiod` tourne dans `istio-system` | Traffic Director — xDS server Google (meshconfig.googleapis.com:443) |
| Activation namespace | `kubectl label namespace X istio-injection=enabled` | `kubectl label namespace X meshconfig.io/proxy-version=asm-managed-rapid` |
| Propagation config | Quasi-instantanée (Istiod dans le cluster) | **30 à 90 secondes** (Traffic Director distribue via Google Cloud) |
| APIs GCP requises | Aucune | `trafficdirector.googleapis.com`, `meshca.googleapis.com`, `networksecurity.googleapis.com`, `networkservices.googleapis.com` |
| Access logs istio-proxy | stdout du container | **Cloud Logging** (pas de stdout) |
| Coût | Compute du control plane (Istiod) | Inclus dans GKE Autopilot |
| `istioctl` | Obligatoire | Non requis (tout via kubectl + CRDs) |

### Identification concrète du mode

```bash
# Mode Traffic Director : Envoy se connecte à Google, pas au cluster
kubectl logs <pod> -c istio-proxy | grep "xdsproxy"
# → xdsproxy connected to upstream XDS server: meshconfig.googleapis.com:443
```

---

## 3. Les CRDs Istio essentiels

### 3.1 PeerAuthentication — Politique mTLS

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: notification-strict
  namespace: default
spec:
  selector:           # workloadSelector = ciblé sur un pod spécifique
    matchLabels:
      app: notification-pod
  mtls:
    mode: STRICT      # STRICT | PERMISSIVE | DISABLE
```

| Mode | Comportement |
|---|---|
| `STRICT` | Tout trafic entrant **doit** être mTLS. Un client sans sidecar est rejeté. |
| `PERMISSIVE` | Accepte mTLS et HTTP plain. Mode de migration/transition. |
| `DISABLE` | Désactive mTLS. Trafic en clair. |

**Sans `selector`** : la politique s'applique à tout le namespace. Dangereux si certains pods n'ont pas de sidecar (OTel collector déployé avant l'activation du mesh, par exemple).

### 3.2 DestinationRule — Politique vers une destination

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: kube-train-api
  namespace: default
spec:
  host: kube-train-service        # nom DNS du Service K8s
  subsets:
    - name: v1
      labels:
        version: v1               # filtre les pods avec ce label
    - name: v2
      labels:
        version: v2
```

La `DestinationRule` ne route pas le trafic — elle **définit les groupes de pods** (subsets). C'est le `VirtualService` qui décide quelle fraction du trafic va vers quel subset.

**Résultat concret** : Traffic Director crée 3 clusters Envoy distincts :
- `outbound|80|v1|kube-train-service...` → uniquement les pods `version=v1`
- `outbound|80|v2|kube-train-service...` → uniquement les pods `version=v2`
- `outbound|80||kube-train-service...` → tous les pods (cluster sans subset, utilisé hors VirtualService)

### 3.3 VirtualService — Règles de routage

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: kube-train-api
  namespace: default
spec:
  hosts:
    - kube-train-service         # service K8s ciblé
  http:
    - route:
        - destination:
            host: kube-train-service
            subset: v1           # utilise le subset de la DestinationRule
          weight: 90
        - destination:
            host: kube-train-service
            subset: v2
          weight: 10
```

**Le VirtualService sans DestinationRule** : les `subset` n'existent pas → trafic redirigé vers tous les pods → 503 si aucun endpoint ne matche.

### 3.4 AuthorizationPolicy — Contrôle d'accès L7

```yaml
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: notification-allow-only-api
  namespace: default
spec:
  selector:
    matchLabels:
      app: notification-pod       # workload ciblé
  action: ALLOW
  rules:
    - from:
        - source:
            principals:           # identité SPIFFE du client
              - "cluster.local/ns/default/sa/kube-train-api-sa"
      to:
        - operation:
            ports: ["8081"]
```

**Règle critique** : dès qu'une `AuthorizationPolicy` ALLOW existe sur un workload, tout ce qui ne matche **aucune règle** est refusé (allowlist implicite).

**Format principal** : `cluster.local/ns/<namespace>/sa/<serviceaccount>` — c'est l'URI SPIFFE sans le préfixe `spiffe://`.

---

## 4. ELI5 — Comment Istio intercepte le trafic (iptables)

**L'analogie** : imagine un immeuble de bureaux. Chaque étage a un gardien (proxy Envoy) devant l'ascenseur. Quand tu envoies un email à quelqu'un dans l'immeuble, le gardien de TON étage l'intercepte d'abord, applique les règles (mTLS, routing), puis le transmet au gardien de l'étage destinataire, qui remet enfin l'email à la bonne personne.

**Techniquement** :

```
┌────────────────────────────────────────────────────────────┐
│  Pod kube-train-api                                        │
│                                                            │
│  app-container → envoie à 10.x.x.x:8081                    │
│       │                                                    │
│       ▼ intercepté par iptables (règle REDIRECT)           │
│  istio-proxy (port 15001 — outbound)                       │
│       │ applique VirtualService / DestinationRule          │
│       │ chiffre en mTLS avec certificat SPIFFE             │
│       ▼                                                    │
│  → réseau K8s → Pod notification                           │
│                      │                                     │
│                      ▼ intercepté par iptables (inbound)   │
│                 istio-proxy (port 15006 — inbound)         │
│                      │ vérifie mTLS + AuthorizationPolicy  │
│                      ▼                                     │
│                 notification-container (port 8081)         │
└────────────────────────────────────────────────────────────┘
```

**init container `istio-init`** : s'exécute avant l'app et configure les règles iptables `REDIRECT`. Tout trafic sortant va vers le port 15001, tout trafic entrant vers le port 15006. L'app ne sait pas qu'Istio existe.

**Conséquence pour les annotations d'exclusion** : certaines destinations **ne doivent pas** passer par Envoy :
- `169.254.169.254` (GKE metadata server pour les tokens Workload Identity)
- Port 5432 inbound (Cloud SQL Auth Proxy — il gère son propre TLS)

---

## 5. ELI5 — mTLS avec Istio

### TLS normal (HTTPS standard)

```
Client ──── [vérifie le certificat du serveur] ──── Serveur
           "Je sais que je parle au bon serveur"
```

Le **serveur** prouve son identité. Le client reste anonyme.

### mTLS (mutual TLS)

```
Client ──── [échange de certificats bidirectionnel] ──── Serveur
           "On se connaît tous les deux"
```

**Les deux** présentent un certificat. Impossible d'usurper l'identité d'un service.

### Comment Istio le fait automatiquement

Istio/Cloud Service Mesh émet un certificat SPIFFE pour chaque pod. Le format est :
```
spiffe://cluster.local/ns/<namespace>/sa/<serviceaccount>
```

Par exemple :
- `spiffe://cluster.local/ns/default/sa/kube-train-api-sa` → c'est l'API
- `spiffe://cluster.local/ns/default/sa/notification-sa` → c'est le service de notification

Quand `kube-train-api` appelle `notification-service`, leurs proxies Envoy échangent ces certificats automatiquement. L'`AuthorizationPolicy` compare le certificat présenté avec la liste des principals autorisés.

**Sans Istio** : on ne peut faire confiance qu'à l'adresse IP du pod (volatile, réutilisable). **Avec Istio** : on fait confiance à l'identité cryptographique stable (lié au ServiceAccount K8s, pas à l'IP).

---

## 6. ELI5 — Traffic Director et propagation xDS

**L'analogie** : Traffic Director est comme un chef d'orchestre dans un datacenter Google. Tous les proxies Envoy du cluster sont des musiciens. Le chef envoie des partitions (config xDS) à chaque musicien. Le problème : la partition prend du temps à voyager de Paris (Google Cloud) jusqu'à chaque musicien (pod GKE).

**En pratique** :

```
kubectl apply -f peer-authentication-strict.yaml
        │
        ▼
Kubernetes API Server (K8s enregistre la CR)
        │
        ▼ ~2-5s
Traffic Director lit la CR (polling)
        │
        ▼ ~20-60s
Traffic Director traduit PA → config xDS
        │
        ▼ ~5-15s
Traffic Director pousse le nouvel xDS vers tous les Envoy du cluster
        │
        ▼
Envoy applique la nouvelle politique ← SEULEMENT ICI le STRICT est actif
```

**Total : 30 à 90 secondes.** C'est pourquoi les tests immédiats après `kubectl apply` donnent des résultats incorrects.

**Avec Istiod auto-hébergé** : Istiod est dans le cluster → propagation quasi-instantanée (~1s). La différence est fondamentale pour les tests.

---

## 7. Canary deployment — Architecture et observabilité

### Architecture des clusters Envoy

```bash
kubectl exec -n default mesh-client -c istio-proxy -- \
  pilot-agent request GET clusters | grep "kube-train-service"
```

Résultat réel obtenu :
```
outbound|80|v1|kube-train-service.default...::10.125.130.9:8080::rq_total::141
outbound|80|v2|kube-train-service.default...::10.125.130.10:8080::rq_total::9
```

**141 requêtes sur v1, 9 sur v2 = 6% pour v2** (attendu 10% — la variance statistique sur 150 requêtes est normale).

### Pourquoi les logs `istio-proxy` sont vides en Cloud Service Mesh

En managed ASM, l'Envoy sidecar envoie les access logs vers **Cloud Logging**, pas vers stdout. `kubectl logs -c istio-proxy` ne montrera que les messages de configuration et d'erreur du proxy, pas les logs HTTP.

**Pour vérifier si v2 reçoit du trafic** :
```bash
# ✅ Logs de l'application (Spring Boot) — visibles en stdout
kubectl logs -l version=v2 -c api-container | grep "GET /"

# ✅ Stats Envoy — compteurs de requêtes par cluster
kubectl exec -l version=v1 -c istio-proxy -- \
  pilot-agent request GET clusters | grep "rq_total"
```

### Observabilité du canary via le log Spring Boot

Le TrainController logue chaque requête avec le nom du pod :
```json
{"message":"GET / — pod=kube-train-deployment-v2-c564bdfdb-zsz74","trace_id":"..."}
```

Cela permet de confirmer que le VirtualService route bien vers v2, et d'observer le taux réel avec trace_id pour le correler dans Cloud Trace.

---

## 8. Istio + Cloud SQL Auth Proxy — pièges spécifiques GKE

### Le problème : `169.254.169.254` blackhole

```
Chronologie :
1. Istio est activé sur le namespace default
2. Envoy intercepte TOUT le trafic sortant via iptables (incluant 169.254.169.254)
3. Sans config xDS valide (Traffic Director non encore provisionné), Envoy blackhole le trafic
4. Cloud SQL Auth Proxy appelle GET http://169.254.169.254/computeMetadata/v1/... pour obtenir un token Workload Identity
5. La requête est interceptée par Envoy et blackholée → dial tcp 169.254.169.254:80: connection refused
6. Le proxy ne peut pas s'authentifier → connexion Cloud SQL échoue
7. Hibernate ne peut pas détecter le dialecte → Spring Boot crashe
```

**Fix** :
```yaml
annotations:
  traffic.sidecar.istio.io/excludeOutboundIPRanges: "169.254.169.254/32"
```

**Pourquoi c'est safe de hardcoder cette IP** : `169.254.169.254` est une adresse link-local **constante dans TOUS les environnements GCP**. C'est le GKE metadata server — son adresse ne changera jamais. Hardcoder est acceptable et même recommandé.

### Le problème : port 5432 inbound et mTLS

Sans l'exclusion du port 5432 en inbound, Envoy intercepte les connexions entrantes sur le port 5432 et tente d'appliquer mTLS. Le Cloud SQL Auth Proxy, qui écoute sur ce port, reçoit une négociation TLS inattendue et rejette la connexion.

**Fix** :
```yaml
annotations:
  traffic.sidecar.istio.io/excludeInboundPorts: "5432,15020"
```

`15020` est le port de santé de `pilot-agent` (le process qui gère le cycle de vie du sidecar Envoy) — l'exclure évite que les health probes K8s ne passent par Envoy et ne reçoivent une erreur 500 si la config xDS est incomplète.

---

## 9. APIs GCP requises pour Cloud Service Mesh

Cloud Service Mesh (gestion via `gcloud container fleet mesh enable`) ne documente pas explicitement toutes ses dépendances. Les 4 APIs suivantes doivent être actives :

| API | Rôle |
|---|---|
| `trafficdirector.googleapis.com` | Control plane xDS pour les proxies Envoy |
| `meshca.googleapis.com` | Autorité de certification pour les certificats mTLS SPIFFE |
| `networksecurity.googleapis.com` | Gestion des politiques de sécurité réseau (PeerAuthentication → TLS policy) |
| `networkservices.googleapis.com` | Gestion des règles de routage (VirtualService → URL map Traffic Director) |

**Symptôme si APIs manquantes** :
```
# istio-proxy log
gRPC status 5 (NOT_FOUND): Traffic Director configuration was not found for mesh "gsmrsvd-..."
```

```bash
# Diagnostic
gcloud container fleet mesh describe --project=kube-train-project
# → CONFIG_VALIDATION_ERROR: API is not enabled: Network Security API.
```

Ces 4 APIs sont maintenant gérées dans `infra/apis.tf` avec `disable_on_destroy = false` pour éviter de les désactiver lors d'un `terraform destroy`.

---

## 10. Erreurs et blocages rencontrés en TP — Retour d'expérience

> Tous ces incidents correspondent à des pièges réels en production. La plupart sont liés à la combinaison Cloud Service Mesh + GKE Autopilot, qui diffère d'une installation Istio standard.

### 10.1 Traffic Director NOT_FOUND — API `networksecurity` manquante

**Symptôme** : Pod kube-train crashe en boucle. Log `istio-proxy` :
```
gRPC status 5 (NOT_FOUND): Traffic Director configuration was not found for mesh "gsmrsvd-dpn6eu7b5ya45yfi4qr3r3-v6ycjxtj"
```

**Cause** : `gcloud container fleet mesh enable` a activé le mesh fleet mais sans l'API `networksecurity.googleapis.com` → Traffic Director ne peut pas créer les policies TLS → il ne trouve pas de config pour le mesh ID.

**Diagnostic** :
```bash
gcloud container fleet mesh describe --project=kube-train-project
# → Code: CONFIG_VALIDATION_ERROR
#    Message: API is not enabled: Network Security API.
#    Link: https://console.cloud.google.com/apis/library/networksecurity.googleapis.com
```

**Fix** :
```bash
gcloud services enable networksecurity.googleapis.com networkservices.googleapis.com
```

**Fix permanent dans `infra/apis.tf`** :
```hcl
resource "google_project_service" "networksecurity" {
  service            = "networksecurity.googleapis.com"
  disable_on_destroy = false
}
resource "google_project_service" "networkservices" {
  service            = "networkservices.googleapis.com"
  disable_on_destroy = false
}
```

---

### 10.2 Cloud SQL Auth Proxy — `dial tcp 169.254.169.254:80: connect: connection refused`

**Symptôme** : Pod kube-train démarre, atteint HikariPool init, puis crashe :
```
dial tcp 127.0.0.6:0->169.254.169.254:80: connect: connection refused
```

**Cause** : Istio (via iptables init container) intercepte TOUT le trafic sortant. Quand Envoy n'a pas encore de config xDS valide (Traffic Director non provisionné), il blackhole le trafic vers `169.254.169.254`. Le Cloud SQL Auth Proxy utilise cette IP pour obtenir ses tokens Workload Identity → 0 token → connexion Cloud SQL impossible.

**Fix** :
```yaml
# Dans le pod template annotations
traffic.sidecar.istio.io/excludeOutboundIPRanges: "169.254.169.254/32"
```

**Règle** : En présence de Cloud SQL Auth Proxy + Istio, cette annotation est **obligatoire**.

---

### 10.3 Hibernate `Unable to determine Dialect without JDBC metadata`

**Symptôme** :
```
Unable to determine Dialect without JDBC metadata (please set 'jakarta.persistence.jdbc.url'
```

**Cause** : Conséquence de 10.2. La connexion Cloud SQL échoue (SQLState 08001 = erreur réseau) → Spring Boot/Hibernate ne peut pas interroger la base pour détecter le dialecte → crash.

**Ce n'est PAS** un problème de configuration Spring/Hibernate. C'est une erreur 100% réseau remontée sous une forme trompeuse.

---

### 10.4 Health probe HTTP 500 via pilot-agent

**Symptôme** : La startupProbe retourne HTTP 500 en boucle jusqu'à SIGKILL (exit code 137).

**Cause** : Istio réécrit les health probes kubelet pour qu'elles passent par `pilot-agent` (port 15020). `pilot-agent` transmet la probe vers `10.x.x.x:8080` (IP du pod, pas localhost). Ce trafic passe par Envoy. Sans config xDS, Envoy bloque → 500.

**Fix** : même que 10.2 — une fois Envoy configuré correctement via Traffic Director, les probes passent normalement. L'annotation `excludeInboundPorts: "15020"` est aussi une solution de secours.

---

### 10.5 OTel gRPC `Required SETTINGS preface not received`

**Symptôme** (log notification-service) :
```
Required SETTINGS preface not received
```

**Cause** : Le pod notification a démarré pendant la période de transition où Traffic Director n'avait pas encore une config xDS complète. Envoy avait une config partielle/incohérente → négociation HTTP/2 (gRPC) entre le sidecar de notification et le sidecar de l'OTel collector échouait.

**Fix** :
```bash
kubectl rollout restart deployment/notification-deployment
```

Le nouveau pod obtient une config xDS complète depuis le début → plus d'erreur.

**Règle** : En cas d'erreurs gRPC HTTP/2 entre services meshés juste après activation d'Istio, redémarrer les pods affectés pour qu'ils obtiennent une config xDS propre.

---

### 10.6 PeerAuthentication STRICT non appliqué immédiatement (propagation Traffic Director)

**Symptôme** : `kubectl apply -f peer-authentication-strict.yaml`, puis immédiatement :
```bash
kubectl exec plain-client -- curl http://notification-service:8081/actuator/health
# → HTTP 200 OK  ← attendu : connexion refusée
```

**Cause** : Traffic Director (Cloud Service Mesh managed) prend **30 à 90 secondes** pour distribuer un nouvel xDS policy aux proxies Envoy. Un test lancé immédiatement après `kubectl apply` teste l'ancien comportement.

**Même phénomène** pour `AuthorizationPolicy` : `other-client` a obtenu HTTP 200 (au lieu de 403) car l'AP n'était pas encore propagée.

**Comportement correct après attente** :
- `plain-client` (sans sidecar) : `Connection reset by peer`
- `other-client` (SA default, pas autorisé) : `HTTP 403 RBAC: access denied`

**Règle** : Avec Cloud Service Mesh (Traffic Director), **toujours attendre 60+ secondes** entre `kubectl apply` d'une politique de sécurité et le test de son application. Avec Istiod auto-hébergé, quelques secondes suffisent.

---

### 10.7 deployment-gke-v2.yaml avec `IMAGE_TAG_PLACEHOLDER`

**Symptôme** : `kubectl apply -f k8s/workloads/deployment-gke-v2.yaml` → pod en `ImagePullBackOff` / `ErrImagePull`.

**Cause** : Le fichier `deployment-gke-v2.yaml` a été poussé sur GitHub avec `IMAGE_TAG_PLACEHOLDER` comme tag d'image (placeholder pour la CI/CD). L'utilisateur a appliqué le fichier **avant** que la CI/CD ait fini de remplacer le placeholder par le SHA réel.

**Fix immédiat** :
```bash
# Récupérer le tag depuis le déploiement v1 (déjà mis à jour par CI/CD)
IMAGE=$(kubectl get deployment kube-train-deployment \
  -o jsonpath='{.spec.template.spec.containers[?(@.name=="api-container")].image}')
kubectl set image deployment/kube-train-deployment-v2 api-container=$IMAGE
```

**Fix durable** : Toujours `git pull` avant d'appliquer les manifests qui contiennent des tags d'images gérés par la CI/CD :
```bash
git pull && kubectl apply -f k8s/workloads/deployment-gke-v2.yaml
```

---

### 10.8 Access logs istio-proxy absents en stdout (Cloud Service Mesh)

**Symptôme** : `kubectl logs -l version=v2 -c istio-proxy | grep '"GET'` retourne vide malgré le trafic.

**Cause** : En mode Cloud Service Mesh (Traffic Director), les access logs Envoy sont envoyés vers **Cloud Logging** (via l'agent GKE), pas vers stdout. `kubectl logs` ne montre que les messages internes du proxy (connexion xDS, erreurs config, probes).

**Alternatives pour observer le trafic** :
```bash
# Logs Spring Boot de l'application (stdout)
kubectl logs -l version=v2 -c api-container | grep "GET /"

# Stats Envoy en temps réel
kubectl exec -l version=v2 -c istio-proxy -- \
  pilot-agent request GET clusters | grep "rq_total"

# Cloud Logging (dans la console GCP ou via gcloud)
gcloud logging read 'resource.type="k8s_container" AND labels."k8s-pod/version"="v2"' \
  --project=kube-train-project --limit=20
```

---

## 11. Tests E2E avec données réelles

> Tests réalisés sur le cluster `kube-train-cluster` en `europe-west1`, namespace `default`, le 24-25 juin 2026.

### 11.1 mTLS STRICT — Résultat réel

**PeerAuthentication** sur `app: notification-pod` avec `mode: STRICT`.

```bash
# Pod meshé (SA kube-train-api-sa, sidecar 2/2) → AUTORISÉ
kubectl exec -n default mesh-client -c curl -- \
  curl -s http://notification-service:8081/actuator/health
# → {"groups":["liveness","readiness"],"status":"UP"}

# Pod sans sidecar (plain-client, 1/1) → REFUSÉ
kubectl exec -n default plain-client -c curl -- \
  curl -sv http://notification-service:8081/actuator/health
# → * Connected to notification-service (34.118.230.33) port 8081
#   * Recv failure: Connection reset by peer
```

**Note d'implémentation** : Le premier test (juste après `kubectl apply`) a montré HTTP 200 pour plain-client — le policy n'était pas encore propagé. Après ~60s, la connexion est refusée comme attendu.

### 11.2 Canary 90/10 — Stats Envoy réelles

```
outbound|80|v1|kube-train-service.default.svc.cluster.local::10.125.130.9:8080::rq_total::141
outbound|80|v2|kube-train-service.default.svc.cluster.local::10.125.130.10:8080::rq_total::9
```

- **94% vers v1, 6% vers v2** sur 150 requêtes — proche du 90/10 configuré (variance statistique normale sur petit échantillon).
- Les logs Spring Boot de v2 confirment : `GET / — pod=kube-train-deployment-v2-c564bdfdb-zsz74`

### 11.3 AuthorizationPolicy — Résultats réels

```bash
# mesh-client (SA kube-train-api-sa) — AUTORISÉ par la policy
curl http://notification-service:8081/actuator/health
# → {"groups":["liveness","readiness"],"status":"UP"}

# other-client (SA default, meshé mais non autorisé) — REFUSÉ
curl -sv http://notification-service:8081/actuator/health
# → < HTTP/1.1 403 Forbidden
# → RBAC: access denied
```

**Comportement Istio RBAC** : le 403 inclut le message `RBAC: access denied` (header `content-length: 19`). Le client sait qu'il est bien refusé par Istio, pas par l'application.

### 11.4 Fault injection 500ms — Mesure réelle

```bash
time kubectl exec -n default mesh-client -c curl -- \
  curl -s http://kube-train-service/reservations/TEST > /dev/null
# → real  0m0.980s
```

**~980ms total** = ~450ms overhead kubectl/réseau + **500ms de délai injecté**. La fault injection fonctionne côté Envoy source (avant même que la requête ne parte vers le pod).

### 11.5 Labels Istio automatiquement injectés

```bash
kubectl get pods -l app=kube-train-pod --show-labels
```

Istio ajoute automatiquement ces labels sur les pods :
```
security.istio.io/tlsMode=istio          → mTLS activé
service.istio.io/canonical-name=kube-train-pod
service.istio.io/canonical-revision=v1   → déduit du label version: v1
```

Le label `canonical-revision` est déduit automatiquement du label `version` — Istio le standardise pour l'observabilité (Kiali, Jaeger).

---

## 12. Fichiers Istio créés dans kube-train (récapitulatif)

| Fichier | Rôle |
|---|---|
| `k8s/istio/peer-authentication-strict.yaml` | mTLS STRICT sur notification-pod (workloadSelector) |
| `k8s/workloads/notification-service.yaml` | Service ClusterIP pour les tests mTLS intra-cluster |
| `k8s/network/network-policy-notification.yaml` | NetworkPolicy allow-ingress port 8081 (séparation L4 / L7) |
| `k8s/istio/istio-test-pods.yaml` | mesh-client (sidecar + SA kube-train-api-sa) + plain-client (no sidecar) |
| `k8s/workloads/deployment-gke-v2.yaml` | Déploiement canary v2 (même image, label version: v2) |
| `k8s/istio/istio-canary.yaml` | DestinationRule (subsets v1/v2) + VirtualService 90/10 |
| `k8s/istio/authorization-policy.yaml` | AuthorizationPolicy ALLOW uniquement kube-train-api-sa → notification-pod |
| `k8s/istio/istio-fault-injection.yaml` | VirtualService avec 500ms fixedDelay sur /reservations |
| `infra/apis.tf` | 4 APIs GCP requises pour Cloud Service Mesh (gérées par Terraform) |
| `k8s/workloads/deployment-gke.yaml` | Annotations Istio + label version: v1 + fix cloud-sql-proxy 128Mi |
| `k8s/workloads/notification-deployment-gke.yaml` | Annotations Istio (excludeOutboundIPRanges + excludeInboundPorts) |
| `k8s/security/quota.yaml` | Recalibré : pods 10, limits.memory 8Gi (canary + test pods) |

---

## 13. Coût GKE avec Istio

| Composant | Requests CPU | Requests memory | Impact coût Autopilot |
|---|---|---|---|
| Sidecar `istio-proxy` (par pod) | ~100m | ~128Mi | +~15% par pod meshé |
| 3 pods existants avec sidecar | +300m total | +384Mi total | ~+0.5€/jour |
| Pod canary v2 (démo) | 100m+50m+100m | 512Mi+128Mi+128Mi | ~+0.3€/jour |

**Règle** : `terraform destroy` en fin de journée reste la stratégie principale. Avec Istio, le coût journalier passe de ~3.5€ à ~4.2€ (hors canary v2 si supprimé).

```bash
# Fin de journée — supprimer les ressources de démo
kubectl delete -f k8s/workloads/deployment-gke-v2.yaml
kubectl delete pod mesh-client plain-client other-client
```

---

## 14. Pour aller plus loin — Questions avancées

### 14.1 Différence entre NetworkPolicy et AuthorizationPolicy

| Critère | NetworkPolicy (K8s) | AuthorizationPolicy (Istio) |
|---|---|---|
| Niveau | L3/L4 (IP, port TCP) | L7 (identité mTLS, path HTTP, méthode) |
| Identité | Adresse IP du pod (volatile) | Certificat SPIFFE (stable, lié au SA) |
| Opérateur | kube-proxy / CNI | Envoy proxy |
| Granularité | Pod → Pod sur un port | ServiceAccount → Path HTTP spécifique |
| Requiert Istio | Non | Oui |

**Bonne pratique** : Utiliser les deux en complément :
- NetworkPolicy = défense en profondeur L4 (un pod sans sidecar ne peut pas même se connecter)
- AuthorizationPolicy = contrôle fin L7 (un pod avec sidecar mais mauvaise identité reçoit 403)

### 14.2 Que se passe-t-il si on supprime `istio-fault-injection.yaml` sans supprimer `istio-canary.yaml` ?

`istio-fault-injection.yaml` écrase le `VirtualService kube-train-api`. Pour revenir au canary sans latence :
```bash
kubectl apply -f k8s/istio/istio-canary.yaml
```
Cela met à jour le VirtualService existant (même `name: kube-train-api`) pour supprimer la section `fault`. La DestinationRule n'est pas affectée.

### 14.3 Pourquoi Traffic Director crée-t-il un cluster `outbound|80||kube-train-service` en plus des subsets v1/v2 ?

Le cluster sans subset (`||`) correspond au `kube-train-service` sans VirtualService — c'est le cluster de **fallback**. Il est utilisé par les pods non-meshés ou pour les requêtes qui ne correspondent à aucune règle VirtualService. Il contient tous les endpoints (v1 et v2), comme un Service K8s normal.

Dans l'output réel observé :
```
outbound|80||kube-train-service::10.125.130.9:8080::rq_total::13   (v1)
outbound|80||kube-train-service::10.125.130.10:8080::rq_total::28  (v2)
```
Ces 41 requêtes viennent d'autres sources (health probes kubelet via pilot-agent, ou trafic externe via LoadBalancer avant que le VirtualService soit propagé).
