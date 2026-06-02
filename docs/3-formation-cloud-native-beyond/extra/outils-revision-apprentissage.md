# Outils de révision & apprentissage continu

> Sélection d'outils pour faciliter la préparation aux certifications (CKAD, GCP) et la révision des notes de formation, en complément des TP pratiques.

---

## 🏆 Stack recommandée

```
Matin (TP actif)       → Formation F4 + Copilot CLI (TP progressifs sur kube-train)
Pause / cuisine        → NotebookLM audio (écoute des notes)
Transports / soir      → Anki flashcards (5-10 min/jour, spaced repetition)
Weekend avant certif   → Killer.sh (simulateur examen CKAD, 2 sessions de 2h)
Complément GCP         → Cloud Skills Boost (1 quest/semaine)
```

---

## 📋 Tableau comparatif des outils

| Outil | Usage | Gratuit ? | Plateforme | ROI |
|-------|-------|-----------|------------|-----|
| **NotebookLM** (Google) | Audio podcast IA + Quiz + Fiches depuis tes .md | ✅ | Web | 🔥🔥🔥 |
| **Killer.sh** | Simulateur CKAD identique à l'examen réel | Inclus avec inscription CKAD | Web | 🔥🔥🔥 |
| **Anki** | Flashcards spaced repetition (decks CKAD/GCP) | ✅ | Desktop + Mobile | 🔥🔥 |
| **Cloud Skills Boost** | Labs GCP hands-on (quests certifs) | Crédits gratuits | Web | 🔥🔥 |
| **Play with Kubernetes** | Cluster K8s éphémère gratuit (browser, 30s) | ✅ | Web | 🔥 |
| **Speechify** | Lecture audio de documents (Chrome extension) | Freemium | Web + Mobile | 🔥 |
| **Natural Reader** | Conversion texte → MP3 (voix naturelles) | Freemium | Web + Desktop | 🔥 |
| **Obsidian + plugin TTS** | Lecture audio intégrée dans l'éditeur de notes | ✅ | Desktop + Mobile | 🔥 |

---

## 🎧 NotebookLM — Guide rapide

**URL** : `notebooklm.google.com`

**Fonctionnalités exploitées :**
1. **Résumé audio** — génère un podcast conversationnel (2 voix IA, 10-15 min) à partir de tes notes
2. **Quiz** — génère des questions de compréhension interactives
3. **Fiches d'apprentissage** — résumé par concepts clés
4. **Carte mentale** — visualisation des relations entre concepts
5. **Chat** — poser des questions sur le contenu (comme un tuteur)

**Workflow optimal pour F4 :**
```
1. Upload notes-J1.md dans NotebookLM
2. Générer le résumé audio → écouter en background
3. Lancer le Quiz intégré → identifier les lacunes
4. Avant le QCM Copilot → réécouter l'audio ciblé
```

**Astuce** : Ajouter les 5 `notes-Jx.md` dans un seul notebook — NotebookLM croisera les concepts entre les jours et pourra générer un audio de synthèse transversal.

---

## 🃏 Anki — Spaced Repetition

**Principe** : Les cartes que tu rates reviennent plus souvent. Celles que tu maîtrises s'espacent (1j → 3j → 7j → 30j…).

**Decks recommandés (rechercher sur AnkiWeb) :**
- "CKAD Kubernetes" — commandes kubectl, YAML patterns
- "GCP Professional Cloud DevOps Engineer" — services GCP, SRE concepts
- "Terraform" — HCL syntax, lifecycle commands

**Créer ses propres cartes depuis les QCM F4 :**
- Chaque question QCM ratée → 1 carte Anki (recto = question, verso = réponse + explication)
- 5-10 min/jour suffisent pour la rétention long terme

---

## ⚔️ Killer.sh — Simulateur CKAD

**URL** : `killer.sh`

**Ce que c'est :**
- Environnement identique à l'examen CKAD (même interface, même contraintes)
- 2 sessions incluses avec l'inscription à l'examen CKAD ($445)
- Chaque session = 2h, 15-20 tasks, corrigée automatiquement

**Stratégie :**
1. Passer la 1ère session 2 semaines avant l'examen → identifier les lacunes
2. Réviser les lacunes (notes F4 + Anki)
3. Passer la 2ème session 2-3 jours avant l'examen → valider la progression

**Tips CKAD :**
- Autorisé : `kubernetes.io/docs` (open-book)
- Vitesse > perfection : 15-20 tâches en 2h = ~6-8 min par tâche
- Maîtriser les alias : `alias k=kubectl`, `export do="--dry-run=client -o yaml"`

---

## 🎓 Cloud Skills Boost — Labs GCP

**URL** : `cloudskillsboost.google`

**Parcours alignés avec F4 :**
- "Implement DevOps Workflows in Google Cloud" (GKE + Cloud Build + Cloud Deploy)
- "Secure Software Delivery" (SLSA, Artifact Registry, Binary Authorization)
- "Monitor and Log with Google Cloud Observability" (SLOs, alertes, dashboards)
- "Networking in Google Cloud" (VPC, firewalls, load balancing)

**Crédits gratuits :** Google offre souvent 30 jours d'accès via des événements ou promotions.

---

## 🌐 Play with Kubernetes

**URL** : `labs.play-with-k8s.com`

**Usage** : Tester rapidement un concept K8s sans Minikube/GKE :
- RBAC : créer un Role + tester avec `kubectl auth can-i`
- NetworkPolicies : 2 pods + tester la connectivité
- Jobs/CronJobs : vérifier le comportement sans polluer ton cluster

**Limitation** : sessions de 4h, pas de persistance.

---

## 📌 Résumé : calendrier type de révision

| Phase | Durée | Outils | Objectif |
|-------|-------|--------|----------|
| Formation F4 | 5 jours | Copilot CLI + kube-train | Acquérir les compétences |
| Consolidation | 2-3 semaines | NotebookLM (audio) + Anki (flashcards) | Ancrer en mémoire |
| Prépa CKAD | 2-3 semaines | Killer.sh + Play with K8s + Anki | Vitesse + précision |
| Prépa GCP DevOps | 3-4 semaines | Cloud Skills Boost + Anki + NotebookLM | Couverture services GCP |

---

*Dernière mise à jour : 2026-05-30*
