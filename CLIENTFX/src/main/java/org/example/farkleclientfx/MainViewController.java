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
 * SWITCH DE MODE :
 *  - STRICT_MODE = true  -> Comportement examen : seul le joueur en ATTENTE poll /stateChanged ; pas de force re-sync ; aucune déduction d’actions.
 *  - STRICT_MODE = false -> Mode compat (ancien fonctionnement) : force re-sync après N zéros ou X ms ; fallback de déduction d’actions.
 *
 * Dans les deux modes :
 *  - Les écritures UI passent par Platform.runLater.
 *  - Le joueur ACTIF ne poll jamais /stateChanged ; après chaque POST, on fait un GET compose pour re-synchroniser l’UI.
 */
public class MainViewController {

    /* ========= SWITCH DE MODE ========= */
    private static final boolean STRICT_MODE = true;            // <- TRUE pour l’examen
    private static final boolean COMPAT_FORCE_RESYNC   = !STRICT_MODE;
    private static final boolean COMPAT_DEDUCE_ACTIONS = !STRICT_MODE;

    // Compat: filets de sécurité (uniquement si STRICT_MODE=false)
    private int  zerosInARow     = 0;
    private long lastFullFetchMs = 0L;
    private static final int  FORCE_RESYNC_AFTER_ZEROS = 3;
    private static final long FORCE_RESYNC_EVERY_MS    = 5000L;

    /* ============================
       == RÉFÉRENCES UI (FXML)  ==
       ============================ */

    @FXML private Label tourLabel, scoreLabel, messageBoxLabel, playerName, playerScore, opponentName, opponentScore;
    @FXML private ImageView playerAvatar, opponentAvatar;
    @FXML private HBox diceBox, keptDiceBox;
    @FXML private Button btnRoll, btnBank, btnKeep, btnQuit;
    @FXML private TableView<Map<String, String>> comboTable;
    @FXML private TableColumn<Map<String, String>, String> colCombo, colPoints;
    @FXML private Label turnUpdateLabel; // toast “<name> played”

    /* ============================
       ====== ÉTAT / SERVICE ======
       ============================ */

    private final FarkleRestService farkleService = new FarkleRestService(); // client REST

    private Integer myPlayerId = null;  // id du joueur local
    private String myName = null;       // nom local

    // Dernier état reçu du serveur (cache UI)
    private TurnStatusDTO dernierEtatRecu = new TurnStatusDTO();

    // Table des combinaisons par défaut
    private ObservableList<Map<String, String>> defaultScoreRules;

    // Représentations graphiques des dés sur le plateau
    private final List<DieView> diceViewsOnPlate = new ArrayList<>();

    /* ============================
       ========= POLLING ==========
       ============================ */

    private ScheduledExecutorService pollingExecutor;     // thread planifié (daemon)
    private ScheduledFuture<?> pollingFuture;             // tâche courante
    private final AtomicBoolean isPollingActive = new AtomicBoolean(false);

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
        System.out.println("[INIT] MainViewController – démarrage (STRICT_MODE=" + STRICT_MODE + ")");
        setupComboTable();        // colonnes + valeurs par défaut
        setupButtonHandlers();    // actions des boutons
        setupPollingExecutor();   // thread daemon pour le polling
        resetUIForNewGame();      // état neutre (pas inscrit)
        Platform.runLater(this::inscriptionJoueurEtDebut); // prompt “Pirate” quand la scène est prête
        loadAvatars();            // charge les ressources images
        // 🔧 Le toast ne bloque jamais les clics
        if (turnUpdateLabel != null) turnUpdateLabel.setMouseTransparent(true);
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
     * Demande un nom, s’inscrit via /join, tente un état initial, puis
     * (ATTENTE) lance le polling ou (ACTIF) active les boutons.
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
            System.out.println("[INSCRIPTION] Abandon : aucun nom saisi.");
            return;
        }

        myName = result.get().trim();
        try {
            RestPlayer joueur = farkleService.inscrireJoueur(myName);
            myPlayerId = joueur.getId();
            myName     = joueur.getName(); // si normalisé
            System.out.println("[INSCRIPTION] OK => " + myName + " (ID=" + myPlayerId + ")");
            tourLabel.setText("🏴‍☠️ Bienvenue " + myName + " ! En attente d'un adversaire...");

            try {
                TurnStatusDTO etatInitial = farkleService.getEtatCompose();
                System.out.println("[INIT] État initial : " + resumeDto(etatInitial));
                majInterfaceAvecEtat(etatInitial);
            } catch (Exception ex) {
                System.out.println("[INIT] Aucun état initial disponible.");
            }

            if (dernierEtatRecu != null && !isMyTurn(dernierEtatRecu)) {
                if (STRICT_MODE) {
                    // ⚠️ Anti-course : on laisse ~1,2s au client qui doit devenir ACTIF
                    // pour récupérer le 1, puis on commence à poller côté ATTENTE.
                    System.out.println("[INIT] STRICT: démarrage du polling différé (1.2s) pour éviter de consommer le 1 de l'autre client.");
                    pollingExecutor.schedule(this::startPolling, 1200, TimeUnit.MILLISECONDS);
                } else {
                    startPolling(); // compat
                }
            } else {
                setActionButtonsEnabled(true);  // ACTIF
            }


        } catch (ApiException e) {
            System.out.println("[INSCRIPTION] Échec : " + e.getMessage());
            afficherAlerteErreur("Erreur d'Inscription", "Impossible de s'inscrire : " + e.getMessage());
        }
    }

    /* ============================
       ========= POLLING ==========
       ============================ */

    /** Démarre la boucle si elle n’est pas déjà active (ATTENTE uniquement). */
    private void startPolling() {
        if (!isPollingActive.compareAndSet(false, true)) {
            System.out.println("[POLL] Déjà actif.");
            return;
        }
        System.out.println("[POLL] Démarrage (ATTENTE, STRICT_MODE=" + STRICT_MODE + ").");

        Runnable pollingTask = () -> {
            try {
                // Si je deviens actif, je stoppe immédiatement (séparation stricte)
                if (dernierEtatRecu != null && isMyTurn(dernierEtatRecu)) {
                    System.out.println("[POLL] Je suis actif -> arrêt polling.");
                    stopPolling();
                    return;
                }

                Integer flag = farkleService.getStateChanged(); // 0/1 consommable
                boolean changed = (flag != null && flag == 1);

                if (STRICT_MODE) {
                    if (changed) {
                        TurnStatusDTO newState = farkleService.getEtatCompose(); // fetch complet déclenché par 1
                        System.out.println("[POLL] (STRICT) Nouvel état via 1 : " + resumeDto(newState));

                        Platform.runLater(() -> {
                            majInterfaceAvecEtat(newState);
                            showPlayedToast(newState.currentPlayerName); // toast à chaque 1

                            if (isMyTurn(newState)) {
                                System.out.println("[POLL] Je deviens actif -> activer boutons et arrêter polling.");
                                setActionButtonsEnabled(true); // activer D'ABORD
                                stopPolling();                 // puis stopper le poll
                            }
                            if ("GAME_OVER".equals(newState.gameState)) {
                                System.out.println("[POLL] Partie terminée -> arrêt polling.");
                                stopPolling();
                            }
                        });
                    }
                    // Si 0 -> ne rien faire (aucun force re-sync)
                } else {
                    // ==== MODE COMPAT ====
                    if (changed) zerosInARow = 0; else zerosInARow++;
                    boolean tooOld    = (System.currentTimeMillis() - lastFullFetchMs) > FORCE_RESYNC_EVERY_MS;
                    boolean forceSync = (zerosInARow >= FORCE_RESYNC_AFTER_ZEROS) || tooOld;

                    if (changed || (COMPAT_FORCE_RESYNC && forceSync) || dernierEtatRecu.currentPlayerId == -1) {
                        if (!changed && (COMPAT_FORCE_RESYNC && forceSync)) {
                            System.out.println("[POLL] (COMPAT) Force re-sync (zeros=" + zerosInARow + ", tooOld=" + tooOld + ")");
                        }
                        TurnStatusDTO newState = farkleService.getEtatCompose();
                        lastFullFetchMs = System.currentTimeMillis();
                        System.out.println("[POLL] (COMPAT) Nouvel état : " + resumeDto(newState));

                        Platform.runLater(() -> {
                            majInterfaceAvecEtat(newState);
                            if (changed) showPlayedToast(newState.currentPlayerName); // toast seulement quand 1

                            if (isMyTurn(newState)) {
                                System.out.println("[POLL] Je deviens actif -> arrêt polling.");
                                stopPolling();
                                setActionButtonsEnabled(true);
                            }
                            if ("GAME_OVER".equals(newState.gameState)) {
                                System.out.println("[POLL] Partie terminée -> arrêt polling.");
                                stopPolling();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                System.out.println("[POLL] Erreur : " + e.getMessage());
            }
        };

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

    private void handlerBank() {
        if (btnBank.isDisabled()) return;
        System.out.println("[ACTION] BANK");
        performApiCall(farkleService::banker);
    }
    private void handlerRoll() {
        if (btnRoll.isDisabled()) return;
        System.out.println("[ACTION] ROLL");
        performApiCall(farkleService::lancerDes);
    }
    private void handlerKeep() {
        if (btnKeep.isDisabled()) return;
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
     * (ACTIF) jamais de polling, puis re-sync immédiat via GET compose.
     * Si on rend la main, on (re)lance le polling (ATTENTE).
     */
    private void performApiCall(FarkleApiCall apiCall) {
        if (myPlayerId == null || myPlayerId == -1) {
            afficherAlerteErreur("Non inscrit", "Inscrivez-vous d'abord.");
            return;
        }

        stopPolling();                 // ACTIF : pas de /stateChanged
        desactiverBoutonsPendantAction();

        new Thread(() -> {
            try {
                // 1) POST (ROLL / KEEP / BANK)
                TurnStatusDTO dtoAfterPost = apiCall.call(); // le POST retourne un DTO riche
                System.out.println("[ACTION] DTO: " + resumeDto(dtoAfterPost));

                // 1bis) Écho visuel côté ACTIF (en STRICT, l'actif ne poll pas /stateChanged)
                if (STRICT_MODE) {
                    showPlayedToast(myName);
                }

                // 2) Mémoriser ce que le POST a fourni (pour ne pas le perdre au re-sync)
                String postImmersive = (dtoAfterPost.immersiveMessage != null && !dtoAfterPost.immersiveMessage.isBlank())
                        ? dtoAfterPost.immersiveMessage : null;
                List<Map<String, String>> postCombos =
                        (dtoAfterPost.combinationHints != null && !dtoAfterPost.combinationHints.isEmpty())
                                ? dtoAfterPost.combinationHints : null;
                List<String> postActions =
                        (dtoAfterPost.availableActions != null && !dtoAfterPost.availableActions.isEmpty())
                                ? dtoAfterPost.availableActions : null;

                // 3) Re-sync immédiat et systématique via GET "composé"
                TurnStatusDTO refreshed = farkleService.getEtatCompose();

                // 4) Fusionner les infos du POST si le re-sync ne les fournit pas
                if ((refreshed.immersiveMessage == null || refreshed.immersiveMessage.isBlank()) && postImmersive != null) {
                    refreshed.immersiveMessage = postImmersive;
                }
                if ((refreshed.combinationHints == null || refreshed.combinationHints.isEmpty()) && postCombos != null) {
                    refreshed.combinationHints = postCombos;
                }
                if ((refreshed.availableActions == null || refreshed.availableActions.isEmpty()) && postActions != null) {
                    refreshed.availableActions = postActions;
                }

                // 5) Appliquer à l'UI + orchestration du polling selon STRICT/COMPAT
                Platform.runLater(() -> {
                    majInterfaceAvecEtat(refreshed);
                    boolean iAmActive = isMyTurn(refreshed);
                    setActionButtonsEnabled(iAmActive);

                    boolean isFarkle = "FARKLE_TURN_ENDED".equals(refreshed.gameState);

                    if (!iAmActive && !"GAME_OVER".equals(refreshed.gameState)) {
                        if (STRICT_MODE) {
                            if (isFarkle) {
                                // ⚠️ La branche FARKLE de majInterfaceAvecEtat() relancera le polling après ~3.5 s.
                                System.out.println("[ACTION][STRICT] FARKLE → pas de relance polling ici (pause FARKLE).");
                            } else {
                                // Handover normal : petite latence pour éviter de consommer le 1 de l’adversaire
                                System.out.println("[ACTION][STRICT] Handover normal → startPolling dans 1.2s.");
                                pollingExecutor.schedule(this::startPolling, 1200, TimeUnit.MILLISECONDS);
                            }
                        } else {
                            // Mode compat : comportement historique
                            System.out.println("[ACTION][COMPAT] startPolling dans 0.4s.");
                            pollingExecutor.schedule(this::startPolling, 400, TimeUnit.MILLISECONDS);
                        }
                    }
                });

            } catch (ApiException e) {
                System.out.println("[ACTION] Erreur REST: " + e.getMessage());
                Platform.runLater(() -> {
                    afficherAlerteErreur("Erreur", e.getMessage());
                    try {
                        // Re-sync best effort pour remettre l'UI d'équerre
                        TurnStatusDTO refreshed = farkleService.getEtatCompose();
                        majInterfaceAvecEtat(refreshed);
                        boolean iAmActive = isMyTurn(refreshed);
                        setActionButtonsEnabled(iAmActive);

                        if (!iAmActive && !"GAME_OVER".equals(refreshed.gameState)) {
                            boolean isFarkle = "FARKLE_TURN_ENDED".equals(refreshed.gameState);
                            if (STRICT_MODE) {
                                if (isFarkle) {
                                    System.out.println("[ACTION][STRICT][ERR] FARKLE → pas de relance polling ici (pause FARKLE).");
                                } else {
                                    System.out.println("[ACTION][STRICT][ERR] Handover normal → startPolling dans 1.2s.");
                                    pollingExecutor.schedule(this::startPolling, 1200, TimeUnit.MILLISECONDS);
                                }
                            } else {
                                System.out.println("[ACTION][COMPAT][ERR] startPolling dans 0.4s.");
                                pollingExecutor.schedule(this::startPolling, 400, TimeUnit.MILLISECONDS);
                            }
                        }
                    } catch (Exception ignore) { /* best effort */ }
                });
            }
        }, "Farkle-Action-Thread").start();
    }

    /* ============================
       ====== MISE À JOUR UI ======
       ============================ */

    /**
     * Applique un état du serveur sur l’interface.
     * Gère explicitement FARKLE avec une pause (immersion).
     */
    private void majInterfaceAvecEtat(TurnStatusDTO etat) {
        if (etat == null) {
            System.out.println("[UI] majInterfaceAvecEtat(null)");
            return;
        }
        System.out.println("[UI] Mise à jour avec: " + resumeDto(etat));

        // 1) Cas FARKLE : message immersif + pause, puis reprise (ATTENTE/ACTIF selon le tour)
        if ("FARKLE_TURN_ENDED".equals(etat.gameState)) {
            this.dernierEtatRecu = etat;
            messageBoxLabel.setText(etat.immersiveMessage);
            if (etat.diceOnPlate != null) afficherDes(etat.diceOnPlate);
            desactiverBoutonsPendantAction();

            System.out.println("[UI] FARKLE détecté, pause 3.5s.");
            PauseTransition pause = new PauseTransition(Duration.seconds(3.5));
            pause.setOnFinished(e -> {
                if (isMyTurn(dernierEtatRecu)) {
                    setActionButtonsEnabled(true);
                } else if (!"GAME_OVER".equals(dernierEtatRecu.gameState)) {
                    startPolling();
                }
            });
            pause.play();
            return;
        }

        // 2) Remplace le “cache” local par l’état courant
        this.dernierEtatRecu = etat;

        // 3) Bandeau du haut
        if (etat.currentPlayerId == -1) {
            tourLabel.setText("En attente du second joueur...");
        } else {
            tourLabel.setText("C'est au tour de : " +
                    (etat.currentPlayerName != null ? etat.currentPlayerName : "..."));
        }

        // 4) Panneaux joueurs, message central, dés et combinaisons
        updatePlayerPanels(etat);
        scoreLabel.setText("Score du tour : " + etat.tempScore + " 💰");
        updateCentralMessage(etat);
        updateDiceDisplay(etat);

        if (etat.combinationHints != null && !etat.combinationHints.isEmpty()) {
            comboTable.setItems(FXCollections.observableArrayList(etat.combinationHints));
        } else {
            comboTable.setItems(defaultScoreRules);
        }

        // 5) Boutons d’action (STRICT vs COMPAT)
        updateActionButtons(etat);

        // 🔒 Ceinture + bretelles en STRICT : si c'est mon tour, (ré)active les boutons quoi qu'il arrive
        if (STRICT_MODE) {
            setActionButtonsEnabled(isMyTurn(etat) && !"GAME_OVER".equals(etat.gameState));
        }
    }

    /**
     * STRICT : aucune déduction. COMPAT : fallback historique.
     */
    private void updateActionButtons(TurnStatusDTO etat) {
        if (etat == null || "GAME_OVER".equals(etat.gameState)) {
            desactiverBoutonsPendantAction();
            System.out.println("[BUTTONS] Désactivés (fin de partie ou état null).");
            return;
        }

        boolean estMonTour = isMyTurn(etat);
        System.out.println("[BUTTONS] estMonTour=" + estMonTour);

        if (!estMonTour) {
            desactiverBoutonsPendantAction();
            System.out.println("[BUTTONS] Désactivés (tour adverse / attente).");
            return;
        }

        if (STRICT_MODE) {
            //  STRICT : activer/désactiver sans aucune déduction
            boolean enabled = estMonTour && !"GAME_OVER".equals(etat.gameState);
            btnRoll.setDisable(!enabled);
            btnBank.setDisable(!enabled);
            long nbSelected = diceViewsOnPlate.stream().filter(DieView::isSelected).count();
            btnKeep.setDisable(!enabled || nbSelected == 0);
            System.out.println("[BUTTONS] STRICT -> enabled=" + enabled
                    + " roll=" + !btnRoll.isDisabled()
                    + ", keep=" + !btnKeep.isDisabled()
                    + ", bank=" + !btnBank.isDisabled());
            return; // IMPORTANT : ne rien ré-écrire ensuite
        }

        // --- COMPAT : fallback historique (corrigé) ---
        List<String> actions = etat.availableActions;
        if (actions == null || actions.isEmpty()) {
            actions = new ArrayList<>();

            boolean hasKept = etat.keptDiceThisTurn != null && !etat.keptDiceThisTurn.isEmpty();
            boolean hasDice = etat.diceOnPlate       != null && !etat.diceOnPlate.isEmpty();
            int temp = etat.tempScore;

            // Début de tour (rien gardé, rien sur le plateau, 0 point)
            if (!hasKept && !hasDice && temp == 0) {
                actions.add("ROLL");
            }

            // Après un lancer : dés sur le plateau
            if (hasDice) {
                actions.add("SELECT_DICE");
                if (temp > 0) {
                    actions.add("BANK");
                    actions.add("ROLL"); // autoriser la relance après une sélection valide
                }
            }

            // Hot dice : plus de dés sur le plateau mais un score de tour > 0
            if (!hasDice && temp > 0) {
                actions.add("ROLL");
                actions.add("BANK");
            }
        }

        btnRoll.setDisable(!actions.contains("ROLL"));
        btnBank.setDisable(!actions.contains("BANK"));
        long nbSelected = diceViewsOnPlate.stream().filter(DieView::isSelected).count();
        btnKeep.setDisable(!actions.contains("SELECT_DICE") || nbSelected == 0);

        System.out.println("[BUTTONS] COMPAT actions=" + actions
                + " -> roll=" + !btnRoll.isDisabled()
                + ", keep=" + !btnKeep.isDisabled()
                + ", bank=" + !btnBank.isDisabled());
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

    /** Toast “<name> played” ~1s (côté attente, appelé à chaque 1). */
    private void showPlayedToast(String activeName) {
        final String labelText = ((activeName == null || activeName.isBlank()) ? "Player" : activeName) + " played";
        Platform.runLater(() -> {
            if (turnUpdateLabel != null) {
                turnUpdateLabel.setText(labelText);
                turnUpdateLabel.setVisible(true);
                // Astuce FXML : le label est mouseTransparent (cf. initialize)
                PauseTransition t = new PauseTransition(Duration.seconds(1.0));
                t.setOnFinished(e -> turnUpdateLabel.setVisible(false));
                t.playFromStart();
            }
        });
    }

    /** Ancien toast “changement de tour” — conservé si besoin ailleurs. */
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

    /** Désactive tous les boutons d’action (utile pendant une requête ou quand ce n’est pas notre tour). */
    private void desactiverBoutonsPendantAction() {
        btnRoll.setDisable(true);
        btnBank.setDisable(true);
        btnKeep.setDisable(true);
    }

    /** Active/désactive les boutons selon un booléen simple (sans logique métier). */
    private void setActionButtonsEnabled(boolean enabled) {
        btnRoll.setDisable(!enabled);
        btnBank.setDisable(!enabled);
        long nbSelected = diceViewsOnPlate.stream().filter(DieView::isSelected).count();
        btnKeep.setDisable(!enabled || nbSelected == 0);
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
     * – Cliquable pendant MON tour. STRICT : libre ; COMPAT : respecte le vieux “canSelect”.
     * – Notifie updateActionButtons après chaque toggle pour activer/désactiver “Garder”.
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
                    if (STRICT_MODE) {
                        toggleSelection();
                    } else {
                        List<String> actions = dernierEtatRecu.availableActions;
                        boolean canSelect = (actions != null && actions.contains("SELECT_DICE")) ||
                                ((actions == null || actions.isEmpty())
                                        && dernierEtatRecu.diceOnPlate != null
                                        && !dernierEtatRecu.diceOnPlate.isEmpty());
                        if (canSelect) toggleSelection();
                    }
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
