package com.vibeteaching.parsing.web;

import com.vibeteaching.parsing.ParseException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ParseController.class)
public class ParseErrorHandler {
    @ExceptionHandler(ParseException.class)
    ResponseEntity<Map<String, String>> parsing(ParseException e) { return error(e.status(), e.code(), e.getMessage()); }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "PARSE_DATABASE_UNAVAILABLE", "解析任务保存或查询失败，尚未确认成功，请稍后查询原视频");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARSE_REQUEST", "解析请求格式不正确");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
