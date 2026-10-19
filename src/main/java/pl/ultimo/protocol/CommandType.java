package pl.ultimo.protocol;

/**
 * Commands initiated by the client and sent to the server.
 */
public enum CommandType {
    JOIN,
    PLAY,
    DRAW,
    PASS,
    CHAT,
    PING,
    START,
    ULTIMO,
    CATCH_ULTIMO,
    CHALLENGE_WILD_FOUR,
    CREATE_ROOM,
    LIST_ROOMS,
    KICK,
    TRANSFER_OWNERSHIP,
    QUIT,
    RECONNECT;
}
