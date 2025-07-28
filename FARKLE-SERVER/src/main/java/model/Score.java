package model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Classe utilitaire pour le calcul des points et la validation des sélections dans Farkle.
 * Toutes les règles officielles, Farkle, Hot Dice, combinaisons spéciales, etc.
 */
public class Score {

    /**
     * Calcule le total de points pour une liste de dés donnés.
     * Gère toutes les combinaisons classiques ET spéciales.
     */
    public static int calculatePoints(List<Dice> dice) {
        int[] counts = new int[7]; // Index 1 à 6 = valeurs des dés
        for (Dice d : dice) counts[d.getValue()]++;

        int points = 0;

        // 🔥 Suite complète 1-2-3-4-5-6 → 2500 points
        if (Arrays.equals(counts, new int[]{0, 1, 1, 1, 1, 1, 1})) return 2500;

        // 💕 Trois paires → 1500 points
        int pairCount = 0;
        for (int c : counts) if (c == 2) pairCount++;
        if (pairCount == 3) return 1500;

        // 🎯 Six, cinq, quatre dés identiques
        for (int i = 1; i <= 6; i++) {
            if (counts[i] == 6) { points += 3000; counts[i] = 0; }
            else if (counts[i] == 5) { points += 2000; counts[i] = 0; }
            else if (counts[i] == 4) { points += 1000; counts[i] = 0; }
        }

        // 👑 Trois dés identiques : 1000 pour 1, sinon face × 100
        if (counts[1] >= 3) { points += 1000; counts[1] -= 3; }
        for (int i = 2; i <= 6; i++) {
            if (counts[i] >= 3) { points += i * 100; counts[i] -= 3; }
        }

        // ✨ Dés individuels : 1 = 100 pts, 5 = 50 pts
        points += counts[1] * 100;
        points += counts[5] * 50;

        return points;
    }

    /**
     * Retourne les dés scorants parmi une liste (1, 5 ou groupes de 3+ identiques).
     */
    public static List<Dice> getScoringDice(List<Dice> diceList) {
        Map<Integer, Long> valueCount = diceList.stream()
                .collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));

        List<Dice> copy = new ArrayList<>(diceList);
        List<Dice> scorers = new ArrayList<>();

        // 🎯 Groupes de 3+ identiques
        for (int val = 1; val <= 6; val++) {
            long count = valueCount.getOrDefault(val, 0L);
            if (count >= 3) {
                int taken = 0;
                for (Iterator<Dice> it = copy.iterator(); it.hasNext() && taken < count; ) {
                    Dice d = it.next();
                    if (d.getValue() == val) {
                        scorers.add(d); it.remove(); taken++;
                    }
                }
            }
        }
        // ✨ Dés 1 et 5 restants
        for (Iterator<Dice> it = copy.iterator(); it.hasNext(); ) {
            Dice d = it.next();
            if (d.getValue() == 1 || d.getValue() == 5) {
                scorers.add(d); it.remove();
            }
        }
        return scorers;
    }

    /**
     * Vérifie que les dés sélectionnés sont bien scorants.
     */
    public static boolean isValidSelection(List<Dice> selectedDice, List<Dice> availableScoringDice) {
        Map<Integer, Long> selectedCounts = selectedDice.stream()
                .map(Dice::getValue)
                .collect(Collectors.groupingBy(Integer::intValue, Collectors.counting()));

        Map<Integer, Long> availableCounts = availableScoringDice.stream()
                .map(Dice::getValue)
                .collect(Collectors.groupingBy(Integer::intValue, Collectors.counting()));

        for (Map.Entry<Integer, Long> entry : selectedCounts.entrySet()) {
            int value = entry.getKey();
            long count = entry.getValue();
            if (!availableCounts.containsKey(value) || availableCounts.get(value) < count) {
                return false;
            }
        }
        return true;
    }

    /**
     * Teste si tous les dés sont scorants (Hot Dice classique).
     */
    public static boolean isHotDice(List<Dice> diceList) {
        return getScoringDice(diceList).size() == diceList.size();
    }

    /**
     * Teste si la combinaison est un Hot Dice spécial (suite, 3 paires, 6 identiques)
     */
    public static boolean isHotDiceSpecialCombo(List<Dice> diceList) {
        int[] counts = new int[7];
        for (Dice d : diceList) counts[d.getValue()]++;
        if (Arrays.equals(counts, new int[]{0, 1, 1, 1, 1, 1, 1})) return true; // suite complète
        int pairCount = 0;
        for (int c : counts) if (c == 2) pairCount++;
        if (pairCount == 3) return true; // 3 paires
        for (int i = 1; i <= 6; i++) if (counts[i] == 6) return true; // 6 identiques
        return false;
    }
}
