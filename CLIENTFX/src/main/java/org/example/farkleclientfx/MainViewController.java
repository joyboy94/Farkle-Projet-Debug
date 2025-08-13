package org.example.farkleclientfx;

import io.swagger.client.ApiException;
import io.swagger.client.model.RestPlayer;
import io.swagger.client.model.TurnStatusDTO;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
 * Contrôleur principal JavaFX du client “Farkle Pirates”.
 *
 * Principes :
 *  • On interroge le serveur via /farkle/stateChanged (0/1) toutes les secondes.
 *    Si le flag vaut 1 ou si on a trop de “0” d’affilée, on récupère l’état complet via /farkle/state.
 *  • Toutes les écritures UI se font sur le JavaFX Application Thread (Platform.runLater).
 *  • Pendant une action (ROLL / KEEP / BANK), on stoppe le polling pour éviter les collisions réseau,
 *    puis on le relance si nécessaire.
 *  • Fallback client : si le serveur n’envoie pas availableActions, on déduit les actions activables
 *    depuis l’état (dés sur le plateau, score temporaire, etc.).
 *  • UX : un toast “<nom> a joué !” s’affiche ~1,5s quand le tour change.
 *
 * Logging :
 *  • Uniquement des System.out.println pour produire des traces lisibles par n’importe qui.
 */
public class MainViewController {

    /* ============================
       == RÉFÉRENCES UI (FXML)  ==
       ============================ */

    @FXML private Label tourLabel, scoreLabel, messageBoxLabel, playerName, playerScore, opponentName, opponentScore;
    @FXML private ImageView playerAvatar, opponentAvatar;
    @FXML private HBox diceBox, keptDiceBox;
    @FXML private Button btnRoll, btnBank, btnKeep, btnQuit;
    @FXML private TableView<Map<String, String>> comboTable;
    @FXML private TableColumn<Map<String, String>, String> colCombo, colPoints;
    @FXML private Label turnUpdateLabel; // toast “X a joué !”

    /* ============================
       ====== ÉTAT / SERVICE ======
       ============================ */

    private final FarkleRestService farkleService = new FarkleRestService(); // client REST

    private Integer myPlayerId = null;  // id du joueur local (fourni par le serveur)
    private String myName = null;       // nom saisi (éventuellement normalisé côté serveur)

    // Dernier état reçu du serveur. Sert aussi de “cache” pour piloter l’UI.
    private TurnStatusDTO dernierEtatRecu = new TurnStatusDTO();

    // Table des combinaisons (valeurs par défaut affichées si le serveur ne fournit rien)
    private ObservableList<Map<String, String>> defaultScoreRules;

    // Représentations graphiques des dés actuellement “sur le plateau”
    private final List<DieView> diceViewsOnPlate = new ArrayList<>();

    /* ============================
       ========= POLLING ==========
       ============================ */

    private ScheduledExecutorService pollingExecutor;     // thread planifié (daemon)
    private ScheduledFuture<?> pollingFuture;             // tâche courante
    private final AtomicBoolean isPollingActive = new AtomicBoolean(false);

    // Filets de sécurité contre une éventuelle désynchronisation
    private int  zerosInARow       = 0;        // nombre de 0 consécutifs sur /stateChanged
    private long lastFullFetchMs   = 0L;       // date du dernier /state

    private static final int  FORCE_RESYNC_AFTER_ZEROS = 3;     // après 3 “0”, on force un /state
    private static final long FORCE_RESYNC_EVERY_MS    = 5000L; // ou bien toutes les 5 secondes

    /* ============================
       ========= AVATARS ==========
       ============================ */

    private Image avatarP0, avatarP1; // 0 → avatar1, 1 → avatar2 (mapping stable par ID)

    /** Charge simplement les images en mémoire (ne les applique pas encore aux ImageView). */
    private void loadAvatars() {
        try {
            avatarP0 = new Image(getClass().getResource("images/avatar1.png").toExternalForm());
            avatarP1 = new Image(getClass().getResource("images/avatar_joueur2.png").toExternalForm());
            System.out.println("[AVATAR] Ressources chargées.");
        } catch (Exception e) {
            System.out.println("[AVATAR] Erreur de chargement : " + e.getMessage());
        }
    }

    /** Choisit l’avatar à afficher pour un ID de joueur. */
    private Image imageForPlayer(Integer id) {
        if (id == null) return avatarP0;        // valeur par défaut
        return (id == 0) ? avatarP0 : avatarP1; // mapping simple et déterministe
    }

    /* ============================
       ======== INITIALISATION ====
       ============================ */

    @FXML
    private void initialize() {
        System.out.println("[INIT] MainViewController – démarrage");
        setupComboTable();        // colonnes + valeurs par défaut
        setupButtonHandlers();    // actions des boutons
        setupPollingExecutor();   // thread daemon pour le polling
        resetUIForNewGame();      // état neutre (pas inscrit)
        Platform.runLater(this::inscriptionJoueurEtDebut); // prompt “Pirate” quand la scène est prête
        loadAvatars();            // charge les ressources images
    }

    /** Déclare les actions des boutons. */
    private void setupButtonHandlers() {
        btnRoll.setOnAction(e -> handlerRoll());
        btnBank.setOnAction(e -> handlerBank());
        btnKeep.setOnAction(e -> handlerKeep());
        btnQuit.setOnAction(e -> handleQuit());
        btnRoll.getStyleClass().add("roll-button");
        System.out.println("[INIT] Handlers configurés.");
    }

    /** Crée l’executor de polling (un seul thread, daemon). */
    private void setupPollingExecutor() {
        pollingExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Farkle-Polling-Thread");
            t.setDaemon(true);
            return t;
        });
        System.out.println("[INIT] Executor de polling prêt.");
    }

    /** Nettoyage global (arrête le polling et ferme l’appli). */
    private void handleQuit() {
        System.out.println("[QUIT] Fermeture demandée.");
        stopPolling();
        if (pollingExecutor != null) pollingExecutor.shutdown();
        Platform.exit();
    }

    /* ============================
       ===== INSCRIPTION / GO =====
       ============================ */

    /**
     * Demande un nom, s’inscrit via /join, tente un état initial, puis démarre la boucle de polling.
     */
    private void inscriptionJoueurEtDebut() {
        resetUIForNewGame();

        // Boîte de dialogue pour le nom
        TextInputDialog dialog = new TextInputDialog("Pirate");
        dialog.setTitle("Inscription Pirate");
        dialog.setHeaderText("Quel est ton nom de pirate ?");
        dialog.setContentText("Nom :");
        Optional<String> result = dialog.showAndWait();

        if (result.isEmpty() || result.get().trim().isEmpty()) {
            tourLabel.setText("❌ Partie annulée.");
            System.out.println("[INSCRIPTION] Abandon : aucun nom saisi.");
            return;
        }

        // Appel /join
        myName = result.get().trim();
        try {
            RestPlayer joueur = farkleService.inscrireJoueur(myName);
            myPlayerId = joueur.getId();
            myName     = joueur.getName(); // si le serveur a normalisé
            System.out.println("[INSCRIPTION] OK => " + myName + " (ID=" + myPlayerId + ")");
            tourLabel.setText("🏴‍☠️ Bienvenue " + myName + " ! En attente d'un adversaire...");

            // Essai de bootstrap d’état
            try {
                TurnStatusDTO etatInitial = farkleService.getEtatCompose();
                System.out.println("[INIT] État initial : " + resumeDto(etatInitial));
                majInterfaceAvecEtat(etatInitial);
            } catch (Exception ex) {
                System.out.println("[INIT] Aucun état initial disponible.");
            }

            startPolling(); // on lance la boucle

        } catch (ApiException e) {
            System.out.println("[INSCRIPTION] Échec : " + e.getMessage());
            afficherAlerteErreur("Erreur d'Inscription", "Impossible de s'inscrire : " + e.getMessage());
        }
    }

    /* ============================
       ========= POLLING ==========
       ============================ */

    /** Démarre la boucle si elle n’est pas déjà active. */
    private void startPolling() {
        if (!isPollingActive.compareAndSet(false, true)) {
            System.out.println("[POLL] Déjà actif.");
            return;
        }
        System.out.println("[POLL] Démarrage.");

        Runnable pollingTask = () -> {
            try {
                // 1) /stateChanged => 0/1 consommable
                Integer flag = farkleService.getStateChanged();
                boolean changed = (flag != null && flag == 1);

                if (changed) zerosInARow = 0; else zerosInARow++;

                // 2) Force un /state périodiquement ou après n zéros
                boolean tooOld    = (System.currentTimeMillis() - lastFullFetchMs) > FORCE_RESYNC_EVERY_MS;
                boolean forceSync = (zerosInARow >= FORCE_RESYNC_AFTER_ZEROS) || tooOld;

                // 3) Fait un /state si nécessaire
                if (changed || forceSync || dernierEtatRecu.currentPlayerId == -1) {
                    if (!changed && forceSync) {
                        System.out.println("[POLL] Force re-sync (zeros=" + zerosInARow + ", tooOld=" + tooOld + ")");
                    }
                    TurnStatusDTO newState = farkleService.getEtatCompose();
                    lastFullFetchMs = System.currentTimeMillis();
                    System.out.println("[POLL] Nouvel état : " + resumeDto(newState));

                    // 4) Toute MAJ UI → Application Thread
                    Platform.runLater(() -> {
                        majInterfaceAvecEtat(newState);
                        if ("GAME_OVER".equals(newState.gameState)) {
                            System.out.println("[POLL] Partie terminée -> arrêt polling.");
                            stopPolling();
                        }
                    });
                }
            } catch (Exception e) {
                System.out.println("[POLL] Erreur : " + e.getMessage());
            }
        };

        // périodicité : 1 seconde
        pollingFuture = pollingExecutor.scheduleAtFixedRate(pollingTask, 0, 1000, TimeUnit.MILLISECONDS);
    }

    /** Arrête la boucle si active. */
    private void stopPolling() {
        if (isPollingActive.compareAndSet(true, false)) {
            System.out.println("[POLL] Arrêt.");
            if (pollingFuture != null && !pollingFuture.isCancelled()) {
                pollingFuture.cancel(false);
                pollingFuture = null;
            }
        }
    }

    /* ============================
       ========= ACTIONS ==========
       ============================ */

    private void handlerRoll() {
        System.out.println("[ACTION] ROLL");
        performApiCall(farkleService::lancerDes);
    }

    private void handlerBank() {
        System.out.println("[ACTION] BANK");
        performApiCall(farkleService::banker);
    }

    private void handlerKeep() {
        // Construit la chaîne attendue par l’API depuis les dés sélectionnés
        String selection = diceViewsOnPlate.stream()
                .filter(DieView::isSelected)
                .map(d -> String.valueOf(d.getValue()))
                .collect(Collectors.joining(" "));
        System.out.println("[ACTION] KEEP selection='" + selection + "'");

        if (selection.isEmpty()) {
            afficherAlerte("Sélection Vide", "Veuillez sélectionner les dés à garder.");
            return;
        }
        performApiCall(() -> farkleService.selectionnerDes(selection));
    }

    /**
     * Exécute une action REST dans un thread de fond, désactive les boutons,
     * stoppe temporairement le polling (évite les collisions /state) et relance si besoin.
     */
    private void performApiCall(FarkleApiCall apiCall) {
        if (myPlayerId == null || myPlayerId == -1) {
            afficherAlerteErreur("Non inscrit", "Inscrivez-vous d'abord.");
            return;
        }

        stopPolling();                 // on se met au calme
        desactiverBoutonsPendantAction();

        new Thread(() -> {
            try {
                TurnStatusDTO dto = apiCall.call();
                System.out.println("[ACTION] DTO: " + resumeDto(dto));

                Platform.runLater(() -> {
                    majInterfaceAvecEtat(dto);

                    // Si ce n’est pas un FARKLE (géré avec une pause), on relance le polling
                    // quand le tour passe à l’adversaire.
                    if (!"FARKLE_TURN_ENDED".equals(dto.gameState)) {
                        if (!isMyTurn(dto) && !"GAME_OVER".equals(dto.gameState)) {
                            System.out.println("[ACTION] Tour adverse → relance polling.");
                            pollingExecutor.schedule(this::startPolling, 500, TimeUnit.MILLISECONDS);
                        }
                    }
                });
            } catch (ApiException e) {
                System.out.println("[ACTION] Erreur REST: " + e.getMessage());
                Platform.runLater(() -> {
                    afficherAlerteErreur("Erreur", e.getMessage());
                    updateActionButtons(dernierEtatRecu);
                    pollingExecutor.schedule(this::startPolling, 500, TimeUnit.MILLISECONDS);
                });
            }
        }, "Farkle-Action-Thread").start();
    }

    /* ============================
       ====== MISE À JOUR UI ======
       ============================ */

    /**
     * Applique un état du serveur sur l’interface.
     * Gère explicitement le cas FARKLE avec une pause de 3,5 s.
     */
    private void majInterfaceAvecEtat(TurnStatusDTO etat) {
        if (etat == null) {
            System.out.println("[UI] majInterfaceAvecEtat(null)");
            return;
        }
        System.out.println("[UI] Mise à jour avec: " + resumeDto(etat));

        // 1) Cas FARKLE : on affiche le message immersif puis on relance le polling après la pause
        if ("FARKLE_TURN_ENDED".equals(etat.gameState)) {
            this.dernierEtatRecu = etat;
            messageBoxLabel.setText(etat.immersiveMessage);
            if (etat.diceOnPlate != null) afficherDes(etat.diceOnPlate);
            desactiverBoutonsPendantAction();

            System.out.println("[UI] FARKLE détecté, pause 3.5s.");
            PauseTransition pause = new PauseTransition(Duration.seconds(3.5));
            pause.setOnFinished(e -> startPolling());
            pause.play();
            return;
        }

        // 2) Toast “X a joué !” si le tour a changé
        boolean turnChanged =
                (dernierEtatRecu != null &&
                        dernierEtatRecu.currentPlayerId >= 0 &&
                        etat.currentPlayerId          >= 0 &&
                        !Objects.equals(etat.currentPlayerId, dernierEtatRecu.currentPlayerId));

        if (turnChanged) {
            String previousPlayer =
                    (dernierEtatRecu.currentPlayerName != null && !dernierEtatRecu.currentPlayerName.isEmpty())
                            ? dernierEtatRecu.currentPlayerName
                            : "L'adversaire";
            System.out.println("[UI] Changement de tour → " + previousPlayer + " a joué !");
            showTurnUpdateMessage(previousPlayer + " a joué !");
        }

        // 3) On remplace notre “cache” local par l’état courant
        this.dernierEtatRecu = etat;

        // 4) Bandeau du haut
        if (etat.currentPlayerId == -1) {
            tourLabel.setText("En attente du second joueur...");
        } else {
            tourLabel.setText("C'est au tour de : " +
                    (etat.currentPlayerName != null ? etat.currentPlayerName : "..."));
        }

        // 5) Panneaux joueurs, message central, dés et combinaisons
        updatePlayerPanels(etat);
        scoreLabel.setText("Score du tour : " + etat.tempScore + " 💰");
        updateCentralMessage(etat);
        updateDiceDisplay(etat);

        if (etat.combinationHints != null && !etat.combinationHints.isEmpty()) {
            comboTable.setItems(FXCollections.observableArrayList(etat.combinationHints));
        } else {
            comboTable.setItems(defaultScoreRules);
        }

        // 6) Boutons d’action (avec fallback si necessary)
        updateActionButtons(etat);
    }

    /**
     * Calcule les actions activables et active/désactive les boutons.
     * Fallback si availableActions est vide côté serveur.
     */
    private void updateActionButtons(TurnStatusDTO etat) {
        if (etat == null || "GAME_OVER".equals(etat.gameState)) {
            desactiverBoutonsPendantAction();
            System.out.println("[BUTTONS] Désactivés (fin de partie ou état null).");
            return;
        }

        boolean estMonTour = isMyTurn(etat);
        System.out.println("[BUTTONS] estMonTour=" + estMonTour +
                ", currentPlayerId=" + etat.currentPlayerId +
                ", myPlayerId=" + myPlayerId);

        if (!estMonTour) {
            desactiverBoutonsPendantAction();
            System.out.println("[BUTTONS] Désactivés (tour adverse).");
            return;
        }

        List<String> actions = etat.availableActions;

        // Fallback “intelligent”
        if (actions == null || actions.isEmpty()) {
            actions = new ArrayList<>();

            boolean hasKept = etat.keptDiceThisTurn != null && !etat.keptDiceThisTurn.isEmpty();
            boolean hasDice = etat.diceOnPlate       != null && !etat.diceOnPlate.isEmpty();

            // Début de tour (rien gardé, rien sur le plateau, score 0)
            if (!hasKept && !hasDice && etat.tempScore == 0) {
                actions.add("ROLL");
            }
            // Après un lancer : dés sur le plateau
            if (hasDice) {
                actions.add("SELECT_DICE");
                if (etat.tempScore > 0) actions.add("BANK");
            }
            // Hot dice : plus de dés sur le plateau mais un score de tour > 0
            if (!hasDice && etat.tempScore > 0) {
                actions.add("ROLL");
                actions.add("BANK");
            }
        }

        // Activation effective
        btnRoll.setDisable(!actions.contains("ROLL"));
        btnBank.setDisable(!actions.contains("BANK"));
        long nbSelected = diceViewsOnPlate.stream().filter(DieView::isSelected).count();
        btnKeep.setDisable(!actions.contains("SELECT_DICE") || nbSelected == 0);

        System.out.println("[BUTTONS] Actions=" + actions +
                " -> roll=" + !btnRoll.isDisabled() +
                ", keep=" + !btnKeep.isDisabled() +
                ", bank=" + !btnBank.isDisabled());
    }

    /** Texte central (message immersif, fin de partie, etc.). */
    private void updateCentralMessage(TurnStatusDTO etat) {
        if ("GAME_OVER".equals(etat.gameState)) {
            tourLabel.setText("🎉 PARTIE TERMINÉE 🎉");
            if (etat.winningPlayerName != null && etat.winningPlayerName.equals(myName)) {
                messageBoxLabel.setText("VICTOIRE ! Vous avez gagné !");
            } else if (etat.winningPlayerName != null) {
                messageBoxLabel.setText(etat.winningPlayerName + " a gagné la partie !");
            } else {
                messageBoxLabel.setText("Partie terminée.");
            }
        } else if (etat.immersiveMessage != null && !etat.immersiveMessage.isEmpty()) {
            messageBoxLabel.setText(etat.immersiveMessage);
        } else if (isMyTurn(etat)) {
            messageBoxLabel.setText("À vous de jouer, Capitaine !");
        } else {
            messageBoxLabel.setText("L'adversaire prépare son coup...");
        }
    }

    /**
     * Met à jour noms/scores/avatars des deux panneaux.
     * Les avatars sont stables par ID (0 → avatarP0, 1 → avatarP1).
     */
    private void updatePlayerPanels(TurnStatusDTO etat) {
        // Panneau du joueur local
        playerName.setText(myName != null ? myName + " (Vous)" : "Vous");

        // Récupère le score à jour auprès du serveur (si ça échoue, on garde l’affiché)
        if (myPlayerId != null) {
            try {
                RestPlayer me = farkleService.getRestPlayer(myPlayerId);
                if (me != null) playerScore.setText(String.valueOf(me.getScore()));
            } catch (Exception ignored) {}
        }

        // Détermine “qui est l’adversaire” à partir de l’état
        Integer oppId = null;
        String oppName = "Adversaire";
        String oppScore = "0";

        if (etat != null) {
            if (Objects.equals(myPlayerId, etat.currentPlayerId)) {
                oppId = etat.opponentPlayerId;
                if (etat.opponentPlayerName != null && !etat.opponentPlayerName.isBlank())
                    oppName = etat.opponentPlayerName;
                oppScore = String.valueOf(etat.opponentPlayerScore);
            } else {
                oppId = etat.currentPlayerId;
                if (etat.currentPlayerName != null && !etat.currentPlayerName.isBlank())
                    oppName = etat.currentPlayerName;
                oppScore = String.valueOf(etat.currentPlayerScore);
            }
        }

        opponentName.setText(oppName);
        opponentScore.setText(oppScore);

        // Applique les bons avatars
        playerAvatar.setImage(imageForPlayer(myPlayerId));
        opponentAvatar.setImage(imageForPlayer(oppId));
    }

    /** Reconstruit l’affichage des dés “sur le plateau” + la zone des dés gardés. */
    private void updateDiceDisplay(TurnStatusDTO etat) {
        if (etat.diceOnPlate != null) {
            afficherDes(etat.diceOnPlate);
        } else {
            afficherDes(Collections.emptyList());
        }

        if (etat.keptDiceThisTurn != null) {
            afficherDesGardes(etat.keptDiceThisTurn);
        } else {
            afficherDesGardes(Collections.emptyList());
        }
    }

    /** Affiche un toast centré “<nom> a joué !” pendant ~1,5s. */
    private void showTurnUpdateMessage(String message) {
        Platform.runLater(() -> {
            if (turnUpdateLabel != null) {
                turnUpdateLabel.setText(message);
                turnUpdateLabel.setVisible(true);
                // Astuce FXML : mettre mouseTransparent="true" pour ne pas bloquer les clics.
                PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
                pause.setOnFinished(e -> turnUpdateLabel.setVisible(false));
                pause.play();
            }
        });
    }

    /* ============================
       ======= HELPERS UI DÉS =====
       ============================ */

    /** Construit la rangée des dés “sur le plateau” (cliquables). */
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

    /** Construit la rangée des dés “gardés” (désactivés). */
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

    /** Désactive tous les boutons d’action (utile pendant une requête). */
    private void desactiverBoutonsPendantAction() {
        btnRoll.setDisable(true);
        btnBank.setDisable(true);
        btnKeep.setDisable(true);
    }

    /** Indique si, selon l’état serveur, c’est notre tour. */
    private boolean isMyTurn(TurnStatusDTO etat) {
        return myPlayerId != null && etat != null && Objects.equals(myPlayerId, etat.currentPlayerId);
    }

    /* ============================
       == TABLE DES COMBINAISONS ==
       ============================ */

    /** Configure la table et charge les rangées par défaut. */
    private void setupComboTable() {
        colCombo.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().get("combo")));
        colPoints.setCellValueFactory(data ->
                new javafx.beans.property.SimpleStringProperty(data.getValue().get("points")));

        this.defaultScoreRules = FXCollections.observableArrayList(
                createScoreRow("Un [1]", "100"),
                createScoreRow("Un [5]", "50"),
                createScoreRow("Brelan de [1] (x3)", "1000"),
                createScoreRow("Brelan de [2] (x3)", "200"),
                createScoreRow("Brelan de [3] (x3)", "300"),
                createScoreRow("Brelan de [4] (x3)", "400"),
                createScoreRow("Brelan de [5] (x3)", "500"),
                createScoreRow("Brelan de [6] (x3)", "600"),
                createScoreRow("Quatre identiques", "1000"),
                createScoreRow("Cinq identiques", "2000"),
                createScoreRow("Six identiques", "3000"),
                createScoreRow("Suite 1-6", "2500"),
                createScoreRow("Trois paires", "1500")
        );
        comboTable.setItems(this.defaultScoreRules);
        System.out.println("[INIT] Table des combinaisons prête.");
    }

    private Map<String, String> createScoreRow(String combo, String points) {
        Map<String, String> row = new HashMap<>();
        row.put("combo", combo);
        row.put("points", points + " pts");
        return row;
    }

    /** Réinitialise complètement l’UI pour une nouvelle partie (avant inscription). */
    private void resetUIForNewGame() {
        stopPolling();

        diceBox.getChildren().clear();
        keptDiceBox.getChildren().clear();
        diceViewsOnPlate.clear();

        tourLabel.setText("⚓ Farkle Pirates ⚓");
        messageBoxLabel.setText("Inscrivez-vous pour commencer !");
        scoreLabel.setText("Score du tour : 0");

        playerName.setText("Vous");
        opponentName.setText("Adversaire");
        playerScore.setText("0");
        opponentScore.setText("0");

        if (turnUpdateLabel != null) turnUpdateLabel.setVisible(false);

        this.dernierEtatRecu = new TurnStatusDTO();
        this.dernierEtatRecu.currentPlayerId = -1;

        desactiverBoutonsPendantAction();
        System.out.println("[UI] Reset complet.");
    }

    /* ============================
       ========= UTILITÉS =========
       ============================ */

    private void afficherAlerte(String titre, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
            alert.setTitle(titre);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
    }

    private void afficherAlerteErreur(String titre, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, message);
            alert.setTitle(titre);
            alert.setHeaderText(null);
            alert.showAndWait();
        });
    }

    @FunctionalInterface
    private interface FarkleApiCall {
        TurnStatusDTO call() throws ApiException;
    }

    /** Résumé compact d’un DTO pour logs lisibles. */
    private String resumeDto(TurnStatusDTO dto) {
        if (dto == null) return "null";
        return String.format("{state=%s, curId=%d, curName=%s, temp=%d, dice=%s, kept=%s, actions=%s}",
                dto.gameState, dto.currentPlayerId, dto.currentPlayerName,
                dto.tempScore, dto.diceOnPlate, dto.keptDiceThisTurn, dto.availableActions);
    }

    /* ============================
       ====== VUE D’UN DÉ =========
       ============================ */

    /**
     * Petit composant graphique pour afficher un dé.
     * – Cliquable si on a le droit de sélectionner des dés.
     * – Notifie updateActionButtons après chaque toggle pour (dés)activer “Garder la sélection”.
     */
    private class DieView extends StackPane {
        private final int value;
        private boolean selected = false;
        private final Rectangle rect;

        public DieView(int value) {
            this.value = value;

            rect = new Rectangle(48, 48, Color.WHITESMOKE);
            rect.setStroke(Color.BLACK);
            rect.setArcWidth(10);
            rect.setArcHeight(10);

            Text txt = new Text(String.valueOf(value));
            txt.setStyle("-fx-font-size: 28; -fx-font-weight: bold;");

            setAlignment(Pos.CENTER);
            getChildren().addAll(rect, txt);

            setOnMouseClicked(e -> {
                if (isMyTurn(dernierEtatRecu)) {
                    List<String> actions = dernierEtatRecu.availableActions;
                    // Sélection autorisée si le serveur l’indique
                    // OU en fallback s’il y a des dés sur le plateau
                    boolean canSelect = (actions != null && actions.contains("SELECT_DICE")) ||
                            ((actions == null || actions.isEmpty())
                                    && dernierEtatRecu.diceOnPlate != null
                                    && !dernierEtatRecu.diceOnPlate.isEmpty());

                    if (canSelect) toggleSelection();
                }
            });
        }

        private void toggleSelection() {
            selected = !selected;
            rect.setStroke(selected ? Color.GOLD : Color.BLACK);
            rect.setStrokeWidth(selected ? 3 : 1);
            rect.setFill(selected ? Color.LIGHTYELLOW : Color.WHITESMOKE);
            updateActionButtons(dernierEtatRecu);
        }

        public int getValue() { return value; }
        public boolean isSelected() { return selected; }
    }
}
