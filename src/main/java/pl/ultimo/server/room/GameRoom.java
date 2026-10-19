package pl.ultimo.server.room;

import pl.ultimo.core.bot.BotAction;
import pl.ultimo.core.bot.BotDecision;
import pl.ultimo.core.bot.HeuristicBotEngine;
import pl.ultimo.core.engine.GameResult;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.GameState;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.RoomVisibility;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.metrics.MetricsRegistry;
import pl.ultimo.server.net.ClientSession;
import pl.ultimo.server.net.ConnectionListener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Encapsulates an active multiplayer Ultimo game match as a pure single-writer Actor.
 * Processes all incoming {@link RoomEnvelope} messages sequentially on a dedicated room virtual thread,
 * eliminating hybrid locks and synchronized monitors to guarantee zero Virtual Thread carrier pinning.
 */
public class GameRoom implements Runnable {

    private final String roomId;
    private final String roomName;
    private final RoomVisibility visibility;
    private final String accessCode;
    private final int maxPlayers;
    private final Random random;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Consumer<ClientSession> onPlayerLeave;

    private final List<ClientSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Player> players = new CopyOnWriteArrayList<>();
    private final Map<String, Player> sessionPlayerMap = new ConcurrentHashMap<>();
    private final Map<String, String> playerReconnectTokens = new ConcurrentHashMap<>();
    private final BlockingQueue<RoomEnvelope> commandQueue = new LinkedBlockingQueue<>();
    private final Map<RoomEnvelope, Queue<CompletableFuture<Void>>> completionTrackers = new ConcurrentHashMap<>();
    private final List<BotSession> botSessions = new CopyOnWriteArrayList<>();

    private final AtomicBoolean isRunning = new AtomicBoolean(true);
    private final AtomicBoolean actorLoopActive = new AtomicBoolean(false);
    private volatile Thread actorThread;

    private volatile String ownerUsername;
    private volatile RoomState state = RoomState.WAITING_FOR_PLAYERS;
    private volatile UltimoEngine engine;
    private volatile int targetScore = UltimoEngine.DEFAULT_TARGET_SCORE;

    public record DisconnectedPlayerRecord(Player player, String reconnectToken, long disconnectedAt) {
        public DisconnectedPlayerRecord(Player player, long disconnectedAt) {
            this(player, null, disconnectedAt);
        }
    }

    public static final long DEFAULT_RECONNECT_GRACE_PERIOD_MS = 120_000L;
    public static final long DEFAULT_AFK_TURN_TIMEOUT_MS = 120_000L;
    public static final long DEFAULT_WATCHDOG_CHECK_INTERVAL_MS = 5_000L;
    public static final long DEFAULT_ROUND_INTERMISSION_MS = 10_000L;

    private final Map<String, DisconnectedPlayerRecord> disconnectedPlayers = new ConcurrentHashMap<>();
    private final AtomicBoolean isWatchdogRunning = new AtomicBoolean(false);
    private volatile Thread watchdogThread;
    private volatile long reconnectGracePeriodMs = DEFAULT_RECONNECT_GRACE_PERIOD_MS;
    private volatile long afkTurnTimeoutMs = DEFAULT_AFK_TURN_TIMEOUT_MS;
    private volatile long watchdogCheckIntervalMs = DEFAULT_WATCHDOG_CHECK_INTERVAL_MS;
    private volatile long roundIntermissionMs = DEFAULT_ROUND_INTERMISSION_MS;
    private volatile long intermissionStartedTimestamp = 0L;
    private volatile long turnStartedTimestamp = 0L;
    private final Map<String, Integer> consecutiveAfkTurns = new ConcurrentHashMap<>();
    private volatile String currentTurnPlayer = null;
    private Consumer<GameRoom> onRoomFinished;

    public static final int MIN_CAPACITY = 2;
    public static final int MAX_CAPACITY = 16;

    public record RoomCommandEvent(ClientSession session, ClientCommand command) {}

    public GameRoom(String roomId, int maxPlayers) {
        this(roomId, maxPlayers, new Random());
    }

    public GameRoom(String roomId, int maxPlayers, Random random) {
        this(roomId, roomId, RoomVisibility.PUBLIC, null, maxPlayers, random, null);
    }

    public GameRoom(String roomId, String roomName, RoomVisibility visibility, String accessCode, int maxPlayers) {
        this(roomId, roomName, visibility, accessCode, maxPlayers, new Random(), null);
    }

    public GameRoom(String roomId, String roomName, RoomVisibility visibility, String accessCode, int maxPlayers, Random random) {
        this(roomId, roomName, visibility, accessCode, maxPlayers, random, null);
    }

    public GameRoom(String roomId, String roomName, RoomVisibility visibility, String accessCode, int maxPlayers, Random random, Consumer<ClientSession> onPlayerLeave) {
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.roomName = (roomName != null && !roomName.isBlank()) ? roomName.trim() : roomId;
        this.visibility = visibility != null ? visibility : RoomVisibility.PUBLIC;
        this.accessCode = (accessCode != null && !accessCode.isBlank()) ? accessCode.trim() : null;
        if (maxPlayers < MIN_CAPACITY || maxPlayers > MAX_CAPACITY) {
            throw new IllegalArgumentException("Room capacity must be between " + MIN_CAPACITY + " and " + MAX_CAPACITY + " players");
        }
        this.maxPlayers = maxPlayers;
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.onPlayerLeave = onPlayerLeave;
    }

    public BlockingQueue<RoomEnvelope> getCommandQueue() {
        return commandQueue;
    }

    public void submitEnvelope(RoomEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (isRunning.get()) {
            commandQueue.offer(envelope);
        } else {
            failEnvelopeFutureIfStopped(envelope);
        }
    }

    private void failEnvelopeFutureIfStopped(RoomEnvelope envelope) {
        switch (envelope) {
            case RoomEnvelope.JoinEnvelope join -> {
                if (join.future() != null) join.future().complete(false);
            }
            case RoomEnvelope.ReconnectEnvelope rec -> {
                if (rec.future() != null) rec.future().complete(false);
            }
            case RoomEnvelope.AddBotEnvelope bot -> {
                if (bot.future() != null) bot.future().complete(false);
            }
            default -> {}
        }
    }

    private String generateReconnectToken() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String getReconnectToken(String username) {
        if (username == null) {
            return null;
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        DisconnectedPlayerRecord rec = disconnectedPlayers.get(key);
        if (rec != null && rec.reconnectToken() != null) {
            return rec.reconnectToken();
        }
        return playerReconnectTokens.get(key);
    }

    public boolean validateAccess(String code) {
        if (visibility != RoomVisibility.PRIVATE) {
            return true;
        }
        if (this.accessCode == null || this.accessCode.isBlank()) {
            return true;
        }
        return this.accessCode.equals(code != null ? code.trim() : null);
    }

    /**
     * Checks if a player with the specified username (case-insensitive) is already in the room.
     */
    public boolean hasPlayer(String username) {
        if (username == null) {
            return false;
        }
        String trimmed = username.trim();
        return players.stream().anyMatch(p -> p.getUsername().equalsIgnoreCase(trimmed));
    }

    public boolean isDisconnected(String username) {
        if (username == null) {
            return false;
        }
        return disconnectedPlayers.containsKey(username.trim().toLowerCase(Locale.ROOT));
    }

    public DisconnectedPlayerRecord getDisconnectedPlayer(String username) {
        if (username == null) {
            return null;
        }
        return disconnectedPlayers.get(username.trim().toLowerCase(Locale.ROOT));
    }

    public Map<String, DisconnectedPlayerRecord> getDisconnectedPlayers() {
        return Map.copyOf(disconnectedPlayers);
    }

    public int getConnectedSessionCount() {
        return sessions.size();
    }

    public List<String> getConnectedPlayerNames() {
        List<String> names = new ArrayList<>();
        for (ClientSession session : sessions) {
            Player p = sessionPlayerMap.get(session.getSessionId());
            if (p != null) {
                names.add(p.getUsername());
            }
        }
        return names;
    }

    public boolean addPlayer(ClientSession session, String username) {
        return addPlayer(session, username, null);
    }

    /**
     * Enrolls a player into the room by submitting a {@link RoomEnvelope.JoinEnvelope} to the room mailbox.
     */
    public boolean addPlayer(ClientSession session, String username, String code) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(username, "username must not be null");

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        submitEnvelope(new RoomEnvelope.JoinEnvelope(session, username, code, future));
        return awaitEnvelopeFuture(future, false);
    }

    private boolean handleJoinInternal(ClientSession session, String username, String code) {
        if (session == null || username == null) {
            return false;
        }

        if (!validateAccess(code)) {
            session.sendEvent(new ServerEvent.ErrorEvent("INVALID_ACCESS_CODE: Room is private and requires valid access code"));
            return false;
        }

        if (isDisconnected(username)) {
            String storedToken = getReconnectToken(username);
            return handleReconnectInternal(session, username, storedToken);
        }

        String trimmedUsername = username.trim();
        if (players.stream().anyMatch(p -> p.getUsername().equalsIgnoreCase(trimmedUsername))) {
            session.sendEvent(new ServerEvent.ErrorEvent("NAME_TAKEN: Player with name '" + username + "' already exists in this room"));
            return false;
        }

        if (state != RoomState.WAITING_FOR_PLAYERS || players.size() >= maxPlayers) {
            return false;
        }

        Player player = new Player(session.getSessionId(), username);
        String token = generateReconnectToken();
        playerReconnectTokens.put(trimmedUsername.toLowerCase(Locale.ROOT), token);

        if (players.isEmpty()) {
            this.ownerUsername = player.getUsername();
        }
        players.add(player);
        sessions.add(session);
        sessionPlayerMap.put(session.getSessionId(), player);

        int currentCount = players.size();
        List<String> playerNamesSnapshot = players.stream().map(Player::getUsername).toList();
        boolean shouldStart = (currentCount == maxPlayers);

        broadcast(new ServerEvent.RoomStatus(roomId, currentCount, maxPlayers, playerNamesSnapshot));
        if (this.accessCode != null && !this.accessCode.isBlank()) {
            session.sendEvent(new ServerEvent.ChatBroadcast("GAME", "Room Access Code: " + accessCode));
        }

        if (shouldStart) {
            startGameInternal();
        }

        return true;
    }

    /**
     * Rebinds a newly connected client session to an existing disconnected player entity,
     * using the player's stored cryptographic token.
     */
    public boolean reconnectPlayer(ClientSession session, String username) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(username, "username must not be null");
        String storedToken = getReconnectToken(username);
        return reconnectPlayer(session, username, storedToken);
    }

    /**
     * Rebinds a newly connected client session to an existing player entity who was disconnected
     * during an active match within the 2-minute grace period, validating their cryptographic reconnectToken.
     */
    public boolean reconnectPlayer(ClientSession session, String username, String token) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(username, "username must not be null");

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        submitEnvelope(new RoomEnvelope.ReconnectEnvelope(session, username, token, future));
        return awaitEnvelopeFuture(future, false);
    }

    private boolean handleReconnectInternal(ClientSession session, String username, String token) {
        if (session == null || username == null) {
            return false;
        }

        String key = username.trim().toLowerCase(Locale.ROOT);
        DisconnectedPlayerRecord record = disconnectedPlayers.get(key);
        if (record == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("RECONNECT_FAILED: No disconnected session found for player '" + username + "'"));
            return false;
        }

        String expectedToken = record.reconnectToken();
        if (expectedToken != null) {
            if (token == null || token.isBlank() || !MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    token.trim().getBytes(StandardCharsets.UTF_8))) {
                session.sendEvent(new ServerEvent.ErrorEvent("INVALID_RECONNECT_TOKEN: Invalid cryptographic reconnect token for player '" + username + "'"));
                return false;
            }
        }

        disconnectedPlayers.remove(key);
        Player existingPlayer = record.player();

        sessions.add(session);
        sessionPlayerMap.put(session.getSessionId(), existingPlayer);
        session.setUsername(existingPlayer.getUsername());

        GameState snapshot = engine != null ? engine.captureSnapshot() : null;
        List<Player> allPlayersSnapshot = List.copyOf(players);
        List<String> connectedNamesSnapshot = getConnectedPlayerNames();
        int connectedCount = sessions.size();

        if (snapshot != null) {
            session.sendEvent(new ServerEvent.HandUpdate(List.copyOf(existingPlayer.getHand().getCards())));
            session.sendEvent(new ServerEvent.TopCard(snapshot.topCard(), snapshot.activeColor()));
            session.sendEvent(new ServerEvent.TurnAnnounced(snapshot.activePlayerName()));

            for (Player other : allPlayersSnapshot) {
                if (!other.equals(existingPlayer)) {
                    session.sendEvent(new ServerEvent.OpponentUpdate(other.getUsername(), other.getHand().size()));
                }
            }
        }

        broadcast(new ServerEvent.ChatBroadcast("GAME", existingPlayer.getUsername() + " has reconnected!"));
        broadcast(new ServerEvent.RoomStatus(roomId, connectedCount, maxPlayers, connectedNamesSnapshot));

        return true;
    }

    /**
     * Enrolls an in-memory autonomous heuristic bot into the room via {@link RoomEnvelope.AddBotEnvelope}.
     */
    public boolean addBot(String botName) {
        return addBot(botName, "NORMAL");
    }

    public boolean addBot(String botName, String difficulty) {
        Objects.requireNonNull(botName, "botName must not be null");
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        submitEnvelope(new RoomEnvelope.AddBotEnvelope(botName, difficulty, future));
        return awaitEnvelopeFuture(future, false);
    }

    private boolean handleAddBotInternal(String botName, String difficulty) {
        if (botName == null || botName.isBlank()) {
            return false;
        }
        String cleanName = botName.trim();
        if (state != RoomState.WAITING_FOR_PLAYERS || players.size() >= maxPlayers) {
            return false;
        }
        if (players.stream().anyMatch(p -> p.getUsername().equalsIgnoreCase(cleanName))) {
            return false;
        }

        try {
            String botSessionId = "bot-" + UUID.randomUUID();
            BotSession botSession = new BotSession(botSessionId, cleanName, difficulty != null ? difficulty : "NORMAL");
            botSessions.add(botSession);
            botSession.startBotWorker();

            boolean added = handleJoinInternal(botSession, cleanName, this.accessCode);
            if (!added) {
                botSessions.remove(botSession);
                botSession.close();
            }
            return added;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Explicitly starts the match if at least 2 players have joined.
     */
    public boolean startGame() {
        if (Thread.currentThread() == actorThread || !actorLoopActive.get()) {
            return startGameInternal();
        }
        Player ownerPlayer = null;
        ClientSession ownerSession = null;
        for (ClientSession s : sessions) {
            Player p = sessionPlayerMap.get(s.getSessionId());
            if (p != null && p.getUsername().equalsIgnoreCase(ownerUsername)) {
                ownerPlayer = p;
                ownerSession = s;
                break;
            }
        }
        if (ownerSession != null && ownerPlayer != null) {
            RoomEnvelope.CommandEnvelope env = new RoomEnvelope.CommandEnvelope(ownerSession, new ClientCommand.Start());
            CompletableFuture<Void> done = registerCompletionTracker(env);
            submitEnvelope(env);
            awaitEnvelopeFuture(done, null);
            return state == RoomState.IN_PROGRESS;
        }
        return startGameInternal();
    }

    private boolean startGameInternal() {
        if (state != RoomState.WAITING_FOR_PLAYERS || players.size() < 2) {
            return false;
        }

        this.engine = new UltimoEngine(players, random, targetScore, true, true);
        this.engine.start();
        this.state = RoomState.IN_PROGRESS;
        this.intermissionStartedTimestamp = 0L;
        this.turnStartedTimestamp = System.currentTimeMillis();
        if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
            this.currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
        }

        broadcast(new ServerEvent.ChatBroadcast("GAME", "Game has started! Good luck."));
        broadcastGameState();
        startWatchdogIfNeeded();

        return true;
    }

    private boolean startNextRoundInternal() {
        if (state != RoomState.ROUND_INTERMISSION || engine == null) {
            return false;
        }
        boolean started = engine.startNextRound();
        if (!started) {
            return false;
        }

        this.state = RoomState.IN_PROGRESS;
        this.intermissionStartedTimestamp = 0L;
        this.turnStartedTimestamp = System.currentTimeMillis();
        if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
            this.currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
        }

        broadcast(new ServerEvent.ChatBroadcast("GAME", "Next round has started! Good luck."));
        broadcastGameState();
        startWatchdogIfNeeded();

        return true;
    }

    private void scheduleIntermissionTimer() {
        long delayMs = this.roundIntermissionMs;
        long startedAt = this.intermissionStartedTimestamp;
        Thread.ofVirtual().name("intermission-" + roomId).start(() -> {
            try {
                Thread.sleep(delayMs);
                if (isRunning.get() && state == RoomState.ROUND_INTERMISSION) {
                    long tickNow = Math.max(System.currentTimeMillis(), startedAt + delayMs);
                    submitEnvelope(new RoomEnvelope.WatchdogTickEnvelope(tickNow));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Enqueues an incoming command to be processed sequentially on the room's event loop.
     */
    public void enqueueCommand(ClientSession session, ClientCommand command) {
        submitEnvelope(new RoomEnvelope.CommandEnvelope(session, command));
    }

    @Override
    public void run() {
        actorThread = Thread.currentThread();
        actorLoopActive.set(true);
        try {
            while (isRunning.get()) {
                try {
                    RoomEnvelope envelope = commandQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (envelope != null) {
                        processEnvelope(envelope);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error processing envelope in room " + roomId + ": " + e.getMessage());
                }
            }
        } finally {
            actorLoopActive.set(false);
        }
    }

    public void processEnvelope(RoomEnvelope envelope) {
        try {
            switch (envelope) {
                case RoomEnvelope.CommandEnvelope cmd -> processCommand(cmd.session(), cmd.command());
                case RoomEnvelope.JoinEnvelope join -> {
                    try {
                        boolean ok = handleJoinInternal(join.session(), join.username(), join.accessCode());
                        if (join.future() != null) {
                            join.future().complete(ok);
                        }
                    } catch (Throwable t) {
                        if (join.future() != null) {
                            join.future().completeExceptionally(t);
                        }
                    }
                }
                case RoomEnvelope.ReconnectEnvelope rec -> {
                    try {
                        boolean ok = handleReconnectInternal(rec.session(), rec.username(), rec.token());
                        if (rec.future() != null) {
                            rec.future().complete(ok);
                        }
                    } catch (Throwable t) {
                        if (rec.future() != null) {
                            rec.future().completeExceptionally(t);
                        }
                    }
                }
                case RoomEnvelope.DisconnectEnvelope disc -> handleDisconnectInternal(disc.session());
                case RoomEnvelope.WatchdogTickEnvelope tick -> handleWatchdogTickInternal(tick.nowMillis());
                case RoomEnvelope.AddBotEnvelope bot -> {
                    try {
                        boolean ok = handleAddBotInternal(bot.botName(), bot.difficulty());
                        if (bot.future() != null) {
                            bot.future().complete(ok);
                        }
                    } catch (Throwable t) {
                        if (bot.future() != null) {
                            bot.future().completeExceptionally(t);
                        }
                    }
                }
            }
        } finally {
            Queue<CompletableFuture<Void>> trackers = completionTrackers.get(envelope);
            if (trackers != null) {
                CompletableFuture<Void> done = trackers.poll();
                if (done != null) {
                    done.complete(null);
                }
                if (trackers.isEmpty()) {
                    completionTrackers.remove(envelope, trackers);
                }
            }
        }
    }

    private CompletableFuture<Void> registerCompletionTracker(RoomEnvelope envelope) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        completionTrackers.computeIfAbsent(envelope, k -> new ConcurrentLinkedQueue<>()).add(done);
        return done;
    }

    private <T> T awaitEnvelopeFuture(CompletableFuture<T> future, T defaultValue) {
        if (!isRunning.get() && !future.isDone()) {
            return defaultValue;
        }
        if (Thread.currentThread() == actorThread) {
            while (!future.isDone() && isRunning.get()) {
                RoomEnvelope next = commandQueue.poll();
                if (next == null) {
                    break;
                }
                processEnvelope(next);
            }
            return future.getNow(defaultValue);
        }

        try {
            if (future.isDone()) {
                return future.get();
            }
            long waitStart = System.nanoTime();
            while (!future.isDone() && isRunning.get()) {
                if (actorLoopActive.get()) {
                    try {
                        return future.get(10, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException ignored) {
                        // Continue waiting while actor loop is active
                    }
                } else {
                    try {
                        return future.get(1, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException ignored) {
                    }
                    if (actorLoopActive.get()) {
                        continue;
                    }
                    if (System.nanoTime() - waitStart > 3_000_000L) {
                        RoomEnvelope next = commandQueue.poll();
                        if (next != null) {
                            processEnvelope(next);
                        } else {
                            break;
                        }
                    } else {
                        Thread.yield();
                    }
                }
            }
            if (future.isDone()) {
                return future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e.getCause());
        }
        return defaultValue;
    }

    private void processCommand(ClientSession session, ClientCommand command) {
        long startNanos = System.nanoTime();
        try {
            Player player = sessionPlayerMap.get(session.getSessionId());

            switch (command) {
                case ClientCommand.Start start -> handleStart(session);
                case ClientCommand.Kick kick -> handleKick(session, kick);
                case ClientCommand.TransferOwnership to -> handleTransferOwnership(session, to);
                case ClientCommand.Play play -> handlePlay(session, player, play);
                case ClientCommand.Draw draw -> handleDraw(session, player);
                case ClientCommand.Pass pass -> handlePass(session, player);
                case ClientCommand.Chat chat -> handleChat(session, chat);
                case ClientCommand.Ping ping -> session.sendEvent(new ServerEvent.Pong());
                case ClientCommand.Ultimo ultimo -> handleUltimo(session, player);
                case ClientCommand.CatchUltimo catchUltimo -> handleCatchUltimo(session, player, catchUltimo);
                case ClientCommand.ChallengeWildFour challenge -> handleChallengeWildFour(session, player);
                default -> session.sendEvent(new ServerEvent.ErrorEvent("Unsupported command in room: " + command.type()));
            }
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            MetricsRegistry.getInstance().recordCommandProcessed(command.type().name(), durationNanos / 1_000_000_000.0);
        }
    }

    private void handleStart(ClientSession session) {
        Player caller = sessionPlayerMap.get(session.getSessionId());
        if (caller == null || ownerUsername == null || !caller.getUsername().equalsIgnoreCase(ownerUsername)) {
            session.sendEvent(new ServerEvent.ErrorEvent("Only room owner can perform this action"));
            return;
        }

        if (state == RoomState.ROUND_INTERMISSION) {
            startNextRoundInternal();
            return;
        }

        if (state == RoomState.IN_PROGRESS) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is already in progress"));
            return;
        }

        if (state != RoomState.WAITING_FOR_PLAYERS) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot start match in current room state: " + state));
            return;
        }

        if (getPlayerCount() < 2) {
            session.sendEvent(new ServerEvent.ErrorEvent("Need at least 2 players to start match"));
            return;
        }

        startGameInternal();
    }

    private void handleKick(ClientSession session, ClientCommand.Kick kick) {
        Player caller = sessionPlayerMap.get(session.getSessionId());
        if (caller == null || ownerUsername == null || !caller.getUsername().equalsIgnoreCase(ownerUsername)) {
            session.sendEvent(new ServerEvent.ErrorEvent("Only room owner can perform this action"));
            return;
        }
        String targetName = kick.targetPlayer();
        if (targetName == null || targetName.isBlank() || targetName.equalsIgnoreCase(ownerUsername)) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot kick yourself or empty target"));
            return;
        }
        ClientSession targetSession = null;
        Player targetPlayer = null;
        for (Player p : players) {
            if (p.getUsername().equalsIgnoreCase(targetName.trim())) {
                targetPlayer = p;
                break;
            }
        }
        if (targetPlayer != null) {
            for (ClientSession s : sessions) {
                if (targetPlayer.equals(sessionPlayerMap.get(s.getSessionId()))) {
                    targetSession = s;
                    break;
                }
            }
        }

        if (targetPlayer == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Player '" + targetName + "' not found in room"));
            return;
        }

        if (targetSession != null) {
            targetSession.sendEvent(new ServerEvent.ErrorEvent("You have been kicked from the room by the host"));
            broadcast(new ServerEvent.ChatBroadcast("GAME", targetPlayer.getUsername() + " was kicked by the room owner."));
            handleDisconnectInternal(targetSession);
        } else {
            broadcast(new ServerEvent.ChatBroadcast("GAME", targetPlayer.getUsername() + " was kicked by the room owner."));
            disconnectedPlayers.remove(targetPlayer.getUsername().toLowerCase(Locale.ROOT));
            handleExpiredPlayer(targetPlayer);
        }
    }

    private void handleTransferOwnership(ClientSession session, ClientCommand.TransferOwnership to) {
        Player caller = sessionPlayerMap.get(session.getSessionId());
        if (caller == null || ownerUsername == null || !caller.getUsername().equalsIgnoreCase(ownerUsername)) {
            session.sendEvent(new ServerEvent.ErrorEvent("Only room owner can perform this action"));
            return;
        }
        String targetName = to.targetPlayer();
        if (targetName == null || targetName.isBlank() || targetName.equalsIgnoreCase(ownerUsername)) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot transfer ownership to yourself"));
            return;
        }
        boolean found = false;
        for (Player p : players) {
            if (p.getUsername().equalsIgnoreCase(targetName.trim())) {
                this.ownerUsername = p.getUsername();
                found = true;
                break;
            }
        }

        if (!found) {
            session.sendEvent(new ServerEvent.ErrorEvent("Player '" + targetName + "' not found in room"));
            return;
        }

        broadcast(new ServerEvent.RoomOwnerChanged(this.ownerUsername));
        broadcast(new ServerEvent.ChatBroadcast("GAME", "Room ownership transferred to " + this.ownerUsername));
    }

    private void handlePlay(ClientSession session, Player player, ClientCommand.Play play) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Player not recognized in this room"));
            return;
        }

        UltimoEngine.PlayResult result = engine.playCards(player, play.cards(), play.chosenWildColor());
        if (!result.success()) {
            session.sendEvent(new ServerEvent.ErrorEvent(result.errorMessage()));
            return;
        }

        consecutiveAfkTurns.remove(player.getUsername().toLowerCase(Locale.ROOT));
        turnStartedTimestamp = System.currentTimeMillis();

        if (play.cards().size() > 1) {
            broadcast(new ServerEvent.ChatBroadcast("GAME",
                    player.getUsername() + " played a combo of " + play.cards().size() + " cards!"));
        }

        if (!result.isGameOver() && !play.cards().isEmpty()) {
            Card lastPlayed = play.cards().get(play.cards().size() - 1);
            if (lastPlayed.value() == CardValue.DRAW_TWO) {
                int cardsCount = 2 * play.cards().size();
                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        "PENALTY STACK: +" + cardsCount + " CARDS! Targeted at " + engine.getTurnManager().getCurrentPlayer().getUsername()));
            } else if (lastPlayed.value() == CardValue.WILD_DRAW_FOUR) {
                int cardsCount = 4 * play.cards().size();
                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        "PENALTY STACK: +" + cardsCount + " CARDS! Targeted at " + engine.getTurnManager().getCurrentPlayer().getUsername()));
            } else if (lastPlayed.value() == CardValue.SKIP) {
                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        "SKIP DEFLECTED! Shifted to " + engine.getTurnManager().getCurrentPlayer().getUsername() + "!"));
            }
        }

        if (result.isUltimo()) {
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " declared ULTIMO! (1 card remaining)"));
        }

        if (player.isFinished() && player.getPlacementRank() > 0) {
            broadcast(new ServerEvent.PlayerFinished(player.getUsername(), player.getPlacementRank()));
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " finished in place #" + player.getPlacementRank() + "!"));
        }

        if (result.isGameOver()) {
            for (Player p : engine.getFinishedPlayers()) {
                if (!p.equals(player)) {
                    broadcast(new ServerEvent.PlayerFinished(p.getUsername(), p.getPlacementRank()));
                    broadcast(new ServerEvent.ChatBroadcast("GAME", p.getUsername() + " finished in place #" + p.getPlacementRank() + "!"));
                }
            }

            Optional<GameResult> resultOpt = engine.getResult();
            if (resultOpt.isPresent()) {
                GameResult gr = resultOpt.get();
                broadcast(new ServerEvent.RoundOver(gr.winner().getUsername(), gr.roundPoints(), gr.cumulativeScores(), gr.isTournamentWon()));
                broadcast(new ServerEvent.ChatBroadcast("GAME", gr.winner().getUsername() + " won the round! Points earned: +" + gr.roundPoints()));
            }

            if (!engine.getMatchStandings().isEmpty()) {
                broadcast(new ServerEvent.MatchStandings(engine.getMatchStandings()));
            }

            if (engine.isTournamentWon()) {
                this.state = RoomState.FINISHED;
                broadcastGameState();
                Player tournamentWinner = engine.getTournamentWinner();
                String winnerName = tournamentWinner != null
                        ? tournamentWinner.getUsername()
                        : (result.winner() != null ? result.winner().getUsername() : player.getUsername());
                broadcast(new ServerEvent.GameOver(winnerName));
            } else {
                this.state = RoomState.ROUND_INTERMISSION;
                this.intermissionStartedTimestamp = System.currentTimeMillis();
                broadcastGameState();
                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        "Round intermission! Next round starts in " + Math.max(1, roundIntermissionMs / 1000) + "s or when host sends START."));
                scheduleIntermissionTimer();
                startWatchdogIfNeeded();
            }
            return;
        }

        broadcastGameState();
    }

    private void handleUltimo(ClientSession session, Player player) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            return;
        }

        boolean declared = engine.declareUltimo(player);
        if (declared) {
            broadcast(new ServerEvent.UltimoDeclared(player.getUsername()));
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " declared ULTIMO!"));
        } else {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot declare ULTIMO (hand size must be 1 or 2 cards)"));
        }
    }

    private void handleCatchUltimo(ClientSession session, Player player, ClientCommand.CatchUltimo cmd) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            return;
        }

        UltimoEngine.CatchResult res = engine.catchUltimo(player, cmd.targetUsername());
        if (res.success()) {
            broadcast(new ServerEvent.UltimoPenalty(res.penalizedPlayer().getUsername(), player.getUsername(), res.penaltyCards()));
            broadcast(new ServerEvent.ChatBroadcast("GAME", res.message()));
            broadcastGameState();
        } else {
            session.sendEvent(new ServerEvent.ErrorEvent(res.message()));
        }
    }

    private void handleChallengeWildFour(ClientSession session, Player player) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            return;
        }

        UltimoEngine.WildChallengeResult res = engine.challengeWildFour(player);
        if (res.success()) {
            consecutiveAfkTurns.remove(player.getUsername().toLowerCase(Locale.ROOT));
            turnStartedTimestamp = System.currentTimeMillis();
            broadcast(new ServerEvent.ChallengeResult(player.getUsername(), res.penalizedPlayer().getUsername(), res.isGuilty(), res.penalizedPlayer().getUsername(), res.cardsDrawn()));
            broadcast(new ServerEvent.ChatBroadcast("GAME", res.message()));
            broadcastGameState();
        } else {
            session.sendEvent(new ServerEvent.ErrorEvent(res.message()));
        }
    }

    private void handleDraw(ClientSession session, Player player) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            return;
        }

        UltimoEngine.DrawResult drawRes = engine.drawCardWithResult(player);
        if (drawRes.cardsDrawn() == 0 && drawRes.card().isEmpty()) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot draw (either not your turn or already drawn this turn)"));
            return;
        }

        consecutiveAfkTurns.remove(player.getUsername().toLowerCase(Locale.ROOT));
        turnStartedTimestamp = System.currentTimeMillis();

        if (drawRes.cardsDrawn() > 1) {
            broadcast(new ServerEvent.ChatBroadcast("GAME",
                    player.getUsername() + " accepted penalty and drew " + drawRes.cardsDrawn() + " cards."));
            broadcastGameState();
            return;
        }

        if (drawRes.card().isPresent()) {
            session.sendEvent(new ServerEvent.DrawResult(drawRes.card().get()));
        }

        if (drawRes.autoPassed()) {
            broadcast(new ServerEvent.ChatBroadcast("GAME",
                    player.getUsername() + " drew a card (" + drawRes.card().orElse(null) + ") but has no playable moves. Turn passed."));
        }

        broadcastGameState();
    }

    private void handlePass(ClientSession session, Player player) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            return;
        }

        boolean wasSkipVictim = engine.isActionDefendingEnabled() && engine.isPendingSkipDeflection()
                && player.equals(engine.getSkipTarget());
        boolean wasDrawVictim = engine.isActionDefendingEnabled() && engine.getPendingDrawPenalty() > 0
                && player.equals(engine.getDrawTarget());

        boolean passed = engine.passTurn(player);
        if (!passed) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot pass (must draw before passing, or not your turn)"));
            return;
        }

        consecutiveAfkTurns.remove(player.getUsername().toLowerCase(Locale.ROOT));
        turnStartedTimestamp = System.currentTimeMillis();

        if (wasSkipVictim) {
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " was skipped!"));
        } else if (wasDrawVictim) {
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " accepted penalty and drew cards."));
        } else {
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " passed their turn."));
        }
        broadcastGameState();
    }

    private void handleChat(ClientSession session, ClientCommand.Chat chat) {
        String msg = chat.message() != null ? chat.message().trim() : "";
        if ("ADMIN_SETUP_DEFENSE".equals(msg)) {
            if (engine != null) {
                engine.setupDefenseScenario();
                turnStartedTimestamp = System.currentTimeMillis();
                if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
                    currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
                }
                broadcastGameState();
            }
            return;
        }
        if ("ADMIN_TRIGGER_PODIUM".equals(msg)) {
            if (engine != null) {
                engine.setupPodiumScenario();
                turnStartedTimestamp = System.currentTimeMillis();
                if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
                    currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
                }
                broadcastGameState();
            }
            return;
        }
        String senderName = session.getUsername() != null ? session.getUsername() : "Player";
        broadcast(new ServerEvent.ChatBroadcast(senderName, chat.message()));
    }

    /**
     * Broadcasts authoritative game state with Fog of War protections:
     * - Table status (top card, active color, turn) is public.
     * - Hands are strictly private to each individual player.
     * - Opponents only receive anonymized card count updates.
     */
    public void broadcastGameState() {
        if (engine == null) {
            return;
        }

        GameState snapshot = engine.captureSnapshot();
        String activePlayer = snapshot.activePlayerName();
        if (activePlayer != null && !activePlayer.equals(currentTurnPlayer)) {
            currentTurnPlayer = activePlayer;
            turnStartedTimestamp = System.currentTimeMillis();
        }

        // 1. Public table state
        broadcast(new ServerEvent.TopCard(snapshot.topCard(), snapshot.activeColor()));
        broadcast(new ServerEvent.TurnAnnounced(snapshot.activePlayerName()));

        // 2. Secret hand update and opponent counts
        record HandDelivery(ClientSession session, List<Card> handCards, List<ServerEvent.OpponentUpdate> oppUpdates) {}
        List<HandDelivery> deliveries = new ArrayList<>();

        for (ClientSession targetSession : sessions) {
            Player target = sessionPlayerMap.get(targetSession.getSessionId());
            if (target == null) {
                continue;
            }
            List<Card> handCards = List.copyOf(target.getHand().getCards());

            List<ServerEvent.OpponentUpdate> oppUpdates = new ArrayList<>();
            for (Player other : players) {
                if (!other.equals(target)) {
                    oppUpdates.add(new ServerEvent.OpponentUpdate(other.getUsername(), other.getHand().size()));
                }
            }
            deliveries.add(new HandDelivery(targetSession, handCards, oppUpdates));
        }

        // 3. Deliveries
        for (HandDelivery delivery : deliveries) {
            delivery.session().sendEvent(new ServerEvent.HandUpdate(delivery.handCards()));
            for (ServerEvent.OpponentUpdate oppUpdate : delivery.oppUpdates()) {
                delivery.session().sendEvent(oppUpdate);
            }
        }
    }

    public void handleDisconnect(ClientSession session) {
        if (session == null) {
            return;
        }
        RoomEnvelope.DisconnectEnvelope env = new RoomEnvelope.DisconnectEnvelope(session);
        CompletableFuture<Void> done = registerCompletionTracker(env);
        submitEnvelope(env);
        awaitEnvelopeFuture(done, null);
    }

    private void handleDisconnectInternal(ClientSession session) {
        if (session == null) {
            return;
        }

        String sid = session.getSessionId();
        Player leavingPlayer = sessionPlayerMap.remove(sid);
        sessions.remove(session);

        if (leavingPlayer == null) {
            return;
        }

        RoomState currentState = state;
        int connectedCount = sessions.size();
        int remainingCount;
        List<String> playerNamesSnapshot;
        String nextOwner = null;
        boolean allDisconnected = false;

        if (currentState == RoomState.WAITING_FOR_PLAYERS) {
            players.remove(leavingPlayer);
            playerReconnectTokens.remove(leavingPlayer.getUsername().toLowerCase(Locale.ROOT));
            remainingCount = players.size();
            playerNamesSnapshot = players.stream().map(Player::getUsername).toList();

            boolean wasOwner = ownerUsername != null && leavingPlayer.getUsername().equalsIgnoreCase(ownerUsername);
            if (wasOwner) {
                if (!players.isEmpty()) {
                    this.ownerUsername = players.getFirst().getUsername();
                    nextOwner = this.ownerUsername;
                } else {
                    this.ownerUsername = null;
                }
            }
        } else if (currentState == RoomState.IN_PROGRESS || currentState == RoomState.ROUND_INTERMISSION) {
            if (connectedCount == 0) {
                allDisconnected = true;
                this.state = RoomState.FINISHED;
                remainingCount = 0;
                playerNamesSnapshot = List.of();
            } else {
                String key = leavingPlayer.getUsername().toLowerCase(Locale.ROOT);
                String token = playerReconnectTokens.computeIfAbsent(key, k -> generateReconnectToken());
                disconnectedPlayers.put(key, new DisconnectedPlayerRecord(leavingPlayer, token, System.currentTimeMillis()));
                remainingCount = players.size();
                playerNamesSnapshot = getConnectedPlayerNames();

                boolean wasOwner = ownerUsername != null && leavingPlayer.getUsername().equalsIgnoreCase(ownerUsername);
                if (wasOwner) {
                    Player nextActive = players.stream()
                            .filter(p -> !isDisconnected(p.getUsername()))
                            .findFirst()
                            .orElse(null);
                    if (nextActive != null) {
                        this.ownerUsername = nextActive.getUsername();
                        nextOwner = this.ownerUsername;
                    }
                }
            }
        } else {
            remainingCount = players.size();
            playerNamesSnapshot = getConnectedPlayerNames();
        }

        if (onPlayerLeave != null) {
            try {
                onPlayerLeave.accept(session);
            } catch (Exception ignored) {}
        }

        if (allDisconnected) {
            stop();
            return;
        }

        if (nextOwner != null) {
            broadcast(new ServerEvent.RoomOwnerChanged(nextOwner));
            broadcast(new ServerEvent.ChatBroadcast("GAME", "Host left. Ownership transferred to " + nextOwner));
        }

        if (currentState == RoomState.WAITING_FOR_PLAYERS) {
            broadcast(new ServerEvent.RoomStatus(roomId, remainingCount, maxPlayers, playerNamesSnapshot));
        } else if (currentState == RoomState.IN_PROGRESS || currentState == RoomState.ROUND_INTERMISSION) {
            broadcast(new ServerEvent.ChatBroadcast("GAME", leavingPlayer.getUsername() + " disconnected. They have 2 minutes to rejoin."));
            broadcast(new ServerEvent.RoomStatus(roomId, connectedCount, maxPlayers, playerNamesSnapshot));
            startWatchdogIfNeeded();
        }
    }

    private void startWatchdogIfNeeded() {
        if (!isRunning.get() || (state != RoomState.IN_PROGRESS && state != RoomState.ROUND_INTERMISSION)) {
            return;
        }
        if (isWatchdogRunning.compareAndSet(false, true)) {
            watchdogThread = Thread.ofVirtual().name("watchdog-" + roomId).start(this::runWatchdog);
        }
    }

    private void runWatchdog() {
        try {
            while (isRunning.get() && isWatchdogRunning.get()
                    && (state == RoomState.IN_PROGRESS || state == RoomState.ROUND_INTERMISSION)) {
                Thread.sleep(watchdogCheckIntervalMs);
                if (isRunning.get() && (state == RoomState.IN_PROGRESS || state == RoomState.ROUND_INTERMISSION)) {
                    submitEnvelope(new RoomEnvelope.WatchdogTickEnvelope(System.currentTimeMillis()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            isWatchdogRunning.set(false);
        }
    }

    private void handleWatchdogTickInternal(long now) {
        if (state == RoomState.ROUND_INTERMISSION) {
            checkExpiredDisconnectionsInternal(now);
            if (state == RoomState.ROUND_INTERMISSION
                    && intermissionStartedTimestamp > 0
                    && (now - intermissionStartedTimestamp >= roundIntermissionMs)) {
                startNextRoundInternal();
            }
            return;
        }
        if (state == RoomState.IN_PROGRESS) {
            checkExpiredDisconnectionsInternal(now);
            checkTurnInactivityInternal(now);
        }
    }

    public void checkExpiredDisconnections() {
        checkExpiredDisconnections(System.currentTimeMillis());
    }

    public void checkExpiredDisconnections(long now) {
        RoomEnvelope.WatchdogTickEnvelope env = new RoomEnvelope.WatchdogTickEnvelope(now);
        CompletableFuture<Void> done = registerCompletionTracker(env);
        submitEnvelope(env);
        awaitEnvelopeFuture(done, null);
    }

    private void checkExpiredDisconnectionsInternal(long now) {
        if ((state != RoomState.IN_PROGRESS && state != RoomState.ROUND_INTERMISSION) || disconnectedPlayers.isEmpty()) {
            return;
        }

        List<DisconnectedPlayerRecord> expired = new ArrayList<>();
        for (DisconnectedPlayerRecord record : disconnectedPlayers.values()) {
            if (now - record.disconnectedAt() >= reconnectGracePeriodMs) {
                expired.add(record);
            }
        }

        for (DisconnectedPlayerRecord record : expired) {
            if (disconnectedPlayers.remove(record.player().getUsername().toLowerCase(Locale.ROOT)) != null) {
                handleExpiredPlayer(record.player());
            }
        }
    }

    public void checkTurnInactivity() {
        checkTurnInactivity(System.currentTimeMillis());
    }

    public void checkTurnInactivity(long now) {
        RoomEnvelope.WatchdogTickEnvelope env = new RoomEnvelope.WatchdogTickEnvelope(now);
        CompletableFuture<Void> done = registerCompletionTracker(env);
        submitEnvelope(env);
        awaitEnvelopeFuture(done, null);
    }

    private void checkTurnInactivityInternal(long now) {
        if (state != RoomState.IN_PROGRESS || engine == null || engine.isGameOver()) {
            return;
        }

        Player activePlayer = engine.getTurnManager().getCurrentPlayer();
        if (activePlayer == null || isDisconnected(activePlayer.getUsername())) {
            return;
        }

        if (turnStartedTimestamp > 0 && (now - turnStartedTimestamp >= afkTurnTimeoutMs)) {
            String key = activePlayer.getUsername().toLowerCase(Locale.ROOT);
            int afkCount = consecutiveAfkTurns.merge(key, 1, Integer::sum);

            if (afkCount >= 2) {
                handleAfkKick(activePlayer);
            } else {
                if (state == RoomState.IN_PROGRESS && engine != null && !engine.isGameOver()
                        && activePlayer.equals(engine.getTurnManager().getCurrentPlayer())) {
                    if (!engine.hasDrawnThisTurn()) {
                        engine.drawCardWithResult(activePlayer);
                    }
                    if (activePlayer.equals(engine.getTurnManager().getCurrentPlayer())) {
                        engine.passTurn(activePlayer);
                    }
                    this.turnStartedTimestamp = now;
                }

                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        activePlayer.getUsername() + " was inactive for 2 minutes; turn auto-passed."));
                broadcastGameState();
            }
        }
    }

    private void handleExpiredPlayer(Player player) {
        removePlayerFromMatch(player, player.getUsername() + " failed to reconnect within 2 minutes and was removed.", false);
    }

    private void handleAfkKick(Player player) {
        removePlayerFromMatch(player, player.getUsername() + " was removed from match due to repeated inactivity.", true);
    }

    private void removePlayerFromMatch(Player player, String reasonMessage, boolean isAfkKick) {
        if (state != RoomState.IN_PROGRESS && state != RoomState.ROUND_INTERMISSION) {
            return;
        }

        Player winner = null;
        String nextOwner = null;
        ClientSession playerSession = null;

        if (engine != null) {
            engine.removePlayer(player);
        }
        players.remove(player);
        int remainingCount = players.size();
        String key = player.getUsername().toLowerCase(Locale.ROOT);
        consecutiveAfkTurns.remove(key);
        playerReconnectTokens.remove(key);

        if (isAfkKick) {
            for (Map.Entry<String, Player> entry : sessionPlayerMap.entrySet()) {
                if (entry.getValue().equals(player)) {
                    for (ClientSession s : sessions) {
                        if (s.getSessionId().equals(entry.getKey())) {
                            playerSession = s;
                            break;
                        }
                    }
                    break;
                }
            }
            if (playerSession != null) {
                sessions.remove(playerSession);
                sessionPlayerMap.remove(playerSession.getSessionId());
            }
        }

        boolean wasOwner = ownerUsername != null && player.getUsername().equalsIgnoreCase(ownerUsername);
        if (wasOwner) {
            Player nextActive = players.stream()
                    .filter(p -> !isDisconnected(p.getUsername()))
                    .findFirst()
                    .orElse(null);
            if (nextActive != null) {
                this.ownerUsername = nextActive.getUsername();
                nextOwner = this.ownerUsername;
            } else if (!players.isEmpty()) {
                this.ownerUsername = players.getFirst().getUsername();
                nextOwner = this.ownerUsername;
            } else {
                this.ownerUsername = null;
            }
        }

        if (remainingCount == 1) {
            this.state = RoomState.FINISHED;
            winner = players.getFirst();
        } else if (remainingCount == 0) {
            this.state = RoomState.FINISHED;
        }

        if (playerSession != null) {
            playerSession.sendEvent(new ServerEvent.ErrorEvent("KICKED: " + reasonMessage));
        }

        broadcast(new ServerEvent.ChatBroadcast("GAME", reasonMessage));

        if (nextOwner != null) {
            broadcast(new ServerEvent.RoomOwnerChanged(nextOwner));
            broadcast(new ServerEvent.ChatBroadcast("GAME", "Host left. Ownership transferred to " + nextOwner));
        }

        if (winner != null) {
            broadcast(new ServerEvent.GameOver(winner.getUsername()));
            broadcast(new ServerEvent.ChatBroadcast("GAME", winner.getUsername() + " wins by forfeit!"));
            stop();
        } else if (remainingCount == 0) {
            stop();
        } else {
            this.turnStartedTimestamp = System.currentTimeMillis();
            broadcastGameState();
        }
    }

    public void broadcast(ServerEvent event) {
        for (ClientSession session : sessions) {
            session.sendEvent(event);
        }
    }

    public List<String> getPlayerNames() {
        return players.stream().map(Player::getUsername).toList();
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public RoomVisibility getVisibility() {
        return visibility;
    }

    public String getAccessCode() {
        return accessCode;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public boolean isPrivate() {
        return visibility == RoomVisibility.PRIVATE;
    }

    public RoomState getState() {
        return state;
    }

    public int getPlayerCount() {
        return players.size();
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public UltimoEngine getEngine() {
        return engine;
    }

    public int getTargetScore() {
        return targetScore;
    }

    public void setTargetScore(int targetScore) {
        this.targetScore = Math.max(1, targetScore);
    }

    public long getRoundIntermissionMs() {
        return roundIntermissionMs;
    }

    public void setRoundIntermissionMs(long millis) {
        this.roundIntermissionMs = Math.max(10L, millis);
    }

    public long getReconnectGracePeriodMs() {
        return reconnectGracePeriodMs;
    }

    public void setReconnectGracePeriodMs(long millis) {
        this.reconnectGracePeriodMs = Math.max(10L, millis);
    }

    public long getWatchdogCheckIntervalMs() {
        return watchdogCheckIntervalMs;
    }

    public void setWatchdogCheckIntervalMs(long millis) {
        this.watchdogCheckIntervalMs = Math.max(10L, millis);
    }

    public long getAfkTurnTimeoutMs() {
        return afkTurnTimeoutMs;
    }

    public void setAfkTurnTimeoutMs(long millis) {
        this.afkTurnTimeoutMs = Math.max(10L, millis);
    }

    public long getTurnStartedTimestamp() {
        return turnStartedTimestamp;
    }

    public void setTurnStartedTimestamp(long timestamp) {
        this.turnStartedTimestamp = timestamp;
    }

    public int getConsecutiveAfkTurns(String username) {
        if (username == null) return 0;
        return consecutiveAfkTurns.getOrDefault(username.trim().toLowerCase(Locale.ROOT), 0);
    }

    public void setOnRoomFinished(Consumer<GameRoom> onRoomFinished) {
        this.onRoomFinished = onRoomFinished;
    }

    public void stop() {
        isRunning.set(false);
        isWatchdogRunning.set(false);
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
        for (BotSession bot : botSessions) {
            bot.close();
        }
        botSessions.clear();
        consecutiveAfkTurns.clear();
        sessions.clear();
        sessionPlayerMap.clear();
        disconnectedPlayers.clear();
        playerReconnectTokens.clear();
        commandQueue.clear();
        for (Queue<CompletableFuture<Void>> q : completionTrackers.values()) {
            CompletableFuture<Void> f;
            while ((f = q.poll()) != null) {
                f.complete(null);
            }
        }
        completionTrackers.clear();
        if (onRoomFinished != null) {
            try {
                onRoomFinished.accept(this);
            } catch (Exception ignored) {}
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * In-memory zero-IO socket stub for internal virtual-thread bot sessions.
     */
    private static final class InMemoryBotSocket extends Socket {
        private final InputStream in = new ByteArrayInputStream(new byte[0]);
        private final OutputStream out = OutputStream.nullOutputStream();

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public void setSoTimeout(int timeout) {
            // No-op for in-memory bot socket
        }

        @Override
        public void close() {
            // No-op for in-memory bot socket
        }
    }

    private static final ConnectionListener NO_OP_LISTENER = new ConnectionListener() {
        @Override
        public void onCommandReceived(ClientSession session, ClientCommand command) {}

        @Override
        public void onSessionDisconnected(ClientSession session) {}
    };

    /**
     * Internal virtual-thread bot session that listens to room events and invokes {@link HeuristicBotEngine}
     * to submit commands back to the room's Actor mailbox.
     */
    final class BotSession extends ClientSession {
        private final String botName;
        private final String difficulty;
        private final BlockingQueue<ServerEvent> botInbox = new LinkedBlockingQueue<>();
        private final AtomicBoolean botActive = new AtomicBoolean(true);
        private volatile Thread botWorkerThread;

        BotSession(String sessionId, String botName, String difficulty) throws IOException {
            super(sessionId, new InMemoryBotSocket(), NO_OP_LISTENER, 0);
            this.botName = botName;
            this.difficulty = difficulty;
            setUsername(botName);
        }

        public String getDifficulty() {
            return difficulty;
        }

        void startBotWorker() {
            this.botWorkerThread = Thread.ofVirtual()
                    .name("bot-worker-" + roomId + "-" + botName)
                    .start(this::runBotWorker);
        }

        @Override
        public void sendEvent(ServerEvent event) {
            if (!botActive.get() || isClosed()) {
                return;
            }
            MetricsRegistry.getInstance().recordEventEmitted(event.type().name());
            botInbox.offer(event);
        }

        private void runBotWorker() {
            try {
                while (botActive.get() && isRunning.get() && !isClosed()) {
                    ServerEvent event = botInbox.poll(100, TimeUnit.MILLISECONDS);
                    if (event == null) {
                        continue;
                    }
                    boolean shouldEvaluate = isActionTriggerEvent(event);
                    ServerEvent next;
                    while ((next = botInbox.poll()) != null) {
                        if (isActionTriggerEvent(next)) {
                            shouldEvaluate = true;
                        }
                    }

                    if (shouldEvaluate && botActive.get() && isRunning.get()) {
                        evaluateAndSubmitBotTurn();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean isActionTriggerEvent(ServerEvent event) {
            return event instanceof ServerEvent.HandUpdate
                    || event instanceof ServerEvent.TurnAnnounced
                    || event instanceof ServerEvent.UltimoDeclared
                    || event instanceof ServerEvent.UltimoPenalty
                    || event instanceof ServerEvent.ChallengeResult
                    || event instanceof ServerEvent.ErrorEvent;
        }

        private void evaluateAndSubmitBotTurn() {
            if (state != RoomState.IN_PROGRESS || engine == null || engine.isGameOver()) {
                return;
            }
            Player botPlayer = sessionPlayerMap.get(getSessionId());
            if (botPlayer == null || botPlayer.isFinished()) {
                return;
            }

            // 1. Check if an opponent can be caught for failing to declare ULTIMO
            Optional<BotDecision> catchDecision = HeuristicBotEngine.INSTANCE.checkCatchUltimo(engine, botPlayer);
            if (catchDecision.isPresent()) {
                submitCommandAndWait(new ClientCommand.CatchUltimo(catchDecision.get().targetPlayer()));
            }

            // 2. Check if it is currently this bot's turn
            if (state != RoomState.IN_PROGRESS || engine == null || engine.isGameOver()) {
                return;
            }
            Player current = engine.getTurnManager().getCurrentPlayer();
            if (!botPlayer.equals(current)) {
                return;
            }

            BotDecision decision = HeuristicBotEngine.INSTANCE.decide(engine, botPlayer);

            if (decision.action() == BotAction.DECLARE_ULTIMO) {
                submitCommandAndWait(new ClientCommand.Ultimo());
                if (state != RoomState.IN_PROGRESS || engine == null || engine.isGameOver()
                        || !botPlayer.equals(engine.getTurnManager().getCurrentPlayer())) {
                    return;
                }
                decision = HeuristicBotEngine.INSTANCE.decide(engine, botPlayer);
            }

            if (decision.action() == BotAction.CATCH_ULTIMO) {
                submitCommandAndWait(new ClientCommand.CatchUltimo(decision.targetPlayer()));
                if (state != RoomState.IN_PROGRESS || engine == null || engine.isGameOver()
                        || !botPlayer.equals(engine.getTurnManager().getCurrentPlayer())) {
                    return;
                }
                decision = HeuristicBotEngine.INSTANCE.decide(engine, botPlayer);
            }

            switch (decision.action()) {
                case PLAY -> {
                    if (HeuristicBotEngine.INSTANCE.willLeaveOneCard(botPlayer.getHand(), decision)
                            && !engine.hasDeclaredUltimo(botPlayer)) {
                        submitCommandAndWait(new ClientCommand.Ultimo());
                    }
                    int handBefore = botPlayer.getHand().size();
                    submitCommandAndWait(new ClientCommand.Play(decision.cards(), decision.chosenWildColor()));

                    // Fallback if play was rejected (e.g. pending Wild Draw Four challenge restriction)
                    if (state == RoomState.IN_PROGRESS && engine != null && !engine.isGameOver()
                            && botPlayer.equals(engine.getTurnManager().getCurrentPlayer())
                            && botPlayer.getHand().size() == handBefore) {
                        if (!engine.hasDrawnThisTurn()) {
                            submitCommandAndWait(new ClientCommand.Draw());
                        }
                        if (state == RoomState.IN_PROGRESS && engine != null && !engine.isGameOver()
                                && botPlayer.equals(engine.getTurnManager().getCurrentPlayer())
                                && engine.hasDrawnThisTurn()) {
                            submitCommandAndWait(new ClientCommand.Pass());
                        }
                    }
                }
                case DRAW -> submitCommandAndWait(new ClientCommand.Draw());
                case PASS -> submitCommandAndWait(new ClientCommand.Pass());
                case DECLARE_ULTIMO -> submitCommandAndWait(new ClientCommand.Ultimo());
                case CATCH_ULTIMO -> submitCommandAndWait(new ClientCommand.CatchUltimo(decision.targetPlayer()));
                case CHALLENGE_WILD_FOUR -> submitCommandAndWait(new ClientCommand.ChallengeWildFour());
            }
        }

        private void submitCommandAndWait(ClientCommand command) {
            if (!botActive.get() || !isRunning.get()) {
                return;
            }
            RoomEnvelope.CommandEnvelope env = new RoomEnvelope.CommandEnvelope(this, command);
            CompletableFuture<Void> done = registerCompletionTracker(env);
            submitEnvelope(env);
            awaitEnvelopeFuture(done, null);
        }

        @Override
        public void close() {
            if (botActive.compareAndSet(true, false)) {
                if (botWorkerThread != null) {
                    botWorkerThread.interrupt();
                }
                botInbox.clear();
                super.close();
            }
        }
    }
}
