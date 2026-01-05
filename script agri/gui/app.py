#!/usr/bin/env python3
"""
Interface graphique principale pour configurer et lancer le bot d'agriculture.
Regroupe l'édition du fichier farming_config.json et le contrôle du bot.
"""

import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Dict, List, Tuple

from pynput import mouse

from utils.config import DEFAULT_CONFIG, load_config, save_config
from utils.controller import BotController
from utils.plants import (
    PLANTS,
    format_temps,
    get_plant_names,
    temps_total_croissance,
    temps_total_en_secondes,
)
from core.auto_reply import AutoReplyBot


class ConfigLauncherApp:
    """Fenêtre principale avec onglets Configuration et Lancement."""

    def __init__(self, config_path: str = "farming_config.json") -> None:
        self.config_path = Path(config_path)
        self.config_data: Dict = {}
        self.position_vars: Dict[str, Tuple[tk.IntVar, tk.IntVar]] = {}
        self.station_data: List[Dict] = []
        self._position_listener: mouse.Listener | None = None

        self.bot_controller = BotController(config_path=config_path)
        self.auto_reply_bot: AutoReplyBot | None = None

        self.root = tk.Tk()
        self.root.title("PyroFarm Bot - Interface")
        self.root.geometry("900x820")

        self._build_widgets()
        self._load_and_populate()

    def _load_and_populate(self) -> None:
        self.config_data = load_config(str(self.config_path))
        self._populate_fields()

    def _save_from_form(self) -> None:
        try:
            delays = {
                "short": float(self.short_delay_var.get()),
                "medium": float(self.medium_delay_var.get()),
                "long": float(self.long_delay_var.get()),
                "human_variation": float(self.human_variation_var.get()),
                "startup_delay": float(self.startup_delay_var.get()),
                "description": self.config_data.get("delays", {}).get(
                    "description", DEFAULT_CONFIG["delays"]["description"]
                ),
            }

            positions: Dict[str, Dict] = {}
            for name, (x_var, y_var) in self.position_vars.items():
                base_pos = self.config_data.get("positions", {}).get(name, {})
                positions[name] = {
                    "x": int(x_var.get()),
                    "y": int(y_var.get()),
                    "description": base_pos.get("description", ""),
                }

            homes_special = {
                "coffre1": self.coffre1_var.get().strip(),
                "coffre2": self.coffre2_var.get().strip(),
                "description": self.config_data.get("homes_special", {}).get(
                    "description", DEFAULT_CONFIG["homes_special"]["description"]
                ),
            }

            auto_reply = {
                "enabled": self.auto_reply_enabled_var.get(),
                "pseudo": self.pseudo_var.get().strip(),
                "description": self.config_data.get("auto_reply", {}).get(
                    "description", DEFAULT_CONFIG["auto_reply"]["description"]
                ),
            }

            # Récupérer le boost (borné à 0 minimum)
            try:
                growth_boost = max(0, float(self.growth_boost_var.get()))
            except (ValueError, tk.TclError):
                growth_boost = 0

            plant_type = self.plant_type_var.get().strip()

            # Calculer le temps de croissance en secondes
            if plant_type in PLANTS:
                growth_time = temps_total_en_secondes(plant_type, growth_boost)
            else:
                growth_time = int(self.growth_time_var.get())

            plant_settings = {
                "plant_type": plant_type,
                "growth_time": growth_time,
                "growth_boost": growth_boost,
                "description": self.config_data.get("plant_settings", {}).get(
                    "description", DEFAULT_CONFIG["plant_settings"]["description"]
                ),
            }

            # La pause entre sessions est le temps de croissance
            session_pause = growth_time
            log_path = self.log_path_var.get().strip()

            stations_clean = [
                {"name": station.get("name", ""), "harvest_pos": station.get("harvest_pos")}
                for station in self.station_data
                if station.get("name", "")
            ]

            updated_config = {
                "log_path": log_path,
                "plant_settings": plant_settings,
                "session_pause": session_pause,
                "session_pause_description": self.config_data.get(
                    "session_pause_description", DEFAULT_CONFIG["session_pause_description"]
                ),
                "delays": delays,
                "positions": positions,
                "stations": stations_clean,
                "homes_special": homes_special,
                "auto_reply": auto_reply,
            }

            save_config(updated_config, str(self.config_path))
            messagebox.showinfo("Sauvegarde", "Configuration enregistrée.")
        except ValueError as exc:
            messagebox.showerror("Valeur invalide", f"Impossible de sauvegarder: {exc}")

    def _build_widgets(self) -> None:
        main_frame = ttk.Frame(self.root, padding=10)
        main_frame.pack(fill=tk.BOTH, expand=True)

        self.notebook = ttk.Notebook(main_frame)
        self.notebook.pack(fill=tk.BOTH, expand=True)

        self.config_tab = ttk.Frame(self.notebook, padding=10)
        self.launch_tab = ttk.Frame(self.notebook, padding=10)

        self.notebook.add(self.config_tab, text="Configuration")
        self.notebook.add(self.launch_tab, text="Lancement")

        self._build_config_tab()
        self._build_launch_tab()

        action_frame = ttk.Frame(main_frame)
        action_frame.pack(fill=tk.X, pady=(10, 0))
        ttk.Button(action_frame, text="Recharger", command=self._load_and_populate).pack(
            side=tk.LEFT, padx=4
        )
        ttk.Button(action_frame, text="Sauvegarder", command=self._save_from_form).pack(
            side=tk.RIGHT, padx=4
        )

    def _build_config_tab(self) -> None:
        # Scrollable config tab
        canvas = tk.Canvas(self.config_tab)
        scrollbar = ttk.Scrollbar(self.config_tab, orient="vertical", command=canvas.yview)
        scrollable_frame = ttk.Frame(canvas)

        scrollable_frame.bind(
            "<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all"))
        )

        canvas.create_window((0, 0), window=scrollable_frame, anchor="nw")
        canvas.configure(yscrollcommand=scrollbar.set)

        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        # Log file frame
        log_frame = ttk.LabelFrame(scrollable_frame, text="Fichier de log Minecraft", padding=10)
        log_frame.pack(fill=tk.X, padx=5, pady=5)

        self.log_path_var = tk.StringVar()
        log_entry = ttk.Entry(log_frame, textvariable=self.log_path_var)
        log_entry.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))
        ttk.Button(log_frame, text="Parcourir", command=self._browse_log_path).pack(side=tk.LEFT)

        # Plant settings frame
        plant_frame = ttk.LabelFrame(scrollable_frame, text="Paramètres de plante", padding=10)
        plant_frame.pack(fill=tk.X, padx=5, pady=5)

        # Sélecteur de plante (dropdown)
        ttk.Label(plant_frame, text="Plante").grid(row=0, column=0, sticky=tk.W, pady=3)
        self.plant_type_var = tk.StringVar()
        plant_names = get_plant_names()
        self.plant_combo = ttk.Combobox(
            plant_frame,
            textvariable=self.plant_type_var,
            values=plant_names,
            state="readonly",
            width=28,
        )
        self.plant_combo.grid(row=0, column=1, sticky=tk.W, pady=3)
        self.plant_combo.bind("<<ComboboxSelected>>", self._on_plant_or_boost_change)

        # Boost de croissance
        ttk.Label(plant_frame, text="Boost croissance (%)").grid(row=1, column=0, sticky=tk.W, pady=3)
        self.growth_boost_var = tk.DoubleVar(value=0)
        boost_frame = ttk.Frame(plant_frame)
        boost_frame.grid(row=1, column=1, sticky=tk.W, pady=3)
        boost_entry = ttk.Entry(boost_frame, textvariable=self.growth_boost_var, width=10)
        boost_entry.pack(side=tk.LEFT)
        boost_entry.bind("<KeyRelease>", self._on_plant_or_boost_change)
        ttk.Label(boost_frame, text="%", foreground="#555").pack(side=tk.LEFT, padx=(2, 0))

        # Affichage du temps calculé
        ttk.Label(plant_frame, text="Temps de croissance").grid(row=2, column=0, sticky=tk.W, pady=3)
        self.growth_time_display_var = tk.StringVar(value="-- (sélectionnez une plante)")
        ttk.Label(
            plant_frame,
            textvariable=self.growth_time_display_var,
            font=("Arial", 10, "bold"),
            foreground="#006600",
        ).grid(row=2, column=1, sticky=tk.W, pady=3)

        # Variable interne pour stocker le temps calculé en secondes
        self.growth_time_var = tk.IntVar(value=600)

        # Auto-reply section
        auto_reply_frame = ttk.LabelFrame(scrollable_frame, text="Auto-Reply (Mistral AI)", padding=10)
        auto_reply_frame.pack(fill=tk.X, padx=5, pady=5)

        # Pseudo
        ttk.Label(auto_reply_frame, text="Pseudo").grid(row=0, column=0, sticky=tk.W, pady=3)
        self.pseudo_var = tk.StringVar(value="Dayti")
        ttk.Entry(auto_reply_frame, textvariable=self.pseudo_var, width=20).grid(
            row=0, column=1, sticky=tk.W, pady=3
        )
        ttk.Label(auto_reply_frame, text="(ton pseudo en jeu)", foreground="#555").grid(
            row=0, column=2, sticky=tk.W, padx=8
        )

        # Bouton activer/désactiver
        self.auto_reply_enabled_var = tk.BooleanVar(value=False)
        self.auto_reply_btn = ttk.Button(
            auto_reply_frame, text="Activer Auto-Reply", command=self._toggle_auto_reply
        )
        self.auto_reply_btn.grid(row=1, column=0, columnspan=2, pady=8, sticky=tk.W)

        self.auto_reply_status_var = tk.StringVar(value="Auto-reply désactivé")
        ttk.Label(
            auto_reply_frame,
            textvariable=self.auto_reply_status_var,
            foreground="#666",
        ).grid(row=1, column=2, sticky=tk.W, padx=8)

        ttk.Label(
            auto_reply_frame,
            text="Fichiers: auto_reply_wordlist.txt (mots déclencheurs) | mistral_api_key.txt (clé API)",
            foreground="#555",
            font=("Arial", 8),
        ).grid(row=2, column=0, columnspan=3, sticky=tk.W, pady=(4, 0))

        # Variables pour les délais (utilisées en interne avec valeurs par défaut)
        self.short_delay_var = tk.DoubleVar(value=0.3)
        self.medium_delay_var = tk.DoubleVar(value=0.8)
        self.long_delay_var = tk.DoubleVar(value=1.5)
        self.human_variation_var = tk.DoubleVar(value=0.25)
        self.startup_delay_var = tk.DoubleVar(value=5)

        # Extra parameters frame
        extras_frame = ttk.LabelFrame(scrollable_frame, text="Autres paramètres", padding=10)
        extras_frame.pack(fill=tk.X, padx=5, pady=5)

        ttk.Label(extras_frame, text="Home Drop").grid(row=0, column=0, sticky=tk.W, pady=3)
        self.coffre1_var = tk.StringVar()
        ttk.Entry(extras_frame, textvariable=self.coffre1_var, width=20).grid(
            row=0, column=1, sticky=tk.W, pady=3
        )

        ttk.Label(extras_frame, text="Home coffre").grid(row=1, column=0, sticky=tk.W, pady=3)
        self.coffre2_var = tk.StringVar()
        ttk.Entry(extras_frame, textvariable=self.coffre2_var, width=20).grid(
            row=1, column=1, sticky=tk.W, pady=3
        )

        # Variable interne pour la pause entre sessions (calculée automatiquement)
        self.session_pause_var = tk.IntVar(value=900)

        # Positions frame
        self.position_container = ttk.LabelFrame(
            scrollable_frame, text="Positions de clic", padding=10
        )
        self.position_container.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.position_hint_var = tk.StringVar(
            value='Cliquez sur "Modifier" puis cliquez n\'importe où pour enregistrer la position.'
        )

        # Stations frame
        self._build_stations_section(scrollable_frame)

    def _build_stations_section(self, parent) -> None:
        stations_frame = ttk.LabelFrame(parent, text="Stations", padding=10)
        stations_frame.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)

        top_frame = ttk.Frame(stations_frame)
        top_frame.pack(fill=tk.BOTH, expand=True, pady=(0, 8))

        self.station_tree = ttk.Treeview(top_frame, columns=("name",), show="headings", height=10)
        self.station_tree.heading("name", text="Home")
        self.station_tree.column("name", width=640)
        self.station_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self.station_tree.bind("<Double-1>", self._start_inline_station_edit)

        scrollbar = ttk.Scrollbar(top_frame, orient=tk.VERTICAL, command=self.station_tree.yview)
        self.station_tree.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        btn_frame = ttk.Frame(stations_frame)
        btn_frame.pack(fill=tk.X)

        self.new_station_var = tk.StringVar()
        ttk.Entry(btn_frame, textvariable=self.new_station_var, width=24).pack(
            side=tk.LEFT, padx=4, pady=4
        )
        ttk.Button(btn_frame, text="Ajouter", command=self._add_station).pack(
            side=tk.LEFT, padx=4, pady=4
        )
        ttk.Button(btn_frame, text="Supprimer", command=self._delete_station).pack(
            side=tk.LEFT, padx=4, pady=4
        )

        ttk.Label(stations_frame, text="Double-cliquez sur un home pour le renommer.", foreground="#555").pack(
            anchor=tk.W, pady=(2, 0)
        )

    def _build_launch_tab(self) -> None:
        status_frame = ttk.LabelFrame(self.launch_tab, text="Contrôle du bot", padding=10)
        status_frame.pack(fill=tk.X, padx=5, pady=5)

        self.status_var = tk.StringVar(value="Bot en attente.")
        ttk.Label(
            status_frame, textvariable=self.status_var, font=("Arial", 12, "bold")
        ).pack(fill=tk.X, pady=(0, 10))

        # Delayed start option
        delay_frame = ttk.Frame(status_frame)
        delay_frame.pack(fill=tk.X, pady=(0, 10))
        ttk.Label(delay_frame, text="Démarrage différé:").pack(side=tk.LEFT)
        self.delayed_start_var = tk.IntVar(value=0)
        delayed_spinbox = ttk.Spinbox(
            delay_frame,
            from_=0,
            to=480,
            width=5,
            textvariable=self.delayed_start_var
        )
        delayed_spinbox.pack(side=tk.LEFT, padx=(5, 2))
        ttk.Label(delay_frame, text="minutes (0 = immédiat, max 8h)").pack(side=tk.LEFT)

        btn_frame = ttk.Frame(status_frame)
        btn_frame.pack(pady=10)

        ttk.Button(btn_frame, text="Session unique", command=self._start_single).grid(
            row=0, column=0, padx=6, pady=4
        )
        ttk.Button(btn_frame, text="Mode continu", command=self._start_continuous).grid(
            row=0, column=1, padx=6, pady=4
        )
        ttk.Button(btn_frame, text="Arrêter", command=self._stop_bot).grid(
            row=0, column=2, padx=6, pady=4
        )

        ttk.Label(
            status_frame, text="Raccourci: configurez, sauvegardez puis lancez directement ici."
        ).pack(pady=(10, 0))

        # Section Tests
        test_frame = ttk.LabelFrame(self.launch_tab, text="Tests", padding=10)
        test_frame.pack(fill=tk.X, padx=5, pady=5)

        ttk.Label(
            test_frame,
            text="Testez les différentes fonctionnalités du bot:",
            foreground="#555",
        ).pack(anchor=tk.W, pady=(0, 8))

        test_btn_frame = ttk.Frame(test_frame)
        test_btn_frame.pack(fill=tk.X)

        ttk.Button(
            test_btn_frame,
            text="Transition Jour->Matin",
            command=self._start_test_transition_morning,
        ).grid(row=0, column=0, padx=4, pady=4, sticky=tk.W)

        ttk.Button(
            test_btn_frame,
            text="Transition Matin->Jour",
            command=self._start_test_transition_afternoon,
        ).grid(row=0, column=1, padx=4, pady=4, sticky=tk.W)

        ttk.Button(
            test_btn_frame,
            text="Session 1 Station",
            command=self._start_test_single_station,
        ).grid(row=1, column=0, padx=4, pady=4, sticky=tk.W)

        ttk.Button(
            test_btn_frame,
            text="TP Toutes Stations",
            command=self._start_test_teleport_stations,
        ).grid(row=1, column=1, padx=4, pady=4, sticky=tk.W)

        ttk.Button(
            test_btn_frame,
            text="Session 5 Stations",
            command=self._start_test_five_stations,
        ).grid(row=2, column=0, padx=4, pady=4, sticky=tk.W)

        ttk.Button(
            test_btn_frame,
            text="Test Login (5min)",
            command=self._start_test_login_message_detection,
        ).grid(row=2, column=1, padx=4, pady=4, sticky=tk.W)

        ttk.Label(
            test_frame,
            text="Jour->Matin: jette 15 seaux | Matin->Jour: reprend les seaux",
            foreground="#666",
            font=("Arial", 8),
        ).pack(anchor=tk.W, pady=(8, 0))

        ttk.Label(
            test_frame,
            text="Test Login: détecte pseudo mentionné, bonjour (30s), et répond 're'",
            foreground="#666",
            font=("Arial", 8),
        ).pack(anchor=tk.W, pady=(2, 0))

    def _populate_fields(self) -> None:
        self.log_path_var.set(self.config_data.get("log_path", ""))

        plant = self.config_data.get("plant_settings", {})
        plant_type = plant.get("plant_type", "")
        self.plant_type_var.set(plant_type)
        self.growth_boost_var.set(plant.get("growth_boost", 0))
        self.growth_time_var.set(plant.get("growth_time", 600))

        # Mettre à jour l'affichage du temps
        self._update_growth_time_display()

        delays = self.config_data.get("delays", {})
        self.short_delay_var.set(delays.get("short", 0.3))
        self.medium_delay_var.set(delays.get("medium", 0.8))
        self.long_delay_var.set(delays.get("long", 1.5))
        self.human_variation_var.set(delays.get("human_variation", 0.25))
        self.startup_delay_var.set(delays.get("startup_delay", 5))

        self.session_pause_var.set(self.config_data.get("session_pause", 900))

        homes = self.config_data.get("homes_special", {})
        self.coffre1_var.set(homes.get("coffre1", ""))
        self.coffre2_var.set(homes.get("coffre2", ""))

        # Auto-reply settings
        auto_reply = self.config_data.get("auto_reply", {})
        self.pseudo_var.set(auto_reply.get("pseudo", "Dayti"))

        for widget in self.position_container.winfo_children():
            widget.destroy()

        ttk.Label(
            self.position_container,
            textvariable=self.position_hint_var,
            foreground="#555",
        ).grid(row=0, column=0, sticky=tk.W, pady=(0, 6))

        self.position_vars.clear()
        positions = self.config_data.get("positions", {})
        for row, (name, pos) in enumerate(positions.items(), start=1):
            frame = ttk.Frame(self.position_container)
            frame.grid(row=row, column=0, pady=4, sticky=tk.W)
            ttk.Label(frame, text=name, width=18).pack(side=tk.LEFT)

            x_var = tk.IntVar(value=pos.get("x", 0))
            y_var = tk.IntVar(value=pos.get("y", 0))
            self.position_vars[name] = (x_var, y_var)

            ttk.Label(frame, text="X").pack(side=tk.LEFT, padx=(6, 2))
            ttk.Entry(frame, textvariable=x_var, width=8).pack(side=tk.LEFT)
            ttk.Label(frame, text="Y").pack(side=tk.LEFT, padx=(6, 2))
            ttk.Entry(frame, textvariable=y_var, width=8).pack(side=tk.LEFT)

            ttk.Button(
                frame,
                text="Modifier",
                command=lambda n=name: self._open_position_editor(n),
            ).pack(side=tk.LEFT, padx=6)

            desc = pos.get("description", "")
            if desc:
                ttk.Label(frame, text=f"({desc})", foreground="#555").pack(side=tk.LEFT, padx=8)

        self.station_data = [
            {"name": station.get("name", ""), "harvest_pos": station.get("harvest_pos")}
            for station in self.config_data.get("stations", [])
        ]
        for item in self.station_tree.get_children():
            self.station_tree.delete(item)
        for idx, station in enumerate(self.station_data):
            self.station_tree.insert(
                "",
                tk.END,
                iid=str(idx),
                values=(station.get("name", ""),),
            )

    def _add_station(self) -> None:
        name = self.new_station_var.get().strip()
        if not name:
            messagebox.showerror("Nom requis", "Le nom du home est obligatoire.")
            return
        station_entry = {"name": name, "harvest_pos": None}
        self.station_data.append(station_entry)
        self._refresh_station_tree()
        self.new_station_var.set("")

    def _delete_station(self) -> None:
        selected = self.station_tree.selection()
        if not selected:
            messagebox.showinfo("Sélection requise", "Sélectionnez une station à supprimer.")
            return
        idx = int(selected[0])
        station = self.station_data[idx]
        if messagebox.askyesno("Confirmer", f"Supprimer la station '{station.get('name', '')}' ?"):
            self.station_data.pop(idx)
            self._refresh_station_tree()

    def _refresh_station_tree(self) -> None:
        self._cancel_inline_station_edit()
        for item in self.station_tree.get_children():
            self.station_tree.delete(item)
        for idx, station in enumerate(self.station_data):
            self.station_tree.insert(
                "", tk.END, iid=str(idx), values=(station.get("name", ""),)
            )

    def _start_inline_station_edit(self, event) -> None:
        item_id = self.station_tree.identify_row(event.y)
        column = self.station_tree.identify_column(event.x)
        if not item_id or column != "#1":
            return

        bbox = self.station_tree.bbox(item_id, "name")
        if not bbox:
            return

        x, y, width, height = bbox
        current_value = self.station_tree.item(item_id, "values")[0]

        if hasattr(self, "_station_edit_entry") and self._station_edit_entry:
            self._station_edit_entry.destroy()

        self._station_edit_var = tk.StringVar(value=current_value)
        self._station_edit_entry = ttk.Entry(
            self.station_tree, textvariable=self._station_edit_var
        )
        self._station_edit_entry.place(x=x, y=y, width=width, height=height)
        self._station_edit_entry.focus_set()

        self._station_edit_entry.bind(
            "<Return>", lambda e, item=item_id: self._commit_inline_station_edit(item)
        )
        self._station_edit_entry.bind("<Escape>", lambda e: self._cancel_inline_station_edit())
        self._station_edit_entry.bind("<FocusOut>", lambda e: self._cancel_inline_station_edit())

    def _commit_inline_station_edit(self, item_id: str) -> None:
        new_name = self._station_edit_var.get().strip()
        if new_name:
            index = int(item_id)
            if 0 <= index < len(self.station_data):
                self.station_data[index]["name"] = new_name
                self._refresh_station_tree()
        self._cancel_inline_station_edit()

    def _cancel_inline_station_edit(self) -> None:
        if hasattr(self, "_station_edit_entry") and self._station_edit_entry:
            self._station_edit_entry.destroy()
            self._station_edit_entry = None

    def _stop_position_listener(self) -> None:
        if hasattr(self, "_position_listener") and self._position_listener:
            self._position_listener.stop()
            self._position_listener = None

    def _open_position_editor(self, position_name: str) -> None:
        """Capture la position via le prochain clic global, sans fenêtre supplémentaire."""

        if position_name not in self.position_vars:
            return

        x_var, y_var = self.position_vars[position_name]
        self.position_hint_var.set(
            f"Cliquez n'importe où pour définir '{position_name}' (annule si vous changez d'avis)."
        )

        self._stop_position_listener()

        def on_global_click(x: int, y: int, _button, pressed: bool) -> None:
            if not pressed:
                return

            def record() -> None:
                x_var.set(int(x))
                y_var.set(int(y))
                self.position_hint_var.set(
                    f"Position '{position_name}' enregistrée: X={int(x)} | Y={int(y)}"
                )

            self.root.after(0, record)
            self._stop_position_listener()

        self._position_listener = mouse.Listener(on_click=on_global_click)
        self._position_listener.start()

    def _start_single(self) -> None:
        self._save_from_form()
        delay_minutes = self.delayed_start_var.get()
        if delay_minutes > 0:
            self._start_delayed_countdown(delay_minutes, "session")
        else:
            self.bot_controller.start_session()
            self.status_var.set("Session unique en cours...")

    def _start_continuous(self) -> None:
        self._save_from_form()
        delay_minutes = self.delayed_start_var.get()
        if delay_minutes > 0:
            self._start_delayed_countdown(delay_minutes, "continuous")
        else:
            self.bot_controller.start_continuous()
            self.status_var.set("Mode continu en cours...")

    def _start_delayed_countdown(self, minutes: int, mode: str) -> None:
        """Démarre un compte à rebours avant de lancer le bot."""
        import threading

        self._delayed_countdown_cancelled = False
        total_seconds = minutes * 60

        def countdown():
            remaining = total_seconds
            while remaining > 0 and not self._delayed_countdown_cancelled:
                mins, secs = divmod(remaining, 60)
                self.root.after(0, lambda r=remaining, m=mins, s=secs:
                    self.status_var.set(f"Démarrage dans {m}m {s:02d}s..."))
                import time
                time.sleep(1)
                remaining -= 1

            if not self._delayed_countdown_cancelled:
                self.root.after(0, lambda: self._execute_delayed_start(mode))

        self.status_var.set(f"Démarrage dans {minutes}m 00s...")
        self._countdown_thread = threading.Thread(target=countdown, daemon=True)
        self._countdown_thread.start()

    def _execute_delayed_start(self, mode: str) -> None:
        """Exécute le démarrage après le compte à rebours."""
        if mode == "session":
            self.bot_controller.start_session()
            self.status_var.set("Session unique en cours...")
        elif mode == "continuous":
            self.bot_controller.start_continuous()
            self.status_var.set("Mode continu en cours...")

    def _stop_bot(self) -> None:
        # Cancel delayed countdown if running
        if hasattr(self, '_delayed_countdown_cancelled'):
            self._delayed_countdown_cancelled = True

        if self.bot_controller.is_running:
            self.status_var.set("Arrêt demandé...")
        self.bot_controller.stop()
        if not self.bot_controller.is_running:
            self.status_var.set("Bot en attente.")

    def _start_test_transition_morning(self) -> None:
        self._save_from_form()
        self.bot_controller.start_test_transition_morning()
        self.status_var.set("Test transition Jour->Matin en cours...")

    def _start_test_transition_afternoon(self) -> None:
        self._save_from_form()
        self.bot_controller.start_test_transition_afternoon()
        self.status_var.set("Test transition Matin->Jour en cours...")

    def _start_test_single_station(self) -> None:
        self._save_from_form()
        self.bot_controller.start_test_single_station()
        self.status_var.set("Test session 1 station en cours...")

    def _start_test_teleport_stations(self) -> None:
        self._save_from_form()
        self.bot_controller.start_test_teleport_stations()
        self.status_var.set("Test TP toutes stations en cours...")

    def _start_test_five_stations(self) -> None:
        self._save_from_form()
        self.bot_controller.start_test_five_stations()
        self.status_var.set("Test session 5 stations en cours...")

    def _start_test_login_message_detection(self) -> None:
        self._save_from_form()
        self.bot_controller.start_test_login_message_detection()
        self.status_var.set("Test Login (5min) en cours...")

    def _on_plant_or_boost_change(self, event=None) -> None:
        """Appelé lorsque la plante ou le boost change."""
        self._update_growth_time_display()

    def _update_growth_time_display(self) -> None:
        """Met à jour l'affichage du temps de croissance calculé."""
        plant_type = self.plant_type_var.get().strip()

        if not plant_type or plant_type not in PLANTS:
            self.growth_time_display_var.set("-- (sélectionnez une plante)")
            return

        try:
            boost = max(0, float(self.growth_boost_var.get()))
        except (ValueError, tk.TclError):
            boost = 0

        # Calculer le temps en minutes
        temps_minutes = temps_total_croissance(plant_type, boost)
        temps_secondes = temps_minutes * 60

        # Mettre à jour les variables
        self.growth_time_var.set(temps_secondes)
        self.session_pause_var.set(temps_secondes)

        # Afficher le temps formaté
        temps_formate = format_temps(temps_minutes)
        self.growth_time_display_var.set(f"{temps_formate} ({temps_secondes}s)")

    def _browse_log_path(self) -> None:
        file_path = filedialog.askopenfilename(title="Sélectionnez le fichier latest.log")
        if file_path:
            self.log_path_var.set(file_path)

    def _toggle_auto_reply(self) -> None:
        """Active ou désactive l'auto-reply."""
        if self.auto_reply_bot and self.auto_reply_bot.is_running():
            # Désactiver
            self.auto_reply_bot.stop()
            self.auto_reply_enabled_var.set(False)
            self.auto_reply_btn.config(text="Activer Auto-Reply")
            self.auto_reply_status_var.set("Auto-reply désactivé")
        else:
            # Activer
            log_path = self.log_path_var.get().strip()
            if not log_path:
                messagebox.showerror("Erreur", "Veuillez configurer le chemin du fichier log.")
                return

            pseudo = self.pseudo_var.get().strip()
            if not pseudo:
                messagebox.showerror("Erreur", "Veuillez entrer votre pseudo.")
                return

            self.auto_reply_bot = AutoReplyBot(
                log_path=log_path,
                pseudo=pseudo,
            )

            # Callbacks pour l'UI
            def on_message(sender, message, line):
                self.root.after(0, lambda: self.auto_reply_status_var.set(
                    f"Message de {sender}: {message[:30]}..."
                ))

            def on_reply(reply):
                self.root.after(0, lambda: self.auto_reply_status_var.set(
                    f"Réponse envoyée: {reply[:30]}..."
                ))

            def on_error(error):
                self.root.after(0, lambda: self.auto_reply_status_var.set(
                    f"Erreur: {error[:40]}"
                ))

            self.auto_reply_bot.on_message_detected = on_message
            self.auto_reply_bot.on_reply_sent = on_reply
            self.auto_reply_bot.on_error = on_error

            self.auto_reply_bot.start()

            if self.auto_reply_bot.is_running():
                self.auto_reply_enabled_var.set(True)
                self.auto_reply_btn.config(text="Désactiver Auto-Reply")
                self.auto_reply_status_var.set(f"Auto-reply actif (pseudo: {pseudo})")
            else:
                messagebox.showerror(
                    "Erreur",
                    "Impossible de démarrer l'auto-reply.\n"
                    "Vérifiez la clé API dans mistral_api_key.txt"
                )

    def run(self) -> None:
        self.root.mainloop()
