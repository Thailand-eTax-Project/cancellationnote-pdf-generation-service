package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfStoragePort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.cancellationnote.pdf.domain.service.CancellationNotePdfGenerationService;
import com.wpanther.cancellationnote.pdf.domain.model.GenerationStatus;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.SagaCommandHandler;
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
    @DisplayName("handle() process: generates PDF and publishes SUCCESS")
    void testHandleProcess_Success() throws Exception {
        // Given
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
        getHandler().handle("doc-123", "CN-2024-001", SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

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
                eq("doc-123"),
                eq("CN-2024-001"),
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456")
        );
    }

    @Test
    @DisplayName("handle() process: idempotent SUCCESS for already completed document")
    void testHandleProcess_AlreadyCompleted() {
        // Given
        CancellationNotePdfDocument completedDoc = createCompletedDocument();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(completedDoc));

        // When
        getHandler().handle("doc-123", "CN-2024-001", SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService, never()).beginGeneration(anyString(), anyString());
        verify(pdfDocumentService).publishIdempotentSuccess(
                eq(completedDoc),
                eq("doc-123"),
                eq("CN-2024-001"),
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456")
        );
    }

    @Test
    @DisplayName("handle() process: FAILURE when max retries exceeded")
    void testHandleProcess_MaxRetriesExceeded() {
        // Given
        CancellationNotePdfDocument failedDoc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .cancellationNoteId("doc-123")
                .cancellationNoteNumber("CN-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(3)
                .build();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(failedDoc));

        // When
        getHandler().handle("doc-123", "CN-2024-001", SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService).publishRetryExhausted(
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456"),
                eq("doc-123"),
                eq("CN-2024-001")
        );
    }

    @Test
    @DisplayName("handle() process: FAILURE on signedXmlUrl validation")
    void testHandleProcess_NullSignedXmlUrl() {
        // When
        getHandler().handle("doc-123", "CN-2024-001", null,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService).publishGenerationFailure(
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456"),
                eq("signedXmlUrl is null or blank")
        );
    }

    @Test
    @DisplayName("handle() process: FAILURE on null documentId validation")
    void testHandleProcess_NullDocumentId() {
        // When
        getHandler().handle(null, "CN-2024-001", SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService).publishGenerationFailure(
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456"),
                eq("documentId is null or blank")
        );
    }

    @Test
    @DisplayName("handle() process: FAILURE on null documentNumber validation")
    void testHandleProcess_NullDocumentNumber() {
        // When
        getHandler().handle("doc-123", null, SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService).publishGenerationFailure(
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456"),
                eq("documentNumber is null or blank")
        );
    }

    @Test
    @DisplayName("handle() process: FAILURE on PDF generation failure")
    void testHandleProcess_GenerationFails() throws Exception {
        // Given
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
        getHandler().handle("doc-123", "CN-2024-001", SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService).failGenerationAndPublish(
                eq(generatingDoc.getId()),
                contains("CancellationNotePdfGenerationException: FOP failed"),
                eq(-1),
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456")
        );
    }

    @Test
    @DisplayName("handle() process: FAILURE on circuit breaker open")
    void testHandleProcess_CircuitBreakerOpen() throws Exception {
        // Given
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.empty());
        when(signedXmlFetchPort.fetch(SIGNED_XML_URL))
                .thenThrow(new RuntimeException("Circuit breaker open"));

        // When
        getHandler().handle("doc-123", "CN-2024-001", SIGNED_XML_URL,
                "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfDocumentService).publishGenerationFailure(
                eq("saga-001"),
                eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF),
                eq("corr-456"),
                anyString()
        );
    }

    @Test
    @DisplayName("handle() compensate: deletes document and publishes COMPENSATED")
    void testHandleCompensation_Success() {
        // Given
        CancellationNotePdfDocument doc = createCompletedDocument();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(doc));

        // When
        getHandler().handle("doc-123", "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfStoragePort).delete("2024/01/15/cancellationnote-CN-2024-001-abc.pdf");
        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated("saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");
    }

    @Test
    @DisplayName("handle() compensate: COMPENSATED even when document not found")
    void testHandleCompensation_NoDocumentFound() {
        // Given
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.empty());

        // When
        getHandler().handle("doc-123", "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then
        verify(pdfStoragePort, never()).delete(anyString());
        verify(pdfDocumentService, never()).deleteById(any());
        verify(pdfDocumentService).publishCompensated("saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");
    }

    @Test
    @DisplayName("handle() compensate: publishes COMPENSATED even when storage deletion fails (storage errors are logged only)")
    void testHandleCompensation_StorageFailure() {
        // Given
        CancellationNotePdfDocument doc = createCompletedDocument();
        when(pdfDocumentService.findByCancellationNoteId("doc-123")).thenReturn(Optional.of(doc));
        doThrow(new RuntimeException("MinIO unavailable")).when(pdfStoragePort).delete(anyString());

        // When
        getHandler().handle("doc-123", "saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");

        // Then - storage deletion failures are swallowed, compensation succeeds
        verify(pdfDocumentService).deleteById(doc.getId());
        verify(pdfDocumentService).publishCompensated("saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456");
    }

    @Test
    @DisplayName("publishOrchestrationFailure() publishes failure for DLQ events")
    void testPublishOrchestrationFailure() {
        // Given
        Throwable cause = new RuntimeException("DLQ error");

        // When
        getHandler().publishOrchestrationFailure("saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456", cause);

        // Then
        verify(sagaReplyPort).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF), eq("corr-456"),
                contains("Message routed to DLQ"));
    }

    @Test
    @DisplayName("publishCompensationOrchestrationFailure() publishes failure for compensation DLQ")
    void testPublishCompensationOrchestrationFailure() {
        // Given
        Throwable cause = new RuntimeException("Compensation DLQ error");

        // When
        getHandler().publishCompensationOrchestrationFailure("saga-001", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-456", cause);

        // Then
        verify(sagaReplyPort).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_CANCELLATION_NOTE_PDF), eq("corr-456"),
                contains("Compensation DLQ"));
    }
}
