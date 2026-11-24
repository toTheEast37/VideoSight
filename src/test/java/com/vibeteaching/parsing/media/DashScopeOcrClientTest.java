package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibeteaching.parsing.config.ParseProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeOcrClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<Incoming> incoming = new AtomicReference<>();
    @TempDir Path temp;
    private HttpServer server;
    private DashScopeOcrClient client;
    private Path image;

    @BeforeEach void prepare() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            try (exchange) {
                incoming.set(new Incoming(exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Reply response = reply.get();
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        var bindings = new MapConfigurationPropertySource(Map.of(
                "app.parse.ocr-provider", "qwen",
                "app.parse.ocr-base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1/",
                "app.parse.ocr-api-key", "test-key"));
        ParseProperties properties = new Binder(bindings).bind("app.parse", Bindable.of(ParseProperties.class)).get();
        client = new DashScopeOcrClient(properties, json);
        image = Files.write(temp.resolve("frame.jpg"), new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void sendsInlineImageAndKeepsRawTextIncludingWhitespace() throws Exception {
        String raw = "  检 查 路 径\n\nassetId\n";
        reply.set(new Reply(200, json.writeValueAsString(Map.of("choices", new Object[] {
                Map.of("finish_reason", "stop", "message", Map.of("content", raw))
        }))));
        assertThat(client.recognize(image)).isEqualTo(raw);
        assertThat(incoming.get().authorization()).isEqualTo("Bearer test-key");
        var request = json.readTree(incoming.get().body());
        assertThat(request.path("model").asText()).isEqualTo("qwen3.5-ocr");
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.at("/messages/0/content/0/image_url/url").asText())
                .isEqualTo("data:image/jpeg;base64,/9j/AQ==");
    }

    @Test void emptyTextSucceedsButTruncationAndMalformedResponsesAreUncertain() {
        reply.set(new Reply(200, """
                {"choices":[{"finish_reason":"stop","message":{"content":""}}]}
                """));
        assertThat(client.recognize(image)).isEmpty();
        reply.set(new Reply(200, """
                {"choices":[{"finish_reason":"length","message":{"content":"只识别了一部分"}}]}
                """));
        assertFailure("OCR_RESULT_UNCERTAIN", false, true);
        reply.set(new Reply(502, "{}"));
        assertFailure("OCR_RESULT_UNCERTAIN", false, true);
    }

    @Test void distinguishesRejectedAccessFromRetryableRateLimits() {
        reply.set(new Reply(403, "{}"));
        assertFailure("OCR_ACCESS_DENIED", false, false);
        reply.set(new Reply(429, "{}"));
        assertFailure("OCR_RATE_LIMITED", true, false);
    }

    private void assertFailure(String code, boolean retryable, boolean uncertain) {
        assertThatThrownBy(() -> client.recognize(image)).isInstanceOfSatisfying(RecognitionException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.retryable()).isEqualTo(retryable);
            assertThat(failure.uncertain()).isEqualTo(uncertain);
        });
    }

    private record Incoming(String authorization, String body) { }
    private record Reply(int status, String body) { }
}
