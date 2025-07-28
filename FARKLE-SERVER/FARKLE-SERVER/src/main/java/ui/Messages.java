package ui;

import model.Dice;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Classe utilitaire pour gérer tous les messages affichés au joueur.
 * Contient des messages aléatoires stylés et immersifs, des animations,
 * des couleurs ANSI pour embellir l'expérience console, etc.
 */
public class Messages {

    private static final Random random = new Random();
    public static final String RESET = "\u001B[0m"; // Réinitialise la couleur de la console

    // 🎉 Message de bienvenue
    public static String randomWelcome() {
        return pick(Arrays.asList(
                "🏴‍☠️ Bienvenue à bord moussaillon ! Prépare-toi à lancer les dés !",
                "🙏 Soyez le bienvenu dans le jeu de Farkle, noble aventurier.",
                "😈 Enfin quelqu’un de courageux ! On va voir si tu tiens le choc.",
                "🎲 Que la partie commence sous les auspices du destin !",
                "🏴‍☠️ Joy Boy t’observe, fais honneur à son chapeau de paille.",
                "👋 Bienvenue à Farkle Island ! Ici, seul le plus chanceux survit."
        ));
    }
    public static String hotDiceChoicePrompt(int tempScore) {
        String[] variants = {
                "🔥 HOT DICE ! Score actuel du tour: " + tempScore + " pts. Tu veux (Banker) ou (Relancer) ?",
                "🔥 Tous tes dés scorent ! (Banker) ou rejouer le destin avec 6 nouveaux dés ?",
                "🔥 Jackpot temporaire: " + tempScore + " points. Tente le diable ou mets à l'abri ?"
        };
        return variants[new java.util.Random().nextInt(variants.length)];
    }
    public static String selectPrompt() {
        return "💡 Quels trésors vas-tu garder ? Sélectionne tes dés scorants !";
    }
    // 💥 Message de Farkle (aucun point marqué)
    public static String randomFarkle() {
        return pick(Arrays.asList(
                "💥 Farkle ! Ton tour s’effondre comme un château de cartes.",
                "😈 BOUM ! Tu perds tout. C’est cruel mais c’est Farkle.",
                "🏴‍☠️ Pas de pitié pour les malchanceux. T’as rien gagné !",
                "🤷 Aucun point. La mer t’a rejeté.",
                "🧨 Et là… plus rien. Triste fin de tour, capitaine."
        ));
    }

    // 💰 Message lorsqu’un joueur sécurise ses points
    public static String randomBanker() {
        return pick(Arrays.asList(
                "💰 Tu mets ton trésor à l’abri. Prudent !",
                "🙏 Points sécurisés avec sagesse.",
                "🏴‍☠️ Tu caches ton butin comme un vrai pirate.",
                "😈 Petite joueuse… mais fine stratégie.",
                "🧠 Tu joues la sécurité, pas bête !",
                "🎯 Bien joué, tu sais quand t’arrêter."
        ));
    }

    // 🏳️ Message quand le joueur abandonne son tour
    public static String randomDrop() {
        return pick(Arrays.asList(
                "🏳️ Tu abandonnes cette manche. À l’abordage la prochaine fois !",
                "🙏 Parfois, se retirer est un signe de sagesse.",
                "😈 Fuyard ! Mais t’inquiète, tu reviendras…",
                "👋 Ce n’est qu’un au revoir. Reviens plus fort !",
                "🤕 Trop de pression ? T’as bien fait de souffler."
        ));
    }

    // 🔁 Message pour relancer les dés
    public static String randomNewRoll() {
        return pick(Arrays.asList(
                "🎲 Les dés sont lancés ! Que la chance soit avec toi.",
                "🌊 Le destin t’attend… Lance les dés !",
                "🏴‍☠️ Un nouveau lancer pour tenter ta chance !",
                "✨ Prépare-toi à défier les probabilités !",
                "💥 Les dés roulent… Que va-t-il se passer ?",
                "🔥 Courage, moussaillon ! Un nouveau lancer commence.",
                "🍀 Un autre tour pour tenter de gagner gros !",
                "⚔️ Mets tout en jeu sur ce lancer !",
                "🎯 Concentration maximale pour ce prochain jet !",
                "🚢 À toi de jouer, cap vers le trésor !"
        ));
    }

    // 🏆 Message de victoire
    public static String randomVictory(String name) {
        return pick(Arrays.asList(
                "🏆 Le grand gagnant est " + name + " avec un trésor de points !",
                "👑 Tous saluent " + name + ", roi du Farkle !",
                "🏴‍☠️ " + name + " a remporté la partie avec panache !",
                "😈 Victoire écrasante de " + name + " ! Quelle démonstration !",
                "👏 Bravo " + name + ", victoire bien méritée !",
                "🔥 " + name + " entre dans la légende du Farkle !"
        ));
    }

    // 🔄 Message pour recommencer une nouvelle partie
    public static String randomRestart() {
        return pick(Arrays.asList(
                "🔄 Une nouvelle légende commence !",
                "🌀 Nouveau jeu, nouveau destin !",
                "🧭 On remet les voiles pour une nouvelle aventure !",
                "🎲 Les dés sont relancés, bonne chance !",
                "🏴‍☠️ Capitaine, une nouvelle chasse au trésor t’attend !"
        ));
    }

    // 👋 Message de fin de partie
    public static String randomGoodbye() {
        return pick(Arrays.asList(
                "👋 Merci d’avoir joué, à bientôt capitaine !",
                "🙏 Merci pour cette belle partie, c’était un honneur.",
                "🏴‍☠️ Le navire rentre au port. À la prochaine bataille !",
                "😈 Tu t’en sors pas si mal… pour un rookie.",
                "👑 La partie est finie, mais la légende continue.",
                "🍷 Un bon jeu, un bon rhum, et un adieu élégant."
        ));
    }

    // 🎨 Affichage en couleur d'une valeur de dé
    public static String coloredDiceValue(int value) {
        String[] colors = {
                "\u001B[31m", // Rouge
                "\u001B[32m", // Vert
                "\u001B[33m", // Jaune
                "\u001B[34m", // Bleu
                "\u001B[35m", // Magenta
                "\u001B[36m"  // Cyan
        };
        return colors[(value - 1) % colors.length] + value + RESET;
    }

    // 🔤 Animation style "machine à écrire"
    public static void typewriter(String message) {
        for (char c : message.toCharArray()) {
            System.out.print(c);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println();
    }

    // 💎 Affiche les combinaisons spéciales après un lancer
    public static void afficherComboSpeciaux(List<Dice> dice) {
        int[] counts = new int[7];
        for (Dice d : dice) counts[d.getValue()]++;

        if (Arrays.equals(counts, new int[]{0, 1, 1, 1, 1, 1, 1})) {
            afficherPointsCombo("🔥 Suite complète (1-6)", 2500);
            return;
        }

        int pairs = 0;
        for (int c : counts) if (c == 2) pairs++;
        if (pairs == 3) {
            afficherPointsCombo("💕 Trois paires", 1500);
            return;
        }

        for (int i = 1; i <= 6; i++) {
            if (counts[i] == 6) {
                afficherPointsCombo("🎯 Six " + i, 3000);
                return;
            }
            if (counts[i] == 5) {
                afficherPointsCombo("🎯 Cinq " + i, 2000);
                return;
            }
            if (counts[i] == 4) {
                afficherPointsCombo("🎯 Quatre " + i, 1000);
                return;
            }
        }
    }

    public static void afficherPointsCombo(String nomCombo, int points) {
        System.out.println("\u001B[33m✨ " + nomCombo + " ! (" + points + " points)\u001B[0m");
    }

    public static String couleurAleatoireNonRepetée(List<String> usedColors) {
        String[] options = {
                "\u001B[31m", "\u001B[32m", "\u001B[33m", "\u001B[34m", "\u001B[35m", "\u001B[36m"
        };
        List<String> available = Arrays.stream(options)
                .filter(c -> !usedColors.contains(c))
                .collect(Collectors.toList());
        String color = available.isEmpty() ? "\u001B[37m" : available.get(random.nextInt(available.size()));
        usedColors.add(color);
        return color;
    }


    public class MessageEvent {
        public String message;
        public String color; // Ex: "#FFD700", "red", "gold"

        public MessageEvent() {}

        public MessageEvent(String message, String color) {
            this.message = message;
            this.color = color;
        }
    }


    private static String pick(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }

    public static void afficherCombinaisonsGagnantes() {
        System.out.println("📜 COMBINAISONS QUI RAPPORTENT DES POINTS :");
        System.out.println(" - 1 seul = 100 pts");
        System.out.println(" - 5 seul = 50 pts");
        System.out.println(" - Trois 1 = 1000 pts");
        System.out.println(" - Trois 2 = 200 pts");
        System.out.println(" - Trois 3 = 300 pts");
        System.out.println(" - Trois 4 = 400 pts");
        System.out.println(" - Trois 5 = 500 pts");
        System.out.println(" - Trois 6 = 600 pts");
        System.out.println(" - Quatre identiques = 1000 pts");
        System.out.println(" - Cinq identiques = 2000 pts");
        System.out.println(" - Six identiques = 3000 pts");
        System.out.println(" - Trois paires = 1500 pts");
        System.out.println(" - Suite 1-2-3-4-5-6 = 2500 pts");
    }
}
