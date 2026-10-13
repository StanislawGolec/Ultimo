package pl.ultimo.server.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.ultimo.server.ServerApp;
import pl.ultimo.server.config.ServerConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebSocket & HTTP Integration Tests")
class WebSocketIntegrationTest {

    private ServerApp server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        ServerConfig config = new ServerConfig(0, 4, 60_000, 30);
        server = new ServerApp(config);
        server.start();

        long deadline = System.currentTimeMillis() + 3000;
        while (server.getBoundPort() <= 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        port = server.getBoundPort();
        assertTrue(port > 0, "Server failed to bind dynamic port");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should serve static HTML5 web client on HTTP GET /")
    void shouldServeStaticWebPage() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<!DOCTYPE html>"));
        assertTrue(response.body().contains("Ultimo Multiplayer | Web Edition"));
    }

    @Test
    @DisplayName("Should respond 200 OK to healthcheck probe on HTTP GET /health")
    void shouldRespondToHealthcheck() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/health"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body());
    }

    @Test
    @DisplayName("Should establish WebSocket connection and handle JOIN command")
    void shouldHandleWebSocketClientConnection() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch welcomeLatch = new CountDownLatch(1);

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                String msg = data.toString();
                receivedMessages.add(msg);
                if (msg.startsWith("WELCOME")) {
                    welcomeLatch.countDown();
                }
                return WebSocket.Listener.super.onText(webSocket, data, last);
            }
        };

        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
                .get(5, TimeUnit.SECONDS);

        ws.sendText("JOIN WebAlice\n", true);

        boolean receivedWelcome = welcomeLatch.await(5, TimeUnit.SECONDS);
        assertTrue(receivedWelcome, "Expected WELCOME message over WebSocket within 5 seconds");
        assertTrue(receivedMessages.stream().anyMatch(m -> m.contains("Welcome to Ultimo, WebAlice!")));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Goodbye").get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should start match between two WebSocket players when START command is issued")
    void shouldStartMatchBetweenTwoWebSocketPlayersWithStartCommand() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<String> aliceMsgs = Collections.synchronizedList(new ArrayList<>());
        List<String> bobMsgs = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch startLatch = new CountDownLatch(2);

        WebSocket wsAlice = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        String msg = data.toString();
                        aliceMsgs.add(msg);
                        if (msg.startsWith("HAND_UPDATE")) {
                            startLatch.countDown();
                        }
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }
                })
                .get(5, TimeUnit.SECONDS);

        WebSocket wsBob = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        String msg = data.toString();
                        bobMsgs.add(msg);
                        if (msg.startsWith("HAND_UPDATE")) {
                            startLatch.countDown();
                        }
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }
                })
                .get(5, TimeUnit.SECONDS);

        wsAlice.sendText("JOIN Alice\n", true);
        wsBob.sendText("JOIN Bob\n", true);

        Thread.sleep(200);

        // Alice sends START to commence 2-player match
        wsAlice.sendText("START\n", true);

        boolean started = startLatch.await(5, TimeUnit.SECONDS);
        assertTrue(started, "Expected both players to receive HAND_UPDATE after START command");

        // Verify game state events arrived for both players
        assertTrue(aliceMsgs.stream().anyMatch(m -> m.startsWith("TOP_CARD")));
        assertTrue(aliceMsgs.stream().anyMatch(m -> m.startsWith("TURN_ANNOUNCED")));
        assertTrue(bobMsgs.stream().anyMatch(m -> m.startsWith("TOP_CARD")));
        assertTrue(bobMsgs.stream().anyMatch(m -> m.startsWith("TURN_ANNOUNCED")));

        wsAlice.sendClose(WebSocket.NORMAL_CLOSURE, "Bye");
        wsBob.sendClose(WebSocket.NORMAL_CLOSURE, "Bye");
    }
}
