// Contenu COMPLET et FINAL pour MainViewController.java, intégrant vos corrections.

package org.example.farkleclientfx;

import io.swagger.client.ApiException;
import io.swagger.client.model.RestPlayer;
import io.swagger.client.model.TurnStatusDTO;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.example.farkleclientfx.service.FarkleRestService;

import java.util.*;
import java.util.stream.Collectors;

public class MainViewController {

    // --- Composants FXML ---
    @FXML private Label tourLabel, scoreLabel, messageBoxLabel, playerName, playerScore, opponentName, opponentScore;
    @FXML private ImageView playerAvatar, opponentAvatar;
    @FXML private HBox diceBox, keptDiceBox;
    @FXML private Button btnRoll, btnBank, btnKeep, btnQuit;
    @FXML private TableView<Map<String, String>> comboTable;
    @FXML private TableColumn<Map<String, String>, String> colCombo, colPoints;
    @FXML private Label turnUpdateLabel;

    // --- Attributs internes ---
    private final FarkleRestService farkleService = new FarkleRestService();
    private Integer myPlayerId = null;
    private String myName = null;
    private ScheduledService<TurnStatusDTO> pollingService;
    private TurnStatusDTO dernierEtatRecu = new TurnStatusDTO();
    private ObservableList<Map<String, String>> defaultScoreRules;
    private final List<DieView> diceViewsOnPlate = new ArrayList<>();

    @FXML
    private void initialize() {
        setupComboTable();
        btnRoll.setOnAction(e -> handlerRoll());
        btnBank.setOnAction(e -> handlerBank());
        btnKeep.setOnAction(e -> handlerKeep());
        btnQuit.setOnAction(e -> handleQuit());

        createPollingService();
        resetUIForNewGame();
        Platform.runLater(this::inscriptionJoueurEtDebut);

        btnRoll.getStyleClass().add("roll-button");
        loadAvatars();
    }

    private void handleNewGame() {
        Alert infoDialog = new Alert(Alert.AlertType.INFORMATION);
        infoDialog.setTitle("Nouvelle Partie");
        infoDialog.setHeaderText("Procédure pour commencer une nouvelle partie");
        infoDialog.setContentText("Pour garantir une réinitialisation complète, veuillez fermer cette application et redémarrer manuellement le serveur.");
        infoDialog.showAndWait();
    }

    private void handleQuit() { Platform.exit(); }

    private void loadAvatars() {
        try {
            Image avatar1 = new Image(getClass().getResource("images/avatar1.png").toExternalForm());
            playerAvatar.setImage(avatar1);
            Image avatar2 = new Image(getClass().getResource("images/avatar_joueur2.png").toExternalForm());
            opponentAvatar.setImage(avatar2);
        } catch (Exception e) { System.err.println("Erreur chargement avatars: " + e.getMessage()); }
    }

    private void inscriptionJoueurEtDebut() {
        resetUIForNewGame();
        TextInputDialog dialog = new TextInputDialog("Pirate");
        dialog.setTitle("Inscription Pirate");
        dialog.setHeaderText("Quel est ton nom de pirate ?");
        dialog.setContentText("Nom :");
        Optional<String> result = dialog.showAndWait();

        if (result.isEmpty() || result.get().trim().isEmpty()) {
            tourLabel.setText("❌ Partie annulée.");
            return;
        }
        myName = result.get().trim();
        try {
            RestPlayer joueur = farkleService.inscrireJoueur(myName);
            myPlayerId = joueur.getId();
            myName = joueur.getName();
            tourLabel.setText("🏴‍☠️ Bienvenue " + myName + " ! En attente d'un adversaire...");
            performApiCall(() -> farkleService.banker());
        } catch (ApiException e) {
            afficherAlerteErreur("Erreur d'Inscription", "Impossible de s'inscrire : " + e.getMessage());
        }
    }

    private void createPollingService() {
        pollingService = new ScheduledService<Void>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        // Appel GET /farkle/stateChanged
                        int hasChanged = farkleService.getStateChanged(); // méthode qui fait le GET vers /farkle/stateChanged
                        if (!isMyTurn(dernierEtatRecu) && hasChanged == 1) {
                            // Rafraîchir l’état complet (affichage spectateur)
                            TurnStatusDTO newEtat = farkleService.getEtat(); // ou utiliser plusieurs endpoints si besoin
                            Platform.runLater(() -> majInterfaceAvecEtat(newEtat));

                            // Afficher le label "X played" pendant 1s
                            Platform.runLater(() -> {
                                String lastPlayer = (dernierEtatRecu.currentPlayerName != null) ? dernierEtatRecu.currentPlayerName : "Adversaire";
                                turnUpdateLabel.setText(lastPlayer + " played");
                                PauseTransition pause = new PauseTransition(Duration.seconds(1));
                                pause.setOnFinished(e -> turnUpdateLabel.setText(""));
                                pause.play();
                            });
                        }
                        // Sinon, silence radio (on ne touche à rien)
                        return null;
                    }
                };
            }
        };
        pollingService.setPeriod(Duration.seconds(2));
    }



    private void performApiCall(FarkleApiCall apiCall) {
        desactiverBoutonsPendantAction();
        stopPolling();

        Task<TurnStatusDTO> task = new Task<>() {
            @Override protected TurnStatusDTO call() throws ApiException { return apiCall.call(); }
        };

        task.setOnSucceeded(event -> {
            TurnStatusDTO dto = task.getValue();
            majInterfaceAvecEtat(dto);
            if (!isMyTurn(dto) && !"GAME_OVER".equals(dto.gameState)) {
                startPolling();
            }
        });

        task.setOnFailed(event -> {
            String errorMessage = "Erreur du serveur.";
            if (event.getSource().getException() != null) {
                errorMessage = event.getSource().getException().getMessage();
            }
            afficherAlerteErreur("Erreur d'action", errorMessage);
            updateActionButtons(dernierEtatRecu);
            startPolling();
        });

        new Thread(task).start();
    }

    private void majInterfaceAvecEtat(TurnStatusDTO etat) {
        if (etat == null) return;
        this.dernierEtatRecu = etat;

        // --- Toujours mettre le joueur local à gauche ---
        if (myPlayerId != null) {
            if (myPlayerId.equals(etat.currentPlayerId)) {
                // C'est mon tour
                playerName.setText((etat.currentPlayerName != null ? etat.currentPlayerName : "Vous") + " (Vous)");
                playerScore.setText(String.valueOf(etat.currentPlayerScore));
                opponentName.setText((etat.opponentPlayerName != null ? etat.opponentPlayerName : "Adversaire"));
                opponentScore.setText(String.valueOf(etat.opponentPlayerScore));
            } else if (myPlayerId.equals(etat.opponentPlayerId)) {
                // Ce n'est pas mon tour, mais je suis l'adversaire dans le DTO
                playerName.setText((etat.opponentPlayerName != null ? etat.opponentPlayerName : "Vous") + " (Vous)");
                playerScore.setText(String.valueOf(etat.opponentPlayerScore));
                opponentName.setText((etat.currentPlayerName != null ? etat.currentPlayerName : "Adversaire"));
                opponentScore.setText(String.valueOf(etat.currentPlayerScore));
            } else {
                playerName.setText("??? (Vous)");
                playerScore.setText("?");
                opponentName.setText("???");
                opponentScore.setText("?");
            }
        }

        tourLabel.setText("C'est au tour de : " + (etat.currentPlayerName != null ? etat.currentPlayerName : "En attente..."));

        scoreLabel.setText("Score du tour : " + etat.tempScore + " 💰");
        updateCentralMessage(etat);
        if (etat.diceOnPlate != null) afficherDes(etat.diceOnPlate);
        if (etat.keptDiceThisTurn != null) afficherDesGardes(etat.keptDiceThisTurn);

        updateActionButtons(etat);
    }


    private void handlerRoll() { performApiCall(() -> farkleService.lancerDes()); }
    private void handlerBank() { performApiCall(() -> farkleService.banker()); }
    private void handlerKeep() {
        String selection = diceViewsOnPlate.stream().filter(DieView::isSelected).map(d -> String.valueOf(d.getValue())).collect(Collectors.joining(" "));
        if (selection.isEmpty()) { afficherAlerte("Sélection Vide", "Veuillez sélectionner les dés à garder."); return; }
        performApiCall(() -> farkleService.selectionnerDes(selection));
    }

    private void startPolling() {
        if (pollingService != null && !pollingService.isRunning()) {
            Platform.runLater(() -> {
                pollingService.reset();
                pollingService.start();
            });
        }
    }

    private void stopPolling() {
        if (pollingService != null && pollingService.isRunning()) {
            pollingService.cancel();
        }
    }

    private void updateCentralMessage(TurnStatusDTO etat) {
        if ("GAME_OVER".equals(etat.gameState)) {
            tourLabel.setText("🎉 PARTIE TERMINÉE 🎉");
            if (etat.winningPlayerName != null && etat.winningPlayerName.equals(myName)) {
                messageBoxLabel.setText("VICTOIRE ! Vous avez gagné !");
            } else {
                messageBoxLabel.setText(etat.winningPlayerName + " a gagné la partie !");
            }
        } else if (etat.immersiveMessage != null && !etat.immersiveMessage.isEmpty()) {
            messageBoxLabel.setText(etat.immersiveMessage);
        } else if (isMyTurn(etat)) {
            messageBoxLabel.setText("À vous de jouer, Capitaine !");
        } else {
            messageBoxLabel.setText("L'adversaire prépare son coup...");
        }
    }

    private void resetUIForNewGame() {
        stopPolling();
        diceBox.getChildren().clear();
        keptDiceBox.getChildren().clear();
        diceViewsOnPlate.clear();
        tourLabel.setText("⚓ Farkle Pirates ⚓");
        messageBoxLabel.setText("Inscrivez-vous pour commencer !");
        playerName.setText("Toi");
        opponentName.setText("Adversaire");
        playerScore.setText("0");
        opponentScore.setText("0");
        scoreLabel.setText("Score du tour : 0");
        this.dernierEtatRecu = new TurnStatusDTO();
        this.dernierEtatRecu.currentPlayerId = -1;
        desactiverBoutonsPendantAction();
    }

    /**
     * Logique de mise à jour des boutons corrigée avec votre suggestion.
     */
    private void updateActionButtons(TurnStatusDTO etat) {
        if (etat == null || "GAME_OVER".equals(etat.gameState)) {
            desactiverBoutonsPendantAction();
            return;
        }

        List<String> actions = etat.availableActions != null ? etat.availableActions : Collections.emptyList();
        boolean estMonTour = isMyTurn(etat);
        long nbDesSelect = diceViewsOnPlate.stream().filter(DieView::isSelected).count();
        btnRoll.setDisable(!estMonTour || (!actions.contains("ROLL") && !actions.contains("CHOOSE_HOT_DICE_ROLL")));
        btnBank.setDisable(!estMonTour || (!actions.contains("BANK") && !actions.contains("CHOOSE_HOT_DICE_BANK")));
        btnKeep.setDisable(!estMonTour || !actions.contains("SELECT_DICE") || nbDesSelect == 0);
    }

    @FunctionalInterface private interface FarkleApiCall { TurnStatusDTO call() throws ApiException; }
    private boolean isMyTurn(TurnStatusDTO etat) { return myPlayerId != null && myPlayerId.equals(etat.currentPlayerId); }
    private void desactiverBoutonsPendantAction() { btnRoll.setDisable(true); btnBank.setDisable(true); btnKeep.setDisable(true); }

    private void afficherDes(List<Integer> valeursDes) {
        diceBox.getChildren().clear();
        diceViewsOnPlate.clear();
        if (valeursDes != null) {
            for (int valeur : valeursDes) {
                DieView die = new DieView(valeur);
                diceBox.getChildren().add(die);
                diceViewsOnPlate.add(die);
            }
        }
    }

    private void afficherDesGardes(List<Integer> keptDice) {
        keptDiceBox.getChildren().clear();
        if (keptDice != null) {
            for (int valeur : keptDice) {
                DieView die = new DieView(valeur);
                die.setDisable(true);
                keptDiceBox.getChildren().add(die);
            }
        }
    }

    private class DieView extends StackPane {
        private final int value; private boolean selected = false; private final Rectangle rect;
        public DieView(int value) {
            this.value = value;
            rect = new Rectangle(48, 48, Color.WHITESMOKE);
            rect.setStroke(Color.BLACK); rect.setArcWidth(10); rect.setArcHeight(10);
            Text txt = new Text(String.valueOf(value));
            txt.setStyle("-fx-font-size: 28; -fx-font-weight: bold;");
            setAlignment(Pos.CENTER); getChildren().addAll(rect, txt);
            setOnMouseClicked(e -> {
                if (isMyTurn(dernierEtatRecu) && dernierEtatRecu.availableActions != null && dernierEtatRecu.availableActions.contains("SELECT_DICE")) {
                    toggleSelection();
                }
            });
        }
        private void toggleSelection() {
            selected = !selected;
            rect.setStroke(selected ? Color.GOLD : Color.BLACK); rect.setStrokeWidth(selected ? 3 : 1);
            rect.setFill(selected ? Color.LIGHTYELLOW : Color.WHITESMOKE);
            updateActionButtons(dernierEtatRecu);
        }
        public int getValue() { return value; }
        public boolean isSelected() { return selected; }
    }

    private void afficherAlerte(String titre, String message) { Platform.runLater(() -> { Alert alert = new Alert(Alert.AlertType.INFORMATION, message); alert.setTitle(titre); alert.setHeaderText(null); alert.showAndWait(); }); }
    private void afficherAlerteErreur(String titre, String message) { Platform.runLater(() -> { Alert alert = new Alert(Alert.AlertType.ERROR, message); alert.setTitle(titre); alert.setHeaderText(null); alert.showAndWait(); }); }

    private void setupComboTable() {
        colCombo.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().get("combo")));
        colPoints.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().get("points")));
        this.defaultScoreRules = FXCollections.observableArrayList(
                createScoreRow("Un [1]", "100"), createScoreRow("Un [5]", "50"),
                createScoreRow("Brelan de [1] (x3)", "1000"), createScoreRow("Brelan de [2] (x3)", "200"),
                createScoreRow("Brelan de [3] (x3)", "300"), createScoreRow("Brelan de [4] (x3)", "400"),
                createScoreRow("Brelan de [5] (x3)", "500"), createScoreRow("Brelan de [6] (x3)", "600"),
                createScoreRow("Quatre identiques (Carré)", "1000"), createScoreRow("Cinq identiques (Quinte)", "2000"),
                createScoreRow("Six identiques (Sextuplé)", "3000"), createScoreRow("Suite 1-6", "2500"),
                createScoreRow("Trois paires", "1500")
        );
        comboTable.setItems(this.defaultScoreRules);
    }
    private Map<String, String> createScoreRow(String combo, String points) {
        Map<String, String> row = new HashMap<>();
        row.put("combo", combo);
        row.put("points", points + " pts");
        return row;
    }
    private void pollGameStateAndRefresh() {
        try {
            int hasChanged = farkleService.getStateChanged(); // Appel GET /farkle/stateChanged
            if (hasChanged == 1) {
                // Appels REST pour tout rafraîchir :
                RestDices dicesPlate = farkleService.getDicesPlate();
                RestDices selectedDices = farkleService.getSelectedDices();
                int tempScore = farkleService.getActualTurnPoints();
                int currentPlayerId = farkleService.getCurrentPlayerID();
                RestPlayer player1 = farkleService.getPlayer(0);
                RestPlayer player2 = farkleService.getPlayer(1);

                RestPlayer myPlayer = (myPlayerId != null && myPlayerId.equals(player1.getId())) ? player1 : player2;
                RestPlayer opponentPlayer = (myPlayerId != null && myPlayerId.equals(player1.getId())) ? player2 : player1;

                Platform.runLater(() -> {
                    tourLabel.setText("C'est au tour de : " + (currentPlayerId == myPlayer.getId() ? myPlayer.getName() : opponentPlayer.getName()));
                    playerName.setText(myPlayer.getName());
                    playerScore.setText(String.valueOf(myPlayer.getScore()));
                    opponentName.setText(opponentPlayer.getName());
                    opponentScore.setText(String.valueOf(opponentPlayer.getScore()));
                    scoreLabel.setText("Score du tour : " + tempScore + " 💰");
                    afficherDes(dicesPlate.getDices());
                    afficherDesGardes(selectedDices.getDices());
                });

                // Affichage "Nom du joueur qui vient de jouer"
                turnUpdateLabel.setText((currentPlayerId == myPlayer.getId() ? opponentPlayer.getName() : myPlayer.getName()) + " played");
                PauseTransition pause = new PauseTransition(Duration.seconds(1));
                pause.setOnFinished(e -> turnUpdateLabel.setText(""));
                pause.play();
            }
        } catch (Exception e) {
            System.err.println("Erreur polling state: " + e.getMessage());
        }
    }

}