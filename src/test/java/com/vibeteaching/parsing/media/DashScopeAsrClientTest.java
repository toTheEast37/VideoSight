package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DashScopeAsrClientTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> streaming = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>("{\"output\":{\"text\":\"中文课程内容\"}}");
    private HttpServer server;
    private String url;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            streaming.set(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/services/aigc/multimodal-generation/generation";
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void sendsLocalAudioAsDocumentedDataUriAndReadsOutputText() throws IOException {
        byte[] audio = wave(32000);
        Path file = Files.write(temp.resolve("audio.wav"), audio);
        assertThat(client("qwen-audio-3.1-asr-flash").transcribe(file)).isEqualTo("中文课程内容");
        assertThat(authorization.get()).isEqualTo("Bearer local-test-key");
        assertThat(streaming.get()).isEqualTo("disable");
        JsonNode sent = request.get();
        assertThat(sent.path("model").asText()).isEqualTo("qwen-audio-3.1-asr-flash");
        assertThat(sent.path("parameters").path("format").asText()).isEqualTo("wav");
        assertThat(sent.path("parameters").path("sample_rate").asText()).isEqualTo("16000");
        JsonNode content = sent.path("input").path("messages").get(0).path("content").get(0);
        assertThat(content.path("type").asText()).isEqualTo("input_audio");
        String encoded = content.path("input_audio").path("data").asText();
        assertThat(encoded).startsWith("data:audio/wav;base64,");
        assertThat(Base64.getDecoder().decode(encoded.substring(encoded.indexOf(',') + 1))).isEqualTo(audio);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void preservesLegitimateEmptyTextWithoutInventingTranscript() throws IOException {
        response.set("{\"output\":{\"text\":\"\"}}");
        assertThat(client("qwen-audio-3.1-asr-flash").transcribe(Files.write(temp.resolve("silence.wav"), wave(32000)))).isEmpty();
    }

    @Test void rejectsWrongModelAndLongOrInvalidAudioBeforeNetworkCall() throws IOException {
        Path file = Files.write(temp.resolve("audio.wav"), wave(32000));
        assertThat(assertThrows(RecognitionException.class,
                () -> client("qwen-audio-3.1-asr-flash-filetrans").transcribe(file)).uncertain()).isFalse();
        Files.write(file, wave(300 * 32000 + 2));
        assertThat(assertThrows(RecognitionException.class,
                () -> client("qwen-audio-3.1-asr-flash").transcribe(file)).code()).isEqualTo("ASR_INPUT_FORMAT");
        Files.writeString(file, "not an audio file");
        assertThat(assertThrows(RecognitionException.class,
                () -> client("qwen-audio-3.1-asr-flash").transcribe(file)).code()).isEqualTo("ASR_INPUT_FORMAT");
        assertThat(calls.get()).isZero();
    }

    @Test void chatCompletionShapeDoesNotMasqueradeAsConfiguredAsrSuccess() throws IOException {
        response.set("{\"choices\":[{\"message\":{\"content\":\"wrong protocol\"}}]}");
        RecognitionException error = assertThrows(RecognitionException.class, () -> client("qwen-audio-3.1-asr-flash")
                .transcribe(Files.write(temp.resolve("audio.wav"), wave(32000))));
        assertThat(error.uncertain()).isTrue();
    }

    private DashScopeAsrClient client(String model) {
        return new DashScopeAsrClient(MediaTestProperties.properties(temp, url, model, Duration.ofSeconds(3)), mapper);
    }

    static byte[] wave(int dataBytes) {
        ByteBuffer bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        return bytes.array();
    }
}
