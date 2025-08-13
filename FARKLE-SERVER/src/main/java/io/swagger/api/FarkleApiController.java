package io.swagger.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import game.GameManager;
import io.swagger.model.RestDices;
import io.swagger.model.RestPlayer;
import io.swagger.model.TurnStatusDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * Contrôleur REST pour l'API Farkle
 * Version avec logs améliorés pour le suivi du respect des exigences
 */
@RestController
@RequestMapping("/farkle")
public class FarkleApiController implements FarkleApi {

    private static final Logger log = LoggerFactory.getLogger(FarkleApiController.class);
    private final ObjectMapper objectMapper;
    private final GameManager gameManager;

    @Autowired
    public FarkleApiController(ObjectMapper objectMapper, GameManager gameManager) {
        this.objectMapper = objectMapper;
        this.gameManager = gameManager;
        log.info("=== FarkleApiController initialisé (version conforme aux exigences) ===");
    }

    @Override
    public ResponseEntity<RestPlayer> name(@Valid @RequestParam(value = "name", required = false) String name) {
        log.info("[API] POST /farkle/name - Inscription du joueur: {}", name);
        RestPlayer restPlayer = gameManager.addPlayer(name);
        if (restPlayer != null) {
            log.info("[API] Joueur inscrit avec succès: {} (ID={})", restPlayer.getName(), restPlayer.getId());
            return new ResponseEntity<>(restPlayer, HttpStatus.OK);
        } else {
            log.warn("[API] Inscription refusée (partie complète)");
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public ResponseEntity<String> roll() {
        log.info("[API] POST /farkle/roll - Joueur ID={}", gameManager.getCurrentPlayerId());
        TurnStatusDTO dto = gameManager.roll();
        String response = toJson(dto);
        log.debug("[API] Réponse roll: {}", response);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<String> select(@Valid @RequestParam(value = "dices", required = false) String dices) {
        log.info("[API] POST /farkle/select - Dés sélectionnés: '{}' par joueur ID={}",
                dices, gameManager.getCurrentPlayerId());
        TurnStatusDTO dto = gameManager.select(dices);
        String response = toJson(dto);
        log.debug("[API] Réponse select: {}", response);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<String> bank() {
        log.info("[API] POST /farkle/bank - Joueur ID={}", gameManager.getCurrentPlayerId());
        TurnStatusDTO dto = gameManager.bank();
        String response = toJson(dto);
        log.debug("[API] Réponse bank: {}", response);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<String> quit(@Valid @RequestParam(value = "playerId", required = false) Integer playerId) {
        log.info("[API] POST /farkle/quit - Joueur ID={} quitte", playerId);
        gameManager.quit(playerId);
        return ResponseEntity.ok(toJson(gameManager.getGameState()));
    }

    @Override
    public ResponseEntity<Integer> getActualTurnPoints() {
        int points = gameManager.getActualTurnPoints();
        log.debug("[API] GET /farkle/actualTurnPoints -> {}", points);
        return ResponseEntity.ok(points);
    }

    @Override
    public ResponseEntity<Integer> getCurrentPlayerID() {
        int id = gameManager.getCurrentPlayerId();
        log.debug("[API] GET /farkle/currentPlayerId -> {}", id);
        return ResponseEntity.ok(id);
    }

    @Override
    public ResponseEntity<RestDices> getDicesPlates() {
        RestDices dices = gameManager.getDicePlate();
        log.debug("[API] GET /farkle/dicesPlate -> {} dés",
                dices.getDices() != null ? dices.getDices().size() : 0);
        return ResponseEntity.ok(dices);
    }

    @Override
    public ResponseEntity<RestPlayer> getPlayer(@PathVariable("id") Integer id) {
        log.debug("[API] GET /farkle/player/{}", id);
        RestPlayer player = gameManager.getRestPlayer(id);
        if (player != null) {
            return new ResponseEntity<>(player, HttpStatus.OK);
        }
        log.debug("[API] Joueur {} non trouvé", id);
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @Override
    public ResponseEntity<RestDices> getSelectedDices() {
        RestDices dices = gameManager.getSelectedDices();
        log.debug("[API] GET /farkle/selectedDices -> {} dés",
                dices.getDices() != null ? dices.getDices().size() : 0);
        return ResponseEntity.ok(dices);
    }

    /**
     * Endpoint /farkle/stateChanged - CRITIQUE pour l'exigence 1
     * Doit retourner 1 une seule fois après chaque changement, puis 0
     */
    @Override
    public ResponseEntity<Integer> getState() {
        Integer state = gameManager.getState();
        log.info("[API] GET /farkle/stateChanged -> {} (EXIGENCE 1)", state);
        return ResponseEntity.ok(state);
    }

    @Override
    public ResponseEntity<RestPlayer> getWinner() {
        log.debug("[API] GET /farkle/winner");
        RestPlayer winner = gameManager.getWinner();
        if (winner != null) {
            log.info("[API] Gagnant trouvé: {} avec {} points", winner.getName(), winner.getScore());
            return new ResponseEntity<>(winner, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /**
     * Utilitaire pour sérialiser les DTOs en JSON
     */
    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            log.error("[API] Erreur de sérialisation JSON", e);
            return "{\"error\":\"Erreur interne du serveur\"}";
        }
    }
}