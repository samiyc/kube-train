# Template réponses — Examen Ouvert J1 (Sécurité K8s & RBAC)

> Copier ce fichier sous `reponse-examen-ouvert-J1.md` (hors git).  
> Répondre en phrases complètes avec les termes techniques. 3-5 lignes minimum par sous-question.  
> Indiquer les fichiers YAML consultés en fin de réponse si utilisés (ex: `[src: k8s/deployment.yaml]`).

---

## Question 1 — PSS : niveaux et modes ⭐

**a) Les trois paires mode/niveau et ce que chaque mode fait :**

(réponse libre)

**b) Pourquoi enforce=baseline et non restricted :**

(réponse libre)

**c) Effet concret d'audit/warn=restricted sans enforce=restricted :**

(réponse libre)

---

## Question 2 — ServiceAccounts : stratégie de découpage ⭐

**a) Les deux ServiceAccounts dédiés de kube-train :**

(réponse libre)

**b) Risque concret du partage via SA default — scénario :**

(réponse libre)

**c) Rôle du token SA monté par défaut, et pourquoi le désactiver :**

(réponse libre)

---

## Question 3 — LimitRange vs ResourceQuota ⭐

**a) Kind, niveau, rôle de chaque objet :**

(réponse libre)

**b) Différence defaultRequest vs default (limit) :**

(réponse libre)

**c) Complémentarité — ce qui passerait sans ResourceQuota :**

(réponse libre)

---

## Question 4 — securityContext : classification complète ⭐⭐

**a) Liste de tous les champs securityContext avec leur niveau :**

(réponse libre)

**b) Pourquoi fsGroup ne peut pas être container-level :**

(réponse libre)

**c) Niveau de seccompProfile dans ce deployment, cas de surcharge :**

(réponse libre)

---

## Question 5 — RBAC : verbes et resourceNames ⭐⭐

**a) Différence get vs list sur secrets :**

(réponse libre)

**b) Ce que l'absence de resourceNames autoriserait :**

(réponse libre)

**c) Résultat des trois commandes kubectl auth can-i :**

```
# Commande 1 — get secret/kube-train-secrets :
Résultat : [yes/no]
Justification :

# Commande 2 — get secret/autre-secret :
Résultat : [yes/no]
Justification :

# Commande 3 — list secrets :
Résultat : [yes/no]
Justification :
```

---

## Question 6 — Init container : états du pod ⭐⭐

**a) STATUS quand postgres est down :**

(réponse libre)

**b) STATUS après succès init container, Spring Boot démarre :**

(réponse libre)

**c) STATUS si l'image busybox:1.36 ne peut pas être pulled :**

(réponse libre)

**d) STATUS si init container OK mais Spring Boot crashe :**

(réponse libre)

---

## Question 7 — readOnlyRootFilesystem et volumes ⭐⭐

**a) Ce qu'un processus ne peut plus faire (2 exemples) :**

(réponse libre)

**b) Chaîne causale Spring Boot → /tmp → emptyDir :**

(réponse libre)

**c) Justification emptyDir vs hostPath vs PVC :**

(réponse libre)

---

## Question 8 — Calcul ResourceQuota et rolling update ⭐⭐⭐

**a) Calcul maxSurge=1 / maxUnavailable=1 / replicas→3 :**

```
Pods existants avant rolling : [X] + [X] + [X] = [total]
Pics pendant rolling :
  - Avant suppression premier ancien pod : [calcul]
  - Maximum simultané : [résultat]
Quota 6 suffisante ? [oui/non] — Justification :
```

**b) Recalcul avec maxSurge=1 / maxUnavailable=0 :**

```
Logique de remplacement :
  [calcul]
Maximum simultané : [résultat]
Conclusion :
```

**c) Vérification limite requests.cpu :**

```
3 pods api × 200m = [X]
+ postgres [X]m + node-exporter [X]m = [X]
Total requests.cpu = [X]
Plafond quota = 2 = 2000m
Respecté ? [oui/non]
```

---

## Question 9 — Deadlock sidecar / init container ⭐⭐⭐

**a) Mécanisme précis du deadlock :**

(réponse libre — décrire l'ordre de démarrage K8s et où le blocage se produit)

**b) Solution adoptée dans kube-train et pourquoi elle évite le deadlock :**

(réponse libre)

**c) Fonctionnalité K8s 1.28+, nom du champ YAML, changement d'ordre de démarrage :**

(réponse libre)

---

## Question 10 — Audit de sécurité RBAC ⭐⭐⭐

**a) Opérations nouvellement autorisées (exhaustif) :**

(réponse libre)

**b) Scénario d'attaque concret avec db-root-password :**

(réponse libre)

**c) Les deux commandes kubectl auth can-i les plus révélatrices :**

```bash
# Avant (configuration actuelle) :
kubectl auth can-i ...

# Après (configuration proposée) :
kubectl auth can-i ...
```

**d) Cas légitime où list sur les secrets serait justifié :**

(réponse libre)

---

*Score estimé : [X]/10 — à soumettre à correction*
