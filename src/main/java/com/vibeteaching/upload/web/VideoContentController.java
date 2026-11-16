package com.vibeteaching.upload.web;

import com.vibeteaching.upload.UploadException;
import com.vibeteaching.upload.UploadService;
import com.vibeteaching.upload.model.VideoAsset;
import com.vibeteaching.upload.storage.UploadObjectStore;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class VideoContentController {
    private final UploadService uploads;
    private final UploadObjectStore objects;
    public VideoContentController(UploadService uploads, UploadObjectStore objects) {
        this.uploads = uploads;
        this.objects = objects;
    }

    @GetMapping("/api/videos/{assetId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable long assetId,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            @RequestParam(defaultValue = "false") boolean download) {
        VideoAsset asset = uploads.asset(assetId);
        if (!objects.bucket().equals(asset.bucket())) {
            throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "请恢复该视频原有的存储桶配置后读取");
        }
        var stored = objects.stat(asset.objectKey()).orElseThrow(() ->
                new UploadException(HttpStatus.NOT_FOUND, "ASSET_OBJECT_MISSING", "视频记录存在，但存储中的文件不可用"));
        if (stored.size() != asset.fileSize() || !asset.sha256().equals(stored.sha256())) {
            throw new UploadException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "视频对象与保存记录不一致，暂时无法读取");
        }
        long start = 0;
        long end = asset.fileSize() - 1;
        if (rangeHeader != null) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() != 1) throw new IllegalArgumentException();
                start = ranges.get(0).getRangeStart(asset.fileSize());
                end = ranges.get(0).getRangeEnd(asset.fileSize());
                if (start < 0 || start > end || start >= asset.fileSize()) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + asset.fileSize()).build();
            }
        }
        long offset = start;
        long length = end - start + 1;
        StreamingResponseBody body = output -> {
            try (InputStream input = objects.open(asset.objectKey(), offset, length)) {
                byte[] buffer = new byte[65536];
                long remaining = length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new java.io.IOException("视频数据意外结束");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(length);
        headers.setContentType(MediaType.parseMediaType(asset.contentType()));
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(asset.originalName(), StandardCharsets.UTF_8).build());
        if (rangeHeader != null) headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + asset.fileSize());
        return new ResponseEntity<>(body, headers, rangeHeader == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT);
    }
}
