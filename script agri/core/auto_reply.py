#!/usr/bin/env python3
"""
Module d'auto-réponse utilisant Mistral AI.
Surveille les messages du chat et répond automatiquement aux salutations.
"""

import os
import re
import time
import threading
import platform
from pathlib import Path
from typing import Callable, Optional, List
import json

import pyautogui
import pydirectinput

# Dépendance optionnelle pour le collage
try:
    import pyperclip
except ImportError:
    pyperclip = None

# Dépendance pour l'API Mistral
try:
    import requests
    HAS_REQUESTS = True
except ImportError:
    HAS_REQUESTS = False


class AutoReplyBot:
    """Bot d'auto-réponse avec intégration Mistral AI."""

    def __init__(
        self,
        log_path: str,
        pseudo: str = "Dayti",
        wordlist_path: str = "auto_reply_wordlist.txt",
        api_key_path: str = "mistral_api_key.txt",
    ):
        self.log_path = log_path
        self.pseudo = pseudo.lower()
        self.wordlist_path = Path(wordlist_path)
        self.api_key_path = Path(api_key_path)

        self.running = False
        self.monitor_thread: Optional[threading.Thread] = None
        self.file_position = 0
        self.lock = threading.Lock()

        # Charger la wordlist et la clé API
        self.trigger_words = self._load_wordlist()
        self.api_key = self._load_api_key()

        # Callbacks pour l'UI
        self.on_message_detected: Optional[Callable[[str, str, str], None]] = None
        self.on_reply_sent: Optional[Callable[[str], None]] = None
        self.on_error: Optional[Callable[[str], None]] = None

        # Historique des messages pour le contexte
        self.conversation_history: List[dict] = []
        self.max_history = 10

        # Délai minimum entre les réponses (éviter le spam)
        self.min_reply_delay = 3.0
        self.last_reply_time = 0

    def _load_wordlist(self) -> List[str]:
        """Charge la liste des mots déclencheurs."""
        words = []
        try:
            if self.wordlist_path.exists():
                with open(self.wordlist_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip().lower()
                        if line and not line.startswith("#"):
                            words.append(line)
                print(f"[AUTO-REPLY] {len(words)} mots déclencheurs chargés")
        except Exception as e:
            print(f"[AUTO-REPLY] Erreur chargement wordlist: {e}")
        return words

    def _load_api_key(self) -> str:
        """Charge la clé API Mistral."""
        try:
            if self.api_key_path.exists():
                with open(self.api_key_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#"):
                            return line
        except Exception as e:
            print(f"[AUTO-REPLY] Erreur chargement clé API: {e}")
        return ""

    def reload_config(self):
        """Recharge la wordlist et la clé API."""
        self.trigger_words = self._load_wordlist()
        self.api_key = self._load_api_key()

    def set_pseudo(self, pseudo: str):
        """Met à jour le pseudo de l'utilisateur."""
        self.pseudo = pseudo.lower()
        print(f"[AUTO-REPLY] Pseudo mis à jour: {pseudo}")

    def start(self):
        """Démarre la surveillance des messages."""
        if self.running:
            return

        if not HAS_REQUESTS:
            print("[AUTO-REPLY] Module requests non installé. Installez-le avec: pip install requests")
            return

        if not self.api_key or self.api_key == "VOTRE_CLE_API_MISTRAL_ICI":
            print("[AUTO-REPLY] Clé API Mistral non configurée!")
            return

        self.running = True
        self._seek_to_end()
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        print(f"[AUTO-REPLY] Surveillance démarrée (pseudo: {self.pseudo})")

    def stop(self):
        """Arrête la surveillance."""
        self.running = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)
        print("[AUTO-REPLY] Surveillance arrêtée")

    def _seek_to_end(self):
        """Se positionne à la fin du fichier log."""
        try:
            if os.path.exists(self.log_path):
                with open(self.log_path, "r", encoding="cp1252", errors="ignore") as f:
                    f.seek(0, 2)
                    self.file_position = f.tell()
        except Exception:
            pass

    def _monitor_loop(self):
        """Boucle principale de surveillance."""
        while self.running:
            try:
                if not os.path.exists(self.log_path):
                    time.sleep(1)
                    continue

                with open(self.log_path, "r", encoding="cp1252", errors="ignore") as f:
                    f.seek(self.file_position)
                    new_lines = f.readlines()

                    if new_lines:
                        self.file_position = f.tell()
                        self._process_lines(new_lines)
                    else:
                        # Vérifier si le fichier a été tronqué
                        f.seek(0, 2)
                        current_size = f.tell()
                        if current_size < self.file_position:
                            print("[AUTO-REPLY] Nouveau fichier log détecté")
                            self.file_position = 0

            except Exception as e:
                print(f"[AUTO-REPLY] Erreur: {e}")

            time.sleep(0.2)

    def _process_lines(self, lines: List[str]):
        """Traite les nouvelles lignes du log."""
        for line in lines:
            line = line.strip()
            if not line:
                continue

            # Détecter les messages du chat
            # Format: [HH:MM:SS] [Render thread/INFO]: [System] [CHAT] Pseudo: Message
            # ou: [HH:MM:SS] [Render thread/INFO]: [System] [CHAT] [Rang] Préfixe Pseudo » Message

            if "[CHAT]" not in line:
                continue

            # Extraire la partie après [CHAT]
            chat_match = re.search(r'\[CHAT\]\s*(.+)$', line)
            if not chat_match:
                continue

            chat_content = chat_match.group(1)

            # Parser le message
            sender, message = self._parse_chat_message(chat_content)

            if not sender or not message:
                continue

            # Ignorer ses propres messages
            if self.pseudo.lower() in sender.lower():
                continue

            message_lower = message.lower().strip()

            # Réponse automatique à "re" (toujours, sans Mistral)
            if message_lower == "re":
                print(f"[AUTO-REPLY] '{sender}' a dit 're' -> Réponse 're'")
                # Vérifier le délai minimum
                current_time = time.time()
                if current_time - self.last_reply_time >= self.min_reply_delay:
                    self._send_chat_message("re")
                    self.last_reply_time = time.time()
                    if self.on_reply_sent:
                        self.on_reply_sent("re")
                continue

            # Vérifier si le message contient un mot déclencheur
            if self._contains_trigger(message):
                print(f"[AUTO-REPLY] Message détecté de {sender}: {message}")

                if self.on_message_detected:
                    self.on_message_detected(sender, message, line)

                # Générer et envoyer la réponse
                self._handle_reply(sender, message)

    def _parse_chat_message(self, chat_content: str) -> tuple[Optional[str], Optional[str]]:
        """Parse un message de chat pour extraire le sender et le message."""
        # Ignorer les messages système
        system_prefixes = ["»", "Téléporté", "Mode de vol", "PASSE DE COMBAT", "SurvivalWorld"]
        for prefix in system_prefixes:
            if chat_content.startswith(prefix):
                return None, None

        # Format 1: Préfixes?Pseudo: Message (ex: ?Divinum?Dayti: Yoo)
        match1 = re.match(r'^(?:\[[\d]+\]\s*)?(?:[^\s]*\s*)?([^:»]+?):\s*(.+)$', chat_content)
        if match1:
            sender = match1.group(1).strip()
            message = match1.group(2).strip()
            # Nettoyer le sender des préfixes (?, §, etc.)
            sender = re.sub(r'^[?§\[\]0-9a-zA-Z]*[?§]', '', sender)
            return sender, message

        # Format 2: [Rang] Préfixes?Pseudo » Message
        match2 = re.match(r'^(?:\[[\d]+\]\s*)?(?:[^\s]*\s*)?([^»]+?)\s*»\s*(.+)$', chat_content)
        if match2:
            sender = match2.group(1).strip()
            message = match2.group(2).strip()
            # Nettoyer le sender
            sender = re.sub(r'^[?§\[\]0-9a-zA-Z]*[?§]', '', sender)
            return sender, message

        return None, None

    def _contains_trigger(self, message: str) -> bool:
        """Vérifie si le message contient un mot déclencheur."""
        message_lower = message.lower()
        words_in_message = re.findall(r'\b\w+\b', message_lower)

        for trigger in self.trigger_words:
            if trigger in words_in_message:
                return True
        return False

    def _handle_reply(self, sender: str, message: str):
        """Gère la génération et l'envoi de la réponse."""
        # Vérifier le délai minimum
        current_time = time.time()
        if current_time - self.last_reply_time < self.min_reply_delay:
            print("[AUTO-REPLY] Délai minimum non respecté, réponse ignorée")
            return

        # Générer la réponse via Mistral
        reply = self._generate_mistral_reply(sender, message)

        if reply:
            self._send_chat_message(reply)
            self.last_reply_time = time.time()

            if self.on_reply_sent:
                self.on_reply_sent(reply)

    def _generate_mistral_reply(self, sender: str, message: str) -> Optional[str]:
        """Génère une réponse via l'API Mistral."""
        if not HAS_REQUESTS:
            return None

        try:
            # Préparer le contexte de conversation
            self.conversation_history.append({
                "role": "user",
                "content": f"{sender}: {message}"
            })

            # Limiter l'historique
            if len(self.conversation_history) > self.max_history:
                self.conversation_history = self.conversation_history[-self.max_history:]

            # Prompt système pour le contexte Minecraft
            system_prompt = """Tu es un joueur de Minecraft sur un serveur français.
Tu dois répondre de manière naturelle, courte et amicale aux messages.
- Réponds en français
- Sois décontracté et amical
- Garde tes réponses courtes (1-2 phrases max)
- Utilise le langage courant des joueurs Minecraft
- Tu peux utiliser des abréviations comme "tkt", "bg", "mdr", etc.
- N'utilise pas d'emojis
- Ne mets pas de préfixe comme "Réponse:" ou ton pseudo
"""

            # Appel à l'API Mistral
            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json"
            }

            data = {
                "model": "mistral-small-latest",
                "messages": [
                    {"role": "system", "content": system_prompt},
                    *self.conversation_history
                ],
                "max_tokens": 100,
                "temperature": 0.7
            }

            response = requests.post(
                "https://api.mistral.ai/v1/chat/completions",
                headers=headers,
                json=data,
                timeout=10
            )

            if response.status_code == 200:
                result = response.json()
                reply = result["choices"][0]["message"]["content"].strip()

                # Nettoyer la réponse
                reply = self._clean_reply(reply)

                # Ajouter à l'historique
                self.conversation_history.append({
                    "role": "assistant",
                    "content": reply
                })

                print(f"[AUTO-REPLY] Réponse générée: {reply}")
                return reply
            else:
                error_msg = f"Erreur API Mistral: {response.status_code}"
                print(f"[AUTO-REPLY] {error_msg}")
                if self.on_error:
                    self.on_error(error_msg)

        except Exception as e:
            error_msg = f"Erreur génération réponse: {e}"
            print(f"[AUTO-REPLY] {error_msg}")
            if self.on_error:
                self.on_error(error_msg)

        return None

    def _clean_reply(self, reply: str) -> str:
        """Nettoie la réponse générée."""
        # Supprimer les guillemets
        reply = reply.strip('"\'')

        # Supprimer les préfixes potentiels
        prefixes_to_remove = ["Réponse:", "Assistant:", "Bot:", f"{self.pseudo}:"]
        for prefix in prefixes_to_remove:
            if reply.lower().startswith(prefix.lower()):
                reply = reply[len(prefix):].strip()

        # Limiter la longueur (limite du chat Minecraft ~256 chars)
        if len(reply) > 200:
            reply = reply[:197] + "..."

        return reply

    def _send_chat_message(self, message: str):
        """Envoie un message dans le chat Minecraft."""
        try:
            # Ouvrir le chat
            pydirectinput.keyDown("t")
            time.sleep(0.05)
            pydirectinput.keyUp("t")
            time.sleep(0.4)

            # Coller le message
            if pyperclip:
                pyperclip.copy(message)
                modifier_key = "command" if platform.system() == "Darwin" else "ctrl"
                pyautogui.hotkey(modifier_key, "a")
                time.sleep(0.05)
                pyautogui.hotkey(modifier_key, "v")
                time.sleep(0.1)
            else:
                # Fallback: taper le message
                for char in message:
                    pydirectinput.write(char)
                    time.sleep(0.03)

            time.sleep(0.2)

            # Envoyer
            pydirectinput.keyDown("enter")
            time.sleep(0.05)
            pydirectinput.keyUp("enter")

            print(f"[AUTO-REPLY] Message envoyé: {message}")

        except Exception as e:
            print(f"[AUTO-REPLY] Erreur envoi message: {e}")

    def is_running(self) -> bool:
        """Retourne l'état de la surveillance."""
        return self.running

    def get_stats(self) -> dict:
        """Retourne les statistiques de l'auto-reply."""
        return {
            "running": self.running,
            "pseudo": self.pseudo,
            "trigger_words_count": len(self.trigger_words),
            "api_configured": bool(self.api_key and self.api_key != "VOTRE_CLE_API_MISTRAL_ICI"),
            "conversation_history_size": len(self.conversation_history),
        }
