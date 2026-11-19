package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.config.ParseProperties;
import okhttp3.MediaType;
import okhttp3.RequestBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Qwen-Audio-3.1-ASR-Flash：短音频 Data URI 同步调用，不需云端读取本机 MinIO。 */
public class DashScopeAsrClient implements AsrClient {
    private static final long MAX_AUDIO_BYTES = 10L * 1024 * 1024;
    private final ParseProperties properties;
    private final ObjectMapper objectMapper;
    private final AsrHttpTransport transport;

    public DashScopeAsrClient(ParseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = new AsrHttpTransport(properties, objectMapper);
    }

    @Override
    public String transcribe(Path audio) {
        validateConfiguration();
        byte[] bytes;
        try {
            if (audio == null || !Files.isRegularFile(audio)) {
                throw new RecognitionException("ASR_INPUT_MISSING", "语音识别输入不存在", true, false);
            }
            long size = Files.size(audio);
            if (size <= 0 || size > MAX_AUDIO_BYTES) {
                throw new RecognitionException("ASR_INPUT_SIZE_LIMIT", "语音识别音频超过单次输入范围", false, false);
            }
            try (InputStream input = Files.newInputStream(audio)) {
                bytes = input.readNBytes((int) MAX_AUDIO_BYTES + 1);
            }
            if (bytes.length != size) {
                throw new RecognitionException("ASR_INPUT_CHANGED", "语音识别输入在读取期间变化", true, false);
            }
        } catch (IOException e) {
            throw new RecognitionException("ASR_INPUT_UNREADABLE", "语音识别输入无法读取", true, false);
        }
        validateWave(bytes);
        // MediaPreparation 始终产生 16 kHz、单声道 PCM WAV，最长 300 秒。
        String data = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes);
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(Map.of(
                    "model", properties.asrModel(),
                    "input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
                            Map.of("type", "input_audio", "input_audio", Map.of("data", data)))))),
                    "parameters", Map.of("format", "wav", "sample_rate", "16000")));
        } catch (IOException e) {
            throw new RecognitionException("ASR_REQUEST_ENCODING_FAILED", "语音识别请求无法编码，尚未提交", false, false);
        }
        JsonNode response = transport.post(properties.asrUrl(), properties.asrApiKey(),
                RequestBody.create(json, MediaType.get("application/json")));
        if (!response.path("output").path("text").isTextual()) {
            throw AsrHttpTransport.uncertain("语音识别响应缺少完整文字，结果待确认");
        }
        // 当前持久化模型保存分片范围；不能用最后一句的时间冒充整片的句级对齐。
        return response.path("output").path("text").textValue().trim();
    }

    private void validateConfiguration() {
        if (properties.asrUrl() == null || properties.asrUrl().isBlank()
                || properties.asrApiKey() == null || properties.asrApiKey().isBlank()
                || !"qwen-audio-3.1-asr-flash".equals(properties.asrModel())) {
            throw new RecognitionException("ASR_CONFIG_MISSING", "请配置已支持的千问短音频识别模型、接口与凭据", false, false);
        }
        try {
            URI uri = URI.create(properties.asrUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || !uri.getPath().endsWith("/services/aigc/multimodal-generation/generation")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new RecognitionException("ASR_CONFIG_INVALID", "千问语音识别接口配置与同步协议不匹配", false, false);
        }
    }

    private static void validateWave(byte[] audio) {
        boolean formatValid = false;
        long audioDataBytes = -1;
        if (audio.length < 44 || !ascii(audio, 0, 4).equals("RIFF") || !ascii(audio, 8, 4).equals("WAVE")) {
            throw invalidAudio();
        }
        ByteBuffer data = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
        long offset = 12;
        while (offset + 8 <= audio.length) {
            int header = (int) offset;
            String name = ascii(audio, header, 4);
            long size = Integer.toUnsignedLong(data.getInt(header + 4));
            long end = offset + 8 + size;
            if (end > audio.length) throw invalidAudio();
            if (name.equals("fmt ")) {
                if (size < 16) throw invalidAudio();
                int start = header + 8;
                formatValid = data.getShort(start) == 1 && data.getShort(start + 2) == 1
                        && data.getInt(start + 4) == 16000 && data.getInt(start + 8) == 32000
                        && data.getShort(start + 12) == 2 && data.getShort(start + 14) == 16;
            } else if (name.equals("data")) {
                if (audioDataBytes != -1) throw invalidAudio();
                audioDataBytes = size;
            }
            offset = end + (size & 1);
        }
        if (!formatValid || audioDataBytes <= 0 || audioDataBytes > 300L * 32000 || audioDataBytes % 2 != 0) {
            throw invalidAudio();
        }
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static RecognitionException invalidAudio() {
        return new RecognitionException("ASR_INPUT_FORMAT", "语音识别要求不超过五分钟的 16 kHz 单声道 PCM WAV", false, false);
    }
}
