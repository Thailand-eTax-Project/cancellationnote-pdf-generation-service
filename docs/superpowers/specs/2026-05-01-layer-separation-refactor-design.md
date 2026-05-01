# cancellationnote-pdf-generation-service Layer Separation Refactoring

**Date:** 2026-05-01
**Author:** Claude Code
**Status:** Draft

## Context

`cancellationnote-pdf-generation-service` has saga infrastructure types (`SagaCommand`, `SagaReply`, `TraceEvent`) and Jackson serialization annotations leaking into the application layer. The use case interfaces accept Kafka command objects as parameters, and `CancellationNotePdfDocumentService` methods accept `KafkaCancellationNoteProcessCommand` / `KafkaCancellationNoteCompensateCommand` directly. This violates Hexagonal/Port-Adapters architecture — domain/application layers should have no framework or infrastructure dependencies.

The reference implementation is `invoice-pdf-generation-service`, which correctly separates:
- Kafka DTOs with saga inheritance and Jackson annotations → `infrastructure/adapter/in/kafka/dto/`
- Use case interfaces with plain parameters → `application/port/in/`
- Pure domain events (no framework annotations) → `application/dto/event/` (for trace events) or `domain/event/` (for truly domain events)

## Problem Statement

The service leaks infrastructure concerns into application/domain layers:

| File | Leaks |
|------|-------|
| `ProcessCancellationNotePdfUseCase.handle(KafkaCancellationNoteProcessCommand)` | Use case interface accepts Kafka DTO |
| `CompensateCancellationNotePdfUseCase.handle(KafkaCancellationNoteCompensateCommand)` | Use case interface accepts Kafka DTO |
| `CancellationNotePdfDocumentService.completeGenerationAndPublish(..., KafkaCancellationNoteProcessCommand)` | Application service accepts Kafka DTO |
| `CancellationNotePdfDocumentService.failGenerationAndPublish(..., KafkaCancellationNoteProcessCommand)` | Application service accepts Kafka DTO |
| `CancellationNotePdfDocumentService.publishIdempotentSuccess(..., KafkaCancellationNoteProcessCommand)` | Application service accepts Kafka DTO |
| `CancellationNotePdfDocumentService.publishRetryExhausted(KafkaCancellationNoteProcessCommand)` | Application service accepts Kafka DTO |
| `CancellationNotePdfDocumentService.publishGenerationFailure(KafkaCancellationNoteProcessCommand, String)` | Application service accepts Kafka DTO |
| `CancellationNotePdfDocumentService.publishCompensated(KafkaCancellationNoteCompensateCommand)` | Application service accepts Kafka DTO |
| `CancellationNotePdfDocumentService.publishCompensationFailure(KafkaCancellationNoteCompensateCommand, String)` | Application service accepts Kafka DTO |
| `CancellationNotePdfGeneratedEvent extends TraceEvent` | Extends saga library TraceEvent (framework type) |
| `CancellationNotePdfReplyEvent extends SagaReply` | Extends saga library SagaReply (framework type) |
| `KafkaCommandMapper` | No-op mapper that just returns its input — unnecessary indirection |

## Design

### Target Architecture

```
infrastructure/adapter/in/kafka/dto/
├── ProcessCancellationNotePdfCommand   ← (rename from KafkaCancellationNoteProcessCommand)
└── CompensateCancellationNotePdfCommand ← (rename from KafkaCancellationNoteCompensateCommand)

infrastructure/adapter/in/kafka/
├── SagaCommandHandler                  ← (moved from application/service/)
└── SagaRouteConfig                     ← (updated to use new DTOs)

application/port/in/
├── ProcessCancellationNotePdfUseCase  ← (refactored from usecase/, plain parameters)
└── CompensateCancellationNotePdfUseCase ← (refactored from usecase/, plain parameters)

application/service/
└── CancellationNotePdfDocumentService ← (unchanged responsibilities, updated method signatures)

infrastructure/adapter/out/messaging/
├── SagaReplyPublisher                  ← (unchanged, inline CancellationNotePdfReplyEvent factory)
└── EventPublisher                      ← (updated import for moved CancellationNotePdfGeneratedEvent)

application/dto/event/
└── CancellationNotePdfGeneratedEvent  ← (moved from infrastructure/adapter/out/messaging/, extends TraceEvent)
```

### Changes

#### 1. `infrastructure/adapter/in/kafka/dto/`

**Rename and consolidate:**
- `KafkaCancellationNoteProcessCommand` → `ProcessCancellationNotePdfCommand`
- `KafkaCancellationNoteCompensateCommand` → `CompensateCancellationNotePdfCommand`

Package: `com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.dto`

#### 2. `application/port/in/`

**Refactor use case interfaces** (rename from `application/usecase/`):

```java
public interface ProcessCancellationNotePdfUseCase {
    void handle(String documentId, String documentNumber, String signedXmlUrl,
                String sagaId, SagaStep sagaStep, String correlationId);
}

public interface CompensateCancellationNotePdfUseCase {
    void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
```

Package: `com.wpanther.cancellationnote.pdf.application.port.in`

#### 3. `infrastructure/adapter/in/kafka/SagaCommandHandler`

**Move from** `application/service/SagaCommandHandler.java`
**To** `infrastructure/adapter/in/kafka/SagaCommandHandler.java`

Handles Kafka DTO → use case call translation. Calls `ProcessCancellationNotePdfUseCase.handle(...)` and `CompensateCancellationNotePdfUseCase.handle(...)` with extracted parameters — no command objects passed into application/service layer.

#### 4. `application/service/CancellationNotePdfDocumentService`

**Modify method signatures.** Methods that currently accept command objects are updated to accept individual fields:

| Method | New Signature |
|--------|---------------|
| `completeGenerationAndPublish` | `(UUID documentId, String s3Key, String fileUrl, long fileSize, int previousRetryCount, String cmdDocumentId, String cmdDocumentNumber, String sagaId, SagaStep sagaStep, String correlationId)` |
| `failGenerationAndPublish` | `(UUID documentId, String errorMessage, int previousRetryCount, String sagaId, SagaStep sagaStep, String correlationId)` |
| `publishIdempotentSuccess` | `(CancellationNotePdfDocument doc, String documentId, String documentNumber, String sagaId, SagaStep sagaStep, String correlationId)` |
| `publishRetryExhausted` | `(String sagaId, SagaStep sagaStep, String correlationId, String documentId, String documentNumber)` |
| `publishGenerationFailure` | `(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage)` |
| `publishCompensated` | `(String sagaId, SagaStep sagaStep, String correlationId)` |
| `publishCompensationFailure` | `(String sagaId, SagaStep sagaStep, String correlationId, String error)` |

#### 5. `application/dto/event/CancellationNotePdfGeneratedEvent`

**Move from** `infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent`
**To** `application/dto/event/CancellationNotePdfGeneratedEvent`

This class extends `TraceEvent` from the saga library — it's a notification DTO, not a pure domain event. Its proper home is `application/dto/event/`.

Package: `com.wpanther.cancellationnote.pdf.application.dto.event`

#### 6. `infrastructure/adapter/out/messaging/SagaReplyPublisher`

**Unchanged** — `CancellationNotePdfReplyEvent` remains a separate top-level class at `infrastructure/adapter/out/messaging/CancellationNotePdfReplyEvent.java`, matching the invoice-pdf-generation-service reference implementation. The class is not inlined.

#### 7. `application/usecase/` — DELETE

After moving interfaces to `application/port/in/`, delete:
- `application/usecase/ProcessCancellationNotePdfUseCase.java`
- `application/usecase/CompensateCancellationNotePdfUseCase.java`

#### 8. `application/service/SagaCommandHandler` — DELETE

After moving to `infrastructure/adapter/in/kafka/SagaCommandHandler`, delete original location.

#### 9. `infrastructure/adapter/in/kafka/KafkaCommandMapper` — DELETE

No-op mapper that just returns its input. Unnecessary indirection — route processors access DTO fields directly.

#### 10. `KafkaCancellationNoteProcessCommand` and `KafkaCancellationNoteCompensateCommand` — DELETE

Replaced by `ProcessCancellationNotePdfCommand` and `CompensateCancellationNotePdfCommand` in `dto/` package.

## Files to Modify

| Action | File |
|--------|------|
| Rename+move | `infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java` → `dto/ProcessCancellationNotePdfCommand.java` |
| Rename+move | `infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java` → `dto/CompensateCancellationNotePdfCommand.java` |
| Rename+move | `application/usecase/ProcessCancellationNotePdfUseCase.java` → `port/in/ProcessCancellationNotePdfUseCase.java` (signature changes) |
| Rename+move | `application/usecase/CompensateCancellationNotePdfUseCase.java` → `port/in/CompensateCancellationNotePdfUseCase.java` (signature changes) |
| Move | `application/service/SagaCommandHandler.java` → `infrastructure/adapter/in/kafka/SagaCommandHandler.java` |
| Move | `infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java` → `application/dto/event/CancellationNotePdfGeneratedEvent.java` |
| Delete | `infrastructure/adapter/in/kafka/KafkaCommandMapper.java` |
| Delete | `infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java` (original) |
| Delete | `infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java` (original) |
| Delete | `infrastructure/adapter/out/messaging/CancellationNotePdfReplyEvent.java` |
| Delete | `application/usecase/ProcessCancellationNotePdfUseCase.java` (original) |
| Delete | `application/usecase/CompensateCancellationNotePdfUseCase.java` (original) |
| Delete | `application/service/SagaCommandHandler.java` (original) |
| Modify | `CancellationNotePdfDocumentService.java` (update method signatures) |
| Modify | `SagaRouteConfig.java` (use new DTO names in `onPrepareFailure` block, remove KafkaCommandMapper) |
| Modify | `EventPublisher.java` (import new package path) |

## Testing Strategy

1. **Unit tests** — Update test classes that reference moved/modified classes:
   - `SagaCommandHandlerTest` → update import for moved handler
   - `CancellationNotePdfDocumentServiceTest` → update command parameter types
   - `CancellationNotePdfGeneratedEventTest` → update package import

2. **Kafka consumer tests** — verify `ProcessCancellationNotePdfCommand` / `CompensateCancellationNotePdfCommand` deserialization still works

3. **Integration test** — Run full service against test infrastructure to verify saga orchestration still functions

4. **Delete stale test** — `KafkaCommandMapperTest.java` (test for deleted class)

## Verification

After refactoring, run:
```bash
mvn clean compile   # Verify no compilation errors
mvn clean test      # Verify all tests pass
```

## Scope

This refactor addresses **only** `cancellationnote-pdf-generation-service`. Other services with similar patterns should be audited separately.