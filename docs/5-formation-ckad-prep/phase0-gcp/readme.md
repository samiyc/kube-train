# Phase 0 — GCP-first (avant fin d'essai)

> **Hors périmètre CKAD** (qui reste 100 % local, J1-J8 en août). Cette phase exploite les
> crédits d'essai GCP restants pour capter les services managés à fort ROI — utiles à la mission
> et à la future certif **GCP Professional Cloud DevOps Engineer**.

---

## 🎯 Principe

- **Approche ELI5** : chaque lab part d'une analogie simple, puis mappe vers le vrai vocabulaire
  GCP. On cherche à *comprendre*, pas à copier des commandes.
- **Time-boxé** : chaque lab ≤ 1 demi-journée.
- **Discipline budget** : ≤ 5 €/jour, `terraform destroy` en fin de session, Cloud SQL stoppée.
- **Priorité aux labs qui exigent le cluster** tant qu'il est UP (il coûte, autant l'amortir).
- **Provisioning en Terraform**, un state jetable par lab (cf. `infra/labs/README.md`).

### Livrables

- **Un `lab-*.md` auto-suffisant par lab** : ELI5 → vocabulaire → ce qu'on construit →
  Terraform (provisioning) → **exploitation** (`gcloud`) → ce que ça prouve → coût → cleanup.
- **`qcm-phase0.md`** en fin de phase (+ `corrections/`) pour ancrer tout le vocabulaire GCP acquis.

> **Pas de runbook séparé** : Terraform absorbe le provisioning (workflow identique pour tous les
> labs → `infra/labs/README.md`), et l'exploitation (`gcloud deploy` &co) vit dans la note du lab
> concerné. Un runbook transverse ne ferait que dupliquer.

---

## 📅 Ordre recommandé

| # | Lab | Pourquoi | Cluster requis | Effort | Statut |
|---|-----|----------|:---:|--------|--------|
| 1 | **Cloud Deploy** | Service signature du GCP DevOps Engineer : delivery pipeline managé, promotion dev→prod, rollback. Le plus gros ROI certif. | ✅ | Moyen | ✅ **Fait 16/07** — [note](lab-cloud-deploy.md) |
| 2 | **Binary Authorization** | Supply chain security : n'autoriser que des images attestées. Prolonge Gatekeeper (F4-J5). ⚠️ S'active au niveau **cluster** → arbitrage d'isolation à trancher. | ✅ | Moyen | ⬜ |
| 3 | **Cloud Profiler** | Le complément manquant de l'observabilité (Trace déjà acquis en F4). Touche l'app (agent in-process). | ✅ | Faible | 🚧 [note](lab-cloud-profiler.md) |
| 4 | **SLO / alerte / dashboard as-code** | Passe les SLO F4-J5 (créés en REST) en Terraform → reproductibles. Comble la dernière dette F4. | ✅ | Moyen | ⬜ |
| 5 | **GKE Cost insights / Recommender** | FinOps : lire la conso, dimensionner. Rapide. | ✅ | Faible | ⬜ |
| 6 | **Gemini Cloud Assist** | Productivité ops, sujet 2026 du GCP Developer. | ➖ | Faible | ⬜ |
| 7 | **Cloud Build triggers** | Alternative managée à GitHub Actions — **valeur marginale faible** (pipeline GH Actions déjà en place et maîtrisé). À faire seulement s'il reste du temps. | ➖ | Faible | ⬜ |

---

## 💰 Contexte

- Essai GCP : quelques jours restants, budget confortable (~72 € au 15/07).
- **Départ en vacances le 24/07 au soir** + fin d'essai GCP.
- `terraform destroy` + stop Cloud SQL **le vendredi soir** avant le week-end.
- Reprise en **août : CKAD local-first sur Minikube** (J1-J8), coût 0 €.

## 🧹 Cleanup fin de session

```bash
cd /mnt/c/DEVDIR/GITHUB/kube-train/infra && terraform destroy
gcloud sql instances patch kube-train-db --activation-policy=NEVER --project=kube-train-project
```

> `infra/bootstrap/` (SA GitHub, WIF, secrets) n'est **jamais** détruit — c'est ce qui permet de
> tout reconstruire d'un `terraform apply` + `gcloud container clusters get-credentials`.

---

## 📎 Références

- Plan F5 : `../formation-ckad-prep-plan.md` (§ Phase 0)
- Rebuild E2E + pièges : `../../4-formation-platform-engineering/extra/terraform-e2e-rebuild-runbook.md`
- Observabilité / trace E2E : `../../4-formation-platform-engineering/extra/trace-e2e-outbox-propagation.md`
- Roadmap certifs : `../../3-formation-cloud-native-beyond/extra/roadmap-certifications-ckad-gcp.md`
