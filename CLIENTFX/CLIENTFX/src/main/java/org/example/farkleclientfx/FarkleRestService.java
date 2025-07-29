package org.example.farkleclientfx.service;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.DefaultApi;
import io.swagger.client.model.RestDices;
import io.swagger.client.model.RestPlayer;
import io.swagger.client.model.TurnStatusDTO;

/**
 * Le FarkleRestService agit comme une couche d'abstraction (un "pont") entre l'interface utilisateur (MainViewController)
 * et le client API auto-généré (DefaultApi).
 * Son rôle est de simplifier les appels au serveur et de gérer la conversion des données (parsing JSON).
 */
public class FarkleRestService {

    /** Instance du client API auto-généré par Swagger/OpenAPI, qui gère la communication HTTP brute. */
    private final DefaultApi api;

    /** Utilitaire pour convertir manuellement les réponses du serveur (qui sont en format String JSON) en objets Java (TurnStatusDTO). */
    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Constructeur du service.
     * Initialise le client API pour qu'il communique avec le serveur.
     */
    public FarkleRestService() {
        ApiClient localApiClient = new ApiClient();
        // IMPORTANT : La ligne ci-dessous est cruciale. C'est ici que l'on configure l'adresse
        // du serveur. Si votre serveur tourne sur une autre machine ou un autre port, il faut la modifier.
        // localApiClient.setBasePath("http://localhost:8080/v1");
        this.api = new DefaultApi(localApiClient);
    }

    /**
     * Méthode utilitaire privée pour analyser la réponse JSON du serveur.
     * C'est une solution de contournement nécessaire car les endpoints d'action (/roll, /bank, /select)
     * ont été générés pour retourner un simple `String` au lieu d'un objet `TurnStatusDTO` typé.
     * @param jsonResponse La réponse du serveur en format String JSON.
     * @return L'objet TurnStatusDTO correspondant.
     * @throws ApiException Si le JSON est malformé ou si une erreur survient.
     */
    private TurnStatusDTO parseResponse(String jsonResponse) throws ApiException {
        try {
            return jsonMapper.readValue(jsonResponse, TurnStatusDTO.class);
        } catch (Exception e) {
            throw new ApiException(500, "Erreur lors de la lecture de la réponse du serveur : " + e.getMessage());
        }
    }

    // --- Actions du Joueur ---

    /**
     * Inscrit un joueur auprès du serveur.
     * @param name Le nom du joueur.
     * @return Un objet RestPlayer contenant l'ID assigné par le serveur.
     * @throws ApiException Si l'inscription échoue.
     */
    public RestPlayer inscrireJoueur(String name) throws ApiException {
        return api.name(name);
    }

    /**
     * Envoie la commande "lancer les dés" au serveur.
     * @return Le nouvel état du jeu après le lancer, encapsulé dans un TurnStatusDTO.
     * @throws ApiException Si l'action échoue.
     */
    public TurnStatusDTO lancerDes() throws ApiException {
        return parseResponse(api.roll());
    }

    /**
     * Envoie la sélection de dés du joueur au serveur.
     * @param diceInput Une chaîne de caractères représentant les dés sélectionnés (ex: "1 5 5").
     * @return Le nouvel état du jeu après la sélection.
     * @throws ApiException Si l'action échoue.
     */
    public TurnStatusDTO selectionnerDes(String diceInput) throws ApiException {
        return parseResponse(api.select(diceInput));
    }

    /**
     * Envoie la commande "mettre en banque" au serveur.
     * @return Le nouvel état du jeu après la mise en banque (généralement, le début du tour de l'adversaire).
     * @throws ApiException Si l'action échoue.
     */
    public TurnStatusDTO banker() throws ApiException {
        return parseResponse(api.bank());
    }

    // --- Méthodes GET pour le Polling et la mise à jour ---

    /**
     * Interroge l'endpoint /stateChanged du serveur.
     * @return 1 si l'état a changé depuis le dernier appel, 0 sinon.
     * @throws ApiException Si l'appel échoue.
     */
    public Integer getState() throws ApiException {
        return api.getState();
    }

    /** Récupère les informations d'un joueur par son ID. */
    public RestPlayer getRestPlayer(Integer id) throws ApiException {
        return api.getPlayer(id);
    }

    /** Récupère la liste des dés actuellement sur le plateau. */
    public RestDices getDicesOnPlate() throws ApiException {
        return api.getDicesPlates();
    }

    /** Récupère la liste des dés que le joueur a mis de côté pendant son tour. */
    public RestDices getSelectedDices() throws ApiException {
        return api.getSelectedDices();
    }

    /** Récupère le score temporaire du tour actuel. */
    public Integer getActualTurnPoints() throws ApiException {
        return api.getActualTurnPoints();
    }

    /** Récupère l'ID du joueur dont c'est le tour. */
    public Integer getCurrentPlayerId() throws ApiException {
        return api.getCurrentPlayerID();
    }

    /** Alias pour getState(), utilisé dans le code du contrôleur. */
    public Integer getStateChanged() throws ApiException {
        return api.getState();
    }

    /**
     * Méthode cruciale pour le fonctionnement du polling sous la contrainte de l'API imposée.
     * Elle reconstruit un objet TurnStatusDTO en effectuant plusieurs appels GET individuels au serveur.
     * C'est la solution de contournement pour l'absence d'un endpoint `GET /gameState`.
     * @return Un TurnStatusDTO assemblé, mais potentiellement incomplet (sans `gameState` ni `availableActions`).
     * @throws ApiException Si l'un des appels internes échoue.
     */
    public TurnStatusDTO getEtatCompose() throws ApiException {
        TurnStatusDTO dto = new TurnStatusDTO();

        // 1. Récupère l'ID du joueur courant.
        Integer currentId = api.getCurrentPlayerID();
        dto.currentPlayerId = currentId != null ? currentId : -1;

        // 2. Tente de récupérer les informations des deux joueurs (supposés avoir les ID 0 et 1).
        try {
            io.swagger.client.model.RestPlayer player0 = api.getPlayer(0);
            io.swagger.client.model.RestPlayer player1 = api.getPlayer(1);

            // 3. Détermine qui est le joueur courant et qui est l'adversaire pour remplir le DTO.
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
            // Cette exception peut arriver normalement si le deuxième joueur n'est pas encore connecté.
        }

        // 4. Récupère les dés sur le plateau.
        io.swagger.client.model.RestDices dicesPlate = api.getDicesPlates();
        dto.diceOnPlate = (dicesPlate != null && dicesPlate.getDices() != null) ? dicesPlate.getDices() : new ArrayList<>();

        // 5. Récupère les dés gardés pendant le tour.
        io.swagger.client.model.RestDices selectedDices = api.getSelectedDices();
        dto.keptDiceThisTurn = (selectedDices != null && selectedDices.getDices() != null) ? selectedDices.getDices() : new ArrayList<>();

        // 6. Récupère le score temporaire du tour.
        Integer tempScore = api.getActualTurnPoints();
        dto.tempScore = (tempScore != null) ? tempScore : 0;

        // Note : Cette méthode ne peut pas récupérer les champs `gameState` ou `availableActions`,
        // car ils sont calculés par la logique du serveur et non de simples données.
        // C'est pourquoi le MainViewController doit les "deviner".

        return dto;
    }

}