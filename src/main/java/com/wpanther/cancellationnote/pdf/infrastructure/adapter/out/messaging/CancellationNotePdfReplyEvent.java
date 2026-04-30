package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaReply;

/**
 * Saga reply event for cancellation note PDF generation service.
 * Published to Kafka topic: saga.reply.cancellation-note-pdf
 *
 * SUCCESS replies include pdfUrl and pdfSize so the orchestrator
 * can forward the URL to subsequent steps.
 */
public class CancellationNotePdfReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

    // Additional fields included in SUCCESS replies
    private String pdfUrl;
    private Long pdfSize;

    public static CancellationNotePdfReplyEvent success(
            String sagaId, SagaStep sagaStep, String correlationId,
            String pdfUrl, Long pdfSize) {
        CancellationNotePdfReplyEvent reply = new CancellationNotePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.SUCCESS);
        reply.pdfUrl = pdfUrl;
        reply.pdfSize = pdfSize;
        return reply;
    }

    public static CancellationNotePdfReplyEvent failure(String sagaId, SagaStep sagaStep, String correlationId,
                                                         String errorMessage) {
        return new CancellationNotePdfReplyEvent(sagaId, sagaStep, correlationId, errorMessage);
    }

    public static CancellationNotePdfReplyEvent compensated(String sagaId, SagaStep sagaStep, String correlationId) {
        return new CancellationNotePdfReplyEvent(sagaId, sagaStep, correlationId, ReplyStatus.COMPENSATED);
    }

    private CancellationNotePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, ReplyStatus status) {
        super(sagaId, sagaStep, correlationId, status);
    }

    private CancellationNotePdfReplyEvent(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        super(sagaId, sagaStep, correlationId, errorMessage);
    }

    public String getPdfUrl() {
        return pdfUrl;
    }

    public Long getPdfSize() {
        return pdfSize;
    }
}
