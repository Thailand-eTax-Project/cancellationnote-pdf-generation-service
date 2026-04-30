package com.wpanther.cancellationnote.pdf.domain.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PdfGenerationConstants Tests")
class PdfGenerationConstantsTest {

    @Test
    @DisplayName("Should have correct document type")
    void shouldHaveCorrectDocumentType() {
        assertThat(PdfGenerationConstants.DOCUMENT_TYPE).isEqualTo("CANCELLATION_NOTE");
    }

    @Test
    @DisplayName("Should have correct S3 key prefix")
    void shouldHaveCorrectS3KeyPrefix() {
        assertThat(PdfGenerationConstants.S3_KEY_PREFIX).isEqualTo("cancellationnote-");
    }

    @Test
    @DisplayName("Should have correct PDF file extension")
    void shouldHaveCorrectPdfFileExtension() {
        assertThat(PdfGenerationConstants.PDF_FILE_EXTENSION).isEqualTo(".pdf");
    }

    @Test
    @DisplayName("Should have correct defaults")
    void shouldHaveCorrectDefaults() {
        assertThat(PdfGenerationConstants.DEFAULT_MAX_RETRIES).isEqualTo(3);
        assertThat(PdfGenerationConstants.DEFAULT_MAX_PDF_SIZE_BYTES).isEqualTo(52_428_800L);
        assertThat(PdfGenerationConstants.DEFAULT_MIME_TYPE).isEqualTo("application/pdf");
    }
}
