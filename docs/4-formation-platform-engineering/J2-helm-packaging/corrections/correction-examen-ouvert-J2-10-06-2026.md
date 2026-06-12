# Correction Examen Ouvert J2 — Helm & Packaging Kubernetes
> Date : 10/06/2026 | Score : **7 / 10**

---

## Question 1 — Anatomie d'un chart ⭐ → **0.7 / 1**

### a) Fichiers obligatoires ✅ Correct

`Chart.yaml` + `templates/` sont bien les deux seuls éléments requis pour `helm lint`.
Tous les autres sont optionnels :

| Fichier/dossier | Rôle |
|---|---|
| `values.yaml` | Valeurs par défaut du chart — optionnel mais recommandé |
| `_helpers.tpl` | Fonctions Go réutilisables — optionnel |
| `.helmignore` | Exclusions du packaging — optionnel |
| `charts/` | Dépendances (sous-charts) — optionnel |
| `NOTES.txt` | Texte affiché après install — optionnel (bonus) |
| `values-*.yaml` | Overlays par environnement — hors chart, utilisés via `-f` |

### b) `version` vs `appVersion` ✅ Correct sur le concept — exemple manquant

La distinction est correcte :
- `version` : version du **chart** (suit les changements de templates, values, structure)
- `appVersion` : version de l'**application packagée** (notre Spring Boot 4.0.0)

**Exemple concret de désalignement** (non fourni dans la réponse) :

On fixe un bug dans `templates/deployment.yaml` (ajout d'un label) sans toucher à l'application :
```yaml
version: 0.2.0      # chart mis à jour (bug fix template)
appVersion: "4.0.0" # application inchangée
```
C'est normal : `helm history` traque la version du chart, pas celle de l'app.

### c) `.helmignore` ⚠️ Concept correct, exemples insuffisants

"Similaire à `.gitignore`" ✅ — mais la question demandait deux exemples concrets avec justification.

**Deux exemples pertinents** :
1. `README.md` — documentation lisible par les humains mais inutile dans le package `.tgz` distribué
2. `values-minikube.yaml` / `values-gke.yaml` — les overlays d'env ne font pas partie du chart lui-même, on ne veut pas les embarquer dans `helm package` pour un repo public (ils contiennent des infos d'infrastructure)

---

## Question 2 — `fullnameOverride` ⭐ → **0.7 / 1**

### a) Nom sans override ✅ Correct

`kube-train-kube-train-chart` — règle : `{release-name}-{chart-name}` sauf si le release name contient déjà le chart name (dans ce cas Helm utilise juste le release name).

### b) Limite DNS ✅ Correct

27 caractères < 63 → pas de problème technique ici. La limite devient critique pour des noms longs, exemple : `my-production-environment-kube-train-chart` = 44 chars — encore OK mais risqué.

### c) Quand ne PAS utiliser `fullnameOverride` ⚠️ Raisonnement incorrect

La réponse proposée ("pour interagir avec les pods via des jobs") n'est pas juste — les noms de pods sont toujours uniques par suffixe aléatoire, indépendamment du nom du Deployment.

**Vrais cas où on laisse Helm construire le nom dynamiquement :**

1. **Multi-instance dans le même namespace** : on déploie le même chart deux fois avec des releases différentes (ex: `blue` et `green` pour un blue-green deployment) → les ressources doivent avoir des noms différents, `fullnameOverride` fixe casserait tout.
2. **Chart utilisé comme dépendance** : si `kube-train-chart` est importé comme sous-chart dans un chart parent, le nom dynamique évite les collisions avec d'autres sous-charts.

```bash
# Multi-instance : IMPOSSIBLE avec fullnameOverride fixe
helm install blue ./kube-train-chart   # crée Deployment "kube-train"
helm install green ./kube-train-chart  # ERREUR : Deployment "kube-train" existe déjà
```

---

## Question 3 — Hiérarchie des values ⭐ → **0.85 / 1**

### a) Valeurs finales ✅ Parfait

| Variable | Valeur finale | Source |
|---|---|---|
| `image.tag` | `v7` | `--set` (priorité max) |
| `image.pullPolicy` | `Never` | `values-minikube.yaml` |
| `service.type` | `NodePort` | `values-minikube.yaml` |
| `config.gcpProjectId` | `""` | `values-minikube.yaml` |

### b) Deep-merge ✅ Correct

`config.otelServiceName` = `"kube-train-api"` (depuis `values.yaml`) — non surchargé par `values-minikube.yaml`.

L'explication du deep-merge est bonne. Complément : contrairement à un remplacement simple (où `-f values-minikube.yaml` remplacerait **tout** l'objet `config`), le deep-merge fusionne les objets imbriqués clé par clé.

### c) `helm get values` vs `--all` ✅ Correct avec une nuance

- `helm get values kube-train` → **USER-SUPPLIED VALUES** : uniquement les valeurs passées par `-f` et `--set`
- `helm get values kube-train --all` → **COMPUTED VALUES** : fusion complète `values.yaml` + `-f` + `--set`

**Nuance** : `helm template` utilise les **COMPUTED VALUES** (comportement de `--all`). C'est pour cela que dans le `--dry-run --debug` du TP, on voyait les valeurs par défaut (`tag: latest`, `type: ClusterIP`) alors que la release installée utilisait `values-minikube.yaml`.

---

## Question 4 — Go templating ⭐⭐ → **0.65 / 1**

### a) Décomposition de la pipeline ✅ Correct

Ordre d'exécution : `toYaml` en premier, puis `| nindent 10`.
1. `{{-` : supprime le retour à la ligne et espaces **avant** l'expression
2. `toYaml .Values.resources` : sérialise l'objet Go en chaîne YAML
3. `| nindent 10` : préfixe chaque ligne du résultat avec 10 espaces ET ajoute un saut de ligne initial

### b) Pourquoi `nindent 10` ? ⚠️ Méthode inexacte

"5 tabulations × 2 espaces" est une approximation non reproductible. La bonne méthode :

**Compter les espaces de la ligne courante dans le template et ajouter 2.**

```yaml
      containers:               # ← 6 espaces
        - name: api-container   # ← 8 espaces
          image: ...            # ← 10 espaces
          resources:            # ← 10 espaces
            {{- toYaml .Values.resources | nindent 12 }}
            # ↑ le contenu de resources (requests, limits) doit être à 12 espaces
```

`resources:` est à 10 espaces → son contenu doit être à 12 → `nindent 12`.  
Dans l'exemple de la question (`nindent 10`), cela implique que `resources:` est à 8 espaces dans le template.

**Règle** : `nindent N` où N = indentation de la clé parent + 2.

### c) Sans le `-` gauche ⚠️ Principe correct, conséquence incomplète

Sans `{{-`, Go template ajoute un **saut de ligne** avant le bloc YAML rendu :

```yaml
# Avec {{-  → correct
resources:
  requests:
    cpu: "200m"

# Sans {{- → ligne vide parasite
resources:

  requests:
    cpu: "200m"
```

K8s rejette ce YAML car la ligne vide crée une ambiguïté de parsing (`resources:` est interprété comme une valeur nulle, pas comme un mapping).

### d) `quote` indispensable ✅ Correct

Exemple de valeur qui casse sans `quote` :
```yaml
# Sans quote, avec trainMessage: "🚨 GREVE : Aucun train ne circule."
TRAIN_MESSAGE: 🚨 GREVE : Aucun train ne circule.
# ↑ Le ":" dans la chaîne est interprété comme séparateur clé:valeur → YAML invalide
```

---

## Question 5 — CronJob CKAD ⭐⭐ → **0.7 / 1**

### a) `restartPolicy` ✅ Correct

`OnFailure` et `Never`. `Always` interdit car le pod d'un Job **doit se terminer** — `Always` provoquerait une boucle infinie empêchant le Job de passer à l'état `Complete`.

### b) Position de `backoffLimit` ✅ Correct

```
CronJob
└── spec
    ├── schedule
    └── jobTemplate
        └── spec               ← backoffLimit ici
            ├── backoffLimit: 2
            └── template
                └── spec
                    └── restartPolicy
```

**Précision sur l'erreur silencieuse** : si `backoffLimit` est placé au mauvais niveau (ex: `spec.backoffLimit` du CronJob), Kubernetes **l'ignore silencieusement** (champ inconnu). Le Job utilisera alors la valeur par défaut (`backoffLimit: 6`), pas 0 comme on pourrait le croire.

### c) `activeDeadlineSeconds` vs `backoffLimit` ⚠️ Partiellement correct

La réponse laisse entendre que `backoffLimit` est conditionnel à `activeDeadlineSeconds`, ce qui est faux. Ce sont deux mécanismes **indépendants** :

| Mécanisme | Déclenche l'arrêt quand... |
|---|---|
| `backoffLimit: 2` | Le pod a échoué 2 fois (exit ≠ 0) |
| `activeDeadlineSeconds: 60` | 60 secondes se sont écoulées depuis le début du Job |

**Ordre de priorité : whichever comes first.** Les deux sont évalués en parallèle. Si le Job a déjà eu 2 échecs en 30 secondes, `backoffLimit` déclenche l'arrêt avant que la deadline ne soit atteinte — et vice versa.

### d) Décomposition du nom ✅ Excellent

`kube-train-outbox-cleanup` (nom CronJob, depuis `fullname + "-outbox-cleanup"`) + `29685015` (timestamp Unix en minutes, identifie le déclenchement) + `dgj52` (suffixe aléatoire du Pod).

Relation : `CronJob` → crée un `Job` à chaque tick → le `Job` crée un `Pod`.

---

## Question 6 — Migration kubectl → Helm ⭐⭐ → **0.75 / 1**

### a) Mécanisme d'ownership ✅ Correct

Bonne compréhension : Helm vérifie les labels/annotations `app.kubernetes.io/managed-by` et `meta.helm.sh/release-name` avant de gérer une ressource. Sans eux, il refuse pour éviter qu'un outil écrase du travail fait par un autre (kubectl, Terraform, ArgoCD).

### b) Deux solutions ⚠️ Une seule solution donnée

**Solution 1 (fournie)** : supprimer et recréer via Helm. ✅  
→ Contexte : ressource non critique, downtime acceptable, données remplaçables (ConfigMap, Service).

**Solution 2 (manquante)** : **annoter et labelliser la ressource existante** pour l'adoption par Helm.
```bash
kubectl annotate configmap kube-train-config \
  meta.helm.sh/release-name=kube-train \
  meta.helm.sh/release-namespace=default

kubectl label configmap kube-train-config \
  app.kubernetes.io/managed-by=Helm
```
→ Contexte : ressource avec état qu'on ne veut pas perdre (Secret, PVC) ou downtime impossible.

### c) Hors/dans le chart ✅ Bonne réflexion

Raisonnement correct : `postgres`, `rbac`, `quota` hors chart car :
- `postgres` → remplacé par Cloud SQL sur GKE (infra différente par env)
- `rbac` → gouvernance cluster, lifecycle distinct de l'app
- `quota` → politique namespace, géré par les ops

Bon point sur Terraform J3.

---

## Question 7 — Conditionnel Cloud SQL Proxy ⭐⭐ → **0.75 / 1**

### a) Minikube : 1 container ✅ Correct

`cloudSqlProxy.enabled: false` → le `{{- if ... }}` est `false` → bloc cloud-sql-proxy non rendu → 1 seul container (`api-container`).

### b) GKE : 2 containers ✅ Correct

`cloudSqlProxy.enabled: true` → 2 containers : `api-container` (premier dans le template) puis `cloud-sql-proxy`.

### c) Architecture différente ✅ Correct

- **GKE** : Cloud SQL Proxy sidecar écoute sur `127.0.0.1:5432` dans le même pod → l'app se connecte en localhost
- **Minikube** : postgres est un pod séparé → connexion via `postgres-service:5432` (DNS cluster K8s) → override `SPRING_DATASOURCE_URL` nécessaire

### d) Pourquoi ne pas activer le proxy en Minikube ⚠️ Une raison juste, la principale manquante

**Raison 2 ✅** (fournie) : postgres est un container Docker local, accessible directement via le service K8s — pas besoin de proxy.

**Raison 1 ✗ (principale, manquante)** : le Cloud SQL Auth Proxy **authentifie avec un compte de service GCP** (via Workload Identity ou une clé JSON). En Minikube il n'y a ni Workload Identity ni accès à GCP. Le proxy démarrerait mais échouerait immédiatement à l'authentification.

```
Error: failed to create proxy client: could not create token source: google: could not find default credentials
```

---

## Question 8 — Override Spring Boot ⭐⭐⭐ → **0.5 / 1**

### a) Nom du mécanisme ⚠️ Concept correct, nom inexact

"Configuration externalisée" est le nom du concept général (Externalized Configuration) — pas le mécanisme spécifique.

**Nom exact** : **Relaxed Binding** (liaison souple).

Règle de conversion pour les variables d'environnement :
- Majuscules → minuscules
- `_` → `.` (séparateur de propriété)
- `__` → `-` (tiret dans le nom de clé)

```
SPRING_DATASOURCE_URL  →  spring.datasource.url   ✅
OTEL_SERVICE_NAME      →  otel.service.name        ✅
```

### b) Priorité des sources Spring Boot ✗ Mauvais framework !

⚠️ **La réponse mélange la hiérarchie Helm (values.yaml, values-*.yaml, --set) avec la hiérarchie Spring Boot — ce sont deux systèmes complètement séparés.**

`values.yaml` et `values-minikube.yaml` ne sont **pas** des sources de configuration Spring Boot. Ils génèrent des `ConfigMap` Kubernetes qui deviennent des **variables d'environnement** dans le container.

**Hiérarchie réelle des sources Spring Boot** (priorité décroissante) :

| Priorité | Source | Exemple |
|---|---|---|
| 1 (plus haute) | Arguments ligne de commande | `java -jar app.jar --server.port=9090` |
| 2 | Variables d'environnement OS/container | `SPRING_DATASOURCE_URL=jdbc:...` ← notre ConfigMap |
| 3 | `application-{profile}.properties` | `application-postgres.properties` |
| 4 | `application.properties` | `application.properties` |
| 5 (plus basse) | Valeurs par défaut Spring | `server.port=8080` |

→ La ConfigMap Helm injecte des **env vars** (niveau 2), qui écrasent les `.properties` (niveau 3-4).

### c) String vide = false en Go templates ✅ Correct

`""` est falsy en Go templates → la clé `SPRING_DATASOURCE_URL` n'apparaît **pas** dans la ConfigMap GKE → Spring Boot utilise la valeur de `application-postgres.properties` (`127.0.0.1:5432`) → Cloud SQL Auth Proxy (sidecar GKE) écoute sur ce port → connexion établie.

---

## Question 9 — Analyse helm history ⭐⭐⭐ → **0.85 / 1**

### a) Enchaînement des événements ✅ Correct dans l'esprit

Bon résumé des events. Pour aller plus loin, l'enchaînement exact :

| Révision | Commande déclenchante | Contexte TP |
|---|---|---|
| 1 | `helm upgrade --install` (premier install) | Chart installé après `kubectl delete configmap` |
| 2-5 | `helm upgrade` successifs | Corrections itératives : secret, datasource URL, password |
| 6 | `helm upgrade` avec `--set cronjob.enabled=true` | CronJob activé |
| 7 | `helm upgrade --set image.tag=does-not-exist --rollback-on-failure` | Simulation d'échec volontaire |
| 8 | Automatique (rollback déclenché par `--rollback-on-failure`) | Aucune commande manuelle |

### b) Rollback vers 6 et non 7 ✅ Correct

REVISION 7 est `failed` → elle n'est **jamais devenue la version déployée**. Helm cherche la **dernière révision stable** (status `deployed` ou `superseded`) → c'est la REVISION 6.

### c) Service disponible pendant l'upgrade ✅ Correct

RollingUpdate : le pod v5 (`kube-train-78c7594497-x64wf`) restait `1/1 Running` pendant que le pod `does-not-exist` était bloqué `ErrImageNeverPull`. Le Service K8s ne route que vers les pods `Ready` → tout le trafic continuait vers le pod v5.

### d) `helm rollback kube-train 5` ✅ Correct

```
9         deployed    Rollback to 5
```
Helm crée une nouvelle révision (9) qui est une copie exacte de la révision 5. L'historique est préservé.

---

## Question 10 — Pipeline CI/CD GKE ⭐⭐⭐ → **0.7 / 1**

### a) Commande helm CI/CD ⚠️ Deux erreurs de syntaxe

```bash
# Réponse fournie (avec erreurs) :
helm upgrade --install kube-train ./kube-train-chart \
  -f ./kube-train-chart/values-gke.yaml \
  --set image.tag=$GIT_SHA \
  --atomic --timeout=5M      # ← deux problèmes ici
```

**Erreur 1** : `--atomic` est déprécié en Helm 4 → `--rollback-on-failure`  
**Erreur 2** : `--timeout=5M` → `5M` = 5 méga-secondes (!). Helm utilise le format Go duration : `5m` (minuscule m pour minutes), `5m0s`, etc.

**Commande correcte** :
```bash
helm upgrade --install kube-train ./kube-train-chart \
  -f kube-train-chart/values-gke.yaml \
  --set image.tag=$GIT_SHA \
  --rollback-on-failure \
  --timeout 5m
```

### b) `maxSurge: 0, maxUnavailable: 1` ✅ Correct sur le fond

La réponse couvre bien la logique (évite 2 pods simultanés, réduit les coûts). Le point GKE Autopilot à ajouter pour aller plus loin :

Avec `maxSurge: 1` (défaut), K8s crée le **nouveau pod avant de supprimer l'ancien**. Sur GKE Autopilot sans nœud disponible, ce pod supplémentaire déclenche un **provisionnement de nœud** (~2-3 min). Résultat : le déploiement bloque pendant 2-3 minutes sur `Pending` le temps qu'Autopilot provisionne, puis `--rollback-on-failure` peut expirer inutilement.

Avec `maxSurge: 0, maxUnavailable: 1` : K8s **supprime d'abord** l'ancien pod, puis crée le nouveau sur le nœud libéré → pas de nœud supplémentaire requis.

### c) Deux nouvelles révisions après échec ✅ Correct

Le TP a démontré exactement ce comportement :
```
N+1   failed    Upgrade failed: ...
N+2   deployed  Rollback to N
```
Service disponible sur la version N pendant tout le rollback. ✅

### d) RBAC hors chart ⚠️ Partiellement correct

**Raisons de garder RBAC hors chart** (à compléter) :
1. **Lifecycle séparé** : le RBAC change moins souvent que l'app — les fusionner crée des upgrades inutiles
2. **Séparation des responsabilités** : les ops/sécu gèrent les permissions, les devs gèrent le chart
3. **Permissions de déploiement** : le compte CI/CD qui fait `helm upgrade` n'a peut-être pas les droits de créer des `Role`/`RoleBinding`

**Quand inclure RBAC dans le chart :**  
Pour un **chart de type opérateur** (ex: cert-manager, prometheus-operator) qui a besoin de droits cluster-wide pour fonctionner — le chart est autonome, le SA et le ClusterRole font partie de son installation. C'est le pattern "batteries included" pour les charts distribués publiquement.

---

## Synthèse

| Question | Points | Note |
|---|---|---|
| Q1 — Anatomie du chart ⭐ | 0.7 | Bon, exemple b) et détails c) manquants |
| Q2 — fullnameOverride ⭐ | 0.7 | a) b) parfaits, c) raisonnement incorrect |
| Q3 — Hiérarchie values ⭐ | 0.85 | Très bien, nuance --all |
| Q4 — toYaml / nindent ⭐⭐ | 0.65 | a) d) bien, b) méthode imprécise, c) partiel |
| Q5 — CronJob CKAD ⭐⭐ | 0.7 | a) d) bien, c) mécanismes confondus |
| Q6 — Migration kubectl→Helm ⭐⭐ | 0.75 | a) c) bien, b) 1 solution sur 2 |
| Q7 — Cloud SQL Proxy ⭐⭐ | 0.75 | a) b) c) bien, d) raison principale manquante |
| Q8 — Spring Boot override ⭐⭐⭐ | 0.5 | c) correct, b) framework Helm≠Spring Boot |
| Q9 — helm history ⭐⭐⭐ | 0.85 | Bien, commandes exactes à préciser |
| Q10 — CI/CD GKE ⭐⭐⭐ | 0.7 | --timeout 5M et --atomic à corriger |
| **Total** | **7 / 10** | |

### Point d'attention principal

**Q8b** est la seule vraie incompréhension : la hiérarchie Helm (`values.yaml` → `-f` → `--set`) et la hiérarchie Spring Boot (env vars → `.properties`) sont deux couches orthogonales. Helm génère la ConfigMap → K8s injecte les env vars → Spring Boot les lit avec Relaxed Binding en priorité sur les `.properties`. Les deux hiérarchies se chaînent mais ne se confondent pas.
