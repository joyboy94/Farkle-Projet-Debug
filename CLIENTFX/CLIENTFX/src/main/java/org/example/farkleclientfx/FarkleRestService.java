package org.example.farkleclientfx.service;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.DefaultApi;
import io.swagger.client.model.RestDices;
import io.swagger.client.model.RestPlayer;
import io.swagger.client.model.TurnStatusDTO;

public class FarkleRestService {

    private final DefaultApi api;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public FarkleRestService() {
        ApiClient localApiClient = new ApiClient();
        // IMPORTANT: Configurez ici l'URL de votre serveur si elle n'est pas la valeur par défaut
        // localApiClient.setBasePath("http://localhost:8080/v1");
        this.api = new DefaultApi(localApiClient);
    }

    // Méthode privée pour centraliser le parsing JSON des réponses qui sont des String
    private TurnStatusDTO parseResponse(String jsonResponse) throws ApiException {
        try {
            return jsonMapper.readValue(jsonResponse, TurnStatusDTO.class);
        } catch (Exception e) {
            throw new ApiException(500, "Erreur lors de la lecture de la réponse du serveur : " + e.getMessage());
        }
    }

    // --- Actions du Joueur ---
    public RestPlayer inscrireJoueur(String name) throws ApiException {
        return api.name(name);
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

    // --- Méthodes GET pour le mode Spectateur et la mise à jour ---

    public Integer getState() throws ApiException {
        return api.getState();
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
    public Integer getStateChanged() throws ApiException {
        return api.getState();
    }

    public TurnStatusDTO getEtatCompose() throws ApiException {
        TurnStatusDTO dto = new TurnStatusDTO();

        // Récupère l'ID du joueur courant
        Integer currentId = api.getCurrentPlayerID();
        dto.currentPlayerId = currentId != null ? currentId : -1;

        // Récupère les joueurs 0 et 1 (si existants)
        try {
            io.swagger.client.model.RestPlayer player0 = api.getPlayer(0);
            io.swagger.client.model.RestPlayer player1 = api.getPlayer(1);

            // Attribue nom et score
            if (player0 != null && player0.getId() == dto.currentPlayerId) {
                dto.currentPlayerName = player0.getName();
                dto.currentPlayerScore = player0.getScore();
                dto.opponentPlayerId = player1.getId();
                dto.opponentPlayerName = player1.getName();
                dto.opponentPlayerScore = player1.getScore();
            } else if (player1 != null && player1.getId() == dto.currentPlayerId) {
                dto.currentPlayerName = player1.getName();
                dto.currentPlayerScore = player1.getScore();
                dto.opponentPlayerId = player0.getId();
                dto.opponentPlayerName = player0.getName();
                dto.opponentPlayerScore = player0.getScore();
            }
        } catch (Exception e) {
            // Peut arriver si le joueur 1 n'existe pas encore (partie solo)
        }

        // Les dés sur le plateau
        io.swagger.client.model.RestDices dicesPlate = api.getDicesPlates();
        dto.diceOnPlate = (dicesPlate != null && dicesPlate.getDices() != null) ? dicesPlate.getDices() : new ArrayList<>();

        // Dés gardés ce tour-ci
        io.swagger.client.model.RestDices selectedDices = api.getSelectedDices();
        dto.keptDiceThisTurn = (selectedDices != null && selectedDices.getDices() != null) ? selectedDices.getDices() : new ArrayList<>();

        // Score temporaire (si tu as un endpoint, sinon laisse à 0)
        Integer tempScore = api.getActualTurnPoints();
        dto.tempScore = (tempScore != null) ? tempScore : 0;

        // Remplis d'autres champs si tu as besoin, par exemple...
        // dto.gameState = ...  // impossible sans endpoint dédié
        // dto.immersiveMessage = ...

        return dto;
    }

}
