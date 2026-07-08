# Examen ouvert F5-J1 — Core workloads & vitesse kubectl

> Répondre en phrases complètes. 3-5 lignes min par sous-question. Verbaliser le "pourquoi".
> *(Stub — questions à finaliser/étendre le jour du drill ; répondre dans une copie `reponse-examen-ouvert-J1.md`.)*

---

## Question 1 — Impératif vs déclaratif ⭐

**a) Quand privilégier l'impératif (`k create ... $do`) vs un manifeste versionné :**

(réponse libre)

**b) Pourquoi `--dry-run=client -o yaml` est le réflexe #1 à l'examen :**

(réponse libre)

**c) Que fait exactement `--dry-run=client` vs `--dry-run=server` :**

(réponse libre)

---

## Question 2 — Cycle de vie & ownership ⭐

**a) Chaîne Deployment → ReplicaSet → Pod et rôle des `ownerReferences` :**

(réponse libre)

**b) Ce qui se passe si on supprime le ReplicaSet d'un Deployment :**

(réponse libre)

**c) Différence `k delete pod` vs `k scale --replicas=0` :**

(réponse libre)

---

## Question 3 — Labels & selectors ⭐⭐

**a) Rôle du `selector.matchLabels` d'un Deployment (et l'erreur si mismatch avec le template) :**

(réponse libre)

**b) Sélection multi-critères avec `-l 'env=prod,tier!=db'` :**

(réponse libre)

**c) Lien avec le routage d'un Service (selector → endpoints) :**

(réponse libre)

---

## Question 4 — Vitesse & open-book ⭐⭐

**a) Comment retrouver un champ oublié sans quitter le terminal (`k explain`) — exemple concret :**

(réponse libre)

**b) Stratégie de temps si une tâche dépasse 8 min :**

(réponse libre)

---

*Score estimé : [X]/10 — à soumettre à correction.*
