package com.vibeteaching.parsing.media;

public class RecognitionException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final boolean uncertain;

    public RecognitionException(String code, String message, boolean retryable, boolean uncertain) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.uncertain = uncertain;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public boolean uncertain() { return uncertain; }
}
