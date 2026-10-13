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
    START;
}
