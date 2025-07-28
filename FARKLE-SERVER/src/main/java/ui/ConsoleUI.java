package ui;

public class ConsoleUI {
    public static void main(String[] args) {
        SplashScreen.show();
        afficherMenu();
    }

    private static void afficherMenu() {
        while (true) {
            System.out.println("\n📋 Menu :");
            System.out.println("[R] Règles");
            System.out.println("[Q] Quitter");
            System.out.print("👉 Choix : ");
            String choix = new java.util.Scanner(System.in).nextLine().trim().toLowerCase();

            switch (choix) {
                case "r":
                    afficherRegles();
                    break;
                case "q":
                    quitterJeu();
                    break;
                default:
                    System.out.println("❌ Choix invalide. Essaie encore !");
            }
        }
    }


    private static void afficherRegles() {
        System.out.println("\n📜 RÈGLES DU JEU FARKLE (version simplifiée) 📜\n");
        System.out.println("🎯 Objectif : Atteindre 10 000 points avant ton adversaire.");
        System.out.println("🎲 Tu lances 6 dés. Garde ceux qui scorent (1, 5, triples, etc.).");
        System.out.println("🔁 Tu peux relancer les dés restants tant que tu gardes un scorant.");
        System.out.println("💣 Si tu ne scores rien sur un lancer, c'est un FARKLE = Tour perdu !");
        System.out.println("💰 Tu peux \"banker\" pour sécuriser les points gagnés pendant le tour.\n");
        System.out.println("📈 COMBINAISONS QUI RAPPORTENT :\n");
        Messages.afficherCombinaisonsGagnantes();
        System.out.println("\nAppuie sur Entrée pour revenir au menu...");
        new java.util.Scanner(System.in).nextLine();
    }

    private static void quitterJeu() {
        Messages.typewriter(Messages.randomGoodbye());
        System.exit(0);
    }
}
