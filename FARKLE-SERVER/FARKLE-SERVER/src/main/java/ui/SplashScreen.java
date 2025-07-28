package ui;

import javax.swing.*;

/**
 * Affiche une image d’intro pendant 3 secondes à l’ouverture du jeu.
 * L’image est chargée depuis le classpath (src/assets/logo_farkle_luffy.png).
 */
public class SplashScreen {

    public static void show() {
        // Essaie de charger l’image depuis le classpath
        java.net.URL imageUrl = SplashScreen.class.getResource("/assets/logo_farkle_luffy.png");

        if (imageUrl == null) {
            System.out.println("❌ Image introuvable ! Vérifie que assets/logo_farkle_luffy.png est bien dans src/");
            return;
        }

        // Crée une fenêtre sans bordure
        JFrame frame = new JFrame("Farkle Pirates - Joy Boy Edition");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Ajoute l’image
        ImageIcon icon = new ImageIcon(imageUrl);
        JLabel label = new JLabel(icon);
        frame.getContentPane().add(label);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Attend 3 secondes
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Ferme la fenêtre après affichage
        frame.dispose();
    }
}
