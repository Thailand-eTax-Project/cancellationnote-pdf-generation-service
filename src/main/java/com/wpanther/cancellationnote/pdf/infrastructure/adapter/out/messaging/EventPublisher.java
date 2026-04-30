package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfEventPort;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Publishes integration events via outbox pattern for reliable delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher implements PdfEventPort {

    private static final String AGGREGATE_TYPE = OutboxConstants.AGGREGATE_TYPE;

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    /**
     * Publish PDF generated event to pdf.generated.cancellation-note topic (for Notification Service).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishGenerated(CancellationNotePdfGeneratedEvent event) {
        Map<String, String> headers = Map.of(
            "documentType", "CANCELLATION_NOTE",
            "correlationId", event.getCorrelationId()
        );

        outboxService.saveWithRouting(
            event,
            AGGREGATE_TYPE,
            event.getDocumentId(),
            "pdf.generated.cancellation-note",
            event.getDocumentId(),
            toJson(headers)
        );

        log.info("Published CancellationNotePdfGeneratedEvent to outbox for notification: {}", event.getDocumentNumber());
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event headers — aborting to prevent publishing without correlation headers", e);
        }
    }
}
