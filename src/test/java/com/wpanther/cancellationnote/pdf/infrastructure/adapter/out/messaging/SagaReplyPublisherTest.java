package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaReplyPublisher Unit Tests")
class SagaReplyPublisherTest {

    @Mock
    private OutboxService outboxService;

    private ObjectMapper objectMapper;
    private SagaReplyPublisher sagaReplyPublisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        sagaReplyPublisher = new SagaReplyPublisher(outboxService, objectMapper, "saga.reply.cancellation-note-pdf");
    }

    @Test
    @DisplayName("publishSuccess() calls OutboxService with SUCCESS reply")
    void testPublishSuccess() {
        // Given
        String sagaId = "saga-123";
        SagaStep sagaStep = SagaStep.GENERATE_CANCELLATION_NOTE_PDF;
        String correlationId = "corr-456";
        String pdfUrl = "http://localhost:9000/cancellationnotes/test.pdf";
        long pdfSize = 12345L;

        // When
        sagaReplyPublisher.publishSuccess(sagaId, sagaStep, correlationId, pdfUrl, pdfSize);

        // Then
        verify(outboxService).saveWithRouting(
                any(CancellationNotePdfReplyEvent.class),
                eq("CancellationNotePdfDocument"),
                eq(sagaId),
                eq("saga.reply.cancellation-note-pdf"),
                eq(sagaId),
                anyString() // headers JSON
        );
    }

    @Test
    @DisplayName("publishSuccess() includes pdfUrl and pdfSize in reply payload")
    void testPublishSuccess_ReplyPayload() {
        // Given
        String sagaId = "saga-1";
        String pdfUrl = "http://localhost:9000/cancellationnotes/test.pdf";
        long pdfSize = 12345L;

        // When
        sagaReplyPublisher.publishSuccess(sagaId, SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr-1", pdfUrl, pdfSize);

        // Then
        ArgumentCaptor<CancellationNotePdfReplyEvent> replyCaptor = ArgumentCaptor.forClass(CancellationNotePdfReplyEvent.class);
        verify(outboxService).saveWithRouting(
                replyCaptor.capture(),
                anyString(), anyString(), anyString(), anyString(),
                anyString()
        );

        CancellationNotePdfReplyEvent reply = replyCaptor.getValue();
        assertThat(reply.isSuccess()).isTrue();
        assertThat(reply.getPdfUrl()).isEqualTo(pdfUrl);
        assertThat(reply.getPdfSize()).isEqualTo(pdfSize);
    }

    @Test
    @DisplayName("publishFailure() calls OutboxService with FAILURE reply")
    void testPublishFailure() {
        // Given
        String sagaId = "saga-123";
        SagaStep sagaStep = SagaStep.GENERATE_CANCELLATION_NOTE_PDF;
        String correlationId = "corr-456";
        String errorMessage = "PDF generation failed";

        // When
        sagaReplyPublisher.publishFailure(sagaId, sagaStep, correlationId, errorMessage);

        // Then
        ArgumentCaptor<CancellationNotePdfReplyEvent> replyCaptor = ArgumentCaptor.forClass(CancellationNotePdfReplyEvent.class);
        verify(outboxService).saveWithRouting(
                replyCaptor.capture(),
                anyString(), anyString(), anyString(), anyString(),
                anyString()
        );

        CancellationNotePdfReplyEvent reply = replyCaptor.getValue();
        assertThat(reply.isFailure()).isTrue();
        assertThat(reply.getErrorMessage()).isEqualTo(errorMessage);
    }

    @Test
    @DisplayName("publishCompensated() calls OutboxService with COMPENSATED reply")
    void testPublishCompensated() {
        // Given
        String sagaId = "saga-123";
        SagaStep sagaStep = SagaStep.GENERATE_CANCELLATION_NOTE_PDF;
        String correlationId = "corr-456";

        // When
        sagaReplyPublisher.publishCompensated(sagaId, sagaStep, correlationId);

        // Then
        ArgumentCaptor<CancellationNotePdfReplyEvent> replyCaptor = ArgumentCaptor.forClass(CancellationNotePdfReplyEvent.class);
        verify(outboxService).saveWithRouting(
                replyCaptor.capture(),
                anyString(), anyString(), anyString(), anyString(),
                anyString()
        );

        CancellationNotePdfReplyEvent reply = replyCaptor.getValue();
        assertThat(reply.isCompensated()).isTrue();
    }
}
