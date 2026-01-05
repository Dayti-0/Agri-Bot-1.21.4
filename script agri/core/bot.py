#!/usr/bin/env python3
"""
Bot d'agriculture automatique pour Minecraft.
Gère les cycles de plantation, récolte et arrosage sur un maximum de 30 stations.
"""

import importlib.util
import json
from pathlib import Path
import platform
import random
import threading
import time
from datetime import datetime
from typing import Dict

import pyautogui
import pydirectinput
from pynput.mouse import Controller as MouseController

from .log_monitor import LogMonitor

# Dépendance optionnelle pour le collage (évite les problèmes de layout clavier)
if importlib.util.find_spec("pyperclip"):
    import pyperclip
else:
    pyperclip = None

# Configuration des modules d'input
pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.1
pydirectinput.PAUSE = 0.05


class FarmingBot:
    """Bot principal pour l'automatisation agricole."""

    def __init__(self, config_path: str = "farming_config.json"):
        self.config_path = Path(config_path)
        self.config = self.load_config(config_path)
        self.mouse = MouseController()
        self.running = False
        self.paused = False
        self.state_path = self.config_path.with_suffix(".bucket_state.json")
        self.last_bucket_mode, self.bucket_slot, self.full_buckets_in_slot, self.bucket_count, self.last_water_refill_time = self.load_bucket_state()
        self.stop_event = threading.Event()
        self.log_monitor = LogMonitor(self.config["log_path"])
        self.water_refill_needed_this_session = None  # Calculé une fois par session

        self.delays = self.config["delays"]

        self.session_start_time = None
        self.stations_completed = 0
        self.is_first_session = True  # Flag pour réinitialiser au slot 1 uniquement au premier lancement

    def load_config(self, config_path: str) -> Dict:
        """Charge la configuration depuis un fichier JSON."""
        with open(config_path, "r", encoding="utf-8") as f:
            return json.load(f)

    def load_bucket_state(self) -> tuple[str | None, int, int, int, float | None]:
        """Charge le dernier mode de seaux, le slot, les seaux pleins, le bucket_count et le timestamp du dernier remplissage."""
        try:
            if self.state_path.exists():
                with open(self.state_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    return (
                        data.get("last_bucket_mode"),
                        data.get("bucket_slot", 1),
                        data.get("full_buckets_in_slot", 0),
                        data.get("bucket_count", 16),
                        data.get("last_water_refill_time")  # Timestamp du dernier remplissage
                    )
        except (json.JSONDecodeError, OSError):
            pass

        return None, 1, 0, 16, None

    def save_bucket_state(self) -> None:
        """Sauvegarde le mode de seaux, le slot, les seaux pleins, bucket_count et le timestamp du dernier remplissage."""
        try:
            with open(self.state_path, "w", encoding="utf-8") as f:
                json.dump({
                    "last_bucket_mode": self.last_bucket_mode,
                    "bucket_slot": self.bucket_slot,
                    "full_buckets_in_slot": self.full_buckets_in_slot,
                    "bucket_count": self.bucket_count,
                    "last_water_refill_time": self.last_water_refill_time
                }, f)
        except OSError:
            pass

    def human_delay(self, base_delay: float, variation: float = 0.2) -> None:
        """Ajoute un délai avec variation humaine."""
        actual_delay = base_delay + random.uniform(
            -variation * base_delay, variation * base_delay
        )
        time.sleep(max(0.05, actual_delay))

    def click(self, x: int, y: int, button: str = "left", human: bool = True) -> None:
        """Effectue un clic avec mouvement humain."""
        if human:
            pyautogui.moveTo(
                x, y, duration=random.uniform(0.15, 0.35), tween=pyautogui.easeInOutQuad
            )
        else:
            pyautogui.moveTo(x, y)

        self.human_delay(0.05)

        if button == "left":
            pyautogui.click(x, y, button="left")
        else:
            pyautogui.rightClick(x, y)

    def press_key(self, key: str, hold_time: float = 0) -> None:
        """Appuie sur une touche avec timing humain."""
        pydirectinput.keyDown(key)
        if hold_time > 0:
            self.human_delay(hold_time, 0.1)
        else:
            self.human_delay(0.05, 0.3)
        pydirectinput.keyUp(key)

    def type_text(self, text: str) -> None:
        """Tape du texte avec vitesse humaine."""
        for char in text:
            pydirectinput.write(char)
            self.human_delay(0.04, 0.5)

    def paste_text(self, text: str) -> bool:
        """Colle un texte complet pour éviter les erreurs de clavier."""
        if not pyperclip:
            return False

        try:
            pyperclip.copy(text)
        except pyperclip.PyperclipException:
            return False

        modifier_key = "command" if platform.system() == "Darwin" else "ctrl"

        pyautogui.hotkey(modifier_key, "a")
        self.human_delay(0.05, 0.2)
        pyautogui.hotkey(modifier_key, "v")
        self.human_delay(0.05, 0.2)

        return True

    def send_command(self, command: str) -> None:
        """Envoie une commande dans le chat."""
        self.press_key("t")
        self.human_delay(0.4)

        if not self.paste_text(command):
            self.type_text(command)
        self.human_delay(0.3)
        self.press_key("enter")
        self.human_delay(2.0)  # Délai après avoir envoyé une commande

    def connect_to_server(self) -> None:
        """Se connecte au serveur Minecraft."""
        print("[BOT] Préparation...")
        print("[BOT] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
        for i in range(5, 0, -1):
            print(f"[BOT] Démarrage dans {i}...")
            time.sleep(1)

        print("[BOT] Connexion au serveur...")

        self.click(
            self.config["positions"]["server_connect"]["x"],
            self.config["positions"]["server_connect"]["y"],
        )
        self.human_delay(2.0, 0.2)  # Délai après premier clic connexion serveur

        self.press_key("down")
        self.human_delay(0.8)

        # Clic droit pour ouvrir le menu (sans déplacer la souris)
        pyautogui.rightClick()
        self.human_delay(0.3)

        # Clic gauche sur server_confirm pour confirmer la connexion
        pos = self.config["positions"]["server_confirm"]
        self.click(pos["x"], pos["y"], button="left")
        self.human_delay(8.0, 0.2)  # Délai après connexion au bon serveur

        self.press_key("t")
        self.human_delay(0.2)
        self.press_key("escape")
        self.human_delay(0.5)

    def disconnect_from_server(self) -> None:
        """Se déconnecte du serveur."""
        print("[BOT] Déconnexion...")
        self.press_key("escape")
        self.human_delay(0.5)
        disconnect_pos = self.config["positions"]["disconnect"]
        self.click(disconnect_pos["x"], disconnect_pos["y"])
        self.human_delay(1.0)

    def check_time_restrictions(self) -> bool:
        """Vérifie si on peut se connecter selon l'heure."""
        now = datetime.now()
        current_time = now.time()

        if (
            datetime.strptime("05:50", "%H:%M").time()
            <= current_time
            <= datetime.strptime("06:30", "%H:%M").time()
        ):
            print("[BOT] Redémarrage serveur en cours, attente jusqu'à 6h31...")
            return False

        return True

    def needs_bucket_management(self) -> str:
        """Détermine le type de gestion des seaux nécessaire."""
        now = datetime.now()
        current_time = now.time()

        if (
            datetime.strptime("06:30", "%H:%M").time()
            <= current_time
            <= datetime.strptime("11:30", "%H:%M").time()
        ):
            return "drop"
        elif current_time > datetime.strptime("11:30", "%H:%M").time():
            return "retrieve"

        return "normal"

    def manage_buckets_morning(self) -> None:
        """Gère les seaux le matin (6h30-11h30).

        Jette 15 seaux depuis le slot actuel (sauvegardé) et garde 1 seau.
        Le seau restant reste dans le même slot pour l'arrosage.
        """
        print(f"[BOT] Transition vers mode matin - jet de 15 seaux depuis slot {self.bucket_slot}...")

        self.send_command("/home coffre1")
        self.human_delay(1.5)

        # Sélectionner le slot où sont les seaux (sauvegardé de la session précédente)
        self.press_key(str(self.bucket_slot))
        self.human_delay(0.3)

        for i in range(15):
            self.press_key("r")  # Touche "r" pour jeter (drop)
            self.human_delay(0.2, 0.3)
            print(f"[BOT] Seau {i+1}/15 jeté")

        # Le seau restant est toujours dans le même slot (pas de changement de slot)
        self.bucket_count = 1
        self.save_bucket_state()
        print(f"[BOT] Mode 1 seau activé - seau dans slot {self.bucket_slot}")

    def retrieve_buckets_afternoon(self) -> None:
        """Récupère les 15 seaux après 11h30."""
        print("[BOT] Récupération des 15 seaux...")

        self.send_command("/home coffre2")
        self.human_delay(2.0)

        pyautogui.rightClick()
        self.human_delay(3.0)  # Délai après ouverture coffre seaux

        pydirectinput.keyDown("shift")
        self.human_delay(0.2)
        self.click(
            self.config["positions"]["bucket_chest"]["x"],
            self.config["positions"]["bucket_chest"]["y"],
        )
        pydirectinput.keyUp("shift")
        self.human_delay(0.5)

        self.press_key("escape")
        self.human_delay(0.8)

        # Après récupération, les 16 seaux sont répartis entre slot 1 et 2
        # On se met sur le slot actuel (conservé de la session précédente)
        self.press_key(str(self.bucket_slot))
        self.bucket_count = 16
        self.full_buckets_in_slot = 0  # Les seaux récupérés sont vides, F7 les remplira
        self.save_bucket_state()
        print(f"[BOT] 16 seaux récupérés (slot {self.bucket_slot})")

    def manage_buckets_if_transition(self) -> None:
        """Gère les seaux uniquement lors d'un changement de plage horaire."""

        bucket_mode = self.needs_bucket_management()

        if bucket_mode == self.last_bucket_mode:
            return

        if bucket_mode == "drop":
            self.manage_buckets_morning()
        elif bucket_mode == "retrieve":
            self.retrieve_buckets_afternoon()

        self.last_bucket_mode = bucket_mode
        self.save_bucket_state()

    def should_refill_water(self) -> bool:
        """Détermine si on doit remplir les stations d'eau cette session.

        L'eau dure 12h exactement. La logique :
        - Première session après lancement (pas de timestamp) : l'utilisateur a rempli manuellement → pas de remplissage
        - Sessions suivantes : remplir si le temps écoulé + temps jusqu'à prochaine session >= 12h

        Returns:
            True si on doit remplir l'eau, False sinon.
        """
        WATER_DURATION = 12 * 60 * 60  # 12 heures en secondes
        current_time = time.time()
        session_pause = self.config.get("session_pause", 900)  # Temps jusqu'à prochaine session

        # Première session après lancement du script (pas de timestamp enregistré)
        # L'utilisateur a rempli manuellement avant de lancer le bot
        if self.last_water_refill_time is None:
            print("[BOT] Première session - stations déjà remplies manuellement, pas de remplissage d'eau")
            # On enregistre le timestamp actuel (sera sauvegardé à la fin de la session)
            return False

        # Calcul du temps écoulé depuis le dernier remplissage
        time_since_refill = current_time - self.last_water_refill_time
        time_until_next_session = session_pause

        # Si le temps total (écoulé + prochaine session) dépasse ou approche 12h, on doit remplir
        # On prend une marge de 10 minutes pour être sûr
        margin = 10 * 60  # 10 minutes de marge

        if time_since_refill + time_until_next_session >= WATER_DURATION - margin:
            hours_since = time_since_refill / 3600
            hours_until_next = time_until_next_session / 3600
            print(f"[BOT] Remplissage d'eau nécessaire - {hours_since:.1f}h depuis dernier remplissage, prochaine session dans {hours_until_next:.1f}h")
            return True
        else:
            hours_since = time_since_refill / 3600
            hours_remaining = (WATER_DURATION - time_since_refill) / 3600
            print(f"[BOT] Pas besoin de remplir l'eau - {hours_since:.1f}h depuis dernier remplissage, reste {hours_remaining:.1f}h d'eau")
            return False

    def harvest_and_plant(self, station: Dict) -> None:
        """Récolte et replante dans une station."""
        harvest_pos = station.get("harvest_pos")
        if not harvest_pos:
            harvest_pos = self.config["positions"].get("default_harvest")

        if not harvest_pos:
            print("[BOT] Position de récolte non configurée!")
            return

        # Combo clic droit puis clic gauche pour garantir la récolte, peu importe la plante
        self.click(harvest_pos["x"], harvest_pos["y"], button="right")
        self.human_delay(0.5)
        self.click(harvest_pos["x"], harvest_pos["y"], button="left")
        self.human_delay(0.5)

        self.press_key("escape")
        self.human_delay(0.3)

        self.press_key("0")
        self.human_delay(0.2)

        pydirectinput.keyDown("q")  # Touche "a" AZERTY = s'accroupir (crouch)
        self.human_delay(0.1)
        pyautogui.rightClick()
        self.human_delay(0.3)
        pydirectinput.keyUp("q")
        self.human_delay(0.2)

    def fill_station_with_water(self, is_last_station: bool = False) -> bool:
        """Remplit une station d'eau jusqu'à ce qu'elle soit pleine.

        Mode 16 seaux: Utilise les seaux pleins restants d'abord, puis change de slot et F7.
        Mode 1 seau: F7 + clic droit à chaque fois, pas de changement de slot.
        Ne pas s'accroupir pour remplir la station (seulement pour planter).

        Args:
            is_last_station: Si True, vide tous les seaux restants même si la station est pleine.
        """
        print(f"[BOT] Remplissage de la station avec eau (slot {self.bucket_slot}, {self.full_buckets_in_slot} seaux pleins)...")

        # Sélectionner le bon slot (sauvegardé de la session précédente)
        self.press_key(str(self.bucket_slot))
        self.human_delay(0.3)

        station_full = False
        fills_done = 0

        self.log_monitor.reset_detection()

        if self.bucket_count == 1:
            # Mode 1 seau: F7 + clic droit à chaque fois, pas de changement de slot
            while not station_full and fills_done < 50:
                # Appuyer sur F7 (macro) pour remplir le seau
                self.press_key("f7")
                self.human_delay(3.0)  # Délai entre remplir sceau et clic droit

                # Clic droit pour vider le seau dans la station
                pyautogui.rightClick()
                self.human_delay(3.0)  # Délai entre clic droit et remplir sceau

                fills_done += 1

                if self.log_monitor.check_station_full():
                    station_full = True
                    print(f"[BOT] Station pleine après {fills_done} remplissages")

        else:
            # Mode 16 seaux: Utiliser les seaux pleins restants d'abord
            # Si pas de seaux pleins, changer de slot et remplir avec F7
            if self.full_buckets_in_slot == 0:
                # Remplir les seaux du slot actuel avec F7 (sans changer de slot au démarrage)
                print(f"[BOT] Remplissage F7 du slot {self.bucket_slot}")
                self.press_key("f7")
                self.human_delay(3.0)  # Délai entre remplir les seaux et premier clic droit
                self.full_buckets_in_slot = 16

            while not station_full:
                # Clic droit pour vider un seau dans la station
                pyautogui.rightClick()
                self.human_delay(3.0)  # Délai entre chaque clic droit (3s pour éviter les bugs de comptage)

                fills_done += 1
                self.full_buckets_in_slot -= 1

                if self.log_monitor.check_station_full():
                    station_full = True
                    print(f"[BOT] Station pleine après {fills_done} remplissages ({self.full_buckets_in_slot} seaux pleins restants)")

                # Si plus de seaux pleins et station pas encore pleine, changer de slot et remplir
                if self.full_buckets_in_slot == 0 and not station_full:
                    # Changer de slot vers les seaux vides
                    if self.bucket_slot == 1:
                        self.press_key("2")
                        self.bucket_slot = 2
                    else:
                        self.press_key("1")
                        self.bucket_slot = 1
                    self.human_delay(0.3)
                    print(f"[BOT] Changement vers slot {self.bucket_slot} et remplissage F7")

                    # Remplir les 16 seaux avec F7
                    self.press_key("f7")
                    self.human_delay(3.0)  # Délai entre remplir les seaux et premier clic droit
                    self.full_buckets_in_slot = 16

                if fills_done >= 32:
                    print(
                        "[BOT] Limite de remplissage atteinte, passage à la station suivante"
                    )
                    break

        # Si c'est la dernière station, vider TOUS les seaux restants
        if is_last_station and self.bucket_count > 1 and self.full_buckets_in_slot > 0:
            print(f"[BOT] Dernière station - vidage des {self.full_buckets_in_slot} seaux restants...")
            remaining = self.full_buckets_in_slot
            for i in range(remaining):
                pyautogui.rightClick()
                self.human_delay(3.0)  # Délai entre chaque clic droit (3s pour éviter les bugs de comptage)
                self.full_buckets_in_slot -= 1
                print(f"[BOT] Seau {i+1}/{remaining} vidé")

        # Technique pour sortir d'un menu potentiel (si clic droit sur sceau vide a ouvert la station)
        # t ouvre le chat (ne fait rien si dans un menu), Echap ferme le chat ou le menu
        self.press_key("t")
        self.human_delay(0.1)
        self.press_key("escape")
        self.human_delay(0.2)

        # Sauvegarder l'état des seaux pour la prochaine station
        self.save_bucket_state()

        return station_full

    def process_station(self, station_index: int, station: Dict, total_stations: int, refill_water: bool = True) -> None:
        """Traite une station complète.

        Args:
            station_index: Index de la station (0-based)
            station: Configuration de la station
            total_stations: Nombre total de stations
            refill_water: Si True, remplit la station d'eau. Si False, saute le remplissage.
        """
        print(f"\n[BOT] Station {station_index + 1}/{total_stations} : {station['name']}")

        self.send_command(f"/home {station['name']}")
        self.human_delay(2.0, 0.2)

        # Sélectionner le slot des graines avant d'ouvrir la station
        # pour éviter de remplir la station si on a un seau d'eau en main
        self.press_key("0")
        self.human_delay(0.2)

        pyautogui.rightClick()
        self.human_delay(3.0)  # Délai après ouverture menu station

        self.harvest_and_plant(station)

        self.human_delay(0.5)

        # Remplir d'eau seulement si nécessaire
        if refill_water:
            # Vérifier si c'est la dernière station pour vider tous les seaux restants
            is_last_station = (station_index == total_stations - 1)
            self.fill_station_with_water(is_last_station)

        self.stations_completed += 1
        print(f"[BOT] Station {station_index + 1} complétée")

    def empty_all_buckets(self) -> None:
        """Vide tous les seaux pleins à la fin de la session."""
        if self.bucket_count == 1:
            print("[BOT] Mode 1 seau - pas de vidage nécessaire")
            return

        # Sélectionner le bon slot
        self.press_key(str(self.bucket_slot))
        self.human_delay(0.3)

        remaining = self.full_buckets_in_slot
        print(f"[BOT] Vidage des {remaining} seaux pleins restants (slot {self.bucket_slot})...")

        if remaining == 0:
            print("[BOT] Aucun seau plein à vider")
            return

        for i in range(remaining):
            # Clic droit pour vider le seau
            pyautogui.rightClick()
            self.human_delay(1.0)
            print(f"[BOT] Seau {i+1}/{remaining} vidé")

        # Tous les seaux sont maintenant vides
        self.full_buckets_in_slot = 0

        # Sauvegarder l'état pour la prochaine session
        self.save_bucket_state()

        print("[BOT] Tous les seaux vidés")

    def run_farming_session(self) -> None:
        """Exécute une session complète de farming."""
        try:
            self.session_start_time = datetime.now()
            self.stations_completed = 0

            print(f"\n{'='*60}")
            print(f"[BOT] Début de session à {self.session_start_time.strftime('%H:%M:%S')}")
            print(f"{'='*60}")

            if not self.check_time_restrictions():
                return

            self.connect_to_server()

            # Réinitialiser au slot 1 uniquement au premier lancement du script
            # car l'utilisateur place toujours les seaux dans le premier slot
            # Les reconnexions automatiques gardent le slot enregistré
            if self.is_first_session:
                self.bucket_slot = 1
                self.full_buckets_in_slot = 0

                # Définir bucket_count selon l'heure de démarrage
                # Entre 6:30-11:30: l'utilisateur met 1 seau dans le slot 1
                # Après 11:30: l'utilisateur met 16 seaux dans le slot 1
                bucket_mode = self.needs_bucket_management()
                if bucket_mode == "drop":
                    self.bucket_count = 1
                    print(f"[BOT] Mode matin détecté - 1 seau dans le slot 1")
                else:
                    self.bucket_count = 16
                    print(f"[BOT] Mode normal détecté - 16 seaux dans le slot 1")

                self.last_bucket_mode = bucket_mode
                self.save_bucket_state()
                self.is_first_session = False
            else:
                # Pour les reconnexions automatiques, gérer les transitions de période
                self.manage_buckets_if_transition()

            stations = self.config["stations"]
            total_stations = len(stations)
            if total_stations == 0:
                print("[BOT] Aucune station configurée. Ajoutez des homes dans l'interface avant de lancer.")
                return

            # Déterminer si on doit remplir l'eau cette session (l'eau dure 12h)
            refill_water = self.should_refill_water()

            for i, station in enumerate(stations):
                if self.stop_event.is_set():
                    print("[BOT] Arrêt demandé")
                    break

                self.process_station(i, station, total_stations, refill_water)

                if i < len(stations) - 1:
                    self.human_delay(0.8, 0.2)

            # Vider les seaux restants seulement si on a rempli l'eau
            if refill_water:
                self.empty_all_buckets()
                # Mettre à jour le timestamp du dernier remplissage
                self.last_water_refill_time = time.time()
                self.save_bucket_state()
                print("[BOT] Timestamp de remplissage d'eau mis à jour")
            elif self.last_water_refill_time is None:
                # Première session : enregistrer le timestamp même sans remplissage
                # (l'utilisateur a rempli manuellement)
                self.last_water_refill_time = time.time()
                self.save_bucket_state()
                print("[BOT] Première session - timestamp initial enregistré")

            self.disconnect_from_server()

            session_duration = (
                datetime.now() - self.session_start_time
            ).total_seconds() / 60
            print(f"\n[BOT] Session terminée - Durée: {session_duration:.1f} minutes")
            print(f"[BOT] Stations complétées: {self.stations_completed}/{total_stations}")

        except Exception as e:
            print(f"[BOT] Erreur durant la session: {e}")
            import traceback

            traceback.print_exc()

    def test_transition_day_to_morning(self) -> None:
        """Test: transition jour -> matin (jeter les seaux pour n'en garder qu'un)."""
        try:
            print("\n" + "=" * 60)
            print("[TEST] Transition jour -> matin (drop buckets)")
            print("=" * 60)

            print("[TEST] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
            for i in range(5, 0, -1):
                print(f"[TEST] Démarrage dans {i}...")
                time.sleep(1)

            self.connect_to_server()

            print("[TEST] Exécution de manage_buckets_morning()...")
            self.manage_buckets_morning()

            self.disconnect_from_server()

            print("[TEST] Test transition jour -> matin terminé")

        except Exception as e:
            print(f"[TEST] Erreur durant le test: {e}")
            import traceback
            traceback.print_exc()

    def test_transition_morning_to_day(self) -> None:
        """Test: transition matin -> jour (reprendre les seaux)."""
        try:
            print("\n" + "=" * 60)
            print("[TEST] Transition matin -> jour (retrieve buckets)")
            print("=" * 60)

            print("[TEST] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
            for i in range(5, 0, -1):
                print(f"[TEST] Démarrage dans {i}...")
                time.sleep(1)

            self.connect_to_server()

            print("[TEST] Exécution de retrieve_buckets_afternoon()...")
            self.retrieve_buckets_afternoon()

            self.disconnect_from_server()

            print("[TEST] Test transition matin -> jour terminé")

        except Exception as e:
            print(f"[TEST] Erreur durant le test: {e}")
            import traceback
            traceback.print_exc()

    def test_single_station(self) -> None:
        """Test: session sur une seule station avec vidage complet des seaux."""
        try:
            print("\n" + "=" * 60)
            print("[TEST] Session sur une seule station")
            print("=" * 60)

            print("[TEST] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
            for i in range(5, 0, -1):
                print(f"[TEST] Démarrage dans {i}...")
                time.sleep(1)

            self.connect_to_server()

            # Réinitialiser au slot 1 au premier lancement du script
            if self.is_first_session:
                self.bucket_slot = 1
                self.full_buckets_in_slot = 0

                # Définir bucket_count selon l'heure de démarrage
                bucket_mode = self.needs_bucket_management()
                if bucket_mode == "drop":
                    self.bucket_count = 1
                    print(f"[TEST] Mode matin détecté - 1 seau dans le slot 1")
                else:
                    self.bucket_count = 16
                    print(f"[TEST] Mode normal détecté - 16 seaux dans le slot 1")

                self.last_bucket_mode = bucket_mode
                self.save_bucket_state()
                self.is_first_session = False
            else:
                self.manage_buckets_if_transition()

            stations = self.config["stations"]
            if not stations:
                print("[TEST] Aucune station configurée!")
                self.disconnect_from_server()
                return

            station = stations[0]
            print(f"[TEST] Station de test: {station['name']}")

            # Déterminer si on doit remplir l'eau cette session
            refill_water = self.should_refill_water()

            # Utiliser process_station pour traiter la station
            self.process_station(0, station, 1, refill_water)

            # Vidage complet des seaux seulement si on a rempli l'eau
            if refill_water:
                self.empty_all_buckets()
                # Mettre à jour le timestamp du dernier remplissage
                self.last_water_refill_time = time.time()
                self.save_bucket_state()
            elif self.last_water_refill_time is None:
                # Première session : enregistrer le timestamp même sans remplissage
                self.last_water_refill_time = time.time()
                self.save_bucket_state()

            self.disconnect_from_server()

            print("[TEST] Test session unique terminé")

        except Exception as e:
            print(f"[TEST] Erreur durant le test: {e}")
            import traceback
            traceback.print_exc()

    def test_five_stations(self) -> None:
        """Test: session complète sur les 5 premières stations."""
        try:
            print("\n" + "=" * 60)
            print("[TEST] Session sur 5 stations")
            print("=" * 60)

            print("[TEST] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
            for i in range(5, 0, -1):
                print(f"[TEST] Démarrage dans {i}...")
                time.sleep(1)

            self.connect_to_server()

            # Réinitialiser au slot 1 au premier lancement du script
            if self.is_first_session:
                self.bucket_slot = 1
                self.full_buckets_in_slot = 0

                # Définir bucket_count selon l'heure de démarrage
                bucket_mode = self.needs_bucket_management()
                if bucket_mode == "drop":
                    self.bucket_count = 1
                    print(f"[TEST] Mode matin détecté - 1 seau dans le slot 1")
                else:
                    self.bucket_count = 16
                    print(f"[TEST] Mode normal détecté - 16 seaux dans le slot 1")

                self.last_bucket_mode = bucket_mode
                self.save_bucket_state()
                self.is_first_session = False
            else:
                self.manage_buckets_if_transition()

            stations = self.config["stations"]
            if not stations:
                print("[TEST] Aucune station configurée!")
                self.disconnect_from_server()
                return

            # Limiter à 5 stations max
            stations_to_test = stations[:5]
            total = len(stations_to_test)
            print(f"[TEST] Test sur {total} station(s)")

            # Déterminer si on doit remplir l'eau cette session
            refill_water = self.should_refill_water()

            for idx, station in enumerate(stations_to_test):
                if self.stop_event.is_set():
                    print("[TEST] Arrêt demandé")
                    break

                # Utiliser process_station pour traiter la station
                self.process_station(idx, station, total, refill_water)

                if idx < total - 1:
                    self.human_delay(0.8, 0.2)

            # Vidage complet des seaux seulement si on a rempli l'eau
            if refill_water:
                self.empty_all_buckets()
                # Mettre à jour le timestamp du dernier remplissage
                self.last_water_refill_time = time.time()
                self.save_bucket_state()
            elif self.last_water_refill_time is None:
                # Première session : enregistrer le timestamp même sans remplissage
                self.last_water_refill_time = time.time()
                self.save_bucket_state()

            self.disconnect_from_server()
            print("\n[TEST] Test session 5 stations terminé")

        except Exception as e:
            print(f"[TEST] Erreur durant le test: {e}")
            import traceback
            traceback.print_exc()

    def test_teleport_all_stations(self) -> None:
        """Test: téléportation à chaque station une par une."""
        try:
            print("\n" + "=" * 60)
            print("[TEST] Téléportation à toutes les stations")
            print("=" * 60)

            print("[TEST] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
            for i in range(5, 0, -1):
                print(f"[TEST] Démarrage dans {i}...")
                time.sleep(1)

            self.connect_to_server()

            stations = self.config["stations"]
            if not stations:
                print("[TEST] Aucune station configurée!")
                self.disconnect_from_server()
                return

            total = len(stations)
            for i, station in enumerate(stations):
                if self.stop_event.is_set():
                    print("[TEST] Arrêt demandé")
                    break

                print(f"[TEST] Téléportation {i+1}/{total}: {station['name']}")
                self.send_command(f"/home {station['name']}")
                self.human_delay(3.0, 0.3)

            self.disconnect_from_server()

            print("[TEST] Test téléportation terminé")

        except Exception as e:
            print(f"[TEST] Erreur durant le test: {e}")
            import traceback
            traceback.print_exc()

    def test_login_message_detection(self, pseudo: str = "") -> None:
        """Test: se connecte et surveille les messages pendant 5 minutes.

        Détecte:
        - Messages contenant le pseudo (ex: "tu as des blocs de laine pseudo?")
        - Messages de bonjour dans les 30 premières secondes
        - Messages "re" -> répond "re"
        """
        import os
        import re

        try:
            print("\n" + "=" * 60)
            print("[TEST LOGIN] Test détection de messages")
            print("=" * 60)

            # Récupérer le pseudo depuis la config si non fourni
            if not pseudo:
                pseudo = self.config.get("auto_reply", {}).get("pseudo", "")

            if not pseudo:
                print("[TEST LOGIN] ERREUR: Pseudo non configuré!")
                return

            pseudo_lower = pseudo.lower()
            print(f"[TEST LOGIN] Pseudo surveillé: {pseudo}")

            print("[TEST LOGIN] 5 secondes pour vous mettre sur la fenêtre Minecraft...")
            for i in range(5, 0, -1):
                print(f"[TEST LOGIN] Démarrage dans {i}...")
                time.sleep(1)

            self.connect_to_server()

            # Durée du test: 5 minutes (300 secondes)
            test_duration = 300
            start_time = time.time()
            connection_time = time.time()

            # Position dans le fichier log
            log_path = self.config.get("log_path", "")
            if not log_path or not os.path.exists(log_path):
                print("[TEST LOGIN] ERREUR: Fichier log non configuré ou introuvable!")
                self.disconnect_from_server()
                return

            # Se positionner à la fin du fichier log
            with open(log_path, "r", encoding="cp1252", errors="ignore") as f:
                f.seek(0, 2)
                file_position = f.tell()

            print(f"[TEST LOGIN] Surveillance active pendant {test_duration // 60} minutes...")
            print("[TEST LOGIN] Détection: pseudo mentionné, bonjour (30s), ou 're'")

            # Mots de salutation
            greetings = ["bonjour", "bonsoir", "salut", "slt", "hello", "hey", "yo", "yoo", "coucou"]

            while time.time() - start_time < test_duration:
                if self.stop_event.is_set():
                    print("[TEST LOGIN] Arrêt demandé")
                    break

                try:
                    with open(log_path, "r", encoding="cp1252", errors="ignore") as f:
                        f.seek(file_position)
                        new_lines = f.readlines()

                        if new_lines:
                            file_position = f.tell()

                            for line in new_lines:
                                line = line.strip()
                                if not line or "[CHAT]" not in line:
                                    continue

                                # Extraire le contenu du chat
                                chat_match = re.search(r'\[CHAT\]\s*(.+)$', line)
                                if not chat_match:
                                    continue

                                chat_content = chat_match.group(1)

                                # Parser le message pour extraire sender et message
                                sender, message = self._parse_chat_for_test(chat_content, pseudo_lower)

                                if not sender or not message:
                                    continue

                                # Ignorer ses propres messages
                                if pseudo_lower in sender.lower():
                                    continue

                                message_lower = message.lower().strip()
                                elapsed_since_connection = time.time() - connection_time

                                # Détection 1: "re" -> répondre "re"
                                if message_lower == "re":
                                    print(f"[TEST LOGIN] '{sender}' a dit 're' -> Réponse 're'")
                                    self.send_command("re")
                                    continue

                                # Détection 2: Pseudo mentionné dans le message
                                if pseudo_lower in message_lower:
                                    print(f"[TEST LOGIN] '{sender}' a mentionné ton pseudo: {message}")
                                    # On pourrait répondre ici si nécessaire
                                    continue

                                # Détection 3: Bonjour dans les 30 premières secondes
                                if elapsed_since_connection <= 30:
                                    words_in_message = re.findall(r'\b\w+\b', message_lower)
                                    for greeting in greetings:
                                        if greeting in words_in_message:
                                            print(f"[TEST LOGIN] '{sender}' a dit bonjour dans les 30s: {message}")
                                            break
                        else:
                            # Vérifier si le fichier a été tronqué
                            f.seek(0, 2)
                            current_size = f.tell()
                            if current_size < file_position:
                                print("[TEST LOGIN] Nouveau fichier log détecté")
                                file_position = 0

                except Exception as e:
                    print(f"[TEST LOGIN] Erreur lecture log: {e}")

                time.sleep(0.3)

                # Afficher le temps restant toutes les 30 secondes
                elapsed = int(time.time() - start_time)
                if elapsed > 0 and elapsed % 30 == 0:
                    remaining = test_duration - elapsed
                    print(f"[TEST LOGIN] Temps restant: {remaining // 60}m {remaining % 60}s")

            self.disconnect_from_server()
            print("[TEST LOGIN] Test terminé")

        except Exception as e:
            print(f"[TEST LOGIN] Erreur durant le test: {e}")
            import traceback
            traceback.print_exc()

    def _parse_chat_for_test(self, chat_content: str, pseudo_lower: str) -> tuple:
        """Parse un message de chat pour extraire le sender et le message."""
        import re

        # Ignorer les messages système
        system_prefixes = ["»", "Téléporté", "Mode de vol", "PASSE DE COMBAT", "SurvivalWorld"]
        for prefix in system_prefixes:
            if chat_content.startswith(prefix):
                return None, None

        # Format 1: Préfixes?Pseudo: Message
        match1 = re.match(r'^(?:\[[\d]+\]\s*)?(?:[^\s]*\s*)?([^:»]+?):\s*(.+)$', chat_content)
        if match1:
            sender = match1.group(1).strip()
            message = match1.group(2).strip()
            sender = re.sub(r'^[?§\[\]0-9a-zA-Z]*[?§]', '', sender)
            return sender, message

        # Format 2: [Rang] Préfixes?Pseudo » Message
        match2 = re.match(r'^(?:\[[\d]+\]\s*)?(?:[^\s]*\s*)?([^»]+?)\s*»\s*(.+)$', chat_content)
        if match2:
            sender = match2.group(1).strip()
            message = match2.group(2).strip()
            sender = re.sub(r'^[?§\[\]0-9a-zA-Z]*[?§]', '', sender)
            return sender, message

        return None, None

    def run_continuous(self) -> None:
        """Lance le bot en mode continu avec pauses entre les sessions."""
        self.running = True
        session_count = 0

        print("\n[BOT] Démarrage en mode continu")
        print(f"[BOT] Pause entre sessions: {self.config['session_pause']} secondes")

        try:
            while self.running and not self.stop_event.is_set():
                session_count += 1
                print(f"\n{'#'*60}")
                print(f"[BOT] SESSION #{session_count}")
                print(f"{'#'*60}")

                while not self.check_time_restrictions():
                    time.sleep(60)
                    if self.stop_event.is_set():
                        break

                if self.stop_event.is_set():
                    break

                self.run_farming_session()

                if self.stop_event.is_set():
                    break

                pause_time = self.config["session_pause"]
                print(
                    f"\n[BOT] Pause de {pause_time} secondes avant la prochaine session..."
                )

                for i in range(pause_time):
                    if self.stop_event.is_set():
                        break
                    time.sleep(1)

                    if i % 30 == 0 and i > 0:
                        remaining = pause_time - i
                        print(f"[BOT] Temps restant: {remaining} secondes")

        except KeyboardInterrupt:
            print("\n[BOT] Arrêt demandé par l'utilisateur")
        finally:
            self.running = False
            print("[BOT] Bot arrêté")
