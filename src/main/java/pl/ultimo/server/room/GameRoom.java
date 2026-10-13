package pl.ultimo.server.room;

import pl.ultimo.core.engine.MoveValidator;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.Player;
import pl.ultimo.core.state.GameState;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.net.ClientSession;

import java.util.ArrayList;
import java.util.Collections;
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

/**
 * Encapsulates an active multiplayer Ultimo game match.
 * Processes incoming player commands sequentially on a dedicated room thread (Actor model),
 * ensuring total thread-safety and zero lock contention on the game engine state.
 */
public class GameRoom implements Runnable {

    private final String roomId;
    private final int maxPlayers;
    private final Random random;

    private final List<ClientSession> sessions = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final Map<String, Player> sessionPlayerMap = new ConcurrentHashMap<>();
    private final BlockingQueue<RoomCommandEvent> commandQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private volatile RoomState state = RoomState.WAITING_FOR_PLAYERS;
    private UltimoEngine engine;

    public record RoomCommandEvent(ClientSession session, ClientCommand command) {}

    public GameRoom(String roomId, int maxPlayers) {
        this(roomId, maxPlayers, new Random());
    }

    public GameRoom(String roomId, int maxPlayers, Random random) {
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        if (maxPlayers < 2) {
            throw new IllegalArgumentException("Room capacity must be at least 2");
        }
        this.maxPlayers = maxPlayers;
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Enrolls a player into the room if capacity is not exceeded and match hasn't started.
     */
    public synchronized boolean addPlayer(ClientSession session, String username) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(username, "username must not be null");

        if (state != RoomState.WAITING_FOR_PLAYERS || players.size() >= maxPlayers) {
            return false;
        }

        Player player = new Player(session.getSessionId(), username);
        players.add(player);
        sessions.add(session);
        sessionPlayerMap.put(session.getSessionId(), player);

        broadcast(new ServerEvent.RoomStatus(roomId, players.size(), maxPlayers, getPlayerNames()));

        // Auto-start match when room is completely filled
        if (players.size() == maxPlayers) {
            startGame();
        }

        return true;
    }

    /**
     * Explicitly starts the match if at least 2 players have joined.
     */
    public synchronized boolean startGame() {
        if (state != RoomState.WAITING_FOR_PLAYERS || players.size() < 2) {
            return false;
        }

        this.engine = new UltimoEngine(players, random);
        this.engine.start();
        this.state = RoomState.IN_PROGRESS;

        broadcast(new ServerEvent.ChatBroadcast("GAME", "Game has started! Good luck."));
        broadcastGameState();

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
        Player player = sessionPlayerMap.get(session.getSessionId());

        switch (command) {
            case ClientCommand.Start start -> handleStart(session);
            case ClientCommand.Play play -> handlePlay(session, player, play);
            case ClientCommand.Draw draw -> handleDraw(session, player);
            case ClientCommand.Pass pass -> handlePass(session, player);
            case ClientCommand.Chat chat -> handleChat(session, chat);
            case ClientCommand.Ping ping -> session.sendEvent(new ServerEvent.Pong());
            default -> session.sendEvent(new ServerEvent.ErrorEvent("Unsupported command in room: " + command.type()));
        }
    }

    private void handleStart(ClientSession session) {
        if (state == RoomState.IN_PROGRESS) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is already in progress"));
            return;
        }

        if (state != RoomState.WAITING_FOR_PLAYERS) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot start match in current room state: " + state));
            return;
        }

        if (players.size() < 2) {
            session.sendEvent(new ServerEvent.ErrorEvent("Need at least 2 players to start match"));
            return;
        }

        startGame();
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

        if (play.cards().size() > 1) {
            broadcast(new ServerEvent.ChatBroadcast("GAME",
                    player.getUsername() + " played a combo of " + play.cards().size() + " cards!"));
        }

        if (result.isUltimo()) {
            broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " declared ULTIMO! (1 card remaining)"));
        }

        if (result.isGameOver()) {
            this.state = RoomState.FINISHED;
            broadcastGameState();
            broadcast(new ServerEvent.GameOver(result.winner().getUsername()));
            return;
        }

        broadcastGameState();
    }

    private void handleDraw(ClientSession session, Player player) {
        if (state != RoomState.IN_PROGRESS || engine == null) {
            session.sendEvent(new ServerEvent.ErrorEvent("Match is not currently in progress"));
            return;
        }
        if (player == null) {
            return;
        }

        Optional<Card> drawnCard = engine.drawCard(player);
        if (drawnCard.isEmpty()) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot draw (either not your turn or already drawn this turn)"));
            return;
        }

        session.sendEvent(new ServerEvent.DrawResult(drawnCard.get()));

        // Check if player has any legally playable cards in hand after drawing
        GameState snapshot = engine.captureSnapshot();
        boolean hasPlayable = MoveValidator.hasPlayableCard(player.getHand(), snapshot.topCard(), snapshot.activeColor());

        if (!hasPlayable) {
            // Auto-pass: player drew a card and has zero legal moves
            engine.passTurn(player);
            broadcast(new ServerEvent.ChatBroadcast("GAME",
                    player.getUsername() + " drew a card (" + drawnCard.get() + ") but has no playable moves. Turn passed."));
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

        boolean passed = engine.passTurn(player);
        if (!passed) {
            session.sendEvent(new ServerEvent.ErrorEvent("Cannot pass (must draw before passing, or not your turn)"));
            return;
        }

        broadcast(new ServerEvent.ChatBroadcast("GAME", player.getUsername() + " passed their turn."));
        broadcastGameState();
    }

    private void handleChat(ClientSession session, ClientCommand.Chat chat) {
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

        // 1. Public table state
        broadcast(new ServerEvent.TopCard(snapshot.topCard(), snapshot.activeColor()));
        broadcast(new ServerEvent.TurnAnnounced(snapshot.activePlayerName()));

        // 2. Secret hand update to each player
        synchronized (this) {
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                ClientSession s = sessions.get(i);
                s.sendEvent(new ServerEvent.HandUpdate(p.getHand().getCards()));
            }

            // 3. Card counts of opponents
            for (int i = 0; i < players.size(); i++) {
                Player target = players.get(i);
                ClientSession targetSession = sessions.get(i);
                for (Player other : players) {
                    if (!other.equals(target)) {
                        targetSession.sendEvent(new ServerEvent.OpponentUpdate(other.getUsername(), other.getHand().size()));
                    }
                }
            }
        }
    }

    public void handleDisconnect(ClientSession session) {
        synchronized (this) {
            String sid = session.getSessionId();
            Player leavingPlayer = sessionPlayerMap.remove(sid);
            sessions.remove(session);

            if (leavingPlayer == null) {
                return;
            }

            if (state == RoomState.WAITING_FOR_PLAYERS) {
                players.remove(leavingPlayer);
                broadcast(new ServerEvent.RoomStatus(roomId, players.size(), maxPlayers, getPlayerNames()));
            } else if (state == RoomState.IN_PROGRESS) {
                players.remove(leavingPlayer);
                broadcast(new ServerEvent.ChatBroadcast("GAME", leavingPlayer.getUsername() + " left the game."));

                // If only 1 player remains, declare forfeit win
                if (players.size() == 1) {
                    this.state = RoomState.FINISHED;
                    Player winner = players.getFirst();
                    broadcast(new ServerEvent.GameOver(winner.getUsername()));
                    broadcast(new ServerEvent.ChatBroadcast("GAME", winner.getUsername() + " wins by forfeit!"));
                    stop();
                } else if (players.isEmpty()) {
                    this.state = RoomState.FINISHED;
                    stop();
                }
            }
        }
    }

    public void broadcast(ServerEvent event) {
        synchronized (this) {
            for (ClientSession session : sessions) {
                session.sendEvent(event);
            }
        }
    }

    public List<String> getPlayerNames() {
        synchronized (this) {
            return players.stream().map(Player::getUsername).toList();
        }
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public String getRoomId() {
        return roomId;
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

    public void stop() {
        isRunning.set(false);
    }
}
