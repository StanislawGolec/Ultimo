package pl.ultimo.server.net;

import pl.ultimo.server.config.ServerConfig;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Listens on the configured TCP port, configures incoming client sockets,
 * and delegates each accepted connection to the configured connection handler.
 */
public class SocketAcceptor implements Runnable, Closeable {

    private final ServerConfig config;
    private final Consumer<Socket> connectionHandler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public SocketAcceptor(ServerConfig config, Consumer<Socket> connectionHandler) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.connectionHandler = Objects.requireNonNull(connectionHandler, "connectionHandler must not be null");
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(config.port());
            isRunning.set(true);

            while (isRunning.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    configureSocket(clientSocket);
                    connectionHandler.accept(clientSocket);
                } catch (SocketException e) {
                    if (!isRunning.get()) {
                        // Clean shutdown
                        break;
                    }
                    System.err.println("Acceptor socket error: " + e.getMessage());
                } catch (IOException e) {
                    if (isRunning.get()) {
                        System.err.println("I/O error accepting connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not bind ServerSocket on port " + config.port() + ": " + e.getMessage());
        } finally {
            close();
        }
    }

    private void configureSocket(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true); // Disable Nagle's algorithm for low-latency game packets
        if (config.socketReadTimeoutMillis() > 0) {
            socket.setSoTimeout(config.socketReadTimeoutMillis());
        }
    }

    /**
     * Returns the port the ServerSocket is bound to, or -1 if not yet bound.
     */
    public int getBoundPort() {
        return (serverSocket != null && serverSocket.isBound()) ? serverSocket.getLocalPort() : -1;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    @Override
    public void close() {
        if (isRunning.compareAndSet(true, false)) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
