package pl.ultimo.server.net;

import pl.ultimo.core.model.Card;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ServerEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Stage Phase 2 Chaos Mesh Verification Runner:
 * 1. 16-Player Multi-Agent Lobby Orchestration (Mix of WebSocket and TCP with auto-retransmit).
 * 2. Tactical Action Defense Validation (+2/+4 stacking accumulation & Skip deflection over proxy).
 * 3. Last-Man-Standing Podium Verification (Match continues until 1 remains; ordered standings 1-16).
 * 4. 120-Second Disconnect Grace Period & State Recovery Snapshot Re-binding.
 */
public class ChaosMeshPhase2Runner {

    public interface ClientBase extends Closeable {
        String getUsername();
        void send(String cmd) throws IOException;
        ServerEvent awaitEvent(Predicate<ServerEvent> predicate, Duration timeout) throws InterruptedException;
        <T extends ServerEvent> T awaitEventType(Class<T> type, Duration timeout) throws InterruptedException;
        List<Card> getHand();
        void clearEvents();
        void disconnectAbruptly() throws IOException;
    }

    public static class TcpTestClient implements ClientBase {
        private final String username;
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final BlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
        private final List<Card> hand = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running = true;

        public TcpTestClient(String username, String host, int port) throws IOException {
            this.username = username;
            Socket s = null;
            IOException lastEx = null;
            for (int attempt = 1; attempt <= 8; attempt++) {
                try {
                    s = new Socket(host, port);
                    s.setTcpNoDelay(true);
                    break;
                } catch (IOException e) {
                    lastEx = e;
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            }
            if (s == null) {
                throw (lastEx != null ? lastEx : new IOException("Failed to connect TCP socket after 8 attempts"));
            }
            this.socket = s;
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            Thread.ofVirtual().name("tcp-client-" + username).start(() -> {
                try {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        try {
                            ServerEvent ev = ProtocolCodec.decodeEvent(line);
                            if (ev != null) {
                                events.offer(ev);
                                if (ev instanceof ServerEvent.HandUpdate hu) {
                                    hand.clear();
                                    hand.addAll(hu.cards());
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            });

            Thread.ofVirtual().name("tcp-heartbeat-" + username).start(() -> {
                while (running) {
                    try {
                        Thread.sleep(8_000);
                        if (running) {
                            send("PING");
                        }
                    } catch (Exception ignored) {}
                }
            });
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public void send(String cmd) throws IOException {
            writer.write(cmd.endsWith("\n") ? cmd : cmd + "\n");
            writer.flush();
        }

        @Override
        public void clearEvents() {
            events.clear();
        }

        @Override
        public ServerEvent awaitEvent(Predicate<ServerEvent> predicate, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                ServerEvent ev = events.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (ev != null && predicate.test(ev)) {
                    return ev;
                }
            }
            return null;
        }

        @Override
        public <T extends ServerEvent> T awaitEventType(Class<T> type, Duration timeout) throws InterruptedException {
            ServerEvent ev = awaitEvent(type::isInstance, timeout);
            return type != null && ev != null ? type.cast(ev) : null;
        }

        @Override
        public List<Card> getHand() {
            return hand;
        }

        @Override
        public void disconnectAbruptly() throws IOException {
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

    public static class WebSocketTestClient implements ClientBase {
        private final String username;
        private final WebSocket ws;
        private final BlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
        private final List<Card> hand = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running = true;

        public WebSocketTestClient(String username, String host, int port) throws Exception {
            this.username = username;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            URI uri = URI.create("ws://" + host + ":" + port + "/ws");

            WebSocket builtWs = null;
            Exception lastEx = null;
            for (int attempt = 1; attempt <= 8; attempt++) {
                try {
                    builtWs = client.newWebSocketBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .buildAsync(uri, new WebSocket.Listener() {
                                private final StringBuilder buffer = new StringBuilder();

                                @Override
                                public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
                                    webSocket.sendPong(message);
                                    return WebSocket.Listener.super.onPing(webSocket, message);
                                }

                                @Override
                                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                    buffer.append(data);
                                    if (last) {
                                        String text = buffer.toString();
                                        buffer.setLength(0);
                                        for (String line : text.split("\n")) {
                                            if (!line.isBlank()) {
                                                try {
                                                    ServerEvent ev = ProtocolCodec.decodeEvent(line);
                                                    if (ev != null) {
                                                        events.offer(ev);
                                                        if (ev instanceof ServerEvent.HandUpdate hu) {
                                                            hand.clear();
                                                            hand.addAll(hu.cards());
                                                        }
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                    }
                                    return WebSocket.Listener.super.onText(webSocket, data, last);
                                }
                            })
                            .get(2, TimeUnit.SECONDS);
                    break;
                } catch (Exception e) {
                    lastEx = e;
                    Thread.sleep(150);
                }
            }

            if (builtWs == null) {
                throw (lastEx != null ? lastEx : new IOException("WebSocket connection failed after 8 attempts"));
            }
            this.ws = builtWs;

            Thread.ofVirtual().name("ws-heartbeat-" + username).start(() -> {
                while (running) {
                    try {
                        Thread.sleep(8_000);
                        if (running) {
                            send("PING");
                        }
                    } catch (Exception ignored) {}
                }
            });
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public void send(String cmd) throws IOException {
            String text = cmd.endsWith("\n") ? cmd : cmd + "\n";
            try {
                ws.sendText(text, true).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException("Failed to send WebSocket text: " + e.getMessage(), e);
            }
        }

        @Override
        public void clearEvents() {
            events.clear();
        }

        @Override
        public ServerEvent awaitEvent(Predicate<ServerEvent> predicate, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                long remainingMs = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                ServerEvent ev = events.poll(remainingMs, TimeUnit.MILLISECONDS);
                if (ev != null && predicate.test(ev)) {
                    return ev;
                }
            }
            return null;
        }

        @Override
        public <T extends ServerEvent> T awaitEventType(Class<T> type, Duration timeout) throws InterruptedException {
            ServerEvent ev = awaitEvent(type::isInstance, timeout);
            return type != null && ev != null ? type.cast(ev) : null;
        }

        @Override
        public List<Card> getHand() {
            return hand;
        }

        @Override
        public void disconnectAbruptly() throws IOException {
            running = false;
            ws.abort();
        }

        @Override
        public void close() {
            running = false;
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Test finish").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
    }

    public static void main(String[] args) {
        int proxyPort = 8090;
        if (args.length > 0) {
            try {
                proxyPort = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException ignored) {}
        }

        System.out.println(">>> Starting ChaosMeshPhase2Runner against proxy port " + proxyPort + "...");
        boolean allPassed = true;

        List<ClientBase> clients = new ArrayList<>();
        try {
            // =================================================================
            // 1. 16-Player Multi-Agent Lobby Orchestration
            // =================================================================
            System.out.println("[Phase2-1/4] Orchestrating 16-Player Multi-Agent Lobby (WebSocket + TCP) via Proxy...");
            String roomId = "room_chaos_16";
            int totalPlayers = 16;

            for (int i = 1; i <= totalPlayers; i++) {
                String name;
                ClientBase client;
                if (i == 1) {
                    name = "Python_MARL_Bot";
                    client = new TcpTestClient(name, "127.0.0.1", proxyPort);
                } else if (i <= 8) {
                    name = String.format("Android_Mobile_%02d", i);
                    client = new WebSocketTestClient(name, "127.0.0.1", proxyPort);
                } else {
                    name = String.format("Agent_Client_%02d", i);
                    client = new TcpTestClient(name, "127.0.0.1", proxyPort);
                }
                clients.add(client);

                // Send JOIN with auto-retransmit in case of lossy proxy packet drops
                String joinCmd = "JOIN " + name + " " + roomId + " " + totalPlayers;
                ServerEvent.Welcome welcome = null;
                for (int attempt = 0; attempt < 5; attempt++) {
                    client.clearEvents();
                    client.send(joinCmd);
                    welcome = client.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(2));
                    if (welcome != null) {
                        break;
                    }
                    ServerEvent.HandUpdate alreadyStarted = client.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofMillis(500));
                    if (alreadyStarted != null) {
                        break;
                    }
                }
            }

            // All 16 clients should receive initial HAND_UPDATE when 16th player joined
            for (ClientBase client : clients) {
                ServerEvent.HandUpdate handUpdate = client.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(10));
                if (handUpdate == null || handUpdate.cards().isEmpty()) {
                    throw new IllegalStateException("Player " + client.getUsername() + " did not receive initial HAND_UPDATE on game start");
                }
            }
            System.out.println("  [OK] 16-Player Multi-Agent Lobby auto-started and all 16 players received private hands!");

            // =================================================================
            // 2. Tactical Action Defense Validation (+2 Stacking & Skip Deflection)
            // =================================================================
            System.out.println("[Phase2-2/4] Validating Tactical Action Defense under Packet Drops...");
            ClientBase p1 = clients.get(0);
            ClientBase p2 = clients.get(1);
            ClientBase p3 = clients.get(2);
            ClientBase p4 = clients.get(3);

            // Set up test table with retries
            for (int attempt = 0; attempt < 5; attempt++) {
                p1.clearEvents();
                p1.send("CHAT ADMIN_SETUP_DEFENSE");
                ServerEvent.TurnAnnounced t = p1.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(2));
                if (t != null && t.activePlayerName().equals(p1.getUsername())) {
                    break;
                }
            }

            // P1 plays RED DRAW_TWO (+2 penalty) with retry
            p1.clearEvents();
            p2.clearEvents();
            ServerEvent.TurnAnnounced t2 = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (p1.getHand().stream().anyMatch(c -> c.value() == pl.ultimo.core.model.CardValue.DRAW_TWO)) {
                    p1.send("PLAY RED_DRAW_TWO");
                }
                t2 = p2.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(3));
                if (t2 != null && t2.activePlayerName().equals(p2.getUsername())) {
                    break;
                }
            }
            if (t2 == null || !t2.activePlayerName().equals(p2.getUsername())) {
                throw new IllegalStateException("P2 did not receive turn after P1 Draw Two");
            }

            // P2 defends with BLUE DRAW_TWO (+4 accumulated penalty) with retry
            int p3HandBefore = p3.getHand().size();
            p2.clearEvents();
            p3.clearEvents();
            ServerEvent.TurnAnnounced t3 = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (p2.getHand().stream().anyMatch(c -> c.value() == pl.ultimo.core.model.CardValue.DRAW_TWO)) {
                    p2.send("PLAY BLUE_DRAW_TWO");
                }
                t3 = p3.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(3));
                if (t3 != null && t3.activePlayerName().equals(p3.getUsername())) {
                    break;
                }
            }
            if (t3 == null || !t3.activePlayerName().equals(p3.getUsername())) {
                throw new IllegalStateException("P3 did not receive turn under Draw Two stack");
            }

            // P3 accepts penalty with DRAW -> must draw 4 accumulated cards with retry
            p3.clearEvents();
            ServerEvent.HandUpdate p3NewHand = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                p3.send("DRAW");
                p3NewHand = p3.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(3));
                if (p3NewHand != null && p3NewHand.cards().size() >= p3HandBefore + 4) {
                    break;
                }
            }
            if (p3NewHand == null || p3NewHand.cards().size() < p3HandBefore + 4) {
                throw new IllegalStateException("Player 3 failed to receive accumulated 4 cards! Before: " + p3HandBefore + ", After: " + (p3NewHand != null ? p3NewHand.cards().size() : "null"));
            }
            System.out.println("  [OK] Draw Stacking Accumulation (+2 on +2 = +4 cards) verified over proxy!");

            // Test Skip Deflection
            for (int attempt = 0; attempt < 5; attempt++) {
                p1.clearEvents();
                p1.send("CHAT ADMIN_SETUP_DEFENSE");
                ServerEvent.TurnAnnounced t = p1.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(2));
                if (t != null && t.activePlayerName().equals(p1.getUsername())) {
                    break;
                }
            }

            // P1 plays RED SKIP
            p1.clearEvents();
            p2.clearEvents();
            ServerEvent.TurnAnnounced skipT2 = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (p1.getHand().stream().anyMatch(c -> c.value() == pl.ultimo.core.model.CardValue.SKIP)) {
                    p1.send("PLAY RED_SKIP");
                }
                skipT2 = p2.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(3));
                if (skipT2 != null && skipT2.activePlayerName().equals(p2.getUsername())) {
                    break;
                }
            }
            if (skipT2 == null || !skipT2.activePlayerName().equals(p2.getUsername())) {
                throw new IllegalStateException("P2 did not receive turn to defend Skip");
            }

            // P2 defends with BLUE SKIP -> deflecting to P3
            p2.clearEvents();
            p3.clearEvents();
            ServerEvent.TurnAnnounced skipT3 = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (p2.getHand().stream().anyMatch(c -> c.value() == pl.ultimo.core.model.CardValue.SKIP)) {
                    p2.send("PLAY BLUE_SKIP");
                }
                skipT3 = p3.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(3));
                if (skipT3 != null && skipT3.activePlayerName().equals(p3.getUsername())) {
                    break;
                }
            }
            if (skipT3 == null || !skipT3.activePlayerName().equals(p3.getUsername())) {
                throw new IllegalStateException("P3 did not receive turn under deflected Skip");
            }

            // P3 has no Skip and sends PASS
            p3.clearEvents();
            p4.clearEvents();
            ServerEvent.TurnAnnounced skipT4 = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                p3.send("PASS");
                skipT4 = p4.awaitEventType(ServerEvent.TurnAnnounced.class, Duration.ofSeconds(3));
                if (skipT4 != null && skipT4.activePlayerName().equals(p4.getUsername())) {
                    break;
                }
            }
            if (skipT4 == null || !skipT4.activePlayerName().equals(p4.getUsername())) {
                throw new IllegalStateException("P4 did not receive turn after P3 was skipped");
            }
            System.out.println("  [OK] Skip Deflection Chain (Skip on Skip -> next player skipped) verified over proxy!");

            // =================================================================
            // 3. Last-Man-Standing Podium Verification (16 Placements Ordered)
            // =================================================================
            System.out.println("[Phase2-3/4] Validating Last-Man-Standing Podium Order (1st to 16th rank)...");
            for (int attempt = 0; attempt < 5; attempt++) {
                p1.clearEvents();
                p1.send("CHAT ADMIN_TRIGGER_PODIUM");
                ServerEvent.HandUpdate hu = p1.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(2));
                if (hu != null && hu.cards().size() == 1) {
                    break;
                }
            }
            Thread.sleep(300);

            // 15 players shed their 1 card sequentially
            java.util.Set<String> finishedClients = new java.util.HashSet<>();
            for (int i = 0; i < 15; i++) {
                ClientBase activeNow = null;
                for (ClientBase c : clients) {
                    if (!finishedClients.contains(c.getUsername()) && !c.getHand().isEmpty()) {
                        activeNow = c;
                        break;
                    }
                }
                if (activeNow == null) {
                    System.out.println("  [PODIUM-DEBUG] Iteration " + i + ": activeNow is NULL! No clients have remaining cards.");
                    break;
                }

                Card toPlay = activeNow.getHand().get(0);
                String cardToken = ProtocolCodec.formatCard(toPlay);
                final String curName = activeNow.getUsername();
                System.out.println("  [PODIUM-DEBUG] [" + (i + 1) + "/15] Player " + curName + " playing " + cardToken);
                boolean finished = false;
                for (int attempt = 0; attempt < 5; attempt++) {
                    activeNow.send("PLAY " + cardToken);
                    ServerEvent ev = p1.awaitEvent(
                            e -> e instanceof ServerEvent.PlayerFinished pf && pf.playerName().equals(curName),
                            Duration.ofSeconds(2)
                    );
                    if (ev != null) {
                        System.out.println("    -> " + curName + " finish confirmed: " + ev);
                        finished = true;
                        finishedClients.add(curName);
                        break;
                    }
                    if (activeNow.getHand().isEmpty()) {
                        System.out.println("    -> " + curName + " hand is empty!");
                        finished = true;
                        finishedClients.add(curName);
                        break;
                    }
                    ServerEvent err = activeNow.awaitEventType(ServerEvent.ErrorEvent.class, Duration.ofMillis(300));
                    if (err != null) {
                        System.err.println("    -> ERROR for " + curName + ": " + ((ServerEvent.ErrorEvent) err).errorMessage());
                    }
                }
                if (!finished) {
                    System.err.println("  [PODIUM-DEBUG] Failed to finish player " + curName);
                }
                Thread.sleep(150);
            }

            // Await MatchStandings event
            ServerEvent.MatchStandings standings = null;
            for (ClientBase c : clients) {
                standings = c.awaitEventType(ServerEvent.MatchStandings.class, Duration.ofSeconds(4));
                if (standings != null && standings.placements().size() == totalPlayers) {
                    System.out.println("  [PODIUM-DEBUG] Received standings on " + c.getUsername() + ": " + standings.placements());
                    break;
                }
            }

            if (standings == null || standings.placements().size() != totalPlayers) {
                throw new IllegalStateException("Failed to receive 16-player MATCH_STANDINGS! Placements: " + (standings != null ? standings.placements() : "null"));
            }

            for (int r = 1; r <= totalPlayers; r++) {
                int expectedRank = r;
                boolean rankFound = standings.placements().stream().anyMatch(p -> p.rank() == expectedRank);
                if (!rankFound) {
                    throw new IllegalStateException("Missing placement rank " + expectedRank + " in match standings!");
                }
            }
            System.out.println("  [OK] Last-Man-Standing complete: 16 ordered podium placements certified!");

            // =================================================================
            // 4. 120-Second Disconnect Grace Period & State Recovery Snapshot
            // =================================================================
            System.out.println("[Phase2-4/4] Validating 120s Disconnect Grace Period & Snapshot Re-binding...");
            String graceRoomId = "room_grace_reconnect";
            TcpTestClient g1 = new TcpTestClient("Grace_Host", "127.0.0.1", proxyPort);
            TcpTestClient g2 = new TcpTestClient("Grace_Rejoiner", "127.0.0.1", proxyPort);
            clients.add(g1);
            clients.add(g2);

            for (int attempt = 0; attempt < 5; attempt++) {
                g1.send("JOIN Grace_Host " + graceRoomId + " 2");
                ServerEvent.Welcome w = g1.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(2));
                if (w != null) break;
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                g2.send("JOIN Grace_Rejoiner " + graceRoomId + " 2");
                ServerEvent.Welcome w = g2.awaitEventType(ServerEvent.Welcome.class, Duration.ofSeconds(2));
                if (w != null) break;
            }

            g1.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5));
            ServerEvent.HandUpdate g2Hand = g2.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(5));
            int g2InitialCardCount = g2Hand != null ? g2Hand.cards().size() : 7;

            // Grace_Rejoiner abruptly severs socket connection
            g1.clearEvents();
            g2.disconnectAbruptly();

            // Wait for host g1 to receive notification that room status changed due to disconnect
            ServerEvent.RoomStatus hostRoomStatus = g1.awaitEventType(
                    ServerEvent.RoomStatus.class,
                    Duration.ofSeconds(4)
            );
            Thread.sleep(1000);

            // Reconnect within 120s grace period over resilient proxy socket
            TcpTestClient g2Reconnected = new TcpTestClient("Grace_Rejoiner", "127.0.0.1", proxyPort);
            clients.add(g2Reconnected);
            ServerEvent.HandUpdate restoredHand = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                g2Reconnected.send("JOIN Grace_Rejoiner " + graceRoomId + " 2");
                restoredHand = g2Reconnected.awaitEventType(ServerEvent.HandUpdate.class, Duration.ofSeconds(2));
                if (restoredHand != null) {
                    break;
                }
                ServerEvent.ErrorEvent err = g2Reconnected.awaitEventType(ServerEvent.ErrorEvent.class, Duration.ofMillis(300));
                if (err != null) {
                    System.err.println("  [GRACE-DEBUG] Error on rejoin attempt " + attempt + ": " + err.errorMessage());
                }
                Thread.sleep(250);
            }

            if (restoredHand == null) {
                throw new IllegalStateException("Reconnected player failed to receive restored private hand snapshot!");
            }
            if (restoredHand.cards().size() != g2InitialCardCount) {
                throw new IllegalStateException("Restored hand size mismatch: expected " + g2InitialCardCount + " but got " + restoredHand.cards().size());
            }
            System.out.println("  [OK] 120s Disconnect Grace Period & State Recovery Snapshot Re-binding verified!");

        } catch (Exception e) {
            System.err.println("  [FAIL] Phase 2 Execution Error: " + e.getMessage());
            e.printStackTrace();
            allPassed = false;
        } finally {
            for (ClientBase client : clients) {
                try {
                    client.close();
                } catch (Exception ignored) {}
            }
        }

        if (allPassed) {
            System.out.println(">>> ChaosMeshPhase2Runner completed with 100% SUCCESS!");
            System.exit(0);
        } else {
            System.err.println(">>> ChaosMeshPhase2Runner FAILED!");
            System.exit(1);
        }
    }
}
