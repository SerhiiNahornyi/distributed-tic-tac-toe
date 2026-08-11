package dev.nahornyi.tictactoe.engine.web;

import dev.nahornyi.tictactoe.contracts.api.CreateGameRequest;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRequest;
import dev.nahornyi.tictactoe.engine.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/games")
@Tag(name = "Games", description = "Board state, move validation and game outcome")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    @Operation(summary = "Create a game",
            description = "Idempotent when a gameId is supplied: creating the same id twice returns the existing game.")
    public ResponseEntity<GameStateResponse> createGame(
            @RequestBody(required = false) CreateGameRequest request,
            UriComponentsBuilder uriBuilder) {

        GameStateResponse game = gameService.createGame(request);
        return ResponseEntity
                .created(uriBuilder.path("/games/{gameId}").build(game.gameId()))
                .body(game);
    }

    @PostMapping("/{gameId}/move")
    @Operation(summary = "Play a move",
            description = "Validates the move, updates the board and returns the resulting game status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Move accepted"),
            @ApiResponse(responseCode = "400", description = "Malformed request or position outside the board"),
            @ApiResponse(responseCode = "404", description = "Unknown game"),
            @ApiResponse(responseCode = "409", description = "Cell occupied, wrong turn, or the game already finished")
    })
    public GameStateResponse move(@PathVariable UUID gameId, @Valid @RequestBody MoveRequest request) {
        return gameService.applyMove(gameId, request);
    }

    @GetMapping("/{gameId}")
    @Operation(summary = "Read the current board and status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current game state"),
            @ApiResponse(responseCode = "404", description = "Unknown game")
    })
    public GameStateResponse getGame(@PathVariable UUID gameId) {
        return gameService.getGame(gameId);
    }
}
