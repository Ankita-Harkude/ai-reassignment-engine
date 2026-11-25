package com.ziprun.reassignment.exception;

/** A state-machine rule was violated (e.g. accepting an already-rejected suggestion). Maps to HTTP 409. */
public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(String message) {
        super(message);
    }
}
