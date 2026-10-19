package pl.ultimo.server.room;

import pl.ultimo.core.engine.GameResult;
import pl.ultimo.core.engine.MoveValidator;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.GameState;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.RoomVisibility;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.metrics.MetricsRegistry;
import pl.ultimo.server.net.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Encapsulates an active multiplayer Ultimo game match.
 * Processes incoming player commands sequentially on a dedicated room thread (Actor model),
 * ensuring total thread-safety and zero lock contention on the game engine state.
 * Employs ReentrantLock and decoupled state snapshots to eliminate Virtual Thread carrier pinning.
 */
public class GameRoom implements Runnable {

    private final String roomId;
    private final String roomName;
    private final RoomVisibility visibility;
    private final String accessCode;
    private final int maxPlayers;
    private final Random random;
    private final Consumer<ClientSession> onPlayerLeave;
    private final ReentrantLock roomLock = new ReentrantLock();

    private final List<ClientSession> sessions = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Player> sessionPlayerMap = new ConcurrentHashMap<>();
    private final BlockingQueue<RoomCommandEvent> commandQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private volatile String ownerUsername;
    private volatile RoomState state = RoomState.WAITING_FOR_PLAYERS;
    private UltimoEngine engine;

    public record DisconnectedPlayerRecord(Player player, long disconnectedAt) {}

    public static final long DEFAULT_RECONNECT_GRACE_PERIOD_MS = 120_000L;
    public static final long DEFAULT_AFK_TURN_TIMEOUT_MS = 120_000L;
    public static final long DEFAULT_WATCHDOG_CHECK_INTERVAL_MS = 5_000L;

    private final Map<String, DisconnectedPlayerRecord> disconnectedPlayers = new ConcurrentHashMap<>();
    private final AtomicBoolean isWatchdogRunning = new AtomicBoolean(false);
    private volatile Thread watchdogThread;
    private volatile long reconnectGracePeriodMs = DEFAULT_RECONNECT_GRACE_PERIOD_MS;
    private volatile long afkTurnTimeoutMs = DEFAULT_AFK_TURN_TIMEOUT_MS;
    private volatile long watchdogCheckIntervalMs = DEFAULT_WATCHDOG_CHECK_INTERVAL_MS;
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
        roomLock.lock();
        try {
            return players.stream().anyMatch(p -> p.getUsername().equalsIgnoreCase(username.trim()));
        } finally {
            roomLock.unlock();
        }
    }

    public boolean isDisconnected(String username) {
        if (username == null) {
            return false;
        }
        return disconnectedPlayers.containsKey(username.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public DisconnectedPlayerRecord getDisconnectedPlayer(String username) {
        if (username == null) {
            return null;
        }
        return disconnectedPlayers.get(username.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public Map<String, DisconnectedPlayerRecord> getDisconnectedPlayers() {
        return Map.copyOf(disconnectedPlayers);
    }

    public int getConnectedSessionCount() {
        roomLock.lock();
        try {
            return sessions.size();
        } finally {
            roomLock.unlock();
        }
    }

    public List<String> getConnectedPlayerNames() {
        roomLock.lock();
        try {
            List<String> names = new ArrayList<>();
            for (ClientSession session : sessions) {
                Player p = sessionPlayerMap.get(session.getSessionId());
                if (p != null) {
                    names.add(p.getUsername());
                }
            }
            return names;
        } finally {
            roomLock.unlock();
        }
    }

    public boolean addPlayer(ClientSession session, String username) {
        return addPlayer(session, username, null);
    }

    /**
     * Enrolls a player into the room if capacity is not exceeded, match hasn't started,
     * username is unique in this room, and access code is valid (if private).
     * If the player is currently disconnected from an in-progress match, re-binds them.
     */
    public boolean addPlayer(ClientSession session, String username, String code) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(username, "username must not be null");

        if (!validateAccess(code)) {
            session.sendEvent(new ServerEvent.ErrorEvent("INVALID_ACCESS_CODE: Room is private and requires valid access code"));
            return false;
        }

        if (isDisconnected(username)) {
            return reconnectPlayer(session, username);
        }


        List<String> playerNamesSnapshot;
        int currentCount;
        boolean shouldStart = false;
        String currentOwner;

        roomLock.lock();
        try {
            if (players.stream().anyMatch(p -> p.getUsername().equalsIgnoreCase(username.trim()))) {
                session.sendEvent(new ServerEvent.ErrorEvent("NAME_TAKEN: Player with name '" + username + "' already exists in this room"));
                return false;
            }

            if (state != RoomState.WAITING_FOR_PLAYERS || players.size() >= maxPlayers) {
                return false;
            }

            Player player = new Player(session.getSessionId(), username);
            if (players.isEmpty()) {
                this.ownerUsername = player.getUsername();
            }
            players.add(player);
            sessions.add(session);
            sessionPlayerMap.put(session.getSessionId(), player);

            currentOwner = this.ownerUsername;
            currentCount = players.size();
            playerNamesSnapshot = players.stream().map(Player::getUsername).toList();
            if (currentCount == maxPlayers) {
                shouldStart = true;
            }
        } finally {
            roomLock.unlock();
        }

        broadcast(new ServerEvent.RoomStatus(roomId, currentCount, maxPlayers, playerNamesSnapshot));
        if (this.accessCode != null && !this.accessCode.isBlank()) {
            session.sendEvent(new ServerEvent.ChatBroadcast("GAME", "Room Access Code: " + accessCode));
        }

        // Auto-start match when room is completely filled
        if (shouldStart) {
            startGame();
        }

        return true;
    }

    /**
     * Rebinds a newly connected client session to an existing player entity who was disconnected
     * during an active match within the 2-minute grace period.
     */
    public boolean reconnectPlayer(ClientSession session, String username) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(username, "username must not be null");

        DisconnectedPlayerRecord record = disconnectedPlayers.remove(username.trim().toLowerCase(java.util.Locale.ROOT));
        if (record == null) {
            return false;
        }

        Player existingPlayer = record.player();
        GameState snapshot = null;
        List<Player> allPlayersSnapshot;
        List<String> connectedNamesSnapshot;
        int connectedCount;

        roomLock.lock();
        try {
            sessions.add(session);
            sessionPlayerMap.put(session.getSessionId(), existingPlayer);
            session.setUsername(existingPlayer.getUsername());

            if (engine != null) {
                snapshot = engine.captureSnapshot();
            }
            allPlayersSnapshot = List.copyOf(players);
            connectedNamesSnapshot = getConnectedPlayerNames();
            connectedCount = sessions.size();
        } finally {
            roomLock.unlock();
        }

        // Deliver private authoritative state snapshot to reconnected session outside lock
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

        // Broadcast to room that player has reconnected
        broadcast(new ServerEvent.ChatBroadcast("GAME", existingPlayer.getUsername() + " has reconnected!"));
        broadcast(new ServerEvent.RoomStatus(roomId, connectedCount, maxPlayers, connectedNamesSnapshot));

        return true;
    }

    /**
     * Explicitly starts the match if at least 2 players have joined.
     */
    public boolean startGame() {
        roomLock.lock();
        try {
            if (state != RoomState.WAITING_FOR_PLAYERS || players.size() < 2) {
                return false;
            }

            this.engine = new UltimoEngine(players, random, UltimoEngine.DEFAULT_TARGET_SCORE, true, true);
            this.engine.start();
            this.state = RoomState.IN_PROGRESS;
            this.turnStartedTimestamp = System.currentTimeMillis();
            if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
                this.currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
            }
        } finally {
            roomLock.unlock();
        }

        broadcast(new ServerEvent.ChatBroadcast("GAME", "Game has started! Good luck."));
        broadcastGameState();
        startWatchdogIfNeeded();

        return true;
    }

    /**
     * Enqueues an incoming command to be processed sequentially on the room's event loop.
     */
    public void enqueueCommand(ClientSession session, ClientCommand command) {
        if (isRunning.get()) {
            commandQueue.offer(new RoomCommandEvent(session, command));
        }
    }

    @Override
    public void run() {
        while (isRunning.get()) {
            try {
                RoomCommandEvent event = commandQueue.poll(200, TimeUnit.MILLISECONDS);
                if (event != null) {
                    processCommand(event.session(), event.command());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Error processing command in room " + roomId + ": " + e.getMessage());
            }
        }
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

        startGame();
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
        roomLock.lock();
        try {
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
        } finally {
            roomLock.unlock();
        }

        if (targetPlayer == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Player '" + targetName + "' not found in room"));
            return;
        }

        if (targetSession != null) {
            targetSession.sendEvent(new ServerEvent.ErrorEvent("You have been kicked from the room by the host"));
            broadcast(new ServerEvent.ChatBroadcast("GAME", targetPlayer.getUsername() + " was kicked by the room owner."));
            handleDisconnect(targetSession);
        } else {
            broadcast(new ServerEvent.ChatBroadcast("GAME", targetPlayer.getUsername() + " was kicked by the room owner."));
            disconnectedPlayers.remove(targetPlayer.getUsername().toLowerCase(java.util.Locale.ROOT));
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
        roomLock.lock();
        try {
            for (Player p : players) {
                if (p.getUsername().equalsIgnoreCase(targetName.trim())) {
                    this.ownerUsername = p.getUsername();
                    found = true;
                    break;
                }
            }
        } finally {
            roomLock.unlock();
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

        consecutiveAfkTurns.remove(player.getUsername().toLowerCase(java.util.Locale.ROOT));
        turnStartedTimestamp = System.currentTimeMillis();

        if (play.cards().size() > 1) {
            broadcast(new ServerEvent.ChatBroadcast("GAME",
                    player.getUsername() + " played a combo of " + play.cards().size() + " cards!"));
        }

        if (!result.isGameOver() && !play.cards().isEmpty()) {
            Card lastPlayed = play.cards().get(play.cards().size() - 1);
            if (lastPlayed.value() == pl.ultimo.core.model.CardValue.DRAW_TWO) {
                int cardsCount = 2 * play.cards().size();
                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        "PENALTY STACK: +" + cardsCount + " CARDS! Targeted at " + engine.getTurnManager().getCurrentPlayer().getUsername()));
            } else if (lastPlayed.value() == pl.ultimo.core.model.CardValue.WILD_DRAW_FOUR) {
                int cardsCount = 4 * play.cards().size();
                broadcast(new ServerEvent.ChatBroadcast("GAME",
                        "PENALTY STACK: +" + cardsCount + " CARDS! Targeted at " + engine.getTurnManager().getCurrentPlayer().getUsername()));
            } else if (lastPlayed.value() == pl.ultimo.core.model.CardValue.SKIP) {
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
            if (!engine.getMatchStandings().isEmpty()) {
                broadcast(new ServerEvent.MatchStandings(engine.getMatchStandings()));
            }

            Optional<GameResult> resultOpt = engine.getResult();
            if (resultOpt.isPresent()) {
                GameResult gr = resultOpt.get();
                broadcast(new ServerEvent.RoundOver(gr.winner().getUsername(), gr.roundPoints(), gr.cumulativeScores(), gr.isTournamentWon()));
                broadcast(new ServerEvent.ChatBroadcast("GAME", gr.winner().getUsername() + " won the round! Points earned: +" + gr.roundPoints()));
            }

            this.state = RoomState.FINISHED;
            broadcastGameState();
            String winnerName = result.winner() != null ? result.winner().getUsername() : player.getUsername();
            broadcast(new ServerEvent.GameOver(winnerName));
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
            consecutiveAfkTurns.remove(player.getUsername().toLowerCase(java.util.Locale.ROOT));
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

        consecutiveAfkTurns.remove(player.getUsername().toLowerCase(java.util.Locale.ROOT));
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

        consecutiveAfkTurns.remove(player.getUsername().toLowerCase(java.util.Locale.ROOT));
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
                roomLock.lock();
                try {
                    engine.setupDefenseScenario();
                    turnStartedTimestamp = System.currentTimeMillis();
                    if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
                        currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
                    }
                } finally {
                    roomLock.unlock();
                }
                broadcastGameState();
            }
            return;
        }
        if ("ADMIN_TRIGGER_PODIUM".equals(msg)) {
            if (engine != null) {
                roomLock.lock();
                try {
                    engine.setupPodiumScenario();
                    turnStartedTimestamp = System.currentTimeMillis();
                    if (engine.getTurnManager() != null && engine.getTurnManager().getCurrentPlayer() != null) {
                        currentTurnPlayer = engine.getTurnManager().getCurrentPlayer().getUsername();
                    }
                } finally {
                    roomLock.unlock();
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
     * State is captured and partitioned under lock, then delivered outside lock without pinning carrier threads.
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

        // 2. Secret hand update and opponent counts partitioned under lock
        record HandDelivery(ClientSession session, List<Card> handCards, List<ServerEvent.OpponentUpdate> oppUpdates) {}
        List<HandDelivery> deliveries = new ArrayList<>();

        roomLock.lock();
        try {
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
        } finally {
            roomLock.unlock();
        }

        // 3. Deliveries executed outside lock
        for (HandDelivery delivery : deliveries) {
            delivery.session().sendEvent(new ServerEvent.HandUpdate(delivery.handCards()));
            for (ServerEvent.OpponentUpdate oppUpdate : delivery.oppUpdates()) {
                delivery.session().sendEvent(oppUpdate);
            }
        }
    }

    public void handleDisconnect(ClientSession session) {
        Player leavingPlayer;
        List<String> playerNamesSnapshot;
        int remainingCount;
        int connectedCount;
        RoomState currentState;
        String nextOwner = null;
        boolean allDisconnected = false;

        roomLock.lock();
        try {
            String sid = session.getSessionId();
            leavingPlayer = sessionPlayerMap.remove(sid);
            sessions.remove(session);

            if (leavingPlayer == null) {
                return;
            }

            currentState = state;
            connectedCount = sessions.size();

            if (currentState == RoomState.WAITING_FOR_PLAYERS) {
                players.remove(leavingPlayer);
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
            } else if (currentState == RoomState.IN_PROGRESS) {
                if (connectedCount == 0) {
                    // Immediate Empty Room Reclamation: all players disconnected
                    allDisconnected = true;
                    this.state = RoomState.FINISHED;
                    remainingCount = 0;
                    playerNamesSnapshot = List.of();
                } else {
                    // Stage 13: 2-Minute Reconnection Grace Period
                    // Retain Player entity in memory, add to disconnectedPlayers
                    disconnectedPlayers.put(leavingPlayer.getUsername().toLowerCase(java.util.Locale.ROOT),
                            new DisconnectedPlayerRecord(leavingPlayer, System.currentTimeMillis()));
                    remainingCount = players.size();
                    playerNamesSnapshot = getConnectedPlayerNames();

                    // Transfer host ownership to next active connected player if host disconnected
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
        } finally {
            roomLock.unlock();
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
        } else if (currentState == RoomState.IN_PROGRESS) {
            broadcast(new ServerEvent.ChatBroadcast("GAME", leavingPlayer.getUsername() + " disconnected. They have 2 minutes to rejoin."));
            broadcast(new ServerEvent.RoomStatus(roomId, connectedCount, maxPlayers, playerNamesSnapshot));

            // Start virtual thread watchdog if not already running
            startWatchdogIfNeeded();
        }
    }

    private void startWatchdogIfNeeded() {
        if (!isRunning.get() || state != RoomState.IN_PROGRESS) {
            return;
        }
        if (isWatchdogRunning.compareAndSet(false, true)) {
            watchdogThread = Thread.ofVirtual().name("watchdog-" + roomId).start(this::runWatchdog);
        }
    }

    private void runWatchdog() {
        try {
            while (isRunning.get() && isWatchdogRunning.get() && state == RoomState.IN_PROGRESS) {
                Thread.sleep(watchdogCheckIntervalMs);
                checkExpiredDisconnections();
                checkTurnInactivity();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            isWatchdogRunning.set(false);
        }
    }

    public void checkExpiredDisconnections() {
        checkExpiredDisconnections(System.currentTimeMillis());
    }

    public void checkExpiredDisconnections(long now) {
        if (state != RoomState.IN_PROGRESS || disconnectedPlayers.isEmpty()) {
            return;
        }

        List<DisconnectedPlayerRecord> expired = new ArrayList<>();
        for (DisconnectedPlayerRecord record : disconnectedPlayers.values()) {
            if (now - record.disconnectedAt() >= reconnectGracePeriodMs) {
                expired.add(record);
            }
        }

        for (DisconnectedPlayerRecord record : expired) {
            if (disconnectedPlayers.remove(record.player().getUsername().toLowerCase(java.util.Locale.ROOT)) != null) {
                handleExpiredPlayer(record.player());
            }
        }
    }

    public void checkTurnInactivity() {
        checkTurnInactivity(System.currentTimeMillis());
    }

    public void checkTurnInactivity(long now) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            return;
        }

        Player activePlayer = null;
        boolean isAfkTimeout = false;
        int afkCount = 0;

        roomLock.lock();
        try {
            if (state != RoomState.IN_PROGRESS || engine == null || engine.isGameOver()) {
                return;
            }
            activePlayer = engine.getTurnManager().getCurrentPlayer();
            if (activePlayer == null || isDisconnected(activePlayer.getUsername())) {
                return;
            }

            if (turnStartedTimestamp > 0 && (now - turnStartedTimestamp >= afkTurnTimeoutMs)) {
                isAfkTimeout = true;
                String key = activePlayer.getUsername().toLowerCase(java.util.Locale.ROOT);
                afkCount = consecutiveAfkTurns.merge(key, 1, Integer::sum);
            }
        } finally {
            roomLock.unlock();
        }

        if (isAfkTimeout && activePlayer != null) {
            if (afkCount >= 2) {
                handleAfkKick(activePlayer);
            } else {
                roomLock.lock();
                try {
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
                } finally {
                    roomLock.unlock();
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
        Player winner = null;
        int remainingCount;
        String nextOwner = null;
        ClientSession playerSession = null;

        roomLock.lock();
        try {
            if (state != RoomState.IN_PROGRESS) {
                return;
            }

            if (engine != null) {
                engine.removePlayer(player);
            }
            players.remove(player);
            remainingCount = players.size();
            consecutiveAfkTurns.remove(player.getUsername().toLowerCase(java.util.Locale.ROOT));

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
        } finally {
            roomLock.unlock();
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
        List<ClientSession> sessionSnapshots;
        roomLock.lock();
        try {
            sessionSnapshots = List.copyOf(sessions);
        } finally {
            roomLock.unlock();
        }

        for (ClientSession session : sessionSnapshots) {
            session.sendEvent(event);
        }
    }

    public List<String> getPlayerNames() {
        roomLock.lock();
        try {
            return players.stream().map(Player::getUsername).toList();
        } finally {
            roomLock.unlock();
        }
    }

    public boolean isFull() {
        roomLock.lock();
        try {
            return players.size() >= maxPlayers;
        } finally {
            roomLock.unlock();
        }
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
        roomLock.lock();
        try {
            return players.size();
        } finally {
            roomLock.unlock();
        }
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public UltimoEngine getEngine() {
        return engine;
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
        return consecutiveAfkTurns.getOrDefault(username.trim().toLowerCase(java.util.Locale.ROOT), 0);
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
        consecutiveAfkTurns.clear();
        roomLock.lock();
        try {
            sessions.clear();
            sessionPlayerMap.clear();
            disconnectedPlayers.clear();
            commandQueue.clear();
        } finally {
            roomLock.unlock();
        }
        if (onRoomFinished != null) {
            try {
                onRoomFinished.accept(this);
            } catch (Exception ignored) {}
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }
}
