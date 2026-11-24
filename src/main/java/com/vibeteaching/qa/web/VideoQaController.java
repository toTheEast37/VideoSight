package com.vibeteaching.qa.web;

import com.vibeteaching.parsing.ParseException;
import com.vibeteaching.qa.QaException;
import com.vibeteaching.qa.QaProperties;
import com.vibeteaching.qa.VideoAgentLoop;
import com.vibeteaching.qa.VideoQaService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@EnableConfigurationProperties(QaProperties.class)
public class VideoQaController {
    private final ObjectProvider<VideoQaService> service;
    private final ObjectProvider<VideoAgentLoop> agent;

    public VideoQaController(ObjectProvider<VideoQaService> service, ObjectProvider<VideoAgentLoop> agent) {
        this.service = service;
        this.agent = agent;
    }

    @PostMapping("/api/videos/{assetId}/ask")
    VideoQaService.Answer ask(@PathVariable long assetId, @RequestBody Ask request) {
        VideoQaService qa = service.getIfAvailable();
        if (qa == null) {
            throw new QaException(HttpStatus.SERVICE_UNAVAILABLE, "QA_DISABLED", "视频问答尚未启用");
        }
        return qa.ask(assetId, request.question());
    }

    @PostMapping("/api/videos/{assetId}/ask/agent")
    VideoAgentLoop.AgentAnswer askAgent(@PathVariable long assetId, @RequestBody Ask request) {
        VideoAgentLoop loop = agent.getIfAvailable();
        if (loop == null) {
            throw new QaException(HttpStatus.SERVICE_UNAVAILABLE, "QA_DISABLED", "视频问答尚未启用");
        }
        return loop.ask(assetId, request.question());
    }

    @ExceptionHandler(QaException.class)
    ResponseEntity<Map<String, String>> qaError(QaException error) {
        return error(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(ParseException.class)
    ResponseEntity<Map<String, String>> parseError(ParseException error) {
        return error(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "QA_DATABASE_UNAVAILABLE", "暂时无法读取或保存检索数据，请稍后再试");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QA_REQUEST", "问答请求格式不正确");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    public record Ask(String question) { }
}
