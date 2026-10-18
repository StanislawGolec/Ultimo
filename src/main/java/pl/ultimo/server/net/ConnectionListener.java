package pl.ultimo.server.net;

import pl.ultimo.protocol.ClientCommand;

/**
 * Callback listener for lifecycle and messaging events received from a client session.
 */
public interface ConnectionListener {

    void onCommandReceived(ClientSession session, ClientCommand command);

    void onSessionDisconnected(ClientSession session);

    /**
     * Indicates whether the server is actively accepting connections and ready for traffic.
     * When false (e.g. during graceful drain), readiness probes (/ready) return 503.
     */
    default boolean isServerReady() {
        return true;
    }

    /**
     * Triggered when an administrative graceful shutdown is requested.
     */
    default void onShutdownRequested() {
    }
}
