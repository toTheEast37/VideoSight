package com.vibeteaching.parsing;

import org.springframework.http.HttpStatus;

public class ParseException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ParseException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
