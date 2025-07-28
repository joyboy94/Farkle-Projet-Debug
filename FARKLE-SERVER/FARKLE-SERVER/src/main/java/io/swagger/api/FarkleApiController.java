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
        log.info("FarkleApiController initialisé avec GameManager injecté.");
    }

    // Toutes les méthodes ci-dessous correspondent aux routes REST "/farkle/xxxx"
    // Le préfixe "/farkle" vient de @RequestMapping de classe, et chaque méthode a le chemin relatif


    @Override
    public ResponseEntity<RestPlayer> name(@Valid @RequestParam(value = "name", required = false) String name) {
        log.info("Requête /name reçue pour : {}", name);
        RestPlayer restPlayer = gameManager.addPlayer(name);
        if (restPlayer != null) {
            return new ResponseEntity<>(restPlayer, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public ResponseEntity<String> roll() {
        log.info("Requête /roll reçue pour le joueur ID: {}", gameManager.getCurrentPlayerId());
        TurnStatusDTO dto = gameManager.roll();
        return ResponseEntity.ok(toJson(dto));
    }

    @Override
    public ResponseEntity<String> select(@Valid @RequestParam(value = "dices", required = false) String dices) {
        log.info("Requête /select reçue avec les dés : {} pour le joueur ID: {}", dices, gameManager.getCurrentPlayerId());
        TurnStatusDTO dto = gameManager.select(dices);
        return ResponseEntity.ok(toJson(dto));
    }

    @Override
    public ResponseEntity<String> bank() {
        log.info("Requête /bank reçue pour le joueur ID: {}", gameManager.getCurrentPlayerId());
        TurnStatusDTO dto = gameManager.bank();
        return ResponseEntity.ok(toJson(dto));
    }

    @Override
    public ResponseEntity<String> quit(@Valid @RequestParam(value = "playerId", required = false) Integer playerId) {
        log.info("Requête /quit reçue pour playerId: {}", playerId);
        gameManager.quit(playerId);
        return ResponseEntity.ok(toJson(gameManager.getGameState()));
    }

    @Override
    public ResponseEntity<Integer> getActualTurnPoints() {
        return ResponseEntity.ok(gameManager.getActualTurnPoints());
    }

    @Override
    public ResponseEntity<Integer> getCurrentPlayerID() {
        return ResponseEntity.ok(gameManager.getCurrentPlayerId());
    }

    @Override
    public ResponseEntity<RestDices> getDicesPlates() {
        return ResponseEntity.ok(gameManager.getDicePlate());
    }

    @Override
    public ResponseEntity<RestPlayer> getPlayer(@PathVariable("id") Integer id) {
        RestPlayer player = gameManager.getRestPlayer(id);
        if (player != null) {
            return new ResponseEntity<>(player, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @Override
    public ResponseEntity<RestDices> getSelectedDices() {
        return ResponseEntity.ok(gameManager.getSelectedDices());
    }

    @Override
    public ResponseEntity<Integer> getState() {
        int state = gameManager.getState();
        System.out.println("[DEBUG] Appel /farkle/stateChanged → renvoie : " + state); // ✅ ICI le bon endroit
        return ResponseEntity.ok(state);
    }


    @Override
    public ResponseEntity<RestPlayer> getWinner() {
        RestPlayer winner = gameManager.getWinner();
        if (winner != null) {
            return new ResponseEntity<>(winner, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    // Méthode utilitaire pour sérialiser les DTO
    private String toJson(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            log.error("Erreur lors de la sérialisation en JSON", e);
            return "{\"error\":\"Erreur interne du serveur\"}";
        }
    }
}