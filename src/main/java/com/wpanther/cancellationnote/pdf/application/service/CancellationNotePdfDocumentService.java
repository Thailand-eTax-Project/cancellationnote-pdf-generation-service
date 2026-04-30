package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.cancellationnote.pdf.application.port.out.PdfEventPort;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfStoragePort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.repository.CancellationNotePdfDocumentRepository;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteCompensateCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteProcessCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging.CancellationNotePdfGeneratedEvent;
import com.wpanther.cancellationnote.pdf.infrastructure.metrics.PdfGenerationMetrics;
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
                                             KafkaCancellationNoteProcessCommand command) {
        CancellationNotePdfDocument doc = requireDocument(documentId);
        doc.markCompleted(s3Key, fileUrl, fileSize);
        doc.markXmlEmbedded();
        applyRetryCount(doc, previousRetryCount);
        doc = repository.save(doc);

        pdfEventPort.publishGenerated(buildGeneratedEvent(doc, command));
        sagaReplyPort.publishSuccess(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                doc.getDocumentUrl(), doc.getFileSize());

        log.info("Completed PDF generation for saga {} cancellation note {}",
                command.getSagaId(), doc.getCancellationNoteNumber());
    }

    @Transactional
    public void failGenerationAndPublish(UUID documentId, String errorMessage,
                                         int previousRetryCount,
                                         KafkaCancellationNoteProcessCommand command) {
        String safeError = errorMessage != null ? errorMessage : "PDF generation failed";
        CancellationNotePdfDocument doc = requireDocument(documentId);
        doc.markFailed(safeError);
        applyRetryCount(doc, previousRetryCount);
        repository.save(doc);

        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), safeError);

        log.warn("PDF generation failed for saga {} cancellation note {}: {}",
                command.getSagaId(), doc.getCancellationNoteNumber(), safeError);
    }

    @Transactional
    public void deleteById(UUID documentId) {
        repository.deleteById(documentId);
        repository.flush();
    }

    @Transactional
    public void publishIdempotentSuccess(CancellationNotePdfDocument existing,
                                         KafkaCancellationNoteProcessCommand command) {
        pdfEventPort.publishGenerated(buildGeneratedEvent(existing, command));
        sagaReplyPort.publishSuccess(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                existing.getDocumentUrl(), existing.getFileSize());
        log.warn("Cancellation note PDF already generated for saga {} — re-publishing SUCCESS reply",
                command.getSagaId());
    }

    @Transactional
    public void publishRetryExhausted(KafkaCancellationNoteProcessCommand command) {
        pdfGenerationMetrics.recordRetryExhausted(
                command.getSagaId(),
                command.getDocumentId(),
                command.getDocumentNumber());
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                "Maximum retry attempts exceeded");
        log.error("Max retries exceeded for saga {} document {}",
                command.getSagaId(), command.getDocumentNumber());
    }

    @Transactional
    public void publishGenerationFailure(KafkaCancellationNoteProcessCommand command, String errorMessage) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), errorMessage);
    }

    @Transactional
    public void publishCompensated(KafkaCancellationNoteCompensateCommand command) {
        sagaReplyPort.publishCompensated(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId());
    }

    @Transactional
    public void publishCompensationFailure(KafkaCancellationNoteCompensateCommand command, String error) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), error);
    }

    private CancellationNotePdfGeneratedEvent buildGeneratedEvent(CancellationNotePdfDocument doc,
                                                                 KafkaCancellationNoteProcessCommand command) {
        return new CancellationNotePdfGeneratedEvent(
                command.getSagaId(),
                command.getDocumentId(),
                doc.getCancellationNoteNumber(),
                doc.getDocumentUrl(),
                doc.getFileSize(),
                doc.isXmlEmbedded(),
                command.getCorrelationId());
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
