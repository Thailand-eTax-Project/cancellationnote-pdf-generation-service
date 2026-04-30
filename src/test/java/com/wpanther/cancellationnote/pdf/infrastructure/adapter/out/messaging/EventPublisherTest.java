package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventPublisher Unit Tests")
class EventPublisherTest {

    @Mock
    private OutboxService outboxService;

    private ObjectMapper objectMapper;
    private EventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        eventPublisher = new EventPublisher(outboxService, objectMapper);
    }

    @Test
    @DisplayName("publishGenerated() calls OutboxService with correct parameters")
    void testPublishGenerated() {
        // Given
        String documentId = "doc-123";
        String documentNumber = "CN-2024-001";
        String documentUrl = "http://localhost:9000/cancellationnotes/test.pdf";
        long fileSize = 12345L;
        boolean xmlEmbedded = true;
        String correlationId = "corr-456";

        CancellationNotePdfGeneratedEvent event = new CancellationNotePdfGeneratedEvent(
                "saga-001", documentId, documentNumber, documentUrl, fileSize, xmlEmbedded, correlationId);

        // When
        eventPublisher.publishGenerated(event);

        // Then
        verify(outboxService).saveWithRouting(
                eq(event),
                eq("CancellationNotePdfDocument"),
                eq(documentId),
                eq("pdf.generated.cancellation-note"),
                eq(documentId),
                anyString() // headers JSON
        );
    }

    @Test
    @DisplayName("publishGenerated() includes documentType header")
    void testPublishGenerated_Headers() {
        // Given
        CancellationNotePdfGeneratedEvent event = new CancellationNotePdfGeneratedEvent(
                "saga-001", "doc-123", "CN-001",
                "http://localhost:9000/cancellationnotes/test.pdf", 12345L, true, "corr-456");

        // When
        eventPublisher.publishGenerated(event);

        // Then
        ArgumentCaptor<String> headersCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).saveWithRouting(
                any(), anyString(), anyString(), anyString(), anyString(),
                headersCaptor.capture()
        );

        String headersJson = headersCaptor.getValue();
        assertThat(headersJson).contains("\"documentType\":\"CANCELLATION_NOTE\"");
        assertThat(headersJson).contains("\"correlationId\":\"corr-456\"");
    }

    @Test
    @DisplayName("CancellationNotePdfGeneratedEvent stores sagaId and correlationId independently")
    void testSagaIdAndCorrelationIdStoredIndependently() throws Exception {
        // Given
        String sagaId = "saga-001";
        String correlationId = "corr-456";

        // When
        CancellationNotePdfGeneratedEvent event = new CancellationNotePdfGeneratedEvent(
                sagaId,
                "doc-123", "CN-2024-001",
                "http://localhost:9000/cancellationnotes/test.pdf", 12345L, true,
                correlationId);

        // Then -- constructor stores fields independently
        assertThat(event.getSagaId()).isEqualTo(sagaId);
        assertThat(event.getCorrelationId()).isEqualTo(correlationId);

        // And -- JSON round-trip preserves both fields
        String json = objectMapper.writeValueAsString(event);
        CancellationNotePdfGeneratedEvent deserialized = objectMapper.readValue(json, CancellationNotePdfGeneratedEvent.class);
        assertThat(deserialized.getSagaId()).isEqualTo(sagaId);
        assertThat(deserialized.getCorrelationId()).isEqualTo(correlationId);
    }
}
