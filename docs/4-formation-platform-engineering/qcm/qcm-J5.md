# QCM J5 — SRE — SLOs, Error Budgets & Alertes

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — [SLI vs SLO vs SLA en implémentation]
Dans un contexte SRE appliqué à kube-train, quel énoncé est correct ?

A) Le SLI est la cible contractuelle signée avec le client, le SLO est la métrique brute, le SLA est facultatif.
B) Le SLI est la mesure technique observée (ex. ratio 2xx de `GET /trains`), le SLO est la cible interne configurée dans Cloud Monitoring, et le SLA est un engagement externe porté par le métier ou le contrat.
C) Le SLO appartient toujours au client final, alors que le SLA est piloté par l'équipe SRE uniquement.
D) Le SLI et le SLO sont deux noms différents pour la même ressource API.

---

## Question 2 — [Calcul d'error budget]
Sur 30 jours, `GET /trains` a un SLO de 99%. Si le service traite 1 000 000 requêtes sur la période, quel est l'error budget maximal ?

A) 1 000 requêtes en erreur
B) 100 000 requêtes en erreur
C) 10 000 requêtes en erreur
D) 990 000 requêtes en erreur

---

## Question 3 — [Burn rate alerting]
Pourquoi une alerte burn rate `14.4×` sur 1 heure est-elle considérée comme un signal de "fast burn" utile ?

A) Parce qu'elle correspond à une dérive lente tolérable, destinée uniquement aux rapports mensuels.
B) Parce qu'elle indique que le budget d'erreur est consommé beaucoup trop vite sur une courte fenêtre, ce qui permet de détecter rapidement une régression sévère.
C) Parce qu'elle remplace les SLOs et rend inutile tout dashboard.
D) Parce qu'elle mesure uniquement la saturation CPU des nodes GKE.

---

## Question 4 — [Request-based vs window-based]
Quel choix est le plus pertinent pour implémenter une exigence du type « le P95 de `POST /reservations` doit rester < 300 ms » ?

A) Un SLO window-based, où chaque fenêtre est marquée good/bad selon le respect du seuil de latence sur la période.
B) Un SLO request-based limité aux codes HTTP 2xx, sans notion de latence.
C) Un simple uptime check synthétique exécuté toutes les 24 heures.
D) Une `AuthorizationPolicy` Istio sur `/reservations`.

---

## Question 5 — [Golden signals]
Parmi les ensembles suivants, lequel correspond aux golden signals à afficher sur un dashboard SRE kube-train ?

A) Build time, taille d'image Docker, nombre de commits, couverture JaCoCo
B) Traffic, errors, latency, saturation
C) CPU, mémoire, disque, nombre de PR GitHub
D) Cost, carbon footprint, nombre de topics Pub/Sub, nombre de pods

---

## Question 6 — [Cloud Monitoring SLO API]
Quelle différence est correcte entre `rollingPeriod` et `calendarPeriod` dans l'API SLO de Cloud Monitoring ?

A) `rollingPeriod` recalcule en fenêtre glissante continue (ex. 30 jours), alors que `calendarPeriod` se réinitialise sur une borne calendaire fixe (jour, semaine, mois).
B) `rollingPeriod` ne fonctionne qu'avec les services GKE, `calendarPeriod` seulement avec Cloud Run.
C) `rollingPeriod` est réservé aux dashboards, `calendarPeriod` aux alertes.
D) Les deux champs sont synonymes ; seul le nom change selon la version de l'API.

---

## Question 7 — [OPA / Gatekeeper]
Dans Gatekeeper, quel couple joue le rôle « modèle de règle » puis « instance appliquée avec paramètres » ?

A) `PeerAuthentication` puis `AuthorizationPolicy`
B) `ConstraintTemplate` puis `Constraint`
C) `CustomResourceDefinition` puis `ConfigMap`
D) `Namespace` puis `LimitRange`

---

## Question 8 — [Alert fatigue vs burn rate]
Pourquoi les alertes burn rate sont-elles souvent préférées à de simples seuils statiques du type « taux d'erreur > 2% pendant 5 min » ?

A) Parce qu'elles déclenchent plus souvent, ce qui maximise la vigilance de l'équipe.
B) Parce qu'elles ignorent complètement la notion de budget d'erreur.
C) Parce qu'elles relient le bruit d'alerte à l'impact réel sur le SLO et distinguent mieux une dégradation grave d'un micro-pic transitoire.
D) Parce qu'elles ne nécessitent aucune métrique applicative.

