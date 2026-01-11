package fr.nix.agribot.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Configuration du systeme de reponse automatique aux messages.
 */
data class AutoResponseConfig(
    // Cle API Mistral
    var mistralApiKey: String = "",

    // Activer/desactiver le systeme
    var enabled: Boolean = true,

    // Mode test actif (repond aux messages "msg:xxx" locaux)
    var testModeActive: Boolean = false,

    // Duree de la fenetre de detection apres connexion (en secondes)
    var detectionWindowSeconds: Int = 120,  // 2 minutes par defaut

    // Pseudo du joueur (utilise pour detecter si un message nous est adresse)
    var playerUsername: String = "",

    // Liste des amis avec reponse "damn neige"
    val damnFriends: MutableList<String> = mutableListOf("neige")
) {
    companion object {
        private val logger = LoggerFactory.getLogger("agribot")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private var instance: AutoResponseConfig? = null

        private fun getConfigFile(): File {
            val configDir = FabricLoader.getInstance().configDir.toFile()
            return File(configDir, "agribot_autoresponse.json")
        }

        /**
         * Charge la configuration depuis le fichier.
         */
        fun load(): AutoResponseConfig {
            val file = getConfigFile()

            if (file.exists()) {
                try {
                    val json = file.readText()
                    val loadedConfig = gson.fromJson(json, AutoResponseConfig::class.java)

                    if (loadedConfig != null) {
                        instance = loadedConfig
                        logger.info("Configuration auto-reponse chargee depuis ${file.absolutePath}")
                    } else {
                        logger.warn("Configuration auto-reponse invalide, utilisation des valeurs par defaut")
                        instance = AutoResponseConfig()
                        instance!!.save()
                    }
                } catch (e: Exception) {
                    logger.error("Erreur lors du chargement de la config auto-reponse: ${e.message}")
                    instance = AutoResponseConfig()
                    instance!!.save()
                }
            } else {
                logger.info("Aucune configuration auto-reponse trouvee, creation des valeurs par defaut")
                instance = AutoResponseConfig()
                instance!!.save()
            }

            return instance!!
        }

        /**
         * Recupere l'instance de configuration.
         */
        fun get(): AutoResponseConfig {
            return instance ?: load()
        }
    }

    /**
     * Sauvegarde la configuration dans le fichier.
     */
    fun save() {
        try {
            val file = getConfigFile()
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(this))
            logger.info("Configuration auto-reponse sauvegardee dans ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Erreur lors de la sauvegarde de la config auto-reponse: ${e.message}")
        }
    }

    /**
     * Verifie si l'API Mistral est configuree.
     */
    fun isApiConfigured(): Boolean {
        return mistralApiKey.isNotBlank()
    }
}
