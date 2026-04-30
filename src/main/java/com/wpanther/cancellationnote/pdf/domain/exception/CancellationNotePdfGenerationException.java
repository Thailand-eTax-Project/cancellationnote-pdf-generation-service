package com.wpanther.cancellationnote.pdf.domain.exception;

public class CancellationNotePdfGenerationException extends RuntimeException {

    public CancellationNotePdfGenerationException(String message) {
        super(message);
    }

    public CancellationNotePdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
