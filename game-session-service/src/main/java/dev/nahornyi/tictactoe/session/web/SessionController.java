package dev.nahornyi.tictactoe.session.web;

import dev.nahornyi.tictactoe.contracts.api.CreateSessionRequest;
import dev.nahornyi.tictactoe.contracts.api.SessionResponse;
import dev.nahornyi.tictactoe.session.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/sessions")
@Tag(name = "Sessions", description = "Session management and automated gameplay")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @Operation(summary = "Create a session",
            description = "Also creates the backing game in the Game Engine Service, so an unreachable "
                    + "engine is reported here rather than as a failed simulation later.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Session created"),
            @ApiResponse(responseCode = "400", description = "Unknown move strategy"),
            @ApiResponse(responseCode = "503", description = "Game Engine Service unreachable")
    })
    public ResponseEntity<SessionResponse> createSession(
            @RequestBody(required = false) CreateSessionRequest request,
            UriComponentsBuilder uriBuilder) {

        SessionResponse session = sessionService.createSession(request);
        return ResponseEntity
                .created(uriBuilder.path("/sessions/{sessionId}").build(session.sessionId()))
                .body(session);
    }

    /**
     * Returns 202 rather than 200: the simulation runs in the background so the caller is not held
     * open for the length of a game. Progress arrives over SSE, and the final state is available
     * from {@code GET /sessions/{sessionId}}.
     */
    @PostMapping("/{sessionId}/simulate")
    @Operation(summary = "Start the automated simulation",
            description = "Accepts the request and plays the game on a background thread. "
                    + "Watch progress over SSE or poll the session.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Simulation accepted"),
            @ApiResponse(responseCode = "404", description = "Unknown session"),
            @ApiResponse(responseCode = "409", description = "Simulation already started or finished")
    })
    public ResponseEntity<SessionResponse> simulate(@PathVariable UUID sessionId) {
        return ResponseEntity.accepted().body(sessionService.startSimulation(sessionId));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Read session details, current board and move history")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session details"),
            @ApiResponse(responseCode = "404", description = "Unknown session")
    })
    public SessionResponse getSession(@PathVariable UUID sessionId) {
        return sessionService.getSession(sessionId);
    }
}
