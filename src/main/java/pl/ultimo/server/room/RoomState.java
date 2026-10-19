package pl.ultimo.server.room;

/**
 * Lifecycle states of an Ultimo game room.
 */
public enum RoomState {
    WAITING_FOR_PLAYERS,
    IN_PROGRESS,
    ROUND_INTERMISSION,
    FINISHED;
}
