package com.wpanther.cancellationnote.pdf.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.cancellationnote.pdf.application.port.in.CompensateCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.application.port.in.ProcessCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.SagaCommandHandler;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto.CompensateCancellationNotePdfCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto.ProcessCancellationNotePdfCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.SagaRouteConfig;
import com.wpanther.cancellationnote.pdf.application.dto.event.CancellationNotePdfGeneratedEvent;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging.CancellationNotePdfReplyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("CamelRouteConfig Unit Tests")
class CamelRouteConfigTest {

    @Mock
    private ProcessCancellationNotePdfUseCase processUseCase;

    @Mock
    private CompensateCancellationNotePdfUseCase compensateUseCase;

    @Mock
    private SagaCommandHandler sagaCommandHandler;

    private ObjectMapper objectMapper;
    private SagaRouteConfig sagaRouteConfig;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        sagaRouteConfig = new SagaRouteConfig(processUseCase, compensateUseCase, sagaCommandHandler, objectMapper);
    }

    @Test
    @DisplayName("Should serialize and deserialize ProcessCancellationNotePdfCommand")
    void testProcessCommandSerialization() throws Exception {
        // Given
        ProcessCancellationNotePdfCommand command = new ProcessCancellationNotePdfCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "doc-123", "CN-2024-001",
                "http://minio/cancellationnote-signed.xml"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        ProcessCancellationNotePdfCommand deserialized = objectMapper.readValue(json, ProcessCancellationNotePdfCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_CANCELLATION_NOTE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
        assertThat(deserialized.getDocumentNumber()).isEqualTo("CN-2024-001");
        assertThat(deserialized.getSignedXmlUrl()).isEqualTo("http://minio/cancellationnote-signed.xml");
        assertThat(deserialized.getEventId()).isNotNull();
    }

    @Test
    @DisplayName("Should serialize and deserialize CompensateCancellationNotePdfCommand")
    void testCompensateCommandSerialization() throws Exception {
        // Given
        CompensateCancellationNotePdfCommand command = new CompensateCancellationNotePdfCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "doc-123"
        );

        // When
        String json = objectMapper.writeValueAsString(command);
        CompensateCancellationNotePdfCommand deserialized = objectMapper.readValue(json, CompensateCancellationNotePdfCommand.class);

        // Then
        assertThat(deserialized.getSagaId()).isEqualTo("saga-001");
        assertThat(deserialized.getSagaStep()).isEqualTo(SagaStep.GENERATE_CANCELLATION_NOTE_PDF);
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-456");
        assertThat(deserialized.getDocumentId()).isEqualTo("doc-123");
    }

    @Test
    @DisplayName("Should serialize and deserialize CancellationNotePdfGeneratedEvent")
    void testGeneratedEventSerialization() throws Exception {
        // Given
        CancellationNotePdfGeneratedEvent event = new CancellationNotePdfGeneratedEvent(
                "saga-001", "doc-123", "CN-2024-001",
                "http://example.com/doc.pdf", 12345L, true, "corr-456"
        );

        // When
        String json = objectMapper.writeValueAsString(event);

        // Then
        assertThat(json).contains("\"eventType\":\"pdf.generated.cancellation-note\"");
        assertThat(json).contains("\"eventId\"");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should create CancellationNotePdfReplyEvent with correct status")
    void testReplyEventCreation() throws Exception {
        // Given
        CancellationNotePdfReplyEvent successReply = CancellationNotePdfReplyEvent.success(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "http://localhost:9000/cancellationnotes/test.pdf", 12345L);
        CancellationNotePdfReplyEvent failureReply = CancellationNotePdfReplyEvent.failure(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456", "error msg");
        CancellationNotePdfReplyEvent compensatedReply = CancellationNotePdfReplyEvent.compensated(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        assertThat(successReply.isSuccess()).isTrue();
        assertThat(successReply.getSagaId()).isEqualTo("saga-001");

        assertThat(failureReply.isFailure()).isTrue();
        assertThat(failureReply.getErrorMessage()).isEqualTo("error msg");

        assertThat(compensatedReply.isCompensated()).isTrue();

        // Verify serialization
        String json = objectMapper.writeValueAsString(successReply);
        assertThat(json).contains("\"sagaId\":\"saga-001\"");
        assertThat(json).contains("\"status\":\"SUCCESS\"");
    }

    @Test
    @DisplayName("Should deserialize ProcessCancellationNotePdfCommand from JSON")
    void testProcessCommandDeserialization() throws Exception {
        // Given - sagaStep uses kebab-case code as serialized by SagaStep @JsonValue
        String json = """
            {
                "eventId": "550e8400-e29b-41d4-a716-446655440000",
                "occurredAt": "2024-01-15T10:30:00Z",
                "eventType": "saga.command.cancellation-note-pdf",
                "version": 1,
                "sagaId": "saga-001",
                "sagaStep": "generate-cancellation-note-pdf",
                "correlationId": "corr-456",
                "documentId": "doc-123",
                "documentNumber": "CN-2024-001",
                "signedXmlUrl": "<CancellationNote>signed</CancellationNote>"
            }
            """;

        // When
        ProcessCancellationNotePdfCommand cmd = objectMapper.readValue(json, ProcessCancellationNotePdfCommand.class);

        // Then
        assertThat(cmd.getEventId()).isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        assertThat(cmd.getSagaId()).isEqualTo("saga-001");
        assertThat(cmd.getSagaStep()).isEqualTo(SagaStep.GENERATE_CANCELLATION_NOTE_PDF);
        assertThat(cmd.getCorrelationId()).isEqualTo("corr-456");
        assertThat(cmd.getDocumentId()).isEqualTo("doc-123");
        assertThat(cmd.getDocumentNumber()).isEqualTo("CN-2024-001");
        assertThat(cmd.getSignedXmlUrl()).isEqualTo("<CancellationNote>signed</CancellationNote>");
    }
}