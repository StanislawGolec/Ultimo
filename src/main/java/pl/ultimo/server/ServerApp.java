package pl.ultimo.server;

import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application bootstrap for the Ultimo backend game server.
 */
public class ServerApp implements ConnectionListener {

    private final ServerConfig config;
    private final RoomManager roomManager;
    private final ExecutorService sessionExecutor;
    private final Map<String, ClientSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private SocketAcceptor acceptor;
    private Thread acceptorThread;
    private Thread shutdownHook;

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

        ServerConfig config = ServerConfig.defaultConfiguration();
        if (port != config.port()) {
            config = new ServerConfig(port, config.defaultRoomCapacity(), config.socketReadTimeoutMillis(), config.reconnectGracePeriodSeconds(), config.shutdownDrainSeconds());
        }
        ServerApp app = new ServerApp(config);
        app.start();
    }

    public void start() {
        // Warm up protocol codecs on platform thread to initialize dynamic switch bootstrap tables
        try {
            ProtocolCodec.encodeEvent(new ServerEvent.Pong());
            ProtocolCodec.decodeCommand("PING");
        } catch (Exception ignored) {}

        System.out.println("Starting Ultimo Server on port " + config.port() + "...");
        this.acceptor = new SocketAcceptor(config, this::handleNewConnection);
        this.acceptorThread = Thread.ofPlatform().name("socket-acceptor").start(acceptor);

        this.shutdownHook = new Thread(() -> gracefulShutdown(config.shutdownDrainSeconds()), "ultimo-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public void stop() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already terminating
            }
        }
        gracefulShutdown(0);
    }

    /**
     * Executes the Google SRE / Cloud Run coordinated graceful shutdown sequence:
     * 1. Marks readiness probe as 503 to drop load balancer ingress.
     * 2. Closes SocketAcceptor to reject any incoming TCP handshakes.
     * 3. Broadcasts SERVER_SHUTDOWN <drainSeconds> to all active client sessions.
     * 4. Drains active games within the grace period.
     * 5. Flushes and terminates all client sockets cleanly.
     *
     * @param drainSeconds countdown grace period in seconds
     */
    public void gracefulShutdown(int drainSeconds) {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return;
        }

        System.out.println("Initiating Ultimo Server graceful shutdown (drain window: " + drainSeconds + "s)...");

        // Step 1: Broadcast SERVER_SHUTDOWN warning to all connected sessions
        ServerEvent shutdownEvent = new ServerEvent.ServerShutdown(drainSeconds);
        for (ClientSession session : activeSessions.values()) {
            try {
                session.sendEvent(shutdownEvent);
            } catch (Exception ignored) {
            }
        }

        // Step 2: During the drain window, readiness probe returns 503 Service Unavailable,
        // allowing existing rooms and sessions to drain turns while load balancers cut traffic.
        if (drainSeconds > 0) {
            try {
                Thread.sleep(drainSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Step 3: Reject and stop accepting any incoming connections
        if (acceptor != null) {
            acceptor.close();
        }
        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }

        // Step 4: Close all client sessions and shut down executors
        for (ClientSession session : activeSessions.values()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        activeSessions.clear();

        roomManager.shutdown();
        sessionExecutor.shutdown();
        try {
            if (!sessionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                sessionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sessionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("Ultimo Server graceful shutdown complete.");
    }

    @Override
    public boolean isServerReady() {
        return !isShuttingDown.get() && acceptor != null && acceptor.isRunning();
    }

    public boolean isShuttingDown() {
        return isShuttingDown.get();
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public int getBoundPort() {
        return acceptor != null ? acceptor.getBoundPort() : -1;
    }

    private void handleNewConnection(Socket socket) {
        if (sessionExecutor.isShutdown()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            String sessionId = UUID.randomUUID().toString();
            ClientSession session = new ClientSession(sessionId, socket, this, config.socketReadTimeoutMillis());
            activeSessions.put(sessionId, session);
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
        if (command instanceof ClientCommand.Ping) {
            session.sendEvent(new ServerEvent.Pong());
            return;
        }

        if (command instanceof ClientCommand.ListRooms) {
            java.util.List<ServerEvent.RoomEntry> rooms = roomManager.listPublicRooms();
            session.sendEvent(new ServerEvent.RoomList(rooms));
            return;
        }

        if (command instanceof ClientCommand.CreateRoom create) {
            if (roomManager.getRoomForSession(session.getSessionId()).isPresent()) {
                session.sendEvent(new ServerEvent.ErrorEvent("Already in an active game room"));
                return;
            }
            if (session.getUsername() == null) {
                session.setUsername("Player-" + session.getSessionId().substring(0, 4));
                session.sendEvent(new ServerEvent.Welcome(session.getSessionId(), "Welcome to Ultimo, " + session.getUsername() + "!"));
            }
            int capacity = create.maxPlayers() != null ? create.maxPlayers() : config.defaultRoomCapacity();
            roomManager.createRoom(session, session.getUsername(), create.name(), create.visibility(), create.accessCode(), capacity);
            return;
        }

        if (command instanceof ClientCommand.Join join) {
            long startNanos = System.nanoTime();
            try {
                if (session.getUsername() != null) {
                    session.sendEvent(new ServerEvent.ErrorEvent("Already joined as " + session.getUsername()));
                    return;
                }
                if (!ProtocolCodec.isValidUsername(join.username())) {
                    session.sendEvent(new ServerEvent.ErrorEvent("INVALID_USERNAME: Username must be 2-24 characters using word characters and standard symbols"));
                    return;
                }

                String targetRoomId = join.roomId();
                if (targetRoomId != null && !targetRoomId.isBlank()) {
                    if ("LOBBY".equalsIgnoreCase(targetRoomId) || "NONE".equalsIgnoreCase(targetRoomId)) {
                        session.setUsername(join.username());
                        session.sendEvent(new ServerEvent.Welcome(session.getSessionId(), "Welcome to Ultimo, " + join.username() + "!"));
                        return;
                    }

                    Optional<GameRoom> existingRoomOpt = roomManager.getRoom(targetRoomId);
                    if (existingRoomOpt.isPresent()) {
                        GameRoom targetRoom = existingRoomOpt.get();
                        if (targetRoom.isDisconnected(join.username())) {
                            if (!targetRoom.validateAccess(join.accessCode())) {
                                session.sendEvent(new ServerEvent.ErrorEvent("INVALID_ACCESS_CODE: Room is private and requires valid access code"));
                                return;
                            }
                        } else {
                            if (targetRoom.isFull()) {
                                session.sendEvent(new ServerEvent.ErrorEvent("Targeted room " + targetRoomId + " is full"));
                                return;
                            }
                            if (!targetRoom.validateAccess(join.accessCode())) {
                                session.sendEvent(new ServerEvent.ErrorEvent("INVALID_ACCESS_CODE: Room is private and requires valid access code"));
                                return;
                            }
                            if (targetRoom.hasPlayer(join.username())) {
                                session.sendEvent(new ServerEvent.ErrorEvent("NAME_TAKEN: Player with name '" + join.username() + "' already exists in this room"));
                                return;
                            }
                        }
                    }
                }

                session.setUsername(join.username());
                session.sendEvent(new ServerEvent.Welcome(session.getSessionId(), "Welcome to Ultimo, " + join.username() + "!"));

                int capacity = join.maxPlayers() != null ? join.maxPlayers() : config.defaultRoomCapacity();
                if (targetRoomId != null && !targetRoomId.isBlank()) {
                    roomManager.joinRoom(session, join.username(), targetRoomId, capacity, join.accessCode());
                } else {
                    roomManager.matchmake(session, join.username(), capacity);
                }
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
        if (session != null) {
            activeSessions.remove(session.getSessionId());
        }
        roomManager.handleDisconnect(session);
    }

    @Override
    public void onShutdownRequested() {
        Thread.ofVirtual().name("shutdown-handler").start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            gracefulShutdown(config.shutdownDrainSeconds());
        });
    }

    public RoomManager getRoomManager() {
        return roomManager;
    }
}
