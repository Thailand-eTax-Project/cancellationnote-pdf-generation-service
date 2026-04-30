package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationNotePdfGeneratedEventTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void constructor_setsAllFields() {
        var event = new CancellationNotePdfGeneratedEvent(
                "saga-1", "cnl-001", "CNL-2024-001",
                "http://minio/cnl.pdf", 5000L, true, "corr-1");

        assertThat(event.getSagaId()).isEqualTo("saga-1");
        assertThat(event.getCorrelationId()).isEqualTo("corr-1");
        assertThat(event.getDocumentId()).isEqualTo("cnl-001");
        assertThat(event.getDocumentNumber()).isEqualTo("CNL-2024-001");
        assertThat(event.getDocumentUrl()).isEqualTo("http://minio/cnl.pdf");
        assertThat(event.getFileSize()).isEqualTo(5000L);
        assertThat(event.isXmlEmbedded()).isTrue();
        assertThat(event.getEventType()).isEqualTo("pdf.generated.cancellation-note");
        assertThat(event.getSource()).isEqualTo("cancellationnote-pdf-generation-service");
        assertThat(event.getTraceType()).isEqualTo("PDF_GENERATED");
    }

    @Test
    void jsonCreator_constructor_setsAllFields() {
        var event = new CancellationNotePdfGeneratedEvent(
                UUID.randomUUID(), Instant.now(), "pdf.generated.cancellation-note", 1,
                "saga-1", "corr-1", "cancellationnote-pdf-generation-service",
                "PDF_GENERATED", null,
                "cnl-001", "CNL-2024-001", "http://minio/cnl.pdf", 5000L, true);

        assertThat(event.getDocumentId()).isEqualTo("cnl-001");
        assertThat(event.getDocumentNumber()).isEqualTo("CNL-2024-001");
        assertThat(event.getDocumentUrl()).isEqualTo("http://minio/cnl.pdf");
        assertThat(event.getFileSize()).isEqualTo(5000L);
        assertThat(event.isXmlEmbedded()).isTrue();
    }

    @Test
    void serialization_roundTrip() throws Exception {
        var original = new CancellationNotePdfGeneratedEvent(
                "saga-1", "cnl-001", "CNL-2024-001",
                "http://minio/cnl.pdf", 5000L, true, "corr-1");

        String json = objectMapper.writeValueAsString(original);
        var deserialized = objectMapper.readValue(json, CancellationNotePdfGeneratedEvent.class);

        assertThat(deserialized.getSagaId()).isEqualTo("saga-1");
        assertThat(deserialized.getDocumentId()).isEqualTo("cnl-001");
        assertThat(deserialized.getDocumentNumber()).isEqualTo("CNL-2024-001");
        assertThat(deserialized.getDocumentUrl()).isEqualTo("http://minio/cnl.pdf");
        assertThat(deserialized.getFileSize()).isEqualTo(5000L);
        assertThat(deserialized.isXmlEmbedded()).isTrue();
        assertThat(deserialized.getCorrelationId()).isEqualTo("corr-1");
    }
}