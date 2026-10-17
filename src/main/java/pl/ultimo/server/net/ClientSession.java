package pl.ultimo.server.net;

import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ProtocolException;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.metrics.MetricsRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Universal client session handling both Raw TCP wire socket connections and RFC 6455 WebSockets.
 * Automatically multiplexes protocol based on the initial handshake headers on a single TCP port.
 */
public class ClientSession implements Runnable, Closeable {

    private final String sessionId;
    private final Socket socket;
    private final ConnectionListener listener;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private final InputStream rawInputStream;
    private final OutputStream rawOutputStream;

    private volatile boolean isWebSocket = false;
    private volatile String username;

    public ClientSession(String sessionId, Socket socket, ConnectionListener listener) throws IOException {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.socket = Objects.requireNonNull(socket, "socket must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");

        this.rawInputStream = socket.getInputStream();
        this.rawOutputStream = socket.getOutputStream();

        MetricsRegistry.getInstance().recordConnectionOpened(false);
    }

    @Override
    public void run() {
        try {
            String firstLine = readAsciiLine(rawInputStream);
            if (firstLine == null) {
                return;
            }

            if (firstLine.startsWith("GET ")) {
                handleHttpOrWebSocket(firstLine);
            } else {
                handleTcpConnection(firstLine);
            }
        } catch (IOException e) {
            if (!isClosed.get() && !Thread.currentThread().isInterrupted()) {
                System.err.println("Session " + sessionId + " encountered I/O error: " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    private void handleHttpOrWebSocket(String requestLine) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = readAsciiLine(rawInputStream)) != null && !headerLine.isEmpty()) {
            int colon = headerLine.indexOf(':');
            if (colon > 0) {
                String key = headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String val = headerLine.substring(colon + 1).trim();
                headers.put(key, val);
            }
        }

        String upgrade = headers.get("upgrade");
        if (upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
            String secKey = headers.get("sec-websocket-key");
            if (secKey != null) {
                String acceptKey = WebSocketFrameCodec.computeAcceptKey(secKey);
                String handshakeResponse = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";

                synchronized (writeLock) {
                    rawOutputStream.write(handshakeResponse.getBytes(StandardCharsets.UTF_8));
                    rawOutputStream.flush();
                }

                this.isWebSocket = true;
                MetricsRegistry.getInstance().recordWebSocketUpgrade();
                runWebSocketLoop();
                return;
            }
        }

        // Standard HTTP GET request: serve static web assets or healthcheck
        handleStaticHttpRequest(requestLine);
    }

    private void handleStaticHttpRequest(String requestLine) throws IOException {
        String path = "/";
        String[] parts = requestLine.split(" ");
        if (parts.length > 1) {
            path = parts[1];
        }

        byte[] body;
        String contentType;
        if (path.equals("/metrics")) {
            body = MetricsRegistry.getInstance().toPrometheusText().getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain; version=0.0.4; charset=utf-8";
        } else if (path.equals("/health") || path.equals("/ping") || path.equals("/healthz") || path.equals("/ready")) {
            body = "OK".getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain";
        } else {
            body = WebClientAssets.getHtmlPage().getBytes(StandardCharsets.UTF_8);
            contentType = "text/html; charset=UTF-8";
        }

        String responseHeader = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        synchronized (writeLock) {
            rawOutputStream.write(responseHeader.getBytes(StandardCharsets.UTF_8));
            rawOutputStream.write(body);
            rawOutputStream.flush();
        }
        close();
    }

    private void runWebSocketLoop() {
        try {
            while (!isClosed.get()) {
                WebSocketFrameCodec.Frame frame;
                try {
                    frame = WebSocketFrameCodec.readFrame(rawInputStream);
                } catch (SocketTimeoutException e) {
                    synchronized (writeLock) {
                        WebSocketFrameCodec.writePongFrame(rawOutputStream, new byte[0]);
                    }
                    continue;
                }

                if (frame == null || frame.isClose()) {
                    if (frame != null && frame.isClose()) {
                        synchronized (writeLock) {
                            WebSocketFrameCodec.writeCloseFrame(rawOutputStream);
                        }
                    }
                    break;
                }

                if (frame.isPing()) {
                    synchronized (writeLock) {
                        WebSocketFrameCodec.writePongFrame(rawOutputStream, frame.payload());
                    }
                    continue;
                }

                if (frame.isText()) {
                    String line = frame.text().trim();
                    if (!line.isEmpty()) {
                        processCommandLine(line);
                    }
                }
            }
        } catch (IOException e) {
            if (!isClosed.get() && !Thread.currentThread().isInterrupted()) {
                // Connection closed normally
            }
        } finally {
            close();
        }
    }

    private void handleTcpConnection(String firstLine) throws IOException {
        processCommandLine(firstLine);

        BufferedReader reader = new BufferedReader(new InputStreamReader(rawInputStream, StandardCharsets.UTF_8));
        while (!isClosed.get()) {
            String line;
            try {
                line = reader.readLine();
            } catch (SocketTimeoutException e) {
                sendEvent(new ServerEvent.Pong());
                continue;
            }

            if (line == null) {
                break;
            }

            if (line.isBlank()) {
                continue;
            }

            processCommandLine(line);
        }
    }

    private void processCommandLine(String line) {
        try {
            ClientCommand command = ProtocolCodec.decodeCommand(line);
            listener.onCommandReceived(this, command);
        } catch (ProtocolException pe) {
            MetricsRegistry.getInstance().recordError("PROTOCOL_ERROR");
            sendEvent(new ServerEvent.ErrorEvent("Protocol error: " + pe.getMessage()));
        }
    }

    /**
     * Sends a ServerEvent to the connected client.
     * Automatically formats the event as an RFC 6455 text frame or a raw newline-terminated TCP string.
     */
    public void sendEvent(ServerEvent event) {
        if (isClosed.get()) {
            return;
        }

        MetricsRegistry.getInstance().recordEventEmitted(event.type().name());
        if (event instanceof ServerEvent.ErrorEvent) {
            MetricsRegistry.getInstance().recordError("ERROR_EVENT");
        }

        synchronized (writeLock) {
            if (isClosed.get()) {
                return;
            }
            try {
                if (isWebSocket) {
                    String wire = ProtocolCodec.encodeEvent(event).trim();
                    WebSocketFrameCodec.writeTextFrame(rawOutputStream, wire);
                } else {
                    String wire = ProtocolCodec.encodeEvent(event);
                    rawOutputStream.write(wire.getBytes(StandardCharsets.UTF_8));
                    rawOutputStream.flush();
                }
            } catch (IOException e) {
                close();
            }
        }
    }

    private String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }
        if (b == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    public boolean isWebSocket() {
        return isWebSocket;
    }

    public Socket getSocket() {
        return socket;
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            MetricsRegistry.getInstance().recordConnectionClosed(isWebSocket);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            listener.onSessionDisconnected(this);
        }
    }

    @Override
    public String toString() {
        return "ClientSession{"
                + "sessionId='" + sessionId + '\''
                + ", username='" + username + '\''
                + ", isWebSocket=" + isWebSocket
                + ", closed=" + isClosed.get()
                + '}';
    }
}
