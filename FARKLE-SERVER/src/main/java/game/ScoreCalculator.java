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
        Map<Integer, Long> counts = dice.stream()
                .collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));
        int totalPoints = 0;
        Map<Integer, Long> mutableCounts = new HashMap<>(counts);

        // 1. Suite complète 1-6 (6 dés)
        if (dice.size() == 6 && mutableCounts.keySet().size() == 6 &&
                mutableCounts.values().stream().allMatch(c -> c == 1)) {
            return SCORE_STRAIGHT_1_6;
        }
        // 2. Trois paires (6 dés)
        if (dice.size() == 6 && mutableCounts.values().stream().filter(c -> c == 2).count() == 3) {
            return SCORE_THREE_PAIRS;
        }
        // 3. Six identiques
        for (int value = 1; value <= 6; value++) {
            if (mutableCounts.getOrDefault(value, 0L) == 6) {
                return SCORE_SIX_OF_A_KIND; // Utilise tous les dés, score unique
            }
        }

        // Combinaisons N-of-a-kind (ne consomment pas forcément tous les dés de la liste fournie)
        for (int value = 1; value <= 6; value++) {
            long count = mutableCounts.getOrDefault(value, 0L);
            if (count >= 5) { // Cinq identiques (plus prioritaire que brelan si 5 sont fournis)
                totalPoints += SCORE_FIVE_OF_A_KIND;
                mutableCounts.put(value, 0L); // Consomme ces dés pour le calcul des 1 et 5
            } else if (count >= 4) { // Quatre identiques
                totalPoints += SCORE_FOUR_OF_A_KIND;
                mutableCounts.put(value, 0L);
            } else if (count >= 3) { // Trois identiques
                totalPoints += (value == 1) ? SCORE_TRIPLE_1 : value * SCORE_TRIPLE_OTHERS_MULTIPLIER;
                mutableCounts.put(value, mutableCounts.get(value) - 3);
            }
        }
        // Dés individuels restants
        totalPoints += mutableCounts.getOrDefault(1, 0L) * SCORE_SINGLE_1;
        totalPoints += mutableCounts.getOrDefault(5, 0L) * SCORE_SINGLE_5;
        return totalPoints;
    }

    public List<Dice> findScoringDice(List<Dice> diceList) {
        if (diceList == null || diceList.isEmpty()) return new ArrayList<>();
        Set<Dice> scoringDiceSet = new HashSet<>();
        Map<Integer, Long> counts = diceList.stream().collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));

        if (isHotDiceSpecialCombo(diceList)) return new ArrayList<>(diceList); // Si c'est une combo spéciale, tous les dés scorent

        for (int value = 1; value <= 6; value++) {
            final int v = value; // Ajoute ceci
            if (counts.getOrDefault(v, 0L) >= 3) {
                diceList.stream()
                        .filter(d -> d.getValue() == v) // Utilise 'v' ici !
                        .forEach(scoringDiceSet::add);
            }
        }

        diceList.stream().filter(d -> d.getValue() == 1).forEach(scoringDiceSet::add);
        diceList.stream().filter(d -> d.getValue() == 5).forEach(scoringDiceSet::add);
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