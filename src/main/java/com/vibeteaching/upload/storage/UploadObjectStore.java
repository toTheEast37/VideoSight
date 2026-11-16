package com.vibeteaching.upload.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

public interface UploadObjectStore {
    void ensureBucket();

    Optional<StoredObject> stat(String key);

    void put(String key, Path file, String contentType, String sha256);

    InputStream open(String key, long offset, long length);

    void delete(String key);

    String bucket();

    record StoredObject(long size, String sha256) {
    }
}
