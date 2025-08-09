package model;

import java.util.Random;

/**
 * Représente un dé à six faces unique dans le jeu.
 * Chaque instance de cette classe est un objet indépendant qui peut être "lancé"
 * pour obtenir une nouvelle valeur aléatoire.
 */
public class Dice {

    /** La valeur numérique actuelle de la face supérieure du dé (de 1 à 6). */
    private int value;

    /**
     * Constructeur par défaut.
     * Crée un nouveau dé et appelle immédiatement la méthode roll() pour lui assigner
     * une valeur aléatoire. C'est ce qui fait que les dés ont une valeur dès le début du tour.
     */
    public Dice() {
        roll();
    }

    /**
     * Constructeur alternatif qui permet de créer un dé avec une valeur prédéfinie.
     * Très utile pour les tests ou pour des cas de jeu spécifiques.
     * @param value La valeur à assigner au dé (devrait être entre 1 et 6).
     */
    public Dice(int value) {
        this.value = value;
    }

    /**
     * Simule le lancer du dé.
     * Assigne une nouvelle valeur aléatoire (un entier entre 1 et 6 inclus) à l'attribut 'value'.
     */
    public void roll() {
        this.value = new Random().nextInt(6) + 1;
    }

    /**
     * Méthode "getter" standard pour accéder à la valeur actuelle du dé.
     * @return La valeur actuelle de la face du dé.
     */
    public int getValue() {
        return value;
    }

    /**
     * Méthode "setter" standard pour modifier manuellement la valeur du dé.
     * @param value La nouvelle valeur à assigner au dé.
     */
    public void setValue(int value) {
        this.value = value;
    }
}