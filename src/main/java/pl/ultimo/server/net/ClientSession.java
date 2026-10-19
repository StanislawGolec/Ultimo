package pl.ultimo.server.net;

import pl.ultimo.protocol.ClientCommand;
import pl.ultimo.protocol.ProtocolCodec;
import pl.ultimo.protocol.ProtocolException;
import pl.ultimo.protocol.ServerEvent;
import pl.ultimo.server.metrics.MetricsRegistry;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Universal client session handling both Raw TCP wire socket connections and RFC 6455 WebSockets.
 * Employs non-blocking outbound queue serialization and ReentrantLock to completely eliminate
 * Project Loom Virtual Thread carrier pinning.
 */
public class ClientSession implements Runnable, Closeable {

    public static final int MAX_TCP_LINE_LENGTH = 8192;

    public static class FrameTooLongException extends IOException {
        public FrameTooLongException(String message) {
            super(message);
        }
    }

    private final String sessionId;
    private final Socket socket;
    private final ConnectionListener listener;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final ReentrantLock writeLock = new ReentrantLock();

    private final InputStream rawInputStream;
    private final OutputStream rawOutputStream;
    private final ByteArrayOutputStream currentLineBuffer = new ByteArrayOutputStream(256);

    private final BlockingQueue<ServerEvent> outboundQueue = new LinkedBlockingQueue<>(1024);
    private volatile Thread outboundWriterThread;
    private final int idleTimeoutMillis;
    private final AtomicLong lastActivityMillis = new AtomicLong(System.currentTimeMillis());
    private int consecutiveTimeouts = 0;

    private volatile boolean isWebSocket = false;
    private volatile String username;

    public ClientSession(String sessionId, Socket socket, ConnectionListener listener) throws IOException {
        this(sessionId, socket, listener, 60_000);
    }

    public ClientSession(String sessionId, Socket socket, ConnectionListener listener, int idleTimeoutMillis) throws IOException {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.socket = Objects.requireNonNull(socket, "socket must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        this.idleTimeoutMillis = idleTimeoutMillis;

        this.rawInputStream = socket.getInputStream();
        this.rawOutputStream = socket.getOutputStream();

        if (idleTimeoutMillis > 0) {
            int checkInterval = Math.max(50, idleTimeoutMillis / 2);
            socket.setSoTimeout(checkInterval);
        }

        MetricsRegistry.getInstance().recordConnectionOpened(false);
    }

    @Override
    public void run() {
        try {
            String firstLine;
            try {
                firstLine = readBoundedLine();
            } catch (FrameTooLongException ftle) {
                handleFrameTooLong();
                return;
            }
            if (firstLine == null) {
                return;
            }

            if (firstLine.startsWith("GET ")) {
                handleHttpOrWebSocket(firstLine);
            } else {
                if (!listener.isServerReady()) {
                    sendEvent(new ServerEvent.ErrorEvent("Server is shutting down"));
                    close();
                    return;
                }
                startOutboundWriter();
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

    private void startOutboundWriter() {
        this.outboundWriterThread = Thread.ofVirtual().name("session-writer-" + sessionId).start(this::runOutboundLoop);
    }

    private void runOutboundLoop() {
        try {
            while (!isClosed.get()) {
                ServerEvent event = outboundQueue.poll(200, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                writeEventDirectly(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!isClosed.get()) {
                close();
            }
        }
    }

    private void handleHttpOrWebSocket(String requestLine) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while (true) {
            try {
                headerLine = readBoundedLine();
            } catch (FrameTooLongException ftle) {
                handleFrameTooLong();
                return;
            }
            if (headerLine == null || headerLine.isEmpty()) {
                break;
            }
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

                writeLock.lock();
                try {
                    rawOutputStream.write(handshakeResponse.getBytes(StandardCharsets.UTF_8));
                    rawOutputStream.flush();
                } finally {
                    writeLock.unlock();
                }

                this.isWebSocket = true;
                MetricsRegistry.getInstance().recordWebSocketUpgrade();
                startOutboundWriter();
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
        int statusCode = 200;
        String statusText = "OK";

        if (path.equals("/metrics")) {
            body = MetricsRegistry.getInstance().toPrometheusText().getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain; version=0.0.4; charset=utf-8";
        } else if (path.equals("/health") || path.equals("/ping") || path.equals("/healthz")) {
            body = "OK".getBytes(StandardCharsets.UTF_8);
            contentType = "text/plain";
        } else if (path.equals("/ready")) {
            if (listener.isServerReady()) {
                body = "OK".getBytes(StandardCharsets.UTF_8);
                contentType = "text/plain";
            } else {
                body = "Service Unavailable".getBytes(StandardCharsets.UTF_8);
                contentType = "text/plain";
                statusCode = 503;
                statusText = "Service Unavailable";
            }
        } else {
            body = WebClientAssets.getHtmlBytes();
            contentType = "text/html; charset=UTF-8";
        }

        String responseHeader = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";

        writeLock.lock();
        try {
            rawOutputStream.write(responseHeader.getBytes(StandardCharsets.UTF_8));
            rawOutputStream.write(body);
            rawOutputStream.flush();
        } finally {
            writeLock.unlock();
        }
        close();
    }

    private void runWebSocketLoop() {
        try {
            while (!isClosed.get()) {
                WebSocketFrameCodec.Frame frame;
                try {
                    frame = WebSocketFrameCodec.readFrame(rawInputStream, true);
                } catch (WebSocketFrameCodec.WebSocketProtocolException wpe) {
                    MetricsRegistry.getInstance().recordError("WS_PROTOCOL_ERROR");
                    writeLock.lock();
                    try {
                        WebSocketFrameCodec.writeCloseFrame(rawOutputStream, wpe.getStatusCode(), wpe.getMessage());
                    } catch (IOException ignored) {
                    } finally {
                        writeLock.unlock();
                    }
                    break;
                } catch (SocketTimeoutException e) {
                    if (idleTimeoutMillis > 0) {
                        consecutiveTimeouts++;
                        if (consecutiveTimeouts >= 2) {
                            System.out.println("Session " + sessionId + " idle timeout exceeded without heartbeat. Closing.");
                            break;
                        }
                        writeLock.lock();
                        try {
                            WebSocketFrameCodec.writePingFrame(rawOutputStream, new byte[0]);
                        } finally {
                            writeLock.unlock();
                        }
                    }
                    continue;
                }

                if (frame == null || frame.isClose()) {
                    if (frame != null && frame.isClose()) {
                        writeLock.lock();
                        try {
                            WebSocketFrameCodec.writeCloseFrame(rawOutputStream, frame.closeCode(), "");
                        } finally {
                            writeLock.unlock();
                        }
                    }
                    break;
                }

                consecutiveTimeouts = 0;
                lastActivityMillis.set(System.currentTimeMillis());

                if (frame.isPing()) {
                    writeLock.lock();
                    try {
                        WebSocketFrameCodec.writePongFrame(rawOutputStream, frame.payload());
                    } finally {
                        writeLock.unlock();
                    }
                    continue;
                }

                if (frame.isPong()) {
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

        while (!isClosed.get()) {
            String line;
            try {
                line = readBoundedLine();
            } catch (SocketTimeoutException e) {
                if (idleTimeoutMillis > 0) {
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= 2) {
                        System.out.println("Session " + sessionId + " idle timeout exceeded without heartbeat. Closing.");
                        break;
                    }
                    sendTcpPingProbe();
                }
                continue;
            } catch (FrameTooLongException ftle) {
                handleFrameTooLong();
                break;
            }

            if (line == null) {
                break;
            }

            consecutiveTimeouts = 0;
            lastActivityMillis.set(System.currentTimeMillis());

            if (line.isBlank()) {
                continue;
            }

            processCommandLine(line);
        }
    }

    private void sendTcpPingProbe() {
        writeLock.lock();
        try {
            if (!isClosed.get()) {
                rawOutputStream.write("PING\n".getBytes(StandardCharsets.UTF_8));
                rawOutputStream.flush();
            }
        } catch (IOException e) {
            close();
        } finally {
            writeLock.unlock();
        }
    }

    private void handleFrameTooLong() {
        MetricsRegistry.getInstance().recordError("FRAME_TOO_LONG");
        writeLock.lock();
        try {
            if (!isClosed.get()) {
                rawOutputStream.write("ERROR FRAME_TOO_LONG: Frame length exceeds 8192 bytes\n".getBytes(StandardCharsets.UTF_8));
                rawOutputStream.flush();
            }
        } catch (IOException ignored) {
        } finally {
            writeLock.unlock();
        }
        close();
    }

    private void processCommandLine(String line) {
        if ("PONG".equalsIgnoreCase(line.trim())) {
            consecutiveTimeouts = 0;
            lastActivityMillis.set(System.currentTimeMillis());
            return;
        }
        try {
            ClientCommand command = ProtocolCodec.decodeCommand(line);
            if (command instanceof ClientCommand.Quit) {
                handleQuit(command);
                return;
            }
            listener.onCommandReceived(this, command);
        } catch (ProtocolException pe) {
            MetricsRegistry.getInstance().recordError("PROTOCOL_ERROR");
            sendEvent(new ServerEvent.ErrorEvent("Protocol error: " + pe.getMessage()));
        }
    }

    private void handleQuit(ClientCommand command) {
        writeLock.lock();
        try {
            if (!isClosed.get()) {
                ServerEvent ack = new ServerEvent.ChatBroadcast("SERVER", "QUIT_ACK");
                if (isWebSocket) {
                    WebSocketFrameCodec.writeTextFrame(rawOutputStream, ProtocolCodec.encodeEvent(ack).trim());
                } else {
                    rawOutputStream.write(ProtocolCodec.encodeEvent(ack).getBytes(StandardCharsets.UTF_8));
                    rawOutputStream.flush();
                }
            }
        } catch (IOException ignored) {
        } finally {
            writeLock.unlock();
        }
        try {
            listener.onCommandReceived(this, command);
        } finally {
            close();
        }
    }

    /**
     * Sends a ServerEvent to the connected client asynchronously.
     * Enqueues to the dedicated outbound queue so that slow clients cannot stall the caller.
     */
    public void sendEvent(ServerEvent event) {
        if (isClosed.get()) {
            return;
        }

        MetricsRegistry.getInstance().recordEventEmitted(event.type().name());
        if (event instanceof ServerEvent.ErrorEvent) {
            MetricsRegistry.getInstance().recordError("ERROR_EVENT");
        }

        Thread writer = this.outboundWriterThread;
        if (writer != null && writer.isAlive()) {
            boolean enqueued = outboundQueue.offer(event);
            if (!enqueued) {
                MetricsRegistry.getInstance().recordError("QUEUE_OVERFLOW");
                System.err.println("Session " + sessionId + " outbound queue full, dropping connection.");
                close();
            }
        } else {
            try {
                writeEventDirectly(event);
            } catch (IOException e) {
                close();
            }
        }
    }

    private void writeEventDirectly(ServerEvent event) throws IOException {
        writeLock.lock();
        try {
            if (isClosed.get()) {
                return;
            }
            if (isWebSocket) {
                String wire = ProtocolCodec.encodeEvent(event).trim();
                WebSocketFrameCodec.writeTextFrame(rawOutputStream, wire);
            } else {
                String wire = ProtocolCodec.encodeEvent(event);
                rawOutputStream.write(wire.getBytes(StandardCharsets.UTF_8));
                rawOutputStream.flush();
            }
        } finally {
            writeLock.unlock();
        }
    }

    private String readBoundedLine() throws IOException {
        int b;
        while ((b = rawInputStream.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = currentLineBuffer.toByteArray();
                currentLineBuffer.reset();
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (b != '\r') {
                currentLineBuffer.write(b);
                if (currentLineBuffer.size() > MAX_TCP_LINE_LENGTH) {
                    currentLineBuffer.reset();
                    throw new FrameTooLongException("Frame length exceeds " + MAX_TCP_LINE_LENGTH + " bytes");
                }
            }
        }
        if (currentLineBuffer.size() == 0) {
            return null;
        }
        byte[] bytes = currentLineBuffer.toByteArray();
        currentLineBuffer.reset();
        return new String(bytes, StandardCharsets.UTF_8);
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

    public long getLastActivityMillis() {
        return lastActivityMillis.get();
    }

    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            MetricsRegistry.getInstance().recordConnectionClosed(isWebSocket);
            if (outboundWriterThread != null) {
                outboundWriterThread.interrupt();
            }
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
