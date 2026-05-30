# Notes J1 — Sécurité Kubernetes & RBAC

> Concepts clés, pièges rencontrés, et points d'entretien.

---

### 1) Pod Security Standards (PSS)

Les **Pod Security Standards** remplacent l'ancien monde PSP (PodSecurityPolicy) par 3 profils simples et cumulatifs. Ils s'appliquent au **niveau namespace** via des labels et sont contrôlés par l'admission controller **Pod Security Admission**.

#### Les 3 niveaux

| Niveau | Intention | Ce que ça autorise / interdit | Lecture kube-train |
|--------|-----------|-------------------------------|--------------------|
| **Privileged** | Quasi sans garde-fou | Autorise les conteneurs privilégiés, hostNetwork, hostPID, hostPath, capabilities élevées | Réservé aux composants infra très particuliers, pas à `kube-train-api` |
| **Baseline** | Bloquer les escalades évidentes | Interdit notamment `privileged: true`, `hostNetwork`, `hostPID`, `hostIPC`, `hostPath`, `hostPort`, `seccomp: Unconfined`, ajout de capabilities dangereuses | Bon premier filet de sécurité pour un namespace applicatif |
| **Restricted** | Hardening moderne | Reprend Baseline **+** exige `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `seccompProfile: RuntimeDefault`, `capabilities.drop` incluant `ALL`, volumes limités à des types sûrs | Cible CKAD / prod pour `kube-train-api`, mais demande un vrai travail de compatibilité |

#### Les 3 modes

| Mode | Effet | Usage recommandé |
|------|-------|------------------|
| **enforce** | Le Pod est **refusé** si non conforme | Quand la politique est stabilisée |
| **warn** | Le Pod passe, mais `kubectl` affiche un warning | Phase d'apprentissage / migration |
| **audit** | Le Pod passe, mais une annotation d'audit est produite | Observer l'écart sans casser les déploiements |

#### Labels namespace

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: kube-train
  labels:
    pod-security.kubernetes.io/enforce: baseline
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/audit-version: latest
    pod-security.kubernetes.io/warn: restricted
    pod-security.kubernetes.io/warn-version: latest
```

```bash
kubectl label namespace kube-train \
  pod-security.kubernetes.io/enforce=baseline \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted \
  --overwrite
```

**À retenir :**
- `enforce` agit au moment de la création réelle du Pod.
- `warn` et `audit` sont très utiles pour tester un `Deployment` avant de casser un rollout.
- Les PSS **n'ajoutent rien** automatiquement : ils **rejettent** ou **signalent** un manifest non conforme.

**Exemple kube-train** : aujourd'hui les manifests GKE sont proches d'un niveau `baseline`, mais pas encore `restricted` partout (ex. `kube-train-api` n'a pas encore un `securityContext` complet sur le conteneur principal).

---

### 2) `securityContext` — référence complète

Le `securityContext` définit **comment** un Pod/conteneur s'exécute à runtime. Les PSS vérifient plusieurs de ces champs.

#### Pod-level

| Champ | Rôle | Pourquoi c'est utile dans kube-train |
|-------|------|--------------------------------------|
| `runAsNonRoot: true` | Refuse le démarrage si l'utilisateur effectif est root | Évite qu'un `java -jar` tourne en UID 0 |
| `runAsUser: 1000` | Force un UID non-root explicite | Rend le comportement prédictible, utile en entretien CKAD |
| `fsGroup: 1000` | Donne un groupe commun sur les volumes montés | Pratique si un `emptyDir` ou un PVC doit être écrit par un process non-root |
| `seccompProfile.type: RuntimeDefault` | Active le profil seccomp par défaut du runtime | Réduit la surface syscall exposée |

#### Container-level

| Champ | Rôle | Effet concret |
|-------|------|---------------|
| `allowPrivilegeEscalation: false` | Empêche les escalades via setuid/setgid | Requis par `restricted` |
| `readOnlyRootFilesystem: true` | Rend la racine du conteneur non modifiable | Très bon garde-fou, mais impose des volumes pour les écritures temporaires |
| `capabilities.drop: [ALL]` | Retire les capabilities Linux héritées | Sur `kube-train-api`, on écoute sur 8080, donc on n'a pas besoin d'en rajouter |

#### Point clé kube-train : le piège `/tmp`

Avec `readOnlyRootFilesystem: true`, un conteneur Spring Boot/Tomcat peut échouer si aucun espace d'écriture n'est prévu pour les fichiers temporaires.

**Solution classique** : monter un `emptyDir` sur `/tmp`.

#### Exemple YAML — Pod `kube-train-api`

```yaml
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
      ports:
        - containerPort: 8080
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

**À retenir :**
- `readOnlyRootFilesystem: true` **n'interdit pas toute écriture**, seulement sur la racine image. Une écriture reste possible sur un volume (`emptyDir`, PVC, secret projeté, etc.).
- Le niveau `restricted` demande surtout : non-root, pas d'escalade, seccomp par défaut, capabilities minimales.
- En multi-container pod, il faut penser au **container principal**, aux **init containers** et aux sidecars.

---

### 3) ServiceAccounts

Un **ServiceAccount** représente l'identité Kubernetes d'un Pod lorsqu'il parle à l'API server ou, sur GKE, lorsqu'il est relié à une identité GCP via **Workload Identity**.

#### Pourquoi éviter `default`

Utiliser `default` est pratique pour un lab rapide, mais mauvais en prod :
- identité partagée entre plusieurs workloads ;
- audit moins lisible ;
- on finit souvent par sur-autoriser un compte commun ;
- si un seul workload a besoin d'un rôle supplémentaire, tous les pods qui utilisent `default` héritent du risque.

**État actuel kube-train** : le pipeline GKE annote aujourd'hui `serviceaccount default` pour Workload Identity. Fonctionnel, mais pas idéal pédagogiquement. La bonne évolution J1 est : **1 ServiceAccount dédié par workload**.

#### Exemple : SA dédié pour l'API

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kube-train-api-sa
  namespace: default
  annotations:
    iam.gke.io/gcp-service-account: 399291708401-compute@developer.gserviceaccount.com
```

Puis dans le `Deployment` :

```yaml
spec:
  template:
    spec:
      serviceAccountName: kube-train-api-sa
      automountServiceAccountToken: false
```

#### Pourquoi `automountServiceAccountToken: false` reste intéressant

Dans kube-train, l'API n'a pas besoin d'appeler directement l'API Kubernetes. On peut donc **désactiver le montage du token K8s** pour réduire la surface d'attaque, tout en gardant Workload Identity côté GKE via le metadata server.

**Nuance entretien** :
- **annotation Workload Identity** = lien entre SA Kubernetes et SA GCP ;
- **token K8s monté dans le pod** = utile seulement si l'application appelle réellement l'API Kubernetes.

---

### 4) RBAC

Le **RBAC** (Role-Based Access Control) décrit **qui** peut faire **quoi** sur **quelle ressource**.

#### Rappels

| Objet | Portée | Sert à quoi ? |
|------|--------|----------------|
| `Role` | Namespace | Autoriser des actions dans **un** namespace |
| `ClusterRole` | Cluster | Autoriser sur des ressources cluster-wide, ou réutiliser une règle dans plusieurs namespaces |
| `RoleBinding` | Namespace | Lie un `Role` ou un `ClusterRole` à un sujet **dans un namespace** |
| `ClusterRoleBinding` | Cluster | Lie un `ClusterRole` à l'échelle cluster |

#### Verbs à connaître

| Verb | Sens |
|------|------|
| `get` | Lire un objet précis |
| `list` | Lister une collection |
| `watch` | Écouter les changements |
| `create` | Créer |
| `update` | Modifier complètement |
| `patch` | Modifier partiellement |
| `delete` | Supprimer |

#### Bon réflexe sécurité

Toujours donner le **minimum** :
- préférer `get` à `list` si on connaît le nom de la ressource ;
- éviter `*` dans les verbs ;
- éviter `ClusterRoleBinding` si un simple `RoleBinding` suffit.

#### Exemple — accès **uniquement** au secret `kube-train-secrets`

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: kube-train-api-secret-reader
  namespace: default
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: ["kube-train-secrets"]
    verbs: ["get"]
```

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: kube-train-api-secret-reader-binding
  namespace: default
subjects:
  - kind: ServiceAccount
    name: kube-train-api-sa
    namespace: default
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: kube-train-api-secret-reader
```

#### Vérification rapide

```bash
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa \
  -n default

kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa \
  -n default
```

**Lecture attendue** : `get` = yes, `list` = no.

**Piège classique** : un `RoleBinding` reste **namespace-scoped**, même s'il référence un `ClusterRole`.

---

### 5) LimitRange & ResourceQuota

Ces deux objets sont complémentaires mais n'agissent pas au même niveau.

| Objet | Niveau | Rôle |
|------|--------|------|
| `LimitRange` | Objet individuel (container / pod / PVC) | Définit des **defaults**, minima, maxima, ratio requests/limits |
| `ResourceQuota` | Namespace entier | Fixe un **plafond global** : nombre de pods, CPU/mémoire agrégés, PVC, etc. |

#### Pourquoi c'est utile dans kube-train

- Sur GKE Autopilot, les `requests`/`limits` explicites sont déjà une très bonne pratique.
- En TP, `LimitRange` évite qu'un nouveau conteneur soit déployé sans ressources.
- `ResourceQuota` protège le namespace contre un scale accidentel (`replicas: 10`) ou un run mal dimensionné.

#### Exemple namespace `kube-train`

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
```

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: kube-train-quota
  namespace: kube-train
spec:
  hard:
    pods: "2"
    limits.memory: 1Gi
    requests.cpu: 500m
```

**Effet attendu** :
- si un manifest oublie les ressources, `LimitRange` injecte des valeurs par défaut ;
- si on essaie de passer de 1 à 3 pods API dans un namespace déjà occupé, `ResourceQuota` bloque.

**Piège entretien** : `LimitRange` ne remplace **pas** `ResourceQuota`. L'un définit des contraintes **par objet**, l'autre plafonne le **total namespace**.

---

### 6) Init containers

Un **init container** s'exécute **avant** les conteneurs applicatifs, dans l'ordre déclaré. Si un init container échoue, le Pod ne passe pas à l'étape suivante.

#### Pattern mental

```text
init #1 OK → init #2 OK → container principal démarre
init #1 KO → restart de l'init → le container principal n'existe pas encore
```

#### Cas d'usage typiques

1. **Attendre une dépendance** : PostgreSQL, broker, service HTTP, DNS.
2. **Préparer le filesystem** : créer un répertoire, copier un certificat, générer un fichier de config.
3. **Lancer une migration** : exécuter Flyway/Liquibase avant de démarrer l'app.

#### Exemple YAML — attente PostgreSQL puis migration Flyway

```yaml
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
    - name: flyway-migrate
      image: flyway/flyway:10
      args:
        - -url=jdbc:postgresql://postgres-service:5432/kube_train
        - -user=kube_train_user
        - -password=kube_train_pass
        - migrate
      volumeMounts:
        - name: flyway-sql
          mountPath: /flyway/sql
  volumes:
    - name: flyway-sql
      configMap:
        name: flyway-migrations
```

#### Lecture kube-train

- **Minikube/local** : attendre `postgres-service` via init container est pertinent.
- **GKE + Cloud SQL Proxy sidecar** : attention, un init container **ne peut pas attendre un sidecar du même pod**, car les conteneurs applicatifs démarrent **après** la fin des init containers. Attendre `127.0.0.1:5432` dans ce cas crée un deadlock.

**Règle d'or** : un init container doit être **idempotent**, rapide et deterministic.

---

### 7) Points clés entretien J1

| Question | Réponse courte attendue |
|----------|-------------------------|
| **Quelle différence entre PSS `baseline` et `restricted` ?** | `baseline` bloque les escalades évidentes ; `restricted` impose le hardening moderne (`runAsNonRoot`, `seccomp`, `allowPrivilegeEscalation: false`, `drop ALL`). |
| **Pourquoi éviter le ServiceAccount `default` ?** | Parce qu'il mutualise l'identité, brouille l'audit et pousse au sur-privilege. On préfère 1 SA dédié par workload. |
| **Role vs ClusterRole ?** | `Role` = namespace ; `ClusterRole` = cluster-wide ou règles réutilisables. |
| **RoleBinding vs ClusterRoleBinding ?** | `RoleBinding` accorde des droits dans un namespace ; `ClusterRoleBinding` accorde des droits à l'échelle cluster. |
| **Pourquoi `readOnlyRootFilesystem: true` peut casser Spring Boot ?** | Parce que l'application ou Tomcat écrit souvent dans `/tmp`. Il faut prévoir un volume writable, typiquement `emptyDir` monté sur `/tmp`. |
| **Init container vs sidecar ?** | L'init container finit avant le démarrage de l'app ; le sidecar tourne en parallèle pendant la vie du pod. |

#### Mini-checklist CKAD J1

- Savoir lire et poser les labels PSS sur un namespace.
- Savoir écrire un `securityContext` compatible `restricted`.
- Savoir expliquer pourquoi `RoleBinding` est namespace-scoped.
- Savoir créer un ServiceAccount dédié + le référencer dans un `Deployment`.
- Savoir distinguer `LimitRange` et `ResourceQuota`.
- Savoir reconnaître quand un init container est adapté… et quand il ne l'est pas.

