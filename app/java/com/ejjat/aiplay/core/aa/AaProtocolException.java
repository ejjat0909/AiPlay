package com.ejjat.aiplay.core.aa;

/** Raised when a peer sends a frame that cannot be trusted or parsed. */
public class AaProtocolException extends Exception {

    public AaProtocolException(String message) {
        super(message);
    }

    public AaProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
