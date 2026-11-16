package com.vibeteaching.upload.web;

import com.vibeteaching.upload.UploadService;
import com.vibeteaching.upload.model.VideoAsset;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class UploadController {
    private final UploadService uploads;
    public UploadController(UploadService uploads) { this.uploads = uploads; }

    @PostMapping("/api/uploads")
    public UploadService.SessionView initialize(@RequestBody UploadService.InitializeRequest request) {
        return uploads.initialize(request);
    }

    @GetMapping("/api/uploads/{uploadId}")
    public UploadService.SessionView status(@PathVariable String uploadId) { return uploads.status(uploadId); }

    @PutMapping(value = "/api/uploads/{uploadId}/parts/{index}", consumes = "application/octet-stream")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void part(@PathVariable String uploadId, @PathVariable int index,
                     @RequestHeader("X-Chunk-SHA256") String digest, HttpServletRequest request) throws IOException {
        uploads.uploadPart(uploadId, index, digest, request.getContentLengthLong(), request.getInputStream());
    }

    @PostMapping("/api/uploads/{uploadId}/complete")
    public VideoAsset complete(@PathVariable String uploadId) { return uploads.complete(uploadId); }

    @GetMapping("/api/videos")
    public List<VideoAsset> videos() { return uploads.assets(); }

    @GetMapping("/api/videos/{assetId}")
    public VideoAsset video(@PathVariable long assetId) { return uploads.asset(assetId); }
}
