package pl.ultimo.protocol;

/**
 * Thrown when an incoming network payload cannot be parsed or violates protocol framing rules.
 */
public class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
