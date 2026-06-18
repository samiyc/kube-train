# Notes J5 — SRE en pratique : SLOs, Alertes & Gatekeeper

> Formation F4 — Platform Engineering  
> Objectif : implémenter SLOs, alertes burn rate et policy-as-code — passer de la compréhension à la pratique production.

---

## Glossaire — Acronymes J5

| Acronyme | Signification | Contexte |
|---|---|---|
| SRE | Site Reliability Engineering | Discipline Google : fiabilité comme problème d'ingénierie |
| SLO | Service Level Objective | Objectif de fiabilité mesurable (ex : disponibilité ≥ 99,9 % sur 30 jours) |
| SLI | Service Level Indicator | Métrique qui mesure la fiabilité (ex : taux de requêtes HTTP 2xx) |
| SLA | Service Level Agreement | Contrat externe avec pénalités — basé sur les SLOs |
| EB | Error Budget | Budget d'erreur = 100 % - SLO (ex: 0,1 % = 43 min/mois d'indispo autorisée) |
| MQL | Monitoring Query Language | Langage de requête Cloud Monitoring GCP pour les métriques et dashboards |
| OPA | Open Policy Agent | Moteur de politique générique (CNCF) — base de Gatekeeper K8s |
| CRD | Custom Resource Definition | Extension de l'API K8s — Gatekeeper ajoute ConstraintTemplate et Constraint |
| GKE | Google Kubernetes Engine | Service Kubernetes managé sur Google Cloud Platform |
| CI/CD | Continuous Integration / Continuous Delivery | Pipeline automatisé — les SLOs mesurent sa fiabilité en production |
| P95 | Percentile 95 | 95 % des requêtes sont traitées en moins de X ms (SLI de latence) |
| P99 | Percentile 99 | 99 % des requêtes sont traitées en moins de X ms (SLI de latence strict) |
| MTTR | Mean Time To Recovery | Temps moyen de rétablissement après un incident |
| MTBF | Mean Time Between Failures | Temps moyen entre deux incidents (inverse du taux de pannes) |

---

> A compléter lors de J5
