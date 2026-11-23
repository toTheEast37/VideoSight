package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpAsrClientTest {
    @TempDir Path temp;
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>("{\"text\":\"识别结果\"}");
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private volatile boolean disconnect;
    private volatile CountDownLatch responseGate;
    private String url;
    private Path audio;

    @BeforeEach void start() throws IOException {
        audio = Files.writeString(temp.resolve("input.wav"), "local-audio-payload");
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            receivedBody.set(new String(exchange.getRequestBody().readNBytes(1024 * 1024), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (disconnect) { exchange.close(); return; }
            if (responseGate != null) {
                try { responseGate.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/audio/transcriptions";
    }

    @AfterEach void stop() {
        if (responseGate != null) responseGate.countDown();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void submitsFileAndModelAsMultipartAndAllowsEmptySpeech() {
        response.set("{\"text\":\"\"}");
        assertThat(client(Duration.ofSeconds(3)).transcribe(audio)).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(contentType.get()).startsWith("multipart/form-data;");
        assertThat(receivedBody.get()).contains("name=\"file\"", "local-audio-payload", "name=\"model\"", "test-model");
    }

    @ParameterizedTest
    @CsvSource({"400,false,false", "401,false,false", "403,false,false", "415,false,false", "429,true,false", "503,false,true"})
    void distinguishesRejectedRequestsRateLimitsAndUncertainBilling(int code, boolean retryable, boolean uncertain) {
        status.set(code);
        response.set("{\"message\":\"private-error-detail\"}");
        RecognitionException error = assertThrows(RecognitionException.class, () -> client(Duration.ofSeconds(3)).transcribe(audio));
        assertThat(error.retryable()).isEqualTo(retryable);
        assertThat(error.uncertain()).isEqualTo(uncertain);
        assertThat(error.getMessage()).doesNotContain("private-error-detail", url, "local-test-key");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void neverReplaysRequestAfterConnectionDrops() {
        disconnect = true;
        RecognitionException error = assertThrows(RecognitionException.class, () -> client(Duration.ofSeconds(3)).transcribe(audio));
        assertThat(error.uncertain()).isTrue();
        assertThat(error.retryable()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void timeoutAfterSubmissionRemainsUncertainAndIsNotReplayed() {
        responseGate = new CountDownLatch(1);
        RecognitionException error = assertThrows(RecognitionException.class, () -> client(Duration.ofMillis(300)).transcribe(audio));
        assertThat(error.uncertain()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void oversizedOrMalformedSuccessCannotBeMarkedAsTranscribed() {
        response.set("x".repeat(1024 * 1024 + 1));
        assertThat(assertThrows(RecognitionException.class, () -> client(Duration.ofSeconds(3)).transcribe(audio)).uncertain()).isTrue();
        response.set("{\"output\":{\"task_id\":\"not-a-transcript\"}}");
        assertThat(assertThrows(RecognitionException.class, () -> client(Duration.ofSeconds(3)).transcribe(audio)).uncertain()).isTrue();
    }

    @Test void refusesKnownAsynchronousProtocolBeforeSendingPaidRequest() {
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/services/audio/asr/transcription";
        RecognitionException error = assertThrows(RecognitionException.class, () -> client(Duration.ofSeconds(3)).transcribe(audio));
        assertThat(error.code()).isEqualTo("ASR_CONFIG_UNSUPPORTED");
        assertThat(calls.get()).isZero();
    }

    private HttpAsrClient client(Duration timeout) {
        return new HttpAsrClient(MediaTestProperties.properties(temp, url, "test-model", timeout), new ObjectMapper());
    }
}
