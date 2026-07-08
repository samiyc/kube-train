# Correction QCM J5 — SRE — SLOs, Error Budgets & Alertes
> Date : 08/07/2026 | Score : **7 / 8** (87,5 %)

---

## Q1 — SLI vs SLO vs SLA en implémentation

**Ta réponse : B ✅ CORRECT**

> Le SLI est la mesure technique observée (ex. ratio 2xx de `GET /trains`), le SLO est la cible interne configurée dans Cloud Monitoring, et le SLA est un engagement externe porté par le métier ou le contrat.

C'est exactement la hiérarchie implémentée sur kube-train : `serviceLevelIndicator.requestBased.goodTotalRatio` (SLI, mesure brute) → `goal: 0.999` dans le SLO (cible interne) → un éventuel contrat client (SLA, pas géré dans ce TP).

**Pièges** :
- A : inversé — le SLA est le contrat, pas le SLI
- C : le SLO n'appartient pas au client, c'est un outil interne SRE
- D : SLI et SLO sont deux concepts distincts (mesure vs cible), pas des synonymes

---

## Q2 — Calcul d'error budget

**Ta réponse : A ❌ INCORRECT — bonne réponse : C**

> Error budget = 100% - SLO = 1% (pour un SLO à 99%).
> Sur 1 000 000 requêtes : 1% × 1 000 000 = **10 000 requêtes en erreur autorisées**.

```
SLO = 99%   → Error Budget = 1%    → 1% × 1 000 000 = 10 000 requêtes  ✅ (réponse C)
SLO = 99,9% → Error Budget = 0,1%  → 0,1% × 1 000 000 = 1 000 requêtes  (ceci est le calcul pour 99,9%, pas 99%)
```

**Piège probable** : la réponse A (1 000) correspond au calcul avec un SLO de **99,9%**, pas 99%. Vérifie toujours le pourcentage exact énoncé dans la question avant de calculer — une confusion 99% / 99,9% change le résultat d'un facteur 10.

**Pièges** :
- B (100 000) : correspondrait à un SLO de 90%
- D (990 000) : c'est le nombre de requêtes **réussies** attendues, pas l'error budget

---

## Q3 — Burn rate alerting

**Ta réponse : B ✅ CORRECT**

> Un burn rate de 14,4× sur 1h signale que le budget d'erreur est consommé ~14 fois plus vite que la normale — assez rapide pour justifier un page immédiat, mais calibré (avec la fenêtre longue associée) pour éviter le bruit d'un micro-pic.

Rappel du calcul (voir notes-J5.md section 3) : `14,4 × 0,1% = 1,44% d'erreurs` → budget épuisé en `30/14,4 ≈ 2,08 jours` si le taux se maintient. C'est le seuil recommandé par le Google SRE Workbook pour le pattern multi-window multi-burn-rate.

**Pièges** :
- A : c'est l'inverse — 14,4× est un signal **rapide**, pas une dérive lente
- C : les burn rate alerts ne remplacent pas les SLOs, ils les complètent
- D : rien à voir avec la saturation CPU des nodes

---

## Q4 — Request-based vs window-based

**Ta réponse : A ✅ CORRECT**

> Un SLO window-based, où chaque fenêtre est marquée good/bad selon le respect du seuil de latence sur la période.

C'est le type de SLO utilisé en pratique sur kube-train pour la latence (`windowsBased` + `metricMeanInRange`, voir notes-J5.md section 4 et section 10). Une exigence de percentile (P95 < 300ms) se traduit naturellement en fenêtres temporelles jugées conformes ou non — contrairement à un simple ratio 2xx qui ne capture aucune notion de latence.

**Pièges** :
- B : un SLO request-based sur les 2xx ne dit rien sur la latence
- C : un uptime check toutes les 24h est bien trop grossier pour du P95
- D : une `AuthorizationPolicy` gère l'autorisation, pas la latence

**Point de vigilance vu en révision** : sur kube-train, le SLO réellement implémenté est un **proxy** (`_max < 500ms`, pas un vrai P95) car Micrometer n'exporte pas les buckets d'histogramme par défaut. Le concept testé ici (window-based pour la latence) reste correct ; l'implémentation réelle est une approximation documentée en section 10 des notes.

---

## Q5 — Golden signals

**Ta réponse : B ✅ CORRECT**

> Traffic, errors, latency, saturation.

Les 4 golden signals du Google SRE Book, tous représentés dans le dashboard "kube-train — Golden Signals" (4 widgets, voir notes-J5.md section 10).

**Pièges** :
- A, C, D : mélangent des métriques DevOps/CI ou de coût qui ne font pas partie des 4 golden signals canoniques

---

## Q6 — Cloud Monitoring SLO API

**Ta réponse : A ✅ CORRECT**

> `rollingPeriod` recalcule en fenêtre glissante continue (ex. 30 jours), alors que `calendarPeriod` se réinitialise sur une borne calendaire fixe (jour, semaine, mois).

Les deux SLOs créés sur kube-train utilisent `"rollingPeriod": "2592000s"` (30 jours glissants) — pas de remise à zéro sur une date calendaire fixe.

**Pièges** :
- B : les deux champs sont disponibles quel que soit le type de service monitored (GKE, Cloud Run, custom, etc.)
- C : rien n'empêche d'utiliser l'un ou l'autre pour dashboards ET alertes
- D : ce sont deux sémantiques différentes, pas des synonymes

---

## Q7 — OPA / Gatekeeper

**Ta réponse : B ✅ CORRECT**

> `ConstraintTemplate` puis `Constraint`.

Exactement l'architecture utilisée pour `allowed-repos-kube-train` et `required-limits-kube-train` : le `ConstraintTemplate` définit la règle en Rego (le "moule"), le `Constraint` instancie cette règle avec des paramètres concrets (ex. `allowedPrefixes`) et un `enforcementAction`.

**Test réel de cette semaine** : la contrainte `allowed-repos-kube-train` a bloqué `curlimages/curl:8.8.0` avec le message `image non autorisée` — preuve que le couple ConstraintTemplate/Constraint fonctionne en production sur le cluster.

**Pièges** :
- A : `PeerAuthentication`/`AuthorizationPolicy` sont des ressources Istio (mTLS et RBAC réseau), pas Gatekeeper
- C : `CustomResourceDefinition` est le mécanisme K8s sous-jacent, mais pas la paire fonctionnelle attendue ; `ConfigMap` n'a rien à voir
- D : `Namespace`/`LimitRange` sont des ressources K8s natives (quotas), pas du policy-as-code Gatekeeper

---

## Q8 — Alert fatigue vs burn rate

**Ta réponse : C ✅ CORRECT**

> Les alertes burn rate relient le bruit d'alerte à l'impact réel sur le SLO et distinguent mieux une dégradation grave d'un micro-pic transitoire.

C'est tout l'intérêt du pattern multi-window multi-burn-rate (section 2 des notes) : un seuil statique de type "taux d'erreur > 2% pendant 5 min" ne dit rien sur l'impact réel vis-à-vis du budget, alors qu'un burn rate calibré (14,4× / 6× / 1×) est directement lié à la vitesse de consommation de l'error budget.

**Pièges** :
- A : c'est l'inverse — bien calibrées, les burn rate alerts réduisent le bruit, elles ne le maximisent pas
- B : au contraire, le burn rate EST une fonction de l'error budget
- D : les burn rate alerts nécessitent justement des métriques applicatives (SLI) pour être calculées

---

## Synthèse

**1 erreur sur 8** — uniquement un calcul numérique (Q2 : confusion entre error budget à 99% vs 99,9%). Tous les concepts SRE (SLI/SLO/SLA, burn rate, golden signals, SLO API, Gatekeeper) sont acquis.

**Point de vigilance pour la suite** : bien relire le pourcentage de SLO énoncé dans la question avant de calculer l'error budget — une confusion 99%/99,9%/99,99% change le résultat d'un ordre de grandeur à chaque fois.
