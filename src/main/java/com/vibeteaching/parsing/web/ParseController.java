package com.vibeteaching.parsing.web;

import com.vibeteaching.parsing.ParseService;
import com.vibeteaching.parsing.ParseException;
import com.vibeteaching.parsing.ParseModels.TaskStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
public class ParseController {
    private final ParseService parsing;
    public ParseController(ParseService parsing) { this.parsing = parsing; }

    @PostMapping("/api/videos/{assetId}/parse")
    public ResponseEntity<ParseService.TaskView> submit(@PathVariable long assetId, @RequestBody Submit request) {
        if (request == null) throw new ParseException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_PARSE_REQUEST", "解析请求不能为空");
        var task = parsing.submit(assetId, request.requestKey());
        if (task.status() != TaskStatus.QUEUED && task.status() != TaskStatus.RUNNING) return ResponseEntity.ok(task);
        return ResponseEntity.accepted().body(task);
    }

    @GetMapping("/api/videos/{assetId}/parse")
    public ParseService.TaskView byAsset(@PathVariable long assetId) { return parsing.byAsset(assetId); }

    @GetMapping("/api/parse-tasks/{taskId}")
    public ParseService.TaskView task(@PathVariable String taskId) { return parsing.byId(taskId); }

    @PostMapping("/api/parse-tasks/{taskId}/retry")
    public ParseService.TaskView retry(@PathVariable String taskId, @RequestBody(required = false) Retry request) {
        return parsing.retry(taskId, request != null && request.confirmUncertain());
    }

    public record Submit(String requestKey) { }
    public record Retry(boolean confirmUncertain) { }
}
