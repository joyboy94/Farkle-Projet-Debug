package org.example.farkleclientfx.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.DefaultApi;
import io.swagger.client.model.RestDices;
import io.swagger.client.model.RestPlayer;
import io.swagger.client.model.TurnStatusDTO;

import java.util.ArrayList;

/**
 * Service REST côté client -
 */
public class FarkleRestService {

    private final DefaultApi api;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private Integer localPlayerId = null;

    public FarkleRestService() {
        ApiClient localApiClient = new ApiClient();
        localApiClient.setBasePath("http://localhost:8080/v1");
        this.api = new DefaultApi(localApiClient);
        System.out.println("[SERVICE] FarkleRestService initialisé");
    }

    private TurnStatusDTO parseResponse(String jsonResponse) throws ApiException {
        try {
            return jsonMapper.readValue(jsonResponse, TurnStatusDTO.class);
        } catch (Exception e) {
            throw new ApiException(500, "Erreur de parsing JSON: " + e.getMessage());
        }
    }

    // --- Actions ---
    public RestPlayer inscrireJoueur(String name) throws ApiException {
        System.out.println("[SERVICE] Inscription du joueur: " + name);
        RestPlayer p = api.name(name);
        if (p != null) {
            this.localPlayerId = p.getId();
            System.out.println("[SERVICE] Joueur inscrit avec ID=" + p.getId());
        }
        return p;
    }

    public TurnStatusDTO lancerDes() throws ApiException {
        return parseResponse(api.roll());
    }

    public TurnStatusDTO selectionnerDes(String diceInput) throws ApiException {
        return parseResponse(api.select(diceInput));
    }

    public TurnStatusDTO banker() throws ApiException {
        return parseResponse(api.bank());
    }

    // --- Polling ---
    public Integer getState() throws ApiException {
        return api.getState();
    }

    public Integer getStateChanged() throws ApiException {
        Integer state = api.getState();
        System.out.println("[SERVICE] GET /stateChanged -> " + state);
        return state;
    }

    public RestPlayer getRestPlayer(Integer id) throws ApiException {
        return api.getPlayer(id);
    }

    public RestDices getDicesOnPlate() throws ApiException {
        return api.getDicesPlates();
    }

    public RestDices getSelectedDices() throws ApiException {
        return api.getSelectedDices();
    }

    public Integer getActualTurnPoints() throws ApiException {
        return api.getActualTurnPoints();
    }

    public Integer getCurrentPlayerId() throws ApiException {
        return api.getCurrentPlayerID();
    }

    public RestPlayer getWinner() throws ApiException {
        return api.getWinner();
    }

    /**
     * Reconstruit un état composite - VERSION CORRIGÉE ET OPTIMISÉE
     */
    public TurnStatusDTO getEtatCompose() throws ApiException {
        System.out.println("[SERVICE] Construction de l'état composite...");
        TurnStatusDTO dto = new TurnStatusDTO();

        // 1. Récupérer l'ID du joueur courant
        try {
            Integer currentId = getCurrentPlayerId();
            dto.currentPlayerId = currentId != null ? currentId : -1;
            System.out.println("[SERVICE] Joueur courant ID=" + dto.currentPlayerId);
        } catch (Exception e) {
            dto.currentPlayerId = -1;
        }

        // 2. Récupérer les infos des deux joueurs (IDs fixes: 0 et 1)
        RestPlayer player0 = null;
        RestPlayer player1 = null;

        try {
            player0 = api.getPlayer(0);
            System.out.println("[SERVICE] Joueur 0: " + (player0 != null ? player0.getName() : "n/a"));
        } catch (Exception ignored) {}

        try {
            player1 = api.getPlayer(1);
            System.out.println("[SERVICE] Joueur 1: " + (player1 != null ? player1.getName() : "n/a"));
        } catch (Exception ignored) {}

        // 3. Assigner les rôles en fonction du currentPlayerId
        if (dto.currentPlayerId == 0 && player0 != null) {
            dto.currentPlayerName = player0.getName();
            dto.currentPlayerScore = player0.getScore();
            if (player1 != null) {
                dto.opponentPlayerId = 1;
                dto.opponentPlayerName = player1.getName();
                dto.opponentPlayerScore = player1.getScore();
            }
        } else if (dto.currentPlayerId == 1 && player1 != null) {
            dto.currentPlayerName = player1.getName();
            dto.currentPlayerScore = player1.getScore();
            if (player0 != null) {
                dto.opponentPlayerId = 0;
                dto.opponentPlayerName = player0.getName();
                dto.opponentPlayerScore = player0.getScore();
            }
        }

        // 4. Récupérer les dés et le score
        try {
            RestDices dicesPlate = getDicesOnPlate();
            dto.diceOnPlate = (dicesPlate != null && dicesPlate.getDices() != null) ?
                    dicesPlate.getDices() : new ArrayList<>();
        } catch (Exception e) {
            dto.diceOnPlate = new ArrayList<>();
        }

        try {
            RestDices selectedDices = getSelectedDices();
            dto.keptDiceThisTurn = (selectedDices != null && selectedDices.getDices() != null) ?
                    selectedDices.getDices() : new ArrayList<>();
        } catch (Exception e) {
            dto.keptDiceThisTurn = new ArrayList<>();
        }

        try {
            Integer tempScore = getActualTurnPoints();
            dto.tempScore = (tempScore != null) ? tempScore : 0;
        } catch (Exception e) {
            dto.tempScore = 0;
        }

        // 5. Vérifier si partie terminée
        try {
            RestPlayer winner = getWinner();
            if (winner != null) {
                dto.gameState = "GAME_OVER";
                dto.winningPlayerName = winner.getName();
                dto.winningPlayerScore = winner.getScore();
            }
        } catch (Exception ignored) {}

        System.out.println("[SERVICE] État composite construit");
        return dto;
    }

    public void setLocalPlayerId(Integer playerId) {
        this.localPlayerId = playerId;
    }

    public Integer getLocalPlayerId() {
        return localPlayerId;
    }
}

