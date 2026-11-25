package com.ziprun.reassignment.ai;

import com.ziprun.reassignment.ai.LlmFailureException.Kind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP wire format for Gemini, Groq and Ollama (adapted from the supplied Addendum B).
 * Differences from the addendum: bounded timeouts, the API key travels in a header (never in a URL that
 * could be logged), JSON-mode hints, and every failure is mapped to a typed LlmFailureException.
 * Prompt building, parsing, validation and fallback live elsewhere.
 */
@Component
public class HttpLlmGateway implements LlmGateway {

    private final String provider;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final RestClient http;

    public HttpLlmGateway(@Value("${llm.provider}") String provider,
                          @Value("${llm.api-key:}") String apiKey,
                          @Value("${llm.model}") String model,
                          @Value("${llm.base-url}") String baseUrl,
                          @Value("${llm.connect-timeout-ms:3000}") int connectTimeoutMs,
                          @Value("${llm.read-timeout-ms:15000}") int readTimeoutMs) {
        this.provider = provider.toLowerCase();
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String complete(String prompt) {
        if (!"ollama".equals(provider) && (apiKey == null || apiKey.isBlank())) {
            throw new LlmFailureException(Kind.NOT_CONFIGURED, "LLM_API_KEY is not set");
        }
        try {
            return switch (provider) {
                case "gemini" -> callGemini(prompt);
                case "groq" -> callOpenAiCompatible(prompt, baseUrl + "/openai/v1/chat/completions", true);
                case "ollama" -> callOpenAiCompatible(prompt, baseUrl + "/v1/chat/completions", false);
                default -> throw new LlmFailureException(Kind.NOT_CONFIGURED, "Unknown llm.provider: " + provider);
            };
        } catch (LlmFailureException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            Kind kind = e.getStatusCode().value() == 429 ? Kind.QUOTA : Kind.HTTP_ERROR;
            throw new LlmFailureException(kind, "LLM HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // Timeouts can surface wrapped at different depths depending on where the read stalls.
            Kind kind = hasCause(e, SocketTimeoutException.class) ? Kind.TIMEOUT
                    : hasCause(e, IOException.class) ? Kind.NETWORK : Kind.UNPARSEABLE;
            throw new LlmFailureException(kind, "LLM " + kind.name().toLowerCase() + " failure", e);
        } catch (RuntimeException e) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "Unexpected LLM response shape", e);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) return true;
        }
        return false;
    }

    private String callGemini(String prompt) {
        String url = baseUrl + "/v1beta/models/" + model + ":generateContent";
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json"));
        Map<?, ?> resp = http.post().uri(url)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map.class);
        try {
            List<?> candidates = (List<?>) resp.get("candidates");
            Map<?, ?> content = (Map<?, ?>) ((Map<?, ?>) candidates.get(0)).get("content");
            List<?> parts = (List<?>) content.get("parts");
            return (String) ((Map<?, ?>) parts.get(0)).get("text");
        } catch (RuntimeException e) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "Gemini response had no text candidate", e);
        }
    }

    private String callOpenAiCompatible(String prompt, String url, boolean sendAuth) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", Map.of("type", "json_object"));
        RestClient.RequestBodySpec request = http.post().uri(url).contentType(MediaType.APPLICATION_JSON);
        if (sendAuth) {
            request = request.header("Authorization", "Bearer " + apiKey);
        }
        Map<?, ?> resp = request.body(body).retrieve().body(Map.class);
        try {
            List<?> choices = (List<?>) resp.get("choices");
            Map<?, ?> message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
            return (String) message.get("content");
        } catch (RuntimeException e) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "LLM response had no message content", e);
        }
    }
}
