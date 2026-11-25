package com.ziprun.reassignment.ai;

/**
 * Provider-agnostic seam: prompt in, raw model text out. Throws LlmFailureException on any failure.
 * A functional interface so tests can stub the model with a lambda.
 */
@FunctionalInterface
public interface LlmGateway {
    String complete(String prompt);
}
