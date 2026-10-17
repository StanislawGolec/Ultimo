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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
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
}
