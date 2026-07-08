# Correction QCM J1 — Sécurité Kubernetes & RBAC
> Date : 04/06/2026 | Score : **8 / 8** (100 %)

---

## Question 1 — Pod Security Standards : niveaux ⭐

**Ta réponse : C ✅ CORRECT**

> `restricted` impose explicitement `runAsNonRoot: true`, `allowPrivilegeEscalation: false` et un `seccompProfile` non vide.

C'est le profil le plus strict. Rappel des 3 niveaux :

| Niveau | Ce qu'il impose |
|---|---|
| `privileged` | Rien — tout est autorisé |
| `baseline` | Bloque les escalades évidentes (hostNetwork, hostPID, capabilities dangereuses) |
| `restricted` | Tout baseline **+** runAsNonRoot, allowPrivilegeEscalation:false, seccompProfile, drop:ALL, volumes restreints |

**Mémo entretien** : `baseline` = "ne fais pas de bêtises" ; `restricted` = "hardening moderne complet".

---

## Question 2 — `securityContext` : pod vs container ⭐⭐

**Ta réponse : B ✅ CORRECT** *(vérifié sur les YAML — bonne démarche)*

> `allowPrivilegeEscalation` est défini au **niveau conteneur**, pas au niveau pod.

Règle de répartition :

| Champ | Niveau | Pourquoi |
|---|---|---|
| `fsGroup` | Pod uniquement | S'applique aux volumes montés par tous les conteneurs |
| `runAsUser` | Pod ou conteneur | Override possible par conteneur |
| `seccompProfile` | Pod ou conteneur | Override possible par conteneur |
| `allowPrivilegeEscalation` | **Conteneur uniquement** | Propriété du processus, pas du pod entier |
| `readOnlyRootFilesystem` | **Conteneur uniquement** | Propriété du filesystem du conteneur |
| `capabilities` | **Conteneur uniquement** | Par définition liées au processus |

Vérifier le YAML pour confirmer est exactement la bonne réflexe — les connaissances théoriques se consolident par la pratique.

---

## Question 3 — RBAC : verbs ⭐⭐

**Ta réponse : A ✅ CORRECT** *(vérifié sur les YAML — bonne démarche)*

> `resources: ["secrets"]`, `resourceNames: ["kube-train-secrets"]`, `verbs: ["get"]` — le plus restrictif.

C'est exactement ce qui est implémenté dans `k8s/security/rbac.yaml`. Décryptage des distracteurs :

| Option | Problème |
|---|---|
| B : `verbs: ["list"]` | `list` renvoie TOUS les secrets du namespace — trop large |
| C : `resources: ["*"]` | Accès à toutes les ressources — principe du moindre privilège violé |
| D : `verbs: ["watch"]` | `watch` écoute les changements en continu — inutile et trop large |

**Point clé** : `resourceNames` est le garde-fou ultime — même si quelqu'un crée un autre secret demain, `kube-train-api-sa` ne pourra pas le lire.

**Validation pratique** :
```bash
kubectl auth can-i get secret/kube-train-secrets --as=system:serviceaccount:default:kube-train-api-sa  # yes
kubectl auth can-i get secret/autre-secret --as=system:serviceaccount:default:kube-train-api-sa        # no
kubectl auth can-i list secrets --as=system:serviceaccount:default:kube-train-api-sa                   # no
```

---

## Question 4 — ServiceAccount dédié vs `default` ⭐

**Ta réponse : D ✅ CORRECT**

> Un SA dédié améliore l'isolation, l'audit et le principe du moindre privilège.

Les autres options sont des fausses croyances fréquentes :
- A : `default` fonctionne parfaitement avec un Deployment
- B : GKE Autopilot n'interdit pas `default`
- C : n'importe quel SA (y compris `default`) peut être utilisé avec des secrets via `secretRef`

**Conséquence concrète avec kube-train** : en utilisant `default`, si une autre app dans le namespace avait besoin d'un accès étendu, tous les pods y auraient accès. Le SA dédié isole l'identité.

---

## Question 5 — `LimitRange` vs `ResourceQuota` ⭐⭐

**Ta réponse : C ✅ CORRECT**

> `LimitRange` agit par objet ; `ResourceQuota` plafonne la consommation globale du namespace.

Décryptage des distracteurs :
- A : c'est `LimitRange` qui injecte les defaults, pas `ResourceQuota`
- B : c'est `ResourceQuota` qui limite le nombre total de pods, pas `LimitRange`
- D : les deux objets ont des rôles complémentaires et très différents

**Retour d'expérience J1** : ce point a été illustré concrètement — la `ResourceQuota` avec `pods: 4` a bloqué le rolling update car le namespace `default` contenait déjà la stack monitoring (~9 pods). La valeur a été portée à 6 pour laisser de la marge. En prod, chaque namespace aurait son propre quota dimensionné à sa charge réelle.

---

## Question 6 — Init container : comportement ⭐⭐

**Ta réponse : C ✅ CORRECT**

> Le Pod reste bloqué sur l'init container — l'application ne démarre pas tant qu'il ne réussit pas.

**Confirmation par la pratique** : le init container `wait-for-postgres` a fonctionné exactement comme décrit — le pod a attendu que `postgres-service:5432` réponde avant de laisser démarrer Spring Boot.

```
Pod lifecycle avec init container :
Pending → Init:0/1 (boucle tant que postgres est down)
         → PodInitializing (init OK)
         → Running (conteneur principal démarre)
```

Les autres options sont des comportements inventés — Kubernetes ne "timeout" pas un init container ni ne le démarre après 3 essais.

**Rappel piège GKE** : ne jamais faire attendre `127.0.0.1:5432` dans un init container si le Cloud SQL Proxy est un sidecar du même pod → deadlock (le sidecar ne démarre qu'après tous les init containers).

---

## Question 7 — RoleBinding : portée ⭐⭐⭐

**Ta réponse : A ✅ CORRECT**

> Un `RoleBinding` dans le namespace `kube-train` qui référence un `ClusterRole` → permissions **uniquement** dans `kube-train`.

C'est le **piège classique d'entretien CKAD** le plus fréquent sur RBAC. La règle :

| Objet | Scope des permissions |
|---|---|
| `RoleBinding` → `Role` | Namespace du RoleBinding |
| `RoleBinding` → `ClusterRole` | **Namespace du RoleBinding** (pas le cluster entier !) |
| `ClusterRoleBinding` → `ClusterRole` | Cluster entier |

Un `ClusterRole` peut être réutilisé dans plusieurs namespaces via des `RoleBinding` distincts — c'est même son usage le plus courant (éviter de dupliquer les règles).

**Mémo entretien** : *"C'est le **RoleBinding** qui détermine la portée, pas le ClusterRole qu'il référence."*

---

## Question 8 — Capabilities Linux ⭐⭐

**Ta réponse : B ✅ CORRECT**

> `capabilities.drop: [ALL]` retire les capabilities Linux non nécessaires. Sur un service écoutant en 8080 on n'a rien à rajouter.

Les capabilities Linux sont des sous-privilèges du root fragmentés. Un processus Java qui écoute en 8080 n'a besoin d'aucune capability :
- Pas de `CAP_NET_BIND_SERVICE` (port > 1024)
- Pas de `CAP_SYS_ADMIN`, `CAP_CHOWN`, `CAP_SETUID`...

Décryptage des distracteurs :
- A : `drop: [ALL]` fait l'inverse — retire les capabilities au lieu d'en ajouter
- C : `seccompProfile` filtre les syscalls ; `capabilities` filtre les privilèges — complémentaires, pas équivalents
- D : les probes HTTP de Kubernetes (kubelet) font des connexions TCP depuis l'hôte — `capabilities` du conteneur ne les affecte pas

---

## Score final

| Question | Sujet | Niveau | Résultat | Points |
|---|---|---|---|---|
| Q1 | PSS niveaux | ⭐ | ✅ | 1 / 1 |
| Q2 | securityContext pod vs container | ⭐⭐ | ✅ | 1 / 1 |
| Q3 | RBAC verbs + resourceNames | ⭐⭐ | ✅ | 1 / 1 |
| Q4 | ServiceAccount dédié | ⭐ | ✅ | 1 / 1 |
| Q5 | LimitRange vs ResourceQuota | ⭐⭐ | ✅ | 1 / 1 |
| Q6 | Init container comportement | ⭐⭐ | ✅ | 1 / 1 |
| Q7 | RoleBinding portée | ⭐⭐⭐ | ✅ | 1 / 1 |
| Q8 | Capabilities Linux | ⭐⭐ | ✅ | 1 / 1 |
| **TOTAL** | | | | **8 / 8 (100 %)** |

### Points forts
- Maîtrise solide des 3 niveaux PSS et de leur différence pratique
- Bonne distinction pod-level vs container-level (validée sur les YAML réels)
- Principe du moindre privilège bien intégré (Q3 : resourceNames, Q4 : SA dédié)
- Piège classique CKAD Q7 (RoleBinding + ClusterRole = namespace-scoped) correctement identifié
- Compréhension concrète du comportement des init containers (validée en pratique)

### Pour aller plus loin avant entretien CKAD
- Savoir écrire de mémoire un `securityContext` complet PSS restricted (exercice : écrire sans regarder)
- Mémoriser les verbes RBAC : `get` vs `list` vs `watch` et quand choisir lequel
- Pratiquer `kubectl auth can-i --as=system:serviceaccount:ns:sa-name` — question fréquente en examen
- Retenir : **`RoleBinding` détermine la portée**, pas le ClusterRole référencé
