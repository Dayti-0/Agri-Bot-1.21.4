#!/usr/bin/env python3
"""Orchestration du bot dans des threads contrôlables depuis l'interface."""

import threading
from pathlib import Path
from typing import Optional

from .config import load_config
from core.bot import FarmingBot


class BotController:
    """Démarre ou arrête le bot sans bloquer l'interface graphique."""

    def __init__(self, config_path: str = "farming_config.json") -> None:
        self.config_path = Path(config_path)
        self.bot: Optional[FarmingBot] = None
        self.thread: Optional[threading.Thread] = None
        self.mode: Optional[str] = None

    def _ensure_bot(self) -> None:
        """Crée une instance de bot avec la configuration fusionnée."""
        config = load_config(str(self.config_path))
        self.bot = FarmingBot(config_path=str(self.config_path))
        self.bot.config = config

    def _run_session(self) -> None:
        self.mode = "session"
        self._ensure_bot()
        self.bot.run_farming_session()
        self.mode = None

    def _run_continuous(self) -> None:
        self.mode = "continuous"
        self._ensure_bot()
        self.bot.run_continuous()
        self.mode = None

    def start_session(self) -> None:
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_session, daemon=True)
        self.thread.start()

    def start_continuous(self) -> None:
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_continuous, daemon=True)
        self.thread.start()

    def _run_test_transition_morning(self) -> None:
        self.mode = "test"
        self._ensure_bot()
        self.bot.test_transition_day_to_morning()
        self.mode = None

    def _run_test_transition_afternoon(self) -> None:
        self.mode = "test"
        self._ensure_bot()
        self.bot.test_transition_morning_to_day()
        self.mode = None

    def _run_test_single_station(self) -> None:
        self.mode = "test"
        self._ensure_bot()
        self.bot.test_single_station()
        self.mode = None

    def _run_test_teleport_stations(self) -> None:
        self.mode = "test"
        self._ensure_bot()
        self.bot.test_teleport_all_stations()
        self.mode = None

    def _run_test_five_stations(self) -> None:
        self.mode = "test"
        self._ensure_bot()
        self.bot.test_five_stations()
        self.mode = None

    def _run_test_login_message_detection(self) -> None:
        self.mode = "test"
        self._ensure_bot()
        self.bot.test_login_message_detection()
        self.mode = None

    def start_test_transition_morning(self) -> None:
        """Lance le test de transition jour -> matin."""
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_test_transition_morning, daemon=True)
        self.thread.start()

    def start_test_transition_afternoon(self) -> None:
        """Lance le test de transition matin -> jour."""
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_test_transition_afternoon, daemon=True)
        self.thread.start()

    def start_test_single_station(self) -> None:
        """Lance le test sur une seule station."""
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_test_single_station, daemon=True)
        self.thread.start()

    def start_test_teleport_stations(self) -> None:
        """Lance le test de téléportation à toutes les stations."""
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_test_teleport_stations, daemon=True)
        self.thread.start()

    def start_test_five_stations(self) -> None:
        """Lance le test sur 5 stations."""
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_test_five_stations, daemon=True)
        self.thread.start()

    def start_test_login_message_detection(self) -> None:
        """Lance le test de détection de messages."""
        if self.is_running:
            return
        self.thread = threading.Thread(target=self._run_test_login_message_detection, daemon=True)
        self.thread.start()

    def stop(self) -> None:
        """Demande l'arrêt du bot et attend la fin du thread."""
        if self.bot:
            self.bot.running = False
            self.bot.stop_event.set()
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)

    @property
    def is_running(self) -> bool:
        return bool(self.thread and self.thread.is_alive())
