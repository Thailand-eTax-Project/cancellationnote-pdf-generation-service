package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.cancellationnote.pdf.application.port.out.PdfStoragePort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.cancellationnote.pdf.application.usecase.CompensateCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.application.usecase.ProcessCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.service.CancellationNotePdfGenerationService;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteCompensateCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteProcessCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Service
@Slf4j
public class SagaCommandHandler implements ProcessCancellationNotePdfUseCase, CompensateCancellationNotePdfUseCase {

    private static final String MDC_SAGA_ID        = "sagaId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_DOCUMENT_NUMBER = "documentNumber";
    private static final String MDC_DOCUMENT_ID     = "documentId";

    private final CancellationNotePdfDocumentService pdfDocumentService;
    private final CancellationNotePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;
    private final SagaReplyPort sagaReplyPort;
    private final SignedXmlFetchPort signedXmlFetchPort;
    private final int maxRetries;

    public SagaCommandHandler(CancellationNotePdfDocumentService pdfDocumentService,
                              CancellationNotePdfGenerationService pdfGenerationService,
                              PdfStoragePort pdfStoragePort,
                              SagaReplyPort sagaReplyPort,
                              SignedXmlFetchPort signedXmlFetchPort,
                              @Value("${app.pdf.generation.max-retries:3}") int maxRetries) {
        this.pdfDocumentService = pdfDocumentService;
        this.pdfGenerationService = pdfGenerationService;
        this.pdfStoragePort = pdfStoragePort;
        this.sagaReplyPort = sagaReplyPort;
        this.signedXmlFetchPort = signedXmlFetchPort;
        this.maxRetries = maxRetries;
    }

    @Override
    public void handle(KafkaCancellationNoteProcessCommand command) {
        MDC.put(MDC_SAGA_ID,         command.getSagaId());
        MDC.put(MDC_CORRELATION_ID,  command.getCorrelationId());
        MDC.put(MDC_DOCUMENT_NUMBER, command.getDocumentNumber());
        MDC.put(MDC_DOCUMENT_ID,     command.getDocumentId());
        try {
            log.info("Handling ProcessCommand for saga {} document {}",
                    command.getSagaId(), command.getDocumentNumber());
            try {
                String signedXmlUrl  = command.getSignedXmlUrl();
                String documentId    = command.getDocumentId();
                String documentNum   = command.getDocumentNumber();
                String sagaId         = command.getSagaId();
                SagaStep sagaStep     = command.getSagaStep();
                String correlationId  = command.getCorrelationId();

                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "signedXmlUrl is null or blank");
                    return;
                }
                if (documentId == null || documentId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentId is null or blank");
                    return;
                }
                if (documentNum == null || documentNum.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentNumber is null or blank");
                    return;
                }

                Optional<CancellationNotePdfDocument> existing =
                        pdfDocumentService.findByCancellationNoteId(documentId);

                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), documentId, documentNum, sagaId, sagaStep, correlationId);
                    return;
                }

                int previousRetryCount = existing.map(CancellationNotePdfDocument::getRetryCount).orElse(-1);

                if (existing.isPresent()) {
                    if (existing.get().isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(sagaId, sagaStep, correlationId, documentId, documentNum);
                        return;
                    }
                }

                CancellationNotePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, documentId, documentNum);
                } else {
                    document = pdfDocumentService.beginGeneration(documentId, documentNum);
                }

                String s3Key = null;
                try {
                    String signedXml = signedXmlFetchPort.fetch(signedXmlUrl);
                    byte[] pdfBytes  = pdfGenerationService.generatePdf(documentNum, signedXml);
                    s3Key = pdfStoragePort.store(documentNum, pdfBytes);
                    String fileUrl   = pdfStoragePort.resolveUrl(s3Key);

                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length, previousRetryCount,
                            documentId, documentNum, sagaId, sagaStep, correlationId);

                } catch (CallNotPermittedException e) {
                    log.warn("Circuit breaker OPEN for saga {} document {}: {}",
                            sagaId, documentNum, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "Circuit breaker open: " + e.getMessage(),
                            previousRetryCount, sagaId, sagaStep, correlationId);

                } catch (RestClientException e) {
                    log.warn("HTTP error fetching signed XML for saga {} document {}: {}",
                            sagaId, documentNum, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "HTTP error fetching signed XML: " + describeThrowable(e),
                            previousRetryCount, sagaId, sagaStep, correlationId);

                } catch (Exception e) {
                    if (s3Key != null) {
                        try { pdfStoragePort.delete(s3Key); }
                        catch (Exception del) {
                            log.error("[ORPHAN_PDF] s3Key={} saga={} error={}", s3Key, sagaId,
                                    describeThrowable(del));
                        }
                    }
                    log.error("PDF generation failed for saga {} document {}: {}",
                            sagaId, documentNum, e.getMessage(), e);
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount, sagaId, sagaStep, correlationId);
                }

            } catch (OptimisticLockingFailureException e) {
                log.warn("Concurrent modification for saga {}: {}", command.getSagaId(), e.getMessage());
                pdfDocumentService.publishGenerationFailure(command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), "Concurrent modification: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {}: {}", command.getSagaId(), e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handle(KafkaCancellationNoteCompensateCommand command) {
        MDC.put(MDC_SAGA_ID,        command.getSagaId());
        MDC.put(MDC_CORRELATION_ID,  command.getCorrelationId());
        MDC.put(MDC_DOCUMENT_ID,     command.getDocumentId());
        try {
            log.info("Handling compensation for saga {} document {}",
                    command.getSagaId(), command.getDocumentId());
            try {
                Optional<CancellationNotePdfDocument> existing =
                        pdfDocumentService.findByCancellationNoteId(command.getDocumentId());

                if (existing.isPresent()) {
                    CancellationNotePdfDocument doc = existing.get();
                    pdfDocumentService.deleteById(doc.getId());
                    if (doc.getDocumentPath() != null) {
                        try { pdfStoragePort.delete(doc.getDocumentPath()); }
                        catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    command.getSagaId(), doc.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated CancellationNotePdfDocument {} for saga {}",
                            doc.getId(), command.getSagaId());
                } else {
                    log.info("No document for documentId {} — already compensated",
                            command.getDocumentId());
                }
                pdfDocumentService.publishCompensated(command.getSagaId(), command.getSagaStep(), command.getCorrelationId());

            } catch (Exception e) {
                log.error("Failed to compensate for saga {}: {}", command.getSagaId(), e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(
                        command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), "Compensation failed: " + describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(KafkaCancellationNoteProcessCommand command, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(),
                    "Message routed to DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {}", command.getSagaId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(KafkaCancellationNoteCompensateCommand command, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(),
                    "Compensation DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {}", command.getSagaId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailureForUnparsedMessage(
            String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Message routed to DLQ after deserialization failure: "
                    + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after DLQ routing (deserialization failure) for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ deserialization failure for saga {} — orchestrator must timeout",
                    sagaId, e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}