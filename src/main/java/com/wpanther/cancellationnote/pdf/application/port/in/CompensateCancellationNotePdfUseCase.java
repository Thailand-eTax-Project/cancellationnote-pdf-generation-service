package com.wpanther.cancellationnote.pdf.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for cancellation note PDF compensation.
 * Called by SagaCommandHandler with plain fields — no command objects.
 */
public interface CompensateCancellationNotePdfUseCase {

    void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
