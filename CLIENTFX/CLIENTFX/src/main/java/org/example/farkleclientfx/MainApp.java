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

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {

        // --- 1. Création de la scène du Splash Screen avec votre image ---
        StackPane splashPane = new StackPane();
        try {
            // !! IMPORTANT : Mettez ici le nom exact de votre image de splash screen !!
            Image splashImage = new Image(getClass().getResource("images/splash_image.png").toExternalForm());
            splashPane.getChildren().add(new ImageView(splashImage));
        } catch (Exception e) {
            System.err.println("ERREUR : Impossible de charger l'image du splash screen.");
            System.err.println("Vérifiez que le nom du fichier est correct et qu'il est dans le dossier 'images'.");
            // En cas d'erreur, on affiche un fond noir pour ne pas crasher
            splashPane.setStyle("-fx-background-color: black;");
        }

        Scene splashScene = new Scene(splashPane, 800, 600); // Adaptez la taille à votre image si besoin
        // On rend la scène transparente pour que les coins de l'image ne soient pas blancs
        splashScene.setFill(Color.TRANSPARENT);

        // --- 2. Configuration de la fenêtre en mode "sans bordure" (plus immersif) ---
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(splashScene);
        primaryStage.show();

        // --- 3. Création de la tâche de fond pour charger le jeu ---
        Task<Parent> loadTask = new Task<>() {
            @Override
            protected Parent call() throws Exception {
                // On laisse le temps au splash screen de s'afficher et à l'utilisateur de le voir
                Thread.sleep(2500); // Pause de 2.5 secondes

                // On charge le FXML (opération la plus longue)
                FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource("MainView.fxml"));
                return fxmlLoader.load();
            }
        };

        // --- 4. Quand le chargement est fini, on change de fenêtre ---
        loadTask.setOnSucceeded(event -> {
            Parent gameRoot = loadTask.getValue();
            Scene gameScene = new Scene(gameRoot, 1280, 800);
            gameScene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());

            // On crée une NOUVELLE fenêtre, avec un style normal, pour le jeu
            Stage gameStage = new Stage();
            gameStage.setTitle("Farkle Pirates - Joy Boy Edition!");
            gameStage.setScene(gameScene);

            // On ferme la fenêtre du splash screen
            primaryStage.close();
            // Et on affiche la nouvelle fenêtre du jeu
            gameStage.show();
        });

        // On lance la tâche de fond
        new Thread(loadTask).start();
    }


    public static void main(String[] args) {
        launch();
    }

    public void afficherEtatPartieConsole(TurnStatusDTO etat, boolean estMonTour, String monNom) {
        System.out.println("\n======================== 🎲 FARKLE 🎲 ========================");

        // Affichage clair des joueurs et leurs scores
        System.out.println("👤 Joueur Actif : " + etat.currentPlayerName);

        if(estMonTour) {
            System.out.printf("🟢 [Vous] %-15s | Score : %d\n", monNom, etat.currentPlayerScore);
            System.out.printf("🔴 [Adversaire] %-15s | Score : %d\n", etat.opponentPlayerName, etat.opponentPlayerScore);
        } else {
            System.out.printf("🟢 [Actif] %-15s | Score : %d\n", etat.currentPlayerName, etat.currentPlayerScore);
            System.out.printf("🔴 [Vous] %-15s | Score : %d\n", monNom, etat.opponentPlayerScore);
        }

        // Affichage des dés sur le plateau
        System.out.println("\n🎲 Dés sur le plateau : " + (etat.diceOnPlate.isEmpty() ? "Aucun dé" : etat.diceOnPlate));

        // Affichage des dés gardés ce tour-ci
        System.out.println("📥 Dés gardés ce tour-ci : " + (etat.keptDiceThisTurn.isEmpty() ? "Aucun dé" : etat.keptDiceThisTurn));

        // Affichage du score provisoire
        System.out.println("💰 Score temporaire ce tour : " + etat.tempScore);

        System.out.println("==============================================================\n");
    }

}