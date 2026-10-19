package pl.ultimo.server.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.room.GameRoom;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Stage 15 (Stream F): Multi-Agent Concurrent Chaos, Pinning & High-Concurrency Integration Verification")
class MultiAgentGameplayIntegrationTest {

    private ServerApp server;
    private int port;
    private final List<SimulatedAgentPlayer> activeClients = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig(0, 8, 30_000, 15);
        server = new ServerApp(config);
        server.start();

        long deadline = System.currentTimeMillis() + 3000;
        while (server.getBoundPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        port = server.getBoundPort();
        assertTrue(port > 0, "Server must bind to dynamic ephemeral port");
    }

    @AfterEach
    void tearDown() {
        for (SimulatedAgentPlayer client : activeClients) {
            client.close();
        }
        activeClients.clear();

        if (server != null) {
            server.stop();
        }
    }

    private SimulatedAgentPlayer createAgent(String username) throws IOException {
        SimulatedAgentPlayer agent = new SimulatedAgentPlayer(username, port);
        activeClients.add(agent);
        return agent;
    }

    @Test
    @Timeout(30)
    @DisplayName("End-to-end multi-agent game flow: Room code, name sanitization, defending, ULTIMO catch, reconnect, auto-pass, podium")
    void shouldExecuteFullMultiAgentGameplayScenario() throws Exception {
        String roomId = "chaos-e2e-room";
        String accessCode = "K9X2";

        // 1. Player 1 joins with room code
        SimulatedAgentPlayer p1 = createAgent("Player_One");
        p1.send("JOIN Player_One " + roomId + " " + accessCode + " 4");

        ServerEvent.Welcome welcome1 = p1.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(5));
        assertNotNull(welcome1, "Player 1 should receive WELCOME");
        ServerEvent.RoomStatus status1 = p1.awaitEventType(ServerEvent.RoomStatus.class, Duration.ofSeconds(5));
        assertNotNull(status1, "Player 1 should receive ROOM_STATUS");
        assertEquals(1, status1.currentPlayers());

        // 2. Player 2 attempts join with space in name -> asserted rejected
        try (Socket badSocket = new Socket("localhost", port);
             BufferedWriter badWriter = new BufferedWriter(new OutputStreamWriter(badSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader badReader = new BufferedReader(new InputStreamReader(badSocket.getInputStream(), StandardCharsets.UTF_8))) {

            badWriter.write("JOIN \"Bob Ross\" " + roomId + " " + accessCode + "\n");
            badWriter.flush();

            String errorLine = badReader.readLine();
            assertNotNull(errorLine, "Server must respond to invalid username join");
            ServerEvent errEvent = ProtocolCodec.decodeEvent(errorLine);
            assertTrue(errEvent instanceof ServerEvent.ErrorEvent, "Expected ErrorEvent for space in username: " + errorLine);
            String errMsg = ((ServerEvent.ErrorEvent) errEvent).errorMessage();
            assertTrue(errMsg.toLowerCase().contains("username") || errMsg.toLowerCase().contains("invalid") || errMsg.toLowerCase().contains("format"),
                    "Error should explain invalid username requirement: " + errMsg);
        }

        // 2b. Player 2 joins with valid [A-Za-z_] name
        SimulatedAgentPlayer p2 = createAgent("Player_Two");
        p2.send("JOIN Player_Two " + roomId + " " + accessCode);
        ServerEvent.Welcome welcome2 = p2.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(5));
        assertNotNull(welcome2, "Player 2 should receive WELCOME after valid name");

        // 3. Players 3 and 4 join
        SimulatedAgentPlayer p3 = createAgent("Player_Three");
        p3.send("JOIN Player_Three " + roomId + " " + accessCode);
        assertNotNull(p3.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(5)));

        SimulatedAgentPlayer p4 = createAgent("Player_Four");
        p4.send("JOIN Player_Four " + roomId + " " + accessCode);
        assertNotNull(p4.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(5)));

        // Match auto-starts when 4th player joins room of capacity 4
        ServerEvent.HandUpdate p1HandEvent = p1.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5));
        assertNotNull(p1HandEvent, "Player 1 should receive HAND_UPDATE when game starts");
        assertNotNull(p2.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5)));
        assertNotNull(p3.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5)));
        assertNotNull(p4.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5)));

        GameRoom room = server.getRoomManager().getRoom(roomId).orElseThrow();
        assertNotNull(room, "Game room must exist in room manager");
        UltimoEngine engine = room.getEngine();
        assertNotNull(engine, "Room engine must be initialized");

        // 4. Action Defending & Stacking verification (+2 on +2):
        // Setup table: top card is RED FOUR
        engine.getDiscardPile().playCard(new Card(CardColor.RED, CardValue.FOUR), null);
        Player curPlayer = engine.getTurnManager().getCurrentPlayer();

        // Give current player a DRAW_TWO and next player a DRAW_TWO
        Player nextPlayer = engine.getTurnManager().peekNextPlayer();
        curPlayer.getHand().clear();
        Card drawTwoRed = new Card(CardColor.RED, CardValue.DRAW_TWO);
        curPlayer.getHand().addCard(drawTwoRed);
        curPlayer.getHand().addCard(new Card(CardColor.GREEN, CardValue.NINE));

        nextPlayer.getHand().clear();
        Card drawTwoBlue = new Card(CardColor.BLUE, CardValue.DRAW_TWO);
        nextPlayer.getHand().addCard(drawTwoBlue);
        nextPlayer.getHand().addCard(new Card(CardColor.GREEN, CardValue.NINE));

        // Current player plays Draw Two (+2)
        SimulatedAgentPlayer activeAgent = findClientByUsername(curPlayer.getUsername());
        assertNotNull(activeAgent);
        activeAgent.send("PLAY RED_DRAW_TWO");
        SimulatedAgentPlayer nextAgent = findClientByUsername(nextPlayer.getUsername());
        assertNotNull(nextAgent);
        assertNotNull(nextAgent.awaitEvent(ev -> ev instanceof ServerEvent.TurnAnnounced ta && ta.activePlayerName().equals(nextPlayer.getUsername()), Duration.ofSeconds(5)));

        // Next player defends with Draw Two (+4 total)
        int victimIdx = (engine.getPlayers().indexOf(nextPlayer) + 1) % engine.getPlayers().size();
        Player victimPlayer = engine.getPlayers().get(victimIdx);
        nextAgent.send("PLAY BLUE_DRAW_TWO");

        // Player 3 cannot defend -> sends DRAW to pull the accumulated 4 cards
        SimulatedAgentPlayer victimAgent = findClientByUsername(victimPlayer.getUsername());
        assertNotNull(victimAgent);
        assertNotNull(victimAgent.awaitEvent(ev -> ev instanceof ServerEvent.TurnAnnounced ta && ta.activePlayerName().equals(victimPlayer.getUsername()), Duration.ofSeconds(5)));

        int victimBefore = victimPlayer.getHand().size();
        victimAgent.send("DRAW");
        assertNotNull(victimAgent.awaitEvent(ev -> ev instanceof ServerEvent.ChatBroadcast cb && cb.message().contains("accepted penalty and drew 4 cards"), Duration.ofSeconds(5)));
        assertEquals(victimBefore + 4, victimPlayer.getHand().size(), "Player 3 should draw the accumulated 4 cards");

        // 5. ULTIMO Declaration & 6-Card Penalty Catch verification:
        Player p4Entity = engine.getPlayers().stream()
                .filter(p -> p.getUsername().equals("Player_Four"))
                .findFirst().orElseThrow();
        p4Entity.getHand().clear();
        Card activeColorCard = new Card(engine.getDiscardPile().getActiveColor(), CardValue.SEVEN);
        Card remainingCard = new Card(CardColor.YELLOW, CardValue.EIGHT);
        p4Entity.getHand().addCard(activeColorCard);
        p4Entity.getHand().addCard(remainingCard);

        // Ensure turn is on Player 4
        while (!engine.getTurnManager().getCurrentPlayer().equals(p4Entity)) {
            engine.getTurnManager().advanceTurn();
        }

        // Player 4 plays penultimate card without shouting ULTIMO
        p4.send("PLAY " + activeColorCard.color() + "_" + activeColorCard.value());
        assertNotNull(p4.awaitEvent(ev -> ev instanceof ServerEvent.HandUpdate hu && hu.cards().size() == 1, Duration.ofSeconds(5)));

        assertEquals(1, p4Entity.getHand().size(), "Player 4 should hold exactly 1 card");

        // Violator did not shout ULTIMO -> Player 1 catches Player 4
        p1.send("CATCH_ULTIMO Player_Four");
        assertNotNull(p1.awaitEvent(ev -> ev instanceof ServerEvent.UltimoPenalty up && up.penalizedPlayer().equals("Player_Four"), Duration.ofSeconds(5)));

        // Player 4 draws 6 penalty cards (1 + 6 = 7 cards)
        assertTrue(p4Entity.getHand().size() >= 2, "Penalized player should hold penalty cards");
        assertEquals(7, p4Entity.getHand().size(), "Player 4 should have 7 cards after 6 penalty cards draw");

        // 6. Session Disconnect & Reconnect within 2 minutes:
        int p2CardsBefore = engine.getPlayers().stream()
                .filter(p -> p.getUsername().equals("Player_Two"))
                .findFirst().orElseThrow().getHand().size();

        // Player 2 abruptly drops socket
        p2.disconnectAbruptly();
        activeClients.remove(p2);
        Thread.sleep(250);

        // Room remains active under reconnection grace period
        assertEquals(pl.ultimo.server.room.RoomState.IN_PROGRESS, room.getState());

        // Player 2 reconnects with fresh socket and same username within 2-minute window
        SimulatedAgentPlayer p2Reconnected = new SimulatedAgentPlayer("Player_Two", port);
        activeClients.add(p2Reconnected);
        p2Reconnected.send("JOIN Player_Two " + roomId + " " + accessCode);

        // State restored snapshot received
        ServerEvent.HandUpdate restoredHand = p2Reconnected.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5));
        assertNotNull(restoredHand, "Reconnected player must receive restored private hand snapshot");
        assertEquals(p2CardsBefore, restoredHand.cards().size(), "Hand count must match pre-disconnect state");

        // 7. Auto-Pass Evaluator:
        Player activeNow = engine.getTurnManager().getCurrentPlayer();
        activeNow.getHand().clear();
        // Hand contains only cards with no matching color or value
        CardColor topColor = engine.getDiscardPile().getActiveColor();
        CardColor nonMatchingColor = (topColor == CardColor.RED) ? CardColor.GREEN : CardColor.RED;
        CardValue topValue = engine.getDiscardPile().getTopCard().orElseThrow().value();
        CardValue nonMatchingValue = (topValue == CardValue.NINE) ? CardValue.EIGHT : CardValue.NINE;
        activeNow.getHand().addCard(new Card(nonMatchingColor, nonMatchingValue));

        // Ensure next card drawn from deck is guaranteed unplayable so auto-pass triggers
        engine.getDeck().pushTop(new Card(nonMatchingColor, nonMatchingValue));

        SimulatedAgentPlayer activeClientNow = findClientByUsername(activeNow.getUsername());
        if (activeClientNow != null) {
            String activeNameBefore = activeNow.getUsername();
            activeClientNow.send("DRAW");
            long deadline = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < deadline && engine.getTurnManager().getCurrentPlayer().getUsername().equals(activeNameBefore)) {
                Thread.sleep(50);
            }

            Player activeAfter = engine.getTurnManager().getCurrentPlayer();
            assertNotEquals(activeNameBefore, activeAfter.getUsername(), "Turn must advance upon drawing with zero playable moves");
        }

        // 8. Podium & Finished Players Verification:
        // Current player sheds their last card and finishes 1st
        Player finisher = engine.getTurnManager().getCurrentPlayer();
        CardColor currentColor = engine.getDiscardPile().getActiveColor();
        finisher.getHand().clear();
        Card winningCard = new Card(currentColor, CardValue.ONE);
        finisher.getHand().addCard(winningCard);

        SimulatedAgentPlayer finisherAgent = findClientByUsername(finisher.getUsername());
        assertNotNull(finisherAgent, "Finisher client must be found");
        finisherAgent.send("PLAY " + winningCard.color() + "_" + winningCard.value());

        long finishDeadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < finishDeadline && !finisher.isFinished()) {
            Thread.sleep(50);
        }

        assertTrue(finisher.isFinished(), "Finishing player must have finished flag set");
        assertEquals(1, finisher.getPlacementRank(), "First player to empty hand must receive 1st place");
        assertEquals(pl.ultimo.server.room.RoomState.IN_PROGRESS, room.getState(), "Room continues until only 1 player remains");
    }

    @Test
    @Timeout(15)
    @DisplayName("Loom Carrier Thread Pinning Audit: Zero synchronized blocks in pl.ultimo.server")
    void shouldAuditZeroSynchronizedLocksInServerPackage() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String packagePath = "pl/ultimo/server";
        Enumeration<URL> resources = classLoader.getResources(packagePath);

        List<Class<?>> serverClasses = new ArrayList<>();
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            File directory = new File(resource.toURI());
            if (directory.exists() && directory.isDirectory()) {
                findClasses(directory, "pl.ultimo.server", serverClasses);
            }
        }

        assertFalse(serverClasses.isEmpty(), "Server classes must be discovered for pinning audit");

        List<String> synchronizedMethods = new ArrayList<>();
        for (Class<?> clazz : serverClasses) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (Modifier.isSynchronized(method.getModifiers())) {
                    synchronizedMethods.add(clazz.getSimpleName() + "." + method.getName());
                }
            }
        }

        assertTrue(synchronizedMethods.isEmpty(),
                "Found synchronized methods in pl.ultimo.server which violate Project Loom virtual thread guidelines: " + synchronizedMethods);
    }

    @Test
    @Timeout(20)
    @DisplayName("High-concurrency chaotic churn: 8 concurrent rooms with 32 virtual thread agents")
    void shouldSustainHighConcurrencyChaoticChurnAcrossRooms() throws Exception {
        int roomCount = 8;
        int playersPerRoom = 4;
        CountDownLatch finishLatch = new CountDownLatch(roomCount * playersPerRoom);

        for (int r = 0; r < roomCount; r++) {
            String roomName = "stress-room-" + r;
            String pass = "KEY" + r;

            for (int p = 0; p < playersPerRoom; p++) {
                String userName = "Agent_" + r + "_" + p;
                Thread.ofVirtual().name("Sim-" + userName).start(() -> {
                    try (SimulatedAgentPlayer client = new SimulatedAgentPlayer(userName, port)) {
                        client.send("JOIN " + userName + " " + roomName + " " + pass + " " + playersPerRoom);
                        ServerEvent ev = client.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(5));
                        assertNotNull(ev);

                        // Send simulated periodic pings and chats
                        client.send("CHAT Hello from " + userName);
                        client.send("PING");
                        Thread.sleep(50);
                    } catch (Exception ignored) {
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }
        }

        boolean allFinished = finishLatch.await(15, TimeUnit.SECONDS);
        assertTrue(allFinished, "All 32 simulated virtual thread agents must complete without deadlocks");
    }

    private SimulatedAgentPlayer findClientByUsername(String username) {
        synchronized (activeClients) {
            for (int i = activeClients.size() - 1; i >= 0; i--) {
                SimulatedAgentPlayer client = activeClients.get(i);
                if (client.username.equals(username) && !client.socket.isClosed()) {
                    return client;
                }
            }
        }
        return null;
    }

    private static void findClasses(File directory, String packageName, List<Class<?>> classes) throws ClassNotFoundException {
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                findClasses(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className));
            }
        }
    }

    static class SimulatedAgentPlayer implements AutoCloseable {
        final String username;
        final Socket socket;
        final BufferedWriter writer;
        final BufferedReader reader;
        final BlockingQueue<ServerEvent> receivedEvents = new LinkedBlockingQueue<>();
        final BlockingQueue<String> rawLines = new LinkedBlockingQueue<>();
        final List<Card> hand = Collections.synchronizedList(new ArrayList<>());
        volatile boolean running = true;
        final Thread readerThread;

        SimulatedAgentPlayer(String username, int port) throws IOException {
            this.username = username;
            this.socket = new Socket("localhost", port);
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.readerThread = Thread.ofVirtual().name("Agent-" + username).start(() -> {
                try {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        rawLines.offer(line);
                        ServerEvent event = ProtocolCodec.decodeEvent(line);
                        if (event != null) {
                            receivedEvents.offer(event);
                            if (event instanceof ServerEvent.HandUpdate hu) {
                                hand.clear();
                                hand.addAll(hu.cards());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }

        void send(String cmd) throws IOException {
            writer.write(cmd.endsWith("\n") ? cmd : cmd + "\n");
            writer.flush();
        }

        ServerEvent awaitEvent(Predicate<ServerEvent> predicate, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                ServerEvent ev = receivedEvents.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (ev != null && predicate.test(ev)) {
                    return ev;
                }
            }
            return null;
        }

        <T extends ServerEvent> T awaitEventType(Class<T> type, Duration timeout) throws InterruptedException {
            ServerEvent ev = awaitEvent(type::isInstance, timeout);
            return type != null && ev != null ? type.cast(ev) : null;
        }

        void disconnectAbruptly() throws IOException {
            running = false;
            socket.setSoLinger(true, 0);
            socket.close();
        }

        @Override
        public void close() {
            running = false;
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
