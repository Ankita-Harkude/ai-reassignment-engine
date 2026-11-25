package com.ziprun.reassignment.ai;

/** Every distinct way the AI path can fail, so the fallback can record WHY it was bypassed. */
public class LlmFailureException extends RuntimeException {

    public enum Kind {
        NOT_CONFIGURED,     // no API key for a provider that needs one
        TIMEOUT,            // read/connect timeout
        NETWORK,            // DNS / connection refused
        QUOTA,              // HTTP 429
        HTTP_ERROR,         // any other non-2xx
        UNPARSEABLE,        // response was not the JSON we asked for
        INVALID_CONFIDENCE, // confidence missing or outside 0..1
        UNKNOWN_AGENT       // model hallucinated an agent id that is not a candidate
    }

    private final Kind kind;

    public LlmFailureException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmFailureException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
