package game;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class ServerMessages {
    private static final Random random = new Random();

    // --- Messages d'Ambiance Généraux ---
    public static String welcome() { return pick(Arrays.asList("🏴‍☠️ Bienvenue à bord moussaillon ! Prépare-toi à lancer les dés !", "🙏 Soyez le bienvenu dans le jeu de Farkle, noble aventurier.", "😈 Enfin quelqu’un de courageux ! On va voir si tu tiens le choc.", "🎲 Que la partie commence sous les auspices du destin !", "🏴‍☠️ Joy Boy t’observe, fais honneur à son chapeau de paille.", "👋 Bienvenue à Farkle Island ! Ici, seul le plus chanceux survit.")); }
    public static String playerTurn(String playerName) { return String.format("🧭 C’est à %s de jouer !", playerName); }
    public static String diceRollAnimation() { return pick(Arrays.asList("🚢 À toi de jouer, cap vers le trésor !", "🎲 Les dés sont lancés ! Que la chance soit avec toi.", "🌊 Le destin t’attend… Lance les dés !", "✨ Prépare-toi à défier les probabilités !", "💥 Les dés roulent… Que va-t-il se passer ?", "🔥 Courage, moussaillon ! Un nouveau lancer commence.")); }
    public static String diceRolled() { return "🎲 Les dés sont lancés !"; }
    public static String eventHotDiceResolvedBank() { // <-- MÉTHODE RECHERCHÉE
        return "Sage décision de sécuriser le butin du Hot Dice !";
    }
    public static String eventHotDiceResolvedRoll() { // <-- Et sa jumelle pour la relance
        return "L'audace paiera-t-elle ? Relance des 6 dés après ce Hot Dice !";
    }

    // --- Prompts d'Action ---
    public static String selectPrompt() { return "💡 Quels trésors vas-tu garder ? Sélectionne tes dés scorants !"; }
    public static String rollAgainPrompt() { return "🎲 Prêt à défier à nouveau le sort ? Relance les dés restants !"; }
    public static String bankPrompt(int tempScore) { return String.format("💰 Tu as %d points ce tour-ci. Veux-tu les mettre en sécurité (Banker) ?", tempScore); }
    public static String hotDiceChoicePrompt(int tempScore) { return String.format("🔥 HOT DICE ! Score actuel du tour: %d. (B)anker ce pactole ou (R)elancer les 6 dés ?", tempScore); }

    // --- Événements de Jeu ---
    public static String diceKept(String diceValues, int points) { return String.format("✅ Dés gardés : %s (+%d points).", diceValues, points); }
    public static String farkle() { return pick(Arrays.asList("💥 Farkle ! Ton tour s’effondre comme un château de cartes.", "😈 BOOM ! Tu perds tout. C’est cruel mais c’est Farkle.", "🤷 Aucun point. La mer t’a rejeté cette fois.", "🧨 Et là… plus rien. Triste fin de tour, capitaine.")); }
    public static String hotDiceGeneric() { return pick(Arrays.asList("🔥 HOT DICE ! Tous tes dés sont scorants !", "✨ Flamboyant ! Tous les dés marquent !")); }
    public static String hotDiceSpecial(String comboName, int points) { return String.format("✨ 🔥 %s ! (%d points) C'est un HOT DICE SPÉCIAL !", comboName, points); }
    public static String pointsAddedAutomatically(int points) { return String.format("🎯 Points ajoutés automatiquement : %d", points); }
    public static String bankedPoints(int points, String playerName, int totalScore) { return pick(Arrays.asList(String.format("➕ BANKING pour %s: %d points (Total: %d). Prudent !", playerName, points, totalScore), String.format("💰 %s met son trésor à l’abri (%d pts). Total: %d.", playerName, points, totalScore), String.format("🧠 %s joue la sécurité avec %d pts. Total: %d.", playerName, points, totalScore))); }
    public static String victory(String playerName, int score) { return pick(Arrays.asList(String.format("🏆 Le grand %s est le ROI DU FARKLE avec %d points !", playerName, score), String.format("👑 Tous saluent %s, nouveau Seigneur Pirate du Farkle avec %d points !", playerName, score), String.format("😈 Victoire écrasante de %s (%d pts) ! Quelle démonstration !", playerName, score)));}

    // --- Erreurs / Infos ---
    public static String invalidSelection() { return "❌ Sélection invalide. Choisis des dés qui marquent des points et qui sont disponibles !"; }
    public static String cannotRollNow() { return "⚓️ Action impossible : Tu dois d'abord sélectionner des dés scorants ou la situation ne permet pas de lancer !"; }
    public static String cannotSelectNow() { return "⚓️ Action impossible : Tu dois lancer les dés avant de pouvoir sélectionner !"; }
    public static String cannotBankNow() { return "⚓️ Action impossible : Tu dois avoir des points à sécuriser ce tour-ci pour banker !"; }
    public static String waitingForPlayers() { return "⏳ En attente d'un autre pirate pour commencer la chasse au trésor...";}
    public static String gameReady() { return "🌊 La partie est prête à commencer ! Que le meilleur gagne !"; }


    private static String pick(List<String> list) {
        if (list == null || list.isEmpty()) return "";
        return list.get(random.nextInt(list.size()));
    }
}