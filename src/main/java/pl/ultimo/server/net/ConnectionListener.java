package pl.ultimo.server.net;

import pl.ultimo.protocol.ClientCommand;

/**
 * Callback listener for lifecycle and messaging events received from a client session.
 */
public interface ConnectionListener {

    void onCommandReceived(ClientSession session, ClientCommand command);

    void onSessionDisconnected(ClientSession session);
}
