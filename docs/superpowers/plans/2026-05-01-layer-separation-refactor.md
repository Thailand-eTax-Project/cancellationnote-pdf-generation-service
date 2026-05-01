# cancellationnote-pdf-generation-service Layer Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor cancellationnote-pdf-generation-service to place saga infrastructure types in `infrastructure/` and use case interfaces with plain parameters in `application/port/in/`, matching the architecture of invoice-pdf-generation-service.

**Architecture:** Kafka DTOs (SagaCommand + Jackson) live in `infrastructure/adapter/in/kafka/dto/`. Use case interfaces accept plain field parameters. `SagaCommandHandler` routes DTOs to use cases by extracting fields — no command objects flow into application/service layer. `CancellationNotePdfGeneratedEvent` moves to `application/dto/event/` as it extends TraceEvent (notification DTO, not domain). `CancellationNotePdfReplyEvent` remains a separate top-level class (unchanged), matching invoice-pdf-generation-service reference.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, saga-commons library, Jackson

---

## File Changes Overview

```
CREATING:
  infrastructure/adapter/in/kafka/dto/ProcessCancellationNotePdfCommand.java
  infrastructure/adapter/in/kafka/dto/CompensateCancellationNotePdfCommand.java
  application/port/in/ProcessCancellationNotePdfUseCase.java
  application/port/in/CompensateCancellationNotePdfUseCase.java
  infrastructure/adapter/in/kafka/SagaCommandHandler.java
  application/dto/event/CancellationNotePdfGeneratedEvent.java

MOVING:
  (none — files are created new in target locations)

DELETING:
  application/service/SagaCommandHandler.java
  application/usecase/ProcessCancellationNotePdfUseCase.java
  application/usecase/CompensateCancellationNotePdfUseCase.java
  infrastructure/adapter/in/kafka/KafkaCommandMapper.java
  infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java
  infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java
  infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java
  src/test/java/.../infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java

MODIFYING:
  CancellationNotePdfDocumentService.java    (method signatures change from command objects to plain fields)
  SagaRouteConfig.java                        (use new DTO names in onPrepareFailure, remove KafkaCommandMapper)
  EventPublisher.java                         (import new package path for CancellationNotePdfGeneratedEvent)
```

---

## Before You Start

- Build the service to confirm it compiles: `mvn clean compile -q`
- Run tests to confirm baseline: `mvn clean test -q`
- Work inside the service directory: `/home/wpanther/projects/etax/invoice-microservices/services/cancellationnote-pdf-generation-service`

---

## Task 1: Create `dto/` directory and new `ProcessCancellationNotePdfCommand`

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto/ProcessCancellationNotePdfCommand.java`
- Source: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java`

- [ ] **Step 1: Create directory and new file**

```bash
mkdir -p src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto
```

Create `ProcessCancellationNotePdfCommand.java` — rename of `KafkaCancellationNoteProcessCommand` with package changed:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
public class ProcessCancellationNotePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("signedXmlUrl")
    private final String signedXmlUrl;

    @JsonCreator
    public ProcessCancellationNotePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.signedXmlUrl = signedXmlUrl;
    }

    public ProcessCancellationNotePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                             String documentId, String documentNumber,
                                             String signedXmlUrl) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
        this.documentNumber = Objects.requireNonNull(documentNumber, "documentNumber is required");
        this.signedXmlUrl = Objects.requireNonNull(signedXmlUrl, "signedXmlUrl is required");
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors related to the new file

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto/ProcessCancellationNotePdfCommand.java
git commit -m "refactor: rename KafkaCancellationNoteProcessCommand to ProcessCancellationNotePdfCommand in dto/ package"
```

---

## Task 2: Create `CompensateCancellationNotePdfCommand` in `dto/`

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto/CompensateCancellationNotePdfCommand.java`
- Source: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java`

- [ ] **Step 1: Write the new file**

```bash
cat > src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto/CompensateCancellationNotePdfCommand.java << 'EOF'
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
public class CompensateCancellationNotePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonCreator
    public CompensateCancellationNotePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
    }

    public CompensateCancellationNotePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                                String documentId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
    }
}
EOF
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto/CompensateCancellationNotePdfCommand.java
git commit -m "refactor: rename KafkaCancellationNoteCompensateCommand to CompensateCancellationNotePdfCommand in dto/ package"
```

---

## Task 3: Create `ProcessCancellationNotePdfUseCase` in `application/port/in/`

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/ProcessCancellationNotePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/ProcessCancellationNotePdfUseCase.java` (later in Task 9)

- [ ] **Step 1: Create directory and write the interface**

```bash
mkdir -p src/main/java/com/wpanther/cancellationnote/pdf/application/port/in
```

```bash
cat > src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/ProcessCancellationNotePdfUseCase.java << 'EOF'
package com.wpanther.cancellationnote.pdf.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for cancellation note PDF generation.
 * Called by SagaCommandHandler with plain fields — no command objects.
 */
public interface ProcessCancellationNotePdfUseCase {

    void handle(String documentId, String documentNumber, String signedXmlUrl,
                String sagaId, SagaStep sagaStep, String correlationId);
}
EOF
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/ProcessCancellationNotePdfUseCase.java
git commit -m "refactor: add ProcessCancellationNotePdfUseCase in application/port/in/ with plain parameter signatures"
```

---

## Task 4: Create `CompensateCancellationNotePdfUseCase` in `application/port/in/`

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/CompensateCancellationNotePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/CompensateCancellationNotePdfUseCase.java` (later in Task 9)

- [ ] **Step 1: Write the interface**

```bash
cat > src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/CompensateCancellationNotePdfUseCase.java << 'EOF'
package com.wpanther.cancellationnote.pdf.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for cancellation note PDF compensation.
 * Called by SagaCommandHandler with plain fields — no command objects.
 */
public interface CompensateCancellationNotePdfUseCase {

    void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
EOF
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/CompensateCancellationNotePdfUseCase.java
git commit -m "refactor: add CompensateCancellationNotePdfUseCase in application/port/in/ with plain parameter signatures"
```

---

## Task 5: Rewrite `SagaCommandHandler` in `infrastructure/adapter/in/kafka/`

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaCommandHandler.java` (new location)
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/application/service/SagaCommandHandler.java` (later in Task 9)
- Modify: `CancellationNotePdfDocumentService.java` (Task 7)

This is the most complex rewrite. The handler:
1. Accepts `ProcessCancellationNotePdfCommand` / `CompensateCancellationNotePdfCommand` from Kafka
2. Extracts all fields and calls use case interfaces with plain parameters
3. Calls `CancellationNotePdfDocumentService` with plain parameters (not command objects)
4. Error-handling methods use plain fields (sagaId, sagaStep, correlationId) — not command objects

- [ ] **Step 1: Write the new SagaCommandHandler**

```bash
cat > src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaCommandHandler.java << 'EOFCMDHANDLER'
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.cancellationnote.pdf.application.port.in.CompensateCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.application.port.in.ProcessCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.application.service.CancellationNotePdfDocumentService;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfStoragePort;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.cancellationnote.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.service.CancellationNotePdfGenerationService;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto.CompensateCancellationNotePdfCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto.ProcessCancellationNotePdfCommand;
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

/**
 * Saga command handler — driving adapter that receives Kafka messages and calls use cases.
 * No command objects flow into application/service layer — only plain field parameters.
 */
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
    public void handle(String documentId, String documentNumber, String signedXmlUrl,
                       String sagaId, SagaStep sagaStep, String correlationId) {
        MDC.put(MDC_SAGA_ID,         sagaId);
        MDC.put(MDC_CORRELATION_ID,  correlationId);
        MDC.put(MDC_DOCUMENT_NUMBER, documentNumber);
        MDC.put(MDC_DOCUMENT_ID,     documentId);
        try {
            log.info("Handling ProcessCancellationNotePdfCommand for saga {} document {}",
                    sagaId, documentNumber);
            try {
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "signedXmlUrl is null or blank");
                    return;
                }
                if (documentId == null || documentId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentId is null or blank");
                    return;
                }
                if (documentNumber == null || documentNumber.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentNumber is null or blank");
                    return;
                }

                Optional<CancellationNotePdfDocument> existing =
                        pdfDocumentService.findByCancellationNoteId(documentId);

                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), documentId, documentNumber, sagaId, sagaStep, correlationId);
                    return;
                }

                int previousRetryCount = existing.map(CancellationNotePdfDocument::getRetryCount).orElse(-1);

                if (existing.isPresent()) {
                    CancellationNotePdfDocument prior = existing.get();
                    if (!prior.isFailed()) {
                        log.warn("Found document in non-terminal state (status={}) for document {} saga {} — TX2 may have rolled back; will delete and retry",
                                prior.getStatus(), documentId, sagaId);
                    }
                    if (prior.isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(sagaId, sagaStep, correlationId, documentId, documentNumber);
                        return;
                    }
                }

                CancellationNotePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, documentId, documentNumber);
                } else {
                    document = pdfDocumentService.beginGeneration(documentId, documentNumber);
                }

                String s3Key = null;
                try {
                    String signedXml = signedXmlFetchPort.fetch(signedXmlUrl);
                    byte[] pdfBytes  = pdfGenerationService.generatePdf(documentNumber, signedXml);
                    s3Key = pdfStoragePort.store(documentNumber, pdfBytes);
                    String fileUrl   = pdfStoragePort.resolveUrl(s3Key);

                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length, previousRetryCount,
                            documentId, documentNumber, sagaId, sagaStep, correlationId);

                    log.debug("Successfully processed PDF generation for saga {} document {}", sagaId, documentNumber);

                } catch (CallNotPermittedException e) {
                    log.warn("Circuit breaker OPEN for saga {} document {}: {}",
                            sagaId, documentNumber, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "Circuit breaker open: " + e.getMessage(),
                            previousRetryCount, sagaId, sagaStep, correlationId);

                } catch (RestClientException e) {
                    log.warn("HTTP error fetching signed XML for saga {} document {}: {}",
                            sagaId, documentNumber, e.getMessage());
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
                            sagaId, documentNumber, e.getMessage(), e);
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount, sagaId, sagaStep, correlationId);
                }

            } catch (OptimisticLockingFailureException e) {
                log.warn("Concurrent modification for saga {}: {}", sagaId, e.getMessage());
                pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "Concurrent modification: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {}: {}", sagaId, e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId) {
        MDC.put(MDC_SAGA_ID,        sagaId);
        MDC.put(MDC_CORRELATION_ID,  correlationId);
        MDC.put(MDC_DOCUMENT_ID,     documentId);
        try {
            log.info("Handling compensation for saga {} document {}", sagaId, documentId);
            try {
                Optional<CancellationNotePdfDocument> existing =
                        pdfDocumentService.findByCancellationNoteId(documentId);

                if (existing.isPresent()) {
                    CancellationNotePdfDocument doc = existing.get();
                    pdfDocumentService.deleteById(doc.getId());
                    if (doc.getDocumentPath() != null) {
                        try { pdfStoragePort.delete(doc.getDocumentPath()); }
                        catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    sagaId, doc.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated CancellationNotePdfDocument {} for saga {}", doc.getId(), sagaId);
                } else {
                    log.info("No document for documentId {} — already compensated", documentId);
                }
                pdfDocumentService.publishCompensated(sagaId, sagaStep, correlationId);

            } catch (Exception e) {
                log.error("Failed to compensate for saga {}: {}", sagaId, e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(sagaId, sagaStep, correlationId, "Compensation failed: " + describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Message routed to DLQ after retry exhaustion: " + describeThrowable(cause));
            log.error("Published FAILURE reply after DLQ routing for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {}", sagaId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId,
                    "Compensation DLQ after retry exhaustion: " + describeThrowable(cause));
            log.error("Published FAILURE reply after compensation DLQ routing for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {}", sagaId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailureForUnparsedMessage(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Message routed to DLQ after deserialization failure: " + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after DLQ routing (deserialization failure) for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ deserialization failure for saga {} — orchestrator must timeout", sagaId, e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
EOFCMDHANDLER
```

- [ ] **Step 2: Compile and fix any errors**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Compile errors in `CancellationNotePdfDocumentService` (method signatures don't match yet) — this is expected

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaCommandHandler.java
git commit -m "refactor: move SagaCommandHandler to infrastructure/adapter/in/kafka/ with plain parameter calls"
```

---

## Task 6: Move `CancellationNotePdfGeneratedEvent` to `application/dto/event/`

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/dto/event/CancellationNotePdfGeneratedEvent.java`
- Modify: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/EventPublisher.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java` (original)

- [ ] **Step 1: Create directory and new file**

```bash
mkdir -p src/main/java/com/wpanther/cancellationnote/pdf/application/dto/event
```

```bash
cat > src/main/java/com/wpanther/cancellationnote/pdf/application/dto/event/CancellationNotePdfGeneratedEvent.java << 'EOF'
package com.wpanther.cancellationnote.pdf.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when cancellation note PDF generation is completed.
 * Consumed by notification-service.
 */
@Getter
public class CancellationNotePdfGeneratedEvent extends TraceEvent {

    private static final String EVENT_TYPE = "pdf.generated.cancellation-note";
    private static final String SOURCE = "cancellationnote-pdf-generation-service";
    private static final String TRACE_TYPE = "PDF_GENERATED";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("documentUrl")
    private final String documentUrl;

    @JsonProperty("fileSize")
    private final long fileSize;

    @JsonProperty("xmlEmbedded")
    private final boolean xmlEmbedded;

    public CancellationNotePdfGeneratedEvent(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentUrl,
            long fileSize,
            boolean xmlEmbedded,
            String correlationId) {
        super(sagaId, correlationId, SOURCE, TRACE_TYPE, null);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @JsonCreator
    public CancellationNotePdfGeneratedEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("source") String source,
            @JsonProperty("traceType") String traceType,
            @JsonProperty("context") String context,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("documentUrl") String documentUrl,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("xmlEmbedded") boolean xmlEmbedded) {
        super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }
}
EOF
```

- [ ] **Step 2: Update EventPublisher import**

Read `EventPublisher.java` and change the import from:
```java
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging.CancellationNotePdfGeneratedEvent;
```
to:
```java
import com.wpanther.cancellationnote.pdf.application.dto.event.CancellationNotePdfGeneratedEvent;
```

- [ ] **Step 3: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Errors in `CancellationNotePdfDocumentService` (will fix in Task 7)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/application/dto/event/CancellationNotePdfGeneratedEvent.java
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/EventPublisher.java
git commit -m "refactor: move CancellationNotePdfGeneratedEvent to application/dto/event/"
```

---

## Task 7: Update `CancellationNotePdfDocumentService` method signatures

**Files:**
- Modify: `src/main/java/com/wpanther/cancellationnote/pdf/application/service/CancellationNotePdfDocumentService.java`

This is the largest change — update all method signatures to accept plain fields instead of command objects.

- [ ] **Step 1: Rewrite CancellationNotePdfDocumentService.java with updated method signatures**

Full file rewrite. Key changes:
- Remove imports for `KafkaCancellationNoteProcessCommand` and `KafkaCancellationNoteCompensateCommand`
- Add import for `com.wpanther.saga.domain.enums.SagaStep`
- All methods that took command objects now take individual field parameters
- `buildGeneratedEvent` takes plain parameters instead of command object

```java
package com.wpanther.cancellationnote.pdf.application.service;

import com.wpanther.cancellationnote.pdf.application.dto.event.CancellationNotePdfGeneratedEvent;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfEventPort;
import com.wpanther.cancellationnote.pdf.application.port.out.PdfStoragePort;
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
```

Write the above content to `src/main/java/com/wpanther/cancellationnote/pdf/application/service/CancellationNotePdfDocumentService.java`

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Should compile now (or very few errors)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/application/service/CancellationNotePdfDocumentService.java
git commit -m "refactor: update CancellationNotePdfDocumentService method signatures to use plain fields instead of command objects"
```

---

## Task 8: Update `SagaRouteConfig` — use new DTO names, remove KafkaCommandMapper

**Files:**
- Modify: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java`

Key changes:
1. Remove `KafkaCommandMapper` field and constructor parameter
2. Change `onPrepareFailure` block to use `ProcessCancellationNotePdfCommand` / `CompensateCancellationNotePdfCommand` from `dto/` package
3. Call error-handling methods with plain extracted fields (not command objects)
4. Route processors call use case interfaces with plain parameters

- [ ] **Step 1: Rewrite SagaRouteConfig**

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.cancellationnote.pdf.application.port.in.CompensateCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.application.port.in.ProcessCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto.CompensateCancellationNotePdfCommand;
import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto.ProcessCancellationNotePdfCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private final ProcessCancellationNotePdfUseCase processUseCase;
    private final CompensateCancellationNotePdfUseCase compensateUseCase;
    private final SagaCommandHandler sagaCommandHandler;
    private final ObjectMapper objectMapper;

    public SagaRouteConfig(ProcessCancellationNotePdfUseCase processUseCase,
                           CompensateCancellationNotePdfUseCase compensateUseCase,
                           SagaCommandHandler sagaCommandHandler,
                           ObjectMapper objectMapper) {
        this.processUseCase = processUseCase;
        this.compensateUseCase = compensateUseCase;
        this.sagaCommandHandler = sagaCommandHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure() throws Exception {

        errorHandler(deadLetterChannel(
                        "kafka:{{app.kafka.topics.dlq}}?brokers={{app.kafka.bootstrap-servers}}")
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true)
                        .onPrepareFailure(exchange -> {
                            Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                            Object body = exchange.getIn().getBody();
                            if (body instanceof ProcessCancellationNotePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of retry exhaustion for saga {} document {}",
                                        cmd.getSagaId(), cmd.getDocumentNumber());
                                sagaCommandHandler.publishOrchestrationFailure(
                                        cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId(), cause);
                            } else if (body instanceof CompensateCancellationNotePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of compensation retry exhaustion for saga {} document {}",
                                        cmd.getSagaId(), cmd.getDocumentId());
                                sagaCommandHandler.publishCompensationOrchestrationFailure(
                                        cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId(), cause);
                            } else {
                                log.error("DLQ: body not deserialized ({}); attempting saga metadata recovery",
                                        body == null ? "null" : body.getClass().getSimpleName());
                                recoverAndNotifyOrchestrator(body, cause);
                            }
                        }));

        from("kafka:{{app.kafka.topics.saga-command-cancellation-note-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.command-group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error:true}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records:100}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count:3}}")
                .routeId("saga-command-consumer")
                .log(LoggingLevel.DEBUG, "Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                .unmarshal().json(JsonLibrary.Jackson, ProcessCancellationNotePdfCommand.class)
                .process(exchange -> {
                        ProcessCancellationNotePdfCommand cmd = exchange.getIn().getBody(ProcessCancellationNotePdfCommand.class);
                        log.info("Processing saga command for saga: {}, document: {}",
                                        cmd.getSagaId(), cmd.getDocumentNumber());
                        processUseCase.handle(
                                cmd.getDocumentId(),
                                cmd.getDocumentNumber(),
                                cmd.getSignedXmlUrl(),
                                cmd.getSagaId(),
                                cmd.getSagaStep(),
                                cmd.getCorrelationId());
                })
                .log("Successfully processed saga command");

        from("kafka:{{app.kafka.topics.saga-compensation-cancellation-note-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.compensation-group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error:true}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records:100}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count:3}}")
                .routeId("saga-compensation-consumer")
                .log(LoggingLevel.DEBUG, "Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                .unmarshal().json(JsonLibrary.Jackson, CompensateCancellationNotePdfCommand.class)
                .process(exchange -> {
                        CompensateCancellationNotePdfCommand cmd = exchange.getIn().getBody(CompensateCancellationNotePdfCommand.class);
                        log.info("Processing compensation for saga: {}, document: {}",
                                        cmd.getSagaId(), cmd.getDocumentId());
                        compensateUseCase.handle(
                                cmd.getDocumentId(),
                                cmd.getSagaId(),
                                cmd.getSagaStep(),
                                cmd.getCorrelationId());
                })
                .log("Successfully processed compensation command");
    }

    private void recoverAndNotifyOrchestrator(Object body, Throwable cause) {
        if (body == null) {
            log.error("DLQ: null message body — orchestrator must timeout");
            return;
        }
        try {
            byte[] rawBytes = body instanceof byte[] b
                    ? b
                    : body.toString().getBytes(StandardCharsets.UTF_8);
            JsonNode node        = objectMapper.readTree(rawBytes);
            String sagaId        = node.path("sagaId").asText(null);
            String sagaStepStr   = node.path("sagaStep").asText(null);
            String correlationId = node.path("correlationId").asText(null);

            if (sagaId == null || sagaStepStr == null) {
                log.error("DLQ: saga metadata missing in raw message — orchestrator must timeout");
                return;
            }
            SagaStep sagaStep = objectMapper.readValue(
                    "\"" + sagaStepStr + "\"", SagaStep.class);
            sagaCommandHandler.publishOrchestrationFailureForUnparsedMessage(
                    sagaId, sagaStep, correlationId, cause);
        } catch (Exception parseEx) {
            log.error("DLQ: cannot parse raw message for saga metadata — orchestrator must timeout", parseEx);
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Clean compile

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java
git commit -m "refactor: update SagaRouteConfig to use new DTO package and remove KafkaCommandMapper"
```

---

## Task 9: Delete old files from `application/service/`, `application/usecase/`, `infrastructure/adapter/in/kafka/`, `infrastructure/adapter/out/messaging/`, and test directory

**Files:**
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/application/service/SagaCommandHandler.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/ProcessCancellationNotePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/CompensateCancellationNotePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java`
- Delete: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java`
- Delete: `src/test/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java`

- [ ] **Step 1: Delete all old files**

```bash
rm src/main/java/com/wpanther/cancellationnote/pdf/application/service/SagaCommandHandler.java
rm src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/ProcessCancellationNotePdfUseCase.java
rm src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/CompensateCancellationNotePdfUseCase.java
rm src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java
rm src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java
rm src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java
rm src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java
rm src/test/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Clean compile

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: delete old saga command classes, KafkaCommandMapper, and stale test"
```

---

## Task 10: Build and test

**Files:**
- All modified files

- [ ] **Step 1: Full clean compile**

Run: `mvn clean compile 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run tests**

Run: `mvn clean test 2>&1 | tail -30`
Expected: BUILD SUCCESS (all tests pass)

- [ ] **Step 3: Commit all remaining changes**

```bash
git add -A
git commit -m "refactor: complete layer separation — saga types in infrastructure, plain-parameter use cases in application/port/in/"
```

---

## Verification Checklist

After all tasks:

```bash
# 1. Compile check
mvn clean compile -q

# 2. All tests pass
mvn clean test -q

# 3. Confirm new structure
ls src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/dto/
# Expected: CompensateCancellationNotePdfCommand.java, ProcessCancellationNotePdfCommand.java

ls src/main/java/com/wpanther/cancellationnote/pdf/application/port/in/
# Expected: CompensateCancellationNotePdfUseCase.java, ProcessCancellationNotePdfUseCase.java

ls src/main/java/com/wpanther/cancellationnote/pdf/application/dto/event/
# Expected: CancellationNotePdfGeneratedEvent.java

ls src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/
# Expected: SagaCommandHandler.java, SagaRouteConfig.java (NO KafkaCommandMapper, NO KafkaCancellationNoteProcessCommand, NO KafkaCancellationNoteCompensateCommand)

# 4. Confirm KafkaCommandMapper is gone
ls src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java 2>/dev/null && echo "ERROR: KafkaCommandMapper still exists" || echo "OK: KafkaCommandMapper deleted"

# 5. Confirm usecase directory is gone
ls src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/ 2>/dev/null && echo "ERROR: usecase directory still exists" || echo "OK: usecase directory deleted"
```

---

## Self-Review Checklist

- [ ] `ProcessCancellationNotePdfCommand` and `CompensateCancellationNotePdfCommand` are in `infrastructure/adapter/in/kafka/dto/`
- [ ] Use case interfaces in `application/port/in/` have plain parameter signatures (no command objects)
- [ ] `SagaCommandHandler` is in `infrastructure/adapter/in/kafka/` and calls use cases with extracted fields
- [ ] `CancellationNotePdfGeneratedEvent` is in `application/dto/event/`
- [ ] `CancellationNotePdfReplyEvent` remains as top-level class (unchanged)
- [ ] `KafkaCommandMapper` is deleted
- [ ] `KafkaCancellationNoteProcessCommand` and `KafkaCancellationNoteCompensateCommand` are deleted
- [ ] `application/usecase/` directory is deleted
- [ ] `KafkaCommandMapperTest` is deleted
- [ ] All tests pass with `mvn clean test`
- [ ] Compilation succeeds with `mvn clean compile`