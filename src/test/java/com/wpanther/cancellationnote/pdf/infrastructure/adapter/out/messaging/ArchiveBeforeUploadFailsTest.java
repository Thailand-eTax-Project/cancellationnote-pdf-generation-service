package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.cancellationnote.pdf.application.dto.event.DocumentArchiveEvent;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Verifies the ordering invariant: sourceUrl must be populated before publishing.
 */
class ArchiveBeforeUploadFailsTest {

    private final OutboxService outbox = mock(OutboxService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OutboxDocumentArchiveAdapter adapter = new OutboxDocumentArchiveAdapter(outbox, mapper);

    @Test
    void rejectsNullSourceUrl() {
        DocumentArchiveEvent event = new DocumentArchiveEvent(
                "doc-1", "CN-1", "CANCELLATION_NOTE", "UNSIGNED_PDF",
                null, "x.pdf", "application/pdf", 1L, "s", "c");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> adapter.publish(event));
        assertThat(ex.getMessage()).contains("null/blank sourceUrl");
    }

    @Test
    void rejectsBlankSourceUrl() {
        DocumentArchiveEvent event = new DocumentArchiveEvent(
                "doc-1", "CN-1", "CANCELLATION_NOTE", "UNSIGNED_PDF",
                "   ", "x.pdf", "application/pdf", 1L, "s", "c");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> adapter.publish(event));
        assertThat(ex.getMessage()).contains("null/blank sourceUrl");
    }
}