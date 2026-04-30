package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfEventPort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.domain.model.GenerationStatus;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.repository.CancellationNotePdfDocumentRepository;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteCompensateCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteProcessCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging.CancellationNotePdfGeneratedEvent;
import com.wpanther.cancellationnote.pdf.infrastructure.metrics.PdfGenerationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancellationNotePdfDocumentService Unit Tests")
class CancellationNotePdfDocumentServiceTest {

    @Mock
    private CancellationNotePdfDocumentRepository repository;

    @Mock
    private PdfEventPort pdfEventPort;

    @Mock
    private SagaReplyPort sagaReplyPort;

    @Mock
    private PdfGenerationMetrics pdfGenerationMetrics;

    // Note: Using reflection to instantiate because Lombok @RequiredArgsConstructor
    // is scope=provided, not available during test compilation
    private CancellationNotePdfDocumentService getService() {
        try {
            return CancellationNotePdfDocumentService.class
                    .getDeclaredConstructor(CancellationNotePdfDocumentRepository.class,
                                           PdfEventPort.class, SagaReplyPort.class,
                                           PdfGenerationMetrics.class)
                    .newInstance(repository, pdfEventPort, sagaReplyPort, pdfGenerationMetrics);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate CancellationNotePdfDocumentService", e);
        }
    }

    private CancellationNotePdfDocument createCompletedDocument() {
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .cancellationNoteId("cn-inv-001")
                .cancellationNoteNumber("CN-001")
                .status(GenerationStatus.GENERATING) // Start in GENERATING
                .mimeType("application/pdf")
                .build();
        // Now transition to COMPLETED via the proper method
        doc.markCompleted("2024/01/15/test.pdf", "http://localhost:9000/cancellationnotes/test.pdf", 5000L);
        doc.markXmlEmbedded();
        return doc;
    }

    @Test
    @DisplayName("findByCancellationNoteId() delegates to repository")
    void testFindByCancellationNoteId() {
        // Given
        CancellationNotePdfDocument doc = createCompletedDocument();
        when(repository.findByCancellationNoteId("cn-inv-001")).thenReturn(Optional.of(doc));

        // When
        var service = getService();
        Optional<CancellationNotePdfDocument> result = service.findByCancellationNoteId("cn-inv-001");

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getCancellationNoteNumber()).isEqualTo("CN-001");
    }

    @Test
    @DisplayName("beginGeneration() creates GENERATING document")
    void testBeginGeneration() {
        // Given
        CancellationNotePdfDocument savedDoc = CancellationNotePdfDocument.builder()
                .id(UUID.randomUUID())
                .status(GenerationStatus.GENERATING)
                .cancellationNoteId("cn-inv-001")
                .cancellationNoteNumber("CN-001")
                .mimeType("application/pdf")
                .build();
        when(repository.save(any())).thenReturn(savedDoc);

        // When
        var service = getService();
        service.beginGeneration("cn-inv-001", "CN-001");

        // Then
        ArgumentCaptor<CancellationNotePdfDocument> captor = ArgumentCaptor.forClass(CancellationNotePdfDocument.class);
        verify(repository).save(captor.capture());

        CancellationNotePdfDocument toSave = captor.getValue();
        assertThat(toSave.getCancellationNoteId()).isEqualTo("cn-inv-001");
        assertThat(toSave.getCancellationNoteNumber()).isEqualTo("CN-001");
    }

    @Test
    @DisplayName("deleteById() deletes document and flushes")
    void testDeleteById() {
        // When
        var service = getService();
        service.deleteById(UUID.randomUUID());

        // Then
        verify(repository).deleteById(any(UUID.class));
        verify(repository).flush();
    }

    @Test
    @DisplayName("publishIdempotentSuccess() publishes events for already completed document")
    void testPublishIdempotentSuccess() {
        // Given
        CancellationNotePdfDocument doc = createCompletedDocument();
        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1", "CN-001",
                "http://minio:9000/signed.xml");

        // When
        var service = getService();
        service.publishIdempotentSuccess(doc, command);

        // Then
        verify(pdfEventPort).publishGenerated(any(CancellationNotePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "http://localhost:9000/cancellationnotes/test.pdf", 5000L);
    }

    @Test
    @DisplayName("publishRetryExhausted() publishes failure reply")
    void testPublishRetryExhausted() {
        // Given
        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1", "CN-001",
                "http://minio:9000/signed.xml");

        // When
        var service = getService();
        service.publishRetryExhausted(command);

        // Then
        verify(pdfGenerationMetrics).recordRetryExhausted("saga-1", "doc-1", "CN-001");
        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "Maximum retry attempts exceeded");
    }

    @Test
    @DisplayName("publishGenerationFailure() publishes failure with error message")
    void testPublishGenerationFailure() {
        // Given
        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1", "CN-001",
                "http://minio:9000/signed.xml");
        String errorMessage = "Invalid XML format";

        // When
        var service = getService();
        service.publishGenerationFailure(command, errorMessage);

        // Then
        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                errorMessage);
    }

    @Test
    @DisplayName("publishCompensated() publishes COMPENSATED reply")
    void testPublishCompensated() {
        // Given
        KafkaCancellationNoteCompensateCommand command = new KafkaCancellationNoteCompensateCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1");

        // When
        var service = getService();
        service.publishCompensated(command);

        // Then
        verify(sagaReplyPort).publishCompensated("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1");
    }

    @Test
    @DisplayName("publishCompensationFailure() publishes failure for compensation error")
    void testPublishCompensationFailure() {
        // Given
        KafkaCancellationNoteCompensateCommand command = new KafkaCancellationNoteCompensateCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1");
        String error = "Failed to delete PDF file";

        // When
        var service = getService();
        service.publishCompensationFailure(command, error);

        // Then
        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1", error);
    }

    @Test
    @DisplayName("completeGenerationAndPublish() marks COMPLETED and publishes events")
    void testCompleteGenerationAndPublish() {
        // Given
        UUID documentId = UUID.randomUUID();
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .id(documentId)
                .cancellationNoteId("cn-inv-001")
                .cancellationNoteNumber("CN-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();

        // Create a completed document with the same ID for the mock return
        CancellationNotePdfDocument savedDoc = CancellationNotePdfDocument.builder()
                .id(documentId)
                .cancellationNoteId("cn-inv-001")
                .cancellationNoteNumber("CN-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();
        savedDoc.markCompleted("2024/01/15/test.pdf", "http://localhost:9000/cancellationnotes/test.pdf", 5000L);
        savedDoc.markXmlEmbedded();

        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenReturn(savedDoc);

        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1", "CN-001",
                "http://minio:9000/signed.xml");

        // When
        var service = getService();
        service.completeGenerationAndPublish(documentId, "2024/01/15/test.pdf",
                "http://localhost:9000/cancellationnotes/test.pdf", 5000L, 0, command);

        // Then
        ArgumentCaptor<CancellationNotePdfDocument> captor = ArgumentCaptor.forClass(CancellationNotePdfDocument.class);
        verify(repository).save(captor.capture());

        CancellationNotePdfDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(saved.isXmlEmbedded()).isTrue();

        verify(pdfEventPort).publishGenerated(any(CancellationNotePdfGeneratedEvent.class));
        verify(sagaReplyPort).publishSuccess("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "http://localhost:9000/cancellationnotes/test.pdf", 5000L);
    }

    @Test
    @DisplayName("failGenerationAndPublish() marks FAILED and publishes failure")
    void testFailGenerationAndPublish() {
        // Given
        UUID documentId = UUID.randomUUID();
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .id(documentId)
                .cancellationNoteId("cn-inv-001")
                .cancellationNoteNumber("CN-001")
                .status(GenerationStatus.GENERATING)
                .mimeType("application/pdf")
                .build();
        CancellationNotePdfDocument savedDoc = CancellationNotePdfDocument.builder()
                .id(documentId)
                .cancellationNoteId("cn-inv-001")
                .cancellationNoteNumber("CN-001")
                .status(GenerationStatus.FAILED)
                .errorMessage("PDF generation failed")
                .mimeType("application/pdf")
                .build();
        when(repository.findById(documentId)).thenReturn(Optional.of(doc));
        when(repository.save(any())).thenReturn(savedDoc);

        KafkaCancellationNoteProcessCommand command = new KafkaCancellationNoteProcessCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1",
                "doc-1", "CN-001",
                "http://minio:9000/signed.xml");
        String errorMessage = "PDF generation failed";

        // When
        var service = getService();
        service.failGenerationAndPublish(documentId, errorMessage, 0, command);

        // Then
        ArgumentCaptor<CancellationNotePdfDocument> captor = ArgumentCaptor.forClass(CancellationNotePdfDocument.class);
        verify(repository).save(captor.capture());

        CancellationNotePdfDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(saved.getErrorMessage()).isEqualTo(errorMessage);

        verify(sagaReplyPort).publishFailure("saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1", errorMessage);
    }
}
