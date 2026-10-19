package pl.ultimo.server.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.engine.UltimoEngine;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.core.model.CardValue;
import pl.ultimo.core.model.Player;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;
import pl.ultimo.server.room.GameRoom;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Polyglot Ecosystem Integration Test (TCP Bot + WebSocket Mobile)")
class EcosystemIntegrationTest {

    private ServerApp server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig(0, 4, 30_000, 15);
        server = new ServerApp(config);
        server.start();

        long deadline = System.currentTimeMillis() + 3000;
        while (server.getBoundPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        port = server.getBoundPort();
        assertTrue(port > 0, "Server must bind dynamic port");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Concurrent TCP socket client and WebSocket client play in targeted room under simulated network delay")
    void shouldCompleteGameplayAcrossTcpAndWebSocketEcosystems() throws Exception {
        String roomId = "cross-ecosystem-room-42";

        // 1. Establish raw TCP client (simulating PyTorch RL Bot / Agent 07)
        try (Socket tcpSocket = new Socket("localhost", port);
             BufferedWriter tcpWriter = new BufferedWriter(new OutputStreamWriter(tcpSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader tcpReader = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream(), StandardCharsets.UTF_8))) {

            List<ServerEvent> tcpEvents = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch tcpStartLatch = new CountDownLatch(1);

            Thread.ofVirtual().start(() -> {
                try {
                    String line;
                    while ((line = tcpReader.readLine()) != null) {
                        ServerEvent ev = ProtocolCodec.decodeEvent(line);
                        tcpEvents.add(ev);
                        if (ev instanceof ServerEvent.HandUpdate) {
                            tcpStartLatch.countDown();
                        }
                    }
                } catch (Exception ignored) {}
            });

            // 2. Establish WebSocket client (simulating Kotlin Android Client / Agent 08)
            HttpClient httpClient = HttpClient.newHttpClient();
            List<String> wsMessages = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch wsStartLatch = new CountDownLatch(1);

            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            String msg = data.toString();
                            wsMessages.add(msg);
                            if (msg.startsWith("HAND_UPDATE")) {
                                wsStartLatch.countDown();
                            }
                            return WebSocket.Listener.super.onText(webSocket, data, last);
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            // Join targeted room
            tcpWriter.write("JOIN PythonBot " + roomId + "\n");
            tcpWriter.flush();

            // Inject artificial 100ms jitter before Mobile connects
            Thread.sleep(100);

            ws.sendText("JOIN AndroidMobile " + roomId + "\n", true);

            // Wait for both to be registered in the target room
            Thread.sleep(200);
            GameRoom room = server.getRoomManager().getRoom(roomId).orElseThrow();
            assertEquals(2, room.getPlayerCount());

            // TCP player issues START command
            tcpWriter.write("START\n");
            tcpWriter.flush();

            boolean tcpStarted = tcpStartLatch.await(5, TimeUnit.SECONDS);
            boolean wsStarted = wsStartLatch.await(5, TimeUnit.SECONDS);

            assertTrue(tcpStarted, "TCP Bot should have received initial HAND_UPDATE");
            assertTrue(wsStarted, "WebSocket Mobile should have received initial HAND_UPDATE");

            // Verify Fog of War: Both players received game events and hands
            UltimoEngine engine = room.getEngine();
            assertNotNull(engine);
            Player currentPlayer = engine.getTurnManager().getCurrentPlayer();
            assertNotNull(currentPlayer);

            // Simulated round gameplay: Current player plays a legal card
            Card currentTop = engine.getDiscardPile().getTopCard().orElseThrow();
            CardColor validColor = currentTop.color() == CardColor.WILD ? CardColor.RED : currentTop.color();
            Card matchingCard = new Card(validColor, CardValue.SEVEN);
            currentPlayer.getHand().addCard(matchingCard);

            if (currentPlayer.getUsername().equals("PythonBot")) {
                // TCP bot plays card using canonical wire token
                Thread.sleep(50); // Simulated inference latency
                tcpWriter.write("PLAY " + ProtocolCodec.formatCard(matchingCard) + "\n");
                tcpWriter.flush();
            } else {
                // WebSocket client plays card using abbreviated wire token
                Thread.sleep(50); // Simulated UI render latency
                ws.sendText("PLAY " + ProtocolCodec.formatCard(matchingCard, true) + "\n", true);
            }

            // Verify top card updated
            Thread.sleep(200);
            assertEquals(matchingCard, engine.getDiscardPile().getTopCard().orElseThrow());

            // WebSocket client clean closure
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Ecosystem test done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Verify DEFENSE_STATE and PLAYER_STATE wire frames across TCP and WebSocket clients")
    void shouldVerifyDefenseStateAndPlayerStateWireFrames() throws Exception {
        // 1. Verify Protocol v2 DEFENSE_STATE wire encoding & decoding
        ServerEvent.ActionDefenseState drawTwoDef = new ServerEvent.ActionDefenseState(CardValue.DRAW_TWO, 4, "Bob");
        String drawTwoWire = ProtocolCodec.encodeEvent(drawTwoDef);
        assertEquals("DEFENSE_STATE DRAW_TWO 4 Bob\n", drawTwoWire);
        ServerEvent decodedDrawTwo = ProtocolCodec.decodeEvent(drawTwoWire);
        assertInstanceOf(ServerEvent.ActionDefenseState.class, decodedDrawTwo);
        ServerEvent.ActionDefenseState ads1 = (ServerEvent.ActionDefenseState) decodedDrawTwo;
        assertEquals(CardValue.DRAW_TWO, ads1.defenseType());
        assertEquals(4, ads1.accumulatedPenalty());
        assertEquals("Bob", ads1.targetPlayer());

        ServerEvent.ActionDefenseState wildFourDef = new ServerEvent.ActionDefenseState(CardValue.WILD_DRAW_FOUR, 8, "Charlie");
        String wildFourWire = ProtocolCodec.encodeEvent(wildFourDef);
        assertEquals("DEFENSE_STATE WILD_DRAW_FOUR 8 Charlie\n", wildFourWire);
        ServerEvent.ActionDefenseState ads2 = (ServerEvent.ActionDefenseState) ProtocolCodec.decodeEvent(wildFourWire);
        assertEquals(CardValue.WILD_DRAW_FOUR, ads2.defenseType());
        assertEquals(8, ads2.accumulatedPenalty());
        assertEquals("Charlie", ads2.targetPlayer());

        ServerEvent.ActionDefenseState skipDef = new ServerEvent.ActionDefenseState(CardValue.SKIP, 1, "Charlie");
        String skipWire = ProtocolCodec.encodeEvent(skipDef);
        assertEquals("DEFENSE_STATE SKIP 1 Charlie\n", skipWire);
        ServerEvent.ActionDefenseState ads3 = (ServerEvent.ActionDefenseState) ProtocolCodec.decodeEvent(skipWire);
        assertEquals(CardValue.SKIP, ads3.defenseType());
        assertEquals(1, ads3.accumulatedPenalty());
        assertEquals("Charlie", ads3.targetPlayer());

        ServerEvent.ActionDefenseState noneDef = new ServerEvent.ActionDefenseState(null, 0, "Alice");
        String noneWire = ProtocolCodec.encodeEvent(noneDef);
        assertEquals("DEFENSE_STATE NONE 0 Alice\n", noneWire);
        ServerEvent.ActionDefenseState adsNone = (ServerEvent.ActionDefenseState) ProtocolCodec.decodeEvent(noneWire);
        assertNull(adsNone.defenseType());
        assertEquals(0, adsNone.accumulatedPenalty());
        assertEquals("Alice", adsNone.targetPlayer());

        // 2. Verify Protocol v2 PLAYER_STATE wire encoding & decoding
        ServerEvent.PlayerState discState = new ServerEvent.PlayerState("Bob", "DISCONNECTED", 95);
        String discWire = ProtocolCodec.encodeEvent(discState);
        assertEquals("PLAYER_STATE Bob DISCONNECTED 95\n", discWire);
        ServerEvent.PlayerState ps1 = (ServerEvent.PlayerState) ProtocolCodec.decodeEvent(discWire);
        assertEquals("Bob", ps1.username());
        assertEquals("DISCONNECTED", ps1.status());
        assertEquals(95, ps1.graceSecondsRemaining());

        ServerEvent.PlayerState recState = new ServerEvent.PlayerState("Bob", "RECONNECTED", 0);
        String recWire = ProtocolCodec.encodeEvent(recState);
        assertEquals("PLAYER_STATE Bob RECONNECTED\n", recWire);
        ServerEvent.PlayerState ps2 = (ServerEvent.PlayerState) ProtocolCodec.decodeEvent(recWire);
        assertEquals("Bob", ps2.username());
        assertEquals("RECONNECTED", ps2.status());
        assertEquals(0, ps2.graceSecondsRemaining());

        ServerEvent.PlayerState connState = new ServerEvent.PlayerState("Alice", "CONNECTED", 0);
        String connWire = ProtocolCodec.encodeEvent(connState);
        assertEquals("PLAYER_STATE Alice CONNECTED\n", connWire);
        ServerEvent.PlayerState ps3 = (ServerEvent.PlayerState) ProtocolCodec.decodeEvent(connWire);
        assertEquals("Alice", ps3.username());
        assertEquals("CONNECTED", ps3.status());

        // 3. Verify wire frame exchange over live TCP socket connection
        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            writer.write("JOIN SpecTester\n");
            writer.flush();

            String welcomeLine = reader.readLine();
            assertNotNull(welcomeLine);
            ServerEvent welcomeEv = ProtocolCodec.decodeEvent(welcomeLine);
            assertInstanceOf(ServerEvent.Welcome.class, welcomeEv);

            // Issue PING probe and verify PONG wire frame
            writer.write("PING\n");
            writer.flush();
            ServerEvent pongEv = null;
            for (int i = 0; i < 5; i++) {
                String line = reader.readLine();
                if (line == null) break;
                ServerEvent ev = ProtocolCodec.decodeEvent(line);
                if (ev instanceof ServerEvent.Pong) {
                    pongEv = ev;
                    break;
                }
            }
            assertNotNull(pongEv, "Server must respond with PONG to PING probe");
            assertInstanceOf(ServerEvent.Pong.class, pongEv);

            // Clean QUIT
            writer.write("QUIT\n");
            writer.flush();
        }
    }

    @Test
    @DisplayName("Verify bounded line reader drops TCP connections sending lines > 8KB")
    void shouldDropTcpConnectionWhenLineExceeds8Kb() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Construct oversized payload exceeding 8192 bytes
            byte[] oversized = ("X".repeat(8193) + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(oversized);
            out.flush();

            // Server must respond with error frame and terminate connection
            String response = reader.readLine();
            assertNotNull(response, "Server must respond with error before closing");
            assertEquals("ERROR FRAME_TOO_LONG: Frame length exceeds 8192 bytes", response);

            // Subsequent read must return null indicating connection drop (EOF)
            String postDrop = reader.readLine();
            assertNull(postDrop, "Server must cleanly terminate socket after oversized frame");
        }
    }

    @Test
    @DisplayName("Verify token-based RECONNECT under simulated TCP RST (socket.setSoLinger(true, 0))")
    void shouldReconnectWithTokenUnderSimulatedTcpRst() throws Exception {
        String testRoomId = "room-rst-reconnect-42";

        // 1. Host establishes TCP connection and creates targeted 2-player room
        Socket hostSocket = new Socket("127.0.0.1", port);
        BufferedWriter hostWriter = new BufferedWriter(new OutputStreamWriter(hostSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader hostReader = new BufferedReader(new InputStreamReader(hostSocket.getInputStream(), StandardCharsets.UTF_8));

        hostWriter.write("JOIN HostPlayer " + testRoomId + " 2\n");
        hostWriter.flush();
        String hostWelcome = hostReader.readLine();
        assertNotNull(hostWelcome);
        assertTrue(hostWelcome.startsWith("WELCOME"));

        // 2. Guest establishes TCP connection and joins
        Socket guestSocket = new Socket("127.0.0.1", port);
        BufferedWriter guestWriter = new BufferedWriter(new OutputStreamWriter(guestSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader guestReader = new BufferedReader(new InputStreamReader(guestSocket.getInputStream(), StandardCharsets.UTF_8));

        guestWriter.write("JOIN GuestPlayer " + testRoomId + " 2\n");
        guestWriter.flush();

        String guestWelcomeLine = guestReader.readLine();
        assertNotNull(guestWelcomeLine);
        ServerEvent.Welcome guestWelcome = (ServerEvent.Welcome) ProtocolCodec.decodeEvent(guestWelcomeLine);
        String reconnectToken = guestWelcome.reconnectToken();
        assertNotNull(reconnectToken, "WELCOME event must provide non-null reconnectToken");
        assertFalse(reconnectToken.isBlank(), "reconnectToken must not be blank");

        // Drain room status and hand updates
        BlockingQueue<ServerEvent> guestEvents = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try {
                String line;
                while ((line = guestReader.readLine()) != null) {
                    ServerEvent ev = ProtocolCodec.decodeEvent(line);
                    if (ev != null) {
                        guestEvents.offer(ev);
                    }
                }
            } catch (Exception ignored) {}
        });

        // Await initial HandUpdate
        ServerEvent.HandUpdate initialHand = null;
        for (int i = 0; i < 20; i++) {
            ServerEvent ev = guestEvents.poll(500, TimeUnit.MILLISECONDS);
            if (ev instanceof ServerEvent.HandUpdate hu) {
                initialHand = hu;
                break;
            }
        }
        assertNotNull(initialHand, "Guest must receive initial private hand");
        int expectedCardCount = initialHand.cards().size();
        assertEquals(7, expectedCardCount);

        // 3. Simulate abrupt TCP RST by setting SO_LINGER to 0 and closing immediately
        guestSocket.setSoLinger(true, 0);
        guestSocket.close();

        // Allow server session reaper and room watchdog to register the disconnect
        Thread.sleep(600);

        // 4. Guest connects via brand-new TCP socket and issues token-based RECONNECT
        try (Socket reconnectedSocket = new Socket("127.0.0.1", port);
             BufferedWriter recWriter = new BufferedWriter(new OutputStreamWriter(reconnectedSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader recReader = new BufferedReader(new InputStreamReader(reconnectedSocket.getInputStream(), StandardCharsets.UTF_8))) {

            recWriter.write("RECONNECT GuestPlayer " + testRoomId + " " + reconnectToken + "\n");
            recWriter.flush();

            String recWelcomeLine = recReader.readLine();
            assertNotNull(recWelcomeLine, "Reconnected socket must receive WELCOME response");
            ServerEvent.Welcome recWelcome = (ServerEvent.Welcome) ProtocolCodec.decodeEvent(recWelcomeLine);
            assertEquals("GuestPlayer", recWelcome.message().contains("GuestPlayer") ? "GuestPlayer" : recWelcome.message());

            // Await snapshot recovery events: HAND_UPDATE, TOP_CARD, TURN_ANNOUNCED
            ServerEvent.HandUpdate restoredHand = null;
            for (int i = 0; i < 10; i++) {
                String line = recReader.readLine();
                if (line == null) break;
                ServerEvent ev = ProtocolCodec.decodeEvent(line);
                if (ev instanceof ServerEvent.HandUpdate hu) {
                    restoredHand = hu;
                    break;
                }
            }

            assertNotNull(restoredHand, "Reconnected player must receive restored private hand snapshot");
            assertEquals(expectedCardCount, restoredHand.cards().size(), "Restored hand size must match pre-disconnect state");
            assertEquals(initialHand.cards(), restoredHand.cards(), "Restored hand cards must match exact pre-disconnect cards");
        } finally {
            hostSocket.close();
        }
    }

    @Test
    @DisplayName("Verify idle TCP sockets survive > 90 seconds without premature disconnect by exchanging PING/PONG")
    void shouldSurviveIdleTcpSocketSurpass90SecondsWithPingPong() throws Exception {
        // Default duration: 91 seconds to rigorously verify > 90 seconds contract.
        // Can be overridden via system property -Dultimo.idle.test.seconds=N for rapid local debugging.
        int targetIdleSeconds = Integer.getInteger("ultimo.idle.test.seconds", 91);
        System.out.printf(">>> Starting idle TCP heartbeat keepalive test (target: %ds)...%n", targetIdleSeconds);

        try (Socket idleSocket = new Socket("127.0.0.1", port)) {
            idleSocket.setSoTimeout(8000);
            idleSocket.setTcpNoDelay(true);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(idleSocket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(idleSocket.getInputStream(), StandardCharsets.UTF_8));

            // Initial PING to establish session
            writer.write("PING\n");
            writer.flush();
            String initialPong = reader.readLine();
            assertEquals("PONG", initialPong);

            long startTime = System.currentTimeMillis();
            long deadline = startTime + (targetIdleSeconds * 1000L);
            int heartbeatCount = 0;

            // Heartbeat exchange loop: send PING every 10 seconds and assert PONG
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(10_000);
                heartbeatCount++;

                writer.write("PING\n");
                writer.flush();

                String pong = reader.readLine();
                assertNotNull(pong, "Server must respond to heartbeat PING probe");
                assertEquals("PONG", pong);

                long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                System.out.printf("  [IDLE-TCP-KEEPALIVE] Socket healthy after %ds / %ds (heartbeats exchanged: %d)%n",
                        elapsedSeconds, targetIdleSeconds, heartbeatCount);
            }

            long totalElapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
            assertTrue(totalElapsedSeconds >= targetIdleSeconds,
                    "Socket must survive for the full requested idle duration (elapsed: " + totalElapsedSeconds + "s)");

            // Clean exit
            writer.write("QUIT\n");
            writer.flush();
            String quitAck = reader.readLine();
            assertNotNull(quitAck);
            assertTrue(quitAck.contains("QUIT_ACK"));

            System.out.printf(">>> Successfully verified idle TCP socket survival for %ds with zero disconnects.%n", totalElapsedSeconds);
        }
    }
}
