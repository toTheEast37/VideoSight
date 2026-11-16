package com.vibeteaching.upload.storage;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.config.ObjectStorageProperties;
import com.sun.net.httpserver.HttpServer;
import io.minio.Http;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioUploadObjectStoreTest {

    private final MinioClient client = mock(MinioClient.class);
    private final MinioUploadObjectStore objects = new MinioUploadObjectStore(client,
            new ObjectStorageProperties("http://127.0.0.1:9000", "placeholder", "placeholder", "videos"));

    @Test
    void minioClientCanInitializeItsHttpRuntimeWithoutConnecting() throws Exception {
        try (MinioClient initialized = MinioClient.builder().endpoint("http://127.0.0.1:9000")
                .credentials("placeholder", "placeholder").build()) {
            assertThat(initialized).isNotNull();
        }
    }

    @Test
    void missingObjectCanBeUploadedAgain() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(error("NoSuchKey"));
        assertThat(objects.stat("uploads/one/parts/0")).isEmpty();
    }

    @Test
    void missingBucketMustNotBeMistakenForAnAbsentPart() throws Exception {
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(error("NoSuchBucket"));
        assertUnavailable(() -> objects.stat("uploads/one/parts/0"));
    }

    @Test
    void writeFailureDoesNotReturnSuccessOrExposeSdkDetails(@TempDir Path directory) throws Exception {
        Path part = Files.write(directory.resolve("part"), new byte[]{1, 2});
        when(client.uploadObject(any(UploadObjectArgs.class))).thenThrow(new IllegalStateException("私密端点与认证信息"));
        assertUnavailable(() -> objects.put("uploads/one/parts/0", part, "application/octet-stream", "a".repeat(64)));
    }

    @Test
    void storedDigestIsReadFromUserMetadata() throws Exception {
        StatObjectResponse response = mock(StatObjectResponse.class);
        when(response.size()).thenReturn(8L);
        when(response.userMetadata()).thenReturn(new Http.Headers(Map.of("sha256", "a".repeat(64))));
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(response);
        assertThat(objects.stat("videos/one")).contains(new UploadObjectStore.StoredObject(8, "a".repeat(64)));
    }

    @Test
    void sdkActuallySendsTheBytesAndDigestAndReadsRanges(@TempDir Path directory) throws Exception {
        // 这是本地可控 HTTP 替身，验证 SDK 接线；不等同于真实 MinIO 服务验收。
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<String> digest = new AtomicReference<>();
        AtomicReference<String> range = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
                exchange.getResponseHeaders().set("Last-Modified", "Sun, 15 Nov 2026 00:00:00 GMT");
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        body.set(exchange.getRequestBody().readAllBytes());
                        digest.set(exchange.getRequestHeaders().getFirst("x-amz-meta-sha256"));
                        exchange.sendResponseHeaders(200, -1);
                    }
                    case "HEAD" -> {
                        exchange.getResponseHeaders().set("Content-Length", "4");
                        exchange.getResponseHeaders().set("x-amz-meta-sha256", "a".repeat(64));
                        exchange.sendResponseHeaders(200, -1);
                    }
                    case "GET" -> {
                        range.set(exchange.getRequestHeaders().getFirst("Range"));
                        exchange.getResponseHeaders().set("Content-Range", "bytes 1-2/4");
                        exchange.sendResponseHeaders(206, 2);
                        exchange.getResponseBody().write(new byte[]{2, 3});
                    }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try (MinioClient initialized = MinioClient.builder().endpoint(endpoint).region("us-east-1")
                .credentials("placeholder", "placeholder").build()) {
            MinioUploadObjectStore actual = new MinioUploadObjectStore(initialized,
                    new ObjectStorageProperties(endpoint, "placeholder", "placeholder", "videos"));
            Path file = Files.write(directory.resolve("part"), new byte[]{1, 2, 3, 4});
            actual.put("uploads/one/parts/0", file, "application/octet-stream", "a".repeat(64));
            assertThat(body.get()).containsExactly(1, 2, 3, 4);
            assertThat(digest.get()).isEqualTo("a".repeat(64));
            assertThat(actual.stat("uploads/one/parts/0"))
                    .contains(new UploadObjectStore.StoredObject(4, "a".repeat(64)));
            try (var stream = actual.open("uploads/one/parts/0", 1, 2)) {
                assertThat(stream.readAllBytes()).containsExactly(2, 3);
            }
            assertThat(range.get()).isEqualTo("bytes=1-2");
        } finally {
            server.stop(0);
        }
    }

    private static ErrorResponseException error(String code) {
        return new ErrorResponseException(new ErrorResponse(code, "私密端点与认证信息", "videos", "object", "resource",
                "request", "host"), null, "");
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(UploadException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.getCode()).isEqualTo("OBJECT_STORAGE_UNAVAILABLE");
            assertThat(error.getMessage()).doesNotContain("私密", "认证", "端点");
        });
    }
}
