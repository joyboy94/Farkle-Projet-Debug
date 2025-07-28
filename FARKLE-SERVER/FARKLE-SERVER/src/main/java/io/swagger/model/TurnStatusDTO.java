package io.swagger.model; // Ou votre package modèle pertinent

import java.util.List;
import java.util.ArrayList;
import java.util.Map; // Pour les hints détaillés

public class TurnStatusDTO {
    // Infos Joueur Actuel
    public int currentPlayerId;
    public String currentPlayerName;
    public int currentPlayerScore;

    // Infos Adversaire
    public int opponentPlayerId = -1;
    public String opponentPlayerName;
    public int opponentPlayerScore;

    // État du Plateau et du Tour
    public List<Integer> diceOnPlate;         // Dés actuellement sur le plateau à sélectionner/lancer
    public List<Integer> keptDiceThisTurn;    // Dés déjà sélectionnés et mis de côté ce tour-ci
    public int tempScore;                     // Score accumulé dans le tour courant

    // État Général du Jeu et Guidage Client
    public String gameState;                   // Ex: "WAITING_FOR_ROLL", "WAITING_FOR_SELECTION", "HOT_DICE_CHOICE", "FARKLE_TURN_ENDED", "TURN_BANKED", "GAME_OVER"
    public String immersiveMessage;            // Message principal thématique pour le joueur (prompt, annonce majeure)
    public List<String> turnEvents;            // Log des événements/scores (ex: "Brelan de 5 ! +500pts", "Dé [1] gardé.")
    public List<Map<String, String>> combinationHints; // Liste de Map pour les hints (ex: {"combo": "Trois 1", "points": "1000 pts"})
    public List<String> availableActions;      // Actions possibles pour le joueur (ex: "ROLL", "SELECT_DICE", "BANK", "CHOOSE_HOT_DICE_ROLL", "CHOOSE_HOT_DICE_BANK")

    // Fin de Partie
    public String winningPlayerName;
    public int winningPlayerScore;

    public TurnStatusDTO() {
        this.diceOnPlate = new ArrayList<>();
        this.keptDiceThisTurn = new ArrayList<>();
        this.turnEvents = new ArrayList<>();
        this.combinationHints = new ArrayList<>();
        this.availableActions = new ArrayList<>();
    }
}

