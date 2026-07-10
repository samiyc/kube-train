# Point de situation chiffré — projet kube-train

> Photo quantitative du dépôt au **2026-07-10** (branche `main`, commit `43f08d5`).
> Objectif : présenter rapidement des **insights sur le volume, la progression et la structure**
> du projet, au-delà des technos (K8s / GCP / Helm / Terraform / Kafka / Pub-Sub / OTel).
>
> Méthodo : fichiers = `git ls-files` (suivis uniquement, hors artefacts de build) ; tests = Maven Surefire ;
> couverture = JaCoCo (`kube-train-api`). Commandes de reproduction en fin de doc.

---

## 🎯 Insights clés (les chiffres à retenir)

| # | Insight | Chiffre |
|---|---------|---------|
| 1 | **Projet de formation avant tout** : la doc pèse ~8× le code | **20 692** lignes `.md` vs **2 605** lignes `.java` → **ratio 7,9×** |
| 2 | Application compacte et « production-shaped » | **31** classes main / **1 734** lignes, **6** endpoints REST, **2** micro-services |
| 3 | Base de tests solide | **39** tests verts, **couverture lignes 71,5 %** (api) |
| 4 | Rythme de travail intense et régulier | **223** commits sur **7 mois** (pic **130** en mai 2026) |
| 5 | Infra-as-code réelle | **35** manifests K8s, **11** fichiers Helm, **9** fichiers Terraform |
| 6 | Parcours pédagogique structuré | **5** formations, **11** QCM, **6** TP, **10** runbooks, **13** corrections |

**Volume total du dépôt** : **218** fichiers suivis · **28 085** lignes (hors binaires).

```
Répartition du volume (lignes, hors binaires)
  .md    ██████████████████████████████████████   20 692  (74 %)
  .java  █████                                     2 605  ( 9 %)
  .yaml  ████                                      2 083  ( 7 %)  (+ .yml 523)
  .xml   █                                           438  (poms)
  .tf    ▏                                          200
  autres ▏                                       ~1 544
```

---

## 📦 Volume par type de fichier

| Type | Fichiers | Lignes | Rôle |
|------|---------:|-------:|------|
| `.md` | 81 | 20 692 | Documentation de formation |
| `.yaml` + `.yml` | 52 | 2 606 | Manifests K8s, Helm, CI, docker-compose |
| `.java` | 41 | 2 605 | Code applicatif + tests |
| `.xml` | 6 | 438 | `pom.xml` (parent + 2 modules) |
| `.properties` | 9 | 129 | Config Spring (6 profils) |
| `.tf` | 9 | 200 | Terraform (infra GCP) |
| `.sql` | 3 | 65 | Migrations Flyway |
| `Dockerfile` | 2 | 49 | Build images (api + notification) |
| `.feature` | 1 | 30 | BDD Cucumber |
| `.py` | 1 | 14 | Load test Locust |
| Autres | 13 | ~1 257 | `.cmd`/`mvnw`, `.json`, `.tpl`, `.hcl`, `.gitignore`… |
| **Total** | **218** | **28 085** | |

---

## 💻 Code Java

| Métrique | Main | Test | Total |
|----------|-----:|-----:|------:|
| Fichiers | 31 | 10 | 41 |
| Lignes | 1 734 | 871 | 2 605 |
| Lignes non vides | 1 514 | 731 | 2 245 |

**Types (main)** : 22 classes · 6 records · 3 interfaces · 0 enum = **31 types**.
**API REST** : 6 endpoints — 5 `@GetMapping` (`/`, `/trains`, `/trains/{id}`, `/reservations/{id}`, `/secure`) + 1 `@PostMapping` (`/reservations`).
**Micro-services** : `kube-train-api` (REST + producer) et `train-notification-service` (consumer) — projet Maven multi-module (1 parent + 2 enfants).

---

## ✅ Tests & couverture

**39 tests exécutés** (Maven Surefire, BUILD SUCCESS) :

| Module | Tests | Détail |
|--------|------:|--------|
| `kube-train-api` | 36 | 26 tests unitaires/intégration (`@Test`) + 3 tests de contrat générés (Spring Cloud Contract) + **7 exécutions BDD** |
| `train-notification-service` | 3 | tests de contrat consumer (stub-runner WireMock) |

**BDD Cucumber** : 4 scénarios écrits (dont 1 *Plan du scénario* × 4 exemples) → **7 exécutions**, 12 steps.
**Contrats** : 3 fichiers `contracts/*.yaml` (producer) → stubs réutilisés côté consumer.

**Couverture JaCoCo — `kube-train-api`** :

| Compteur | Couverture |
|----------|-----------:|
| Lignes | **71,5 %** (203/284) |
| Instructions | 69,9 % (837/1197) |
| Branches | 75,0 % (15/20) |
| Méthodes | 70,1 % (54/77) |
| Classes | 85,0 % (17/20) |

> `train-notification-service` n'a pas le plugin JaCoCo → pas de rapport de couverture pour ce module.

---

## ☸️ Infrastructure & déploiement (as code)

| Élément | Volume |
|---------|-------:|
| Manifests K8s (`k8s/`) | **35** yaml |
| — `network/` | 9 |
| — `workloads/` | 8 |
| — `security/` | 6 |
| — `istio/` | 5 |
| — `database/` | 3 |
| — `observability/` | 3 |
| — `argocd/` | 1 |
| Chart Helm (`kube-train-chart/`) | **11** fichiers |
| Terraform (`infra/`) | **9** `.tf` (200 lignes) |
| `docker-compose*.yml` | 2 (dev local + observabilité) |
| Migrations Flyway | 3 `.sql` |

---

## 📚 Documentation de formation

**Par formation** :

| Formation | Fichiers `.md` | Lignes | Statut |
|-----------|---------------:|-------:|--------|
| F1 — Kubernetes / Minikube | 2 | 337 | ✅ |
| F2 — Cloud Native (GKE) | 7 | 2 951 | ✅ |
| F3 — Cloud Native Beyond | 20 | 4 482 | ✅ |
| **F4 — Platform Engineering** | **44** | **11 974** | ✅ (en cours de clôture) |
| F5 — CKAD Prep | 6 | 743 | ⏳ à démarrer |
| Racine (`README`, `CLAUDE.md`) | 2 | — | — |

**Par type de livrable pédagogique** :

| Type | Nombre |
|------|-------:|
| Corrections (QCM / examens) | 13 |
| QCM | 11 |
| Runbooks | 10 |
| TP | 6 |
| Notes de jour (`notes-Jx`) | 6 |
| Plans de formation | 5 |
| Examens ouverts | 3 |
| Bilans | 2 |
| Notes de révision | 2 |

---

## 📈 Activité Git

| Métrique | Valeur |
|----------|-------:|
| Commits total | **223** |
| Période | 2025-12-08 → 2026-07-10 (~7 mois) |
| Auteurs humains | 158 commits (samiyc) |
| Bot CI (GitOps tags) | 65 commits (29 %) |

**Commits par mois** :

```
2025-12  ███ 3
2026-04  ██████████ 10
2026-05  ████████████████████████████████████████████████████████████████ 130
2026-06  ████████████████████████████████████ 73
2026-07  ███████ 7
```

> Le pic de mai/juin correspond aux formations F2→F4 (janvier-mars = pause).

---

## 🔁 Reproductibilité

```powershell
# Historique
git rev-list --count HEAD ; git shortlog -sne HEAD

# Inventaire par extension (fichiers suivis)
git ls-files | ForEach-Object { [IO.Path]::GetExtension($_) } | Group-Object | Sort-Object Count -Descending

# LOC Java main/test
git ls-files '*.java' | Group-Object { if($_ -match 'src/test/'){'test'}else{'main'} }

# Couverture (après ./mvnw test)
Select-String -Path kube-train-api/target/site/jacoco/jacoco.xml -Pattern '<counter type="LINE"'
```

*Chiffres calculés le 2026-07-10 depuis `git ls-files` (fichiers suivis), Maven Surefire et JaCoCo. Les lignes `.md`/`.java` incluent commentaires et lignes vides sauf mention « non vides ».*
