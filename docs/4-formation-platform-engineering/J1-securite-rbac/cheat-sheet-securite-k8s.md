# Cheat Sheet — Sécurité Kubernetes (F4-J1)

> Glossaire et schémas de référence pour la sécurisation d'un cluster K8s.  
> Focus sur les objets utilisés dans kube-train.

---

## 1. Architecture multi-couche et frontières du Namespace

```
CLUSTER (1 plan de contrôle)
  └── NODE (VM physique/virtuelle — unité de calcul)
        └── POD (unité de scheduling — 1 IP, volumes partagés)
              └── CONTAINER (processus isolé — 1 runtime)

NAMESPACE (dimension logique, orthogonale aux nodes)
  ├── Peut contenir des pods sur n'importe quel node
  ├── Les pods d'un même namespace peuvent être sur des nodes différents
  └── Plusieurs namespaces partagent les mêmes nodes
```

### Ce qu'un namespace isole (et ce qu'il n'isole PAS)

| Dimension | Namespace isole ? | Détail |
|---|---|---|
| **Noms de ressources** | ✅ Oui | Un Deployment `api` peut exister dans `default` ET dans `staging` |
| **RBAC** | ✅ Oui | Un Role/RoleBinding est namespace-scoped |
| **Quotas / LimitRange** | ✅ Oui | ResourceQuota et LimitRange s'appliquent au namespace |
| **Secrets / ConfigMaps** | ✅ Oui | Un pod ne peut pas lire les Secrets d'un autre namespace |
| **Réseau** | ❌ Non (par défaut) | Les pods peuvent communiquer inter-namespace sauf `NetworkPolicy` |
| **Nœuds physiques** | ❌ Non | Les pods de namespaces différents peuvent cohabiter sur le même node |
| **Syscalls / kernel** | ❌ Non | Nécessite `securityContext` + `seccompProfile` |

**Analogie** : le namespace est un "dossier virtuel" dans le cluster — il organise et contrôle les accès,
mais ne crée pas de barrière réseau ou physique.

---

## 2. PSS (Pod Security Standards) vs securityContext

### Vue d'ensemble

| Mécanisme | Niveau d'application | Qui le configure | Ce qu'il fait |
|---|---|---|---|
| **PSS** | **Namespace** | Administrateur cluster (labels sur ns) | Définit ce qui est autorisé à _entrer_ dans le namespace |
| **securityContext pod** | **Pod** | Développeur (spec.securityContext) | Configure l'environnement du pod entier |
| **securityContext container** | **Container** | Développeur (containers[].securityContext) | Configure le processus du conteneur |

### PSS — 3 niveaux × 3 modes

```yaml
# k8s/namespace-pss.yaml
metadata:
  labels:
    pod-security.kubernetes.io/enforce: baseline     # bloque si violation
    pod-security.kubernetes.io/audit: restricted     # log si violation
    pod-security.kubernetes.io/warn: restricted      # warning si violation
```

| Niveau | Ce qu'il bloque |
|---|---|
| `privileged` | Rien — tout est autorisé |
| `baseline` | hostNetwork, hostPID, capabilities dangereuses (SYS_ADMIN…) |
| `restricted` | Tout baseline + runAsNonRoot obligatoire, allowPrivilegeEscalation:false, seccompProfile requis, drop:ALL, volumes limités |

**Modes** : `enforce` (bloque le pod) / `warn` (warning côté client) / `audit` (log dans l'audit log K8s)

### securityContext — Répartition pod vs container

```yaml
spec:
  securityContext:               # Pod-level → s'applique à TOUS les containers
    runAsNonRoot: true           # refuse de lancer si UID=0
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000                # GID pour les volumes montés (pod-level UNIQUEMENT)
    seccompProfile:
      type: RuntimeDefault       # peut être surchargé par container
  containers:
  - securityContext:             # Container-level → processus du container
      allowPrivilegeEscalation: false   # container-level UNIQUEMENT
      readOnlyRootFilesystem: true      # container-level UNIQUEMENT
      capabilities:
        drop: [ALL]                     # container-level UNIQUEMENT
```

**Règle mnémotechnique** :
- `fsGroup` → seul le pod sait quels volumes sont montés → pod-level uniquement
- `allowPrivilegeEscalation`, `readOnlyRootFilesystem`, `capabilities` → propriétés d'un processus → container-level uniquement
- `runAsUser`, `runAsGroup`, `seccompProfile` → peuvent être définis aux deux niveaux (le container surcharge le pod)

---

## 3. ServiceAccounts — Stratégie de découpage

### Principe : 1 SA par workload (pas par pod, pas par namespace)

```
Cluster
  └── namespace: default
        ├── SA: kube-train-api-sa       → uniquement pour l'API Spring Boot
        ├── SA: notification-sa         → uniquement pour le service notification
        └── SA: default                 → ne jamais l'utiliser pour des workloads applicatifs
```

### Pourquoi pas le SA `default` ?

Si une autre app dans le namespace a besoin d'un accès étendu et utilise `default`,
tous les pods (y compris les tiens) héritent de ces droits. Le SA dédié isole l'identité RBAC.

### Granularité recommandée

| Quoi | SA dédié ? | Pourquoi |
|---|---|---|
| Service applicatif (API, notif…) | ✅ 1 SA par service | Isolation RBAC, audit distinct |
| Base de données (Postgres) | ✅ SA dédié | N'accède pas aux secrets applicatifs |
| Batch / CronJob | ✅ SA dédié | Permissions temporelles différentes |
| Accès DB (login/password) | ❌ Pas via SA | Via Secret + envFrom (ce n'est pas du RBAC K8s) |

### GKE : SA + Workload Identity

Sur GKE, le SA K8s peut être annoté pour s'authentifier auprès des APIs GCP sans clé de service :

```yaml
# k8s/rbac-gke.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kube-train-api-sa
  annotations:
    iam.gke.io/gcp-service-account: kube-train-sa@kube-train-project.iam.gserviceaccount.com
```

---

## 4. RBAC — Anatomie et portée

### Les 4 objets RBAC

| Objet | Scope | Contient |
|---|---|---|
| `Role` | Namespace | Règles d'accès (resources + verbs) |
| `ClusterRole` | Cluster | Règles d'accès (resources + verbs) |
| `RoleBinding` | **Namespace** | Lie un SA/User à un Role ou ClusterRole |
| `ClusterRoleBinding` | Cluster | Lie un SA/User à un ClusterRole |

**Piège classique CKAD** : un `RoleBinding` qui référence un `ClusterRole` donne des droits
**uniquement dans le namespace du RoleBinding** — pas dans tout le cluster.

```yaml
# C'est le RoleBinding qui détermine la portée, pas le ClusterRole référencé
```

### Exemple kube-train (k8s/rbac.yaml)

```yaml
kind: Role
rules:
- apiGroups: [""]
  resources: ["secrets"]
  resourceNames: ["kube-train-secrets"]  # garde-fou : un seul secret précis
  verbs: ["get"]                          # get seulement (pas list, pas watch)
---
kind: RoleBinding
subjects:
- kind: ServiceAccount
  name: kube-train-api-sa
roleRef:
  kind: Role
  name: kube-train-api-secret-reader
```

### Verbes RBAC — Ce qu'ils permettent

| Verbe | Action | Quand l'autoriser |
|---|---|---|
| `get` | Lire 1 ressource par nom | Lecture d'un secret précis ✅ |
| `list` | Lister toutes les ressources du type | Dangereux sur secrets — expose tout |
| `watch` | Écouter les changements en continu | Inutile pour une app stateless |
| `create` | Créer | Rarement nécessaire côté app |
| `update` / `patch` | Modifier | Rarement nécessaire côté app |
| `delete` | Supprimer | Pratiquement jamais côté app |

---

## 5. LimitRange, ResourceQuota, InitContainers

### LimitRange — Portée : container (dans un pod)

```yaml
# k8s/quota.yaml
kind: LimitRange
spec:
  limits:
  - type: Container       # s'applique à chaque container individuellement
    default:              # valeurs injectées si absent du manifest
      memory: "256Mi"
      cpu: "250m"
    max:                  # plafond par container
      memory: "512Mi"
      cpu: "500m"
    min:                  # plancher par container
      memory: "64Mi"
      cpu: "50m"
```

**Rôle** : injecter des defaults et borner les requests/limits par container.
Sans LimitRange, un container sans `resources:` peut consommer tout le node.

### ResourceQuota — Portée : namespace (total cumulé)

```yaml
kind: ResourceQuota
spec:
  hard:
    pods: "6"               # max 6 pods dans le namespace (tous pods confondus)
    requests.cpu: "1"       # total CPU requests ≤ 1 core
    requests.memory: "1Gi"
    limits.cpu: "2"
    limits.memory: "2Gi"
```

**Rôle** : limiter la consommation totale d'un namespace.
LimitRange + ResourceQuota sont complémentaires :
- LimitRange → protège le node (par container)
- ResourceQuota → protège le cluster (par namespace)

**Retour d'expérience J1** : `pods: "4"` a bloqué le rolling update car le namespace `default`
contenait déjà la stack monitoring (~9 pods). Solution : namespace dédié par équipe en prod.

### InitContainers — Portée : pod (avant les containers applicatifs)

```yaml
spec:
  initContainers:
  - name: wait-for-postgres
    image: busybox:1.36
    command: ['sh', '-c', 'until nc -z postgres-service 5432; do sleep 2; done']
    securityContext:         # securityContext s'applique au container init aussi
      runAsNonRoot: true
      allowPrivilegeEscalation: false
      capabilities:
        drop: [ALL]
```

**Comportement** :
1. Les init containers s'exécutent séquentiellement (l'un après l'autre)
2. Un init container qui échoue → pod en `Init:Error` → kubelet redémarre l'init container
3. Les containers applicatifs ne démarrent **qu'après** la réussite de tous les init containers

**Piège GKE** : si l'init container attend `127.0.0.1:5432` et que le Cloud SQL Proxy est
un sidecar du même pod → deadlock (le sidecar ne démarre qu'après les init containers,
mais l'init container attend le sidecar). Solution : init container attend le service K8s, pas localhost.

---

## 6. Sidecar — Type, emplacement, configuration

### Qu'est-ce qu'un sidecar ?

Un sidecar est un **container ordinaire** dans `spec.containers[]`, qui s'exécute
**en parallèle** du container applicatif principal, pendant toute la vie du pod.

```
Pod
  ├── containers[0]: api-container    (container principal — Spring Boot)
  └── containers[1]: cloud-sql-proxy  (sidecar — Cloud SQL Auth Proxy)
```

Ils partagent :
- L'adresse IP du pod (`localhost` entre eux)
- Les volumes déclarés dans `spec.volumes[]`
- Le cycle de vie du pod

### Exemple kube-train — Cloud SQL Auth Proxy (GKE)

```yaml
spec:
  containers:
  - name: api-container
    image: kube-train-api:...
    env:
    - name: SPRING_DATASOURCE_URL
      value: "jdbc:postgresql://127.0.0.1:5432/trains"  # sidecar écoute sur localhost
  
  - name: cloud-sql-proxy                               # sidecar
    image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:2
    args:
    - "--structured-logs"
    - "kube-train-project:europe-west1:kube-train-db"
    securityContext:
      runAsNonRoot: true
      allowPrivilegeEscalation: false
    resources:
      requests:
        memory: "32Mi"
        cpu: "10m"
```

### Sidecar natif K8s 1.28+ (optionnel)

Depuis K8s 1.28, on peut déclarer un sidecar comme init container avec `restartPolicy: Always`.
Il démarre en premier et reste actif. Cela résout le deadlock init container / sidecar.

```yaml
initContainers:
- name: cloud-sql-proxy
  restartPolicy: Always    # sidecar natif — démarre avant les init containers classiques
  image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:2
```

kube-train utilise l'approche classique (`spec.containers[]`) car GKE Autopilot le supporte.

---

## 7. Fichiers K8s importants pour la sécurité du cluster

Ordre de lecture recommandé pour auditer la sécurité d'un cluster kube-train :

### `k8s/namespace-pss.yaml` — Première ligne de défense

```yaml
# Labels PSS sur le namespace — contrôle ce qui peut entrer
pod-security.kubernetes.io/enforce: baseline
pod-security.kubernetes.io/audit: restricted
pod-security.kubernetes.io/warn: restricted
```

Vérifier : le mode `enforce` est-il au moins `baseline` ? Le mode `audit/warn` est-il `restricted` ?

### `k8s/rbac.yaml` / `k8s/rbac-gke.yaml` — Identités et droits

- Définit les ServiceAccounts dédiés (pas `default`)
- Définit les Roles avec `resourceNames` (accès au secret précis seulement)
- Vérifie que `automountServiceAccountToken: false` est présent
- Sur GKE : annotation Workload Identity présente ?

### `k8s/deployment.yaml` / `k8s/deployment-gke.yaml` — Hardening runtime

Checklist sécurité dans un Deployment :

```yaml
spec:
  template:
    spec:
      serviceAccountName: kube-train-api-sa    # SA dédié (pas default)
      automountServiceAccountToken: false       # token K8s non monté si inutile
      securityContext:                          # pod-level
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
        seccompProfile:
          type: RuntimeDefault
      containers:
      - securityContext:                        # container-level
          allowPrivilegeEscalation: false
          readOnlyRootFilesystem: true
          capabilities:
            drop: [ALL]
        resources:                              # présent ? (requis pour LimitRange/Quota)
          requests: { memory: "...", cpu: "..." }
          limits:   { memory: "...", cpu: "..." }
```

### `k8s/quota.yaml` — Gouvernance des ressources

- `LimitRange` : default/min/max par container bien dimensionnés ?
- `ResourceQuota` : nombre de pods compatible avec le rolling update ? (`pods >= replicas * 2 + DaemonSets`)

### Fichiers à ne PAS commiter (secrets hors cluster)

| Ce qui ne doit PAS être dans git | Où ça doit être |
|---|---|
| `kubectl create secret` avec valeurs réelles | GitHub Actions Secrets / Secret Manager |
| `.env` ou fichiers de credentials | Hors repo |
| Clés de service GCP (`.json`) | Workload Identity (pas de clé) |
| Réponses QCM personnelles | `~/Downloads` (hors repo) |

---

## Résumé visuel — Qui contrôle quoi

```
CLUSTER
  ├── PSS (namespace labels)          → ce qui est AUTORISÉ À ENTRER dans le namespace
  │     enforce/warn/audit × privileged/baseline/restricted
  │
  ├── RBAC (Role + RoleBinding)       → QUI peut faire QUOI sur QUELLES ressources K8s
  │     SA → Role (verbs: get/list/watch + resourceNames)
  │
  └── NAMESPACE
        ├── ResourceQuota             → TOTAL consommable par le namespace
        │
        └── POD
              ├── LimitRange          → BORNES par container (injectées si absent)
              ├── securityContext pod → env commun à tous les containers du pod
              ├── initContainers      → séquentiels, bloquants, avant les containers app
              │
              └── CONTAINER
                    ├── securityContext container → processus du container
                    └── sidecar = container ordinaire dans containers[], parallèle au main
```
