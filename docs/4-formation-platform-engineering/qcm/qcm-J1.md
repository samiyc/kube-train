# QCM J1 — Sécurité Kubernetes & RBAC

**8 questions — Durée estimée : 10-15 min**

---

## Question 1 — Pod Security Standards : niveaux

Quel profil PSS impose explicitement `runAsNonRoot: true`, `allowPrivilegeEscalation: false` et un `seccompProfile` non vide ?

A) `privileged`
B) `baseline`
C) `restricted`
D) Aucun, ce sont seulement des bonnes pratiques non vérifiées par Kubernetes

---

## Question 2 — `securityContext` : pod vs container

Dans le manifest de `kube-train-api`, quel champ est typiquement défini au **niveau conteneur** et non au niveau pod ?

A) `fsGroup`
B) `allowPrivilegeEscalation`
C) `runAsUser`
D) `seccompProfile`

---

## Question 3 — RBAC : verbs

Tu veux autoriser `kube-train-api-sa` à lire uniquement le secret `kube-train-secrets` sans pouvoir lister tous les secrets du namespace. Quelle combinaison est la plus adaptée ?

A) `resources: ["secrets"]`, `resourceNames: ["kube-train-secrets"]`, `verbs: ["get"]`
B) `resources: ["secrets"]`, `verbs: ["list"]`
C) `resources: ["*"]`, `verbs: ["get"]`
D) `resources: ["secrets"]`, `verbs: ["watch"]`

---

## Question 4 — ServiceAccount dédié vs `default`

Pourquoi créer un ServiceAccount `kube-train-api-sa` au lieu d'utiliser `default` ?

A) Parce que `default` ne fonctionne pas avec un Deployment
B) Parce que GKE interdit `default` en mode Autopilot
C) Parce que seul un ServiceAccount dédié peut être utilisé avec un secret
D) Parce qu'un SA dédié améliore l'isolation, l'audit et le principe du moindre privilège

---

## Question 5 — `LimitRange` vs `ResourceQuota`

Quelle affirmation est correcte ?

A) `ResourceQuota` injecte automatiquement les `requests`/`limits` manquants dans un Pod
B) `LimitRange` limite le nombre total de pods dans un namespace
C) `LimitRange` agit sur les contraintes/defaults par objet ; `ResourceQuota` plafonne la consommation globale du namespace
D) Les deux objets sont équivalents, seul le nom change

---

## Question 6 — Init container : comportement

Que se passe-t-il si l'init container `wait-for-postgres` échoue en boucle ?

A) Le conteneur principal démarre quand même après le timeout de la probe
B) Kubernetes ignore l'init container après 3 tentatives
C) Le Pod reste bloqué sur l'init container et l'application ne démarre pas tant qu'il ne réussit pas
D) Le Pod passe automatiquement en `Completed`

---

## Question 7 — RoleBinding : portée

Tu crées un `RoleBinding` dans le namespace `kube-train` qui référence un `ClusterRole`. Quelle est la portée réelle des permissions accordées ?

A) Uniquement le namespace `kube-train`
B) Tous les namespaces du cluster
C) Tous les namespaces, sauf `kube-system`
D) Le cluster entier seulement si le sujet est un ServiceAccount

---

## Question 8 — Capabilities Linux

Dans une posture `restricted`, pourquoi mettre `capabilities.drop: [ALL]` sur `kube-train-api` est pertinent ?

A) Parce que cela force automatiquement le conteneur à tourner en root
B) Parce que cela retire les capabilities Linux non nécessaires ; sur un service écoutant en 8080 on n'a généralement rien à rajouter
C) Parce que cela remplace complètement `seccompProfile`
D) Parce que cela empêche Kubernetes de faire des probes HTTP

---

