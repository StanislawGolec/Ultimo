package pl.ultimo.server;

import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.metrics.MetricsRegistry;
import pl.ultimo.server.net.ClientSession;
import pl.ultimo.server.net.ConnectionListener;
import pl.ultimo.server.net.SocketAcceptor;
import pl.ultimo.server.room.GameRoom;
import pl.ultimo.server.room.RoomManager;

import java.io.IOException;
import java.net.Socket;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main application bootstrap for the Ultimo backend game server.
 */
public class ServerApp implements ConnectionListener {

    private final ServerConfig config;
    private final RoomManager roomManager;
    private final ExecutorService sessionExecutor;
    private SocketAcceptor acceptor;
    private Thread acceptorThread;

    public ServerApp(ServerConfig config) {
        this.config = config;
        this.roomManager = new RoomManager(config);
        this.sessionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public static void main(String[] args) {
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {
                // Keep default port
            }
        }
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException ignored) {
                // Keep resolved port
            }
        }

        ServerConfig config = new ServerConfig(port, 4, 60_000, 30);
        ServerApp app = new ServerApp(config);
        app.start();
    }

    public void start() {
        System.out.println("Starting Ultimo Server on port " + config.port() + "...");
        this.acceptor = new SocketAcceptor(config, this::handleNewConnection);
        this.acceptorThread = Thread.ofPlatform().name("socket-acceptor").start(acceptor);
    }

    public void stop() {
        if (acceptor != null) {
            acceptor.close();
        }
        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }
        sessionExecutor.shutdownNow();
        roomManager.shutdown();
    }

    public int getBoundPort() {
        return acceptor != null ? acceptor.getBoundPort() : -1;
    }

    private void handleNewConnection(Socket socket) {
        try {
            String sessionId = UUID.randomUUID().toString();
            ClientSession session = new ClientSession(sessionId, socket, this);
            sessionExecutor.submit(session);
        } catch (IOException e) {
            System.err.println("Failed to initialize client session: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void onCommandReceived(ClientSession session, ClientCommand command) {
        if (command instanceof ClientCommand.Join join) {
            long startNanos = System.nanoTime();
            try {
                if (session.getUsername() != null) {
                    session.sendEvent(new ServerEvent.ErrorEvent("Already joined as " + session.getUsername()));
                    return;
                }
                session.setUsername(join.username());
                session.sendEvent(new ServerEvent.Welcome(session.getSessionId(), "Welcome to Ultimo, " + join.username() + "!"));
                roomManager.matchmake(session, join.username());
                return;
            } finally {
                long durationNanos = System.nanoTime() - startNanos;
                MetricsRegistry.getInstance().recordCommandProcessed("JOIN", durationNanos / 1_000_000_000.0);
            }
        }

        if (session.getUsername() == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Must send JOIN <username> before issuing other commands"));
            return;
        }

        Optional<GameRoom> roomOpt = roomManager.getRoomForSession(session.getSessionId());
        if (roomOpt.isPresent()) {
            roomOpt.get().enqueueCommand(session, command);
        } else {
            session.sendEvent(new ServerEvent.ErrorEvent("Not currently in an active game room"));
        }
    }

    @Override
    public void onSessionDisconnected(ClientSession session) {
        roomManager.handleDisconnect(session);
    }

    public RoomManager getRoomManager() {
        return roomManager;
    }
}
