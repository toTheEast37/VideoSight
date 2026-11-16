package com.vibeteaching.upload.storage;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.config.ObjectStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class MinioUploadObjectStore implements UploadObjectStore {

    private final MinioClient client;
    private final String bucket;

    public MinioUploadObjectStore(MinioClient client, ObjectStorageProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    @Override
    public void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (ErrorResponseException failure) {
            if (!"BucketAlreadyOwnedByYou".equals(failure.errorResponse().code())) throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            Set<String> hashes = response.userMetadata().get("sha256");
            String sha256 = hashes != null && hashes.size() == 1 ? hashes.iterator().next() : null;
            return Optional.of(new StoredObject(response.size(), sha256));
        } catch (ErrorResponseException failure) {
            if ("NoSuchKey".equals(failure.errorResponse().code())
                    || "NoSuchObject".equals(failure.errorResponse().code())) return Optional.empty();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @Override
    public void put(String key, Path file, String contentType, String sha256) {
        try {
            client.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(key)
                    .filename(file.toAbsolutePath().toString()).contentType(contentType)
                    .userMetadata(Map.of("sha256", sha256)).build());
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @Override
    public InputStream open(String key, long offset, long length) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key)
                    .offset(offset).length(length).build());
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @Override
    public String bucket() {
        return bucket;
    }

    private static UploadException unavailable() {
        // 不把 SDK 的异常正文、端点或认证信息返回给页面。
        return new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "OBJECT_STORAGE_UNAVAILABLE",
                "视频文件存储暂时不可用，请稍后重试。");
    }
}
