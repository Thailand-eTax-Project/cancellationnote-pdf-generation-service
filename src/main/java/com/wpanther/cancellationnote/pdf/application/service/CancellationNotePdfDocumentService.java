package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.cancellationnote.pdf.application.dto.event.CancellationNotePdfGeneratedEvent;
import com.wpanther.cancellationnote.pdf.application.dto.event.DocumentArchiveEvent;
import com.wpanther.cancellationnote.pdf.application.port.out.DocumentArchivePort;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfEventPort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.repository.CancellationNotePdfDocumentRepository;
import com.wpanther.cancellationnote.pdf.infrastructure.metrics.PdfGenerationMetrics;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationNotePdfDocumentService {

    private final CancellationNotePdfDocumentRepository repository;
    private final PdfEventPort pdfEventPort;
    private final SagaReplyPort sagaReplyPort;
    private final DocumentArchivePort documentArchivePort;
    private final PdfGenerationMetrics pdfGenerationMetrics;

    @Transactional(readOnly = true)
    public Optional<CancellationNotePdfDocument> findByCancellationNoteId(String cancellationNoteId) {
        return repository.findByCancellationNoteId(cancellationNoteId);
    }

    @Transactional
    public CancellationNotePdfDocument beginGeneration(String cancellationNoteId, String cancellationNoteNumber) {
        log.info("Initiating PDF generation for cancellation note: {}", cancellationNoteNumber);
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .cancellationNoteId(cancellationNoteId)
                .cancellationNoteNumber(cancellationNoteNumber)
                .build();
        doc.startGeneration();
        return repository.save(doc);
    }

    @Transactional
    public CancellationNotePdfDocument replaceAndBeginGeneration(
            UUID existingId, int previousRetryCount, String cancellationNoteId, String cancellationNoteNumber) {
        log.info("Replacing document {} and re-starting generation for cancellation note: {}", existingId, cancellationNoteNumber);
        repository.deleteById(existingId);
        repository.flush();
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .cancellationNoteId(cancellationNoteId)
                .cancellationNoteNumber(cancellationNoteNumber)
                .build();
        doc.startGeneration();
        doc.incrementRetryCountTo(previousRetryCount + 1);
        return repository.save(doc);
    }

    @Transactional
    public void completeGenerationAndPublish(UUID documentId, String s3Key, String fileUrl,
                                             long fileSize, int previousRetryCount,
                                             String cmdDocumentId, String cmdDocumentNumber,
                                             String sagaId, SagaStep sagaStep, String correlationId) {
        CancellationNotePdfDocument doc = requireDocument(documentId);
        doc.markCompleted(s3Key, fileUrl, fileSize);
        doc.markXmlEmbedded();
        applyRetryCount(doc, previousRetryCount);
        doc = repository.save(doc);

        // Emit document.archive for unsigned PDF archival
        documentArchivePort.publish(new DocumentArchiveEvent(
                cmdDocumentId,
                doc.getCancellationNoteNumber(),
                "CANCELLATION_NOTE",
                "UNSIGNED_PDF",
                doc.getDocumentUrl(),
                doc.getCancellationNoteNumber() + ".pdf",
                doc.getMimeType(),
                doc.getFileSize(),
                sagaId,
                correlationId));

        pdfEventPort.publishGenerated(buildGeneratedEvent(doc, cmdDocumentId, cmdDocumentNumber, sagaId, correlationId));
        sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId, doc.getDocumentUrl(), doc.getFileSize());

        log.info("Completed PDF generation for saga {} cancellation note {}",
                sagaId, doc.getCancellationNoteNumber());
    }

    @Transactional
    public void failGenerationAndPublish(UUID documentId, String errorMessage,
                                         int previousRetryCount,
                                         String sagaId, SagaStep sagaStep, String correlationId) {
        String safeError = errorMessage != null ? errorMessage : "PDF generation failed";
        CancellationNotePdfDocument doc = requireDocument(documentId);
        doc.markFailed(safeError);
        applyRetryCount(doc, previousRetryCount);
        repository.save(doc);

        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, safeError);

        log.warn("PDF generation failed for saga {} cancellation note {}: {}",
                sagaId, doc.getCancellationNoteNumber(), safeError);
    }

    @Transactional
    public void deleteById(UUID documentId) {
        repository.deleteById(documentId);
        repository.flush();
    }

    @Transactional
    public void publishIdempotentSuccess(CancellationNotePdfDocument existing,
                                         String documentId, String documentNumber,
                                         String sagaId, SagaStep sagaStep, String correlationId) {
        pdfEventPort.publishGenerated(buildGeneratedEvent(existing, documentId, documentNumber, sagaId, correlationId));
        sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId, existing.getDocumentUrl(), existing.getFileSize());
        log.warn("Cancellation note PDF already generated for saga {} — re-publishing SUCCESS reply", sagaId);
    }

    @Transactional
    public void publishRetryExhausted(String sagaId, SagaStep sagaStep, String correlationId,
                                      String documentId, String documentNumber) {
        pdfGenerationMetrics.recordRetryExhausted(sagaId, documentId, documentNumber);
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Maximum retry attempts exceeded");
        log.error("Max retries exceeded for saga {} document {}", sagaId, documentNumber);
    }

    @Transactional
    public void publishGenerationFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, errorMessage);
    }

    @Transactional
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
    }

    @Transactional
    public void publishCompensationFailure(String sagaId, SagaStep sagaStep, String correlationId, String error) {
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
    }

    private CancellationNotePdfGeneratedEvent buildGeneratedEvent(CancellationNotePdfDocument doc,
                                                                   String documentId, String documentNumber,
                                                                   String sagaId, String correlationId) {
        return new CancellationNotePdfGeneratedEvent(
                sagaId,
                documentId,
                doc.getCancellationNoteNumber(),
                doc.getDocumentUrl(),
                doc.getFileSize(),
                doc.isXmlEmbedded(),
                correlationId);
    }

    private CancellationNotePdfDocument requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> {
                    log.error("CancellationNotePdfDocument not found for id={}", documentId);
                    return new IllegalStateException("Expected cancellation note PDF document is absent");
                });
    }

    private void applyRetryCount(CancellationNotePdfDocument doc, int previousRetryCount) {
        if (previousRetryCount < 0) return;
        doc.incrementRetryCountTo(previousRetryCount + 1);
    }
}