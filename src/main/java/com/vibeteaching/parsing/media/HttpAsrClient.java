package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.config.ParseProperties;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** 仅支持同步 multipart(file+model) → {text:...} 协议，不隐式兼容厂商异步 API。 */
public class HttpAsrClient implements AsrClient {
    private final ParseProperties properties;
    private final AsrHttpTransport transport;

    public HttpAsrClient(ParseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = new AsrHttpTransport(properties, objectMapper);
    }

    @Override
    public String transcribe(Path audio) {
        validateConfiguration();
        if (audio == null || !Files.isRegularFile(audio)) {
            throw new RecognitionException("ASR_INPUT_MISSING", "语音识别输入不存在", true, false);
        }
        try {
            if (Files.size(audio) == 0) {
                throw new RecognitionException("ASR_INPUT_EMPTY", "语音识别音频文件为空", false, false);
            }
        } catch (IOException e) {
            throw new RecognitionException("ASR_INPUT_UNREADABLE", "语音识别输入无法读取", true, false);
        }
        RequestBody body;
        try {
            String contentType = audio.getFileName().toString().endsWith(".wav") ? "audio/wav" : "audio/mpeg";
            body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", audio.getFileName().toString(),
                            RequestBody.create(audio.toFile(), MediaType.get(contentType)))
                    .addFormDataPart("model", properties.asrModel())
                    .build();
        } catch (IllegalArgumentException e) {
            throw new RecognitionException("ASR_CONFIG_INVALID", "语音识别接口配置无效", false, false);
        }
        JsonNode result = transport.post(properties.asrUrl(), properties.asrApiKey(), body);
        if (!result.path("text").isTextual()) {
            throw AsrHttpTransport.uncertain("语音识别响应不符合已配置的同步协议，结果待确认");
        }
        return result.path("text").textValue().trim();
    }

    private void validateConfiguration() {
        if (blank(properties.asrUrl()) || blank(properties.asrModel()) || blank(properties.asrApiKey())) {
            throw new RecognitionException("ASR_CONFIG_MISSING", "尚未配置可用的同步语音识别接口、模型及凭据", false, false);
        }
        try {
            URI uri = URI.create(properties.asrUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            if (uri.getPath().contains("/services/audio/asr/transcription")) {
                throw new RecognitionException("ASR_CONFIG_UNSUPPORTED", "当前适配器尚未接入该厂商异步文件识别协议", false, false);
            }
        } catch (RecognitionException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new RecognitionException("ASR_CONFIG_INVALID", "语音识别接口配置无效", false, false);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

}
