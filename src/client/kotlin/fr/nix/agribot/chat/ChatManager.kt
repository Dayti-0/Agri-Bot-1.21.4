package fr.nix.agribot.chat

import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory

/**
 * Gestionnaire de chat pour envoyer des commandes et messages.
 */
object ChatManager {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    // === CACHE CONNEXION ===
    // Evite les verifications repetees dans le meme tick
    private var cacheTickId: Long = -1
    private var cachedIsConnected: Boolean = false
    private var globalTickCounter: Long = 0

    /**
     * Doit etre appele une fois par tick pour permettre l'invalidation du cache.
     * Appele automatiquement par BotCore.
     */
    fun onTick() {
        globalTickCounter++
    }

    /**
     * Invalide le cache si necessaire (nouveau tick).
     */
    private fun ensureCacheValid() {
        if (cacheTickId == globalTickCounter) {
            return // Cache valide
        }

        // Invalider et recalculer
        cacheTickId = globalTickCounter
        cachedIsConnected = client.player != null && client.networkHandler != null
    }

    /**
     * Envoie une commande dans le chat (avec /).
     * @param command La commande sans le / (ex: "home station1")
     */
    fun sendCommand(command: String) {
        val player = client.player ?: run {
            logger.warn("Impossible d'envoyer la commande: pas de joueur")
            return
        }

        val networkHandler = client.networkHandler ?: run {
            logger.warn("Impossible d'envoyer la commande: pas de connexion")
            return
        }

        logger.info("Envoi commande: /$command")
        networkHandler.sendChatCommand(command)
    }

    /**
     * Envoie un message dans le chat (sans /).
     * @param message Le message a envoyer
     */
    fun sendMessage(message: String) {
        val player = client.player ?: run {
            logger.warn("Impossible d'envoyer le message: pas de joueur")
            return
        }

        val networkHandler = client.networkHandler ?: run {
            logger.warn("Impossible d'envoyer le message: pas de connexion")
            return
        }

        logger.info("Envoi message: $message")
        networkHandler.sendChatMessage(message)
    }

    /**
     * Teleporte le joueur a un home.
     * @param homeName Nom du home (ex: "coldocean")
     */
    fun teleportToHome(homeName: String) {
        sendCommand("home $homeName")
    }

    /**
     * Remplit les seaux en main avec /eau.
     */
    fun fillBuckets() {
        sendCommand("eau")
    }

    /**
     * Affiche un message dans l'action bar du joueur (au-dessus de la hotbar).
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @param message Le message a afficher
     * @param color Code couleur (ex: "6" pour orange, "a" pour vert)
     */
    fun showActionBar(message: String, color: String = "6") {
        client.execute {
            client.player?.sendMessage(
                net.minecraft.text.Text.literal("§${color}[AgriBot]§r $message"),
                true
            )
        }
    }

    /**
     * Affiche un message dans le chat local (visible uniquement par le joueur).
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @param message Le message a afficher
     * @param color Code couleur
     */
    fun showLocalMessage(message: String, color: String = "6") {
        client.execute {
            client.player?.sendMessage(
                net.minecraft.text.Text.literal("§${color}[AgriBot]§r $message"),
                false
            )
        }
    }

    /**
     * Verifie si le joueur est connecte a un serveur.
     * OPTIMISE: utilise le cache pour eviter les verifications repetees.
     */
    fun isConnected(): Boolean {
        ensureCacheValid()
        return cachedIsConnected
    }

    /**
     * Recupere le nom du serveur actuel.
     */
    fun getCurrentServerAddress(): String? {
        return client.currentServerEntry?.address
    }
}
