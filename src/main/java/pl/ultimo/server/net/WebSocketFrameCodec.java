package pl.ultimo.server.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Lightweight, zero-dependency RFC 6455 WebSocket frame encoder, decoder, and handshake utility.
 * Built entirely with standard Java 21 libraries to power web browser connections.
 */
public final class WebSocketFrameCodec {

    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private WebSocketFrameCodec() {
    }

    /**
     * Computes the Sec-WebSocket-Accept response header value from the client's Sec-WebSocket-Key
     * as defined in RFC 6455 Section 4.2.2.
     *
     * @param clientKey the client's nonce key
     * @return base64-encoded SHA-1 digest
     */
    public static String computeAcceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((clientKey.trim() + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Immutable representation of a decoded WebSocket frame.
     */
    public record Frame(int opcode, byte[] payload, String text) {
        public boolean isClose() {
            return opcode == OPCODE_CLOSE;
        }

        public boolean isPing() {
            return opcode == OPCODE_PING;
        }

        public boolean isPong() {
            return opcode == OPCODE_PONG;
        }

        public boolean isText() {
            return opcode == OPCODE_TEXT;
        }
    }

    /**
     * Reads a single RFC 6455 frame from the given input stream.
     *
     * @param in the socket input stream
     * @return decoded Frame, or null if EOF was reached
     * @throws IOException on network or protocol framing error
     */
    public static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null; // Connection closed
        }

        int opcode = b0 & 0x0F;

        int b1 = in.read();
        if (b1 == -1) {
            throw new EOFException("Unexpected EOF while reading frame header length");
        }

        boolean masked = (b1 & 0x80) != 0;
        long payloadLength = b1 & 0x7F;

        if (payloadLength == 126) {
            int high = in.read();
            int low = in.read();
            if (high == -1 || low == -1) {
                throw new EOFException("Unexpected EOF while reading 16-bit payload length");
            }
            payloadLength = ((high & 0xFF) << 8) | (low & 0xFF);
        } else if (payloadLength == 127) {
            byte[] lenBytes = in.readNBytes(8);
            if (lenBytes.length < 8) {
                throw new EOFException("Unexpected EOF while reading 64-bit payload length");
            }
            long len = 0;
            for (byte b : lenBytes) {
                len = (len << 8) | (b & 0xFF);
            }
            payloadLength = len;
        }

        if (payloadLength < 0 || payloadLength > 16 * 1024 * 1024) {
            throw new IOException("WebSocket frame payload exceeds acceptable safety limit: " + payloadLength);
        }

        byte[] mask = null;
        if (masked) {
            mask = in.readNBytes(4);
            if (mask.length < 4) {
                throw new EOFException("Unexpected EOF while reading masking key");
            }
        }

        byte[] payload = in.readNBytes((int) payloadLength);
        if (payload.length < payloadLength) {
            throw new EOFException("Unexpected EOF while reading payload bytes");
        }

        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }

        String text = null;
        if (opcode == OPCODE_TEXT) {
            text = new String(payload, StandardCharsets.UTF_8);
        }

        return new Frame(opcode, payload, text);
    }

    /**
     * Encodes and writes a text message as an unmasked RFC 6455 frame to the output stream.
     */
    public static void writeTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        writeFrame(out, OPCODE_TEXT, payload);
    }

    /**
     * Writes a standard close frame.
     */
    public static void writeCloseFrame(OutputStream out) throws IOException {
        writeFrame(out, OPCODE_CLOSE, new byte[0]);
    }

    /**
     * Writes a pong frame in response to a ping.
     */
    public static void writePongFrame(OutputStream out, byte[] payload) throws IOException {
        writeFrame(out, OPCODE_PONG, payload != null ? payload : new byte[0]);
    }

    /**
     * Writes a ping frame to probe client liveness.
     */
    public static void writePingFrame(OutputStream out, byte[] payload) throws IOException {
        writeFrame(out, OPCODE_PING, payload != null ? payload : new byte[0]);
    }

    /**
     * Encodes and writes a WebSocket frame according to RFC 6455 Section 5.1 (server frames are unmasked).
     */
    public static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int length = payload.length;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(length + 10);

        // Byte 0: FIN (0x80) | opcode
        buffer.write(0x80 | (opcode & 0x0F));

        // Byte 1+: mask bit is 0 for server-sent frames
        if (length < 126) {
            buffer.write(length);
        } else if (length <= 0xFFFF) {
            buffer.write(126);
            buffer.write((length >> 8) & 0xFF);
            buffer.write(length & 0xFF);
        } else {
            buffer.write(127);
            for (int i = 7; i >= 0; i--) {
                buffer.write((int) ((length >>> (i * 8)) & 0xFF));
            }
        }

        buffer.write(payload);
        out.write(buffer.toByteArray());
        out.flush();
    }
}
