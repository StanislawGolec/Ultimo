package pl.ultimo.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.core.model.Card;
import pl.ultimo.core.model.CardColor;
import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.config.ServerConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("End-to-End Server and Multiplayer Gameplay Test")
class EndToEndGameFlowTest {

    private ServerApp server;
    private int port;

    private static class RemotePlayerClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final List<ServerEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());

        public RemotePlayerClient(int port) throws Exception {
            this.socket = new Socket("localhost", port);
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        public void send(String line) throws Exception {
            writer.write(line.endsWith("\n") ? line : line + "\n");
            writer.flush();
        }

        public ServerEvent readEvent(long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        ServerEvent event = ProtocolCodec.decodeEvent(line);
                        receivedEvents.add(event);
                        return event;
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }

        public void drainEvents() {
            try {
                while (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        receivedEvents.add(ProtocolCodec.decodeEvent(line));
                    }
                }
            } catch (Exception ignored) {}
        }

        public <T extends ServerEvent> T waitForEvent(Class<T> type, long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                drainEvents();
                for (ServerEvent ev : receivedEvents) {
                    if (type.isInstance(ev)) {
                        return type.cast(ev);
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            socket.close();
        }
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        // Room capacity 2 for immediate auto-start
        ServerConfig config = new ServerConfig(0, 2, 10_000, 10);
        server = new ServerApp(config);
        server.start();

        int retries = 50;
        while ((port = server.getBoundPort()) <= 0 && retries-- > 0) {
            Thread.sleep(20);
        }
        assertTrue(port > 0, "Server failed to bind");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Complete end-to-end flow: connection, lobby joining, game start, and card play")
    void fullEndToEndGameFlow() throws Exception {
        try (RemotePlayerClient alice = new RemotePlayerClient(port);
             RemotePlayerClient bob = new RemotePlayerClient(port)) {

            // 1. Alice joins
            alice.send("JOIN Alice");
            ServerEvent.Welcome aliceWelcome = alice.waitForEvent(ServerEvent.Welcome.class, 2000);
            assertNotNull(aliceWelcome);
            assertTrue(aliceWelcome.message().contains("Alice"));

            // 2. Bob joins -> triggers auto-match start!
            bob.send("JOIN Bob");
            ServerEvent.Welcome bobWelcome = bob.waitForEvent(ServerEvent.Welcome.class, 2000);
            assertNotNull(bobWelcome);
            assertTrue(bobWelcome.message().contains("Bob"));

            // 3. Both receive game start events
            ServerEvent.TopCard topCardEvent = alice.waitForEvent(ServerEvent.TopCard.class, 2000);
            assertNotNull(topCardEvent, "Alice should have received initial TOP_CARD");

            ServerEvent.HandUpdate aliceHandEvent = alice.waitForEvent(ServerEvent.HandUpdate.class, 2000);
            assertNotNull(aliceHandEvent, "Alice should have received private HAND_UPDATE");
            assertEquals(7, aliceHandEvent.cards().size());

            ServerEvent.HandUpdate bobHandEvent = bob.waitForEvent(ServerEvent.HandUpdate.class, 2000);
            assertNotNull(bobHandEvent, "Bob should have received private HAND_UPDATE");
            assertEquals(7, bobHandEvent.cards().size());

            // 4. Verify turn announcement
            ServerEvent.TurnAnnounced turnEvent = alice.waitForEvent(ServerEvent.TurnAnnounced.class, 2000);
            assertNotNull(turnEvent);
            String activePlayer = turnEvent.activePlayerName();
            assertTrue(activePlayer.equals("Alice") || activePlayer.equals("Bob"));

            // 5. Active player performs a DRAW command
            RemotePlayerClient activeClient = activePlayer.equals("Alice") ? alice : bob;
            activeClient.send("DRAW");

            ServerEvent.DrawResult drawResult = activeClient.waitForEvent(ServerEvent.DrawResult.class, 2000);
            assertNotNull(drawResult, "Active player should have received DRAW_RESULT");

            // Active player passes
            activeClient.send("PASS");
            Thread.sleep(200);

            // 6. Turn successfully advanced to the next player
            alice.drainEvents();
            bob.drainEvents();
            boolean gotNextTurn = alice.receivedEvents.stream()
                    .filter(e -> e instanceof ServerEvent.TurnAnnounced)
                    .count() >= 2;
            assertTrue(gotNextTurn, "Turn should have advanced after pass");
        }
    }
}
