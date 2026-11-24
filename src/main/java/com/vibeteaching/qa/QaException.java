package com.vibeteaching.qa;

import org.springframework.http.HttpStatus;

public final class QaException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public QaException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static QaException invalidModelOutput() {
        return new QaException(HttpStatus.BAD_GATEWAY, "QA_INVALID_MODEL_RESPONSE", "模型返回的结果无法校验，请稍后再试");
    }
}
