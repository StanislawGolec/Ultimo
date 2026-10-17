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
 * Enforces strict RFC 6455 protocol security: client frame masking (§5.1), control frame constraints (§5.5),
 * and 64KB maximum payload DOS / OOM protection.
 */
public final class WebSocketFrameCodec {

    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    public static final int MAX_PAYLOAD_SIZE = 65536; // 64KB max payload DOS protection
    public static final int MAX_CONTROL_PAYLOAD_SIZE = 125; // RFC 6455 §5.5

    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    public static final int CLOSE_UNSUPPORTED = 1003;
    public static final int CLOSE_POLICY_VIOLATION = 1008;
    public static final int CLOSE_MESSAGE_TOO_BIG = 1009;

    private WebSocketFrameCodec() {
    }

    /**
     * Dedicated RFC 6455 protocol exception carrying standard status codes (1002, 1009, etc.).
     */
    public static class WebSocketProtocolException extends IOException {
        private final int statusCode;

        public WebSocketProtocolException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
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
    public record Frame(boolean fin, int opcode, byte[] payload, String text) {
        public Frame(int opcode, byte[] payload, String text) {
            this(true, opcode, payload, text);
        }

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

        public boolean isBinary() {
            return opcode == OPCODE_BINARY;
        }

        public boolean isControl() {
            return (opcode & 0x08) != 0;
        }

        public int closeCode() {
            if (isClose() && payload != null && payload.length >= 2) {
                return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            }
            return CLOSE_NORMAL;
        }

        public String closeReason() {
            if (isClose() && payload != null && payload.length > 2) {
                return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
            }
            return "";
        }
    }

    /**
     * Reads a single RFC 6455 frame with default masking rules (unmasked allowed, e.g. for testing server-sent frames).
     */
    public static Frame readFrame(InputStream in) throws IOException {
        return readFrame(in, false);
    }

    /**
     * Reads a single RFC 6455 frame from the given input stream with strict RFC 6455 compliance enforcement.
     *
     * @param in the socket input stream
     * @param requireMask if true, throws WebSocketProtocolException(1002) if incoming frame is not masked (RFC 6455 §5.1)
     * @return decoded Frame, or null if EOF was reached
     * @throws IOException on network or protocol framing error
     */
    public static Frame readFrame(InputStream in, boolean requireMask) throws IOException {
        int b0 = in.read();
        if (b0 == -1) {
            return null; // Connection closed
        }

        boolean fin = (b0 & 0x80) != 0;
        int rsv = (b0 & 0x70) >> 4;
        int opcode = b0 & 0x0F;

        // RFC 6455 §5.2: RSV bits MUST be 0 unless an extension is negotiated
        if (rsv != 0) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR,
                    "RSV bits must be 0 (received RSV=" + rsv + ")");
        }

        // RFC 6455 §5.2: Validate opcode
        boolean isControl = (opcode & 0x08) != 0;
        if (opcode != OPCODE_CONTINUATION && opcode != OPCODE_TEXT && opcode != OPCODE_BINARY
                && opcode != OPCODE_CLOSE && opcode != OPCODE_PING && opcode != OPCODE_PONG) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR,
                    "Invalid or reserved opcode: 0x" + Integer.toHexString(opcode));
        }

        // RFC 6455 §5.5: Control frames MUST NOT be fragmented (FIN=1)
        if (isControl && !fin) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR,
                    "Control frames must not be fragmented (FIN=0)");
        }

        int b1 = in.read();
        if (b1 == -1) {
            throw new EOFException("Unexpected EOF while reading frame header length");
        }

        boolean masked = (b1 & 0x80) != 0;
        // RFC 6455 §5.1: Client MUST mask all frames sent to the server
        if (requireMask && !masked) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR,
                    "Client sent unmasked frame (RFC 6455 §5.1 violation)");
        }

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

        if (payloadLength < 0) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR, "Negative payload length");
        }

        // RFC 6455 §5.5: Control frames MUST have payload length <= 125
        if (isControl && payloadLength > MAX_CONTROL_PAYLOAD_SIZE) {
            throw new WebSocketProtocolException(CLOSE_PROTOCOL_ERROR,
                    "Control frame payload length exceeds 125 bytes: " + payloadLength);
        }

        // RFC 6455 §7.4.1 / DoS protection: Enforce 64KB maximum payload size
        if (payloadLength > MAX_PAYLOAD_SIZE) {
            throw new WebSocketProtocolException(CLOSE_MESSAGE_TOO_BIG,
                    "WebSocket frame payload exceeds 64KB limit: " + payloadLength);
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

        return new Frame(fin, opcode, payload, text);
    }

    /**
     * Encodes and writes a text message as an unmasked RFC 6455 frame to the output stream.
     */
    public static void writeTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        writeFrame(out, OPCODE_TEXT, payload);
    }

    /**
     * Writes a standard close frame with default status 1000 (Normal Closure).
     */
    public static void writeCloseFrame(OutputStream out) throws IOException {
        writeCloseFrame(out, CLOSE_NORMAL, "");
    }

    /**
     * Writes an RFC 6455 close frame with the given status code and reason.
     */
    public static void writeCloseFrame(OutputStream out, int statusCode, String reason) throws IOException {
        byte[] reasonBytes = (reason != null && !reason.isBlank()) ? reason.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        if (reasonBytes.length > 0) {
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        }
        writeFrame(out, OPCODE_CLOSE, payload);
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
        int length = payload != null ? payload.length : 0;
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

        if (payload != null && length > 0) {
            buffer.write(payload);
        }
        out.write(buffer.toByteArray());
        out.flush();
    }
}
