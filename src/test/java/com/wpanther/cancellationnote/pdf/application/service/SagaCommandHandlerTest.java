package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfStoragePort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.cancellationnote.pdf.domain.service.CancellationNotePdfGenerationService;
import com.wpanther.cancellationnote.pdf.domain.model.GenerationStatus;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteCompensateCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteProcessCommand;
import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    @Mock
    private CancellationNotePdfDocumentService pdfDocumentService;

    @Mock
    private CancellationNotePdfGenerationService pdfGenerationService;

    @Mock
    private PdfStoragePort pdfStoragePort;

    @Mock
    private SagaReplyPort sagaReplyPort;

    @Mock
    private SignedXmlFetchPort signedXmlFetchPort;

    private SagaCommandHandler getHandler() {
        return new SagaCommandHandler(pdfDocumentService, pdfGenerationService, pdfStoragePort,
                sagaReplyPort, signedXmlFetchPort, 3);
    }

    private static final String SIGNED_XML_URL = "http://minio:9000/signed/cancellationnote-signed.xml";
    private static final String SIGNED_XML_CONTENT = "<CancellationNote>signed</CancellationNote>";

    private KafkaCancellationNoteProcessCommand createProcessCommand() {
        return new KafkaCancellationNoteProcessCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "doc-123", "CN-2024-001",
                SIGNED_XML_URL
        );
    }

    private KafkaCancellationNoteCompensateCommand createCompensateCommand() {
        return new KafkaCancellationNoteCompensateCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "doc-123"
        );
    }

    private CancellationNotePdfDocument createCompletedDocument() {
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .cancellationNoteId("doc-123")
                .cancellationNoteNumber("CN-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentPath("2024/01/15/cancellationnote-CN-2024-001-abc.pdf")
                .documentUrl("http://localhost:9000/cancellationnotes/2024/01/15/cancellationnote-CN-2024-001-abc.pdf")
                .fileSize(12345L)
                .build();
        return doc;
    }

    @Test
    @DisplayName("handle() process command: generates PDF and publishes SUCCESS")
    void testHandleProcessCommand_Success() throws Exception {
        // Given
        KafkaCancellationNoteProcessCommand command = createProcessCommand();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);

        CancellationNotePdfDocument generatingDoc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .cancellationNoteId("doc-123")
                .cancellationNoteNumber("CN-2024-001")
                .status(GenerationStatus.GENERATING)
                .build();
        when(pdfDocumentService.beginGeneration("doc-123", "CN-2024-001"))
                .thenReturn(generatingDoc);

        byte[] pdfBytes = new byte[5000];
        when(pdfGenerationService.generatePdf(anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(pdfStoragePort.store(anyString(), any(byte[].class)))
                .thenReturn("2024/01/15/cancellationnote-CN-2024-001-abc.pdf");
        when(pdfStoragePort.resolveUrl(anyString()))
                .thenReturn("http://localhost:9000/cancellationnotes/2024/01/15/cancellationnote-CN-2024-001-abc.pdf");

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService).beginGeneration("doc-123", "CN-2024-001");
        verify(pdfGenerationService).generatePdf("CN-2024-001", SIGNED_XML_CONTENT);
        verify(pdfStoragePort).store("CN-2024-001", pdfBytes);
        verify(pdfDocumentService).completeGenerationAndPublish(
                eq(generatingDoc.getId()),
                eq("2024/01/15/cancellationnote-CN-2024-001-abc.pdf"),
                eq("http://localhost:9000/cancellationnotes/2024/01/15/cancellationnote-CN-2024-001-abc.pdf"),
                eq(5000L),
                eq(-1),
                eq(command)
        );
    }

    @Test
    @DisplayName("handle() process command: idempotent SUCCESS for already completed document")
    void testHandleProcessCommand_AlreadyCompleted() {
        // Given
        KafkaCancellationNoteProcessCommand command = createProcessCommand();
        CancellationNotePdfDocument completedDoc = createCompletedDocument();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(completedDoc));

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verify(pdfDocumentService).publishIdempotentSuccess(completedDoc, command);
    }

    @Test
    @DisplayName("handle() process command: FAILURE when max retries exceeded")
    void testHandleProcessCommand_MaxRetriesExceeded() {
        // Given
        KafkaCancellationNoteProcessCommand command = createProcessCommand();
        CancellationNotePdfDocument failedDoc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .cancellationNoteId("doc-123")
                .cancellationNoteNumber("CN-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(3)
                .build();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(failedDoc));

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService).publishRetryExhausted(command);
    }

    @Test
    @DisplayName("handle() process command: FAILURE on signedXmlUrl validation")
    void testHandleProcessCommand_NullSignedXmlUrl() {
        // Given
        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "doc-123", "CN-2024-001",
                null);

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService).publishGenerationFailure(command, "signedXmlUrl is null or blank");
    }

    @Test
    @DisplayName("handle() process command: FAILURE on null documentId validation")
    void testHandleProcessCommand_NullDocumentId() {
        // Given
        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                null, "CN-2024-001",
                SIGNED_XML_URL);

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService).publishGenerationFailure(command, "documentId is null or blank");
    }

    @Test
    @DisplayName("handle() process command: FAILURE on null documentNumber validation")
    void testHandleProcessCommand_NullDocumentNumber() {
        // Given
        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456",
                "doc-123", null,
                SIGNED_XML_URL);

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService).publishGenerationFailure(command, "documentNumber is null or blank");
    }

    @Test
    @DisplayName("handle() process command: FAILURE on PDF generation failure")
    void testHandleProcessCommand_GenerationFails() throws Exception {
        // Given
        KafkaCancellationNoteProcessCommand command = createProcessCommand();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL)).thenReturn(SIGNED_XML_CONTENT);

        CancellationNotePdfDocument generatingDoc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .cancellationNoteId("doc-123")
                .cancellationNoteNumber("CN-2024-001")
                .status(GenerationStatus.GENERATING)
                .build();
        when(pdfDocumentService.beginGeneration("doc-123", "CN-2024-001"))
                .thenReturn(generatingDoc);

        when(pdfGenerationService.generatePdf(anyString(), anyString()))
                .thenThrow(new CancellationNotePdfGenerationException("FOP failed"));

        // When
        getHandler().handle(command);

        // Then
        verify(pdfDocumentService).failGenerationAndPublish(
                eq(generatingDoc.getId()),
                contains("CancellationNotePdfGenerationException: FOP failed"),
                eq(-1),
                eq(command)
        );
    }

    @Test
    @DisplayName("handle() process command: FAILURE on circuit breaker open")
    void testHandleProcessCommand_CircuitBreakerOpen() throws Exception {
        // Given
        KafkaCancellationNoteProcessCommand command = createProcessCommand();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL))
                .thenThrow(new RuntimeException("Circuit breaker open"));

        // When
        getHandler().handle(command);

        // Then - exception happens before document is created, so publishGenerationFailure is called
        verify(pdfDocumentService).publishGenerationFailure(eq(command), anyString());
    }

    @Test
    @DisplayName("handle() compensate command: deletes document and publishes COMPENSATED")
    void testHandleCompensation_Success() {
        // Given
        KafkaCancellationNoteCompensateCommand command = createCompensateCommand();
        CancellationNotePdfDocument doc = createCompletedDocument();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(doc));

        // When
        getHandler().handle(command);

        // Then
        verify(pdfStoragePort).delete("2024/01/15/cancellationnote-CN-2024-001-abc.pdf");
        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated(command);
    }

    @Test
    @DisplayName("handle() compensate command: COMPENSATED even when document not found")
    void testHandleCompensation_NoDocumentFound() {
        // Given
        KafkaCancellationNoteCompensateCommand command = createCompensateCommand();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.empty());

        // When
        getHandler().handle(command);

        // Then
        verify(pdfStoragePort, never()).delete(anyString());
        verify(pdfDocumentService, never()).deleteById(any());
        verify(pdfDocumentService).publishCompensated(command);
    }

    @Test
    @DisplayName("handle() compensate command: publishes COMPENSATED even when storage deletion fails (storage errors are logged only)")
    void testHandleCompensation_StorageFailure() {
        // Given
        KafkaCancellationNoteCompensateCommand command = createCompensateCommand();
        CancellationNotePdfDocument doc = createCompletedDocument();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("MinIO unavailable")).when(pdfStoragePort).delete(anyString());

        // When
        getHandler().handle(command);

        // Then - storage deletion failures are swallowed, compensation succeeds
        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated(command);
    }

    @Test
    @DisplayName("publishOrchestrationFailure() publishes failure for DLQ events")
    void testPublishOrchestrationFailure() {
        // Given
        KafkaCancellationNoteProcessCommand command = createProcessCommand();
        Throwable cause = new RuntimeException("DLQ error");

        // When
        getHandler().publishOrchestrationFailure(command, cause);

        // Then
        verify(sagaReplyPort).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF), eq("corr-456"),
                contains("Message routed to DLQ"));
    }

    @Test
    @DisplayName("publishCompensationOrchestrationFailure() publishes failure for compensation DLQ")
    void testPublishCompensationOrchestrationFailure() {
        // Given
        KafkaCancellationNoteCompensateCommand command = createCompensateCommand();
        Throwable cause = new RuntimeException("Compensation DLQ error");

        // When
        getHandler().publishCompensationOrchestrationFailure(command, cause);

        // Then
        verify(sagaReplyPort).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF), eq("corr-456"),
                contains("Compensation DLQ"));
    }
}
