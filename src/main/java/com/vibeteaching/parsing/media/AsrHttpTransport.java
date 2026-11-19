package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.config.ParseProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;

/** 付费请求只发一次；无法确认的提交不由 HTTP 客户端偷偷重放。 */
final class AsrHttpTransport {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client;

    AsrHttpTransport(ParseProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .callTimeout(properties.asrTimeout()).connectTimeout(properties.asrTimeout())
                .readTimeout(properties.asrTimeout()).writeTimeout(properties.asrTimeout()).build();
    }

    JsonNode post(String url, String apiKey, RequestBody body) {
        Request request;
        try {
            request = new Request.Builder().url(url).header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-SSE", "disable").post(body).build();
        } catch (IllegalArgumentException e) {
            throw new RecognitionException("ASR_CONFIG_INVALID", "语音识别接口配置无效", false, false);
        }
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 429) {
                throw new RecognitionException("ASR_RATE_LIMITED", "语音识别服务限流，稍后可重试", true, false);
            }
            if (response.code() == 401 || response.code() == 403) {
                throw new RecognitionException("ASR_AUTH_FAILED", "语音识别服务鉴权失败，请检查配置", false, false);
            }
            if (response.code() >= 500) throw uncertain("语音识别服务异常，结果与计费状态待确认");
            if (!response.isSuccessful()) {
                throw new RecognitionException("ASR_REQUEST_REJECTED", "语音识别请求被拒绝，请检查格式与接口协议", false, false);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) throw uncertain("语音识别未返回可确认的结果");
            byte[] bytes;
            try (InputStream input = responseBody.byteStream()) {
                bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) throw uncertain("语音识别返回结果超过允许大小");
            JsonNode result = objectMapper.readTree(bytes);
            if (result == null || !result.isObject()) throw uncertain("语音识别响应无法确认");
            return result;
        } catch (RecognitionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw uncertain("语音识别连接或响应中断，结果与计费状态待确认");
        }
    }

    static RecognitionException uncertain(String message) {
        return new RecognitionException("ASR_UNCERTAIN", message, false, true);
    }
}
