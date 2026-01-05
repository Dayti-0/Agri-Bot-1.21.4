#!/usr/bin/env python3
"""
Module de surveillance des logs Minecraft.
Gestion optimisée avec threading et détection robuste.
"""

import os
import time
import threading
from collections import deque
from datetime import datetime
from typing import Callable, Optional


class LogMonitor:
    """Moniteur simple pour surveiller les logs Minecraft."""

    def __init__(self, log_path: str):
        self.log_path = log_path
        self.stop_message = "Votre Station de Croissance est déjà pleine d'eau !"
        self.station_full_detected = False
        self.last_position = 0

    def reset_detection(self):
        """Réinitialise la détection pour une nouvelle station."""
        self.station_full_detected = False
        # Se positionner à la fin du fichier pour ignorer les anciens messages
        try:
            if os.path.exists(self.log_path):
                with open(self.log_path, "r", encoding="cp1252", errors="ignore") as f:
                    f.seek(0, 2)  # Aller à la fin du fichier
                    self.last_position = f.tell()
        except Exception:
            pass

    def check_station_full(self) -> bool:
        """Vérifie si le message de station pleine est apparu."""
        if self.station_full_detected:
            return True

        try:
            if not os.path.exists(self.log_path):
                return False

            with open(self.log_path, "r", encoding="cp1252", errors="ignore") as f:
                f.seek(self.last_position)
                new_lines = f.readlines()
                self.last_position = f.tell()

                for line in new_lines:
                    if self.stop_message in line:
                        self.station_full_detected = True
                        return True

        except Exception as e:
            print(f"[LOG] Erreur lecture: {e}")

        return False


class AdvancedLogMonitor:
    """Moniteur avancé pour les logs Minecraft avec gestion thread-safe."""

    def __init__(self, log_path: str, buffer_size: int = 100):
        self.log_path = log_path
        self.buffer_size = buffer_size
        self.running = False

        self.patterns = {
            "station_full": "Votre Station de Croissance est déjà pleine d'eau !",
            "station_empty": "Votre Station de Croissance est vide",
            "seed_planted": "Vous avez planté une graine",
            "harvest_done": "Vous avez récolté",
            "connection": "joined the game",
            "disconnection": "left the game",
        }

        self.detections = {key: False for key in self.patterns.keys()}
        self.last_detections = {key: None for key in self.patterns.keys()}

        self.line_buffer = deque(maxlen=buffer_size)

        self.monitor_thread = None
        self.lock = threading.Lock()
        self.callbacks = {}

        self.file_position = 0
        self.last_check = time.time()

    def start(self):
        """Démarre la surveillance des logs."""
        if self.running:
            return

        self.running = True
        self.monitor_thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.monitor_thread.start()
        print(f"[LOG_MONITOR] Surveillance démarrée: {self.log_path}")

    def stop(self):
        """Arrête la surveillance."""
        self.running = False
        if self.monitor_thread:
            self.monitor_thread.join(timeout=2)
        print("[LOG_MONITOR] Surveillance arrêtée")

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
                        f.seek(0, 2)
                        current_size = f.tell()

                        if current_size < self.file_position:
                            print("[LOG_MONITOR] Nouveau fichier log détecté")
                            self.file_position = 0
                            self._reset_detections()

            except Exception as e:
                print(f"[LOG_MONITOR] Erreur: {e}")

            time.sleep(0.1)

    def _process_lines(self, lines):
        """Traite les nouvelles lignes du log."""
        with self.lock:
            for line in lines:
                line = line.strip()
                if not line:
                    continue

                self.line_buffer.append({"timestamp": datetime.now(), "content": line})

                for key, pattern in self.patterns.items():
                    if pattern in line:
                        self.detections[key] = True
                        self.last_detections[key] = datetime.now()

                        if key in self.callbacks:
                            self.callbacks[key](line)

                        print(f"[LOG_MONITOR] Détecté: {key}")

    def register_callback(self, pattern_key: str, callback: Callable):
        """Enregistre un callback pour un pattern spécifique."""
        if pattern_key in self.patterns:
            self.callbacks[pattern_key] = callback
            print(f"[LOG_MONITOR] Callback enregistré pour: {pattern_key}")
        else:
            print(f"[LOG_MONITOR] Pattern inconnu: {pattern_key}")

    def check_pattern(self, pattern_key: str, reset: bool = True) -> bool:
        """Vérifie si un pattern a été détecté."""
        with self.lock:
            detected = self.detections.get(pattern_key, False)
            if detected and reset:
                self.detections[pattern_key] = False
            return detected

    def wait_for_pattern(self, pattern_key: str, timeout: float = 30) -> bool:
        """Attend qu'un pattern soit détecté."""
        start_time = time.time()

        with self.lock:
            self.detections[pattern_key] = False

        while time.time() - start_time < timeout:
            if self.check_pattern(pattern_key, reset=False):
                with self.lock:
                    self.detections[pattern_key] = False
                return True
            time.sleep(0.1)

        return False

    def reset_detection(self, pattern_key: Optional[str] = None):
        """Réinitialise les détections."""
        with self.lock:
            if pattern_key:
                self.detections[pattern_key] = False
            else:
                self._reset_detections()

    def _reset_detections(self):
        """Réinitialise toutes les détections."""
        for key in self.detections.keys():
            self.detections[key] = False

    def get_recent_lines(self, count: int = 10) -> list:
        """Récupère les dernières lignes du buffer."""
        with self.lock:
            return list(self.line_buffer)[-count:]

    def add_custom_pattern(self, key: str, pattern: str):
        """Ajoute un pattern personnalisé à surveiller."""
        with self.lock:
            self.patterns[key] = pattern
            self.detections[key] = False
            self.last_detections[key] = None
        print(f"[LOG_MONITOR] Pattern ajouté: {key} = '{pattern}'")

    def get_stats(self) -> dict:
        """Récupère les statistiques de surveillance."""
        with self.lock:
            stats = {
                "running": self.running,
                "file_position": self.file_position,
                "buffer_size": len(self.line_buffer),
                "patterns_count": len(self.patterns),
                "detections": {},
            }

            for key, last_time in self.last_detections.items():
                if last_time:
                    stats["detections"][key] = {
                        "last_detected": last_time.strftime("%H:%M:%S"),
                        "seconds_ago": (datetime.now() - last_time).total_seconds(),
                    }

        return stats


class SmartWaterFiller:
    """Module intelligent pour le remplissage d'eau avec détection avancée."""

    def __init__(self, log_monitor: AdvancedLogMonitor):
        self.monitor = log_monitor
        self.filling_active = False
        self.fills_count = 0
        self.station_full = False

        self.monitor.register_callback("station_full", self._on_station_full)

    def _on_station_full(self, line: str):
        """Callback quand une station est pleine."""
        self.station_full = True
        self.filling_active = False
        print(f"[WATER] Station pleine détectée après {self.fills_count} remplissages")

    def start_filling(self):
        """Commence le remplissage d'une nouvelle station."""
        self.filling_active = True
        self.station_full = False
        self.fills_count = 0
        self.monitor.reset_detection("station_full")
        print("[WATER] Début du remplissage de la station")

    def register_fill(self):
        """Enregistre un remplissage effectué."""
        if self.filling_active:
            self.fills_count += 1

    def is_station_full(self) -> bool:
        """Vérifie si la station est pleine."""
        return self.station_full or self.monitor.check_pattern("station_full", reset=False)

    def get_fill_count(self) -> int:
        """Récupère le nombre de remplissages effectués."""
        return self.fills_count

    def wait_for_full(self, timeout: float = 60) -> bool:
        """Attend que la station soit pleine."""
        return self.monitor.wait_for_pattern("station_full", timeout)
