# Examen Ouvert — Sécurité Kubernetes & RBAC (F4-J1)

> **Format** : questions ouvertes — reformuler avec les termes techniques appropriés.  
> **Ressources autorisées** : `k8s/*.yaml`, `notes-J1.md`, `cheat-sheet-securite-k8s.md`, `runbook.md`  
> **Objectif** : 6-8 / 10 — les questions avancées ont vocation à générer des corrections instructives.  
> **Réponses** : copier `corrections/template-reponse-examen-ouvert-J1.md`, compléter, soumettre à correction.

---

## Questions ⭐ — Fondamentaux

### Question 1 — PSS : niveaux et modes ⭐

En lisant `k8s/security/namespace-pss.yaml` :

a) Le namespace `default` utilise trois paires `mode/niveau`. Citer chaque paire et expliquer en une phrase ce que chaque **mode** fait concrètement (ce que K8s fait quand une violation est détectée).

b) Pourquoi `enforce` est configuré à `baseline` et non `restricted` — alors que `audit/warn` sont à `restricted` ? Quel compromis cela exprime-t-il ?

c) Quel est l'effet concret d'avoir `audit: restricted` et `warn: restricted` sans `enforce: restricted` sur un pod qui viole `restricted` mais respecte `baseline` ?

---

### Question 2 — ServiceAccounts : stratégie de découpage ⭐

a) Nommer les deux ServiceAccounts dédiés utilisés dans kube-train et indiquer à quel workload chacun correspond.

b) Expliquer concrètement le risque si l'API Spring Boot et le service notification partageaient le SA `default` — illustrer avec un scénario où ce partage pose un problème de sécurité réel.

c) Dans `k8s/workloads/deployment.yaml`, la ligne `automountServiceAccountToken: false` est présente. À quoi sert le token de SA que K8s monte par défaut, et pourquoi le désactiver ici est une bonne pratique ?

---

### Question 3 — LimitRange vs ResourceQuota ⭐

En lisant `k8s/security/quota.yaml`, deux objets sont définis côte à côte.

a) Pour chaque objet : citer son `kind`, préciser le niveau auquel il s'applique (Container / Pod / Namespace), et résumer son rôle en une phrase.

b) Dans le `LimitRange`, quelle est la différence entre `defaultRequest` et `default` (limit) ? Que se passe-t-il pour un container qui ne déclare pas de `resources:` dans son manifest ?

c) Pourquoi ces deux objets sont-ils **complémentaires** plutôt que redondants ? Donner un exemple de ce qui "passerait entre les mailles" si on n'avait que le `LimitRange` sans `ResourceQuota`.

---

## Questions ⭐⭐ — Maîtrise

### Question 4 — securityContext : classification complète ⭐⭐

En lisant `k8s/workloads/deployment.yaml` :

a) Lister **tous** les champs `securityContext` présents (pod-level ET container-level). Pour chacun, préciser son niveau.

b) `fsGroup: 1000` est défini au niveau pod. Expliquer pourquoi ce champ **ne peut pas** être défini au niveau container (réponse technique attendue — qu'est-ce que fsGroup affecte qui rend le niveau container incohérent ?).

c) `seccompProfile` peut être défini aux deux niveaux (pod et container). Dans ce deployment, à quel niveau est-il défini ? Dans quel cas voudrait-on le surcharger au niveau container ?

---

### Question 5 — RBAC : verbes et resourceNames ⭐⭐

En lisant `k8s/security/rbac.yaml` :

a) Expliquer la différence concrète entre `get` et `list` sur la ressource `secrets`. Pourquoi `list` serait-il dangereux sur ce namespace ?

b) Sans la ligne `resourceNames: ["kube-train-secrets"]`, que peut faire `kube-train-api-sa` que la configuration actuelle interdit ? Être précis sur le périmètre.

c) Prédire le résultat des trois commandes suivantes (répondre par `yes` ou `no` et justifier) :
```bash
kubectl auth can-i get secret/kube-train-secrets \
  --as=system:serviceaccount:default:kube-train-api-sa

kubectl auth can-i get secret/autre-secret \
  --as=system:serviceaccount:default:kube-train-api-sa

kubectl auth can-i list secrets \
  --as=system:serviceaccount:default:kube-train-api-sa
```

---

### Question 6 — Init container : états du pod ⭐⭐

En lisant `k8s/workloads/deployment.yaml`, l'init container `wait-for-postgres` attend que `postgres-service:5432` réponde.

Décrire précisément la valeur de `STATUS` visible dans `kubectl get pods` pour chacun des scénarios suivants :

a) Le pod vient d'être schedulé, PostgreSQL est **down** (service inexistant).

b) PostgreSQL répond, l'init container se termine avec exit code 0 ; Spring Boot est en cours de démarrage.

c) L'image `busybox:1.36` n'est pas disponible sur le registry (pull impossible).

d) L'init container se termine avec exit code 0, mais Spring Boot crashe au démarrage (ex : secret manquant).

---

### Question 7 — readOnlyRootFilesystem et volumes ⭐⭐

En lisant `k8s/workloads/deployment.yaml` :

a) `readOnlyRootFilesystem: true` est activé. Décrire concrètement ce qu'un processus dans le container **ne peut plus faire** (donner 2 exemples d'opérations bloquées).

b) Un volume `emptyDir` est monté sur `/tmp` et la variable `JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/tmp` est injectée. Expliquer la chaîne causale : pourquoi Spring Boot a-t-il besoin d'un répertoire `/tmp` accessible en écriture, et pourquoi le filesystem racine seul ne suffit-il pas ?

c) Justifier le choix de `emptyDir` pour ce volume — en quoi est-il plus adapté que `hostPath` ou `persistentVolumeClaim` pour ce cas d'usage ?

---

## Questions ⭐⭐⭐ — Avancé

### Question 8 — Calcul ResourceQuota et rolling update ⭐⭐⭐

Contexte : namespace `default` sur Minikube.
- Quota actuel : `pods: "6"` (dans `quota.yaml`)
- Pods existants : 1 node-exporter (DaemonSet, ne peut pas être mis à 0), 1 postgres
- Deployment kube-train-api : `replicas: 2`

On veut passer à `replicas: 3`.

a) Avec la stratégie RollingUpdate et ses valeurs par défaut (`maxSurge: 25%` → arrondi à 1, `maxUnavailable: 25%` → arrondi à 1), calculer le **nombre maximum de pods simultanés** dans le namespace pendant le rolling update. La quota de 6 est-elle suffisante ?

b) Si on utilise `maxSurge: 1, maxUnavailable: 0` (stratégie "sans interruption"), recalculer le maximum. Conclusion ?

c) La ResourceQuota actuelle contient aussi `requests.cpu: "2"`. Si chaque pod api-container a `requests.cpu: 200m`, est-ce que passer à 3 replicas respecte ce plafond (en comptant postgres et node-exporter avec 100m chacun) ?

---

### Question 9 — Deadlock sidecar / init container ⭐⭐⭐

Le commentaire dans `k8s/workloads/deployment.yaml` indique :
> `⚠️ Pattern Minikube uniquement — Sur GKE, ne pas attendre 127.0.0.1:5432 (cloud-sql-proxy est un sidecar → deadlock)`

a) Expliquer **précisément** le mécanisme du deadlock qui se produirait si l'init container faisait `until nc -z 127.0.0.1 5432` ET que Cloud SQL Auth Proxy était déclaré dans `spec.containers[]` du même pod. Décrire l'ordre de démarrage que K8s applique.

b) Sur GKE, que fait l'init container `wait-for-postgres` à la place, et pourquoi cette approche évite-t-elle le deadlock ? (lire le manifest GKE si nécessaire — ou raisonner à partir du concept)

c) Kubernetes 1.28 a introduit une fonctionnalité pour résoudre ce problème nativement. Nommer le champ YAML à ajouter à un init container pour en faire un "sidecar natif", et expliquer comment il change l'ordre de démarrage.

---

### Question 10 — Audit de sécurité RBAC ⭐⭐⭐

Un développeur propose de remplacer le contenu de `k8s/security/rbac.yaml` par :
```yaml
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list", "watch"]
```
*(sans `resourceNames`)*

a) Lister précisément les opérations que `kube-train-api-sa` peut désormais effectuer que **la configuration actuelle interdit**. Être exhaustif.

b) Une autre équipe crée un secret `db-root-password` dans le namespace `default`. Décrire un scénario d'attaque concret : comment un attaquant qui compromet le pod kube-train-api peut exploiter cette configuration pour élever ses privilèges ou exfiltrer des données.

c) Proposer les deux commandes `kubectl auth can-i` les plus révélatrices pour démontrer la différence avant/après ce changement.

d) Dans quel cas `list` sur les secrets serait-il techniquement légitime pour une application ? (donner un exemple d'architecture réelle où c'est nécessaire)
