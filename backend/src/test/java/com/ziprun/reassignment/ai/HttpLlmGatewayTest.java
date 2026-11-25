package com.ziprun.reassignment.ai;

import com.ziprun.reassignment.ai.LlmFailureException.Kind;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real HTTP gateway against a throwaway local server - no network, no real key. */
class HttpLlmGatewayTest {

    private static final String SECRET = "test-secret-key-123";

    private HttpServer server;
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastGoogleKeyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastUri.set(ex.getRequestURI().toString());
            lastAuthHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastGoogleKeyHeader.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            try {
                ex.getRequestBody().readAllBytes();
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (IOException ignored) {   // client may have timed out and gone away
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpLlmGateway gateway(String provider, String key, int readTimeoutMs) {
        return new HttpLlmGateway(provider, key, "test-model", baseUrl(), 500, readTimeoutMs);
    }

    @Test
    void geminiSuccessSendsKeyInHeaderNeverInTheUrl() {
        body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello gemini\"}]}}]}";

        String text = gateway("gemini", SECRET, 2000).complete("prompt");

        assertThat(text).isEqualTo("hello gemini");
        assertThat(lastGoogleKeyHeader.get()).isEqualTo(SECRET);
        assertThat(lastUri.get()).doesNotContain(SECRET).doesNotContain("key=");
    }

    @Test
    void groqSuccessUsesBearerAuth() {
        body = "{\"choices\":[{\"message\":{\"content\":\"hello groq\"}}]}";

        assertThat(gateway("groq", SECRET, 2000).complete("prompt")).isEqualTo("hello groq");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer " + SECRET);
        assertThat(lastUri.get()).isEqualTo("/openai/v1/chat/completions").doesNotContain(SECRET);
    }

    @Test
    void ollamaNeedsNoKey() {
        body = "{\"choices\":[{\"message\":{\"content\":\"hello local\"}}]}";

        assertThat(gateway("ollama", "", 2000).complete("prompt")).isEqualTo("hello local");
        assertThat(lastAuthHeader.get()).isNull();
    }

    @Test
    void missingKeyFailsFastWithoutAnyNetworkCall() {
        assertThatThrownBy(() -> gateway("gemini", "", 2000).complete("p"))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_CONFIGURED));
        assertThat(lastUri.get()).isNull();
    }

    @Test
    void http429IsClassifiedAsQuota() {
        status = 429;
        assertThatThrownBy(() -> gateway("gemini", SECRET, 2000).complete("p"))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> assertThat(e.kind()).isEqualTo(Kind.QUOTA));
    }

    @Test
    void http500IsClassifiedAsHttpErrorAndNeverLeaksTheKey() {
        status = 500;
        assertThatThrownBy(() -> gateway("gemini", SECRET, 2000).complete("p"))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.HTTP_ERROR);
                    assertThat(e.getMessage()).doesNotContain(SECRET);
                });
    }

    @Test
    void slowServerIsClassifiedAsTimeout() {
        delayMs = 1500;
        body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"late\"}]}}]}";
        assertThatThrownBy(() -> gateway("gemini", SECRET, 300).complete("p"))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
    }

    @Test
    void unexpectedResponseShapeIsUnparseable() {
        body = "{\"unexpected\":true}";
        assertThatThrownBy(() -> gateway("gemini", SECRET, 2000).complete("p"))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNPARSEABLE));
    }

    @Test
    void unreachableHostIsClassifiedAsNetwork() {
        int port = server.getAddress().getPort();
        server.stop(0);
        var gw = new HttpLlmGateway("groq", SECRET, "m", "http://127.0.0.1:" + port, 500, 500);

        assertThatThrownBy(() -> gw.complete("p"))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NETWORK));
    }
}
