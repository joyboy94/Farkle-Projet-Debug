package model;

import ui.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un joueur dans le jeu Farkle.
 * Gère son nom, son score, sa couleur (console), et ses dés gardés.
 */
public class Player {

    private final String name;          // Nom du joueur
    private int score = 0;              // Score actuel
    private final String couleur;       // Couleur d'affichage (ANSI) — côté console uniquement
    private final List<Dice> keptDice;  // Dés gardés temporairement pendant le tour
    private int id;

    public void setId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Crée un joueur avec un nom et une couleur unique (console).
     * @param name Le nom du joueur
     * @param usedColors Liste des couleurs déjà attribuées
     */
    public Player(String name, List<String> usedColors) {
        this.name = name;
        this.couleur = Messages.couleurAleatoireNonRepetée(usedColors); // méthode améliorée à prévoir
        this.keptDice = new ArrayList<>();
    }

    // 🔹 Nom du joueur
    public String getName() {
        return name;
    }

    // 🔹 Score brut
    public int getScore() {
        return score;
    }

    // 🔹 Ajoute des points au score
    public void addScore(int points) {
        this.score += points;
    }

    // 🔹 Réinitialise le score
    public void resetScore() {
        this.score = 0;
    }

    // 🔹 Liste des dés gardés pendant un tour
    public List<Dice> getKeptDice() {
        return keptDice;
    }

    // 🔹 Ajoute un dé aux dés gardés
    public void addKeptDice(Dice dice) {
        keptDice.add(dice);
    }

    // 🔹 Réinitialise les dés gardés
    public void clearKeptDice() {
        keptDice.clear();
    }

    // 🔹 Récupère la couleur ANSI pour affichage console (à désactiver en GUI ou client)
    public String getCouleur() {
        return couleur;
    }

    // 🔹 Retourne le score avec couleur intégrée (console seulement)
    public String getFormattedScore() {
        return couleur + score + Messages.RESET;
    }

    // 🔹 Retourne le nom avec couleur intégrée
    public String getFormattedName() {
        return couleur + name + Messages.RESET;
    }

}
