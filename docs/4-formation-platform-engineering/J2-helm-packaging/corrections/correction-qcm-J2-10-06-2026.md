# Correction QCM J2 — Helm & Packaging Kubernetes
> Date : 10/06/2026 | Score : **8 / 8**

---

## Question 1 — Structure d'un chart → **1 / 1** ✅

**Réponse : B** — `Chart.yaml` et `templates/`

| Option | Contenu réel |
|---|---|
| A | `values.yaml` (valeurs par défaut) + `charts/` (dépendances — sous-charts) |
| **B ✅** | `Chart.yaml` (métadonnées : name, version, appVersion) + `templates/` (manifestes templatisés) |
| C | `_helpers.tpl` (fonctions Go réutilisables) + `crds/` (Custom Resource Definitions — installées avant les templates) |
| D | `Chart.lock` (verrouillage des versions de dépendances) + `rendered/` (n'existe pas dans la structure standard) |

**À retenir** : les deux seuls fichiers/dossiers **obligatoires** pour `helm lint` sont `Chart.yaml` et `templates/`. Tout le reste (`values.yaml`, `_helpers.tpl`, `charts/`) est optionnel.

---

## Question 2 — Go templating → **1 / 1** ✅

**Réponse : A**

```yaml
{{ if .Values.serviceAccount.create }}
serviceAccountName: {{ include "kube-train.serviceAccountName" . }}
{{ end }}
```

| Option | Erreur |
|---|---|
| **A ✅** | Syntaxe correcte : `.Values` (point obligatoire), `include` avec nom entre guillemets, point `.` passé comme contexte |
| B | `.if` invalide (le point ne précède pas les mots-clés) ; `template` sans point `.` en dernier argument |
| C | `Values` sans point initial → Go template ne résout pas la variable |
| D | `range` itère sur une liste — ne peut pas s'appliquer à un booléen |

**Règle mnémotechnique** :
- Mots-clés (`if`, `range`, `end`, `define`) → **pas de point**
- Valeurs et objets (`.Values`, `.Release`, `.Chart`) → **point obligatoire**
- `include "nom" .` → toujours le point `.` en dernier argument (contexte courant)

---

## Question 3 — Release lifecycle → **1 / 1** ✅

**Réponse : D** — Rollback automatique si l'upgrade échoue.

`helm upgrade --install kube-train ./kube-train-chart --atomic` :
1. Si la release n'existe pas → `install` (révision 1)
2. Si elle existe → `upgrade` (révision N+1)
3. Attend que tous les pods passent `Ready` (selon les probes configurées)
4. Si timeout ou pod en `CrashLoopBackOff` → rollback automatique à la révision précédente

**Note Helm 4** : `--atomic` est déprécié, remplacé par `--rollback-on-failure` (comportement identique).
```bash
# Helm 3 / début Helm 4
helm upgrade --install kube-train . --atomic --timeout 5m
# Helm 4.x
helm upgrade --install kube-train . --rollback-on-failure --timeout 5m
```

**Piège** : `--rollback-on-failure` ne détecte que les problèmes que les probes exposent. Sans `startupProbe`/`readinessProbe` fiables, un pod `Running` qui ne répond pas sera considéré comme sain.

---

## Question 4 — Override de valeurs → **1 / 1** ✅

**Réponse : C** — `sha-8672f7b` (`--set` a la priorité la plus haute)

Hiérarchie des valeurs Helm (priorité croissante) :
```
values.yaml          →  image.tag: latest
-f values-gke.yaml   →  image.tag: stable     (écrase values.yaml)
--set image.tag=...  →  image.tag: sha-8672f7b (écrase tout)
```

**Usage CI/CD** : le SHA git est toujours injecté via `--set image.tag=$GIT_SHA` dans le pipeline. Cela évite de modifier les fichiers values entre chaque déploiement tout en gardant une traçabilité complète dans `helm history`.

---

## Question 5 — Hooks Helm → **1 / 1** ✅

**Réponse : C** — `helm.sh/hook: pre-install,post-upgrade`

Un hook Helm est un `Job` (ou autre manifest) annoté pour s'exécuter à un moment précis du lifecycle :

| Valeur de hook | Quand s'exécute-t-il ? |
|---|---|
| `pre-install` | Avant la première installation |
| `post-install` | Après la première installation |
| `pre-upgrade` | Avant chaque upgrade |
| `post-upgrade` | Après chaque upgrade |

L'énoncé demande : "avant l'installation initiale **puis** après chaque upgrade" → `pre-install,post-upgrade`.

| Option | Problème |
|---|---|
| A `pre-install` | Ne couvre pas le post-upgrade |
| B `pre-install,post-install` | `post-install` = après install, pas après upgrade |
| **C ✅** | `pre-install,post-upgrade` = couvre les deux cas |
| D `hook-weight` | `hook-weight` contrôle l'**ordre d'exécution** entre plusieurs hooks (entier, ex: `"0"`, `"10"`) — ce n'est pas une valeur de hook |

---

## Question 6 — Jobs vs CronJobs → **1 / 1** ✅

**Réponse : B**

```yaml
apiVersion: batch/v1
kind: CronJob
spec:
  schedule: "*/5 * * * *"           # ← sur le CronJob
  jobTemplate:
    spec:
      backoffLimit: 3               # ← sur le Job (jobTemplate.spec)
      template:
        spec:
          restartPolicy: OnFailure  # ← sur le pod template
          containers: [...]
```

| Option | Erreur |
|---|---|
| A | Inversé : c'est le **CronJob** qui a `schedule`, pas le Job. `completions` appartient au Job |
| **B ✅** | Correct : CronJob → `jobTemplate` → pod template → `restartPolicy` |
| C | `restartPolicy: Always` est **interdit** dans un Job/CronJob — un Job doit se terminer |
| D | `backoffLimit` appartient au **Job** (`jobTemplate.spec.backoffLimit`), pas uniquement au CronJob — et il n'existe pas "que pour les CronJob" |

**Règles CKAD à mémoriser** :
- `restartPolicy` → `OnFailure` ou `Never` (jamais `Always` dans un Job)
- `backoffLimit` → `jobTemplate.spec` dans un CronJob
- `schedule` → `spec.schedule` du CronJob (pas dans le Job)

---

## Question 7 — Rendu et simulation → **1 / 1** ✅

**Réponse : A**

| Commande | Cluster requis ? | Crée une release ? | Affiche les NOTES.txt ? | Usage principal |
|---|---|---|---|---|
| `helm template` | Non (offline) | Non | Non | Debug de rendu YAML en local |
| `helm install --dry-run --debug` | Oui (API server) | Non | Oui | Simulation complète avec contexte release |

**Complément** : `--dry-run` communique avec l'API Kubernetes pour valider les manifestes (admission webhooks, quota checks). `helm template` est 100% local — il peut produire un YAML invalide sans erreur.

**Cas d'usage `--debug`** : affiche le YAML rendu même si Helm détecte une erreur de syntaxe. Utile pour debugger les erreurs d'indentation (`toYaml | nindent`) car les messages d'erreur Helm sont rarement précis sur l'emplacement exact.

```bash
# Debug d'une erreur d'indentation :
helm template kube-train ./kube-train-chart --debug 2>&1 | head -50
```

---

## Question 8 — ArgoCD + Helm → **1 / 1** ✅

**Réponse : C**

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
spec:
  source:
    repoURL: https://github.com/samiyc/kube-train.git
    targetRevision: main
    path: kube-train-chart           # dossier du chart dans le repo Git
    helm:
      valueFiles:
        - values-gke.yaml            # relatif au path ci-dessus
  destination:
    server: https://kubernetes.default.svc
    namespace: default
```

| Option | Erreur |
|---|---|
| A | `spec.source.directory.recurse: true` est pour les dossiers de manifestes YAML plats, pas pour Helm |
| B | `spec.source.chart` est réservé aux **Helm repositories** (OCI ou HTTP), pas aux repos Git |
| **C ✅** | `spec.source.path` + `spec.source.helm.valueFiles` = configuration correcte pour un chart dans un repo Git |
| D | `valueFiles` dans `spec.destination` n'existe pas — `destination` contient uniquement `server` et `namespace` |

**Subtilité** : `valueFiles` dans ArgoCD est relatif au `path` du chart (pas à la racine du repo). Donc `values-gke.yaml` pointe vers `kube-train-chart/values-gke.yaml`.
