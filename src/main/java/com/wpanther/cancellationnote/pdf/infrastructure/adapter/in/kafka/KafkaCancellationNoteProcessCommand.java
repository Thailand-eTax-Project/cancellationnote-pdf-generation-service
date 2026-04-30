package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;

import java.time.Instant;
import java.util.UUID;

public class KafkaCancellationNoteProcessCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")    private final String documentId;
    @JsonProperty("documentNumber") private final String documentNumber;
    @JsonProperty("signedXmlUrl")  private final String signedXmlUrl;

    @JsonCreator
    public KafkaCancellationNoteProcessCommand(
            @JsonProperty("eventId")       UUID eventId,
            @JsonProperty("occurredAt")    Instant occurredAt,
            @JsonProperty("eventType")     String eventType,
            @JsonProperty("version")       int version,
            @JsonProperty("sagaId")        String sagaId,
            @JsonProperty("sagaStep")      SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId")    String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("signedXmlUrl")  String signedXmlUrl) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId     = documentId;
        this.documentNumber = documentNumber;
        this.signedXmlUrl   = signedXmlUrl;
    }

    public KafkaCancellationNoteProcessCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                               String documentId, String documentNumber, String signedXmlUrl) {
        super(sagaId, sagaStep, correlationId);
        this.documentId     = documentId;
        this.documentNumber = documentNumber;
        this.signedXmlUrl   = signedXmlUrl;
    }

    @Override public String getSagaId()        { return super.getSagaId(); }
    @Override public SagaStep getSagaStep()    { return super.getSagaStep(); }
    @Override public String getCorrelationId() { return super.getCorrelationId(); }
    public String getDocumentId()     { return documentId; }
    public String getDocumentNumber() { return documentNumber; }
    public String getSignedXmlUrl()   { return signedXmlUrl; }
}
