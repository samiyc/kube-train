# Notes J1 — Sécurité Kubernetes & RBAC

> Concepts clés, pièges rencontrés, et points d'entretien.
> *(fusion des notes de formation + cheat-sheet sécurité)*

---

## Glossaire — Acronymes J1

| Acronyme | Signification | Contexte |
|---|---|---|
| K8s | Kubernetes | Plateforme d'orchestration de containers |
| GKE | Google Kubernetes Engine | Service Kubernetes managé sur Google Cloud Platform |
| PSS | Pod Security Standards | Trois profils de sécurité pod : Privileged / Baseline / Restricted |
| PSP | PodSecurityPolicy | Ancêtre des PSS, déprécié depuis K8s 1.25 |
| PSA | Pod Security Admission | Admission controller qui enforces les PSS sur les namespaces |
| RBAC | Role-Based Access Control | Contrôle d'accès : qui peut faire quoi sur quelles ressources K8s |
| SA | ServiceAccount | Identité K8s d'un pod pour s'authentifier auprès de l'API server |
| KSA | Kubernetes ServiceAccount | Synonyme de SA, utilisé pour distinguer de GSA (Google Service Account) |
| GSA | Google Service Account | Identité GCP (IAM) pour accéder aux services Google Cloud |
| JWT | JSON Web Token | Token signé contenant des claims — monté dans chaque pod par K8s |
| GID | Group ID | Identifiant Unix du groupe (`fsGroup`, `runAsGroup`) |
| UID | User ID | Identifiant Unix de l'utilisateur (`runAsUser`) |
| OOM | Out of Memory | Pod tué par le kernel car dépassement de `limits.memory` → STATUS `OOMKilled` |
| HPA | Horizontal Pod Autoscaler | Composant K8s qui ajuste le nombre de réplicas automatiquement |
| PVC | Persistent Volume Claim | Demande de volume persistant (par opposition à `emptyDir` éphémère) |
| RS | ReplicaSet | Objet K8s qui assure un nombre de replicas de pods (créé par Deployment) |
| IPC | Inter-Process Communication | Mécanisme Linux de communication inter-processus (`hostIPC` = risque sécurité) |
| JVM | Java Virtual Machine | Environnement d'exécution Java — a besoin d'écrire dans `/tmp` |
| OTel | OpenTelemetry | Framework d'observabilité (traces, métriques, logs) — standard CNCF |
| E2E | End-to-End | Test de bout en bout — valide le flux complet de l'application |

---

### 0) Architecture multi-couche K8s — où se situe le Namespace

```
CLUSTER (1 plan de contrôle)
  └── NODE (VM — unité de calcul)
        └── POD (unité de scheduling — 1 IP, volumes partagés)
              └── CONTAINER (processus isolé — 1 image Docker)

NAMESPACE (dimension logique, orthogonale aux nodes)
  ├── Un namespace peut avoir des pods sur n'importe quel node
  ├── Deux namespaces différents peuvent cohabiter sur le même node
  └── Un pod ne peut appartenir qu'à un seul namespace
```

#### Ce qu'un namespace isole — et ce qu'il N'isole PAS

| Dimension | Namespace isole ? | Détail |
|---|---|---|
| Noms de ressources | ✅ | Un Deployment `api` peut exister dans `default` et `staging` |
| RBAC | ✅ | Un Role/RoleBinding est namespace-scoped |
| Quotas / LimitRange | ✅ | ResourceQuota et LimitRange s'appliquent au namespace |
| Secrets / ConfigMaps | ✅ | Un pod ne lit pas les Secrets d'un autre namespace |
| Réseau | ❌ (par défaut) | Les pods communiquent inter-namespace sauf NetworkPolicy |
| Nœuds physiques | ❌ | Pods de namespaces différents cohabitent sur le même node |
| Syscalls / kernel | ❌ | Nécessite securityContext + seccompProfile |

**Analogie** : le namespace est un "dossier virtuel" dans le cluster — il organise et contrôle les accès, mais ne crée pas de barrière réseau ou physique.

---

### 1) Pod Security Standards (PSS)

Les **Pod Security Standards** remplacent l'ancien PodSecurityPolicy (PSP) par 3 profils simples et cumulatifs. Ils s'appliquent au **niveau namespace** via des labels et sont contrôlés par l'admission controller **Pod Security Admission**.

#### Les 3 niveaux

| Niveau | Intention | Ce que ça bloque | Usage kube-train |
|---|---|---|---|
| **Privileged** | Quasi sans garde-fou | Rien | Réservé composants infra très spécifiques |
| **Baseline** | Bloquer les escalades évidentes | `privileged: true`, `hostNetwork`, `hostPID`, `hostIPC`, `hostPath`, `hostPort`, capabilities dangereuses | Bon premier filet pour un namespace applicatif |
| **Restricted** | Hardening moderne | Tout baseline **+** exige `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `seccompProfile: RuntimeDefault`, `capabilities.drop: [ALL]`, volumes limités | Cible CKAD / prod pour kube-train-api |

**Mémo entretien** : `baseline` = "ne fais pas de bêtises" ; `restricted` = "hardening moderne complet".

#### Les 3 modes

| Mode | Effet sur un pod non conforme | Où l'information apparaît |
|---|---|---|
| **enforce** | Pod **refusé** (admission bloquée) | Erreur dans `kubectl apply` |
| **warn** | Pod créé, warning affiché | Terminal du développeur (`kubectl apply` affiche ⚠️) |
| **audit** | Pod créé, annotation ajoutée | Journal d'audit K8s (`/var/log/kubernetes/audit.log`) |

**Distinction warn vs audit** — ce ne sont PAS le même mécanisme "plus ou moins approfondi" :
- `warn` → visible immédiatement par le développeur qui fait `kubectl apply`
- `audit` → visible par l'équipe sécurité en post-mortem dans les audit logs

#### Configuration kube-train (namespace-pss.yaml)

```yaml
labels:
  pod-security.kubernetes.io/enforce: baseline      # bloque si violation baseline
  pod-security.kubernetes.io/enforce-version: latest
  pod-security.kubernetes.io/audit: restricted       # log si violation restricted
  pod-security.kubernetes.io/audit-version: latest
  pod-security.kubernetes.io/warn: restricted        # warning si violation restricted
  pod-security.kubernetes.io/warn-version: latest
```

**Pourquoi `enforce=baseline` et pas `restricted`** : plusieurs pods dans le namespace `default` ne sont pas encore compatibles PSS restricted (postgres sans seccompProfile, node-exporter avec hostPID). Enforcer restricted les bloquerait. La stratégie : enforcer baseline maintenant (bloque le pire), utiliser warn/audit=restricted pour tracer ce qui reste à corriger avant de monter enforce à restricted. C'est une migration progressive contrainte par l'existant.

**À retenir :**
- `enforce` agit au moment de la création réelle du Pod.
- `warn` et `audit` sont utiles pour tester un `Deployment` avant de casser un rollout.
- Les PSS **n'ajoutent rien** automatiquement : ils **rejettent** ou **signalent** un manifest non conforme.

---

### 2) `securityContext` — référence complète

Le `securityContext` définit **comment** un Pod/conteneur s'exécute à runtime. Les PSS vérifient plusieurs de ces champs.

#### Répartition pod-level vs container-level

| Champ | Niveau | Pourquoi ce niveau |
|---|---|---|
| `runAsNonRoot` | Pod ou container | Override possible par container |
| `runAsUser` | Pod ou container | Override possible par container |
| `runAsGroup` | Pod ou container | Override possible par container |
| `fsGroup` | **Pod uniquement** | S'applique aux volumes montés — les volumes sont pod-scoped |
| `seccompProfile` | Pod ou container | Override possible par container |
| `allowPrivilegeEscalation` | **Container uniquement** | Propriété du processus du container |
| `readOnlyRootFilesystem` | **Container uniquement** | Propriété du filesystem du container |
| `capabilities` | **Container uniquement** | Liées au processus, pas au pod |

**Pourquoi `fsGroup` est uniquement pod-level** : `fsGroup` configure le GID appliqué aux volumes montés. Un volume (`emptyDir`, PVC…) est déclaré dans `spec.volumes[]` — c'est un objet pod-level, partageable entre plusieurs containers. Si chaque container définissait son propre fsGroup, K8s ne saurait pas quel GID appliquer au volume partagé.

**Règle mnémotechnique** : tout ce qui concerne un volume → pod-level. Tout ce qui concerne le processus du container → container-level.

#### Exemple YAML — Pod `kube-train-api`

```yaml
spec:
  securityContext:               # pod-level
    runAsNonRoot: true
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
    seccompProfile:
      type: RuntimeDefault
  volumes:
    - name: tmp
      emptyDir: {}
  containers:
    - name: api-container
      securityContext:           # container-level
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

#### Point clé kube-train : le piège `/tmp`

Avec `readOnlyRootFilesystem: true`, le filesystem du container (toutes les couches Docker) est en lecture seule, y compris `/tmp`. Spring Boot/Tomcat a besoin d'écrire dans `/tmp` pour : classes extracted du fat JAR, fichiers temporaires Tomcat, sockets Unix OTel.

**Solution** : monter un `emptyDir` sur `/tmp` + `JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/tmp`.

**Pourquoi `emptyDir` et pas `hostPath`** : `hostPath` expose le filesystem du node au container (élévation de privilège, fuite inter-pods) et est interdit par PSS baseline. `emptyDir` est créé vide, isolé, éphémère — parfait pour des fichiers temporaires JVM.

---

### 3) ServiceAccounts

Un **ServiceAccount** représente l'identité Kubernetes d'un Pod lorsqu'il parle à l'API server ou, sur GKE, lorsqu'il est relié à une identité GCP via **Workload Identity**.

#### Stratégie de découpage — 1 SA par workload

```
Namespace default
  ├── SA: kube-train-api-sa    → API Spring Boot uniquement
  ├── SA: notification-sa      → Service notification uniquement
  └── SA: default              → ne jamais utiliser pour des workloads applicatifs
```

| Quoi | SA dédié ? | Pourquoi |
|---|---|---|
| Service applicatif (API, notif…) | ✅ 1 SA par service | Isolation RBAC, audit distinct |
| Base de données (Postgres) | ✅ SA dédié | N'accède pas aux secrets applicatifs |
| Batch / CronJob | ✅ SA dédié | Permissions temporelles différentes |
| Login DB (user/password) | ❌ Pas via SA | Via Secret + envFrom — ce n'est pas du RBAC K8s |

**Pourquoi éviter `default`** : identité partagée entre plusieurs workloads — si un workload est compromis, l'attaquant utilise le token SA monté dans le pod pour appeler l'API K8s avec les droits de tous les workloads qui partagent ce SA.

#### `automountServiceAccountToken: false`

Par défaut, K8s monte un JWT token dans chaque pod (`/var/run/secrets/kubernetes.io/serviceaccount/token`). Ce token permet au pod de s'authentifier auprès de l'API K8s.

**Désactiver si** : le pod n'a pas besoin d'appeler l'API K8s directement. Sur GKE, l'auth aux APIs GCP passe par le metadata server (Workload Identity), pas par ce token.

**Impact sécurité** : si le container est compromis, l'attaquant ne peut pas utiliser ce token pour lister/modifier des ressources K8s (pods, secrets, configmaps).

#### GKE : SA + Workload Identity

```yaml
# k8s/security/rbac-gke.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: kube-train-api-sa
  annotations:
    iam.gke.io/gcp-service-account: kube-train-sa@kube-train-project.iam.gserviceaccount.com
```

- **annotation Workload Identity** = lien entre SA Kubernetes et SA GCP
- **token K8s monté dans le pod** = utile seulement si l'application appelle réellement l'API Kubernetes

---

### 4) RBAC

Le **RBAC** (Role-Based Access Control) décrit **qui** peut faire **quoi** sur **quelle ressource**.

#### Les 4 objets et leur portée

| Objet | Portée | Contient |
|---|---|---|
| `Role` | Namespace | Règles d'accès (resources + verbs) |
| `ClusterRole` | Cluster | Règles d'accès, réutilisables dans plusieurs namespaces |
| `RoleBinding` | **Namespace** | Lie un SA/User à un Role ou ClusterRole |
| `ClusterRoleBinding` | Cluster | Lie un SA/User à un ClusterRole à l'échelle cluster |

> **Piège classique CKAD** : un `RoleBinding` qui référence un `ClusterRole` donne des droits **uniquement dans le namespace du RoleBinding** — pas dans tout le cluster.  
> **C'est le RoleBinding qui détermine la portée, pas le ClusterRole référencé.**

#### Verbes — ce qu'ils autorisent et leurs risques

| Verbe | Action | Risque sur secrets |
|---|---|---|
| `get` | Lire 1 ressource par nom | Faible — 1 secret connu |
| `list` | Lister toutes les ressources du type + **leurs valeurs** | ⚠️ Élevé — exfiltre tous les secrets d'un coup |
| `watch` | Flux temps réel des changements | ⚠️ Élevé — stream permanent de tous les secrets |
| `create` / `update` / `delete` | Modifier | Rarement nécessaire côté app |

**`list` sur les secrets** retourne les **valeurs** de tous les secrets du namespace, pas seulement leurs noms. C'est une exfiltration de données complète en une seule requête.

#### Exemple kube-train (k8s/security/rbac.yaml)

```yaml
kind: Role
rules:
- apiGroups: [""]
  resources: ["secrets"]
  resourceNames: ["kube-train-secrets"]   # accès à CE secret uniquement
  verbs: ["get"]                          # get seulement
---
kind: RoleBinding
subjects:
- kind: ServiceAccount
  name: kube-train-api-sa
roleRef:
  kind: Role
  name: kube-train-api-secret-reader
```

`resourceNames` = garde-fou ultime. Même si un autre secret est créé demain dans le namespace, `kube-train-api-sa` ne pourra pas le lire.

#### Vérification rapide

```bash
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa   # → yes

kubectl auth can-i get secret/autre-secret \
  --as=system:serviceaccount:default:kube-train-api-sa   # → no

kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa   # → no
```

---

### 5) LimitRange & ResourceQuota

Ces deux objets sont complémentaires mais n'agissent pas au même niveau.

| Objet | Niveau | Rôle |
|---|---|---|
| `LimitRange` | Container individuel | Injecte des defaults CPU/RAM, pose min/max par container |
| `ResourceQuota` | Namespace entier | Plafonne la consommation totale (pods, CPU, RAM) |

#### LimitRange — les 4 champs à connaître

```yaml
spec:
  limits:
  - type: Container
    defaultRequest:    # injecté dans resources.requests si absent du manifest
      cpu: 100m
      memory: 256Mi
    default:           # injecté dans resources.limits si absent du manifest
      cpu: 500m
      memory: 512Mi
    min:               # plancher — refus si resources.requests < min
      cpu: 50m
      memory: 128Mi
    max:               # plafond — refus si resources.limits > max
      cpu: "1"
      memory: 768Mi
```

**`defaultRequest` vs `default`** : `defaultRequest` = valeur injectée dans `resources.requests`. `default` = valeur injectée dans `resources.limits`. Un container sans `resources:` reçoit les deux. Sans LimitRange, un container sans `resources:` dans un namespace avec ResourceQuota active est rejeté (quota ne peut pas calculer).

#### ResourceQuota — comportement si dépassée

Si la quota est atteinte et qu'un nouveau pod est demandé :
- L'**admission controller** rejette la création (avant même que le pod existe)
- **`kubectl get pods` ne montre aucun nouveau pod** — le pod n'est jamais persisté en etcd
- L'erreur est visible dans les events :

```bash
kubectl get events --sort-by=.lastTimestamp | grep -i forbidden
# → Error creating: pods "..." is forbidden: exceeded quota: kube-train-quota,
#   requested: pods=1, used: pods=6, limited: pods=6

kubectl describe replicaset <rs-name>
# → Events: Warning FailedCreate (répété toutes ~10s)
```

**À ne pas confondre** : `CrashLoopBackOff` = container qui tourne et crashe. Quota dépassée = pod jamais créé (invisible dans `kubectl get pods`).

#### Calcul pour le rolling update

```
Peak pods = replicas_cible + maxSurge + pods_autres_workloads
           (maxUnavailable ne crée PAS de pods — il permet d'en supprimer)
```

**Retour d'expérience J1** : `pods: "4"` a bloqué le rolling update car le namespace `default` contenait déjà la stack monitoring F3 (~9 pods). Quota portée à `pods: "6"`. En prod : chaque équipe a son namespace dédié, le monitoring est dans `monitoring`.

#### Sizing `requests.cpu`

- `requests.cpu` = **réservation de scheduling** (pas un cap d'exécution — c'est `limits.cpu` qui throttle)
- Quota trop serrée → pod non schedulé même si les nodes ont de la CPU disponible
- **Règle pratique** : `requests.cpu` quota ≥ `replicas_max × cpu_request × 1.5` (le ×1.5 couvre maxSurge + marge HPA)

---

### 6) Init containers & Sidecars

#### Init containers

Un **init container** s'exécute **avant** les containers applicatifs, dans l'ordre déclaré. Si un init container échoue, le Pod ne passe pas à l'étape suivante.

```text
init #1 OK → init #2 OK → containers applicatifs démarrent
init #1 KO → restart de l'init → containers applicatifs n'existent pas encore
```

**Cas d'usage typiques** :
1. Attendre une dépendance (PostgreSQL, broker, DNS)
2. Préparer le filesystem (certificat, fichier de config)
3. Lancer une migration (Flyway/Liquibase avant l'app)

**securityContext s'applique aussi aux init containers** — les soumettre aux mêmes contraintes PSS.

#### Sidecar (container auxiliaire)

Un **sidecar** est un container ordinaire dans `spec.containers[]`, qui s'exécute **en parallèle** du container applicatif principal pendant toute la vie du pod.

```
Pod
  ├── containers[0]: api-container       (container principal — Spring Boot)
  └── containers[1]: cloud-sql-proxy     (sidecar — Cloud SQL Auth Proxy)
```

Ils partagent : l'IP du pod (`localhost` entre eux), les volumes du pod, le cycle de vie.

**Exemple GKE — Cloud SQL Auth Proxy** :
```yaml
containers:
- name: api-container
  env:
  - name: SPRING_DATASOURCE_URL
    value: "jdbc:postgresql://127.0.0.1:5432/trains"   # sidecar écoute sur localhost

- name: cloud-sql-proxy                                 # sidecar
  image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:2
  args: ["--structured-logs", "kube-train-project:europe-west1:kube-train-db"]
  securityContext:
    runAsNonRoot: true
    allowPrivilegeEscalation: false
  resources:
    requests: { memory: "32Mi", cpu: "10m" }
```

#### Deadlock init container / sidecar

**Règle K8s** : tous les `initContainers` doivent se terminer avec succès **avant** que K8s démarre le premier container de `spec.containers[]`.

Si un init container fait `until nc -z 127.0.0.1 5432` ET que le Cloud SQL Proxy est un sidecar :

```
Init container démarre → attend localhost:5432
Cloud SQL Proxy (sidecar) → attend que les init containers finissent
→ DEADLOCK : chacun attend l'autre indéfiniment → pod bloqué en Init:0/1 pour toujours
```

**Solution kube-train (Minikube)** : l'init container attend `postgres-service:5432` (Service K8s externe au pod), pas localhost.

**Solution K8s 1.28+ — sidecar natif** : ajouter `restartPolicy: Always` à un initContainer le transforme en "sidecar natif". Il démarre **avant** les init containers classiques et reste actif. Cela résout le deadlock.

```yaml
initContainers:
- name: cloud-sql-proxy
  restartPolicy: Always    # sidecar natif — démarre en premier, reste actif
  image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:2
```

---

### 7) Glossaire des STATUS `kubectl get pods`

> Section à réviser avant examen CKAD. Les STATUS sont les valeurs réelles de la colonne `STATUS` dans `kubectl get pods`.

#### Cycle de vie normal

| STATUS | READY | Signification |
|---|---|---|
| `Pending` | `0/1` | Pod schedulé mais pas encore sur un node (en attente du scheduler) |
| `Init:0/1` | `0/1` | Init container n°1 en cours (pas encore réussi) |
| `Init:1/2` | `0/1` | 1er init container OK, 2ème en cours |
| `PodInitializing` | `0/1` | Tous les init containers OK, containers applicatifs en démarrage |
| `Running` | `0/1` | Container lancé, en attente que la readinessProbe soit OK |
| `Running` | `1/1` | Container actif et ready (reçoit du trafic) |
| `Completed` | `0/1` | Container terminé avec exit code 0 (Jobs/init containers) |
| `Terminating` | `1/1` → `0/1` | Pod en cours de suppression (grace period) |

#### Erreurs à connaître

| STATUS | Cause typique | Diagnostic |
|---|---|---|
| `Pending` (bloqué) | Aucun node ne peut le scheduler (insuffisance de resources, taints, quota) | `kubectl describe pod` → Events: FailedScheduling |
| `Init:CrashLoopBackOff` | L'init container crashe en boucle | `kubectl logs <pod> -c <init-container>` |
| `Init:Error` | L'init container s'est terminé avec un exit code non-zero | `kubectl logs <pod> -c <init-container> --previous` |
| `ImagePullBackOff` | Image introuvable ou accès refusé au registry | `kubectl describe pod` → Events: Failed to pull image |
| `ErrImagePull` | Même chose, premier essai avant le backoff | `kubectl describe pod` → Events |
| `CrashLoopBackOff` | Container applicatif crashe et redémarre en boucle | `kubectl logs <pod> --previous` |
| `Error` | Container terminé avec exit code non-zero (avant le backoff) | `kubectl logs <pod>` |
| `OOMKilled` | Container tué car dépassement de `limits.memory` | `kubectl describe pod` → Last State: OOMKilled |
| `Failed` | Pod terminé définitivement (Job avec restartPolicy: Never) | `kubectl describe pod` → State: Terminated |

#### Cas spéciaux

| STATUS | Contexte |
|---|---|
| `ContainerCreating` | Image en cours de pull ou volume en cours de montage |
| `Evicted` | Pod expulsé par le kubelet (node sous pression mémoire/disk) |
| `NodeLost` | Node unreachable depuis > 5 min (pod marqué inconnu) |
| `Unknown` | État du pod inconnu (perte de communication avec le node) |

#### Exemples concrets (scénarios F4-J1)

```
# Postgres down — init container en boucle
NAME                                    READY   STATUS       RESTARTS
kube-train-deployment-abc-xyz           0/1     Init:0/1     0

# Image OTel corrompue — Spring Boot crashe
NAME                                    READY   STATUS             RESTARTS
kube-train-deployment-abc-xyz           0/1     CrashLoopBackOff   5

# ResourceQuota dépassée — nouveau pod jamais créé
NAME                                    READY   STATUS    RESTARTS
kube-train-deployment-abc-old1          1/1     Running   0        # anciens pods toujours là
kube-train-deployment-abc-old2          1/1     Running   0
# → pas de nouveau pod visible (rejeté à l'admission)
# → voir: kubectl get events | grep -i forbidden

# Rolling update bloqué (nouveau pod crashe)
NAME                                    READY   STATUS             RESTARTS
kube-train-deployment-abc-old1          1/1     Running            0
kube-train-deployment-abc-old2          1/1     Running            0
kube-train-deployment-def-new1          0/1     CrashLoopBackOff   3
```

**Mémo CKAD** :
- `Pending` = problème de **scheduling** (avant que le container démarre)
- `Init:0/1` = problème dans les **init containers** (pendant le démarrage)
- `CrashLoopBackOff` = problème dans le **container applicatif** (après démarrage)
- `Failed` = uniquement pour les **Jobs** avec restartPolicy: Never

---

### 8) Points clés entretien J1

| Question | Réponse courte attendue |
|---|---|
| **Quelle différence entre PSS `baseline` et `restricted` ?** | `baseline` bloque les escalades évidentes ; `restricted` impose le hardening moderne (`runAsNonRoot`, `seccomp`, `allowPrivilegeEscalation: false`, `drop ALL`). |
| **Pourquoi éviter le ServiceAccount `default` ?** | Parce qu'il mutualise l'identité, brouille l'audit et pousse au sur-privilege. On préfère 1 SA dédié par workload. |
| **Pourquoi `automountServiceAccountToken: false` ?** | Le pod n'appelle pas l'API K8s — le token exposé augmente la surface d'attaque sans utilité. |
| **Role vs ClusterRole ?** | `Role` = namespace ; `ClusterRole` = cluster-wide ou règles réutilisables dans plusieurs namespaces. |
| **RoleBinding vs ClusterRoleBinding ?** | `RoleBinding` accorde des droits dans **un** namespace (même s'il référence un ClusterRole) ; `ClusterRoleBinding` accorde des droits cluster entier. |
| **fsGroup : pourquoi uniquement pod-level ?** | fsGroup s'applique aux volumes — les volumes sont pod-scoped, partagés entre containers. Container-level n'a pas de sens. |
| **Pourquoi `readOnlyRootFilesystem: true` peut casser Spring Boot ?** | Spring Boot écrit dans `/tmp`. Il faut monter un `emptyDir` sur `/tmp` + `JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/tmp`. |
| **Différence init container vs sidecar ?** | L'init container finit AVANT que les containers applicatifs démarrent ; le sidecar tourne EN PARALLÈLE pendant la vie du pod. |
| **Deadlock init container / sidecar ?** | Un init container qui attend localhost:<port> alors que ce service est un sidecar du même pod → deadlock. Le sidecar ne démarre qu'après les init containers. |
| **Pod STATUS quand ResourceQuota dépassée ?** | Aucun nouveau pod visible — création rejetée à l'admission. Erreur dans `kubectl get events` et `kubectl describe rs`. |

#### Mini-checklist CKAD J1

- [ ] Lire et poser les labels PSS sur un namespace
- [ ] Écrire un `securityContext` compatible `restricted` (pod + container level complet)
- [ ] Créer un ServiceAccount dédié + le référencer dans un `Deployment`
- [ ] Écrire un Role avec `resourceNames` + RoleBinding
- [ ] Distinguer `LimitRange` et `ResourceQuota` (scope + rôle)
- [ ] Reconnaître quand un init container est adapté… et quand il ne l'est pas (deadlock)
- [ ] Lire et interpréter un STATUS `kubectl get pods` (Init:0/1, CrashLoopBackOff, etc.)

---

### 9) Fichiers YAML de sécurité — quoi regarder en premier

Pour auditer la sécurité d'un cluster kube-train, ordre de lecture recommandé :

| Fichier | Ce qu'il contrôle | Points à vérifier |
|---|---|---|
| `k8s/security/namespace-pss.yaml` | Première ligne de défense (admission) | enforce ≥ baseline ? audit/warn = restricted ? |
| `k8s/security/rbac.yaml` / `k8s/security/rbac-gke.yaml` | Identités et droits | SA dédiés ? `resourceNames` présent ? `automountServiceAccountToken: false` ? |
| `k8s/workloads/deployment.yaml` / `k8s/workloads/deployment-gke.yaml` | Hardening runtime | securityContext complet ? SA dédié référencé ? resources définis ? |
| `k8s/security/quota.yaml` | Gouvernance des ressources | `pods` quota compatible avec rolling update ? `requests.cpu` laisse de la marge HPA ? |

**Checklist sécurité Deployment** :
```yaml
spec:
  template:
    spec:
      serviceAccountName: kube-train-api-sa    # SA dédié (pas default)
      automountServiceAccountToken: false       # token non monté si inutile
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
        resources:                              # toujours présent
          requests: { memory: "256Mi", cpu: "200m" }
          limits:   { memory: "512Mi", cpu: "500m" }
```

---

### 10) Pièges rencontrés en validation E2E Minikube (F4-J1)

Ces points ne sont pas dans les théories standards mais se rencontrent dès la première mise en pratique.

#### Piège 1 — ResourceQuota dans un namespace partagé

**Symptôme** : `ReplicaFailure: FailedCreate` + `ProgressDeadlineExceeded`. Le nouveau pod n'est jamais créé.

**Cause** : `quota.yaml` dimensionné pour un namespace propre (postgres + 2 API = 4 pods). En Minikube, le namespace `default` contenait la stack monitoring F3 (~9 pods existants).

**Règle** : ResourceQuota est vérifiée à l'admission de chaque nouveau pod. Les pods existants au moment de la création du quota ne sont pas expulsés, mais tout nouveau pod est bloqué si la limite est atteinte.

**Fix** : compter tous les pods du namespace + laisser une marge pour le rolling update. Valeur retenue : `pods: "6"`.

**Leçon production** : en prod, chaque équipe a son namespace dédié. La collision vient de l'usage du namespace `default` pour tout.

#### Piège 2 — Rolling update bloqué : pourquoi les anciens pods ne sont pas supprimés

**Symptôme** : 3 pods kube-train visibles — 2 anciens Running + 1 nouveau CrashLoopBackOff — qui durent indéfiniment.

**Cause** : Kubernetes ne supprime les anciens pods qu'une fois les nouveaux `Ready`. Si le nouveau pod est en CrashLoopBackOff (jamais Ready), le rolling update est bloqué et les anciens sont conservés pour maintenir le service.

```
Ancien RS (2 pods Running) ──┐
                             ├── service continue de répondre
Nouveau RS (1 pod crashé) ───┘
```

**Fix** : corriger la cause racine du crash (image corrompue, config manquante) puis `kubectl apply` pour créer un nouveau ReplicaSet.

#### Piège 3 — Docker `ADD https://` et le cache avec `--no-cache`

**Symptôme** : après `docker build --no-cache`, l'image contient toujours un fichier OTel corrompu (0 octet).

**Cause** : Docker/BuildKit cache les layers `ADD https://` basé sur les headers HTTP (ETag/Last-Modified). Même avec `--no-cache`, si le serveur renvoie le même ETag, BuildKit peut réutiliser la layer.

**Fix** : utiliser un `ARG CACHEBUST=$(date +%s)` avant le `ADD`, ou télécharger en dehors du build et utiliser `COPY`.

#### Piège 4 — Override ENTRYPOINT/CMD par environnement

**Contexte** : le Dockerfile charge l'agent OTel via ENTRYPOINT. En Minikube, pas de collector OTel — l'agent génère du bruit.

**Solution** : surcharger dans `deployment.yaml` sans reconstruire l'image :

```yaml
containers:
  - name: api-container
    command: ["java"]
    args: ["-jar", "app.jar"]
```

`deployment-gke.yaml` conserve l'ENTRYPOINT original.

**À retenir** : `command` dans K8s = override de `ENTRYPOINT` Docker. `args` = override de `CMD` Docker.

---

### Schéma récapitulatif — Qui contrôle quoi

```
CLUSTER
  ├── PSS (namespace labels)          → ce qui est AUTORISÉ À ENTRER dans le namespace
  │     enforce/warn/audit × privileged/baseline/restricted
  │
  ├── RBAC (Role + RoleBinding)       → QUI peut faire QUOI sur QUELLES ressources K8s
  │     SA → Role (verbs: get + resourceNames)
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
                    └── sidecar = container dans containers[], parallèle au main
```
