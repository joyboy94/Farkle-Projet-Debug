package org.example.farkleclientfx;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import io.swagger.client.model.TurnStatusDTO;

import java.io.IOException;

/**
 * Classe principale de l'application client JavaFX.
 * Elle hérite de `Application` et son rôle est de démarrer l'interface graphique.
 * Cette classe gère le "Splash Screen" (écran de chargement) et la transition vers la fenêtre de jeu principale.
 */
public class MainApp extends Application {

    /**
     * Méthode principale du cycle de vie d'une application JavaFX.
     * Elle est appelée au lancement de l'application pour initialiser et afficher la première fenêtre.
     * @param primaryStage La fenêtre principale (Stage) fournie par JavaFX.
     * @throws IOException Si le fichier FXML ne peut pas être chargé.
     */
    @Override
    public void start(Stage primaryStage) throws IOException {

        // --- 1. Création de la scène du Splash Screen avec votre image ---
        StackPane splashPane = new StackPane();
        try {
            // Charge l'image depuis le dossier des ressources.
            Image splashImage = new Image(getClass().getResource("images/splash_image.png").toExternalForm());
            splashPane.getChildren().add(new ImageView(splashImage));
        } catch (Exception e) {
            System.err.println("ERREUR : Impossible de charger l'image du splash screen.");
            System.err.println("Vérifiez que le nom du fichier est correct et qu'il est dans le dossier 'images'.");
            // En cas d'erreur, on affiche un fond noir pour ne pas planter l'application.
            splashPane.setStyle("-fx-background-color: black;");
        }

        Scene splashScene = new Scene(splashPane, 800, 600); // Adaptez la taille à votre image si besoin.
        // Rend la scène transparente pour que les coins de l'image (si elle est arrondie) ne soient pas blancs.
        splashScene.setFill(Color.TRANSPARENT);

        // --- 2. Configuration de la fenêtre en mode "sans bordure" pour un effet plus immersif ---
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(splashScene);
        primaryStage.show();

        // --- 3. Création d'une tâche de fond pour charger le jeu sans figer l'interface ---
        // Un `Task` JavaFX est idéal pour les opérations longues (chargement de fichiers, appels réseau).
        Task<Parent> loadTask = new Task<>() {
            @Override
            protected Parent call() throws Exception {
                // On simule un temps de chargement pour que l'utilisateur ait le temps de voir le splash screen.
                Thread.sleep(2500); // Pause de 2.5 secondes.

                // On charge le fichier FXML qui définit la structure de l'interface de jeu. C'est l'opération la plus longue.
                FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource("MainView.fxml"));
                return fxmlLoader.load();
            }
        };

        // --- 4. Définition de ce qu'il faut faire une fois que la tâche de fond a réussi ---
        loadTask.setOnSucceeded(event -> {
            Parent gameRoot = loadTask.getValue(); // On récupère l'interface de jeu chargée.
            Scene gameScene = new Scene(gameRoot, 1280, 800);
            // On applique la feuille de style CSS à notre interface.
            gameScene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());

            // On crée une NOUVELLE fenêtre (Stage), cette fois avec un style normal (bordures, titre, etc.).
            Stage gameStage = new Stage();
            gameStage.setTitle("Farkle Pirates - Joy Boy Edition!");
            gameStage.setScene(gameScene);

            // On ferme la fenêtre du splash screen.
            primaryStage.close();
            // Et on affiche la nouvelle fenêtre du jeu.
            gameStage.show();
        });

        // On lance la tâche de chargement sur un nouveau thread pour ne pas bloquer le thread de l'interface graphique.
        new Thread(loadTask).start();
    }


    /**
     * Point d'entrée standard d'un programme Java.
     * La méthode `launch()` est une méthode de la classe `Application` qui démarre le cycle de vie JavaFX.
     * @param args Les arguments de la ligne de commande (non utilisés ici).
     */
    public static void main(String[] args) {
        launch();
    }

    /**
     * Méthode utilitaire pour afficher une représentation textuelle de l'état du jeu dans la console.
     * Utile pour le débogage ou pour une version "console" du client.
     * @param etat L'objet DTO contenant toutes les informations sur l'état du jeu.
     * @param estMonTour Un booléen indiquant si c'est le tour du joueur qui exécute ce client.
     * @param monNom Le nom du joueur local.
     */
    public void afficherEtatPartieConsole(TurnStatusDTO etat, boolean estMonTour, String monNom) {
        System.out.println("\n======================== 🎲 FARKLE 🎲 ========================");

        // Affiche clairement qui est le joueur actif.
        System.out.println("👤 Joueur Actif : " + etat.currentPlayerName);

        // Adapte l'affichage pour que le joueur local se voie toujours comme "Vous".
        if(estMonTour) {
            System.out.printf("🟢 [Vous] %-15s | Score : %d\n", monNom, etat.currentPlayerScore);
            System.out.printf("🔴 [Adversaire] %-15s | Score : %d\n", etat.opponentPlayerName, etat.opponentPlayerScore);
        } else {
            System.out.printf("🟢 [Actif] %-15s | Score : %d\n", etat.currentPlayerName, etat.currentPlayerScore);
            System.out.printf("🔴 [Vous] %-15s | Score : %d\n", monNom, etat.opponentPlayerScore);
        }

        // Affiche les différentes listes de dés et le score temporaire.
        System.out.println("\n🎲 Dés sur le plateau : " + (etat.diceOnPlate.isEmpty() ? "Aucun dé" : etat.diceOnPlate));
        System.out.println("📥 Dés gardés ce tour-ci : " + (etat.keptDiceThisTurn.isEmpty() ? "Aucun dé" : etat.keptDiceThisTurn));
        System.out.println("💰 Score temporaire ce tour : " + etat.tempScore);

        System.out.println("==============================================================\n");
    }

}