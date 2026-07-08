# Formation F5 — Préparation CKAD (Certified Kubernetes Application Developer)

> **Fil rouge** : passer de "je sais déployer kube-train" à "je résous 15-20 tâches K8s en 2h sous chrono, open-book, sans hésiter". Focus **vitesse impérative** + couverture exhaustive des 5 domaines CKAD.

**Prérequis** : F2 (Cloud Native), F3 (Beyond), F4 (Platform Engineering) complétées.

**Certif visée** : CKAD — 2h, 15-20 tâches hands-on CLI, ≥ 66 % pour passer, open-book (`kubernetes.io/docs` autorisé), validité 2 ans.

---

## 🎯 Objectifs de la formation

1. **Vitesse** — maîtriser l'impératif (`kubectl create/run --dry-run=client -o yaml`), les alias, vim, pour tenir le rythme ~6-8 min/tâche.
2. **Couverture** — combler les gaps CKAD non couverts F2-F4 (Kustomize, patterns multi-container, `kubectl explain`, Jobs/CronJobs avancés).
3. **Reproductibilité** — chaque examen blanc est rejouable (environnement + procédure de reset), pour s'entraîner par répétition.
4. **Rétention** — ancrer via examens ouverts quotidiens + Anki + NotebookLM.
5. **Local-first** — tout sur Minikube pour tenir le budget ≤ 5 €/jour (GCP réactivé seulement pour les labs haut-ROI).

---

## 💰 Contexte budgétaire & timeline

> **Essai GCP se termine fin juillet. Vacances à partir du vendredi 24 juillet.**

- **Avant le 24/07** (essai actif) : faire les **labs GCP haut-ROI** (voir section dédiée) pendant que les crédits sont disponibles.
- **À partir d'août** (retour) : **priorité Minikube/local**. GCP réactivé ponctuellement, toujours avec `terraform destroy` en fin de session, objectif ≤ 5 €/jour.
- Le CKAD se prépare et se passe **entièrement en local** — aucune dépendance GCP.

---

## 📋 Format pédagogique

| Élément | Description |
|---------|-------------|
| **Durée** | 8 jours (J1-J8) : 6 drills + 2 examens |
| **Matin** | Théorie ciblée CKAD + démo impérative (générer le YAML à la volée) |
| **Après-midi** | Drill chronométré : 8-12 micro-tâches type examen, timées |
| **Fin de journée** | Examen ouvert (questions de compréhension) + création de cartes Anki sur les ratés |
| **Environnement** | Minikube local (Docker driver, WSL) |
| **Livrables** | `notes-Jx.md` + `examen-ouvert-Jx.md` + section runbook par jour |

**Principe drill** : contrairement à F4 (escalier de complexité), F5 privilégie le **volume et la vitesse** — beaucoup de petites tâches répétées jusqu'à l'automatisme, comme à l'examen.

---

## ⚙️ Setup examen (à internaliser dès J1)

```bash
# Alias et raccourcis — à taper les yeux fermés
alias k=kubectl
export do="--dry-run=client -o yaml"    # k create deploy x --image=nginx $do
export now="--force --grace-period=0"    # k delete pod x $now
source <(kubectl completion bash)
complete -o default -F __start_kubectl k

# vim ~/.vimrc — indentation YAML
# set tabstop=2 shiftwidth=2 expandtab
```

**Réflexes clés** : jamais écrire un YAML from scratch → toujours `k ... $do > f.yaml` puis éditer. Toujours `k explain <resource>.spec` en cas de doute (open-book intégré au cluster).

---

## 📅 Programme détaillé

---

### J1 — Core workloads & vitesse kubectl

**Objectif** : générer Pods/Deployments/ReplicaSets à la volée, sans YAML manuel, sous chrono.

**Matin — Théorie :**
- `kubectl run` / `create deployment` / `create job` en impératif + `$do`
- Édition rapide : `k edit`, `k set image`, `k scale`, `k patch`
- Labels & selectors : `--selector`, `-l`, `k label`, `k annotate`
- `kubectl explain` pour retrouver un champ sans quitter le terminal
- Cheatsheet de conversion impératif → déclaratif

**Après-midi — Drill (timé) :**
1. Créer 5 pods avec images/labels différents en < 5 min
2. Deployment nginx 3 replicas, exposer, scaler à 5, rollback d'image
3. Pod avec command/args custom, env inline, resources requests/limits
4. Multi-label + filtrage par selector, suppression en masse
5. Un pod éphémère de debug (`k run tmp --rm -it --image=busybox`)

**Examen ouvert J1** : impératif vs déclaratif, cycle de vie Pod, ownerReferences (Deployment→RS→Pod)

**Révision transverse** : ↔ F1/F2 (déploiement kube-train de base)

---

### J2 — Config & Security (domaine 25 % — le plus lourd)

**Objectif** : ConfigMaps, Secrets, ServiceAccounts, RBAC, securityContext sous chrono.

**Matin — Théorie :**
- ConfigMap/Secret : `--from-literal`, `--from-file`, `--from-env-file` ; montage env vs volume
- ServiceAccount + `automountServiceAccountToken`
- RBAC : Role/RoleBinding/ClusterRole/ClusterRoleBinding, `k create role/rolebinding`, `k auth can-i`
- securityContext : `runAsNonRoot`, `runAsUser`, `readOnlyRootFilesystem`, `capabilities`, niveau pod vs container
- `kubectl create token`

**Après-midi — Drill (timé) :**
1. ConfigMap depuis literal + fichier → monter en env puis en volume
2. Secret générique → injecter dans un pod, décoder
3. SA dédié + Role (get/list secrets) + RoleBinding + test `can-i`
4. Pod non-root, read-only FS + emptyDir pour `/tmp`
5. Drop de toutes les capabilities sauf `NET_BIND_SERVICE`

**Examen ouvert J2** : RBAC verbes/resourceNames, securityContext niveaux, token SA

**Révision transverse** : ↔ **F4-J1** (RBAC, PSS, securityContext, LimitRange — déjà pratiqué sur kube-train)

---

### J3 — Multi-container & Application Design (domaine 20 %)

**Objectif** : init containers, patterns sidecar/ambassador/adapter, Jobs, CronJobs.

**Matin — Théorie :**
- Init containers : ordre, cas d'usage (attente dépendance, migration)
- Patterns multi-container : sidecar (log shipper), ambassador (proxy), adapter (format)
- `emptyDir` partagé entre containers
- Jobs : `completions`, `parallelism`, `backoffLimit`, `activeDeadlineSeconds`, `restartPolicy`
- CronJobs : `schedule`, `concurrencyPolicy`, `startingDeadlineSeconds`, `successful/failedJobsHistoryLimit`

**Après-midi — Drill (timé) :**
1. Pod init container qui attend un service (busybox `until nslookup`)
2. Sidecar : container principal écrit un log, sidecar le tail via emptyDir partagé
3. Job `completions=5 parallelism=2 backoffLimit=3`
4. CronJob `*/1 * * * *` avec `concurrencyPolicy: Forbid`
5. Ambassador : container app + proxy localhost

**Examen ouvert J3** : ordre de démarrage init/sidecar, sémantique Job vs CronJob, restartPolicy

**Révision transverse** : ↔ **F1** (Cloud SQL Proxy sidecar), **F4-J2** (CronJob Helm outbox)

---

### J4 — Services & Networking (domaine 20 %)

**Objectif** : Services (tous types), Ingress, NetworkPolicies, port-forward.

**Matin — Théorie :**
- Service : ClusterIP / NodePort / LoadBalancer / ExternalName / Headless, `k expose`
- Endpoints & selectors, dépannage "service ne route pas"
- Ingress : rules, paths, `pathType`, hosts, TLS
- NetworkPolicy : ingress/egress, `podSelector`, `namespaceSelector`, default-deny
- `k port-forward`, `k proxy`, DNS interne (`svc.ns.svc.cluster.local`)

**Après-midi — Drill (timé) :**
1. Exposer un deployment en ClusterIP puis NodePort, tester
2. Ingress 2 paths → 2 services différents
3. NetworkPolicy default-deny + autoriser un pod précis par label
4. Debug d'un Service qui ne route pas (selector mismatch)
5. Headless service + résolution DNS

**Examen ouvert J4** : types de Service, chaîne Ingress→Service→Endpoints, logique NetworkPolicy allowlist

**Révision transverse** : ↔ **F3-J4** (NetworkPolicies), **F4-J4** (Istio VirtualService — L7 au-dessus des Services)

---

### J5 — Observability & Maintenance (domaine 15 %)

**Objectif** : probes, logs, `kubectl debug`, troubleshooting rapide.

**Matin — Théorie :**
- Probes : liveness / readiness / startup ; `exec` / `httpGet` / `tcpSocket` ; timing (`initialDelay`, `period`, `failureThreshold`)
- Logs : `k logs`, `-c`, `--previous`, `-f`, `--all-containers --prefix`
- Debug : `k describe`, `k get events`, `k debug` (ephemeral containers), `k exec`
- Diagnostic : CrashLoopBackOff, ImagePullBackOff, Pending, OOMKilled
- `k top` (metrics-server)

**Après-midi — Drill (timé) :**
1. Ajouter les 3 probes à un deployment, casser la readiness, observer
2. Diagnostiquer un CrashLoopBackOff via logs `--previous`
3. Diagnostiquer un Pending (ressources / selector node)
4. `k debug` sur un pod distroless sans shell
5. Extraire un événement précis via `k get events --sort-by`

**Examen ouvert J5** : différence liveness/readiness/startup, arbre de décision d'un pod qui ne démarre pas

**Révision transverse** : ↔ **F4-J5** (probes déjà en prod sur kube-train, debug multi-containers du runbook)

---

### J6 — Deployment & Packaging (domaine 20 %)

**Objectif** : rollouts/rollback, stratégies de déploiement, **Helm + Kustomize**.

**Matin — Théorie :**
- Deployment strategy : RollingUpdate (`maxSurge`/`maxUnavailable`), Recreate
- `k rollout status/history/undo`, `--revision`, `k rollout pause/resume`
- Blue/green & canary "à la main" (2 deployments + service selector switch)
- **Helm** (rappel F4 créateur) : `install/upgrade/rollback`, values, `--set`
- **Kustomize** (NOUVEAU — gap CKAD) : `kustomization.yaml`, bases/overlays, patches, `k apply -k`

**Après-midi — Drill (timé) :**
1. RollingUpdate avec `maxSurge=2 maxUnavailable=0`, observer le pic
2. Rollout d'une mauvaise image → `undo` vers la révision précédente
3. Canary manuel : shift du selector de service entre v1/v2
4. Kustomize : base + overlays dev/prod avec patch de replicas + image
5. Helm : install kube-train-chart en local, override via `--set`

**Examen ouvert J6** : RollingUpdate vs Recreate, Helm vs Kustomize (quand l'un plutôt que l'autre)

**Révision transverse** : ↔ **F4-J2** (Helm chart kube-train), F2/F3 (rolling updates)

---

### J7 — Examen blanc #1 (chronométré, reproductible)

**Objectif** : conditions réelles — 2h, ~16 tâches couvrant les 5 domaines.

- Utiliser `templates/template-examen-blanc-chrono.md` (barème temps + procédure de reset).
- Environnement : namespaces jetables (`ckad-mock1-*`), script de setup + reset.
- **Règles réelles** : chrono strict, seul `kubernetes.io/docs` ouvert, alias autorisés.
- Correction immédiate : score par domaine, identification des lacunes.
- **Chaque tâche ratée → carte Anki** (recto tâche, verso la séquence de commandes).

**Livrable** : `J7-examen-blanc-1/examen-blanc-1.md` + résultats + lacunes ciblées.

---

### J8 — Examen blanc #2 + révision transverse + méta

**Objectif** : valider la progression + consolidation transverse + positionnement mission.

**Matin — Examen blanc #2** (nouveau jeu de tâches, même format que J7). Comparer le score à J7.

**Après-midi — Consolidation :**
- **Révision transverse F2-F4** : relecture croisée des bilans + notes (NotebookLM sur l'ensemble des `notes-*.md`).
- **ELI5 — Rôles** : Architect vs Tech Lead vs DevOps vs SRE (section ci-dessous).
- **Logistique certif** : inscription CKAD, PSI/checks système, Killer.sh (2 sessions incluses), stratégie de temps.

**Livrable** : `J8-examen-blanc-2/examen-blanc-2.md` + synthèse de progression J7→J8 + décision de date d'examen.

---

## 🧩 Gap-analysis CKAD (sujets non couverts F2-F4 à combler)

| Sujet CKAD | Statut avant F5 | Où en F5 |
|------------|-----------------|----------|
| Vitesse impérative (`$do`, alias, vim) | ❌ jamais drillé | J1 (transverse) |
| Deployments/Services/Ingress/Probes | ✅ F2-F4 | rappel J1/J4/J5 |
| RBAC / securityContext / PSS | ✅ F4-J1 | consolidation J2 |
| NetworkPolicies | ✅ F3-J4 | consolidation J4 |
| Jobs / CronJobs | ⚠️ F4-J2 (Helm only) | J3 (impératif) |
| Init containers | ✅ F4-J1 | J3 |
| Patterns multi-container (ambassador/adapter) | ❌ | J3 |
| **Kustomize** | ❌ jamais vu | J6 |
| `kubectl debug` (ephemeral containers) | ❌ | J5 |
| `kubectl explain` réflexe | ⚠️ | J1 (transverse) |
| Rollout undo / history / pause | ⚠️ | J6 |

---

## 🚀 Features GCP haut-ROI — À FAIRE AVANT LE 24/07 (essai actif)

> Objectif : maximiser la valeur de l'essai GCP restant avant sa fin. Time-boxé, chaque lab ≤ 1 demi-journée, `destroy` après.

| Feature | Valeur (certif/mission) | Effort |
|---------|-------------------------|--------|
| **Cloud Deploy** (delivery pipeline managé) | GCP DevOps Engineer — progressive delivery managée | Moyen |
| **Cloud Build triggers** (build sur push) | Alternative managée à GitHub Actions | Faible |
| **Binary Authorization** (attestation d'images) | Supply chain security, aligne avec Gatekeeper F4 | Moyen |
| **GKE Cost insights / Recommender** | FinOps, optimisation coût | Faible |
| **Cloud Trace + Profiler** | Complète l'OTel de F4, observabilité prod | Faible |
| **Gemini Cloud Assist** | Productivité ops, sujet 2026 GCP Developer | Faible |

> ⚠️ Ces labs sont **hors périmètre CKAD** (qui est 100 % local). Ils servent la certif GCP DevOps ultérieure. À ne faire que si le temps le permet avant les vacances.

---

## 🧠 Stratégie de révision (outils)

> Détail complet dans `docs/3-formation-cloud-native-beyond/extra/outils-revision-apprentissage.md`.

- **NotebookLM** — charger tous les `notes-Jx.md` de F5 dans un notebook → audio de synthèse + quiz. Avant chaque examen ouvert.
- **Anki** — rituel quotidien : chaque tâche ratée en drill / chaque question ouverte floue → 1 carte. 5-10 min/jour. Decks AnkiWeb "CKAD Kubernetes" en complément.
- **Killer.sh** — 2 sessions incluses avec l'inscription CKAD. Session 1 après J6, session 2 à J-3 de l'examen. Volontairement plus dur que l'examen réel.
- **Play with Kubernetes** — cluster jetable pour tester un concept sans polluer Minikube.

**Boucle d'apprentissage F5** : drill (faire) → examen ouvert (verbaliser) → Anki (mémoriser) → NotebookLM (synthétiser) → examen blanc (valider sous chrono).

---

## 🎭 ELI5 — Architect vs Tech Lead vs DevOps vs SRE

> Positionnement des rôles pour la mission DKT et les entretiens. Chacun regarde kube-train sous un angle différent.

| Rôle | Question centrale | Sur kube-train | Horizon |
|------|-------------------|----------------|---------|
| **Architect** | *"Quelle structure globale et quels choix de fond ?"* | Event-driven + GKE + Cloud SQL + Pub/Sub ; pourquoi mesh vs pas mesh ; découpage des services | Mois / années |
| **Tech Lead** | *"Comment l'équipe livre ce design proprement ?"* | Standards de code, revue des PR, découpage des tâches, cohérence Helm/Terraform, montée en compétence de l'équipe | Sprints |
| **DevOps** | *"Comment automatiser le chemin du commit à la prod ?"* | Pipeline CI/CD, Terraform IaC, Helm, GitOps — le "plombier" qui rend le déploiement reproductible | Jours / semaines |
| **SRE** | *"Comment garder ça fiable en prod, mesurablement ?"* | SLOs, error budgets, burn rate, dashboards, on-call — arbitre fiabilité vs vélocité | Temps réel / incidents |

**ELI5** :
- L'**Architect** dessine le plan de la maison.
- Le **Tech Lead** dirige les ouvriers pour qu'ils la construisent bien.
- Le **DevOps** installe la plomberie et l'électricité automatisées.
- Le **SRE** surveille que la maison ne prend pas feu et décide quand on peut ajouter un étage.

Les frontières se recouvrent : un senior cloud-native (profil visé) touche souvent aux 4, avec un centre de gravité DevOps/SRE.

---

## 📦 Livrables attendus en fin de F5

- [ ] `notes-Jx.md` pour J1-J6 (théorie ciblée CKAD + cheatsheets impératives)
- [ ] `examen-ouvert-Jx.md` pour J1-J6 (un par jour de drill)
- [ ] 2 examens blancs chronométrés reproductibles (J7, J8) + résultats
- [ ] Runbook F5 (sections par jour, commandes Minikube-first)
- [ ] Deck Anki alimenté (tâches ratées + questions ouvertes)
- [ ] Décision de date d'examen CKAD + planning Killer.sh
- [ ] (Optionnel, avant 24/07) 2-3 labs GCP haut-ROI documentés

---

## 🗓️ Planning suggéré

```
Avant le 24/07 (essai GCP actif) : labs GCP haut-ROI (optionnel) + cleanup infra
Vacances : 24/07 → mi-août
Retour août (local-first Minikube) :
  Semaine 1 : J1 (core) + J2 (config/security) + J3 (multi-container)
  Semaine 2 : J4 (networking) + J5 (observability) + J6 (deployment/kustomize)
  Semaine 3 : J7 (examen blanc 1) + Killer.sh session 1 + révision lacunes
  Semaine 4 : J8 (examen blanc 2) + Killer.sh session 2 → PASSAGE CKAD
```

---

## 🔄 Progression F4 → F5

| Aspect | F4 (Platform Engineering) | F5 (CKAD Prep) |
|--------|---------------------------|----------------|
| **But** | Construire une plateforme production-ready | Réussir la certif CKAD (vitesse + couverture) |
| **Style TP** | Escalier de complexité (4-5 étapes) | Drills volumineux et chronométrés |
| **Environnement** | GKE (cloud) | Minikube (local, budget-first) |
| **Rythme** | Approfondissement | Vitesse (~6-8 min/tâche) |
| **Évaluation** | QCM 8 questions | Examens ouverts + examens blancs chrono |
| **Outil clé** | Helm/Terraform/Istio | `kubectl` impératif + Kustomize |

---

*Dernière mise à jour : 2026-07-08*
