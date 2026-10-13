package pl.ultimo.server.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Network Layer and Concurrency Integration Tests")
class NetworkLayerTest {

    private SocketAcceptor acceptor;
    private Thread acceptorThread;
    private ExecutorService virtualThreadExecutor;
    private int serverPort;

    private final List<ClientCommand> receivedCommands = Collections.synchronizedList(new ArrayList<>());
    private final List<ClientSession> disconnectedSessions = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<ClientSession> activeServerSession = new AtomicReference<>();
    private CountDownLatch commandLatch;
    private CountDownLatch disconnectLatch;

    @BeforeEach
    void setUp() throws InterruptedException {
        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        receivedCommands.clear();
        disconnectedSessions.clear();
        activeServerSession.set(null);
        commandLatch = new CountDownLatch(1);
        disconnectLatch = new CountDownLatch(1);

        // Bind to ephemeral port 0
        ServerConfig config = new ServerConfig(0, 4, 10_000, 10);
        ConnectionListener listener = new ConnectionListener() {
            @Override
            public void onCommandReceived(ClientSession session, ClientCommand command) {
                receivedCommands.add(command);
                commandLatch.countDown();
            }

            @Override
            public void onSessionDisconnected(ClientSession session) {
                disconnectedSessions.add(session);
                disconnectLatch.countDown();
            }
        };

        acceptor = new SocketAcceptor(config, socket -> {
            try {
                ClientSession session = new ClientSession("session-1", socket, listener);
                activeServerSession.set(session);
                virtualThreadExecutor.submit(session);
            } catch (Exception e) {
                fail("Failed to create ClientSession: " + e.getMessage());
            }
        });

        acceptorThread = Thread.ofPlatform().name("test-acceptor").start(acceptor);

        // Wait for server to bind
        int retries = 50;
        while ((serverPort = acceptor.getBoundPort()) <= 0 && retries-- > 0) {
            Thread.sleep(20);
        }
        assertTrue(serverPort > 0, "Acceptor failed to bind to a local port");
    }

    @AfterEach
    void tearDown() {
        if (acceptor != null) {
            acceptor.close();
        }
        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }
        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Client can connect and exchange messages bi-directionally over TCP")
    void clientCanConnectAndExchangeMessages() throws Exception {
        try (Socket clientSocket = new Socket("localhost", serverPort);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

            // Client sends JOIN command
            writer.write("JOIN Alice\n");
            writer.flush();

            assertTrue(commandLatch.await(3, TimeUnit.SECONDS), "Server did not receive command in time");
            assertEquals(1, receivedCommands.size());
            assertInstanceOf(ClientCommand.Join.class, receivedCommands.getFirst());
            assertEquals("Alice", ((ClientCommand.Join) receivedCommands.getFirst()).username());

            // Server replies with WELCOME event
            ClientSession serverSession = activeServerSession.get();
            assertNotNull(serverSession);
            serverSession.sendEvent(new ServerEvent.Welcome("p-1", "Welcome to test!"));

            // Client receives WELCOME event
            String responseLine = reader.readLine();
            assertNotNull(responseLine);
            ServerEvent event = ProtocolCodec.decodeEvent(responseLine);
            assertInstanceOf(ServerEvent.Welcome.class, event);
            ServerEvent.Welcome welcome = (ServerEvent.Welcome) event;
            assertEquals("p-1", welcome.assignedPlayerId());
            assertEquals("Welcome to test!", welcome.message());
        }
    }

    @Test
    @DisplayName("Invalid wire protocol command produces an ERROR event back to client without crashing session")
    void invalidCommandProducesErrorEvent() throws Exception {
        try (Socket clientSocket = new Socket("localhost", serverPort);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))) {

            // Send malformed / unknown command
            writer.write("MALFORMED_COMMAND\n");
            writer.flush();

            String responseLine = reader.readLine();
            assertNotNull(responseLine);
            ServerEvent event = ProtocolCodec.decodeEvent(responseLine);
            assertInstanceOf(ServerEvent.ErrorEvent.class, event);
            assertTrue(((ServerEvent.ErrorEvent) event).errorMessage().contains("Unknown command"));
        }
    }

    @Test
    @DisplayName("Client disconnect triggers session cleanup and disconnection callback")
    void clientDisconnectTriggersCleanup() throws Exception {
        Socket clientSocket = new Socket("localhost", serverPort);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));

        writer.write("PING\n");
        writer.flush();
        assertTrue(commandLatch.await(3, TimeUnit.SECONDS));

        // Close socket from client side
        clientSocket.close();

        assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS), "Server did not detect disconnect in time");
        assertEquals(1, disconnectedSessions.size());
        assertTrue(disconnectedSessions.getFirst().isClosed());
    }

    @Test
    @DisplayName("Virtual threads can handle multiple concurrent client connections simultaneously")
    void virtualThreadsHandleConcurrentClients() throws Exception {
        int clientCount = 5;
        CountDownLatch allConnectedLatch = new CountDownLatch(clientCount);
        List<Socket> clientSockets = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            final int index = i;
            virtualThreadExecutor.submit(() -> {
                try {
                    Socket socket = new Socket("localhost", serverPort);
                    synchronized (clientSockets) {
                        clientSockets.add(socket);
                    }
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    writer.write("JOIN Player_" + index + "\n");
                    writer.flush();
                    allConnectedLatch.countDown();
                } catch (Exception e) {
                    fail("Concurrent client failed: " + e.getMessage());
                }
            });
        }

        assertTrue(allConnectedLatch.await(5, TimeUnit.SECONDS), "Not all clients connected in time");

        // Wait briefly for all commands to be processed
        Thread.sleep(500);
        assertTrue(receivedCommands.size() >= clientCount);

        // Clean up client sockets
        for (Socket s : clientSockets) {
            s.close();
        }
    }
}
