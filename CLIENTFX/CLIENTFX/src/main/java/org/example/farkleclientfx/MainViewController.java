package org.example.farkleclientfx;

import io.swagger.client.ApiException;
import io.swagger.client.model.RestPlayer;
import io.swagger.client.model.TurnStatusDTO;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.example.farkleclientfx.service.FarkleRestService;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MainViewController {

    @FXML private Label tourLabel, scoreLabel, messageBoxLabel, playerName, playerScore, opponentName, opponentScore;
    @FXML private ImageView playerAvatar, opponentAvatar;
    @FXML private HBox diceBox, keptDiceBox;
    @FXML private Button btnRoll, btnBank, btnKeep, btnQuit;
    @FXML private TableView<Map<String, String>> comboTable;
    @FXML private TableColumn<Map<String, String>, String> colCombo, colPoints;
    @FXML private Label turnUpdateLabel;

    private final FarkleRestService farkleService = new FarkleRestService();
    private Integer myPlayerId = null;
    private String myName = null;
    private TurnStatusDTO dernierEtatRecu = new TurnStatusDTO();
    private ObservableList<Map<String, String>> defaultScoreRules;
    private final List<DieView> diceViewsOnPlate = new ArrayList<>();

    // === AJOUTS POUR LE POLLING AVEC ScheduledThreadPoolExecutor ===
    private ScheduledExecutorService pollingExecutor;
    private ScheduledFuture<?> pollingFuture;
    private final AtomicBoolean isPollingActive = new AtomicBoolean(false);

    @FXML
    private void initialize() {
        setupComboTable();
        btnRoll.setOnAction(e -> handlerRoll());
        btnBank.setOnAction(e -> handlerBank());
        btnKeep.setOnAction(e -> handlerKeep());
        btnQuit.setOnAction(e -> handleQuit());

        // Initialiser le thread pool pour le polling
        pollingExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Farkle-Polling-Thread");
            t.setDaemon(true); // Thread daemon pour ne pas bloquer la fermeture
            return t;
        });

        resetUIForNewGame();
        Platform.runLater(this::inscriptionJoueurEtDebut);

        btnRoll.getStyleClass().add("roll-button");
        loadAvatars();
    }

    private void handleQuit() {
        // Arrêt propre du polling avant de quitter
        stopPolling();
        if (pollingExecutor != null) {
            pollingExecutor.shutdown();
        }
        Platform.exit();
    }

    private void loadAvatars() {
        try {
            Image avatar1 = new Image(getClass().getResource("images/avatar1.png").toExternalForm());
            playerAvatar.setImage(avatar1);
            Image avatar2 = new Image(getClass().getResource("images/avatar_joueur2.png").toExternalForm());
            opponentAvatar.setImage(avatar2);
        } catch (Exception e) {
            System.err.println("Erreur chargement avatars: " + e.getMessage());
        }
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
            System.out.println("[INSCRIPTION] Joueur inscrit : myName = " + myName + ", myPlayerId = " + myPlayerId);
            tourLabel.setText("🏴‍☠️ Bienvenue " + myName + " ! En attente d'un adversaire...");

            // Démarrer le polling après l'inscription
            startPolling();

        } catch (ApiException e) {
            afficherAlerteErreur("Erreur d'Inscription", "Impossible de s'inscrire : " + e.getMessage());
        }
    }

    // === MÉTHODES DE POLLING AVEC ScheduledThreadPoolExecutor ===

    private void startPolling() {
        if (isPollingActive.compareAndSet(false, true)) {
            System.out.println("[POLLING] Démarrage du polling (mode contournement)");

            Runnable pollingTask = () -> {
                try {
                    // ON IGNORE /stateChanged ET ON RÉCUPÈRE TOUJOURS L'ÉTAT COMPLET
                    // C'est la seule façon d'éviter la race condition.
                    TurnStatusDTO newState = farkleService.getEtatCompose();

                    // On met à jour l'UI uniquement si l'état a réellement changé
                    // pour éviter de rafraîchir l'écran inutilement.
                    if (newState != null && !newState.equals(dernierEtatRecu)) {
                        Platform.runLater(() -> {
                            majInterfaceAvecEtat(newState);

                            if ("GAME_OVER".equals(newState.gameState) || isMyTurn(newState)) {
                                stopPolling();
                            }
                        });
                    }
                } catch (Exception e) {
                    System.err.println("[POLL] Erreur, arrêt du polling : " + e.getMessage());
                    Platform.runLater(this::stopPolling);
                }
            };
            pollingFuture = pollingExecutor.scheduleAtFixedRate(pollingTask, 0, 2, TimeUnit.SECONDS);
        }
    }

    private void stopPolling() {
        if (isPollingActive.compareAndSet(true, false)) {
            System.out.println("[POLLING] Arrêt du polling");
            if (pollingFuture != null && !pollingFuture.isCancelled()) {
                pollingFuture.cancel(false);
                pollingFuture = null;
            }
        }
    }

    private void performApiCall(FarkleApiCall apiCall) {
        if (myPlayerId == null || myPlayerId == -1) {
            afficherAlerteErreur("Joueur non initialisé", "Vous ne pouvez pas jouer tant que vous n'êtes pas inscrit.");
            return;
        }

        // Arrêter le polling pendant notre action
        stopPolling();
        desactiverBoutonsPendantAction();

        Task<TurnStatusDTO> task = new Task<>() {
            @Override protected TurnStatusDTO call() throws ApiException {
                return apiCall.call();
            }
        };

        task.setOnSucceeded(event -> {
            TurnStatusDTO dto = task.getValue();
            System.out.println("[ACTION] DTO reçu après action = " + resumeDto(dto));
            majInterfaceAvecEtat(dto);
            if (!"FARKLE_TURN_ENDED".equals(dto.gameState)) {
                if (!isMyTurn(dto) && !"GAME_OVER".equals(dto.gameState)) {
                    pollingExecutor.schedule(() -> startPolling(), 500, TimeUnit.MILLISECONDS);
                }
            }

        });

        task.setOnFailed(event -> {
            String errorMessage = "Erreur du serveur.";
            if (event.getSource().getException() != null) {
                errorMessage = event.getSource().getException().getMessage();
            }
            System.err.println("[ACTION] ERREUR : " + errorMessage);
            afficherAlerteErreur("Erreur d'action", errorMessage);
            updateActionButtons(dernierEtatRecu);

            // Redémarrer le polling même en cas d'erreur
            pollingExecutor.schedule(() -> startPolling(), 500, TimeUnit.MILLISECONDS);
        });

        new Thread(task).start();
    }

    private void majInterfaceAvecEtat(TurnStatusDTO etat) {
        if (etat == null) return;

        final TurnStatusDTO etatFinal = etat;

        // Cas spécial : Si un Farkle se produit, on le gère en priorité
        if ("FARKLE_TURN_ENDED".equals(etat.gameState)) {
            System.out.println("[UI] Événement FARKLE détecté ! Affichage et pause.");
            stopPolling();
            // On mémorise l'état du Farkle MAINTENANT
            this.dernierEtatRecu = etat;

            // On met à jour l'interface pour montrer l'état du Farkle
            messageBoxLabel.setText(etat.immersiveMessage);
            if (etat.diceOnPlate != null) {
                afficherDes(etat.diceOnPlate);
            }
            desactiverBoutonsPendantAction();

            PauseTransition pause = new PauseTransition(Duration.seconds(3.5));
            pause.setOnFinished(e -> {
                System.out.println("[UI] Fin de la pause Farkle, reprise du polling.");
                startPolling();
            });
            pause.play();
            return; // On arrête tout ici.
        }

        // Si on n'est pas dans un Farkle, on continue
        if (etat.availableActions == null || etat.availableActions.isEmpty()) {
            etat = enrichirDtoCoteClient(etat);
        }

        // Détecter si l'adversaire vient de jouer pour afficher le message
        if (!isMyTurn(etat) &&
                dernierEtatRecu.currentPlayerId != -1 &&
                !Objects.equals(dernierEtatRecu.currentPlayerId, etat.currentPlayerId) &&
                etat.currentPlayerName != null) {
            showTurnUpdateMessage(etat.currentPlayerName + " a joué !");
        }

        this.dernierEtatRecu = etat;

        System.out.println("[MAJ UI] DTO = " + resumeDto(etat));
        System.out.println("[MAJ UI] myPlayerId = " + myPlayerId + ", currentPlayerId = " + etat.currentPlayerId +
                ", gameState = " + etat.gameState + ", actions = " + etat.availableActions);

        tourLabel.setText("C'est au tour de : " + (etat.currentPlayerName != null ? etat.currentPlayerName : "En attente..."));

        playerName.setText(myName + " (Vous)");
        if (myPlayerId != null) {
            if (myPlayerId.equals(etat.currentPlayerId)) {
                playerScore.setText(String.valueOf(etat.currentPlayerScore));
                opponentName.setText(etat.opponentPlayerName != null ? etat.opponentPlayerName : "Adversaire");
                opponentScore.setText(String.valueOf(etat.opponentPlayerScore));
            } else {
                playerScore.setText(String.valueOf(etat.opponentPlayerScore));
                opponentName.setText(etat.currentPlayerName != null ? etat.currentPlayerName : "Adversaire");
                opponentScore.setText(String.valueOf(etat.currentPlayerScore));
            }
        }

        scoreLabel.setText("Score du tour : " + etat.tempScore + " 💰");
        updateCentralMessage(etat);
        if (etat.diceOnPlate != null) afficherDes(etat.diceOnPlate);
        if (etat.keptDiceThisTurn != null) afficherDesGardes(etat.keptDiceThisTurn);

        // On met à jour la table des combinaisons avec les indices du serveur.
        if (etat.combinationHints != null && !etat.combinationHints.isEmpty()) {
            comboTable.setItems(FXCollections.observableArrayList(etat.combinationHints));
        } else {
            comboTable.setItems(defaultScoreRules);
        }
        updateActionButtons(etat);
    }
    private TurnStatusDTO enrichirDtoCoteClient(TurnStatusDTO etatPartiel) {
        etatPartiel.availableActions = new ArrayList<>();

        if (etatPartiel.currentPlayerId != -1 && !"GAME_OVER".equals(etatPartiel.gameState)) {

            // Règle CLÉ : Si aucun dé n'a été gardé ET que le score du tour est à zéro,
            // c'est forcément un début de tour (ou une relance après un Farkle).
            // L'action principale est de lancer les dés.
            if (etatPartiel.keptDiceThisTurn.isEmpty() && etatPartiel.tempScore == 0) {
                etatPartiel.availableActions.add("ROLL");
            }
            // Dans tous les autres cas d'un tour en cours...
            else {
                // S'il y a des dés sur le plateau, on peut les sélectionner.
                if (etatPartiel.diceOnPlate != null && !etatPartiel.diceOnPlate.isEmpty()) {
                    etatPartiel.availableActions.add("SELECT_DICE");
                }
                // On peut relancer les dés restants.
                etatPartiel.availableActions.add("ROLL");
                // On peut mettre en banque si on a des points.
                if (etatPartiel.tempScore > 0){
                    etatPartiel.availableActions.add("BANK");
                }
            }
        }

        System.out.println("[CLIENT-LOGIC] Actions déduites pour l'état actuel : " + etatPartiel.availableActions);
        return etatPartiel;
    }
    private void showTurnUpdateMessage(String message) {
        Platform.runLater(() -> {
            if (turnUpdateLabel != null) {
                turnUpdateLabel.setText(message);
                turnUpdateLabel.setVisible(true);

                PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
                pause.setOnFinished(e -> turnUpdateLabel.setVisible(false));
                pause.play();
            }
        });
    }

    // ... [Le reste du code reste identique]

    private void handlerRoll() {
        System.out.println("[UI] Bouton Lancer les dés cliqué.");
        performApiCall(() -> farkleService.lancerDes());
    }

    private void handlerBank() {
        System.out.println("[UI] Bouton Mettre en banque cliqué.");
        performApiCall(() -> farkleService.banker());
    }

    private void handlerKeep() {
        String selection = diceViewsOnPlate.stream().filter(DieView::isSelected).map(d -> String.valueOf(d.getValue())).collect(Collectors.joining(" "));
        System.out.println("[UI] Bouton Garder la sélection cliqué. Selection = '" + selection + "'");
        if (selection.isEmpty()) {
            afficherAlerte("Sélection Vide", "Veuillez sélectionner les dés à garder.");
            return;
        }
        performApiCall(() -> farkleService.selectionnerDes(selection));
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
        System.out.println("[UI] resetUIForNewGame() appelée");
    }

    private void updateActionButtons(TurnStatusDTO etat) {
        if (etat == null || "GAME_OVER".equals(etat.gameState)) {
            desactiverBoutonsPendantAction();
            System.out.println("[UI] Action buttons désactivés (fin de partie ou état null)");
            return;
        }
        List<String> actions = etat.availableActions != null ? etat.availableActions : Collections.emptyList();
        boolean estMonTour = isMyTurn(etat);
        btnRoll.setDisable(!estMonTour || !actions.contains("ROLL"));
        long nbDesSelect = diceViewsOnPlate.stream().filter(DieView::isSelected).count();
        btnBank.setDisable(!estMonTour || !actions.contains("BANK"));
        btnKeep.setDisable(!estMonTour || !actions.contains("SELECT_DICE") || nbDesSelect == 0);
        System.out.println("[UI] updateActionButtons : estMonTour = " + estMonTour + ", actions = " + actions + ", nbDesSelect = " + nbDesSelect);
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

    private void afficherAlerte(String titre, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
            alert.setTitle(titre); alert.setHeaderText(null); alert.showAndWait();
        });
    }

    private void afficherAlerteErreur(String titre, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.setTitle(titre); alert.setHeaderText(null); alert.showAndWait();
        });
    }

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

    private String resumeDto(TurnStatusDTO dto) {
        return "{gameState=" + dto.gameState +
                ", currentPlayerId=" + dto.currentPlayerId +
                ", currentPlayerName=" + dto.currentPlayerName +
                ", opponentPlayerName=" + dto.opponentPlayerName +
                ", actions=" + dto.availableActions +
                ", diceOnPlate=" + dto.diceOnPlate +
                ", keptDiceThisTurn=" + dto.keptDiceThisTurn +
                ", tempScore=" + dto.tempScore +
                "}";
    }
}