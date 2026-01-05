# PyroFarm Bot

Bot d'automatisation pour l'agriculture sur serveur Minecraft. Interface graphique Tkinter + contrôle du jeu via `pyautogui`, `pydirectinput` et surveillance des logs.

---

## Table des matières

1. [Fonctionnalités](#fonctionnalités)
2. [Prérequis](#prérequis)
3. [Installation](#installation)
4. [Lancement](#lancement)
5. [Configuration](#configuration)
6. [Fonctionnement détaillé du bot](#fonctionnement-détaillé-du-bot)
7. [Modes de lancement](#modes-de-lancement)
8. [Dépannage](#dépannage)
9. [Responsabilité](#responsabilité)

---

## Fonctionnalités

- **Interface graphique** pour configurer tous les paramètres sans éditer le JSON
- **Calcul automatique** du temps de croissance selon la plante et le boost
- **Capture des positions** via clic global pour les coordonnées d'écran
- **Jusqu'à 30 stations** (homes) avec ajout, suppression et renommage
- **Deux modes** : session unique ou mode continu avec pause automatique
- **Surveillance des logs Minecraft** pour détecter les stations pleines
- **Gestion intelligente des seaux** selon l'heure (matin / reste de la journée)

---

## Prérequis

- Python 3.10+
- Modules : `pyautogui`, `pydirectinput`, `pynput`, `pyperclip`
- Accès au fichier `latest.log` de Minecraft
- Jeu en plein écran ou fenêtre fixe (coordonnées en pixels absolus)

---

## Installation

```bash
git clone https://github.com/votre-repo/Pyrofarm-Bot.git
cd Pyrofarm-Bot
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\Scripts\activate
pip install pyautogui pydirectinput pynput pyperclip
```

---

## Lancement

```bash
python main.py
```

L'interface s'ouvre avec deux onglets :
1. **Configuration** : paramètres, positions, stations
2. **Lancement** : démarrer/arrêter le bot

---

## Configuration

### Fichier de log Minecraft

Chemin vers `latest.log` (ex: `C:\Users\...\AppData\Roaming\.minecraft\logs\latest.log`).
Utilisé pour détecter le message "Station de Croissance déjà pleine".

### Plante et temps de croissance

| Paramètre | Description |
|-----------|-------------|
| **Plante** | Sélection dans la liste déroulante |
| **Boost (%)** | Pourcentage de bonus de croissance en jeu |
| **Temps calculé** | Affiché automatiquement, utilisé comme pause entre sessions |

### Positions de clic

| Position | Rôle |
|----------|------|
| `server_connect` | Serveur dans la liste multijoueur |
| `server_confirm` | Confirmation de connexion |
| `disconnect` | Bouton déconnexion dans le menu |
| `default_harvest` | Position par défaut pour récolter |
| `bucket_chest` | Emplacement des seaux dans le coffre |

> Cliquez sur **Modifier** puis cliquez à l'écran pour enregistrer les coordonnées.

### Stations (homes)

- Ajoutez vos homes via le champ texte + **Ajouter**
- Double-cliquez pour renommer
- L'ordre affiché = ordre de visite

### Homes spéciaux

| Home | Utilisation |
|------|-------------|
| `coffre1` | Où jeter les 15 seaux le matin |
| `coffre2` | Où récupérer les 16 seaux après 11h30 |

---

## Fonctionnement détaillé du bot

Voici le déroulement complet d'une session de farming, étape par étape :

### Phase 1 : Préparation

```
┌─────────────────────────────────────────────────────────────┐
│  COMPTE À REBOURS (5 secondes)                              │
│  → Placez Minecraft au premier plan                         │
│  → Le bot affiche: "Démarrage dans 5... 4... 3... 2... 1..." │
└─────────────────────────────────────────────────────────────┘
```

### Phase 2 : Vérification des restrictions horaires

```
┌─────────────────────────────────────────────────────────────┐
│  VÉRIFICATION HORAIRE                                       │
│  → Si l'heure est entre 05:50 et 06:30 (redémarrage serveur)│
│  → Le bot attend automatiquement 06:31 avant de continuer   │
└─────────────────────────────────────────────────────────────┘
```

### Phase 3 : Connexion au serveur

```
┌─────────────────────────────────────────────────────────────┐
│  ÉTAPE 3.1 : Clic sur server_connect                        │
│  → Clic gauche sur la position configurée                   │
│  → Délai de 2 secondes                                      │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 3.2 : Descente dans la liste                         │
│  → Appui sur touche "Bas" (↓)                               │
│  → Délai de 0.8 secondes                                    │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 3.3 : Confirmation connexion                         │
│  → Clic droit (ouvre le menu)                               │
│  → Clic gauche sur server_confirm                           │
│  → Délai de 8 secondes (chargement serveur)                 │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 3.4 : Initialisation chat                            │
│  → Appui sur "T" (ouvre le chat)                            │
│  → Appui sur "Échap" (ferme le chat)                        │
│  → Prêt pour les commandes                                  │
└─────────────────────────────────────────────────────────────┘
```

### Phase 4 : Gestion des seaux (si transition de période)

Le bot détecte automatiquement les transitions de période et agit en conséquence :

```
┌─────────────────────────────────────────────────────────────┐
│  MODE MATIN (06:30 → 11:30)                                 │
│  Déclenché uniquement lors du passage vers cette période    │
├─────────────────────────────────────────────────────────────┤
│  1. Téléportation: /home coffre1                            │
│  2. Sélection du slot actuel (1 ou 2)                       │
│  3. Appui sur "R" × 15 fois (jette 15 seaux)                │
│  4. Conservation de 1 seau dans la barre                    │
│  5. Sauvegarde de l'état dans .bucket_state.json            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  MODE APRÈS-MIDI (après 11:30)                              │
│  Déclenché uniquement lors du passage vers cette période    │
├─────────────────────────────────────────────────────────────┤
│  1. Téléportation: /home coffre2                            │
│  2. Clic droit (ouvre le coffre)                            │
│  3. Shift + clic sur bucket_chest (récupère 16 seaux)       │
│  4. Appui sur "Échap" (ferme le coffre)                     │
│  5. Sélection du slot 1                                     │
│  6. Sauvegarde de l'état dans .bucket_state.json            │
└─────────────────────────────────────────────────────────────┘
```

### Phase 5 : Traitement des stations (boucle principale)

Pour chaque station dans la liste (de 1 à 30) :

```
┌─────────────────────────────────────────────────────────────┐
│  ÉTAPE 5.1 : Téléportation                                  │
│  → Appui sur "T" (ouvre le chat)                            │
│  → Tape: /home <nom_station>                                │
│  → Appui sur "Entrée"                                       │
│  → Délai de 2 secondes (téléportation)                      │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 5.2 : Préparation                                    │
│  → Appui sur "0" (sélectionne slot graines)                 │
│  → Clic droit (ouvre l'interface de la station)             │
│  → Délai de 3 secondes                                      │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 5.3 : Récolte                                        │
│  → Clic droit sur harvest_pos (ou default_harvest)          │
│  → Clic gauche sur la même position                         │
│  → Ce combo garantit la récolte peu importe la plante       │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 5.4 : Replantation                                   │
│  → Appui sur "Échap" (ferme l'interface)                    │
│  → Appui sur "0" (sélectionne les graines)                  │
│  → Maintien de "Q" + clic droit (plante la graine)          │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 5.5 : Remplissage en eau                             │
│  → Voir détail ci-dessous selon le mode                     │
└─────────────────────────────────────────────────────────────┘
```

#### Remplissage en eau - Mode 1 seau (matin)

```
┌─────────────────────────────────────────────────────────────┐
│  BOUCLE DE REMPLISSAGE (max 50 itérations)                  │
├─────────────────────────────────────────────────────────────┤
│  1. Appui sur "F7" (macro pour remplir le seau)             │
│  2. Clic droit (verse l'eau dans la station)                │
│  3. Vérification du log Minecraft                           │
│     → Si "Station de Croissance déjà pleine" détecté        │
│     → Arrêt immédiat du remplissage                         │
│  4. Sinon: retour à l'étape 1                               │
└─────────────────────────────────────────────────────────────┘
```

#### Remplissage en eau - Mode 16 seaux (après-midi)

```
┌─────────────────────────────────────────────────────────────┐
│  INITIALISATION                                             │
│  → Sélection du slot actuel (1 ou 2)                        │
│  → Si aucun seau plein: appui sur "F7" (remplit 16 seaux)   │
├─────────────────────────────────────────────────────────────┤
│  BOUCLE DE REMPLISSAGE (max 32 itérations)                  │
│  1. Clic droit (verse l'eau)                                │
│  2. Décrémente le compteur de seaux pleins                  │
│  3. Vérification du log Minecraft                           │
│     → Si "Station de Croissance déjà pleine" détecté        │
│     → Arrêt immédiat du remplissage                         │
│  4. Si plus de seaux pleins et station pas pleine:          │
│     → Changement de slot (1 ↔ 2)                            │
│     → Appui sur "F7" (remplit les 16 seaux vides)           │
│  5. Sinon: retour à l'étape 1                               │
├─────────────────────────────────────────────────────────────┤
│  SI DERNIÈRE STATION                                        │
│  → Vide tous les seaux pleins restants (clic droit × N)     │
└─────────────────────────────────────────────────────────────┘
```

### Phase 6 : Fin de session

```
┌─────────────────────────────────────────────────────────────┐
│  ÉTAPE 6.1 : Vidage final des seaux                         │
│  → Si mode 16 seaux: vide tous les seaux pleins restants    │
│  → Sauvegarde de l'état des seaux                           │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 6.2 : Déconnexion                                    │
│  → Appui sur "Échap" (ouvre le menu)                        │
│  → Clic sur disconnect                                      │
│  → Retour au menu multijoueur                               │
├─────────────────────────────────────────────────────────────┤
│  ÉTAPE 6.3 : Bilan                                          │
│  → Affichage de la durée de session                         │
│  → Affichage du nombre de stations complétées               │
└─────────────────────────────────────────────────────────────┘
```

### Phase 7 : Mode continu (si activé)

```
┌─────────────────────────────────────────────────────────────┐
│  PAUSE ENTRE SESSIONS                                       │
│  → Durée: temps de croissance de la plante (session_pause)  │
│  → Affichage du temps restant toutes les 30 secondes        │
│  → Vérification continue du signal d'arrêt                  │
├─────────────────────────────────────────────────────────────┤
│  NOUVELLE SESSION                                           │
│  → Vérification des restrictions horaires                   │
│  → Si 05:50-06:30: attente automatique                      │
│  → Sinon: retour à la Phase 1                               │
└─────────────────────────────────────────────────────────────┘
```

---

## Diagramme récapitulatif

```
                    ┌──────────────┐
                    │   DÉMARRAGE  │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐
                    │ Compte à     │
                    │ rebours (5s) │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐    Oui    ┌──────────────┐
                    │ 05:50-06:30? ├──────────►│ Attendre     │
                    └──────┬───────┘           │ 06:31        │
                           │ Non               └──────┬───────┘
                           ▼                          │
                    ┌──────────────┐◄─────────────────┘
                    │ Connexion    │
                    │ serveur      │
                    └──────┬───────┘
                           │
                           ▼
                    ┌──────────────┐    Oui    ┌──────────────┐
                    │ Transition   ├──────────►│ Gestion      │
                    │ de période?  │           │ seaux        │
                    └──────┬───────┘           └──────┬───────┘
                           │ Non                      │
                           ▼                          │
            ┌──────────────────────────◄──────────────┘
            │
            ▼
    ┌───────────────┐
    │ Pour chaque   │◄─────────────────────────────┐
    │ station       │                              │
    └───────┬───────┘                              │
            │                                      │
            ▼                                      │
    ┌───────────────┐                              │
    │ Téléportation │                              │
    │ /home <nom>   │                              │
    └───────┬───────┘                              │
            │                                      │
            ▼                                      │
    ┌───────────────┐                              │
    │ Récolte +     │                              │
    │ Replantation  │                              │
    └───────┬───────┘                              │
            │                                      │
            ▼                                      │
    ┌───────────────┐                              │
    │ Remplissage   │                              │
    │ eau           │                              │
    └───────┬───────┘                              │
            │                                      │
            ▼                                      │
    ┌───────────────┐    Oui                       │
    │ Encore des    ├──────────────────────────────┘
    │ stations?     │
    └───────┬───────┘
            │ Non
            ▼
    ┌───────────────┐
    │ Vidage seaux  │
    │ + Déconnexion │
    └───────┬───────┘
            │
            ▼
    ┌───────────────┐    Oui    ┌───────────────┐
    │ Mode continu? ├──────────►│ Pause         │──┐
    └───────┬───────┘           │ (session_     │  │
            │ Non               │  pause)       │  │
            ▼                   └───────────────┘  │
    ┌───────────────┐                              │
    │     FIN       │◄─────────────────────────────┘
    └───────────────┘           (retour au début)
```

---

## Modes de lancement

### Session unique
Exécute un cycle complet :
1. Connexion → Gestion seaux → Toutes les stations → Déconnexion

### Mode continu
Enchaîne les sessions avec pause entre chaque :
1. Session complète
2. Pause de `session_pause` secondes
3. Retour à l'étape 1

### Tests disponibles

| Test | Description |
|------|-------------|
| **Transition Jour→Matin** | Teste le jet de 15 seaux |
| **Transition Matin→Jour** | Teste la récupération des seaux |
| **Session 1 Station** | Teste le cycle complet sur 1 station |
| **Session 5 Stations** | Teste sur les 5 premières stations |
| **TP Toutes Stations** | Teste la téléportation à chaque station |

---

## Dépannage

| Problème | Solution |
|----------|----------|
| Clics mal positionnés | Reconfigurez les positions après avoir fixé la résolution |
| Bot trop rapide | Augmentez les délais dans la config (non visible dans l'UI, éditer le JSON) |
| Station non détectée pleine | Vérifiez le chemin du fichier `latest.log` |
| Erreur 05:50-06:30 | Normal, le bot attend la fin du redémarrage serveur |
| Seaux mal gérés | Supprimez `.bucket_state.json` pour réinitialiser |

Pour réinitialiser complètement : supprimez `farming_config.json` (sera recréé avec les valeurs par défaut).

---

## Responsabilité

L'automatisation peut être restreinte sur certains serveurs. Utilisez PyroFarm Bot conformément aux règles en vigueur et à vos propres risques.
