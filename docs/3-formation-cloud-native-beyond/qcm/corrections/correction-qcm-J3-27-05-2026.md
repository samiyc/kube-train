# Correction QCM J3 — ArgoCD, GitOps & Déploiement Pull-Based
> Date : 27/05/2026 | Score : **5.75 / 8** (72 %)

---

## Question 1 — Push vs Pull deployment ⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ Correct — Push = CI pousse les changements, Pull = ArgoCD tire depuis Git et réconcilie.
> B) ⚠️ Premier problème correct ("CI a les accès admin"). Deuxième problème ("nouvelle image fait tomber la prod") → ce risque existe dans TOUT modèle de déploiement (push ET pull). Ce n'est pas spécifique au Push.
> C) ✅ Correct — La contrainte budget est la vraie raison du choix hybride.

**Les vrais problèmes de sécurité du Push :**

| Problème | Explication |
|---|---|
| CI a un accès cluster-admin | Si la CI est compromise (ex: secret GitHub fuité), l'attaquant a accès total au cluster |
| Drift silencieux | Quelqu'un fait `kubectl edit` → le cluster diverge de Git sans que personne ne le sache. Pas d'audit. |

Le modèle Pull corrige les deux : la CI n'a PLUS d'accès kubectl, et ArgoCD détecte et corrige les drifts.

---

## Question 2 — Source de vérité ⭐

**Ta réponse : ✅ CORRECT (1/1)**

> A) ✅ selfHeal détecte le drift et réaligne sur Git
> B) ✅ Modifier dans Git = IaC = traçabilité (commit = qui, quand, quoi)
> C) ✅ 3 minutes (polling par défaut)

Parfait. En entretien, préciser que le polling de 3 min peut être réduit à quelques secondes avec un **webhook GitHub** (notification push vers ArgoCD au lieu de polling).

---

## Question 3 — Composants ArgoCD ⭐⭐

**Ta réponse : ❌ INCORRECT (0.5/1)**

> Tu as choisi A) `argocd-server` comme indispensable. C'est B) `argocd-application-controller`.

**Correction :**

| Composant | Rôle | Indispensable ? |
|---|---|---|
| **B) `argocd-application-controller`** | Compare l'état Git vs cluster, déclenche les syncs, applique les changements | ✅ **LE CŒUR** — sans lui, aucun sync ne se produit |
| A) `argocd-server` | UI web + API REST (le dashboard qu'on voit dans le navigateur) | ❌ L'UI peut être down, les syncs continuent |
| `argocd-repo-server` | Clone le repo Git, génère les manifests finaux | ⚠️ Nécessaire (fait le `git pull`) |
| C) `argocd-dex-server` | SSO/OAuth pour l'UI (login Google, GitHub...) | ❌ Optionnel (auth) |
| D) `argocd-applicationset-controller` | Génère des Applications à partir de templates (monorepo) | ❌ Optionnel (celui en CrashLoopBackOff chez nous, 0 impact) |

**Point clé** : `argocd-server` est l'**interface** (UI/API), pas le **moteur**. Le moteur c'est le `application-controller`. Si l'UI est down, ArgoCD continue de synchroniser en background.

---

## Question 4 — syncPolicy et prune ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ Correct — prune supprime les resources K8s dont le YAML a été supprimé de Git.
> B) ⚠️ L'idée de "double validation" est raisonnable mais ce n'est pas le mécanisme ArgoCD. Le vrai garde-fou :
> C) ✅ Correct — Sans `automated`, sync via UI ou CLI (`argocd app sync kube-train`).

**Les vrais garde-fous contre prune dangereux :**

1. **Annotation par resource** : `argocd.argoproj.io/sync-options: Prune=false` sur une resource critique (ex: PVC de base de données) — ArgoCD ne la supprimera JAMAIS même si elle disparaît de Git.

2. **Finalizers ArgoCD** : `resources-finalizer.argocd.argoproj.io` empêche la suppression tant que la resource n'est pas retirée de la gestion ArgoCD.

3. **Git PR review** : en GitOps strict, personne ne push sur `main` directement. La suppression d'un YAML passe par une PR → review → merge. C'est LE garde-fou naturel du GitOps.

**Exemple concret** : si tu supprimes `k8s/otel-collector.yaml` de Git et push → ArgoCD supprime le pod OTel Collector du cluster. Sans review, c'est dangereux.

---

## Question 5 — La boucle infinie ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ Correct (succinct) — CI commit tag → push → déclenche CI → re-commit → boucle infinie
> B) ✅ Correct — `paths-ignore` sur les deployment YAML
> C) ❌ "Ça ne me parle pas"

**Pourquoi `[skip ci]` n'est pas suffisant seul :**

1. **GitHub Actions ne respecte pas toujours `[skip ci]`** — si le push est fait avec le `GITHUB_TOKEN` par défaut (ce qui est notre cas), `[skip ci]` est respecté. MAIS si on utilise un PAT (Personal Access Token) ou si le commit est squashé/rebased, `[skip ci]` peut être ignoré.

2. **Dépend du fournisseur CI** — `[skip ci]` est une convention, pas un standard garanti. GitLab, Jenkins, CircleCI ont des syntaxes différentes. Migrer de CI = risque de boucle.

3. **`paths-ignore` est un mécanisme natif GitHub Actions** — c'est évalué AVANT le démarrage du workflow. Même si `[skip ci]` échoue, `paths-ignore` est garanti côté serveur.

→ On utilise les deux en ceinture + bretelles, mais `paths-ignore` est le vrai filet de sécurité.

---

## Question 6 — Rollback GitOps ⭐⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ Correct — `git revert` → push → ArgoCD sync l'ancien état
> B) ⚠️ "Config spécifique au cluster" n'est pas la bonne explication.
> C) ✅ Correct — Bouton Rollback UI = décalage avec la source de vérité Git.

**Pourquoi git revert > kubectl rollout undo :**

| Critère | `kubectl rollout undo` | `git revert` + ArgoCD |
|---|---|---|
| Source de vérité | Le cluster (pas Git) | Git (source unique) |
| Audit | Qui a lancé la commande ? Quand ? Pas de trace. | Commit = auteur + timestamp + diff |
| Durabilité | ArgoCD va RE-SYNCER l'état broken de Git → annule le rollback ! | Git EST à jour → ArgoCD sync correctement |
| Reproductibilité | Impossible de reproduire sur un autre cluster | `git log` = historique complet |

**Point critique** : avec ArgoCD actif, `kubectl rollout undo` est **contre-productif** car ArgoCD va immédiatement re-déployer la version Git (qui est broken). Git revert est la SEULE approche qui fonctionne avec GitOps.

---

## Question 7 — Fichiers exclus et include/exclude ⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.75/1)**

> A) ✅ Correct — `deployment.yaml` est pour Minikube/local (imagePullPolicy: Never)
> B) ⚠️ Bonne intuition (éviter la récursion) mais mauvaise justification ("GitHub n'a pas accès"). La vraie raison :
> C) ✅ Correct — Le nouveau fichier sera déployé automatiquement.

**Pourquoi exclure `argocd/**` :**

1. **Éviter la récursion infinie** : si ArgoCD gère son propre `application.yaml`, et qu'il y a un changement → il se resync → modifie son propre état → resync → boucle.

2. **Séparation des responsabilités** : l'Application est un CRD **de management**, pas une resource applicative. C'est comme un fichier `.gitignore` : il gère le repo mais n'est pas "le code".

3. **L'application est appliquée UNE FOIS** (`kubectl apply -f k8s/argocd/application.yaml`) puis ArgoCD se gère via ses propres mécanismes internes.

**Précision sur C)** : ce n'est pas `selfHeal` qui déploie le nouveau fichier, c'est l'**auto-sync** (syncPolicy.automated). selfHeal corrige les drifts APRÈS déploiement (ex: quelqu'un supprime la NetworkPolicy via kubectl → selfHeal la recrée).

---

## Question 8 — Self-heal vs HPA ⭐⭐⭐

**Ta réponse : ⚠️ PARTIEL (0.5/1)**

> A) ⚠️ "Non, ce serait contre-productif" → c'est vrai conceptuellement, MAIS par défaut ArgoCD COMBAT le HPA ! Il essaie de remettre `replicas: 1` (valeur Git) → le HPA remet 3 → ArgoCD remet 1 → boucle. C'est un problème connu qui nécessite une config explicite.
> B) ⚠️ Bonne direction (modifier la config ArgoCD), mais ce n'est pas "dans application.yaml puis kubectl apply". C'est via `ignoreDifferences` dans le spec de l'Application.
> C) ⚠️ L'exemple "service qui veut s'éteindre" n'est pas un cas classique.

**Configuration `ignoreDifferences` :**

```yaml
# Dans k8s/argocd/application.yaml
spec:
  ignoreDifferences:
    - group: apps
      kind: Deployment
      jsonPointers:
        - /spec/replicas   # ArgoCD ignore ce champ → le HPA gère seul
```

Avec cette config, ArgoCD ne considère plus `spec.replicas` comme un drift → le HPA peut scaler librement sans conflit.

**Exemples classiques de conflits controller ↔ ArgoCD :**

| Controller K8s | Champ modifié | Conflit |
|---|---|---|
| **HPA** | `spec.replicas` | ArgoCD remet la valeur Git, HPA la change |
| **VPA** (Vertical Pod Autoscaler) | `resources.requests/limits` | ArgoCD remet les resources Git, VPA les ajuste |
| **cert-manager** | annotations sur Ingress + TLS secret | ArgoCD voit un diff sur les annotations ajoutées par cert-manager |
| **Istio sidecar injector** | `spec.containers` (ajoute envoy) | ArgoCD voit un container en plus vs le manifest Git |

La solution est toujours `ignoreDifferences` pour les champs gérés par d'autres controllers.

---

## 📊 Récapitulatif

| # | Question | Score | Commentaire |
|---|---|---|---|
| 1 | Push vs Pull | 0.75/1 | 2e problème sécurité inexact (pas spécifique au Push) |
| 2 | Source de vérité | 1/1 | ✅ Parfait |
| 3 | Composants ArgoCD | 0.5/1 | Le controller (B) est le cœur, pas le server (A) |
| 4 | syncPolicy et prune | 0.75/1 | Garde-fous ArgoCD (annotations) pas connus |
| 5 | Boucle infinie | 0.75/1 | `[skip ci]` non fiable à 100% |
| 6 | Rollback GitOps | 0.75/1 | `kubectl rollout undo` + ArgoCD actif = conflit |
| 7 | Fichiers exclus | 0.75/1 | Récursion ArgoCD + auto-sync vs selfHeal |
| 8 | Self-heal vs HPA | 0.5/1 | Par défaut ArgoCD COMBAT le HPA (ignoreDifferences requis) |

**Score final : 5.75 / 8 (72 %)**

### Points forts ✅
- Excellente compréhension du modèle Pull et de la source de vérité Git
- Bonne vision d'ensemble de ArgoCD (auto-sync, self-heal, prune)
- Logique IaC bien intégrée ("tout doit passer par Git")

### Points à consolider 📚
- **Composants ArgoCD** : retenir que `application-controller` = le moteur (sync), `server` = l'UI (interface)
- **ignoreDifferences** : configuration cruciale dès qu'un autre controller (HPA, VPA, cert-manager) modifie des champs
- **`[skip ci]` n'est pas fiable** : `paths-ignore` est le vrai garde-fou côté GitHub Actions
- **`kubectl rollout undo` + ArgoCD** = conflit garanti. En GitOps, TOUJOURS passer par Git.
