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

/**
 * Contrôleur principal de l'interface utilisateur JavaFX pour le client Farkle.
 * Cette classe gère tous les éléments de l'interface (boutons, labels, dés),
 * les actions de l'utilisateur, et la communication avec le serveur via le FarkleRestService.
 */
public class MainViewController {

    // --- ÉLÉMENTS DE L'INTERFACE (liés via @FXML au fichier .fxml) ---
    @FXML private Label tourLabel, scoreLabel, messageBoxLabel, playerName, playerScore, opponentName, opponentScore;
    @FXML private ImageView playerAvatar, opponentAvatar;
    @FXML private HBox diceBox, keptDiceBox;
    @FXML private Button btnRoll, btnBank, btnKeep, btnQuit;
    @FXML private TableView<Map<String, String>> comboTable;
    @FXML private TableColumn<Map<String, String>, String> colCombo, colPoints;
    @FXML private Label turnUpdateLabel;

    // --- ATTRIBUTS D'ÉTAT DU CLIENT ---
    /** Service qui gère la communication avec l'API REST du serveur. */
    private final FarkleRestService farkleService = new FarkleRestService();
    /** L'ID unique de ce client, reçu du serveur après l'inscription. */
    private Integer myPlayerId = null;
    /** Le nom de ce joueur. */
    private String myName = null;
    /** Le dernier état complet du jeu reçu du serveur. Utilisé pour les comparaisons et les mises à jour. */
    private TurnStatusDTO dernierEtatRecu = new TurnStatusDTO();
    /** Une liste statique des règles de score, affichée par défaut. */
    private ObservableList<Map<String, String>> defaultScoreRules;
    /** Une liste des objets graphiques représentant les dés sur le plateau, pour gérer les clics. */
    private final List<DieView> diceViewsOnPlate = new ArrayList<>();

    // --- GESTION DU POLLING ---
    /** Un service qui exécute des tâches en arrière-plan à intervalles réguliers. */
    private ScheduledExecutorService pollingExecutor;
    /** Une référence à la tâche de polling en cours, pour pouvoir l'annuler. */
    private ScheduledFuture<?> pollingFuture;
    /** Un drapeau thread-safe pour savoir si le polling est actif, afin d'éviter de le lancer plusieurs fois. */
    private final AtomicBoolean isPollingActive = new AtomicBoolean(false);

    /**
     * Méthode appelée par JavaFX après le chargement du fichier FXML.
     * C'est ici qu'on initialise les composants et les écouteurs d'événements.
     */
    @FXML
    private void initialize() {
        setupComboTable();
        // Lie chaque bouton à sa méthode de gestion d'événement.
        btnRoll.setOnAction(e -> handlerRoll());
        btnBank.setOnAction(e -> handlerBank());
        btnKeep.setOnAction(e -> handlerKeep());
        btnQuit.setOnAction(e -> handleQuit());

        // Crée le service qui exécutera le polling en arrière-plan.
        pollingExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Farkle-Polling-Thread");
            t.setDaemon(true); // Permet à l'application de se fermer même si ce thread tourne encore.
            return t;
        });

        resetUIForNewGame();
        // Lance le processus d'inscription après que l'interface a eu le temps de s'initialiser.
        Platform.runLater(this::inscriptionJoueurEtDebut);

        btnRoll.getStyleClass().add("roll-button");
        loadAvatars();
    }

    /** Gère la fermeture propre de l'application. */
    private void handleQuit() {
        stopPolling();
        if (pollingExecutor != null) {
            pollingExecutor.shutdown(); // Arrête le service de polling.
        }
        Platform.exit(); // Ferme l'application JavaFX.
    }

    /** Charge les images des avatars des joueurs. */
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

    /**
     * Gère le processus d'inscription du joueur au démarrage.
     * Affiche une boîte de dialogue pour demander le nom, puis contacte le serveur.
     */
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

            // Une fois inscrit, on commence à poller le serveur pour attendre l'adversaire.
            startPolling();

        } catch (ApiException e) {
            afficherAlerteErreur("Erreur d'Inscription", "Impossible de s'inscrire : " + e.getMessage());
        }
    }

    /**
     * Démarre le service de polling s'il n'est pas déjà actif.
     * Le polling interroge le serveur à intervalles réguliers pour obtenir l'état du jeu.
     * C'est ce qui permet au joueur en attente de voir les actions de son adversaire.
     */
    private void startPolling() {
        if (isPollingActive.compareAndSet(false, true)) {
            System.out.println("[POLLING] Démarrage du polling (mode contournement)");

            Runnable pollingTask = () -> {
                try {
                    // Pour éviter la "race condition" de l'API /stateChanged, on récupère toujours l'état complet.
                    TurnStatusDTO newState = farkleService.getEtatCompose();

                    // On ne met à jour l'UI que si l'état a vraiment changé pour éviter les clignotements.
                    if (newState != null && !newState.equals(dernierEtatRecu)) {
                        Platform.runLater(() -> {
                            majInterfaceAvecEtat(newState);

                            // On arrête de poller si c'est notre tour ou si la partie est finie.
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
            // Exécute la tâche toutes les 2 secondes.
            pollingFuture = pollingExecutor.scheduleAtFixedRate(pollingTask, 0, 2, TimeUnit.SECONDS);
        }
    }

    /** Arrête le service de polling s'il est actif. */
    private void stopPolling() {
        if (isPollingActive.compareAndSet(true, false)) {
            System.out.println("[POLLING] Arrêt du polling");
            if (pollingFuture != null && !pollingFuture.isCancelled()) {
                pollingFuture.cancel(false);
                pollingFuture = null;
            }
        }
    }

    /**
     * Méthode générique pour exécuter une action de jeu (appel API).
     * Elle arrête le polling, exécute l'action en arrière-plan, met à jour l'UI avec la réponse,
     * puis redémarre le polling si nécessaire.
     * @param apiCall Une expression lambda représentant l'appel API à exécuter (ex: () -> farkleService.lancerDes()).
     */
    private void performApiCall(FarkleApiCall apiCall) {
        if (myPlayerId == null || myPlayerId == -1) {
            afficherAlerteErreur("Joueur non initialisé", "Vous ne pouvez pas jouer tant que vous n'êtes pas inscrit.");
            return;
        }

        // C'est notre tour, on arrête d'écouter le serveur pour agir.
        stopPolling();
        desactiverBoutonsPendantAction();

        // On utilise un Task JavaFX pour exécuter l'appel réseau en dehors du thread UI.
        Task<TurnStatusDTO> task = new Task<>() {
            @Override protected TurnStatusDTO call() throws ApiException {
                return apiCall.call();
            }
        };

        // Code à exécuter si l'appel API réussit.
        task.setOnSucceeded(event -> {
            TurnStatusDTO dto = task.getValue();
            System.out.println("[ACTION] DTO reçu après action = " + resumeDto(dto));
            majInterfaceAvecEtat(dto);

            // On ne redémarre le polling que si l'action n'a PAS causé de Farkle.
            // Si c'est un Farkle, c'est la PauseTransition qui gérera le redémarrage.
            if (!"FARKLE_TURN_ENDED".equals(dto.gameState)) {
                // Si, après notre action, ce n'est plus notre tour (ex: on a banké), on se remet en écoute.
                if (!isMyTurn(dto) && !"GAME_OVER".equals(dto.gameState)) {
                    pollingExecutor.schedule(() -> startPolling(), 500, TimeUnit.MILLISECONDS);
                }
            }

        });

        // Code à exécuter si l'appel API échoue.
        task.setOnFailed(event -> {
            String errorMessage = "Erreur du serveur.";
            if (event.getSource().getException() != null) {
                errorMessage = event.getSource().getException().getMessage();
            }
            System.err.println("[ACTION] ERREUR : " + errorMessage);
            afficherAlerteErreur("Erreur d'action", errorMessage);
            updateActionButtons(dernierEtatRecu);

            // Redémarre le polling même en cas d'erreur pour ne pas bloquer le client.
            pollingExecutor.schedule(() -> startPolling(), 500, TimeUnit.MILLISECONDS);
        });

        // Lance la tâche sur un nouveau thread.
        new Thread(task).start();
    }

    /**
     * La méthode la plus importante du client. Elle prend un état de jeu (DTO)
     * et met à jour TOUS les composants de l'interface pour refléter cet état.
     * @param etat Le TurnStatusDTO reçu du serveur.
     */
    private void majInterfaceAvecEtat(TurnStatusDTO etat) {
        if (etat == null) return;
        final TurnStatusDTO etatFinal = etat;

        // Cas spécial prioritaire : Si un Farkle se produit, on l'affiche et on fait une pause.
        if ("FARKLE_TURN_ENDED".equals(etat.gameState)) {
            System.out.println("[UI] Événement FARKLE détecté ! Affichage et pause.");
            stopPolling();
            this.dernierEtatRecu = etat;

            // On met à jour l'UI pour montrer le résultat du Farkle.
            messageBoxLabel.setText(etat.immersiveMessage);
            if (etat.diceOnPlate != null) {
                afficherDes(etat.diceOnPlate);
            }
            desactiverBoutonsPendantAction();

            // On crée une pause pour laisser le temps au joueur de voir le Farkle.
            PauseTransition pause = new PauseTransition(Duration.seconds(3.5));
            pause.setOnFinished(e -> {
                System.out.println("[UI] Fin de la pause Farkle, reprise du polling.");
                startPolling(); // À la fin, on relance le polling pour attendre l'adversaire.
            });
            pause.play();
            return; // On arrête l'exécution ici pour ne pas écraser l'affichage du Farkle.
        }

        // Si le DTO vient du polling, il est incomplet. On "devine" les actions possibles.
        if (etat.availableActions == null || etat.availableActions.isEmpty()) {
            etat = enrichirDtoCoteClient(etat);
        }

        // Affiche le message temporaire si l'adversaire vient de jouer.
        if (!isMyTurn(etat) &&
                dernierEtatRecu.currentPlayerId != -1 &&
                !Objects.equals(dernierEtatRecu.currentPlayerId, etat.currentPlayerId) &&
                etat.currentPlayerName != null) {
            showTurnUpdateMessage(etat.currentPlayerName + " a joué !");
        }

        this.dernierEtatRecu = etat; // On sauvegarde le nouvel état.

        // Logs de débogage pour suivre l'état reçu.
        System.out.println("[MAJ UI] DTO = " + resumeDto(etat));
        System.out.println("[MAJ UI] myPlayerId = " + myPlayerId + ", currentPlayerId = " + etat.currentPlayerId +
                ", gameState = " + etat.gameState + ", actions = " + etat.availableActions);

        // Mise à jour de tous les labels de l'interface.
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

        // Affiche le message central, les dés sur le plateau et les dés gardés.
        updateCentralMessage(etat);
        if (etat.diceOnPlate != null) afficherDes(etat.diceOnPlate);
        if (etat.keptDiceThisTurn != null) afficherDesGardes(etat.keptDiceThisTurn);

        // Met à jour la table des combinaisons (soit les indices, soit les règles par défaut).
        if (etat.combinationHints != null && !etat.combinationHints.isEmpty()) {
            comboTable.setItems(FXCollections.observableArrayList(etat.combinationHints));
        } else {
            comboTable.setItems(defaultScoreRules);
        }

        // Met à jour l'état (activé/désactivé) des boutons d'action.
        updateActionButtons(etat);
    }

    /**
     * "Devine" les actions possibles quand le serveur ne les fournit pas (via le polling).
     * C'est la solution de contournement pour l'API incomplète.
     * @param etatPartiel Le DTO incomplet reçu.
     * @return Le DTO complété avec une liste d'actions probables.
     */
    private TurnStatusDTO enrichirDtoCoteClient(TurnStatusDTO etatPartiel) {
        etatPartiel.availableActions = new ArrayList<>();

        if (etatPartiel.currentPlayerId != -1 && !"GAME_OVER".equals(etatPartiel.gameState)) {
            // Règle CLÉ : Si aucun dé n'a été gardé ET que le score du tour est à zéro,
            // c'est forcément un début de tour. L'action principale est de lancer les dés.
            if (etatPartiel.keptDiceThisTurn.isEmpty() && etatPartiel.tempScore == 0) {
                etatPartiel.availableActions.add("ROLL");
            }
            // Dans tous les autres cas d'un tour en cours...
            else {
                if (etatPartiel.diceOnPlate != null && !etatPartiel.diceOnPlate.isEmpty()) {
                    etatPartiel.availableActions.add("SELECT_DICE");
                }
                etatPartiel.availableActions.add("ROLL");
                if (etatPartiel.tempScore > 0){
                    etatPartiel.availableActions.add("BANK");
                }
            }
        }
        System.out.println("[CLIENT-LOGIC] Actions déduites pour l'état actuel : " + etatPartiel.availableActions);
        return etatPartiel;
    }

    /** Affiche un message temporaire à l'écran (ex: "X a joué !"). */
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

    /** Gestionnaire pour le clic sur le bouton "Lancer les dés". */
    private void handlerRoll() {
        System.out.println("[UI] Bouton Lancer les dés cliqué.");
        performApiCall(() -> farkleService.lancerDes());
    }

    /** Gestionnaire pour le clic sur le bouton "Mettre en banque". */
    private void handlerBank() {
        System.out.println("[UI] Bouton Mettre en banque cliqué.");
        performApiCall(() -> farkleService.banker());
    }

    /** Gestionnaire pour le clic sur le bouton "Garder la sélection". */
    private void handlerKeep() {
        String selection = diceViewsOnPlate.stream().filter(DieView::isSelected).map(d -> String.valueOf(d.getValue())).collect(Collectors.joining(" "));
        System.out.println("[UI] Bouton Garder la sélection cliqué. Selection = '" + selection + "'");
        if (selection.isEmpty()) {
            afficherAlerte("Sélection Vide", "Veuillez sélectionner les dés à garder.");
            return;
        }
        performApiCall(() -> farkleService.selectionnerDes(selection));
    }

    /** Met à jour le message central en fonction de l'état du jeu. */
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

    /** Réinitialise l'interface à son état de départ. */
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

    /** Active/désactive les boutons en fonction des actions autorisées par le serveur. */
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

    /** Interface fonctionnelle pour passer les appels API en tant que lambdas. */
    @FunctionalInterface private interface FarkleApiCall { TurnStatusDTO call() throws ApiException; }

    /** Vérifie si c'est le tour de ce client. */
    private boolean isMyTurn(TurnStatusDTO etat) { return myPlayerId != null && myPlayerId.equals(etat.currentPlayerId); }

    /** Désactive tous les boutons, typiquement pendant une action ou une attente. */
    private void desactiverBoutonsPendantAction() { btnRoll.setDisable(true); btnBank.setDisable(true); btnKeep.setDisable(true); }

    /** Affiche les dés sur le plateau de jeu. */
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

    /** Affiche les dés mis de côté par le joueur. */
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

    /** Classe interne représentant un dé cliquable dans l'interface. */
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

    /** Affiche une boîte de dialogue d'information standard. */
    private void afficherAlerte(String titre, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
            alert.setTitle(titre); alert.setHeaderText(null); alert.showAndWait();
        });
    }

    /** Affiche une boîte de dialogue d'erreur standard. */
    private void afficherAlerteErreur(String titre, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.setTitle(titre); alert.setHeaderText(null); alert.showAndWait();
        });
    }

    /** Initialise la table des scores avec la liste statique de toutes les règles. */
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

    /** Crée une ligne (Map) pour la table des scores. */
    private Map<String, String> createScoreRow(String combo, String points) {
        Map<String, String> row = new HashMap<>();
        row.put("combo", combo);
        row.put("points", points + " pts");
        return row;
    }

    /** Crée une représentation textuelle concise du DTO pour les logs. */
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