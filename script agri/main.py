#!/usr/bin/env python3
"""
PyroFarm Bot - Bot d'agriculture automatique pour Minecraft.
Point d'entrée principal pour lancer l'interface graphique.
"""

from gui import ConfigLauncherApp


def main() -> None:
    """Lance l'application."""
    app = ConfigLauncherApp()
    app.run()


if __name__ == "__main__":
    main()
