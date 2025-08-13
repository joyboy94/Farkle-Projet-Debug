package game;

import io.swagger.model.RestDices;
import io.swagger.model.RestPlayer;
import io.swagger.model.TurnStatusDTO;
import model.Dice;
import model.Player;
import org.springframework.stereotype.Component;
import ui.Messages;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Contrôleur principal de la logique Farkle (serveur).
 * Version corrigée avec gestion stricte de /stateChanged selon les exigences.
 */
@Component
public class GameManager {

    // --- ÉTAT GLOBAL DE PARTIE ---
    private final Map<Integer, Player> players = new HashMap<>();
    private final int WINNING_SCORE = 10000;

    private Player currentPlayer;
    private Player opponentPlayer;
    private Turn currentTurn;
    private boolean gameActuallyOver = false;
    private int uniquePlayerIdCounter = 0;
    private final ScoreCalculator scoreCalculator;

    // --- GESTION DE /stateChanged CONFORME À L'EXIGENCE 1 ---
    /**
     * Version globale de l'état. Incrémentée à chaque modification significative.
     */
    private int globalStateVersion = 0;

    /**
     * Dernière version "servie" par /stateChanged.
     * Permet d'implémenter le comportement "consommable une fois".
     */
    private int globalLastServedVersion = -1;

    public GameManager() {
        this.scoreCalculator = new ScoreCalculator();
        System.out.println("GameManager initialisé (API /stateChanged conforme aux exigences).");
        // PAS de markStateChanged() ici - on attend que la partie commence vraiment
    }

    /**
     * API /stateChanged - Implémentation STRICTE selon les exigences :
     * - Retourne 1 si l'état a changé depuis la DERNIÈRE LECTURE
     * - Une fois lu, les appels suivants renvoient 0 jusqu'au prochain changement
     */
    public Integer getState() {
        if (globalStateVersion > globalLastServedVersion) {
            System.out.println("[StateChange] Lecture du changement v" + globalStateVersion + " -> retourne 1");
            globalLastServedVersion = globalStateVersion; // Consomme le changement
            return 1;
        }
        System.out.println("[StateChange] Pas de changement -> retourne 0");
        return 0;
    }

    /**
     * Marque un changement d'état (appelé après chaque action significative)
     */
    private void markStateChanged() {
        globalStateVersion++;
        System.out.println("[StateChange] État modifié -> version=" + globalStateVersion);
    }

    /**
     * Réinitialise complètement le jeu
     */
    public void resetGame() {
        players.clear();
        currentPlayer = null;
        opponentPlayer = null;
        currentTurn = null;
        gameActuallyOver = false;
        uniquePlayerIdCounter = 0;

        // Reset de l'indicateur de changement
        globalStateVersion = 0;
        globalLastServedVersion = -1;

        // Marque un changement pour signaler le reset
        markStateChanged();
        System.out.println("GameManager: partie réinitialisée.");
    }

    /**
     * Ajoute un nouveau joueur à la partie
     */
    public RestPlayer addPlayer(String name) {
        if (players.size() >= 2) {
            return null; // Partie complète
        }

        Player p = new Player(name, new ArrayList<>());
        p.setId(uniquePlayerIdCounter++);
        players.put(p.getId(), p);
        System.out.println("Joueur ajouté: " + name + " (id=" + p.getId() + ")");

        if (players.size() == 2) {
            // Début de partie avec 2 joueurs
            List<Player> list = new ArrayList<>(players.values());
            currentPlayer = list.get(0);
            opponentPlayer = list.get(1);
            currentTurn = new Turn(currentPlayer, scoreCalculator);
            gameActuallyOver = false;
            markStateChanged(); // IMPORTANT: Signale le début de partie
            System.out.println("La partie commence. Premier joueur: " + currentPlayer.getName());
        }

        return toRestPlayer(p);
    }

    /**
     * Obtient l'état complet du jeu (utilisé par le polling)
     */
    public TurnStatusDTO getGameState() {
        if (!isGameReady()) {
            return waitingForPlayersDTO();
        }
        TurnStatusDTO dto = createBaseDTO();
        return finalizeDTO(dto);
    }

    /**
     * Action: Lancer les dés
     */
    public TurnStatusDTO roll() {
        if (!isGameReady()) return waitingForPlayersDTO();

        System.out.println("=== [ROLL] Joueur " + currentPlayer.getName() + " lance les dés ===");

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // Gestion du Hot Dice si nécessaire
        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(false));
        }

        // Lance les dés
        dto.turnEvents.addAll(currentTurn.rollDiceAndEvaluate());

        // Gestion du Farkle
        if (currentTurn.isFarkle()) {
            System.out.println("[ROLL] FARKLE détecté!");
            dto.gameState = "FARKLE_TURN_ENDED";
            dto.immersiveMessage = Messages.randomFarkle();

            // IMPORTANT: On finalise le DTO AVANT de changer de joueur
            TurnStatusDTO finalDto = finalizeDTO(dto);

            // Changement de joueur
            switchPlayer();
            markStateChanged(); // IMPORTANT: Signale le changement après Farkle

            return finalDto;
        }

        // Pas de Farkle : le plateau a changé
        markStateChanged(); // IMPORTANT: Signale le changement après roll normal
        return finalizeDTO(dto);
    }

    /**
     * Action: Sélectionner des dés
     */
    public TurnStatusDTO select(String diceValuesInput) {
        if (!isGameReady()) return waitingForPlayersDTO();

        System.out.println("=== [SELECT] Joueur " + currentPlayer.getName() + " sélectionne: " + diceValuesInput + " ===");

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        dto.turnEvents.addAll(currentTurn.selectDice(diceValuesInput));

        markStateChanged(); // IMPORTANT: Signale le changement après sélection
        return finalizeDTO(dto);
    }

    /**
     * Action: Mettre en banque
     */
    public TurnStatusDTO bank() {
        if (!isGameReady()) return waitingForPlayersDTO();

        System.out.println("=== [BANK] Joueur " + currentPlayer.getName() + " met en banque ===");

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // Gestion du Hot Dice si nécessaire
        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(true));
        }

        if (currentTurn.canPlayerBank()) {
            int pointsToBankThisTurn = currentTurn.getTemporaryScore();

            // Ajout automatique des points des dés restants scorants
            if (!currentTurn.getDiceOnPlate().isEmpty()) {
                List<Dice> scorables = scoreCalculator.findScoringDice(currentTurn.getDiceOnPlate());
                if (!scorables.isEmpty()) {
                    pointsToBankThisTurn += scoreCalculator.calculatePoints(scorables);
                }
            }

            if (pointsToBankThisTurn > 0) {
                currentPlayer.addScore(pointsToBankThisTurn);
                dto.immersiveMessage = Messages.randomBanker();
                dto.turnEvents.add(String.format(
                        "%s sécurise %d pts (Total: %d)",
                        currentPlayer.getName(), pointsToBankThisTurn, currentPlayer.getScore()
                ));
                currentTurn.signalTurnBankedOrFarkled();

                // Vérification de victoire
                if (currentPlayer.getScore() >= WINNING_SCORE) {
                    gameActuallyOver = true;
                    dto.winningPlayerName = currentPlayer.getName();
                    dto.winningPlayerScore = currentPlayer.getScore();
                    dto.gameState = "GAME_OVER";
                    markStateChanged(); // IMPORTANT: Signale la fin de partie
                } else {
                    // Changement de joueur
                    switchPlayer();
                    dto.gameState = "TURN_BANKED";
                    markStateChanged(); // IMPORTANT: Signale le changement de tour
                }
            } else {
                dto.turnEvents.add("Impossible de mettre en banque (0 point temporaire).");
            }
        } else {
            dto.turnEvents.add("Impossible de mettre en banque pour le moment.");
        }

        return finalizeDTO(dto);
    }

    /**
     * Gestion du départ d'un joueur
     */
    public boolean quit(Integer playerId) {
        if (playerId == null || !players.containsKey(playerId)) return false;

        System.out.println("=== [QUIT] Joueur id=" + playerId + " quitte la partie ===");
        players.remove(playerId);
        gameActuallyOver = true;
        markStateChanged(); // IMPORTANT: Signale la fin de partie
        return true;
    }

    // ========== MÉTHODES UTILITAIRES PRIVÉES ==========

    private boolean isGameReady() {
        return players.size() == 2 && currentTurn != null && currentPlayer != null && opponentPlayer != null;
    }

    private TurnStatusDTO waitingForPlayersDTO() {
        TurnStatusDTO dto = new TurnStatusDTO();
        dto.gameState = "WAITING_FOR_PLAYERS";
        dto.immersiveMessage = "En attente d'un adversaire...";
        dto.availableActions = Collections.emptyList();
        if (currentPlayer != null) {
            dto.currentPlayerId = currentPlayer.getId();
            dto.currentPlayerName = currentPlayer.getName();
            dto.currentPlayerScore = currentPlayer.getScore();
        }
        if (opponentPlayer != null) {
            dto.opponentPlayerId = opponentPlayer.getId();
            dto.opponentPlayerName = opponentPlayer.getName();
            dto.opponentPlayerScore = opponentPlayer.getScore();
        }
        return dto;
    }

    private boolean isActionValidForCurrentPlayer(TurnStatusDTO dto) {
        if (currentTurn == null || currentPlayer == null || gameActuallyOver) {
            dto.immersiveMessage = "La partie est terminée ou n'a pas commencé.";
            return false;
        }
        return true;
    }

    private TurnStatusDTO createBaseDTO() {
        TurnStatusDTO dto = new TurnStatusDTO();
        if (this.currentPlayer != null) {
            dto.currentPlayerId = this.currentPlayer.getId();
            dto.currentPlayerName = this.currentPlayer.getName();
            dto.currentPlayerScore = this.currentPlayer.getScore();
        }
        if (this.opponentPlayer != null) {
            dto.opponentPlayerId = this.opponentPlayer.getId();
            dto.opponentPlayerName = this.opponentPlayer.getName();
            dto.opponentPlayerScore = this.opponentPlayer.getScore();
        }
        return dto;
    }

    private TurnStatusDTO finalizeDTO(TurnStatusDTO dto) {
        // Copie les informations des joueurs
        if (this.currentPlayer != null) {
            dto.currentPlayerId = this.currentPlayer.getId();
            dto.currentPlayerName = this.currentPlayer.getName();
            dto.currentPlayerScore = this.currentPlayer.getScore();
        }
        if (this.opponentPlayer != null) {
            dto.opponentPlayerId = this.opponentPlayer.getId();
            dto.opponentPlayerName = this.opponentPlayer.getName();
            dto.opponentPlayerScore = this.opponentPlayer.getScore();
        }

        // Gestion de l'état du jeu
        if (gameActuallyOver) {
            dto.gameState = "GAME_OVER";
            Player winner = players.values().stream()
                    .max(Comparator.comparingInt(Player::getScore))
                    .orElse(null);
            if (winner != null) {
                dto.winningPlayerName = winner.getName();
                dto.winningPlayerScore = winner.getScore();
                dto.immersiveMessage = Messages.randomVictory(winner.getName());
            } else {
                dto.immersiveMessage = "La partie est terminée !";
            }
            dto.availableActions = Collections.emptyList();
        } else if (currentTurn != null) {
            // État du plateau et du tour
            dto.diceOnPlate = currentTurn.getDiceOnPlate().stream()
                    .map(Dice::getValue)
                    .collect(Collectors.toList());
            dto.keptDiceThisTurn = currentTurn.getKeptDiceThisTurn().stream()
                    .map(Dice::getValue)
                    .collect(Collectors.toList());
            dto.tempScore = currentTurn.getTemporaryScore();
            dto.combinationHints = currentTurn.canPlayerSelect()
                    ? scoreCalculator.generateCombinationHints(currentTurn.getDiceOnPlate())
                    : new ArrayList<>();
            dto.availableActions = new ArrayList<>();

            // Machine à états pour déterminer les actions possibles
            if (currentTurn.isHotDiceChoicePending()) {
                dto.gameState = "HOT_DICE_CHOICE";
                dto.immersiveMessage = "HOT DICE ! Relance les 6 dés ou sécurise tes " + dto.tempScore + " points !";
                dto.availableActions.add("ROLL");
                dto.availableActions.add("BANK");
            } else {
                // Début de tour
                if (currentTurn.canPlayerRoll() && currentTurn.getKeptDiceThisTurn().isEmpty()) {
                    dto.gameState = "BEGIN_TURN";
                    if (dto.immersiveMessage == null || dto.immersiveMessage.isEmpty()) {
                        dto.immersiveMessage = Messages.randomNewRoll();
                    }
                    dto.availableActions.add("ROLL");
                }
                // Après un lancer, sélection nécessaire
                else if (currentTurn.canPlayerSelect()) {
                    dto.gameState = "POST_ROLL_CHOICE";
                    dto.immersiveMessage = "Quels trésors vas-tu garder ?";
                    dto.availableActions.add("SELECT_DICE");
                }
                // Après sélection, possibilité de relancer
                else if (currentTurn.canPlayerRoll() && !currentTurn.getKeptDiceThisTurn().isEmpty()) {
                    dto.gameState = "POST_SELECTION_CHOICE";
                    dto.immersiveMessage = Messages.randomNewRoll();
                    dto.availableActions.add("ROLL");
                }

                // Bank toujours possible si des points sont accumulés
                if (currentTurn.canPlayerBank()) {
                    dto.availableActions.add("BANK");
                }
            }
        } else {
            dto.gameState = "WAITING_FOR_PLAYERS";
            dto.immersiveMessage = "En attente de joueurs...";
        }

        // Quit toujours disponible sauf fin de partie
        if (!gameActuallyOver) {
            dto.availableActions.add("QUIT_GAME");
        }

        System.out.println("[DTO] État final: " + dto.gameState + ", Actions: " + dto.availableActions);
        return dto;
    }

    private void switchPlayer() {
        if (gameActuallyOver) return;

        if (players.size() == 2 && currentPlayer != null) {
            Player prev = currentPlayer;
            currentPlayer = opponentPlayer;
            opponentPlayer = prev;
            currentTurn = new Turn(currentPlayer, scoreCalculator);
            System.out.println("=== Changement de joueur: " + currentPlayer.getName() + " commence son tour ===");
            // Note: Le markStateChanged() est déjà fait dans les méthodes appelantes
        }
    }

    // --- API REST HELPERS ---
    public int getCurrentPlayerId() {
        return currentPlayer != null ? currentPlayer.getId() : -1;
    }

    public int getActualTurnPoints() {
        return currentTurn != null ? currentTurn.getTemporaryScore() : 0;
    }

    public RestDices getDicePlate() {
        RestDices rd = new RestDices();
        if (currentTurn != null && currentTurn.getDiceOnPlate() != null) {
            rd.setDices(currentTurn.getDiceOnPlate().stream()
                    .map(Dice::getValue)
                    .collect(Collectors.toList()));
        } else {
            rd.setDices(Collections.emptyList());
        }
        return rd;
    }

    public RestPlayer getRestPlayer(int id) {
        return toRestPlayer(players.get(id));
    }

    public RestDices getSelectedDices() {
        RestDices rd = new RestDices();
        if (currentTurn != null && currentTurn.getKeptDiceThisTurn() != null) {
            rd.setDices(currentTurn.getKeptDiceThisTurn().stream()
                    .map(Dice::getValue)
                    .collect(Collectors.toList()));
        } else {
            rd.setDices(Collections.emptyList());
        }
        return rd;
    }

    public RestPlayer getWinner() {
        if (gameActuallyOver) {
            return players.values().stream()
                    .max(Comparator.comparingInt(Player::getScore))
                    .map(this::toRestPlayer)
                    .orElse(null);
        }
        return null;
    }

    private RestPlayer toRestPlayer(Player p) {
        if (p == null) return null;
        RestPlayer rp = new RestPlayer();
        rp.setId(p.getId());
        rp.setName(p.getName());
        rp.setScore(p.getScore());
        return rp;
    }
}