#!/usr/bin/env python3
"""Données des plantes avec leurs temps de croissance."""

from typing import Dict, NamedTuple


class PlantData(NamedTuple):
    """Données d'une plante."""
    temps_tige: int  # Temps de croissance de la tige en minutes
    temps_fruit: int  # Temps de croissance des fruits en minutes (0 si pas de fruits additionnels)
    nb_fruits: int  # Nombre de fruits (1 si fruit unique/pas de fruits)


# Dictionnaire des plantes avec leurs temps de croissance
# Format: nom -> (temps_tige, temps_fruit, nb_fruits)
PLANTS: Dict[str, PlantData] = {
    "Concombre": PlantData(temps_tige=80, temps_fruit=0, nb_fruits=1),
    "Oignons": PlantData(temps_tige=40, temps_fruit=0, nb_fruits=1),
    "Laitue": PlantData(temps_tige=10, temps_fruit=0, nb_fruits=1),
    "Pois": PlantData(temps_tige=480, temps_fruit=80, nb_fruits=3),  # 240 / 3 = 80 min par fruit
    "Tomates": PlantData(temps_tige=60, temps_fruit=20, nb_fruits=2),  # 40 / 2 = 20 min par fruit
    "Poivron": PlantData(temps_tige=120, temps_fruit=120, nb_fruits=4),  # 480 / 4 = 120 min par fruit
    "Zucchini": PlantData(temps_tige=320, temps_fruit=0, nb_fruits=1),
    "Ail": PlantData(temps_tige=120, temps_fruit=0, nb_fruits=1),
    "Glycine glacée": PlantData(temps_tige=20, temps_fruit=0, nb_fruits=1),
    "Wazabi": PlantData(temps_tige=600, temps_fruit=0, nb_fruits=1),
    "Courgette": PlantData(temps_tige=1200, temps_fruit=0, nb_fruits=1),
    "Piment de cayenne": PlantData(temps_tige=240, temps_fruit=0, nb_fruits=1),
    "Vixen": PlantData(temps_tige=60, temps_fruit=0, nb_fruits=1),
    "Lune Akari": PlantData(temps_tige=40, temps_fruit=45, nb_fruits=1),  # Fruit unique
    "Nénuphar": PlantData(temps_tige=300, temps_fruit=120, nb_fruits=3),  # 360 / 3 = 120 min par fruit
    "Chou": PlantData(temps_tige=240, temps_fruit=160, nb_fruits=3),  # 480 / 3 = 160 min par fruit
    "Plume de lave": PlantData(temps_tige=120, temps_fruit=240, nb_fruits=4),  # 960 / 4 = 240 min par fruit
    "Fleur du brasier": PlantData(temps_tige=40, temps_fruit=0, nb_fruits=1),
    "Brocoli": PlantData(temps_tige=120, temps_fruit=80, nb_fruits=3),  # 240 / 3 = 80 min par fruit
    "Iris Pyrobrase": PlantData(temps_tige=1440, temps_fruit=0, nb_fruits=1),
    "Vénus attrape-mouche": PlantData(temps_tige=300, temps_fruit=60, nb_fruits=1),  # Fruit unique
    "Graine de l'enfer": PlantData(temps_tige=190, temps_fruit=0, nb_fruits=1),
    "Âme gelée": PlantData(temps_tige=80, temps_fruit=180, nb_fruits=5),  # 900 / 5 = 180 min par fruit
    "Pommes des ténèbres": PlantData(temps_tige=360, temps_fruit=180, nb_fruits=4),  # 720 / 4 = 180 min par fruit
    "Cœur du vide": PlantData(temps_tige=960, temps_fruit=0, nb_fruits=1),
    "Orchidée Abyssale": PlantData(temps_tige=360, temps_fruit=100, nb_fruits=3),  # 300 / 3 = 100 min par fruit
}


def get_plant_names() -> list:
    """Retourne la liste des noms de plantes triée."""
    return sorted(PLANTS.keys())


def get_harvest_click_type(plant_name: str) -> str:
    """
    Détermine le type de clic pour la récolte en fonction de la plante.

    - Plantes à 1 fruit (temps_fruit == 0) → clic gauche
    - Plantes avec tige + fruits (temps_fruit > 0) → clic droit

    Args:
        plant_name: Nom de la plante

    Returns:
        "left" pour clic gauche, "right" pour clic droit.
        Retourne "right" par défaut si la plante n'est pas trouvée.
    """
    if plant_name not in PLANTS:
        return "right"

    plant = PLANTS[plant_name]
    # Si la plante a un temps de fruit > 0, c'est une plante avec tige + fruits (clic droit)
    # Sinon c'est une plante à 1 fruit (clic gauche)
    return "right" if plant.temps_fruit > 0 else "left"


def temps_total_croissance(plant_name: str, boost: float = 0) -> int:
    """
    Calcule le temps total de croissance en minutes avec le boost appliqué.

    Formules:
    - Pour nb_fruits > 1: (temps_tige / (1 + boost/100)) + (nb_fruits * temps_fruit)
      Le boost s'applique uniquement à la tige, les fruits restent inchangés.
    - Pour nb_fruits == 1: (temps_tige + temps_fruit) / (1 + boost/100)
      Le boost s'applique à l'ensemble du temps.

    Args:
        plant_name: Nom de la plante
        boost: Pourcentage de boost de croissance (0-100+). Les valeurs négatives sont ignorées.

    Returns:
        Temps total de croissance en minutes, arrondi à l'entier supérieur.
    """
    if plant_name not in PLANTS:
        return 0

    plant = PLANTS[plant_name]

    # Boost négatif ignoré (borné à 0)
    boost = max(0, boost)

    diviseur = 1 + boost / 100

    if plant.nb_fruits > 1:
        # Boost uniquement sur la tige, fruits inchangés
        temps_tige_booste = plant.temps_tige / diviseur
        temps_fruits_total = plant.nb_fruits * plant.temps_fruit
        total = temps_tige_booste + temps_fruits_total
    else:
        # Boost sur l'ensemble (tige + fruit éventuel)
        total = (plant.temps_tige + plant.temps_fruit) / diviseur

    # Arrondi à l'entier supérieur
    import math
    return math.ceil(total)


def temps_total_en_secondes(plant_name: str, boost: float = 0) -> int:
    """
    Calcule le temps total de croissance en secondes.

    Args:
        plant_name: Nom de la plante
        boost: Pourcentage de boost de croissance

    Returns:
        Temps total de croissance en secondes.
    """
    return temps_total_croissance(plant_name, boost) * 60


def format_temps(minutes: int) -> str:
    """
    Formate un temps en minutes en format lisible (Xh Ym).

    Args:
        minutes: Temps en minutes

    Returns:
        Chaîne formatée (ex: "1h 20m" ou "40m")
    """
    heures = minutes // 60
    mins = minutes % 60

    if heures > 0 and mins > 0:
        return f"{heures}h {mins}m"
    elif heures > 0:
        return f"{heures}h"
    else:
        return f"{mins}m"
