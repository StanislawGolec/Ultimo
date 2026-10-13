package pl.ultimo.server.config;

/**
 * Immutable configuration for the Ultimo TCP server.
 *
 * @param port the TCP port to bind
 * @param defaultRoomCapacity standard maximum players per room (e.g. 4)
 * @param socketReadTimeoutMillis timeout in milliseconds for idle socket reads
 * @param reconnectGracePeriodSeconds grace period for disconnected players before forfeiting
 */
public record ServerConfig(
        int port,
        int defaultRoomCapacity,
        int socketReadTimeoutMillis,
        int reconnectGracePeriodSeconds
) {
    public static ServerConfig defaultConfiguration() {
        return new ServerConfig(8080, 4, 60_000, 30);
    }
}
