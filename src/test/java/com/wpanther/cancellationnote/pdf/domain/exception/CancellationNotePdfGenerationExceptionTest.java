package com.wpanther.cancellationnote.pdf.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CancellationNotePdfGenerationException Tests")
class CancellationNotePdfGenerationExceptionTest {

    @Test
    @DisplayName("Should create with message")
    void shouldCreateWithMessage() {
        CancellationNotePdfGenerationException ex = new CancellationNotePdfGenerationException("error");
        assertThat(ex.getMessage()).isEqualTo("error");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("Should create with message and cause")
    void shouldCreateWithMessageAndCause() {
        Throwable cause = new RuntimeException("root");
        CancellationNotePdfGenerationException ex = new CancellationNotePdfGenerationException("error", cause);
        assertThat(ex.getMessage()).isEqualTo("error");
        assertThat(ex.getCause()).isEqualTo(cause);
    }
}
