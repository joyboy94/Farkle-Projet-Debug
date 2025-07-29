package game;

import model.Dice; // Votre classe Dice du package model

import java.util.*;
import java.util.stream.Collectors;

public class ScoreCalculator {

    // ... (Constantes de score comme précédemment)
    private static final int SCORE_SINGLE_1 = 100;
    private static final int SCORE_SINGLE_5 = 50;
    private static final int SCORE_TRIPLE_1 = 1000;
    private static final int SCORE_TRIPLE_OTHERS_MULTIPLIER = 100;
    private static final int SCORE_FOUR_OF_A_KIND = 1000;
    private static final int SCORE_FIVE_OF_A_KIND = 2000;
    private static final int SCORE_SIX_OF_A_KIND = 3000;
    private static final int SCORE_THREE_PAIRS = 1500;
    private static final int SCORE_STRAIGHT_1_6 = 2500;


    public int calculatePoints(List<Dice> dice) {
        if (dice == null || dice.isEmpty()) {
            return 0;
        }

        int[] counts = new int[7]; // Index 1 à 6 pour les valeurs des dés
        for (Dice d : dice) {
            counts[d.getValue()]++;
        }

        int points = 0;

        // Suite complète 1-2-3-4-5-6 (doit utiliser 6 dés)
        boolean isStraight = dice.size() == 6;
        if (isStraight) {
            for (int i = 1; i <= 6; i++) {
                if (counts[i] != 1) {
                    isStraight = false;
                    break;
                }
            }
            if (isStraight) return SCORE_STRAIGHT_1_6;
        }

        // Trois paires (doit utiliser 6 dés)
        if (dice.size() == 6) {
            int pairCount = 0;
            for (int c : counts) {
                if (c == 2) pairCount++;
            }
            if (pairCount == 3) return SCORE_THREE_PAIRS;
        }

        // Six, cinq, quatre dés identiques
        for (int i = 1; i <= 6; i++) {
            if (counts[i] >= 6) { points += SCORE_SIX_OF_A_KIND; counts[i] -= 6; }
            else if (counts[i] >= 5) { points += SCORE_FIVE_OF_A_KIND; counts[i] -= 5; }
            else if (counts[i] >= 4) { points += SCORE_FOUR_OF_A_KIND; counts[i] -= 4; }
        }

        // Trois dés identiques
        if (counts[1] >= 3) { points += SCORE_TRIPLE_1; counts[1] -= 3; }
        for (int i = 2; i <= 6; i++) {
            if (counts[i] >= 3) { points += i * SCORE_TRIPLE_OTHERS_MULTIPLIER; counts[i] -= 3; }
        }

        // Dés individuels restants
        points += counts[1] * SCORE_SINGLE_1;
        points += counts[5] * SCORE_SINGLE_5;

        return points;
    }
    public List<Dice> findScoringDice(List<Dice> diceList) {
        if (diceList == null || diceList.isEmpty()) {
            return new ArrayList<>();
        }

        Set<Dice> scoringDiceSet = new HashSet<>();
        Map<Integer, List<Dice>> diceByValue = diceList.stream()
                .collect(Collectors.groupingBy(Dice::getValue));

        // --- Règle 1 : Vérifier les combinaisons spéciales qui utilisent les 6 dés ---
        if (diceList.size() == 6) {
            // Cas 1.1 : Trois paires (ex: 2,2, 4,4, 5,5)
            long pairCount = diceByValue.values().stream().filter(list -> list.size() == 2).count();
            if (pairCount == 3) {
                return new ArrayList<>(diceList); // Tous les dés scorent
            }
            // Cas 1.2 : Suite complète (1,2,3,4,5,6)
            if (diceByValue.size() == 6) {
                return new ArrayList<>(diceList); // Tous les dés scorent
            }
        }

        // --- Règle 2 : Chercher les brelans (ou plus) pour chaque valeur ---
        for (List<Dice> group : diceByValue.values()) {
            if (group.size() >= 3) {
                scoringDiceSet.addAll(group); // Si brelan, carré, etc., tous les dés de cette valeur scorent
            }
        }

        // --- Règle 3 : Chercher les [1] et [5] individuels ---
        // On ajoute les 1 qui ne font pas déjà partie d'un brelan+
        if (diceByValue.containsKey(1) && diceByValue.get(1).size() < 3) {
            scoringDiceSet.addAll(diceByValue.get(1));
        }
        // On ajoute les 5 qui ne font pas déjà partie d'un brelan+
        if (diceByValue.containsKey(5) && diceByValue.get(5).size() < 3) {
            scoringDiceSet.addAll(diceByValue.get(5));
        }

        return new ArrayList<>(scoringDiceSet);
    }

    public boolean isValidSelection(List<Dice> selectedDice, List<Dice> availableDiceInRoll) {
        if (selectedDice == null || selectedDice.isEmpty()) return false;
        List<Dice> tempAvailable = new ArrayList<>(availableDiceInRoll);
        for (Dice dSelected : selectedDice) {
            boolean found = false; // Chercher par valeur car les objets Dice peuvent être différents
            for (Iterator<Dice> it = tempAvailable.iterator(); it.hasNext(); ) {
                Dice availableDie = it.next();
                if (availableDie.getValue() == dSelected.getValue()) {
                    it.remove();
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return calculatePoints(selectedDice) > 0; // La sélection doit elle-même marquer des points
    }

    public boolean isHotDiceSpecialCombo(List<Dice> diceList) { // Renommée pour clarté
        if (diceList == null || diceList.size() != 6) return false;
        int[] countsArray = new int[7];
        for (Dice d : diceList) countsArray[d.getValue()]++;
        boolean isStraight = true;
        for (int i = 1; i <= 6; i++) if (countsArray[i] != 1) { isStraight = false; break; }
        if (isStraight) return true;
        int pairCount = 0;
        for (int c : countsArray) if (c == 2) pairCount++;
        if (pairCount == 3) return true;
        for (int c : countsArray) if (c == 6) return true;
        return false;
    }

    /**
     * Génère des "hints" pour les combinaisons scorantes visibles dans un lancer.
     */
    public List<Map<String, String>> generateCombinationHints(List<Dice> diceOnPlate) {
        List<Map<String, String>> hints = new ArrayList<>();
        if (diceOnPlate == null || diceOnPlate.isEmpty()) return hints;

        Map<Integer, Long> counts = diceOnPlate.stream()
                .collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));

        // Suite complète (si 6 dés)
        if (diceOnPlate.size() == 6 && isHotDiceSpecialCombo(diceOnPlate) &&
                counts.values().stream().allMatch(c -> c == 1)) {
            Map<String, String> hintMap = new HashMap<>();
            hintMap.put("combo", "Suite complète (1-6)");
            hintMap.put("points", String.valueOf(SCORE_STRAIGHT_1_6) + " pts");
            hints.add(Collections.unmodifiableMap(hintMap));
            return hints; // Si suite, c'est généralement le seul hint pertinent affiché
        }
        // Trois paires (si 6 dés)
        if (diceOnPlate.size() == 6 && isHotDiceSpecialCombo(diceOnPlate) &&
                counts.values().stream().filter(c -> c == 2).count() == 3) {
            Map<String, String> hintMap = new HashMap<>();
            hintMap.put("combo", "Trois paires");
            hintMap.put("points", String.valueOf(SCORE_THREE_PAIRS) + " pts");
            hints.add(Collections.unmodifiableMap(hintMap));
        }

        // N-of-a-kind
        for (int value = 1; value <= 6; value++) {
            long count = counts.getOrDefault(value, 0L);
            Map<String, String> hintMap = new HashMap<>(); // Créez la map à l'intérieur pour chaque hint potentiel
            if (count >= 6) {
                hintMap.put("combo", "Six " + value);
                hintMap.put("points", String.valueOf(SCORE_SIX_OF_A_KIND) + " pts");
                hints.add(Collections.unmodifiableMap(hintMap));
            } else if (count == 5) {
                hintMap.put("combo", "Cinq " + value);
                hintMap.put("points", String.valueOf(SCORE_FIVE_OF_A_KIND) + " pts");
                hints.add(Collections.unmodifiableMap(hintMap));
            } else if (count == 4) {
                hintMap.put("combo", "Quatre " + value);
                hintMap.put("points", String.valueOf(SCORE_FOUR_OF_A_KIND) + " pts");
                hints.add(Collections.unmodifiableMap(hintMap));
            } else if (count == 3) {
                int pts = (value == 1) ? SCORE_TRIPLE_1 : value * SCORE_TRIPLE_OTHERS_MULTIPLIER;
                hintMap.put("combo", "Trois " + value);
                hintMap.put("points", String.valueOf(pts) + " pts");
                hints.add(Collections.unmodifiableMap(hintMap));
            }
        }

        // 1 et 5 individuels
        boolean hasTripleOrMoreOne = false;
        for(Map<String,String> existingHint : hints){
            if(existingHint.get("combo").contains(String.valueOf(1)) &&
                    (existingHint.get("combo").startsWith("Trois") || existingHint.get("combo").startsWith("Quatre") || existingHint.get("combo").startsWith("Cinq") || existingHint.get("combo").startsWith("Six"))){
                hasTripleOrMoreOne = true;
                break;
            }
        }
        if (counts.getOrDefault(1, 0L) > 0 && !hasTripleOrMoreOne) {
            Map<String, String> hintMap = new HashMap<>();
            hintMap.put("combo", "Dé(s) 1 détecté(s)");
            hintMap.put("points", String.valueOf(SCORE_SINGLE_1) + " pts chacun");
            hints.add(Collections.unmodifiableMap(hintMap));
        }

        boolean hasTripleOrMoreFive = false;
        for(Map<String,String> existingHint : hints){
            if(existingHint.get("combo").contains(String.valueOf(5)) &&
                    (existingHint.get("combo").startsWith("Trois") || existingHint.get("combo").startsWith("Quatre") || existingHint.get("combo").startsWith("Cinq") || existingHint.get("combo").startsWith("Six"))){
                hasTripleOrMoreFive = true;
                break;
            }
        }
        if (counts.getOrDefault(5, 0L) > 0 && !hasTripleOrMoreFive) {
            Map<String, String> hintMap = new HashMap<>();
            hintMap.put("combo", "Dé(s) 5 détecté(s)");
            hintMap.put("points", String.valueOf(SCORE_SINGLE_5) + " pts chacun");
            hints.add(Collections.unmodifiableMap(hintMap));
        }
        return hints;
    }

}