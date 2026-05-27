# 🎓 QCM J3 — ArgoCD, GitOps & Déploiement Pull-Based

> 8 questions couvrant les sujets de J3.
> Niveaux : ⭐ (basique), ⭐⭐ (intermédiaire), ⭐⭐⭐ (avancé/entretien)
>
> Répondre dans le fichier `template-reponse-qcm.md` (copie en `reponse-qcm-J3.md`).

---

## GitOps — Concepts fondamentaux

### Question 1 — Push vs Pull deployment ⭐

Dans kube-train, on est passé d'un modèle **Push** (CI fait `kubectl apply`) à un modèle **Pull** (ArgoCD).

A) Explique la différence fondamentale entre les deux approches  
B) Cite deux problèmes de sécurité du modèle Push  
C) Dans notre projet, pourquoi garde-t-on les deux en parallèle (hybride) ?

---

### Question 2 — Source de vérité ⭐

Un collègue fait un `kubectl edit deployment/kube-train-deployment` directement sur le cluster GKE pour changer une variable d'environnement en urgence.

A) Que se passe-t-il avec ArgoCD (selfHeal activé) ?  
B) Quelle est la bonne procédure GitOps pour faire ce changement de façon pérenne ?  
C) Combien de temps maximum avant qu'ArgoCD ne détecte le drift (config par défaut) ?

---

## ArgoCD — Architecture & Configuration

### Question 3 — Composants ArgoCD ⭐⭐

Parmi les composants ArgoCD suivants, lequel est **indispensable** au fonctionnement du sync (les autres sont optionnels ou annexes) ?

- A) `argocd-server`
- B) `argocd-application-controller`
- C) `argocd-dex-server`
- D) `argocd-applicationset-controller`

Justifie ta réponse et explique brièvement le rôle de chacun.

---

### Question 4 — syncPolicy et prune ⭐⭐

Dans notre `application.yaml`, on a :

```yaml
syncPolicy:
  automated:
    selfHeal: true
    prune: true
```

A) Que signifie `prune: true` ? Donne un exemple concret avec notre projet.  
B) Pourquoi `prune: true` peut être dangereux en production ? Quel garde-fou existe ?  
C) Sans `automated`, que doit-on faire manuellement pour déclencher un sync ?

---

## Pipeline CI/CD & GitOps

### Question 5 — La boucle infinie ⭐⭐

Le job `update-manifests` de notre CI commit les nouveaux tags d'image dans les fichiers deployment YAML. Sans précaution, cela crée une boucle infinie.

A) Explique le mécanisme exact de la boucle (étape par étape)  
B) Quelle est la solution implémentée dans notre workflow ? (cite le mécanisme précis)  
C) Pourquoi `[skip ci]` dans le message de commit n'est pas suffisant seul ?

---

### Question 6 — Rollback GitOps ⭐⭐⭐

Scénario : un développeur push un commit qui casse l'application (OOMKilled au démarrage). Le pod restart en boucle (CrashLoopBackOff).

A) Comment faire un rollback avec ArgoCD + GitOps ? (procédure exacte)  
B) Pourquoi cette approche est plus fiable qu'un `kubectl rollout undo` ?  
C) ArgoCD propose aussi un bouton "Rollback" dans l'UI — quelle est la différence avec un `git revert` et pourquoi est-ce moins recommandé en GitOps strict ?

---

## Cas pratiques kube-train

### Question 7 — Fichiers exclus et include/exclude ⭐⭐

Notre Application ArgoCD utilise un pattern `exclude` pour éviter de synchroniser certains fichiers du dossier `k8s/`.

A) Pourquoi exclut-on `deployment.yaml` (sans le `-gke`) ?  
B) Pourquoi exclut-on `argocd/**` ?  
C) Si demain on ajoute un fichier `k8s/network-policy-deny.yaml`, ArgoCD le déploiera-t-il automatiquement ? Pourquoi ?

---

### Question 8 — Self-heal vs HPA ⭐⭐⭐

On a testé le self-heal en faisant `kubectl scale deployment/kube-train-deployment --replicas=2`. ArgoCD a remis 1 replica.

A) Si un HPA (Horizontal Pod Autoscaler) est actif sur le même Deployment et décide de scaler à 3 pods, ArgoCD va-t-il combattre le HPA ? Explique le comportement attendu.  
B) Quelle configuration ArgoCD permettrait d'ignorer le champ `spec.replicas` pour laisser le HPA gérer ?  
C) En entreprise, cite un autre exemple de conflit similaire (un controller K8s qui modifie un champ qu'ArgoCD veut aussi gérer).

---