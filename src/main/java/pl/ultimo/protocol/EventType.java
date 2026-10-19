package pl.ultimo.protocol;

/**
 * Events and notifications emitted by the authoritative server to clients.
 */
public enum EventType {
    WELCOME,
    ROOM_STATUS,
    HAND_UPDATE,
    TOP_CARD,
    TURN_ANNOUNCED,
    OPPONENT_UPDATE,
    DRAW_RESULT,
    ERROR,
    CHAT_BROADCAST,
    GAME_OVER,
    PONG,
    SERVER_SHUTDOWN,
    ULTIMO_DECLARED,
    ULTIMO_PENALTY,
    CHALLENGE_RESULT,
    ROUND_OVER,
    ROOM_LIST,
    ROOM_OWNER_CHANGED,
    PLAYER_FINISHED,
    MATCH_STANDINGS;
}
