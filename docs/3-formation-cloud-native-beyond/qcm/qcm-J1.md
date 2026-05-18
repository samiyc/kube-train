# 🎓 QCM J1 — Flyway, Outbox Pattern & Spring Cloud Contract

> 8 questions couvrant les sujets de J1.
> Niveaux : ⭐ (basique), ⭐⭐ (intermédiaire), ⭐⭐⭐ (avancé/entretien)
>
> Répondre dans le fichier `template-reponse-qcm.md` (copie en `reponse-qcm-J1.md`).

---

## Flyway

### Question 1 — Règle des migrations Flyway ⭐

Tu as déjà déployé `V2__grant_privileges.sql` en production. Tu réalises qu'il manque un `GRANT SELECT` dans ce script. Que fais-tu ?

A) Tu modifies directement `V2__grant_privileges.sql` et tu re-déploies  
B) Tu crées un nouveau fichier `V3__add_missing_grant.sql` avec le `GRANT` manquant  
C) Tu supprimes l'entrée dans `flyway_schema_history` et tu relances la migration  
D) Tu désactives `spring.flyway.validate-on-migrate` pour bypasser la vérification  

---

### Question 2 — Flyway et profils Spring ⭐⭐

Dans kube-train, les migrations Flyway s'exécutent uniquement avec le profil `postgres`. Pourquoi ne s'exécutent-elles PAS en profil `default` (local/tests) ?

---

### Question 3 — Table `flyway_schema_history` ⭐⭐

Après 3 migrations réussies (V1, V2, V3), tu examines la table `flyway_schema_history`. Que contient-elle ? À quoi sert la colonne `checksum` ?

---

## Outbox Pattern

### Question 4 — Dual-write problem ⭐⭐

Sans Outbox Pattern, `TrainService.createReservation()` faisait deux opérations séquentielles :
1. `reservationRepository.save(reservation)`
2. `eventPublisher.publish(event)`

Décris le scénario de défaillance qui justifie l'adoption de l'Outbox Pattern. Quelle propriété ACID est en jeu ?

---

### Question 5 — Outbox Pattern en pratique ⭐⭐⭐

Dans notre implémentation de `OutboxPoller`, on publie l'événement sur Kafka/Pub Sub AVANT de marquer le status PROCESSED. Un collègue propose l'inverse : marquer PROCESSED d'abord, publier ensuite. Quelle approche choisir et pourquoi ? Quel est le trade-off ?

A) Publier puis PROCESSED → at-least-once, doublons possibles, consumer doit être idempotent  
B) PROCESSED puis publier → at-most-once, perte de message possible si crash  
C) Les deux sont équivalentes car `@Transactional` garantit l'atomicité  
D) Il faut utiliser une transaction XA (2-phase commit) pour être sûr  

---

### Question 6 — Outbox et idempotence ⭐⭐⭐

L'`OutboxPoller` est un scheduler qui tourne toutes les 5 secondes. Si deux instances de kube-train-api tournent en parallèle (2 replicas sur GKE), que se passe-t-il ? Comment corriger ce problème ?

---

## Spring Cloud Contract

### Question 7 — body vs matchers ⭐⭐

Dans un contrat YAML Spring Cloud Contract, quelle est la différence entre la section `body:` et la section `matchers:` côté response ? Donne un exemple concret avec `reservationId`.

---

### Question 8 — Valeur du Contract Testing ⭐⭐⭐

Un développeur senior dit : *"Les tests d'intégration end-to-end (avec Testcontainers ou un environnement de staging complet) sont suffisants. Le Contract Testing est inutile."*

Que lui réponds-tu ? Cite au moins 2 avantages du Contract Testing que les tests e2e ne fournissent pas.
