#!/usr/bin/env python3
"""Gestion centralisée de la configuration du bot."""

import json
from pathlib import Path
from typing import Any, Dict

DEFAULT_CONFIG: Dict[str, Any] = {
    "log_path": "",
    "plant_settings": {
        "plant_type": "",
        "growth_time": 600,
        "growth_boost": 0,
        "description": "Le clic de récolte est déterminé automatiquement selon la plante",
    },
    "session_pause": 900,
    "session_pause_description": "Temps d'attente en secondes entre les sessions (900 = 15 minutes)",
    "delays": {
        "short": 0.3,
        "medium": 0.8,
        "long": 1.5,
        "human_variation": 0.25,
        "startup_delay": 5,
        "description": "Délais en secondes - augmentez si le bot va trop vite",
    },
    "positions": {
        "server_connect": {"x": 0, "y": 0, "description": "Position du serveur dans la liste"},
        "server_confirm": {"x": 0, "y": 0, "description": "Position pour confirmer la connexion"},
        "disconnect": {"x": 0, "y": 0, "description": "Position du bouton déconnexion"},
        "default_harvest": {"x": 0, "y": 0, "description": "Position par défaut pour récolter"},
        "bucket_chest": {"x": 0, "y": 0, "description": "Position des seaux dans le coffre"},
    },
    "stations": [],
    "homes_special": {
        "coffre1": "coffre1",
        "coffre2": "coffre2",
        "description": "Homes spéciaux pour la gestion des seaux",
    },
    "auto_reply": {
        "enabled": False,
        "pseudo": "Dayti",
        "description": "Paramètres pour l'auto-réponse avec Mistral AI",
    },
}


def merge_defaults(current: Dict[str, Any], default: Dict[str, Any]) -> Dict[str, Any]:
    """Fusionne récursivement une configuration existante avec les valeurs par défaut."""
    merged = dict(default)
    for key, value in current.items():
        if key in merged and isinstance(merged[key], dict) and isinstance(value, dict):
            merged[key] = merge_defaults(value, merged[key])
        else:
            merged[key] = value
    return merged


def load_config(config_path: str = "farming_config.json") -> Dict[str, Any]:
    """Charge le fichier JSON en fusionnant les valeurs manquantes avec le modèle."""
    path = Path(config_path)
    if not path.exists():
        return dict(DEFAULT_CONFIG)
    try:
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError:
        return dict(DEFAULT_CONFIG)
    return merge_defaults(data, DEFAULT_CONFIG)


def save_config(config: Dict[str, Any], config_path: str = "farming_config.json") -> None:
    """Enregistre la configuration au format JSON."""
    path = Path(config_path)
    with path.open("w", encoding="utf-8") as f:
        json.dump(config, f, indent=4, ensure_ascii=False)
