# Comparatif : Terraform vs Ansible

> Contexte : choix d'outils IaC pour un profil cloud-native GCP/Kubernetes.

---

## Résumé en une phrase

- **Terraform** = provisionner l'infrastructure (créer des ressources cloud)
- **Ansible** = configurer ce qui tourne dans l'infrastructure (installer, paramétrer)

---

## Tableau comparatif

| Critère | Terraform | Ansible |
|---------|-----------|---------|
| **Type** | IaC déclaratif (infrastructure) | Configuration management + automation |
| **Quoi** | Crée/détruit des ressources cloud (GKE, Cloud SQL, VPC, IAM…) | Configure les machines (packages, fichiers, services, users) |
| **Modèle** | Déclaratif (tu décris l'état final souhaité) | Impératif/procédural (playbooks = séquence de tâches) |
| **State** | Fichier `.tfstate` (sait ce qui existe, détecte le drift) | Stateless (rejoue tout à chaque run, idempotent par convention) |
| **Langage** | HCL (HashiCorp Configuration Language) | YAML (playbooks) + Jinja2 (templates) |
| **Agent** | Sans agent (appels API directs) | Sans agent (SSH/WinRM) |
| **Quand** | Avant le déploiement (provisionner l'infra) | Après le provisioning (configurer les serveurs) |
| **GCP** | Provider officiel `hashicorp/google` — excellente couverture | Modules GCP existent mais moins naturels |
| **K8s** | Bon pour créer le cluster, pas pour gérer les workloads | Peut déployer des manifests K8s, mais Helm/ArgoCD sont préférés |
| **Idempotence** | Native (plan → apply, ne refait que le delta) | Par convention (modules idempotents, mais pas garanti) |
| **Écosystème** | Terraform Cloud, Atlantis, Spacelift | Ansible Tower/AWX, Ansible Galaxy |
| **Certifications** | HashiCorp Certified: Terraform Associate | Red Hat Certified: Ansible Automation |

---

## Quand utiliser quoi ?

### Terraform (✅ recommandé pour kube-train / GCP)

- Provisionner un cluster GKE
- Créer une instance Cloud SQL
- Configurer IAM, Workload Identity, VPC
- Gérer Artifact Registry, Pub/Sub, Secret Manager
- Pipeline infra : PR → `terraform plan` → merge → `terraform apply`

### Ansible (pertinent mais pas prioritaire ici)

- Configurer des VMs bare-metal ou on-premise
- Installer des outils sur des serveurs (Nginx, PostgreSQL, monitoring agents)
- Orchestrer des migrations multi-serveurs
- Gérer des flottes de serveurs (patching, compliance)

### Les deux ensemble (dans une mission client type)

```
Terraform                          Ansible
    │                                  │
    ▼                                  ▼
Crée la VM/cluster GKE         Configure la VM (si pas K8s)
Crée le réseau VPC             Installe les packages
Crée le load balancer          Déploie les configs applicatives
```

---

## Verdict pour un profil cloud-native GCP + K8s

| Contexte | Choix |
|----------|-------|
| GKE Autopilot (pods immutables) | **Terraform uniquement** — Ansible n'a rien à configurer |
| GKE Standard (nodes gérés par toi) | Terraform pour l'infra + Ansible possible pour les node pools custom |
| VMs Compute Engine (pas K8s) | Terraform pour créer + **Ansible pour configurer** |
| Hybrid cloud (on-prem + GCP) | Les deux sont complémentaires |
| Mission consulting ESN | Terraform très demandé (100%), Ansible souvent requis (~60% des offres) |

---

## ROI pour les certifications

| Certif | Terraform | Ansible |
|--------|-----------|---------|
| CKAD | ❌ Non testé | ❌ Non testé |
| GCP Professional DevOps Engineer | ✅ IaC est un domaine de l'examen | ❌ Pas dans le scope |
| GCP Professional Cloud Architect | ✅ Attendu comme compétence | ⚠️ Mentionné mais pas central |
| HashiCorp Terraform Associate | ✅ Directement l'examen | — |
| Red Hat Ansible Automation | — | ✅ Directement l'examen |

---

## Conclusion

Pour la Formation F4 (kube-train sur GKE), **Terraform est prioritaire** car :
1. Il couvre la totalité de l'infra GCP
2. Il est explicitement dans le scope de la certif GCP DevOps Engineer
3. Avec K8s/Autopilot, il n'y a pas de serveurs à configurer → Ansible n'a pas de cible

Ansible reste un bon investissement pour le futur (missions hybrid cloud, gestion de flottes VM), mais en F4 on se concentre sur Terraform.
