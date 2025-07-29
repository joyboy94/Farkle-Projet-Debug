package game;

import model.Dice;
import model.Player;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Représente un tour de jeu complet pour un seul joueur au Farkle.
 * Cette classe est une machine à états : elle contient l'état actuel du tour
 * (dés sur le plateau, score temporaire) et contrôle les actions que le joueur
 * a le droit d'effectuer (lancer, sélectionner, banker) à chaque instant.
 * Elle est instanciée par le GameManager à chaque changement de joueur.
 */
public class Turn {

    // --- ATTRIBUTS D'ÉTAT DU TOUR ---

    /** Le joueur actuellement en train de jouer ce tour. */
    private final Player player;

    /** Une référence vers l'objet qui gère le calcul des points et la validation des combinaisons. */
    private final ScoreCalculator scoreCalculator;

    /** La liste des dés qui sont encore en jeu et peuvent être relancés. */
    private List<Dice> diceOnPlate = new ArrayList<>();

    /** La liste de tous les dés que le joueur a mis de côté pendant ce tour. */
    private List<Dice> keptDiceThisTurn = new ArrayList<>();

    /** Le score accumulé pendant ce tour. Ce score n'est pas encore ajouté au score total du joueur. */
    private int temporaryScore = 0;

    /** Le nombre de dés avec lesquels un joueur commence un tour (typiquement 6). */
    private final int initialDiceCount = 6;

    /** Un drapeau (boolean) qui indique si le dernier lancer était un "Farkle" (aucun point). */
    private boolean farkleTriggered = false;

    /** * Un drapeau qui indique si le joueur a réussi un "Hot Dice" (tous les dés utilisés sont scorants)
     * et doit maintenant choisir entre continuer son tour avec 6 nouveaux dés ou mettre son score en banque.
     */
    private boolean hotDiceChoicePending = false;

    // --- GESTIONNAIRES D'ÉTAT DES ACTIONS ---

    /** Indique si le joueur a le droit de lancer les dés. */
    private boolean canRollAction = true;

    /** Indique si le joueur a le droit de sélectionner des dés. */
    private boolean canSelectAction = false;

    /** Indique si le joueur a le droit de mettre son score en banque. */
    private boolean canBankAction = false;


    /**
     * Constructeur de la classe Turn.
     * @param player Le joueur qui va jouer ce tour.
     * @param scoreCalculator L'objet utilitaire pour le calcul des scores.
     */
    public Turn(Player player, ScoreCalculator scoreCalculator) {
        this.player = player;
        this.scoreCalculator = scoreCalculator;
        resetForNewTurn(); // Prépare immédiatement le tour pour le joueur.
    }

    /**
     * Crée une nouvelle liste de dés "vides" sur le plateau de jeu.
     * @param count Le nombre de dés à créer.
     */
    private void prepareNewSetOfDice(int count) {
        diceOnPlate.clear();
        for (int i = 0; i < count; i++) {
            diceOnPlate.add(new Dice());
        }
    }

    /**
     * Réinitialise l'état pour une nouvelle séquence après un "Hot Dice".
     * Le score temporaire est conservé, mais le joueur reçoit 6 nouveaux dés.
     */
    private void resetForNewSequenceAfterHotDiceRelance() {
        keptDiceThisTurn.clear();
        prepareNewSetOfDice(initialDiceCount);
        farkleTriggered = false;
        hotDiceChoicePending = false;
        canRollAction = true;
        canSelectAction = false;
        canBankAction = (temporaryScore > 0); // Le joueur peut banker s'il a déjà des points.
    }

    /**
     * Méthode principale qui gère un lancer de dés.
     * Elle lance les dés, analyse le résultat, et met à jour l'état du tour (Farkle, Hot Dice, actions possibles).
     * @return Une liste de chaînes de caractères décrivant les événements du lancer pour l'affichage.
     */
    public List<String> rollDiceAndEvaluate() {
        List<String> events = new ArrayList<>();
        if (!canRollAction) {
            events.add(ServerMessages.cannotRollNow());
            return events;
        }

        // Si le plateau est vide (après une sélection réussie ou un Hot Dice), on prépare 6 nouveaux dés.
        if (diceOnPlate.isEmpty()) {
            prepareNewSetOfDice(initialDiceCount);
            events.add("Relance de 6 nouveaux dés !");
        }

        // Lance physiquement chaque dé sur le plateau.
        diceOnPlate.forEach(Dice::roll);

        // Vérifie s'il y a des combinaisons scorantes dans le résultat.
        List<Dice> allScoringInRoll = scoreCalculator.findScoringDice(diceOnPlate);

        // Si la liste des dés scorants est vide, c'est un Farkle.
        if (allScoringInRoll.isEmpty()) {
            farkleTriggered = true;
            this.temporaryScore = 0; // Le score du tour est perdu.
            events.add(ServerMessages.farkle());
            // Le tour du joueur est terminé, il ne peut plus rien faire.
            canRollAction = false;
            canSelectAction = false;
            canBankAction = false;
        } else {
            // S'il y a des points, le joueur doit maintenant sélectionner des dés.
            canSelectAction = true;
            canRollAction = false;
            canBankAction = true;

            // Cas spécial : si TOUS les dés lancés sont scorants, c'est un "Hot Dice".
            if (allScoringInRoll.size() == diceOnPlate.size()) {
                int gained = scoreCalculator.calculatePoints(diceOnPlate);
                temporaryScore += gained;

                // On informe le joueur du Hot Dice.
                String comboName = getSpecialComboName(diceOnPlate);
                events.add(comboName.equals("Combinaison simple") ? ServerMessages.hotDiceGeneric() : ServerMessages.hotDiceSpecial(comboName, gained));
                events.add(ServerMessages.pointsAddedAutomatically(gained));

                // Tous les dés sont automatiquement mis de côté.
                keptDiceThisTurn.addAll(diceOnPlate);
                diceOnPlate.clear();

                // On active l'état d'attente du choix Hot Dice.
                hotDiceChoicePending = true;
                canSelectAction = false; // Le joueur ne sélectionne pas, il choisit de continuer ou d'arrêter.
            }
        }
        return events;
    }

    /**
     * Méthode utilitaire pour obtenir un nom plus descriptif pour une combinaison spéciale de 6 dés.
     * @param diceList La liste des 6 dés.
     * @return Le nom de la combinaison ("Suite complète", "Trois paires", etc.).
     */
    private String getSpecialComboName(List<Dice> diceList) {
        if (scoreCalculator.isHotDiceSpecialCombo(diceList)) {
            Map<Integer, Long> counts = diceList.stream().collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));
            if (diceList.size() == 6 && counts.size() == 6) return "Suite complète (1-6)";
            if (diceList.size() == 6 && counts.values().stream().filter(c -> c == 2).count() == 3) return "Trois paires";
            if (counts.values().stream().anyMatch(c -> c == 6)) return "Six identiques";
        }
        return "Combinaison simple";
    }

    /**
     * Gère la sélection de dés par le joueur après un lancer.
     * Valide la sélection pour s'assurer que tous les dés choisis sont bien scorants.
     * Met à jour le score temporaire et l'état du jeu.
     * @param inputValues La chaîne de caractères représentant les dés sélectionnés (ex: "1 5 5").
     * @return Une liste d'événements pour l'affichage.
     */
    public List<String> selectDice(String inputValues) {
        List<String> events = new ArrayList<>();
        if (!canSelectAction) {
            events.add(ServerMessages.cannotSelectNow());
            return events;
        }

        // Convertit l'input en une liste d'objets Dice.
        List<Dice> playerSelectedDice = parseSelectedDice(inputValues, diceOnPlate);

        if (playerSelectedDice == null || playerSelectedDice.isEmpty()) {
            events.add(ServerMessages.invalidSelection() + " (Aucun dé valide sélectionné).");
            return events;
        }

        // --- VALIDATION STRICTE ---
        // On vérifie que le joueur n'a sélectionné QUE des dés qui rapportent des points.
        List<Dice> scoringDiceInSelection = scoreCalculator.findScoringDice(playerSelectedDice);
        if (scoringDiceInSelection.size() != playerSelectedDice.size()) {
            events.add(ServerMessages.invalidSelection() + " (Vous ne pouvez garder que des dés ou des combinaisons qui rapportent des points).");
            return events;
        }
        // --- FIN DE LA VALIDATION ---

        // Si la validation est réussie, on met à jour le jeu.
        int gained = scoreCalculator.calculatePoints(playerSelectedDice);
        temporaryScore += gained;
        keptDiceThisTurn.addAll(playerSelectedDice);

        // On retire les dés sélectionnés du plateau.
        for (Dice selectedDie : playerSelectedDice) {
            diceOnPlate.remove(selectedDie);
        }
        events.add(ServerMessages.diceKept(formatDiceValues(playerSelectedDice), gained));

        // Le joueur ne peut plus sélectionner, il doit maintenant relancer ou banker.
        canSelectAction = false;
        canBankAction = true;

        // Si le plateau est vide, c'est un Hot Dice.
        if (diceOnPlate.isEmpty()) {
            hotDiceChoicePending = true;
            canRollAction = false;
        } else {
            canRollAction = true;
        }
        return events;
    }

    /**
     * Gère la décision du joueur après un "Hot Dice".
     * @param playerChoosesToBank true si le joueur veut mettre en banque, false s'il veut continuer.
     * @return Une liste d'événements pour l'affichage.
     */
    public List<String> resolveHotDiceChoice(boolean playerChoosesToBank) {
        List<String> events = new ArrayList<>();
        if (!hotDiceChoicePending) {
            events.add("Erreur interne : Pas de choix Hot Dice en attente.");
            return events;
        }

        hotDiceChoicePending = false; // Le choix est consommé.

        if (playerChoosesToBank) {
            // Si le joueur banke, on bloque les autres actions. Le GameManager gérera le score.
            events.add(ServerMessages.eventHotDiceResolvedBank());
            canRollAction = false;
            canSelectAction = false;
            canBankAction = true;
        } else {
            // Si le joueur continue, on prépare une nouvelle séquence de 6 dés.
            events.add(ServerMessages.eventHotDiceResolvedRoll());
            resetForNewSequenceAfterHotDiceRelance();
        }
        return events;
    }

    /**
     * Formate une liste de dés en une chaîne de caractères lisible (ex: "[1] [5] [5]").
     * @param diceList La liste de dés à formater.
     * @return La chaîne de caractères formatée.
     */
    private String formatDiceValues(List<Dice> diceList) {
        if (diceList == null || diceList.isEmpty()) return "(aucun)";
        return diceList.stream().map(d -> "[" + d.getValue() + "]").collect(Collectors.joining(" "));
    }

    /**
     * Analyse l'input du joueur (ex: "155" ou "1 5 5") et le convertit en une liste
     * d'objets Dice correspondants parmi ceux disponibles sur le plateau.
     * Gère les doublons pour s'assurer qu'un dé n'est pas sélectionné plusieurs fois.
     * @param input La chaîne de caractères brute de l'utilisateur.
     * @param currentDiceOnPlate La liste des dés actuellement sur le plateau.
     * @return Une liste d'objets Dice correspondant à la sélection, ou null si invalide.
     */
    private List<Dice> parseSelectedDice(String input, List<Dice> currentDiceOnPlate) {
        try {
            // Gère les inputs avec ou sans espaces (ex: "155" ou "1 5 5").
            List<Integer> valuesToSelect = input.matches("\\d+")
                    ? input.chars().map(c -> c - '0').boxed().collect(Collectors.toList())
                    : Arrays.stream(input.trim().split("\\s+")).map(Integer::parseInt).collect(Collectors.toList());

            List<Dice> actuallySelectedDice = new ArrayList<>();
            List<Dice> availablePlateDiceCopy = new ArrayList<>(currentDiceOnPlate);

            // Pour chaque valeur demandée, on trouve un dé correspondant et on le retire de la liste
            // des dés disponibles pour éviter de sélectionner deux fois le même objet.
            for (int val : valuesToSelect) {
                Dice foundDieObject = null;
                for(Dice d : availablePlateDiceCopy){
                    if(d.getValue() == val){
                        foundDieObject = d;
                        break;
                    }
                }
                if (foundDieObject != null) {
                    actuallySelectedDice.add(foundDieObject);
                    availablePlateDiceCopy.remove(foundDieObject);
                } else {
                    return null; // Le joueur a demandé un dé qui n'est pas sur le plateau.
                }
            }
            if(actuallySelectedDice.isEmpty() && !valuesToSelect.isEmpty()) return null;
            return actuallySelectedDice;
        } catch (Exception e) {
            return null; // En cas d'erreur de parsing (lettres, etc.).
        }
    }

    // --- GETTERS ---
    // Méthodes simples pour que le GameManager puisse lire l'état actuel du tour.

    public Player getPlayer() { return player; }
    public int getTemporaryScore() { return temporaryScore; }
    public List<Dice> getDiceOnPlate() { return Collections.unmodifiableList(diceOnPlate); }
    public List<Dice> getKeptDiceThisTurn() { return Collections.unmodifiableList(keptDiceThisTurn); }
    public boolean isFarkle() { return farkleTriggered; }
    public boolean isHotDiceChoicePending() { return hotDiceChoicePending; }

    // Les méthodes "can..." combinent plusieurs états pour une réponse simple.
    // Par exemple, on ne peut pas lancer si on a fait un Farkle ou si un choix Hot Dice est en attente.
    public boolean canPlayerRoll() { return canRollAction && !farkleTriggered && !hotDiceChoicePending; }
    public boolean canPlayerSelect() { return canSelectAction && !farkleTriggered && !hotDiceChoicePending; }
    public boolean canPlayerBank() { return canBankAction && !farkleTriggered ; }


    /**
     * Appelée par le GameManager quand le tour se termine (par un bank ou un Farkle résolu)
     * pour s'assurer que le joueur ne peut plus effectuer aucune action.
     */
    public void signalTurnBankedOrFarkled() {
        canRollAction = false;
        canSelectAction = false;
        canBankAction = false;
    }

    /**
     * Réinitialise complètement l'état du tour à ses valeurs par défaut.
     * Appelée par le constructeur et par le GameManager à chaque changement de joueur.
     */
    public void resetForNewTurn() {
        temporaryScore = 0;
        keptDiceThisTurn.clear();
        prepareNewSetOfDice(initialDiceCount);
        farkleTriggered = false;
        hotDiceChoicePending = false;
        // Au début d'un tour, la seule action possible est de lancer les dés.
        canRollAction = true;
        canSelectAction = false;
        canBankAction = false;
    }
}