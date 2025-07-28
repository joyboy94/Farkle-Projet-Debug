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

@Component // Permet à Spring de gérer l'instanciation du GameManager (singleton par défaut)
public class GameManager {

    // --- ATTRIBUTS PRINCIPAUX ---
    private final Map<Integer, Player> players = new HashMap<>(); // Stocke tous les joueurs (clé = id unique)
    private final int WINNING_SCORE = 10000; // Score à atteindre pour gagner la partie
    private Player currentPlayer;            // Joueur dont c'est le tour actuellement
    private Player opponentPlayer;           // Adversaire (pour deux joueurs)
    private Turn currentTurn;                // Tour en cours (gestion des dés, score temporaire, etc.)
    private boolean gameActuallyOver = false;// Indique si la partie est terminée
    private int uniquePlayerIdCounter = 0;   // Générateur d’ID pour chaque nouveau joueur
    private final ScoreCalculator scoreCalculator; // Gestion du calcul des scores et des combinaisons

    private boolean stateChangedFlag = true;

    public Integer getState() {
        if (stateChangedFlag) {
            stateChangedFlag = false; // reset le flag juste après l’appel
            return 1;
        }
        return 0;
    }

    // --- CONSTRUCTEUR ---
    public GameManager() {
        this.scoreCalculator = new ScoreCalculator();
        System.out.println("GameManager initialisé.");
    }

    // --- RÉINITIALISE TOUTE LA PARTIE ---
    public void resetGame() {
        players.clear();
        currentPlayer = null;
        opponentPlayer = null;
        currentTurn = null;
        gameActuallyOver = false;
        uniquePlayerIdCounter = 0;
        System.out.println("GameManager: La partie a été réinitialisée.");
    }


    // --- INSCRIT UN JOUEUR ---
    public RestPlayer addPlayer(String name) {
        if (players.size() >= 2) { // Seulement deux joueurs maximum
            return null;
        }
        Player player = new Player(name, new ArrayList<>());
        player.setId(uniquePlayerIdCounter++);
        players.put(player.getId(), player);
        System.out.println("GameManager: Joueur " + name + " (ID: " + player.getId() + ") ajouté.");

        // Si deux joueurs : la partie commence !
        if (players.size() == 2) {
            List<Player> playerList = new ArrayList<>(players.values());
            currentPlayer = playerList.get(0);
            opponentPlayer = playerList.get(1);
            currentTurn = new Turn(currentPlayer, scoreCalculator);
            gameActuallyOver = false;
            stateChangedFlag = true;
            System.out.println("GameManager: Deux joueurs. La partie commence avec " + currentPlayer.getName());
        }
        return toRestPlayer(player);
    }

    // --- RÉCUPÈRE UN ÉTAT GLOBAL (DTO) DU JEU ---
    public TurnStatusDTO getGameState() {
        TurnStatusDTO dto = createBaseDTO();
        return finalizeDTO(dto);
    }

    // --- LANCER LES DÉS ---
    public TurnStatusDTO roll() {
        System.out.println("=== [DEBUG] ROLL demandé ===");
        System.out.println("Etat initial : currentPlayer = " + (currentPlayer != null ? currentPlayer.getName() : "null"));
        System.out.println("Dice avant roll : " + (currentTurn != null ? currentTurn.getDiceOnPlate() : "null"));
        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(false));
            if (currentTurn.canPlayerRoll()) {
                dto.turnEvents.addAll(currentTurn.rollDiceAndEvaluate());
            }
        } else {
            dto.turnEvents.addAll(currentTurn.rollDiceAndEvaluate());

            System.out.println("Dice après roll : " + currentTurn.getDiceOnPlate());
            System.out.println("Temp score après roll : " + currentTurn.getTemporaryScore());

        }

        // Si le joueur a fait "Farkle" (aucun point possible)
        if (currentTurn.isFarkle()) {
            dto.gameState = "FARKLE_TURN_ENDED";
            dto.immersiveMessage = Messages.randomFarkle();
            switchPlayer();
        }
        stateChangedFlag = true;

        System.out.println("[DEBUG] Avant return roll → gameState = " + dto.gameState + ", tempScore = " + dto.tempScore);

        return finalizeDTO(dto);
    }

    // --- SÉLECTIONNER DES DÉS ---
    public TurnStatusDTO select(String diceValuesInput) {
        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;
        dto.turnEvents.addAll(currentTurn.selectDice(diceValuesInput));
        stateChangedFlag = true;
        return finalizeDTO(dto);
    }

    // --- METTRE EN BANQUE LES POINTS ---
    public TurnStatusDTO bank() {
        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // Cas spécial Hot Dice : forcer la résolution avant banker
        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(true));
        }

        if (currentTurn.canPlayerBank()) {
            int pointsToBankThisTurn = currentTurn.getTemporaryScore();

            // S'il reste des dés scorants non mis en banque
            if (!currentTurn.getDiceOnPlate().isEmpty()) {
                List<Dice> scorables = scoreCalculator.findScoringDice(currentTurn.getDiceOnPlate());
                if (!scorables.isEmpty()) {
                    pointsToBankThisTurn += scoreCalculator.calculatePoints(scorables);
                }
            }

            if (pointsToBankThisTurn > 0) {
                String playerName = currentPlayer.getName();
                currentPlayer.addScore(pointsToBankThisTurn);
                dto.immersiveMessage = Messages.randomBanker();
                dto.turnEvents.add(String.format("%s sécurise %d points (Total: %d)", playerName, pointsToBankThisTurn, currentPlayer.getScore()));
                currentTurn.signalTurnBankedOrFarkled();
                dto.currentPlayerScore = currentPlayer.getScore();

                // Si le joueur atteint ou dépasse le score de victoire
                if (currentPlayer.getScore() >= WINNING_SCORE) {
                    gameActuallyOver = true;
                    dto.winningPlayerName = playerName;
                    dto.winningPlayerScore = currentPlayer.getScore();
                    dto.gameState = "GAME_OVER";
                } else {
                    switchPlayer();
                    dto.gameState = "TURN_BANKED";
                }
            } else {
                dto.turnEvents.add("Impossible de mettre en banque (0 point temporaire).");
            }
        } else {
            dto.turnEvents.add("Impossible de mettre en banque pour le moment.");
        }
        stateChangedFlag = true;
        return finalizeDTO(dto);
    }

    // --- UTILITAIRE : Valide l'action selon l'état du jeu ---
    private boolean isActionValidForCurrentPlayer(TurnStatusDTO dto) {
        if (currentTurn == null || currentPlayer == null || gameActuallyOver) {
            dto.immersiveMessage = "La partie est terminée ou n'a pas commencé.";
            return false;
        }
        return true;
    }

    // --- Crée un DTO de base pour le tour en cours ---
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

    // --- Finalise le DTO (complète les infos selon l'état du jeu) ---
    private TurnStatusDTO finalizeDTO(TurnStatusDTO dto) {
        // Actualise les scores et infos joueurs
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

        // Gestion de l'état du jeu et des actions possibles pour le front
        if (gameActuallyOver) {
            dto.gameState = "GAME_OVER";
            Player winner = players.values().stream().max(Comparator.comparingInt(Player::getScore)).orElse(null);
            if (winner != null) {
                dto.winningPlayerName = winner.getName();
                dto.winningPlayerScore = winner.getScore();
                dto.immersiveMessage = Messages.randomVictory(winner.getName());
            } else {
                dto.immersiveMessage = "La partie est terminée !";
            }
            dto.availableActions = Collections.emptyList();

            if ("FARKLE_TURN_ENDED".equals(dto.gameState) || "TURN_BANKED".equals(dto.gameState)) {

            }

        } else if (currentTurn != null) {
            dto.diceOnPlate = currentTurn.getDiceOnPlate().stream().map(Dice::getValue).collect(Collectors.toList());
            dto.keptDiceThisTurn = currentTurn.getKeptDiceThisTurn().stream().map(Dice::getValue).collect(Collectors.toList());
            dto.tempScore = currentTurn.getTemporaryScore();
            dto.combinationHints = currentTurn.canPlayerSelect() ? scoreCalculator.generateCombinationHints(currentTurn.getDiceOnPlate()) : new ArrayList<>();
            dto.availableActions = new ArrayList<>();
            if (currentTurn.isHotDiceChoicePending()) {
                dto.gameState = "HOT_DICE_CHOICE";
                dto.immersiveMessage = "HOT DICE ! Relancer ou sécuriser " + dto.tempScore + " pts ?";
                dto.availableActions.addAll(Arrays.asList("CHOOSE_HOT_DICE_BANK", "CHOOSE_HOT_DICE_ROLL"));
            } else {
                if (currentTurn.canPlayerSelect()) {
                    dto.gameState = "POST_ROLL_CHOICE";
                    dto.immersiveMessage = "Quels trésors vas-tu garder ?";
                    dto.availableActions.add("SELECT_DICE");
                } else if (currentTurn.canPlayerRoll()) {
                    dto.gameState = "POST_SELECTION_CHOICE";
                    dto.immersiveMessage = Messages.randomNewRoll();
                    dto.availableActions.add("ROLL");
                }
                if (currentTurn.canPlayerBank()) {
                    dto.availableActions.add("BANK");
                }
            }
        } else {
            dto.gameState = "WAITING_FOR_PLAYERS";
            dto.immersiveMessage = "En attente de joueurs...";
        }

        // Permettre de quitter tant que la partie n'est pas terminée
        if (!gameActuallyOver) {
            dto.availableActions.add("QUIT_GAME");
        }
        System.out.println("DEBUG DTO: " + dto.gameState + " | " + dto.availableActions + " | " + dto.immersiveMessage);
        System.out.println("[FINAL DTO] gameState = " + dto.gameState);
        System.out.println("[FINAL DTO] diceOnPlate = " + dto.diceOnPlate);
        System.out.println("[FINAL DTO] keptDice = " + dto.keptDiceThisTurn);
        System.out.println("[FINAL DTO] tempScore = " + dto.tempScore);
        System.out.println("---------------------------------------------------------");
        System.out.println("[DEBUG] finalizeDTO() → gameState = " + dto.gameState +
                " | availableActions = " + dto.availableActions +
                " | message = " + dto.immersiveMessage);
        return dto;

    }

    // --- Passe la main à l'autre joueur ---
    private void switchPlayer() {
        if (gameActuallyOver) { return; }
        if (players.size() == 2 && currentPlayer != null) {
            Player previousPlayer = currentPlayer;
            currentPlayer = opponentPlayer;
            opponentPlayer = previousPlayer;
            currentTurn = new Turn(currentPlayer, scoreCalculator);
        }
    }

    // --- MÉTHODES UTILITAIRES EXPOSÉES À L'API ---
    public int getCurrentPlayerId() { return currentPlayer != null ? currentPlayer.getId() : -1; }

    public int getActualTurnPoints() { return currentTurn != null ? currentTurn.getTemporaryScore() : 0; }

    public RestDices getDicePlate() {
        RestDices rd = new RestDices();
        if (currentTurn != null && currentTurn.getDiceOnPlate() != null) {
            rd.setDices(currentTurn.getDiceOnPlate().stream().map(Dice::getValue).collect(Collectors.toList()));
        } else {
            rd.setDices(Collections.emptyList());
        }
        return rd;
    }

    public RestPlayer getRestPlayer(int id) { return toRestPlayer(players.get(id)); }

    public RestDices getSelectedDices() {
        RestDices rd = new RestDices();
        if (currentTurn != null && currentTurn.getKeptDiceThisTurn() != null) {
            rd.setDices(currentTurn.getKeptDiceThisTurn().stream().map(Dice::getValue).collect(Collectors.toList()));
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

    // --- Retire un joueur et termine la partie ---
    public boolean quit(Integer playerId) {
        if (playerId == null || !players.containsKey(playerId)) return false;
        players.remove(playerId);
        gameActuallyOver = true;
        stateChangedFlag = true;
        return true;
    }

    // --- Convertit un Player (interne) vers RestPlayer (pour l'API REST) ---
    private RestPlayer toRestPlayer(Player p) {
        if (p == null) return null;
        RestPlayer rp = new RestPlayer();
        rp.setId(p.getId());
        rp.setName(p.getName());
        rp.setScore(p.getScore());
        return rp;
    }

}
