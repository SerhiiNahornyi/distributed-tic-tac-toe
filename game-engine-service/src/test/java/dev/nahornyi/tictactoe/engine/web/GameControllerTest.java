package dev.nahornyi.tictactoe.engine.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nahornyi.tictactoe.contracts.GameStatus;
import dev.nahornyi.tictactoe.contracts.PlayerSymbol;
import dev.nahornyi.tictactoe.contracts.api.GameStateResponse;
import dev.nahornyi.tictactoe.contracts.api.MoveRequest;
import dev.nahornyi.tictactoe.engine.domain.GameNotFoundException;
import dev.nahornyi.tictactoe.engine.domain.IllegalMoveException;
import dev.nahornyi.tictactoe.engine.domain.MoveRejection;
import dev.nahornyi.tictactoe.engine.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the HTTP contract in isolation from the domain: status codes, the Location header and
 * the RFC 7807 problem bodies the Game Session Service branches on.
 */
@WebMvcTest(GameController.class)
class GameControllerTest {

    private static final UUID GAME_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GameService gameService;

    @Test
    void createGameReturns201WithALocationHeader() throws Exception {
        when(gameService.createGame(any())).thenReturn(inProgress("---------"));

        mockMvc.perform(post("/games").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/games/" + GAME_ID))
                .andExpect(jsonPath("$.gameId").value(GAME_ID.toString()))
                .andExpect(jsonPath("$.board").value("---------"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void createGameAcceptsAnEmptyBody() throws Exception {
        when(gameService.createGame(any())).thenReturn(inProgress("---------"));

        mockMvc.perform(post("/games"))
                .andExpect(status().isCreated());
    }

    @Test
    void aValidMoveReturnsTheUpdatedState() throws Exception {
        when(gameService.applyMove(eq(GAME_ID), any())).thenReturn(inProgress("----X----"));

        mockMvc.perform(move(GAME_ID, new MoveRequest(PlayerSymbol.X, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board").value("----X----"))
                .andExpect(jsonPath("$.nextTurn").value("O"));
    }

    @Test
    void anUnknownGameReturns404AsProblemJson() throws Exception {
        when(gameService.applyMove(eq(GAME_ID), any())).thenThrow(new GameNotFoundException(GAME_ID));

        mockMvc.perform(move(GAME_ID, new MoveRequest(PlayerSymbol.X, 0)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:tictactoe:error:game-not-found"))
                .andExpect(jsonPath("$.code").value("game-not-found"));
    }

    @Test
    void anOccupiedCellReturns409() throws Exception {
        when(gameService.applyMove(eq(GAME_ID), any()))
                .thenThrow(new IllegalMoveException(MoveRejection.CELL_OCCUPIED, "Cell 4 is already occupied by X"));

        mockMvc.perform(move(GAME_ID, new MoveRequest(PlayerSymbol.O, 4)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cell-occupied"))
                .andExpect(jsonPath("$.detail").value("Cell 4 is already occupied by X"));
    }

    @Test
    void movingOutOfTurnReturns409() throws Exception {
        when(gameService.applyMove(eq(GAME_ID), any()))
                .thenThrow(new IllegalMoveException(MoveRejection.OUT_OF_TURN, "It is X's turn"));

        mockMvc.perform(move(GAME_ID, new MoveRequest(PlayerSymbol.O, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("out-of-turn"));
    }

    @Test
    void movingOnAFinishedGameReturns409() throws Exception {
        when(gameService.applyMove(eq(GAME_ID), any()))
                .thenThrow(new IllegalMoveException(MoveRejection.GAME_ALREADY_FINISHED, "Game already finished"));

        mockMvc.perform(move(GAME_ID, new MoveRequest(PlayerSymbol.O, 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("game-already-finished"));
    }

    @Test
    void aLostRaceReturns409SoTheCallerCanRetry() throws Exception {
        when(gameService.applyMove(eq(GAME_ID), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("games", GAME_ID));

        mockMvc.perform(move(GAME_ID, new MoveRequest(PlayerSymbol.X, 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("concurrent-modification"));
    }

    @Test
    void aPositionOutsideTheBoardIsRejectedByValidationBeforeReachingTheService() throws Exception {
        mockMvc.perform(post("/games/{gameId}/move", GAME_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"X\",\"position\":42}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMissingPositionIsRejected() throws Exception {
        mockMvc.perform(post("/games/{gameId}/move", GAME_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getGameReturnsTheCurrentState() throws Exception {
        when(gameService.getGame(GAME_ID)).thenReturn(inProgress("X---O----"));

        mockMvc.perform(get("/games/{gameId}", GAME_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board").value("X---O----"));
    }

    private org.springframework.test.web.servlet.RequestBuilder move(UUID gameId, MoveRequest request)
            throws Exception {
        return post("/games/{gameId}/move", gameId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
    }

    private GameStateResponse inProgress(String board) {
        long marks = board.chars().filter(c -> c != '-').count();
        return new GameStateResponse(
                GAME_ID,
                board,
                GameStatus.IN_PROGRESS,
                PlayerSymbol.forMoveNumber((int) marks),
                null,
                (int) marks);
    }
}
