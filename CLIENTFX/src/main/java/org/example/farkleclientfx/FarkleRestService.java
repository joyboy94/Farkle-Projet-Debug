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
 * Service REST côté client – STRICT
 * - Ne contient aucune logique de déduction.
 * - Expose explicitement getStateChanged() (évite l’ambiguïté avec getState()).
 * - getEtatCompose() se contente d’agréger les endpoints GET autorisés.
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
    /** STRICT: on expose seulement l’API conforme à l’énoncé. */
    public Integer getStateChanged() throws ApiException {
        Integer state = api.getState(); // operationId "getState" côté swagger == /farkle/stateChanged
        System.out.println("[SERVICE] GET /stateChanged -> " + state);
        return state;
    }

    // --- Lectures atomiques ---
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
     * Construit un état composite à partir des GET autorisés, sans logique métier.
     * Champs critiques toujours initialisés pour l’UI (pas de null bloquant).
     */
    public TurnStatusDTO getEtatCompose() throws ApiException {
        System.out.println("[SERVICE] Construction de l'état composite...");
        TurnStatusDTO dto = new TurnStatusDTO();


        // Défauts sûrs
        dto.currentPlayerId      = -1;
        dto.currentPlayerName    = "";
        dto.currentPlayerScore   = 0;
        dto.opponentPlayerId     = -1;
        dto.opponentPlayerName   = "";
        dto.opponentPlayerScore  = 0;
        dto.diceOnPlate          = new ArrayList<>();
        dto.keptDiceThisTurn     = new ArrayList<>();
        dto.tempScore            = 0;
        dto.gameState            = "";
        dto.winningPlayerName    = "";   // <- plus null
        dto.winningPlayerScore   = 0;    // <- plus null


        // 1) Qui joue ?
        try {
            Integer currentId = getCurrentPlayerId();
            dto.currentPlayerId = (currentId != null ? currentId : -1);
            System.out.println("[SERVICE] Joueur courant ID=" + dto.currentPlayerId);
        } catch (Exception ignored) {}

        // 2) Infos players (IDs 0/1 côté serveur)
        RestPlayer player0 = null, player1 = null;
        try { player0 = api.getPlayer(0); } catch (Exception ignored) {}
        try { player1 = api.getPlayer(1); } catch (Exception ignored) {}

        if (dto.currentPlayerId == 0 && player0 != null) {
            dto.currentPlayerName  = safe(player0.getName());
            dto.currentPlayerScore = safeInt(player0.getScore());
            if (player1 != null) {
                dto.opponentPlayerId    = 1;
                dto.opponentPlayerName  = safe(player1.getName());
                dto.opponentPlayerScore = safeInt(player1.getScore());
            }
        } else if (dto.currentPlayerId == 1 && player1 != null) {
            dto.currentPlayerName  = safe(player1.getName());
            dto.currentPlayerScore = safeInt(player1.getScore());
            if (player0 != null) {
                dto.opponentPlayerId    = 0;
                dto.opponentPlayerName  = safe(player0.getName());
                dto.opponentPlayerScore = safeInt(player0.getScore());
            }
        } else {
            // Pas de joueur courant connu → peu importe l’ordre, on renseigne si dispo
            if (player0 != null) {
                dto.opponentPlayerId    = 0;
                dto.opponentPlayerName  = safe(player0.getName());
                dto.opponentPlayerScore = safeInt(player0.getScore());
            }
            if (player1 != null) {
                // si player0 était vide, l’actuel opponent est 1 ; sinon on écrase pas les infos utiles
                if (dto.opponentPlayerId == -1) {
                    dto.opponentPlayerId    = 1;
                    dto.opponentPlayerName  = safe(player1.getName());
                    dto.opponentPlayerScore = safeInt(player1.getScore());
                }
            }
        }

        // 3) Dés & score temporaire
        try {
            RestDices dicesPlate = getDicesOnPlate();
            if (dicesPlate != null && dicesPlate.getDices() != null)
                dto.diceOnPlate = dicesPlate.getDices();
        } catch (Exception ignored) {}

        try {
            RestDices selectedDices = getSelectedDices();
            if (selectedDices != null && selectedDices.getDices() != null)
                dto.keptDiceThisTurn = selectedDices.getDices();
        } catch (Exception ignored) {}

        try {
            Integer tempScore = getActualTurnPoints();
            if (tempScore != null) dto.tempScore = tempScore;
        } catch (Exception ignored) {}

        // 4) Fin de partie (si dispo via /winner)
        try {
            RestPlayer winner = getWinner();
            if (winner != null) {
                dto.gameState          = "GAME_OVER";
                dto.winningPlayerName  = safe(winner.getName());
                dto.winningPlayerScore = safeInt(winner.getScore());
            }
        } catch (Exception ignored) {}

        System.out.println("[SERVICE] État composite construit");
        return dto;
    }

    private static String safe(String s) {
        return (s == null ? "" : s);
    }

    private static Integer safeInt(Integer i) {
        return (i == null ? 0 : i);
    }

    public void setLocalPlayerId(Integer playerId) {
        this.localPlayerId = playerId;
    }

    public Integer getLocalPlayerId() {
        return localPlayerId;
    }
}
