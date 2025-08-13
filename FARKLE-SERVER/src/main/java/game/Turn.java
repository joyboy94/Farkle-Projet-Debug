package game;

import model.Dice;
import model.Player;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Représente un tour de jeu pour un joueur.
 * Gère l'état du tour et les actions possibles.
 */
public class Turn {

    private final Player player;
    private final ScoreCalculator scoreCalculator;
    private List<Dice> diceOnPlate = new ArrayList<>();
    private List<Dice> keptDiceThisTurn = new ArrayList<>();
    private int temporaryScore = 0;
    private final int initialDiceCount = 6;
    private boolean farkleTriggered = false;
    private boolean hotDiceChoicePending = false;

    // Gestionnaires d'état des actions
    private boolean canRollAction = true;
    private boolean canSelectAction = false;
    private boolean canBankAction = false;

    public Turn(Player player, ScoreCalculator scoreCalculator) {
        this.player = player;
        this.scoreCalculator = scoreCalculator;
        resetForNewTurn();
    }

    /**
     * Prépare des dés "vides" (non lancés) pour le plateau
     * IMPORTANT: Au début d'un tour, on NE prépare PAS les dés
     * On les crée seulement au moment du premier lancer
     */
    private void prepareNewSetOfDice(int count) {
        diceOnPlate.clear();
        for (int i = 0; i < count; i++) {
            diceOnPlate.add(new Dice());
        }
    }

    /**
     * Réinitialise pour une nouvelle séquence après Hot Dice
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
     * Lance les dés et évalue le résultat
     */
    public List<String> rollDiceAndEvaluate() {
        List<String> events = new ArrayList<>();
        if (!canRollAction) {
            events.add("Impossible de lancer les dés maintenant.");
            return events;
        }

        // Si le plateau est vide (premier lancer ou après Hot Dice), on prépare 6 dés
        if (diceOnPlate.isEmpty()) {
            prepareNewSetOfDice(initialDiceCount);
            events.add("Lancement de 6 dés !");
        }

        // Lance chaque dé
        diceOnPlate.forEach(Dice::roll);
        System.out.println("[TURN] Dés lancés: " + diceOnPlate.stream()
                .map(d -> String.valueOf(d.getValue()))
                .collect(Collectors.joining(", ")));

        // Vérifie les combinaisons scorantes
        List<Dice> allScoringInRoll = scoreCalculator.findScoringDice(diceOnPlate);

        if (allScoringInRoll.isEmpty()) {
            // FARKLE!
            farkleTriggered = true;
            this.temporaryScore = 0;
            events.add("FARKLE! Tous les points du tour sont perdus!");
            canRollAction = false;
            canSelectAction = false;
            canBankAction = false;
        } else {
            // Pas de Farkle
            canSelectAction = true;
            canRollAction = false;
            canBankAction = true;

            // Vérification Hot Dice (tous les dés sont scorants)
            if (allScoringInRoll.size() == diceOnPlate.size()) {
                int gained = scoreCalculator.calculatePoints(diceOnPlate);
                temporaryScore += gained;

                String comboName = getSpecialComboName(diceOnPlate);
                events.add(comboName.equals("Combinaison simple")
                        ? "HOT DICE! Tous les dés sont scorants!"
                        : "HOT DICE! " + comboName + " (" + gained + " points)");
                events.add(gained + " points ajoutés automatiquement.");

                keptDiceThisTurn.addAll(diceOnPlate);
                diceOnPlate.clear();

                hotDiceChoicePending = true;
                canSelectAction = false;
            }
        }
        return events;
    }

    private String getSpecialComboName(List<Dice> diceList) {
        if (scoreCalculator.isHotDiceSpecialCombo(diceList)) {
            Map<Integer, Long> counts = diceList.stream()
                    .collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));
            if (diceList.size() == 6 && counts.size() == 6) return "Suite complète (1-6)";
            if (diceList.size() == 6 && counts.values().stream().filter(c -> c == 2).count() == 3) return "Trois paires";
            if (counts.values().stream().anyMatch(c -> c == 6)) return "Six identiques";
        }
        return "Combinaison simple";
    }

    /**
     * Sélectionne des dés après un lancer
     */
    public List<String> selectDice(String inputValues) {
        List<String> events = new ArrayList<>();
        if (!canSelectAction) {
            events.add("Impossible de sélectionner des dés maintenant.");
            return events;
        }

        List<Dice> playerSelectedDice = parseSelectedDice(inputValues, diceOnPlate);

        if (playerSelectedDice == null || playerSelectedDice.isEmpty()) {
            events.add("Sélection invalide - aucun dé valide sélectionné.");
            return events;
        }

        // Validation : vérifier que tous les dés sélectionnés rapportent des points
        List<Dice> scoringDiceInSelection = scoreCalculator.findScoringDice(playerSelectedDice);
        if (scoringDiceInSelection.size() != playerSelectedDice.size()) {
            events.add("Sélection invalide - vous ne pouvez garder que des dés qui rapportent des points.");
            return events;
        }

        // Mise à jour du score et de l'état
        int gained = scoreCalculator.calculatePoints(playerSelectedDice);
        temporaryScore += gained;
        keptDiceThisTurn.addAll(playerSelectedDice);

        // Retirer les dés sélectionnés du plateau
        for (Dice selectedDie : playerSelectedDice) {
            diceOnPlate.remove(selectedDie);
        }

        String diceStr = playerSelectedDice.stream()
                .map(d -> "[" + d.getValue() + "]")
                .collect(Collectors.joining(" "));
        events.add("Dés gardés: " + diceStr + " (+" + gained + " points)");

        canSelectAction = false;
        canBankAction = true;

        if (diceOnPlate.isEmpty()) {
            hotDiceChoicePending = true;
            canRollAction = false;
        } else {
            canRollAction = true;
        }

        return events;
    }

    /**
     * Résout le choix après un Hot Dice
     */
    public List<String> resolveHotDiceChoice(boolean playerChoosesToBank) {
        List<String> events = new ArrayList<>();
        if (!hotDiceChoicePending) {
            events.add("Erreur: Pas de choix Hot Dice en attente.");
            return events;
        }

        hotDiceChoicePending = false;

        if (playerChoosesToBank) {
            events.add("Vous choisissez de sécuriser vos points.");
            canRollAction = false;
            canSelectAction = false;
            canBankAction = true;
        } else {
            events.add("Vous choisissez de continuer avec 6 nouveaux dés!");
            resetForNewSequenceAfterHotDiceRelance();
        }
        return events;
    }

    private List<Dice> parseSelectedDice(String input, List<Dice> currentDiceOnPlate) {
        try {
            List<Integer> valuesToSelect = input.matches("\\d+")
                    ? input.chars().map(c -> c - '0').boxed().collect(Collectors.toList())
                    : Arrays.stream(input.trim().split("\\s+"))
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());

            List<Dice> actuallySelectedDice = new ArrayList<>();
            List<Dice> availablePlateDiceCopy = new ArrayList<>(currentDiceOnPlate);

            for (int val : valuesToSelect) {
                Dice foundDieObject = null;
                for (Dice d : availablePlateDiceCopy) {
                    if (d.getValue() == val) {
                        foundDieObject = d;
                        break;
                    }
                }
                if (foundDieObject != null) {
                    actuallySelectedDice.add(foundDieObject);
                    availablePlateDiceCopy.remove(foundDieObject);
                } else {
                    return null;
                }
            }

            if (actuallySelectedDice.isEmpty() && !valuesToSelect.isEmpty()) return null;
            return actuallySelectedDice;
        } catch (Exception e) {
            return null;
        }
    }

    // --- GETTERS ---
    public Player getPlayer() { return player; }
    public int getTemporaryScore() { return temporaryScore; }
    public List<Dice> getDiceOnPlate() { return Collections.unmodifiableList(diceOnPlate); }
    public List<Dice> getKeptDiceThisTurn() { return Collections.unmodifiableList(keptDiceThisTurn); }
    public boolean isFarkle() { return farkleTriggered; }
    public boolean isHotDiceChoicePending() { return hotDiceChoicePending; }

    public boolean canPlayerRoll() {
        return canRollAction && !farkleTriggered && !hotDiceChoicePending;
    }

    public boolean canPlayerSelect() {
        return canSelectAction && !farkleTriggered && !hotDiceChoicePending;
    }

    public boolean canPlayerBank() {
        return canBankAction && !farkleTriggered;
    }

    public void signalTurnBankedOrFarkled() {
        canRollAction = false;
        canSelectAction = false;
        canBankAction = false;
    }

    /**
     * Réinitialise complètement l'état du tour
     * IMPORTANT: Au début, on NE prépare PAS les dés sur le plateau
     * Le plateau reste vide jusqu'au premier lancer
     */
    public void resetForNewTurn() {
        temporaryScore = 0;
        keptDiceThisTurn.clear();
        diceOnPlate.clear(); // Plateau VIDE au début
        farkleTriggered = false;
        hotDiceChoicePending = false;
        // Au début d'un tour, seul le lancer est possible
        canRollAction = true;
        canSelectAction = false;
        canBankAction = false;
        System.out.println("[TURN] Nouveau tour initialisé pour " + player.getName());
    }
}