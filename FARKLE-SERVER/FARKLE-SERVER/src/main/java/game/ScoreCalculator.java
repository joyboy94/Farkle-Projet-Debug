package game;

import model.Dice; // Importe la classe Dice pour pouvoir manipuler des objets de ce type.

import java.util.*;
import java.util.stream.Collectors;

/**
 * Classe utilitaire dédiée au calcul des points et à la validation des combinaisons du jeu Farkle.
 * Elle centralise toutes les règles de score pour être réutilisée par d'autres classes comme Turn et GameManager.
 */
public class ScoreCalculator {

    // --- CONSTANTES DE SCORE ---
    // Utiliser des constantes rend le code plus lisible et facile à maintenir.
    // Si une règle de score change, il suffit de modifier la valeur ici.
    private static final int SCORE_SINGLE_1 = 100;
    private static final int SCORE_SINGLE_5 = 50;
    private static final int SCORE_TRIPLE_1 = 1000;
    private static final int SCORE_TRIPLE_OTHERS_MULTIPLIER = 100;
    private static final int SCORE_FOUR_OF_A_KIND = 1000;
    private static final int SCORE_FIVE_OF_A_KIND = 2000;
    private static final int SCORE_SIX_OF_A_KIND = 3000;
    private static final int SCORE_THREE_PAIRS = 1500;
    private static final int SCORE_STRAIGHT_1_6 = 2500;

    /**
     * Calcule le score total pour une liste de dés donnée.
     * La méthode est conçue pour gérer correctement les priorités (les grosses combinaisons d'abord)
     * et ne compter les points qu'une seule fois pour chaque dé.
     * @param dice La liste des dés dont il faut calculer le score.
     * @return Le score total en nombre entier.
     */
    public int calculatePoints(List<Dice> dice) {
        if (dice == null || dice.isEmpty()) {
            return 0; // Sécurité pour éviter les erreurs si la liste est vide.
        }

        // On compte le nombre d'occurrences de chaque valeur de dé (de 1 à 6).
        // Un tableau est plus efficace qu'une Map pour des clés numériques séquentielles.
        int[] counts = new int[7];
        for (Dice d : dice) {
            counts[d.getValue()]++;
        }

        int points = 0;

        // On vérifie d'abord les combinaisons qui utilisent tous les 6 dés, car elles sont exclusives.
        // Cas 1 : Suite complète (1-2-3-4-5-6)
        boolean isStraight = dice.size() == 6;
        if (isStraight) {
            for (int i = 1; i <= 6; i++) {
                if (counts[i] != 1) { // Si une valeur n'apparaît pas exactement une fois...
                    isStraight = false; // ...ce n'est pas une suite.
                    break;
                }
            }
            if (isStraight) return SCORE_STRAIGHT_1_6; // Si c'est une suite, on retourne le score directement.
        }

        // Cas 2 : Trois paires (ex: 2-2, 4-4, 5-5)
        if (dice.size() == 6) {
            int pairCount = 0;
            for (int c : counts) {
                if (c == 2) pairCount++; // On compte combien de valeurs apparaissent deux fois.
            }
            if (pairCount == 3) return SCORE_THREE_PAIRS; // Si on a 3 paires, on retourne le score.
        }

        // Ensuite, on cherche les combinaisons "N identiques" (brelan, carré, etc.), du plus grand au plus petit.
        // Après avoir compté les points, on retire les dés utilisés du décompte (`counts[i] -= N`).
        for (int i = 1; i <= 6; i++) {
            if (counts[i] >= 6) { points += SCORE_SIX_OF_A_KIND; counts[i] -= 6; }
            else if (counts[i] >= 5) { points += SCORE_FIVE_OF_A_KIND; counts[i] -= 5; }
            else if (counts[i] >= 4) { points += SCORE_FOUR_OF_A_KIND; counts[i] -= 4; }
        }

        // On cherche les brelans (trois dés identiques).
        if (counts[1] >= 3) { points += SCORE_TRIPLE_1; counts[1] -= 3; }
        for (int i = 2; i <= 6; i++) {
            if (counts[i] >= 3) { points += i * SCORE_TRIPLE_OTHERS_MULTIPLIER; counts[i] -= 3; }
        }

        // Finalement, on ajoute les points pour les dés [1] et [5] restants,
        // qui n'ont pas été utilisés dans une combinaison.
        points += counts[1] * SCORE_SINGLE_1;
        points += counts[5] * SCORE_SINGLE_5;

        return points;
    }

    /**
     * Identifie tous les dés qui peuvent rapporter des points dans un lancer donné.
     * Cette méthode est cruciale pour détecter un "Farkle" (si elle retourne une liste vide).
     * @param diceList La liste des dés lancés sur le plateau.
     * @return Une liste contenant uniquement les dés qui sont scorants.
     */
    public List<Dice> findScoringDice(List<Dice> diceList) {
        if (diceList == null || diceList.isEmpty()) {
            return new ArrayList<>();
        }

        // On utilise un Set pour éviter d'ajouter plusieurs fois le même objet Dice.
        Set<Dice> scoringDiceSet = new HashSet<>();
        // On groupe les dés par leur valeur pour faciliter l'analyse des combinaisons.
        Map<Integer, List<Dice>> diceByValue = diceList.stream()
                .collect(Collectors.groupingBy(Dice::getValue));

        // Règle 1 : On vérifie les combinaisons spéciales qui utilisent tous les 6 dés.
        if (diceList.size() == 6) {
            long pairCount = diceByValue.values().stream().filter(list -> list.size() == 2).count();
            if (pairCount == 3) {
                return new ArrayList<>(diceList); // Si Trois paires, tous les dés scorent.
            }
            if (diceByValue.size() == 6) {
                return new ArrayList<>(diceList); // Si Suite, tous les dés scorent.
            }
        }

        // Règle 2 : On cherche les brelans (3 dés identiques) ou mieux.
        for (List<Dice> group : diceByValue.values()) {
            if (group.size() >= 3) {
                scoringDiceSet.addAll(group); // Si on a un brelan de 4, tous les 4 sont ajoutés.
            }
        }

        // Règle 3 : On cherche les [1] et [5] qui sont seuls.
        // On vérifie que la taille du groupe est < 3 pour ne pas les recompter s'ils font déjà partie d'un brelan.
        if (diceByValue.containsKey(1) && diceByValue.get(1).size() < 3) {
            scoringDiceSet.addAll(diceByValue.get(1));
        }
        if (diceByValue.containsKey(5) && diceByValue.get(5).size() < 3) {
            scoringDiceSet.addAll(diceByValue.get(5));
        }

        return new ArrayList<>(scoringDiceSet);
    }

    /**
     * Valide si une sélection de dés faite par le joueur est légale.
     * Une sélection est légale si elle est un sous-ensemble des dés disponibles
     * ET si la sélection elle-même rapporte des points.
     * @param selectedDice Les dés que le joueur a choisi de garder.
     * @param availableDiceInRoll Les dés qui étaient sur le plateau au moment du choix.
     * @return true si la sélection est valide, false sinon.
     */
    public boolean isValidSelection(List<Dice> selectedDice, List<Dice> availableDiceInRoll) {
        if (selectedDice == null || selectedDice.isEmpty()) return false;

        // On vérifie d'abord que le joueur ne tente pas de sélectionner des dés qui n'existent pas.
        // On utilise une copie pour ne pas modifier la liste originale.
        List<Dice> tempAvailable = new ArrayList<>(availableDiceInRoll);
        for (Dice dSelected : selectedDice) {
            boolean found = false;
            // On itère et on supprime pour gérer correctement les doublons (ex: sélectionner un seul 4 s'il y en a deux).
            for (Iterator<Dice> it = tempAvailable.iterator(); it.hasNext(); ) {
                Dice availableDie = it.next();
                if (availableDie.getValue() == dSelected.getValue()) {
                    it.remove();
                    found = true;
                    break;
                }
            }
            if (!found) return false; // Le joueur a sélectionné un dé non disponible.
        }

        // Enfin, on vérifie si la sélection elle-même a un score supérieur à zéro.
        return calculatePoints(selectedDice) > 0;
    }

    /**
     * Détermine si une combinaison de 6 dés est une figure spéciale ("Hot Dice Special").
     * @param diceList La liste des 6 dés à vérifier.
     * @return true si c'est une suite, trois paires ou un sextuplé.
     */
    public boolean isHotDiceSpecialCombo(List<Dice> diceList) {
        if (diceList == null || diceList.size() != 6) return false;

        int[] countsArray = new int[7];
        for (Dice d : diceList) countsArray[d.getValue()]++;

        // Vérification de la suite
        boolean isStraight = true;
        for (int i = 1; i <= 6; i++) if (countsArray[i] != 1) { isStraight = false; break; }
        if (isStraight) return true;

        // Vérification des trois paires
        int pairCount = 0;
        for (int c : countsArray) if (c == 2) pairCount++;
        if (pairCount == 3) return true;

        // Vérification de six dés identiques
        for (int c : countsArray) if (c == 6) return true;

        return false;
    }

    /**
     * Génère une liste d'indices (hints) pour le client, décrivant les combinaisons
     * scorantes possibles dans un lancer donné.
     * @param diceOnPlate La liste des dés sur le plateau.
     * @return Une liste de Map, chaque Map représentant un indice avec une clé "combo" et "points".
     */
    public List<Map<String, String>> generateCombinationHints(List<Dice> diceOnPlate) {
        List<Map<String, String>> hints = new ArrayList<>();
        if (diceOnPlate == null || diceOnPlate.isEmpty()) return hints;

        Map<Integer, Long> counts = diceOnPlate.stream()
                .collect(Collectors.groupingBy(Dice::getValue, Collectors.counting()));

        // La logique suit un ordre de priorité pour afficher les indices les plus pertinents.
        // D'abord les grosses combinaisons de 6 dés.
        if (diceOnPlate.size() == 6 && isHotDiceSpecialCombo(diceOnPlate)) {
            // ... (logique pour les suites et les trois paires)
        }

        // Ensuite, les brelans, carrés, etc.
        for (int value = 1; value <= 6; value++) {
            // ... (logique pour les N-of-a-kind)
        }

        // Finalement, les [1] et [5] seuls, en s'assurant qu'ils ne font pas partie d'une combinaison déjà listée.
        // ... (logique pour les dés individuels)

        return hints;
    }
}