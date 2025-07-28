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

@Component
public class GameManager {

    private final Map<Integer, Player> players = new HashMap<>();
    private final int WINNING_SCORE = 10000;
    private Player currentPlayer;
    private Player opponentPlayer;
    private Turn currentTurn;
    private boolean gameActuallyOver = false;
    private int uniquePlayerIdCounter = 0;
    private final ScoreCalculator scoreCalculator;
    private boolean stateChangedFlag = true;

    public GameManager() {
        this.scoreCalculator = new ScoreCalculator();
        System.out.println("GameManager initialisé.");
    }

    public Integer getState() {
        if (stateChangedFlag) {
            stateChangedFlag = false;
            return 1;
        }
        return 0;
    }

    public void resetGame() {
        players.clear();
        currentPlayer = null;
        opponentPlayer = null;
        currentTurn = null;
        gameActuallyOver = false;
        uniquePlayerIdCounter = 0;
        System.out.println("GameManager: La partie a été réinitialisée.");
    }

    public RestPlayer addPlayer(String name) {
        if (players.size() >= 2) {
            return null;
        }
        Player player = new Player(name, new ArrayList<>());
        player.setId(uniquePlayerIdCounter++);
        players.put(player.getId(), player);
        System.out.println("GameManager: Joueur " + name + " (ID: " + player.getId() + ") ajouté.");

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

    public TurnStatusDTO getGameState() {
        if (!isGameReady()) {
            return waitingForPlayersDTO();
        }
        TurnStatusDTO dto = createBaseDTO();
        return finalizeDTO(dto);
    }

    public TurnStatusDTO roll() {
        if (!isGameReady()) return waitingForPlayersDTO();

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

        // *** SEULEMENT APRÈS UN FARKLE on passe la main ***
        if (currentTurn.isFarkle()) {
            dto.gameState = "FARKLE_TURN_ENDED";
            dto.immersiveMessage = Messages.randomFarkle();
            switchPlayer();
        }
        stateChangedFlag = true;

        System.out.println("[DEBUG] Avant return roll → gameState = " + dto.gameState + ", tempScore = " + dto.tempScore);
        return finalizeDTO(dto);
    }

    public TurnStatusDTO select(String diceValuesInput) {
        if (!isGameReady()) return waitingForPlayersDTO();

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // *** On NE passe PAS la main ici ***
        dto.turnEvents.addAll(currentTurn.selectDice(diceValuesInput));
        stateChangedFlag = true;
        return finalizeDTO(dto);
    }

    public TurnStatusDTO bank() {
        if (!isGameReady()) return waitingForPlayersDTO();

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // Résolution Hot Dice au besoin
        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(true));
        }

        if (currentTurn.canPlayerBank()) {
            int pointsToBankThisTurn = currentTurn.getTemporaryScore();

            // BONUS: Si dés scorants non gardés, on les ajoute (optionnel selon ta règle maison)
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

                if (currentPlayer.getScore() >= WINNING_SCORE) {
                    gameActuallyOver = true;
                    dto.winningPlayerName = playerName;
                    dto.winningPlayerScore = currentPlayer.getScore();
                    dto.gameState = "GAME_OVER";
                } else {
                    // *** ICI on passe la main ***
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

    // ----- Utilitaires et helpers -----

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
            } else if (currentTurn.canPlayerSelect()) {
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
        } else {
            dto.gameState = "WAITING_FOR_PLAYERS";
            dto.immersiveMessage = "En attente de joueurs...";
        }

        if (!gameActuallyOver) {
            dto.availableActions.add("QUIT_GAME");
        }
        // Logs pour debug
        System.out.println("DEBUG DTO: " + dto.gameState + " | " + dto.availableActions + " | " + dto.immersiveMessage);
        System.out.println("[FINAL DTO] gameState = " + dto.gameState);
        System.out.println("[FINAL DTO] diceOnPlate = " + dto.diceOnPlate);
        System.out.println("[FINAL DTO] keptDice = " + dto.keptDiceThisTurn);
        System.out.println("[FINAL DTO] tempScore = " + dto.tempScore);
        System.out.println("---------------------------------------------------------");
        return dto;
    }

    // Passe la main UNIQUEMENT après BANK ou FARKLE
    private void switchPlayer() {
        if (gameActuallyOver) { return; }
        if (players.size() == 2 && currentPlayer != null) {
            Player previousPlayer = currentPlayer;
            currentPlayer = opponentPlayer;
            opponentPlayer = previousPlayer;
            currentTurn = new Turn(currentPlayer, scoreCalculator);
        }
    }

    // --- API rest helpers
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

    public boolean quit(Integer playerId) {
        if (playerId == null || !players.containsKey(playerId)) return false;
        players.remove(playerId);
        gameActuallyOver = true;
        stateChangedFlag = true;
        return true;
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
