# Détection de Menu

Ce document explique comment utiliser le système de détection de menu ajouté au bot.

## Fonctionnalités

Le nouveau système `MenuDetector` permet de :
- Détecter si un menu est ouvert (coffre, station d'agriculture, etc.)
- Identifier le type de menu ouvert
- Attendre activement qu'un menu soit ouvert au lieu d'utiliser des délais fixes
- Gérer les timeouts si un menu ne s'ouvre pas

## Utilisation de base

### Vérifier si un menu est ouvert

```kotlin
// Vérifier si n'importe quel menu est ouvert
if (MenuDetector.isMenuOpen()) {
    println("Un menu est ouvert")
}

// Vérifier si un coffre/container est ouvert
if (MenuDetector.isChestOrContainerOpen()) {
    println("Un coffre est ouvert")
}

// Vérifier si un menu simple (sans titre) est ouvert
if (MenuDetector.isSimpleMenuOpen()) {
    println("Un menu simple est ouvert")
}
```

### Attendre qu'un menu s'ouvre

Toutes les fonctions d'attente incluent maintenant un **délai de stabilisation** par défaut de **2 secondes** pour s'assurer que le menu est complètement chargé et synchronisé avec le serveur avant de continuer.

```kotlin
// Attendre qu'un menu quelconque s'ouvre (timeout: 5s + stabilisation: 2s)
if (MenuDetector.waitForMenuOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
    println("Menu ouvert et complètement chargé")
} else {
    println("Timeout - aucun menu détecté")
}

// Attendre qu'un coffre s'ouvre avec stabilisation par défaut (2s)
if (MenuDetector.waitForChestOpen(timeoutMs = 5000)) {
    println("Coffre ouvert et chargé")
}

// Attendre qu'un menu simple s'ouvre avec stabilisation personnalisée
if (MenuDetector.waitForSimpleMenuOpen(timeoutMs = 5000, stabilizationDelayMs = 1500)) {
    println("Menu simple ouvert (avec 1.5s de stabilisation)")
}

// Désactiver la stabilisation si nécessaire (non recommandé)
if (MenuDetector.waitForChestOpen(timeoutMs = 5000, stabilizationDelayMs = 0)) {
    println("Coffre détecté (pas de vérification de chargement)")
}
```

### Vérifier si le menu est complètement chargé

```kotlin
// Vérifier que le menu est complètement synchronisé avec le serveur
if (MenuDetector.isMenuFullyLoaded()) {
    println("Menu complètement chargé, slots synchronisés")
} else {
    println("Menu en cours de chargement ou non synchronisé")
}
```

Cette fonction vérifie :
- Que le menu a un `ScreenHandler` valide
- Que les slots sont initialisés
- Que le `syncId` est valide (> 0), ce qui signifie que le menu est synchronisé avec le serveur

### Détecter le type de menu

```kotlin
val menuType = MenuDetector.detectMenuType()

when (menuType) {
    MenuDetector.MenuType.CHEST -> println("C'est un coffre")
    MenuDetector.MenuType.GENERIC -> println("C'est un container générique")
    MenuDetector.MenuType.FURNACE -> println("C'est un fourneau")
    MenuDetector.MenuType.NONE -> println("Aucun menu ouvert")
    else -> println("Menu de type: $menuType")
}
```

### Obtenir des informations sur le menu

```kotlin
val info = MenuDetector.getMenuInfo()
if (info != null) {
    println("Type: ${info.type}")
    println("Nombre de slots: ${info.slotCount}")
    println("Titre: ${info.title}")
}

// Ou afficher directement les infos de debug
MenuDetector.debugCurrentMenu()
```

## Utilisation via ActionManager

Des fonctions utilitaires ont été ajoutées à `ActionManager` pour simplifier l'utilisation :

```kotlin
// Vérifier si un menu est ouvert
if (ActionManager.isMenuOpen()) {
    println("Menu ouvert")
}

// Vérifier si un coffre est ouvert
if (ActionManager.isChestOpen()) {
    println("Coffre ouvert")
}

// Ouvrir un coffre et attendre (avec stabilisation par défaut de 2s)
if (ActionManager.openChestAndWait()) {
    println("Coffre ouvert et chargé avec succès")
    // Faire des opérations sur le coffre
}

// Ouvrir un coffre avec stabilisation personnalisée
if (ActionManager.openChestAndWait(timeoutMs = 5000, stabilizationDelayMs = 1500)) {
    println("Coffre ouvert avec 1.5s de stabilisation")
}

// Ouvrir un menu simple et attendre (avec stabilisation par défaut de 2s)
if (ActionManager.openMenuAndWait()) {
    println("Menu ouvert et chargé avec succès")
    // Faire des opérations sur le menu
}
```

## Types de menu supportés

Le `MenuDetector` peut identifier les types de menu suivants :
- `NONE` : Aucun menu ouvert
- `CHEST` : Coffre simple ou double
- `GENERIC` : Container générique (station d'agriculture, etc.)
- `HOPPER` : Trémie
- `DISPENSER` : Distributeur/dropper
- `FURNACE` : Fourneau (normal, blast, smoker)
- `CRAFTING` : Table de craft
- `BREWING` : Alambic
- `ENCHANTING` : Table d'enchantement
- `ANVIL` : Enclume
- `BEACON` : Balise
- `UNKNOWN` : Menu inconnu

## Intégration dans BotCore

Le système est déjà intégré dans `BotCore` :

### Ouverture du coffre (gestion des seaux)
```kotlin
ActionManager.rightClick()

// Attente avec stabilisation automatique de 2 secondes
if (MenuDetector.waitForChestOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
    logger.info("Coffre ouvert et charge - Pret pour operations")
    MenuDetector.debugCurrentMenu()
    // Le menu est garanti d'être complètement chargé et synchronisé
    // Continuer avec les opérations
} else {
    logger.warn("Echec ouverture coffre - Timeout")
    // Réessayer
}
```

### Ouverture de la station d'agriculture
```kotlin
ActionManager.rightClick()

// Attente avec stabilisation automatique de 2 secondes
if (MenuDetector.waitForSimpleMenuOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
    logger.info("Station ouverte et chargee - Pret pour recolte")
    MenuDetector.debugCurrentMenu()
    // Le menu est garanti d'être complètement chargé et synchronisé
    // Continuer avec la récolte
} else {
    logger.warn("Echec ouverture station - Timeout")
    // Réessayer
}
```

## Avantages

1. **Plus fiable** : Détection active au lieu de délais fixes
2. **Menu complètement chargé** : Vérification que le menu est synchronisé avec le serveur (syncId) avant de continuer
3. **Stabilisation intelligente** : Délai de 2 secondes par défaut après détection pour garantir que les slots sont chargés
4. **Plus rapide** : Pas besoin d'attendre un délai complet si le menu s'ouvre rapidement
5. **Gestion d'erreurs** : Timeout et retry automatique si le menu ne s'ouvre pas
6. **Debug amélioré** : Affichage d'informations sur le menu pour faciliter le débogage
7. **Flexible** : Timeout, intervalle de vérification et délai de stabilisation configurables

## Exemple complet

```kotlin
// Téléporter vers le coffre
ChatManager.teleportToHome(config.homeCoffre)
waitMs(2000)

// Ouvrir le coffre avec détection et stabilisation automatique (2 secondes)
ActionManager.rightClick()

if (MenuDetector.waitForChestOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
    // Le coffre est ouvert ET complètement chargé
    logger.info("Coffre ouvert et charge - Pret pour operations")
    MenuDetector.debugCurrentMenu()

    // Vérifier que le menu est toujours chargé (optionnel)
    if (MenuDetector.isMenuFullyLoaded()) {
        // Faire des opérations sur le coffre
        ActionManager.startSneaking()
        ActionManager.rightClick() // Shift+click pour transférer
        ActionManager.stopSneaking()

        waitMs(500)

        // Fermer le coffre
        ActionManager.pressEscape()
    }
} else {
    // Le coffre ne s'est pas ouvert ou n'est pas chargé, gérer l'erreur
    logger.warn("Impossible d'ouvrir le coffre ou timeout")
    ChatManager.showActionBar("Echec ouverture coffre!", "c")
}
```

## Processus de détection

Voici ce qui se passe lors de l'appel à `waitForChestOpen` :

1. **Détection** : Vérification toutes les 50ms si un coffre/container est ouvert
2. **Menu détecté** : Dès qu'un menu est détecté, passe à l'étape suivante
3. **Stabilisation** : Attente de 2 secondes (configurable) pour que le menu se charge
4. **Vérification** : Contrôle que le menu est toujours ouvert et que `syncId > 0`
5. **Retour** : Retourne `true` si tout est OK, `false` en cas de timeout ou d'échec

Cela garantit que :
- Le menu est ouvert
- Les slots sont synchronisés avec le serveur
- Le menu est stable et prêt à être utilisé
