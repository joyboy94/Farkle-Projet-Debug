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
 * Le GameManager est le contrôleur principal de la logique du jeu Farkle côté serveur.
 * Il est responsable de la gestion des joueurs, du déroulement de la partie,
 * de l'orchestration des tours et de la maintenance de l'état global du jeu.
 * En tant que @Component Spring, une seule instance (singleton) de cette classe gère la partie.
 */
@Component
public class GameManager {

    // --- ATTRIBUTS GLOBAUX DE LA PARTIE ---

    /** Stocke les objets Player de la partie, avec leur ID unique comme clé pour un accès rapide. */
    private final Map<Integer, Player> players = new HashMap<>();

    /** Le score total qu'un joueur doit atteindre ou dépasser pour gagner. */
    private final int WINNING_SCORE = 10000;

    /** Le joueur dont c'est actuellement le tour. */
    private Player currentPlayer;

    /** L'adversaire du joueur actuel. */
    private Player opponentPlayer;

    /** L'objet qui encapsule toute la logique et l'état du tour en cours (dés, score temporaire, etc.). */
    private Turn currentTurn;

    /** Un drapeau qui indique si la partie est terminée (un joueur a gagné ou a quitté). */
    private boolean gameActuallyOver = false;

    /** Un compteur simple pour assigner un ID unique à chaque nouveau joueur. */
    private int uniquePlayerIdCounter = 0;

    /** Une référence vers l'objet utilitaire qui calcule les scores. */
    private final ScoreCalculator scoreCalculator;

    /** Un drapeau pour l'API /stateChanged, indiquant si l'état du jeu a changé depuis la dernière vérification. */
    private boolean stateChangedFlag = true;

    /**
     * Constructeur du GameManager.
     * Est appelé par Spring au démarrage de l'application pour créer l'unique instance.
     */
    public GameManager() {
        this.scoreCalculator = new ScoreCalculator();
        System.out.println("GameManager initialisé.");
    }

    /**
     * Implémente la logique de l'API /stateChanged.
     * Renvoie 1 si l'état a changé depuis le dernier appel, puis réinitialise le drapeau à 0.
     * Renvoie 0 si l'état n'a pas changé.
     * @return 1 si un changement a eu lieu, 0 sinon.
     */
    public Integer getState() {
        if (stateChangedFlag) {
            stateChangedFlag = false; // Le changement est "consommé" par cet appel.
            return 1;
        }
        return 0;
    }

    /**
     * Réinitialise complètement le jeu à son état initial.
     * Vide la liste des joueurs et réinitialise toutes les variables d'état.
     */
    public void resetGame() {
        players.clear();
        currentPlayer = null;
        opponentPlayer = null;
        currentTurn = null;
        gameActuallyOver = false;
        uniquePlayerIdCounter = 0;
        System.out.println("GameManager: La partie a été réinitialisée.");
    }

    /**
     * Ajoute un nouveau joueur à la partie.
     * Si deux joueurs sont présents, la partie démarre automatiquement.
     * @param name Le nom du joueur à ajouter.
     * @return Un objet RestPlayer pour la réponse de l'API.
     */
    public RestPlayer addPlayer(String name) {
        if (players.size() >= 2) {
            return null; // La partie est complète.
        }
        Player player = new Player(name, new ArrayList<>());
        player.setId(uniquePlayerIdCounter++);
        players.put(player.getId(), player);
        System.out.println("GameManager: Joueur " + name + " (ID: " + player.getId() + ") ajouté.");

        // Quand le deuxième joueur rejoint, la partie peut commencer.
        if (players.size() == 2) {
            List<Player> playerList = new ArrayList<>(players.values());
            currentPlayer = playerList.get(0);
            opponentPlayer = playerList.get(1);
            currentTurn = new Turn(currentPlayer, scoreCalculator); // On crée le premier tour.
            gameActuallyOver = false;
            stateChangedFlag = true; // On signale qu'un changement majeur a eu lieu.
            System.out.println("GameManager: Deux joueurs. La partie commence avec " + currentPlayer.getName());
        }
        return toRestPlayer(player);
    }

    /**
     * Point d'entrée principal pour obtenir l'état complet du jeu, utilisé par le polling du client.
     * @return Un TurnStatusDTO complet décrivant l'état actuel de la partie.
     */
    public TurnStatusDTO getGameState() {
        if (!isGameReady()) {
            return waitingForPlayersDTO();
        }
        TurnStatusDTO dto = createBaseDTO();
        return finalizeDTO(dto); // Construit la réponse complète.
    }

    /**
     * Gère l'action "Lancer les dés" du joueur.
     * Délègue la logique du lancer à l'objet Turn et gère le cas spécial du Farkle.
     * @return Le nouvel état du jeu après le lancer.
     */
    public TurnStatusDTO roll() {
        if (!isGameReady()) return waitingForPlayersDTO();

        // Logs de début d'action
        System.out.println("=== [DEBUG] ROLL demandé ===");
        System.out.println("Etat initial : currentPlayer = " + (currentPlayer != null ? currentPlayer.getName() : "null"));
        System.out.println("Dice avant roll : " + (currentTurn != null ? currentTurn.getDiceOnPlate() : "null"));

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // Gère le cas où le joueur relance après un Hot Dice.
        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(false));
        }
        // Exécute le lancer de dés via l'objet Turn.
        dto.turnEvents.addAll(currentTurn.rollDiceAndEvaluate());

        // Logs de milieu d'action
        System.out.println("Dice après roll : " + currentTurn.getDiceOnPlate());
        System.out.println("Temp score après roll : " + currentTurn.getTemporaryScore());

        stateChangedFlag = true; // Une action a eu lieu, l'état a changé.

        // Version finale et correcte de la gestion du Farkle.
        if (currentTurn.isFarkle()) {
            System.out.println("[DEBUG] FARKLE détecté ! Préparation de la réponse AVANT de changer de joueur.");
            dto.gameState = "FARKLE_TURN_ENDED";
            dto.immersiveMessage = Messages.randomFarkle();

            // 1. On construit la réponse FINALE avec les données du tour qui vient de se terminer.
            TurnStatusDTO finalDto = finalizeDTO(dto);

            // 2. ENSUITE, on change de joueur pour préparer le serveur pour la prochaine requête.
            switchPlayer();

            // 3. On renvoie la réponse qu'on a construite AVANT de changer de joueur.
            return finalDto;
        }

        // S'il n'y a PAS de Farkle, on renvoie la réponse normalement.
        System.out.println("[DEBUG] Pas de Farkle, préparation de la réponse normale.");
        return finalizeDTO(dto);
    }

    /**
     * Gère l'action "Sélectionner des dés".
     * Délègue la validation et la logique à l'objet Turn. Ne termine pas le tour.
     * @param diceValuesInput Les valeurs des dés sélectionnés par l'utilisateur.
     * @return Le nouvel état du jeu après la sélection.
     */
    public TurnStatusDTO select(String diceValuesInput) {
        if (!isGameReady()) return waitingForPlayersDTO();

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // On délègue toute la logique à l'objet Turn.
        dto.turnEvents.addAll(currentTurn.selectDice(diceValuesInput));
        stateChangedFlag = true;
        return finalizeDTO(dto);
    }

    /**
     * Gère l'action "Mettre en banque".
     * Ajoute le score temporaire au score total du joueur et passe la main.
     * @return Le nouvel état du jeu après la mise en banque.
     */
    public TurnStatusDTO bank() {
        if (!isGameReady()) return waitingForPlayersDTO();

        TurnStatusDTO dto = createBaseDTO();
        if (!isActionValidForCurrentPlayer(dto)) return dto;

        // Gère le cas où le joueur banke après un Hot Dice.
        if (currentTurn.isHotDiceChoicePending()) {
            dto.turnEvents.addAll(currentTurn.resolveHotDiceChoice(true));
        }

        if (currentTurn.canPlayerBank()) {
            int pointsToBankThisTurn = currentTurn.getTemporaryScore();

            // Règle optionnelle : ajoute automatiquement les points des dés scorants restants.
            if (!currentTurn.getDiceOnPlate().isEmpty()) {
                List<Dice> scorables = scoreCalculator.findScoringDice(currentTurn.getDiceOnPlate());
                if (!scorables.isEmpty()) {
                    pointsToBankThisTurn += scoreCalculator.calculatePoints(scorables);
                }
            }

            if (pointsToBankThisTurn > 0) {
                currentPlayer.addScore(pointsToBankThisTurn);
                dto.immersiveMessage = Messages.randomBanker();
                dto.turnEvents.add(String.format("%s sécurise %d points (Total: %d)", currentPlayer.getName(), pointsToBankThisTurn, currentPlayer.getScore()));
                currentTurn.signalTurnBankedOrFarkled(); // Bloque les actions pour le tour terminé.

                // Vérifie si le joueur a gagné.
                if (currentPlayer.getScore() >= WINNING_SCORE) {
                    gameActuallyOver = true;
                    dto.winningPlayerName = currentPlayer.getName();
                    dto.winningPlayerScore = currentPlayer.getScore();
                    dto.gameState = "GAME_OVER";
                } else {
                    // Si la partie n'est pas finie, on passe la main à l'adversaire.
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

    // --- MÉTHODES UTILITAIRES PRIVÉES ---

    /** Vérifie si la partie est prête à être jouée (2 joueurs connectés). */
    private boolean isGameReady() {
        return players.size() == 2 && currentTurn != null && currentPlayer != null && opponentPlayer != null;
    }

    /** Construit un DTO spécial pour l'état "En attente de joueurs". */
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

    /** Vérifie si une action est possible (la partie n'est pas terminée). */
    private boolean isActionValidForCurrentPlayer(TurnStatusDTO dto) {
        if (currentTurn == null || currentPlayer == null || gameActuallyOver) {
            dto.immersiveMessage = "La partie est terminée ou n'a pas commencé.";
            return false;
        }
        return true;
    }

    /** Crée un DTO de base avec les informations des joueurs. */
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

    /**
     * Finalise le DTO en y ajoutant toutes les informations dynamiques du tour en cours
     * (dés, score temporaire, actions possibles) en se basant sur l'état de l'objet Turn.
     * C'est la machine à états principale pour la communication avec le client.
     * @param dto Le DTO de base à compléter.
     * @return Le DTO final prêt à être envoyé.
     */
    private TurnStatusDTO finalizeDTO(TurnStatusDTO dto) {
        // Copie les informations de base des joueurs.
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

        // Gère le cas de fin de partie.
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
        }
        // Si la partie est en cours...
        else if (currentTurn != null) {
            // Récupère les informations brutes de l'objet Turn.
            dto.diceOnPlate = currentTurn.getDiceOnPlate().stream().map(Dice::getValue).collect(Collectors.toList());
            dto.keptDiceThisTurn = currentTurn.getKeptDiceThisTurn().stream().map(Dice::getValue).collect(Collectors.toList());
            dto.tempScore = currentTurn.getTemporaryScore();
            dto.combinationHints = currentTurn.canPlayerSelect() ? scoreCalculator.generateCombinationHints(currentTurn.getDiceOnPlate()) : new ArrayList<>();
            dto.availableActions = new ArrayList<>();

            // Logique de la machine à états pour déterminer le gameState et les actions possibles.
            // Cas 1 : Prioritaire, le joueur doit choisir après un Hot Dice.
            if (currentTurn.isHotDiceChoicePending()) {
                dto.gameState = "HOT_DICE_CHOICE";
                dto.immersiveMessage = "HOT DICE ! Relance les 6 dés ou sécurise tes " + dto.tempScore + " points !";
                dto.availableActions.add("ROLL");
                dto.availableActions.add("BANK");
            }
            // Si ce n'est pas un Hot Dice, on applique la logique de tour normale.
            else {
                // Règle 1 : C'est le début d'un tour (on peut lancer, rien n'a été gardé).
                if (currentTurn.canPlayerRoll() && currentTurn.getKeptDiceThisTurn().isEmpty()) {
                    dto.gameState = "BEGIN_TURN";
                    // On ne met un message par défaut que s'il n'y a pas déjà un message d'événement (ex: Farkle).
                    if (dto.immersiveMessage == null || dto.immersiveMessage.isEmpty()) {
                        dto.immersiveMessage = Messages.randomNewRoll();
                    }
                    dto.availableActions.add("ROLL");
                }
                // Règle 2 : Le joueur vient de lancer, il doit sélectionner des dés.
                else if (currentTurn.canPlayerSelect()) {
                    dto.gameState = "POST_ROLL_CHOICE";
                    dto.immersiveMessage = "Quels trésors vas-tu garder ?";
                    dto.availableActions.add("SELECT_DICE");
                }
                // Règle 3 : Le joueur vient de sélectionner, il peut maintenant relancer.
                else if (currentTurn.canPlayerRoll() && !currentTurn.getKeptDiceThisTurn().isEmpty()) {
                    dto.gameState = "POST_SELECTION_CHOICE";
                    dto.immersiveMessage = Messages.randomNewRoll();
                    dto.availableActions.add("ROLL");
                }

                // Règle générale : On peut toujours mettre en banque si le tour a rapporté des points.
                if (currentTurn.canPlayerBank()) {
                    dto.availableActions.add("BANK");
                }
            }
        }
        // Si la partie n'a pas encore commencé.
        else {
            dto.gameState = "WAITING_FOR_PLAYERS";
            dto.immersiveMessage = "En attente de joueurs...";
        }

        // Ajoute l'action de quitter, toujours disponible pendant la partie.
        if (!gameActuallyOver) {
            dto.availableActions.add("QUIT_GAME");
        }

        // Logs de débogage pour voir l'état final envoyé au client.
        System.out.println("DEBUG DTO: " + dto.gameState + " | " + dto.availableActions + " | " + dto.immersiveMessage);
        System.out.println("[FINAL DTO] gameState = " + dto.gameState);
        System.out.println("[FINAL DTO] diceOnPlate = " + dto.diceOnPlate);
        System.out.println("[FINAL DTO] keptDice = " + dto.keptDiceThisTurn);
        System.out.println("[FINAL DTO] tempScore = " + dto.tempScore);
        System.out.println("---------------------------------------------------------");
        return dto;
    }

    /**
     * Passe la main à l'adversaire.
     * Inverse les rôles de `currentPlayer` et `opponentPlayer` et crée un nouvel objet Turn.
     */
    private void switchPlayer() {
        if (gameActuallyOver) { return; }
        if (players.size() == 2 && currentPlayer != null) {
            Player previousPlayer = currentPlayer;
            currentPlayer = opponentPlayer;
            opponentPlayer = previousPlayer;
            currentTurn = new Turn(currentPlayer, scoreCalculator); // Le nouveau tour est prêt.
        }
    }

    // --- API REST HELPERS ---
    // Méthodes simples pour fournir des morceaux d'état aux endpoints GET de l'API.

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

    /**
     * Gère le départ d'un joueur, ce qui met fin à la partie.
     * @param playerId L'ID du joueur qui quitte.
     * @return true si le joueur a bien été retiré.
     */
    public boolean quit(Integer playerId) {
        if (playerId == null || !players.containsKey(playerId)) return false;
        players.remove(playerId);
        gameActuallyOver = true;
        stateChangedFlag = true;
        return true;
    }

    /**
     * Convertit un objet Player interne en un objet RestPlayer pour l'API.
     * @param p L'objet Player à convertir.
     * @return L'objet RestPlayer correspondant.
     */
    private RestPlayer toRestPlayer(Player p) {
        if (p == null) return null;
        RestPlayer rp = new RestPlayer();
        rp.setId(p.getId());
        rp.setName(p.getName());
        rp.setScore(p.getScore());
        return rp;
    }
}