# Cancellation Note PDF Generation Service — Design Spec

**Date:** 2026-04-30
**Status:** Approved
**Scope:** `cancellationnote-pdf-generation-service` (new) + `GENERATE_CANCELLATION_NOTE_PDF` SagaStep in `saga-commons`

---

## 1. Overview

A new Spring Boot microservice that generates PDF/A-3 documents for Thai e-Tax Cancellation Note (`CancellationNote_CrossIndustryInvoice`) documents. It participates in the Saga Orchestration pipeline, consuming commands from the orchestrator and replying via the transactional outbox pattern.

The service is a full hexagonal port of `taxinvoice-pdf-generation-service` (port 8089) with all production features. Only cancellation-note-specific identifiers, namespaces, and the XSL-FO template differ.

---

## 2. Saga Commons Change

**File:** `saga-commons/src/main/java/com/wpanther/saga/domain/enums/SagaStep.java`

Add one new enum value after `GENERATE_RECEIPT_PDF`:

```java
/**
 * Cancellation Note PDF generation via cancellationnote-pdf-generation-service.
 */
GENERATE_CANCELLATION_NOTE_PDF("generate-cancellation-note-pdf", "Cancellation Note PDF Generation Service"),
```

---

## 3. Service Identity

| Property | Value |
|----------|-------|
| Port | `8096` |
| Artifact ID | `cancellationnote-pdf-generation-service` |
| Package root | `com.wpanther.cancellationnote.pdf` |
| Main class | `CancellationNotePdfGenerationServiceApplication` |
| Database | `cancellationnotepdf_db` (PostgreSQL) |
| MinIO bucket | `cancellationnotes` (env: `MINIO_BUCKET_NAME`) |
| Camel app name | `cancellationnote-pdf-generation-camel` |

---

## 4. Architecture — Hexagonal (Port/Adapter)

```
com.wpanther.cancellationnote.pdf/
├── domain/
│   ├── model/
│   │   ├── CancellationNotePdfDocument.java   # Aggregate root
│   │   └── GenerationStatus.java              # PENDING → GENERATING → COMPLETED/FAILED
│   ├── repository/
│   │   └── CancellationNotePdfDocumentRepository.java
│   ├── service/
│   │   └── CancellationNotePdfGenerationService.java  # Port interface
│   ├── exception/
│   │   └── CancellationNotePdfGenerationException.java
│   └── constants/
│       └── PdfGenerationConstants.java
│
├── application/
│   ├── service/
│   │   ├── SagaCommandHandler.java
│   │   └── CancellationNotePdfDocumentService.java
│   ├── usecase/
│   │   ├── ProcessCancellationNotePdfUseCase.java
│   │   └── CompensateCancellationNotePdfUseCase.java
│   └── port/out/
│       ├── PdfEventPort.java
│       ├── PdfStoragePort.java
│       ├── SagaReplyPort.java
│       └── SignedXmlFetchPort.java
│
└── infrastructure/
    ├── adapter/in/kafka/
    │   ├── KafkaCancellationNoteProcessCommand.java    # extends SagaCommand
    │   ├── KafkaCancellationNoteCompensateCommand.java # extends SagaCommand
    │   ├── KafkaCommandMapper.java
    │   └── SagaRouteConfig.java                        # Apache Camel routes
    ├── adapter/out/
    │   ├── client/
    │   │   └── RestTemplateSignedXmlFetcher.java       # Circuit breaker: signedXmlFetch
    │   ├── messaging/
    │   │   ├── EventPublisher.java
    │   │   ├── SagaReplyPublisher.java
    │   │   ├── CancellationNotePdfGeneratedEvent.java
    │   │   ├── CancellationNotePdfReplyEvent.java      # SUCCESS: pdfUrl + pdfSize
    │   │   └── OutboxConstants.java
    │   ├── pdf/
    │   │   ├── FopCancellationNotePdfGenerator.java    # XPath + FOP render (Semaphore)
    │   │   ├── PdfA3Converter.java                     # PDFBox: PDF → PDF/A-3 + XML embed
    │   │   ├── ThaiAmountWordsConverter.java            # Baht → Thai words
    │   │   └── CancellationNotePdfGenerationServiceImpl.java
    │   ├── persistence/
    │   │   ├── CancellationNotePdfDocumentEntity.java
    │   │   ├── JpaCancellationNotePdfDocumentRepository.java
    │   │   ├── CancellationNotePdfDocumentRepositoryAdapter.java
    │   │   └── outbox/
    │   │       ├── OutboxEventEntity.java
    │   │       ├── SpringDataOutboxRepository.java
    │   │       └── JpaOutboxEventRepository.java
    │   └── storage/
    │       ├── MinioStorageAdapter.java                # Circuit breaker: minio
    │       └── MinioCleanupService.java                # Scheduled orphan cleanup
    ├── config/
    │   ├── MinioConfig.java
    │   ├── OutboxConfig.java
    │   ├── RestTemplateConfig.java
    │   └── FontHealthCheck.java
    └── metrics/
        └── PdfGenerationMetrics.java
```

---

## 5. Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `saga.command.cancellation-note-pdf` | Consume | Process command from Orchestrator |
| `saga.compensation.cancellation-note-pdf` | Consume | Compensation command from Orchestrator |
| `pdf.generated.cancellation-note` | Produce (outbox) | Notification Service (`documentType=CANCELLATION_NOTE`) |
| `saga.reply.cancellation-note-pdf` | Produce (outbox) | Reply to Orchestrator |
| `pdf.generation.cancellation-note.dlq` | Produce | Dead letter queue |

Consumer group IDs:
- Command: `cancellationnote-pdf-generation-command`
- Compensation: `cancellationnote-pdf-generation-compensation`

---

## 6. Saga Command/Reply Schema

### Input: `KafkaCancellationNoteProcessCommand` (extends `SagaCommand`)

```json
{
  "eventId": "uuid",
  "occurredAt": "...",
  "eventType": "...",
  "version": 1,
  "sagaId": "uuid",
  "sagaStep": "generate-cancellation-note-pdf",
  "correlationId": "uuid",
  "documentId": "uuid",
  "documentNumber": "CNL2024010001",
  "signedXmlUrl": "http://document-storage/documents/..."
}
```

### Output: `CancellationNotePdfReplyEvent` (extends `SagaReply`)

```json
{
  "sagaId": "uuid",
  "sagaStep": "generate-cancellation-note-pdf",
  "correlationId": "uuid",
  "status": "SUCCESS|FAILURE|COMPENSATED",
  "errorMessage": null,
  "pdfUrl": "http://localhost:9000/cancellationnotes/2024/01/15/cancellationnote-CNL2024010001-abc.pdf",
  "pdfSize": 12345
}
```

`pdfUrl` and `pdfSize` present only in SUCCESS replies. Orchestrator stores them in `DocumentMetadata` for the subsequent `PDF_STORAGE` step.

### Output: `CancellationNotePdfGeneratedEvent` (outbox → `pdf.generated.cancellation-note`)

```json
{
  "eventId": "uuid",
  "eventType": "pdf.generated.cancellation-note",
  "version": 1,
  "documentId": "uuid",
  "cancellationNoteId": "uuid",
  "cancellationNoteNumber": "CNL2024010001",
  "documentUrl": "http://...",
  "fileSize": 12345,
  "xmlEmbedded": true,
  "correlationId": "uuid"
}
```

---

## 7. Domain Model — `CancellationNotePdfDocument`

Aggregate root with the same state machine as `TaxInvoicePdfDocument`:

| State transition | Method | Guard |
|-----------------|--------|-------|
| `PENDING → GENERATING` | `startGeneration()` | Must be PENDING |
| `GENERATING → COMPLETED` | `markCompleted(path, url, size)` | Must be GENERATING |
| `any → FAILED` | `markFailed(message)` | — |

Key fields: `cancellationNoteId` (unique constraint / idempotency key), `cancellationNoteNumber`, `documentPath` (S3 key), `documentUrl` (full MinIO URL), `retryCount` (max 3).

MinIO S3 key pattern: `YYYY/MM/DD/cancellationnote-{cancellationNoteNumber}-{uuid}.pdf`

---

## 8. PDF Generation Pipeline

```
KafkaCancellationNoteProcessCommand
        ↓
SagaCommandHandler
        ├── Idempotency check (COMPLETED? re-publish and return SUCCESS)
        ├── Retry limit check (retryCount >= maxRetries? send FAILURE)
        └── CancellationNotePdfDocumentService.generatePdf()
                ├── Create domain aggregate (PENDING → GENERATING)
                ├── CancellationNotePdfGenerationServiceImpl.generatePdf()
                │   ├── RestTemplateSignedXmlFetcher.fetch(signedXmlUrl) → signedXml
                │   ├── XPath on signedXml (rsm:/ram: cancellation note namespaces)
                │   │     → extract GrandTotalAmount
                │   ├── ThaiAmountWordsConverter.toWords(grandTotal) → amountInWords
                │   ├── FopCancellationNotePdfGenerator (amountInWords as XSLT param) → base PDF
                │   └── PdfA3Converter → PDF/A-3b with embedded XML
                ├── MinioStorageAdapter.store(pdf, key) → pdfUrl
                └── markCompleted() → COMPLETED
        ├── EventPublisher → outbox_events (pdf.generated.cancellation-note)
        └── SagaReplyPublisher → outbox_events (saga.reply.cancellation-note-pdf)
```

### Compensation Flow

```
KafkaCancellationNoteCompensateCommand
        ↓
SagaCommandHandler.handleCompensation()
        ├── MinioStorageAdapter.delete(documentPath)
        ├── Delete database record
        └── SagaReplyPublisher → COMPENSATED reply (idempotent if no record)
```

---

## 9. XSL-FO Template (`cancellationnote-direct.xsl`)

Adapted from `taxinvoice-direct.xsl`. The only structural changes are:

| Element | TaxInvoice | CancellationNote |
|---------|-----------|-----------------|
| `rsm` namespace URI | `...TaxInvoice_CrossIndustryInvoice:2` | `...CancellationNote_CrossIndustryInvoice:2` |
| `ram` namespace URI | `...TaxInvoice_ReusableAggregateBusinessInformationEntity:2` | `...CancellationNote_ReusableAggregateBusinessInformationEntity:2` |
| Root match | `/rsm:TaxInvoice_CrossIndustryInvoice` | `/rsm:CancellationNote_CrossIndustryInvoice` |
| Document title (Thai) | `ใบเสร็จรับเงิน/ใบกำกับภาษี` | `ใบแจ้งยกเลิก / CANCELLATION NOTE` |
| Document type code | T01 | T07 |

All XPath paths for `GrandTotalAmount`, seller/buyer parties, line items, and monetary summation are structurally identical — only namespace URIs differ. `amountInWords` XSLT parameter injection is unchanged.

Resources copied as-is from taxinvoice: `fop.xconf`, `sRGB.icc`, Thai font files (`THSarabunNew`, `NotoSansThaiLooped`).

---

## 10. Database — `cancellationnotepdf_db`

Single Flyway migration: `V1__create_cancellation_note_pdf_tables.sql`

Creates in one script:
- `cancellation_note_pdf_documents` table with `cancellation_note_id` unique constraint and `retry_count` column
- `outbox_events` table with compound index on `(status, created_at)`

```sql
CREATE TABLE cancellation_note_pdf_documents (
    id UUID PRIMARY KEY,
    cancellation_note_id VARCHAR(100) NOT NULL UNIQUE,
    cancellation_note_number VARCHAR(50) NOT NULL,
    document_path VARCHAR(500),
    document_url VARCHAR(1000),
    file_size BIGINT,
    mime_type VARCHAR(100) DEFAULT 'application/pdf',
    xml_embedded BOOLEAN DEFAULT false,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100),
    aggregate_id VARCHAR(100),
    event_type VARCHAR(100),
    payload TEXT,
    topic VARCHAR(255),
    status VARCHAR(20) DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_outbox_pending_created ON outbox_events(status, created_at)
    WHERE status = 'PENDING';
```

---

## 11. Configuration (`application.yml`)

All env vars mirror taxinvoice defaults. CancellationNote-specific values:

| Variable | Default |
|----------|---------|
| `server.port` | `8096` |
| `DB_NAME` | `cancellationnotepdf_db` |
| `MINIO_BUCKET_NAME` | `cancellationnotes` |
| `KAFKA_COMMAND_GROUP_ID` | `cancellationnote-pdf-generation-command` |
| `KAFKA_COMPENSATION_GROUP_ID` | `cancellationnote-pdf-generation-compensation` |

All other variables (`MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `KAFKA_BROKERS`, `PDF_GENERATION_MAX_RETRIES`, `PDF_MAX_CONCURRENT_RENDERS`, `PDF_MAX_SIZE_BYTES`, `REST_CLIENT_CONNECT_TIMEOUT`, `REST_CLIENT_READ_TIMEOUT`, `FONT_HEALTH_CHECK_ENABLED`, etc.) use the same defaults as taxinvoice.

---

## 12. Testing Strategy

90% JaCoCo line coverage requirement. H2 in-memory DB, Flyway disabled in `application-test.yml`. Simplified `fop.xconf` for tests (no PDF/A mode, auto-detect fonts — no Thai font files required).

| Test class | What it covers |
|------------|---------------|
| `SagaCommandHandlerTest` | success, idempotency, max retries, generation failure, compensation success, idempotent compensation, compensation failure |
| `CamelRouteConfigTest` | JSON serialization/deserialization of all event types |
| `FopCancellationNotePdfGeneratorTest` | constructor validation, semaphore, valid/malformed XML, size limit, thread interruption, URI resolution, font availability |
| `PdfA3ConverterTest` | constructor, null/empty PDF, exception constructors |
| `MinioStorageAdapterTest` | upload, delete, URL resolution, Thai chars, filename sanitization |
| `CancellationNotePdfDocumentTest` | state machine transitions, invariants, retry counting |
| `CancellationNotePdfDocumentServiceTest` | transactional service methods |
| `EventPublisherTest` | outbox event publishing |
| `SagaReplyPublisherTest` | outbox reply publishing |
| `RestTemplateSignedXmlFetcherTest` | REST client with circuit breaker |
| `KafkaCommandMapperTest` | command mapping |
| `MinioCleanupServiceTest` | cleanup scheduling |
| `FontHealthCheckTest` | font validation at startup |

No embedded Kafka integration tests. No REST API — Spring Actuator only (`/actuator/health`, `/actuator/metrics`, `/actuator/camelroutes`, `/actuator/prometheus`).

---

## 13. Out of Scope

- Orchestrator changes (routing `GENERATE_CANCELLATION_NOTE_PDF` commands) — separate task
- `cancellationnote-processing-service` changes
- Integration tests with embedded Kafka
