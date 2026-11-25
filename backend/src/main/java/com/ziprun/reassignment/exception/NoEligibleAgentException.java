package com.ziprun.reassignment.exception;

/** Routing found nobody who can take the order. Maps to HTTP 422. */
public class NoEligibleAgentException extends RuntimeException {
    public NoEligibleAgentException(String orderId) {
        super("No eligible agent is available for order " + orderId);
    }
}
