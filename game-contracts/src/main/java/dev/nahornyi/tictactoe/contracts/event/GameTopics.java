package dev.nahornyi.tictactoe.contracts.event;

/** Topic names shared by producers and consumers so a rename cannot desynchronise them. */
public final class GameTopics {

    /** Single topic for every {@link GameEvent}, keyed by game id to preserve per-game ordering. */
    public static final String GAME_EVENTS = "game-events";

    private GameTopics() {
    }
}
