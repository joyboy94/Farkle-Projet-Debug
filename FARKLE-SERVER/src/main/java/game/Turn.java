package game;

import model.Dice;
import model.Player;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Classe Turn - Représente le tour de jeu pour un joueur au Farkle.
 * Elle gère le plateau de dés, les dés gardés, le score temporaire du tour,
 * l'état des actions possibles et la logique Hot Dice/Farkle.
 */
public class Turn {
    // Le joueur dont c'est le tour
    private final Player player;
    // Gestionnaire du calcul des points
    private final ScoreCalculator scoreCalculator;

    // Dés actuellement sur le plateau (en cours de tour)
    private List<Dice> diceOnPlate = new ArrayList<>();
    // Dés sélectionnés/gardés pendant CE tour
    private List<Dice> keptDiceThisTurn = new ArrayList<>();
    // Score temporaire pour ce tour (pas encore banké)
    private int temporaryScore = 0;
    // Nombre de dés à lancer en début de séquence (règle Farkle = 6)
    private final int initialDiceCount = 6;

    // Statut de fin de tour Farkle
    private boolean farkleTriggered = false;
    // Statut "Hot Dice" (tous les dés scorants dans un lancer)
    private boolean hotDiceChoicePending = false;

    // Contrôle de ce que le joueur PEUT faire à l’instant T
    private boolean canRollAction = true;
    private boolean canSelectAction = false;
    private boolean canBankAction = false;

    // --- Constructeur ---
    public Turn(Player player, ScoreCalculator scoreCalculator) {
        this.player = player;
        this.scoreCalculator = scoreCalculator;
        resetForNewTurn(); // Initialisation du tour (6 dés, etc.)
    }

    /**
     * Prépare une nouvelle séquence de X dés (6 au départ ou après Hot Dice).
     */
    private void prepareNewSetOfDice(int count) {
        diceOnPlate.clear();
        for (int i = 0; i < count; i++) {
            diceOnPlate.add(new Dice());
        }
    }

    /**
     * Réinitialise le tour pour une relance Hot Dice (score conservé mais dés remis à 6).
     */
    private void resetForNewSequenceAfterHotDiceRelance() {
        keptDiceThisTurn.clear();
        prepareNewSetOfDice(initialDiceCount);
        farkleTriggered = false;
        hotDiceChoicePending = false;
        canRollAction = true;
        canSelectAction = false;
        canBankAction = (temporaryScore > 0);
    }

    /**
     * Effectue un lancer de dés et évalue le résultat.
     * Renvoie une liste de messages pour l’UI (événements du tour).
     */
    public List<String> rollDiceAndEvaluate() {
        List<String> events = new ArrayList<>();
        if (!canRollAction) {
            events.add(ServerMessages.cannotRollNow());
            System.out.println("[DEBUG] Turn.rollDiceAndEvaluate → nouveau lancer de dés");

            return events;
        }

        // Si aucun dé, on relance 6 nouveaux (ex : relance Hot Dice)
        if (diceOnPlate.isEmpty()) {
            prepareNewSetOfDice(initialDiceCount);
            events.add("Relance de 6 nouveaux dés !");
        }

        // Lancer chaque dé
        diceOnPlate.forEach(Dice::roll);
        String diceValuesString = formatDiceValues(diceOnPlate);
        

        // Chercher les dés scorants dans ce lancer
        List<Dice> allScoringInRoll = scoreCalculator.findScoringDice(diceOnPlate);

        // Si aucun score possible => Farkle !
        if (allScoringInRoll.isEmpty()) {
            farkleTriggered = true;
            temporaryScore = 0;
            events.add(ServerMessages.farkle());
            canRollAction = false; canSelectAction = false; canBankAction = false;
        } else {
            canSelectAction = true;
            canRollAction = false;
            canBankAction = true;

            // Tous les dés sont scorants ? ⇒ Hot Dice !
            if (allScoringInRoll.size() == diceOnPlate.size()) {
                int gained = scoreCalculator.calculatePoints(diceOnPlate);
                temporaryScore += gained;
                String comboName = getSpecialComboName(diceOnPlate);
                events.add(comboName.equals("Combinaison simple") ? ServerMessages.hotDiceGeneric() : ServerMessages.hotDiceSpecial(comboName, gained));
                events.add(ServerMessages.pointsAddedAutomatically(gained));

                // Tous les dés vont dans keptDiceThisTurn
                keptDiceThisTurn.addAll(diceOnPlate);
                diceOnPlate.clear();

                hotDiceChoicePending = true;
                canSelectAction = false;
            }
        }
        return events;
    }

    /**
     * Cherche le nom d'une combinaison spéciale lors d’un Hot Dice.
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
     * Gère la sélection des dés scorants (par input utilisateur).
     * Vérifie la validité, retire les dés du plateau, met à jour le score temporaire.
     */
    public List<String> selectDice(String inputValues) {
        List<String> events = new ArrayList<>();
        if (!canSelectAction) {
            events.add(ServerMessages.cannotSelectNow());
            return events;
        }

        // Récupère la sélection utilisateur (liste d'objets Dice)
        List<Dice> playerSelectedDice = parseSelectedDice(inputValues, diceOnPlate);

        // Validation rapide de la sélection (existence + scorable)
        if (playerSelectedDice == null || playerSelectedDice.isEmpty() || !scoreCalculator.isValidSelection(playerSelectedDice, diceOnPlate)) {
            events.add(ServerMessages.invalidSelection());
            return events;
        }

        int gained = scoreCalculator.calculatePoints(playerSelectedDice);
        if (gained == 0) {
            events.add(ServerMessages.invalidSelection() + " (Votre sélection ne rapporte aucun point).");
            return events;
        }

        // Vérifie que chaque dé sélectionné fait partie des scorables
        List<Dice> allScoringOnPlate = scoreCalculator.findScoringDice(diceOnPlate);
        for (Dice selected : playerSelectedDice) {
            boolean isPresentInScoring = false;
            for (Dice scoringDie : allScoringOnPlate) {
                if (selected == scoringDie) { // Comparaison par référence
                    isPresentInScoring = true;
                    break;
                }
            }
            if (!isPresentInScoring) {
                events.add(ServerMessages.invalidSelection() + " (Le dé [" + selected.getValue() + "] n'est pas un choix scorable pour ce lancer).");
                return events;
            }
        }

        // Met à jour le score temporaire et la liste des dés gardés
        temporaryScore += gained;
        events.add(ServerMessages.diceKept(formatDiceValues(playerSelectedDice), gained));
        keptDiceThisTurn.addAll(playerSelectedDice);

        // Retire ces dés du plateau
        for (Dice selectedDie : playerSelectedDice) {
            diceOnPlate.remove(selectedDie);
        }

        canBankAction = true;
        canSelectAction = false;

        // Si tous les dés ont été gardés, c’est un Hot Dice (relance potentielle)
        if (diceOnPlate.isEmpty()) {
            events.add(ServerMessages.hotDiceGeneric() + " (tous les dés utilisés) !");
            hotDiceChoicePending = true;
            canRollAction = false;
        } else {
            canRollAction = true;
        }
        return events;
    }

    /**
     * Gère le choix Hot Dice : relancer ou mettre en banque après avoir utilisé tous les dés.
     */
    public List<String> resolveHotDiceChoice(boolean playerChoosesToBank) {
        List<String> events = new ArrayList<>();
        if (!hotDiceChoicePending) {
            events.add("Erreur interne : Pas de choix Hot Dice en attente.");
            return events;
        }
        hotDiceChoicePending = false;

        if (playerChoosesToBank) {
            events.add(ServerMessages.eventHotDiceResolvedBank());
            canRollAction = false; canSelectAction = false; canBankAction = true;
        } else {
            events.add(ServerMessages.eventHotDiceResolvedRoll());
            resetForNewSequenceAfterHotDiceRelance();
        }
        return events;
    }

    /**
     * Formatage lisible pour l’UI : [2] [5] [1] etc.
     */
    private String formatDiceValues(List<Dice> diceList) {
        if (diceList == null || diceList.isEmpty()) return "(aucun)";
        return diceList.stream().map(d -> "[" + d.getValue() + "]").collect(Collectors.joining(" "));
    }

    /**
     * Décode l’entrée utilisateur ("1 5 5") en objets Dice du plateau.
     * Ne sélectionne qu’un objet Dice par valeur, sans doublon, pour éviter les tricheries.
     */
    private List<Dice> parseSelectedDice(String input, List<Dice> currentDiceOnPlate) {
        try {
            List<Integer> valuesToSelect = input.matches("\\d+")
                    ? input.chars().map(c -> c - '0').boxed().collect(Collectors.toList())
                    : Arrays.stream(input.trim().split("\\s+")).map(Integer::parseInt).collect(Collectors.toList());

            List<Dice> actuallySelectedDice = new ArrayList<>();
            List<Dice> availablePlateDiceCopy = new ArrayList<>(currentDiceOnPlate);

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
                    return null; // Si un des dés n'est pas dispo, sélection invalide
                }
            }
            if(actuallySelectedDice.isEmpty() && !valuesToSelect.isEmpty()) return null;
            return actuallySelectedDice;
        } catch (Exception e) {
            return null;
        }
    }

    // --- Getters et méthodes d'état pour le GameManager/DTO ---
    public Player getPlayer() { return player; }
    public int getTemporaryScore() { return temporaryScore; }
    public List<Dice> getDiceOnPlate() { return Collections.unmodifiableList(diceOnPlate); }
    public List<Dice> getKeptDiceThisTurn() { return Collections.unmodifiableList(keptDiceThisTurn); }
    public boolean isFarkle() { return farkleTriggered; }
    public boolean isHotDiceChoicePending() { return hotDiceChoicePending; }
    public boolean canPlayerRoll() { return canRollAction && !farkleTriggered && !hotDiceChoicePending; }
    public boolean canPlayerSelect() { return canSelectAction && !farkleTriggered && !hotDiceChoicePending; }
    public boolean canPlayerBank() { return canBankAction && !farkleTriggered ; }

    /**
     * Désactive toute action à la fin d'un tour (Farkle ou mise en banque).
     */
    public void signalTurnBankedOrFarkled() {
        canRollAction = false; canSelectAction = false; canBankAction = false;
    }

    /**
     * Réinitialise le tour à zéro, appelé à chaque changement de joueur.
     */
    public void resetForNewTurn() {
        temporaryScore = 0;
        keptDiceThisTurn.clear();
        prepareNewSetOfDice(initialDiceCount);
        farkleTriggered = false;
        hotDiceChoicePending = false;
        canRollAction = true;
        canSelectAction = false;
        canBankAction = false;
    }
}
