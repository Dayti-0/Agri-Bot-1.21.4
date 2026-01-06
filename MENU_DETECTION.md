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

```kotlin
// Attendre qu'un menu quelconque s'ouvre (timeout: 5 secondes)
if (MenuDetector.waitForMenuOpen(timeoutMs = 5000)) {
    println("Menu ouvert avec succès")
} else {
    println("Timeout - aucun menu détecté")
}

// Attendre qu'un coffre s'ouvre
if (MenuDetector.waitForChestOpen(timeoutMs = 5000)) {
    println("Coffre ouvert")
}

// Attendre qu'un menu simple s'ouvre
if (MenuDetector.waitForSimpleMenuOpen(timeoutMs = 5000)) {
    println("Menu simple ouvert")
}
```

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

// Ouvrir un coffre et attendre
if (ActionManager.openChestAndWait()) {
    println("Coffre ouvert avec succès")
    // Faire des opérations sur le coffre
}

// Ouvrir un menu simple et attendre
if (ActionManager.openMenuAndWait()) {
    println("Menu ouvert avec succès")
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

if (MenuDetector.waitForChestOpen(timeoutMs = 5000)) {
    logger.info("Coffre ouvert - Menu detecte")
    MenuDetector.debugCurrentMenu()
    // Continuer avec les opérations
} else {
    logger.warn("Echec ouverture coffre - Timeout")
    // Réessayer
}
```

### Ouverture de la station d'agriculture
```kotlin
ActionManager.rightClick()

if (MenuDetector.waitForSimpleMenuOpen(timeoutMs = 5000)) {
    logger.info("Station ouverte - Menu detecte")
    MenuDetector.debugCurrentMenu()
    // Continuer avec la récolte
} else {
    logger.warn("Echec ouverture station - Timeout")
    // Réessayer
}
```

## Avantages

1. **Plus fiable** : Détection active au lieu de délais fixes
2. **Plus rapide** : Pas besoin d'attendre un délai complet si le menu s'ouvre rapidement
3. **Gestion d'erreurs** : Timeout et retry automatique si le menu ne s'ouvre pas
4. **Debug amélioré** : Affichage d'informations sur le menu pour faciliter le débogage
5. **Flexible** : Timeout et intervalle de vérification configurables

## Exemple complet

```kotlin
// Téléporter vers le coffre
ChatManager.teleportToHome(config.homeCoffre)
waitMs(2000)

// Ouvrir le coffre avec détection
ActionManager.rightClick()

if (MenuDetector.waitForChestOpen(timeoutMs = 5000)) {
    // Le coffre est ouvert, afficher les infos
    MenuDetector.debugCurrentMenu()

    // Faire des opérations sur le coffre
    ActionManager.startSneaking()
    ActionManager.rightClick() // Shift+click pour transférer
    ActionManager.stopSneaking()

    waitMs(500)

    // Fermer le coffre
    ActionManager.pressEscape()
} else {
    // Le coffre ne s'est pas ouvert, gérer l'erreur
    logger.warn("Impossible d'ouvrir le coffre")
    ChatManager.showActionBar("Echec ouverture coffre!", "c")
}
```
