package com.vibeteaching.parsing.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibeteaching.parsing.config.ParseProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DashScopeOcrClient implements OcrClient {
    // 百炼限制编码后的图像字符串为 10 MB，预留 Base64 膨胀和 Data URL 前缀。
    private static final int MAX_IMAGE_BYTES = 7_400_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String PROMPT = "Read all the text in the image.";
    private final ParseProperties properties;
    private final ObjectMapper mapper;
    private final OkHttpClient client;

    public DashScopeOcrClient(ParseProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = new OkHttpClient.Builder().callTimeout(properties.ocrTimeout())
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    @Override
    public String recognize(Path image) {
        if (!properties.qwenOcrReady()) {
            throw new RecognitionException("OCR_CONFIG_INVALID", "百炼画面文字识别配置无效", false, false);
        }
        Request request = request(readImage(image));
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if (code == 401 || code == 403) {
                throw new RecognitionException("OCR_ACCESS_DENIED", "画面识别服务拒绝访问，请检查模型权限和额度", false, false);
            }
            if (code == 429) {
                throw new RecognitionException("OCR_RATE_LIMITED", "画面识别服务限流，稍后重试", true, false);
            }
            if (code == 408 || code >= 500) throw uncertain("画面识别服务异常，结果与计费状态待确认");
            if (!response.isSuccessful()) {
                throw new RecognitionException("OCR_REQUEST_REJECTED", "画面识别请求被拒绝，请检查图片与接口配置", false, false);
            }
            ResponseBody body = response.body();
            if (body == null) throw uncertain("画面识别未返回可确认的结果");
            byte[] bytes = body.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw uncertain("画面识别返回结果超过允许大小");
            JsonNode document = mapper.readTree(bytes);
            if (document == null) throw uncertain("画面识别未返回可确认的结果");
            JsonNode choice = document.path("choices").path(0);
            JsonNode text = choice.path("message").path("content");
            if (!"stop".equals(choice.path("finish_reason").asText()) || !text.isTextual()
                    || text.textValue().length() > 200_000) {
                throw uncertain("画面识别结果缺失、异常或未完整生成");
            }
            return text.textValue();
        } catch (IOException e) {
            throw uncertain("画面识别连接或响应中断，结果与计费状态待确认");
        }
    }

    private Request request(String imageData) {
        List<?> content = List.of(Map.of("type", "image_url", "image_url", Map.of("url", imageData)),
                Map.of("type", "text", "text", PROMPT));
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("model", properties.ocrModel(), "stream", false,
                    "messages", List.of(Map.of("role", "user", "content", content))));
            return new Request.Builder().url(properties.ocrEndpoint())
                    .header("Authorization", "Bearer " + properties.ocrApiKey())
                    .post(RequestBody.create(body, JSON)).build();
        } catch (IOException | IllegalArgumentException e) {
            throw new RecognitionException("OCR_REQUEST_INVALID", "无法构造画面识别请求，请检查本机配置", false, false);
        }
    }

    private static String readImage(Path image) {
        if (image == null || !Files.isRegularFile(image)) {
            throw new RecognitionException("OCR_INPUT_MISSING", "画面识别输入不存在", true, false);
        }
        try (var input = Files.newInputStream(image)) {
            byte[] bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new RecognitionException("OCR_INPUT_SIZE_LIMIT", "画面识别图片超过单次输入范围", false, false);
            }
            // 预处理生产 JPEG。按文件签名识别媒体类型，也允许 PNG 原始截图。
            String type;
            if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) {
                type = "image/jpeg";
            } else if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == '\r' && bytes[5] == '\n'
                    && bytes[6] == 0x1a && bytes[7] == '\n') {
                type = "image/png";
            } else {
                throw new RecognitionException("OCR_INPUT_FORMAT_INVALID", "画面识别输入应为 JPEG 或 PNG 图片", false, false);
            }
            return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new RecognitionException("OCR_INPUT_READ_FAILED", "无法读取待识别画面", true, false);
        }
    }

    private static RecognitionException uncertain(String message) {
        return new RecognitionException("OCR_RESULT_UNCERTAIN", message, false, true);
    }
}
