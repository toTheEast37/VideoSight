package com.vibeteaching.upload.progress;

import com.vibeteaching.upload.model.UploadSession;

import java.util.Map;

public interface UploadProgressStore {
    void initialize(UploadSession session);

    Map<Integer, String> snapshot(UploadSession session);

    void recordPart(UploadSession session, int index, String sha256);

    void renew(UploadSession session);

    void delete(String uploadId);
}
