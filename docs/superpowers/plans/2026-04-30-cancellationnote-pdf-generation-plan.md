# Cancellation Note PDF Generation Service — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a new Spring Boot microservice that generates PDF/A-3 documents for Thai e-Tax Cancellation Note documents, participating in the Saga Orchestration pipeline with MinIO storage.

**Architecture:** Hexagonal (port/adapter) pattern mirroring receipt-pdf-generation-service. Domain layer has no framework dependencies; infrastructure adapters implement port interfaces. Apache Camel handles Kafka routing. Transactional outbox pattern for exactly-once event delivery.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Spring Cloud 2023.0.1, Apache Camel 4.14.4, Apache FOP 2.9, Apache PDFBox 3.0.1, PostgreSQL, MinIO (S3), Resilience4j, saga-commons.

---

## File Structure

```
cancellationnote-pdf-generation-service/
├── pom.xml
├── src/main/java/com/wpanther/cancellationnote/pdf/
│   ├── CancellationNotePdfGenerationServiceApplication.java
│   ├── domain/
│   │   ├── model/
│   │   │   ├── CancellationNotePdfDocument.java
│   │   │   └── GenerationStatus.java
│   │   ├── repository/
│   │   │   └── CancellationNotePdfDocumentRepository.java
│   │   ├── service/
│   │   │   └── CancellationNotePdfGenerationService.java
│   │   ├── exception/
│   │   │   └── CancellationNotePdfGenerationException.java
│   │   └── constants/
│   │       └── PdfGenerationConstants.java
│   ├── application/
│   │   ├── service/
│   │   │   ├── SagaCommandHandler.java
│   │   │   └── CancellationNotePdfDocumentService.java
│   │   ├── usecase/
│   │   │   ├── ProcessCancellationNotePdfUseCase.java
│   │   │   └── CompensateCancellationNotePdfUseCase.java
│   │   └── port/out/
│   │       ├── PdfEventPort.java
│   │       ├── PdfStoragePort.java
│   │       ├── SagaReplyPort.java
│   │       └── SignedXmlFetchPort.java
│   └── infrastructure/
│       ├── adapter/in/kafka/
│       │   ├── KafkaCancellationNoteProcessCommand.java
│       │   ├── KafkaCancellationNoteCompensateCommand.java
│       │   ├── KafkaCommandMapper.java
│       │   └── SagaRouteConfig.java
│       ├── adapter/out/
│       │   ├── client/
│       │   │   └── RestTemplateSignedXmlFetcher.java
│       │   ├── messaging/
│       │   │   ├── EventPublisher.java
│       │   │   ├── SagaReplyPublisher.java
│       │   │   ├── CancellationNotePdfGeneratedEvent.java
│       │   │   ├── CancellationNotePdfReplyEvent.java
│       │   │   └── OutboxConstants.java
│       │   ├── pdf/
│       │   │   ├── FopCancellationNotePdfGenerator.java
│       │   │   ├── PdfA3Converter.java
│       │   │   ├── ThaiAmountWordsConverter.java
│       │   │   └── CancellationNotePdfGenerationServiceImpl.java
│       │   ├── persistence/
│       │   │   ├── CancellationNotePdfDocumentEntity.java
│       │   │   ├── JpaCancellationNotePdfDocumentRepository.java
│       │   │   ├── CancellationNotePdfDocumentRepositoryAdapter.java
│       │   │   └── outbox/
│       │   │       ├── OutboxEventEntity.java
│       │   │       ├── SpringDataOutboxRepository.java
│       │   │       └── JpaOutboxEventRepository.java
│       │   └── storage/
│       │       ├── MinioStorageAdapter.java
│       │       └── MinioCleanupService.java
│       ├── config/
│       │   ├── MinioConfig.java
│       │   ├── OutboxConfig.java
│       │   ├── RestTemplateConfig.java
│       │   └── FontHealthCheck.java
│       └── metrics/
│           └── PdfGenerationMetrics.java
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/V1__create_cancellation_note_pdf_tables.sql
│   ├── fonts/                        # Copied from taxinvoice
│   ├── fop/fop.xconf                 # Copied from taxinvoice
│   ├── icc/sRGB.icc                  # Copied from taxinvoice
│   └── xsl/cancellationnote-direct.xsl
└── src/test/
    ├── java/com/wpanther/cancellationnote/pdf/
    │   ├── domain/model/CancellationNotePdfDocumentTest.java
    │   ├── application/service/SagaCommandHandlerTest.java
    │   ├── application/service/CancellationNotePdfDocumentServiceTest.java
    │   ├── infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java
    │   ├── infrastructure/adapter/in/kafka/CamelRouteConfigTest.java
    │   ├── infrastructure/adapter/out/client/RestTemplateSignedXmlFetcherTest.java
    │   ├── infrastructure/adapter/out/messaging/EventPublisherTest.java
    │   ├── infrastructure/adapter/out/messaging/SagaReplyPublisherTest.java
    │   ├── infrastructure/adapter/out/pdf/FopCancellationNotePdfGeneratorTest.java
    │   ├── infrastructure/adapter/out/pdf/PdfA3ConverterTest.java
    │   ├── infrastructure/adapter/out/persistence/CancellationNotePdfDocumentRepositoryAdapterTest.java
    │   ├── infrastructure/adapter/out/storage/MinioStorageAdapterTest.java
    │   ├── infrastructure/adapter/out/storage/MinioCleanupServiceTest.java
    │   ├── infrastructure/config/FontHealthCheckTest.java
    │   └── infrastructure/metrics/PdfGenerationMetricsTest.java
    └── resources/
        ├── application-test.yml
        ├── fop/fop.xconf
        └── xml/CancellationNote_2p1_sample.xml
```

---

## Task 1: Add GENERATE_CANCELLATION_NOTE_PDF to SagaStep enum

**Files:**
- Modify: `/home/wpanther/projects/etax/saga-commons/src/main/java/com/wpanther/saga/domain/enums/SagaStep.java:73-78`

Add the new enum value after `GENERATE_RECEIPT_PDF` and before `GENERATE_ABBREVIATED_TAX_INVOICE_PDF`.

- [ ] **Step 1: Add the enum value**

Insert after line 73 (`GENERATE_RECEIPT_PDF`) and before line 77 (`GENERATE_ABBREVIATED_TAX_INVOICE_PDF`):

```java
    /**
     * Cancellation note PDF generation via cancellationnote-pdf-generation-service.
     */
    GENERATE_CANCELLATION_NOTE_PDF("generate-cancellation-note-pdf", "Cancellation Note PDF Generation Service"),
```

- [ ] **Step 2: Build saga-commons to verify compilation**

Run: `cd /home/wpanther/projects/etax/saga-commons && mvn clean install -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /home/wpanther/projects/etax/saga-commons
git add src/main/java/com/wpanther/saga/domain/enums/SagaStep.java
git commit -m "feat: add GENERATE_CANCELLATION_NOTE_PDF to SagaStep enum"
```

---

## Task 2: Create pom.xml and main application class

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/CancellationNotePdfGenerationServiceApplication.java`

- [ ] **Step 1: Create pom.xml**

Create `pom.xml` at the service root. Mirrors receipt-pdf-generation-service with these changes:
- `artifactId` → `cancellationnote-pdf-generation-service`
- `name` → `Cancellation Note PDF Generation Service`
- `description` → `Microservice for generating PDF/A-3 documents for Thai e-Tax cancellation notes with embedded XML`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.wpanther</groupId>
    <artifactId>cancellationnote-pdf-generation-service</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Cancellation Note PDF Generation Service</name>
    <description>Microservice for generating PDF/A-3 documents for Thai e-Tax cancellation notes with embedded XML</description>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <spring-boot.version>3.2.5</spring-boot.version>
        <spring-cloud.version>2023.0.1</spring-cloud.version>
        <fop.version>2.9</fop.version>
        <pdfbox.version>3.0.1</pdfbox.version>
        <lombok.version>1.18.30</lombok.version>
        <flyway.version>10.10.0</flyway.version>
        <camel.version>4.14.4</camel.version>
        <saga.commons.version>1.0.0-SNAPSHOT</saga.commons.version>
        <aws-sdk.version>2.20.26</aws-sdk.version>
        <resilience4j.version>2.1.0</resilience4j.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.apache.camel.springboot</groupId>
                <artifactId>camel-spring-boot-bom</artifactId>
                <version>${camel.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>bom</artifactId>
                <version>${aws-sdk.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Apache Camel -->
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-kafka-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.camel.springboot</groupId>
            <artifactId>camel-jackson-starter</artifactId>
        </dependency>

        <!-- Spring Cloud Netflix Eureka Client -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- PostgreSQL Driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
            <version>${flyway.version}</version>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <version>${flyway.version}</version>
        </dependency>

        <!-- Apache FOP -->
        <dependency>
            <groupId>org.apache.xmlgraphics</groupId>
            <artifactId>fop</artifactId>
            <version>${fop.version}</version>
        </dependency>

        <!-- Apache PDFBox -->
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>${pdfbox.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>xmpbox</artifactId>
            <version>${pdfbox.version}</version>
        </dependency>

        <!-- XML Processing -->
        <dependency>
            <groupId>jakarta.xml.bind</groupId>
            <artifactId>jakarta.xml.bind-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.glassfish.jaxb</groupId>
            <artifactId>jaxb-runtime</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Saga Commons -->
        <dependency>
            <groupId>com.wpanther</groupId>
            <artifactId>saga-commons</artifactId>
            <version>${saga.commons.version}</version>
        </dependency>

        <!-- AWS SDK v2 S3 for MinIO -->
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3</artifactId>
        </dependency>

        <!-- Resilience4j -->
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>${resilience4j.version}</version>
        </dependency>

        <!-- Metrics & Tracing -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-tracing-bridge-otel</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>

        <!-- Test Dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.camel</groupId>
            <artifactId>camel-test-spring-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-maven-plugin</artifactId>
                <version>${flyway.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create main application class**

Create `src/main/java/com/wpanther/cancellationnote/pdf/CancellationNotePdfGenerationServiceApplication.java`:

```java
package com.wpanther.cancellationnote.pdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CancellationNotePdfGenerationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CancellationNotePdfGenerationServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /home/wpanther/projects/etax/invoice-microservices/services/cancellationnote-pdf-generation-service && mvn clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/java/com/wpanther/cancellationnote/pdf/CancellationNotePdfGenerationServiceApplication.java
git commit -m "feat: add pom.xml and main application class for cancellation note PDF generation service"
```

---

## Task 3: Domain layer — models, exception, constants, repository interface

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/domain/model/GenerationStatus.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/domain/model/CancellationNotePdfDocument.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/domain/exception/CancellationNotePdfGenerationException.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/domain/constants/PdfGenerationConstants.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/domain/repository/CancellationNotePdfDocumentRepository.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/domain/service/CancellationNotePdfGenerationService.java`
- Create: `src/test/java/com/wpanther/cancellationnote/pdf/domain/model/CancellationNotePdfDocumentTest.java`

- [ ] **Step 1: Create GenerationStatus enum**

Create `src/main/java/com/wpanther/cancellationnote/pdf/domain/model/GenerationStatus.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.model;

public enum GenerationStatus {
    PENDING,
    GENERATING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 2: Create CancellationNotePdfGenerationException**

Create `src/main/java/com/wpanther/cancellationnote/pdf/domain/exception/CancellationNotePdfGenerationException.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.exception;

public class CancellationNotePdfGenerationException extends RuntimeException {

    public CancellationNotePdfGenerationException(String message) {
        super(message);
    }

    public CancellationNotePdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: Create CancellationNotePdfDocument aggregate root**

Create `src/main/java/com/wpanther/cancellationnote/pdf/domain/model/CancellationNotePdfDocument.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.model;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public class CancellationNotePdfDocument {

    private static final String DEFAULT_MIME_TYPE = "application/pdf";

    private final UUID id;
    private final String cancellationNoteId;
    private final String cancellationNoteNumber;
    private String documentPath;
    private String documentUrl;
    private long fileSize;
    private final String mimeType;
    private boolean xmlEmbedded;
    private GenerationStatus status;
    private String errorMessage;
    private int retryCount;
    private final LocalDateTime createdAt;
    private LocalDateTime completedAt;

    private CancellationNotePdfDocument(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID();
        this.cancellationNoteId = Objects.requireNonNull(builder.cancellationNoteId, "Cancellation Note ID is required");
        this.cancellationNoteNumber = Objects.requireNonNull(builder.cancellationNoteNumber, "Cancellation Note number is required");
        this.documentPath = builder.documentPath;
        this.documentUrl = builder.documentUrl;
        this.fileSize = builder.fileSize;
        this.mimeType = builder.mimeType != null ? builder.mimeType : DEFAULT_MIME_TYPE;
        this.xmlEmbedded = builder.xmlEmbedded;
        this.status = builder.status != null ? builder.status : GenerationStatus.PENDING;
        this.errorMessage = builder.errorMessage;
        this.retryCount = builder.retryCount;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.completedAt = builder.completedAt;

        validateInvariant();
    }

    private void validateInvariant() {
        if (cancellationNoteId.isBlank()) {
            throw new CancellationNotePdfGenerationException("Cancellation Note ID cannot be blank");
        }
        if (cancellationNoteNumber.isBlank()) {
            throw new CancellationNotePdfGenerationException("Cancellation Note number cannot be blank");
        }
    }

    public void startGeneration() {
        if (this.status != GenerationStatus.PENDING) {
            throw new CancellationNotePdfGenerationException("Can only start generation from PENDING status");
        }
        this.status = GenerationStatus.GENERATING;
    }

    public void markCompleted(String documentPath, String documentUrl, long fileSize) {
        if (this.status != GenerationStatus.GENERATING) {
            throw new CancellationNotePdfGenerationException("Can only complete from GENERATING status");
        }
        Objects.requireNonNull(documentPath, "Document path is required");
        Objects.requireNonNull(documentUrl, "Document URL is required");
        if (fileSize <= 0) {
            throw new IllegalArgumentException("File size must be positive");
        }
        this.documentPath = documentPath;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.status = GenerationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        Objects.requireNonNull(errorMessage, "Error message is required when marking as failed");
        this.status = GenerationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    public void markXmlEmbedded() {
        this.xmlEmbedded = true;
    }

    public boolean isSuccessful() { return status == GenerationStatus.COMPLETED; }
    public boolean isCompleted() { return status == GenerationStatus.COMPLETED; }
    public boolean isFailed() { return status == GenerationStatus.FAILED; }

    public void incrementRetryCount() { this.retryCount++; }

    public void incrementRetryCountTo(int target) {
        if (target < 0) throw new IllegalArgumentException("Target retry count cannot be negative");
        if (this.retryCount < target) this.retryCount = target;
    }

    public void setRetryCount(int retryCount) {
        if (retryCount < 0) throw new IllegalArgumentException("Retry count cannot be negative");
        this.retryCount = retryCount;
    }

    public boolean isMaxRetriesExceeded(int maxRetries) {
        return this.retryCount >= maxRetries;
    }

    // Getters
    public UUID getId() { return id; }
    public String getCancellationNoteId() { return cancellationNoteId; }
    public String getCancellationNoteNumber() { return cancellationNoteNumber; }
    public String getDocumentPath() { return documentPath; }
    public String getDocumentUrl() { return documentUrl; }
    public long getFileSize() { return fileSize; }
    public String getMimeType() { return mimeType; }
    public boolean isXmlEmbedded() { return xmlEmbedded; }
    public GenerationStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public int getRetryCount() { return retryCount; }

    @Override
    public String toString() {
        return "CancellationNotePdfDocument{id=" + id + ", cancellationNoteId='" + cancellationNoteId
                + "', cancellationNoteNumber='" + cancellationNoteNumber + "', status=" + status
                + ", retryCount=" + retryCount + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CancellationNotePdfDocument other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String cancellationNoteId;
        private String cancellationNoteNumber;
        private String documentPath;
        private String documentUrl;
        private long fileSize;
        private String mimeType;
        private boolean xmlEmbedded;
        private GenerationStatus status;
        private String errorMessage;
        private int retryCount;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder cancellationNoteId(String cancellationNoteId) { this.cancellationNoteId = cancellationNoteId; return this; }
        public Builder cancellationNoteNumber(String cancellationNoteNumber) { this.cancellationNoteNumber = cancellationNoteNumber; return this; }
        public Builder documentPath(String documentPath) { this.documentPath = documentPath; return this; }
        public Builder documentUrl(String documentUrl) { this.documentUrl = documentUrl; return this; }
        public Builder fileSize(long fileSize) { this.fileSize = fileSize; return this; }
        public Builder mimeType(String mimeType) { this.mimeType = mimeType; return this; }
        public Builder xmlEmbedded(boolean xmlEmbedded) { this.xmlEmbedded = xmlEmbedded; return this; }
        public Builder status(GenerationStatus status) { this.status = status; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder completedAt(LocalDateTime completedAt) { this.completedAt = completedAt; return this; }
        public CancellationNotePdfDocument build() { return new CancellationNotePdfDocument(this); }
    }
}
```

- [ ] **Step 4: Create PdfGenerationConstants**

Create `src/main/java/com/wpanther/cancellationnote/pdf/domain/constants/PdfGenerationConstants.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.constants;

public final class PdfGenerationConstants {

    private PdfGenerationConstants() {}

    public static final String DOCUMENT_TYPE = "CANCELLATION_NOTE";
    public static final String S3_KEY_PREFIX = "cancellationnote-";
    public static final String PDF_FILE_EXTENSION = ".pdf";
    public static final String DEFAULT_MIME_TYPE = "application/pdf";

    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_MAX_PDF_SIZE_BYTES = 52_428_800L; // 50 MB
}
```

- [ ] **Step 5: Create repository interface**

Create `src/main/java/com/wpanther/cancellationnote/pdf/domain/repository/CancellationNotePdfDocumentRepository.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.repository;

import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;

import java.util.Optional;
import java.util.UUID;

public interface CancellationNotePdfDocumentRepository {

    CancellationNotePdfDocument save(CancellationNotePdfDocument document);

    Optional<CancellationNotePdfDocument> findById(UUID id);

    Optional<CancellationNotePdfDocument> findByCancellationNoteId(String cancellationNoteId);

    void deleteById(UUID id);

    void flush();
}
```

- [ ] **Step 6: Create domain service port interface**

Create `src/main/java/com/wpanther/cancellationnote/pdf/domain/service/CancellationNotePdfGenerationService.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.service;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;

public interface CancellationNotePdfGenerationService {

    byte[] generatePdf(String cancellationNoteNumber, String signedXml)
        throws CancellationNotePdfGenerationException;
}
```

- [ ] **Step 7: Write CancellationNotePdfDocumentTest**

Create `src/test/java/com/wpanther/cancellationnote/pdf/domain/model/CancellationNotePdfDocumentTest.java`:

```java
package com.wpanther.cancellationnote.pdf.domain.model;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CancellationNotePdfDocumentTest {

    private CancellationNotePdfDocument.Builder validBuilder() {
        return CancellationNotePdfDocument.builder()
                .cancellationNoteId("CNL-001")
                .cancellationNoteNumber("CNL2024010001");
    }

    // --- Builder / Invariants ---

    @Test
    void builder_createsWithPendingStatusAndDefaults() {
        var doc = validBuilder().build();
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(doc.getMimeType()).isEqualTo("application/pdf");
        assertThat(doc.isXmlEmbedded()).isFalse();
        assertThat(doc.getRetryCount()).isZero();
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getCreatedAt()).isNotNull();
    }

    @Test
    void builder_rejectsNullCancellationNoteId() {
        assertThatThrownBy(() -> validBuilder().cancellationNoteId(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cancellation Note ID is required");
    }

    @Test
    void builder_rejectsBlankCancellationNoteId() {
        assertThatThrownBy(() -> validBuilder().cancellationNoteId("  ").build())
                .isInstanceOf(CancellationNotePdfGenerationException.class)
                .hasMessageContaining("Cancellation Note ID cannot be blank");
    }

    @Test
    void builder_rejectsNullCancellationNoteNumber() {
        assertThatThrownBy(() -> validBuilder().cancellationNoteNumber(null).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cancellation Note number is required");
    }

    @Test
    void builder_rejectsBlankCancellationNoteNumber() {
        assertThatThrownBy(() -> validBuilder().cancellationNoteNumber("  ").build())
                .isInstanceOf(CancellationNotePdfGenerationException.class)
                .hasMessageContaining("Cancellation Note number cannot be blank");
    }

    @Test
    void builder_preservesProvidedId() {
        UUID id = UUID.randomUUID();
        var doc = validBuilder().id(id).build();
        assertThat(doc.getId()).isEqualTo(id);
    }

    // --- State Machine: startGeneration ---

    @Test
    void startGeneration_transitionsFromPendingToGenerating() {
        var doc = validBuilder().build();
        doc.startGeneration();
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.GENERATING);
    }

    @Test
    void startGeneration_rejectsNonPendingStatus() {
        var doc = validBuilder().status(GenerationStatus.GENERATING).build();
        assertThatThrownBy(doc::startGeneration)
                .isInstanceOf(CancellationNotePdfGenerationException.class)
                .hasMessageContaining("PENDING");
    }

    // --- State Machine: markCompleted ---

    @Test
    void markCompleted_transitionsFromGeneratingToCompleted() {
        var doc = validBuilder().build();
        doc.startGeneration();
        doc.markCompleted("path.pdf", "http://example.com/path.pdf", 1024);
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(doc.getDocumentPath()).isEqualTo("path.pdf");
        assertThat(doc.getDocumentUrl()).isEqualTo("http://example.com/path.pdf");
        assertThat(doc.getFileSize()).isEqualTo(1024);
        assertThat(doc.getCompletedAt()).isNotNull();
    }

    @Test
    void markCompleted_rejectsNonGeneratingStatus() {
        var doc = validBuilder().build();
        assertThatThrownBy(() -> doc.markCompleted("p", "u", 100))
                .isInstanceOf(CancellationNotePdfGenerationException.class);
    }

    @Test
    void markCompleted_rejectsNullPath() {
        var doc = validBuilder().build();
        doc.startGeneration();
        assertThatThrownBy(() -> doc.markCompleted(null, "url", 100))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void markCompleted_rejectsZeroFileSize() {
        var doc = validBuilder().build();
        doc.startGeneration();
        assertThatThrownBy(() -> doc.markCompleted("p", "u", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- State Machine: markFailed ---

    @Test
    void markFailed_transitionsAnyStatusToFailed() {
        var doc = validBuilder().build();
        doc.startGeneration();
        doc.markFailed("something went wrong");
        assertThat(doc.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("something went wrong");
    }

    @Test
    void markFailed_rejectsNullMessage() {
        var doc = validBuilder().build();
        assertThatThrownBy(() -> doc.markFailed(null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- XmlEmbedded ---

    @Test
    void markXmlEmbedded_setsFlag() {
        var doc = validBuilder().build();
        assertThat(doc.isXmlEmbedded()).isFalse();
        doc.markXmlEmbedded();
        assertThat(doc.isXmlEmbedded()).isTrue();
    }

    // --- Retry counting ---

    @Test
    void incrementRetryCount_increments() {
        var doc = validBuilder().build();
        assertThat(doc.getRetryCount()).isZero();
        doc.incrementRetryCount();
        assertThat(doc.getRetryCount()).isEqualTo(1);
    }

    @Test
    void incrementRetryCountTo_setsToTarget() {
        var doc = validBuilder().build();
        doc.incrementRetryCountTo(5);
        assertThat(doc.getRetryCount()).isEqualTo(5);
    }

    @Test
    void incrementRetryCountTo_doesNotDecrease() {
        var doc = validBuilder().retryCount(5).build();
        doc.incrementRetryCountTo(3);
        assertThat(doc.getRetryCount()).isEqualTo(5);
    }

    @Test
    void incrementRetryCountTo_rejectsNegative() {
        var doc = validBuilder().build();
        assertThatThrownBy(() -> doc.incrementRetryCountTo(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isMaxRetriesExceeded_returnsTrueWhenExceeded() {
        var doc = validBuilder().retryCount(3).build();
        assertThat(doc.isMaxRetriesExceeded(3)).isTrue();
    }

    @Test
    void isMaxRetriesExceeded_returnsFalseWhenUnderLimit() {
        var doc = validBuilder().retryCount(2).build();
        assertThat(doc.isMaxRetriesExceeded(3)).isFalse();
    }

    // --- Convenience methods ---

    @Test
    void isSuccessful_andIsCompleted_areTrueWhenCompleted() {
        var doc = validBuilder().build();
        doc.startGeneration();
        doc.markCompleted("p", "u", 100);
        assertThat(doc.isSuccessful()).isTrue();
        assertThat(doc.isCompleted()).isTrue();
        assertThat(doc.isFailed()).isFalse();
    }

    // --- equals/hashCode ---

    @Test
    void equals_isBasedOnId() {
        UUID id = UUID.randomUUID();
        var a = validBuilder().id(id).cancellationNoteNumber("A").build();
        var b = validBuilder().id(id).cancellationNoteNumber("B").build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equals_returnsFalseForDifferentIds() {
        var a = validBuilder().build();
        var b = validBuilder().build();
        assertThat(a).isNotEqualTo(b);
    }
}
```

- [ ] **Step 8: Run domain tests**

Run: `cd /home/wpanther/projects/etax/invoice-microservices/services/cancellationnote-pdf-generation-service && mvn test -pl . -Dtest="com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocumentTest"`
Expected: All tests pass

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/domain/ src/test/java/com/wpanther/cancellationnote/pdf/domain/
git commit -m "feat: add domain layer — model, exception, constants, repository interface"
```

---

## Task 4: Application layer — ports, use cases, SagaCommandHandler, CancellationNotePdfDocumentService

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/PdfEventPort.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/PdfStoragePort.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/SagaReplyPort.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/SignedXmlFetchPort.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/ProcessCancellationNotePdfUseCase.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/CompensateCancellationNotePdfUseCase.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/service/CancellationNotePdfDocumentService.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/application/service/SagaCommandHandler.java`

- [ ] **Step 1: Create output port interfaces**

Create `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/PdfEventPort.java`:

```java
package com.wpanther.cancellationnote.pdf.application.port.out;

import com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging.CancellationNotePdfGeneratedEvent;

public interface PdfEventPort {
    void publishGenerated(CancellationNotePdfGeneratedEvent event);
}
```

Create `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/PdfStoragePort.java`:

```java
package com.wpanther.cancellationnote.pdf.application.port.out;

public interface PdfStoragePort {

    String store(String cancellationNoteNumber, byte[] pdfBytes);

    void delete(String documentPath);

    String resolveUrl(String documentPath);
}
```

Create `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/SagaReplyPort.java`:

```java
package com.wpanther.cancellationnote.pdf.application.port.out;

import com.wpanther.saga.domain.enums.SagaStep;

public interface SagaReplyPort {

    void publishSuccess(String sagaId, SagaStep step, String correlationId,
                        String pdfUrl, long pdfSize);

    void publishFailure(String sagaId, SagaStep step, String correlationId,
                        String errorMessage);

    void publishCompensated(String sagaId, SagaStep step, String correlationId);
}
```

Create `src/main/java/com/wpanther/cancellationnote/pdf/application/port/out/SignedXmlFetchPort.java`:

```java
package com.wpanther.cancellationnote.pdf.application.port.out;

public interface SignedXmlFetchPort {

    String fetch(String url);

    class SignedXmlFetchException extends RuntimeException {
        public SignedXmlFetchException(String message) { super(message); }
        public SignedXmlFetchException(String message, Throwable cause) { super(message, cause); }
    }
}
```

- [ ] **Step 2: Create use case interfaces**

Create `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/ProcessCancellationNotePdfUseCase.java`:

```java
package com.wpanther.cancellationnote.pdf.application.usecase;

import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteProcessCommand;

public interface ProcessCancellationNotePdfUseCase {
    void handle(KafkaCancellationNoteProcessCommand command);
}
```

Create `src/main/java/com/wpanther/cancellationnote/pdf/application/usecase/CompensateCancellationNotePdfUseCase.java`:

```java
package com.wpanther.cancellationnote.pdf.application.usecase;

import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteCompensateCommand;

public interface CompensateCancellationNotePdfUseCase {
    void handle(KafkaCancellationNoteCompensateCommand command);
}
```

- [ ] **Step 3: Verify compilation of ports and use cases**

Note: These reference Kafka command classes from the infrastructure layer that don't exist yet. This is expected — they will be created in Task 5. For now, verify the domain and port/out interfaces compile:

Run: `cd /home/wpanther/projects/etax/invoice-microservices/services/cancellationnote-pdf-generation-service && mvn clean compile -DskipTests`
Expected: Compilation will fail due to missing Kafka command classes — this is expected. We'll create those next.

Proceed to create the Kafka command classes and messaging events first (Tasks 5-6), then return to complete SagaCommandHandler and CancellationNotePdfDocumentService.

---

## Task 5: Kafka adapter — commands, mapper, route config

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java`

- [ ] **Step 1: Create KafkaCancellationNoteProcessCommand**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteProcessCommand.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;

import java.time.Instant;
import java.util.UUID;

public class KafkaCancellationNoteProcessCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")     private final String documentId;
    @JsonProperty("documentNumber") private final String documentNumber;
    @JsonProperty("signedXmlUrl")   private final String signedXmlUrl;

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
```

- [ ] **Step 2: Create KafkaCancellationNoteCompensateCommand**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCancellationNoteCompensateCommand.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;

import java.time.Instant;
import java.util.UUID;

public class KafkaCancellationNoteCompensateCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId") private final String documentId;

    @JsonCreator
    public KafkaCancellationNoteCompensateCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
    }

    public KafkaCancellationNoteCompensateCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                                  String documentId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
    }

    @Override public String getSagaId()        { return super.getSagaId(); }
    @Override public SagaStep getSagaStep()    { return super.getSagaStep(); }
    @Override public String getCorrelationId() { return super.getCorrelationId(); }
    public String getDocumentId() { return documentId; }
}
```

- [ ] **Step 3: Create KafkaCommandMapper**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import org.springframework.stereotype.Component;

@Component
public class KafkaCommandMapper {

    public KafkaCancellationNoteProcessCommand toProcess(KafkaCancellationNoteProcessCommand src) {
        return src;
    }

    public KafkaCancellationNoteCompensateCommand toCompensate(KafkaCancellationNoteCompensateCommand src) {
        return src;
    }
}
```

- [ ] **Step 4: Create SagaRouteConfig**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.cancellationnote.pdf.application.service.SagaCommandHandler;
import com.wpanther.cancellationnote.pdf.application.usecase.CompensateCancellationNotePdfUseCase;
import com.wpanther.cancellationnote.pdf.application.usecase.ProcessCancellationNotePdfUseCase;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
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
                            if (body instanceof KafkaCancellationNoteProcessCommand cmd) {
                                log.error("DLQ: notifying orchestrator of retry exhaustion for saga {} document {}",
                                        cmd.getSagaId(), cmd.getDocumentNumber());
                                sagaCommandHandler.publishOrchestrationFailure(cmd, cause);
                            } else if (body instanceof KafkaCancellationNoteCompensateCommand cmd) {
                                log.error("DLQ: notifying orchestrator of compensation retry exhaustion for saga {} document {}",
                                        cmd.getSagaId(), cmd.getDocumentId());
                                sagaCommandHandler.publishCompensationOrchestrationFailure(cmd, cause);
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
                .unmarshal().json(JsonLibrary.Jackson, KafkaCancellationNoteProcessCommand.class)
                .process(exchange -> {
                        KafkaCancellationNoteProcessCommand cmd =
                                exchange.getIn().getBody(KafkaCancellationNoteProcessCommand.class);
                        log.info("Processing saga command for saga: {}, document: {}",
                                        cmd.getSagaId(), cmd.getDocumentNumber());
                        processUseCase.handle(cmd);
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
                .unmarshal().json(JsonLibrary.Jackson, KafkaCancellationNoteCompensateCommand.class)
                .process(exchange -> {
                        KafkaCancellationNoteCompensateCommand cmd =
                                exchange.getIn().getBody(KafkaCancellationNoteCompensateCommand.class);
                        log.info("Processing compensation for saga: {}, document: {}",
                                        cmd.getSagaId(), cmd.getDocumentId());
                        compensateUseCase.handle(cmd);
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

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/in/kafka/
git commit -m "feat: add Kafka adapter — commands, mapper, route config"
```

---

## Task 6: Messaging — events, reply event, outbox constants, publishers

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/OutboxConstants.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfReplyEvent.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/EventPublisher.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/SagaReplyPublisher.java`

- [ ] **Step 1: Create OutboxConstants**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/OutboxConstants.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

final class OutboxConstants {

    static final String AGGREGATE_TYPE = "CancellationNotePdfDocument";

    private OutboxConstants() {}
}
```

- [ ] **Step 2: Create CancellationNotePdfGeneratedEvent**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfGeneratedEvent.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

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
            String correlationId
    ) {
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
            @JsonProperty("xmlEmbedded") boolean xmlEmbedded
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }
}
```

- [ ] **Step 3: Create CancellationNotePdfReplyEvent**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/CancellationNotePdfReplyEvent.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.wpanther.saga.domain.enums.ReplyStatus;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaReply;

public class CancellationNotePdfReplyEvent extends SagaReply {

    private static final long serialVersionUID = 1L;

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

    public String getPdfUrl() { return pdfUrl; }
    public Long getPdfSize() { return pdfSize; }
}
```

- [ ] **Step 4: Create EventPublisher**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/EventPublisher.java`:

```java
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

@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher implements PdfEventPort {

    private static final String AGGREGATE_TYPE = OutboxConstants.AGGREGATE_TYPE;

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

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
            throw new IllegalStateException("Failed to serialize outbox event headers", e);
        }
    }
}
```

- [ ] **Step 5: Create SagaReplyPublisher**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/SagaReplyPublisher.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.cancellationnote.pdf.application.port.out.SagaReplyPort;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaReplyPublisher implements SagaReplyPort {

    private static final String REPLY_TOPIC = "saga.reply.cancellation-note-pdf";
    private static final String AGGREGATE_TYPE = OutboxConstants.AGGREGATE_TYPE;

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                               String pdfUrl, long pdfSize) {
        CancellationNotePdfReplyEvent reply = CancellationNotePdfReplyEvent.success(sagaId, sagaStep, correlationId, pdfUrl, pdfSize);
        Map<String, String> headers = Map.of(
                "sagaId", sagaId, "correlationId", correlationId, "status", "SUCCESS");
        outboxService.saveWithRouting(reply, AGGREGATE_TYPE, sagaId, REPLY_TOPIC, sagaId, toJson(headers));
        log.info("Published SUCCESS saga reply for saga {} step {}", sagaId, sagaStep);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        CancellationNotePdfReplyEvent reply = CancellationNotePdfReplyEvent.failure(sagaId, sagaStep, correlationId, errorMessage);
        Map<String, String> headers = Map.of(
                "sagaId", sagaId, "correlationId", correlationId, "status", "FAILURE");
        outboxService.saveWithRouting(reply, AGGREGATE_TYPE, sagaId, REPLY_TOPIC, sagaId, toJson(headers));
        log.info("Published FAILURE saga reply for saga {} step {}: {}", sagaId, sagaStep, errorMessage);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        CancellationNotePdfReplyEvent reply = CancellationNotePdfReplyEvent.compensated(sagaId, sagaStep, correlationId);
        Map<String, String> headers = Map.of(
                "sagaId", sagaId, "correlationId", correlationId, "status", "COMPENSATED");
        outboxService.saveWithRouting(reply, AGGREGATE_TYPE, sagaId, REPLY_TOPIC, sagaId, toJson(headers));
        log.info("Published COMPENSATED saga reply for saga {} step {}", sagaId, sagaStep);
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event headers", e);
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/messaging/
git commit -m "feat: add messaging — events, reply event, outbox constants, publishers"
```

---

## Task 7: Persistence — JPA entity, repository, outbox entities

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/CancellationNotePdfDocumentEntity.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/JpaCancellationNotePdfDocumentRepository.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/CancellationNotePdfDocumentRepositoryAdapter.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/outbox/OutboxEventEntity.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/outbox/SpringDataOutboxRepository.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/outbox/JpaOutboxEventRepository.java`

- [ ] **Step 1: Create CancellationNotePdfDocumentEntity**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/CancellationNotePdfDocumentEntity.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.cancellationnote.pdf.domain.model.GenerationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cancellation_note_pdf_documents", indexes = {
    @Index(name = "idx_cn_pdf_cancellation_note_id", columnList = "cancellation_note_id"),
    @Index(name = "idx_cn_pdf_cancellation_note_number", columnList = "cancellation_note_number"),
    @Index(name = "idx_cn_pdf_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationNotePdfDocumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "cancellation_note_id", nullable = false, length = 100, unique = true)
    private String cancellationNoteId;

    @Column(name = "cancellation_note_number", nullable = false, length = 50)
    private String cancellationNoteNumber;

    @Column(name = "document_path", length = 500)
    private String documentPath;

    @Column(name = "document_url", length = 1000)
    private String documentUrl;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "xml_embedded", nullable = false)
    private Boolean xmlEmbedded;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (status == null) status = GenerationStatus.PENDING;
        if (mimeType == null) mimeType = "application/pdf";
        if (xmlEmbedded == null) xmlEmbedded = false;
        if (retryCount == null) retryCount = 0;
    }
}
```

- [ ] **Step 2: Create JpaCancellationNotePdfDocumentRepository**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/JpaCancellationNotePdfDocumentRepository.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface JpaCancellationNotePdfDocumentRepository extends JpaRepository<CancellationNotePdfDocumentEntity, UUID> {

    Optional<CancellationNotePdfDocumentEntity> findByCancellationNoteId(String cancellationNoteId);

    @Query("SELECT e.documentPath FROM CancellationNotePdfDocumentEntity e WHERE e.documentPath IS NOT NULL")
    Set<String> findAllDocumentPaths();
}
```

- [ ] **Step 3: Create CancellationNotePdfDocumentRepositoryAdapter**

Create `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/CancellationNotePdfDocumentRepositoryAdapter.java`:

```java
package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.repository.CancellationNotePdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CancellationNotePdfDocumentRepositoryAdapter implements CancellationNotePdfDocumentRepository {

    private final JpaCancellationNotePdfDocumentRepository jpaRepository;

    @Override
    public CancellationNotePdfDocument save(CancellationNotePdfDocument document) {
        CancellationNotePdfDocumentEntity entity = toEntity(document);
        entity = jpaRepository.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<CancellationNotePdfDocument> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<CancellationNotePdfDocument> findByCancellationNoteId(String cancellationNoteId) {
        return jpaRepository.findByCancellationNoteId(cancellationNoteId).map(this::toDomain);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    private CancellationNotePdfDocumentEntity toEntity(CancellationNotePdfDocument document) {
        return CancellationNotePdfDocumentEntity.builder()
            .id(document.getId())
            .cancellationNoteId(document.getCancellationNoteId())
            .cancellationNoteNumber(document.getCancellationNoteNumber())
            .documentPath(document.getDocumentPath())
            .documentUrl(document.getDocumentUrl())
            .fileSize(document.getFileSize())
            .mimeType(document.getMimeType())
            .xmlEmbedded(document.isXmlEmbedded())
            .status(document.getStatus())
            .errorMessage(document.getErrorMessage())
            .retryCount(document.getRetryCount())
            .createdAt(document.getCreatedAt())
            .completedAt(document.getCompletedAt())
            .build();
    }

    private CancellationNotePdfDocument toDomain(CancellationNotePdfDocumentEntity entity) {
        return CancellationNotePdfDocument.builder()
            .id(entity.getId())
            .cancellationNoteId(entity.getCancellationNoteId())
            .cancellationNoteNumber(entity.getCancellationNoteNumber())
            .documentPath(entity.getDocumentPath())
            .documentUrl(entity.getDocumentUrl())
            .fileSize(entity.getFileSize() != null ? entity.getFileSize() : 0L)
            .mimeType(entity.getMimeType())
            .xmlEmbedded(entity.getXmlEmbedded() != null && entity.getXmlEmbedded())
            .status(entity.getStatus())
            .errorMessage(entity.getErrorMessage())
            .retryCount(entity.getRetryCount() != null ? entity.getRetryCount() : 0)
            .createdAt(entity.getCreatedAt())
            .completedAt(entity.getCompletedAt())
            .build();
    }
}
```

- [ ] **Step 4: Create outbox entities**

Copy outbox files from receipt service, adapting package name to `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.persistence.outbox`:

- `OutboxEventEntity.java` — identical to receipt version except package
- `SpringDataOutboxRepository.java` — identical to receipt version except package
- `JpaOutboxEventRepository.java` — identical to receipt version except package

These files are generic — they reference `OutboxEvent` from saga-commons, not any document-specific type. Copy them verbatim changing only the `package` declaration.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/persistence/
git commit -m "feat: add persistence — JPA entity, repository adapter, outbox entities"
```

---

## Task 8: Infrastructure adapters — REST client, PDF generation, MinIO storage, configs, metrics

**Files:**
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/client/RestTemplateSignedXmlFetcher.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/pdf/FopCancellationNotePdfGenerator.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/pdf/PdfA3Converter.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverter.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/pdf/CancellationNotePdfGenerationServiceImpl.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/storage/MinioStorageAdapter.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/adapter/out/storage/MinioCleanupService.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/config/MinioConfig.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/config/OutboxConfig.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/config/RestTemplateConfig.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/config/FontHealthCheck.java`
- Create: `src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/metrics/PdfGenerationMetrics.java`

- [ ] **Step 1: Create RestTemplateSignedXmlFetcher**

Copy from receipt service, change package to `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.client`. The class is identical — it implements `SignedXmlFetchPort` and uses `RestTemplate` with circuit breaker. Only the port interface package changes.

- [ ] **Step 2: Create FopCancellationNotePdfGenerator**

Copy from `FopReceiptPdfGenerator` with these changes:
- Package → `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.pdf`
- XSL path constant → `xsl/cancellationnote-direct.xsl`
- Exception type → `CancellationNotePdfGenerationException`
- Class name → `FopCancellationNotePdfGenerator`
- Log messages → reference "cancellation note" instead of "receipt"

- [ ] **Step 3: Create PdfA3Converter**

Copy from receipt service, change package to `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.pdf`. Changes:
- `dc.setTitle("Thai e-Tax Cancellation Note: " + receiptNumber)`
- `dc.setDescription("Electronic cancellation note with embedded XML source")`
- `dc.addCreator("Cancellation Note PDF Generation Service")`
- `xmpBasic.setCreatorTool("Thai e-Tax Cancellation Note System")`
- Exception type → `CancellationNotePdfGenerationException`

- [ ] **Step 4: Create ThaiAmountWordsConverter**

Copy verbatim from receipt service, change package to `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.pdf`. This class is pure utility — no document-specific references.

- [ ] **Step 5: Create CancellationNotePdfGenerationServiceImpl**

Copy from `ReceiptPdfGenerationServiceImpl` with these changes:
- Package → `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.pdf`
- `RSM_NS` → `"urn:etda:uncefact:data:standard:CancellationNote_CrossIndustryInvoice:2"`
- `RAM_NS` → `"urn:etda:uncefact:data:standard:CancellationNote_ReusableAggregateBusinessInformationEntity:2"`
- `GRAND_TOTAL_XPATH` → `/rsm:CancellationNote_CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeSettlement/ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:GrandTotalAmount`
- Class name → `CancellationNotePdfGenerationServiceImpl`
- Method param name → `cancellationNoteNumber` instead of `receiptNumber`
- Constructor injection → `FopCancellationNotePdfGenerator` instead of `FopReceiptPdfGenerator`
- Log messages and exception messages → reference "cancellation note"
- `xmlFilename` → `"cancellationnote-" + cancellationNoteNumber + ".xml"`

- [ ] **Step 6: Create MinioStorageAdapter**

Copy from receipt service with these changes:
- Package → `com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.storage`
- Method param `receiptNumber` → `cancellationNoteNumber`
- Filename format → `String.format("cancellationnote-%s-%s.pdf", safeName, UUID.randomUUID())`
- Import `JpaCancellationNotePdfDocumentRepository` instead of `JpaReceiptPdfDocumentRepository`
- Implements `PdfStoragePort` from cancellation note package

- [ ] **Step 7: Create MinioCleanupService**

Copy from receipt service, change package and import `JpaCancellationNotePdfDocumentRepository`.

- [ ] **Step 8: Create config classes and metrics**

Copy all 4 config classes from receipt service, changing:
- Package → `com.wpanther.cancellationnote.pdf.infrastructure.config`
- `OutboxConfig` → import from cancellation note outbox package
- `PdfGenerationMetrics` → counter names `pdf.generation.cancellation_note.*` instead of `pdf.generation.receipt.*`

- [ ] **Step 9: Complete application service classes**

Now that all infrastructure exists, create the two remaining application service files:

`SagaCommandHandler.java` — Copy from receipt, replacing all `Receipt` → `CancellationNote` identifiers. Key changes:
- Implements `ProcessCancellationNotePdfUseCase`, `CompensateCancellationNotePdfUseCase`
- Constructor takes `CancellationNotePdfDocumentService`, `CancellationNotePdfGenerationService`
- `handle(KafkaCancellationNoteProcessCommand)` / `handle(KafkaCancellationNoteCompensateCommand)`
- Uses `CancellationNotePdfDocument` instead of `ReceiptPdfDocument`
- `findByCancellationNoteId` instead of `findByReceiptId`

`CancellationNotePdfDocumentService.java` — Copy from `ReceiptPdfDocumentService`, replacing all identifiers similarly.

- [ ] **Step 10: Verify full compilation**

Run: `cd /home/wpanther/projects/etax/invoice-microservices/services/cancellationnote-pdf-generation-service && mvn clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/wpanther/cancellationnote/pdf/infrastructure/ src/main/java/com/wpanther/cancellationnote/pdf/application/service/
git commit -m "feat: add infrastructure adapters, configs, metrics, and application services"
```

---

## Task 9: Resources — application.yml, Flyway migration, XSL-FO template, fonts, FOP config

**Files:**
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__create_cancellation_note_pdf_tables.sql`
- Create: `src/main/resources/xsl/cancellationnote-direct.xsl`
- Copy: `src/main/resources/fonts/*` from taxinvoice service
- Copy: `src/main/resources/fop/fop.xconf` from taxinvoice service
- Copy: `src/main/resources/icc/sRGB.icc` from taxinvoice service
- Create: `src/test/resources/application-test.yml`
- Create: `src/test/resources/fop/fop.xconf` (simplified for tests)
- Copy: `src/test/resources/xml/CancellationNote_2p1_sample.xml` from document-intake-service

- [ ] **Step 1: Create application.yml**

Adapt from receipt service `application.yml` with these changes:
- `server.port` → `8096`
- `spring.application.name` → `cancellationnote-pdf-generation-service`
- `DB_NAME` → `cancellationnotepdf_db`
- `camel.springboot.name` → `cancellationnote-pdf-generation-camel`
- `app.kafka.consumer.command-group-id` → `cancellationnote-pdf-generation-command`
- `app.kafka.consumer.compensation-group-id` → `cancellationnote-pdf-generation-compensation`
- `app.kafka.topics.saga-command-*` → `saga.command.cancellation-note-pdf`
- `app.kafka.topics.saga-compensation-*` → `saga.compensation.cancellation-note-pdf`
- `app.kafka.topics.pdf-generated-*` → `pdf.generated.cancellation-note`
- `app.kafka.topics.dlq` → `pdf.generation.cancellation-note.dlq`
- `app.minio.bucket-name` → `cancellationnotes`
- `app.minio.base-url` → `http://localhost:9000/cancellationnotes`
- `logging.level.com.wpanther.cancellationnote.pdf` → `INFO`

- [ ] **Step 2: Create Flyway migration**

Create `src/main/resources/db/migration/V1__create_cancellation_note_pdf_tables.sql`:

```sql
CREATE TABLE cancellation_note_pdf_documents (
    id UUID PRIMARY KEY,
    cancellation_note_id VARCHAR(100) NOT NULL UNIQUE,
    cancellation_note_number VARCHAR(50) NOT NULL,
    document_path VARCHAR(500),
    document_url VARCHAR(1000),
    file_size BIGINT,
    mime_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    xml_embedded BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_cn_pdf_cancellation_note_id ON cancellation_note_pdf_documents(cancellation_note_id);
CREATE INDEX idx_cn_pdf_cancellation_note_number ON cancellation_note_pdf_documents(cancellation_note_number);
CREATE INDEX idx_cn_pdf_status ON cancellation_note_pdf_documents(status);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    topic VARCHAR(255),
    partition_key VARCHAR(255),
    headers TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);
CREATE INDEX idx_outbox_pending_created ON outbox_events(status, created_at)
    WHERE status = 'PENDING';
```

- [ ] **Step 3: Copy fonts, FOP config, ICC profile**

Copy from taxinvoice-pdf-generation-service:
```bash
cp -r /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service/src/main/resources/fonts/ src/main/resources/fonts/
cp -r /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service/src/main/resources/fop/ src/main/resources/fop/
cp -r /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service/src/main/resources/icc/ src/main/resources/icc/
```

- [ ] **Step 4: Create XSL-FO template**

Copy `taxinvoice-direct.xsl` from taxinvoice service as the base, then modify:
- `rsm` namespace → `urn:etda:uncefact:data:standard:CancellationNote_CrossIndustryInvoice:2`
- `ram` namespace → `urn:etda:uncefact:data:standard:CancellationNote_ReusableAggregateBusinessInformationEntity:2`
- Root match → `/rsm:CancellationNote_CrossIndustryInvoice`
- Document title → `ใบแจ้งยกเลิก / CANCELLATION NOTE`
- Type code reference → `T07` instead of `T01`

All other XPath expressions (GrandTotalAmount, seller/buyer, line items, monetary summation) remain identical.

```bash
cp /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service/src/main/resources/xsl/taxinvoice-direct.xsl src/main/resources/xsl/cancellationnote-direct.xsl
```

Then edit the copied file to change the namespace URIs, root match, and title.

- [ ] **Step 5: Create test resources**

Create `src/test/resources/application-test.yml` — copy from receipt service test config, adapting:
- `DB_NAME` → `cancellationnotepdf_db`
- `spring.datasource.url` → `jdbc:h2:mem:cancellationnotepdf_db`
- Kafka topics → cancellation-note-prefixed
- MinIO bucket → `cancellationnotes`
- Font health check → `enabled: false`

Copy `src/test/resources/fop/fop.xconf` from receipt test resources (simplified for tests).

Copy the sample XML:
```bash
cp /home/wpanther/projects/etax/invoice-microservices/services/document-intake-service/src/test/resources/samples/valid/CancellationNote_2p1_valid.xml src/test/resources/xml/CancellationNote_2p1_sample.xml
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/ src/test/resources/
git commit -m "feat: add resources — application.yml, Flyway migration, XSL-FO template, fonts, FOP config"
```

---

## Task 10: Remaining tests for full 90% coverage

**Files:**
- Create all test classes listed in the file structure under `src/test/java/com/wpanther/cancellationnote/pdf/`

- [ ] **Step 1: Write CancellationNotePdfDocumentServiceTest**

Test all service methods: `beginGeneration`, `replaceAndBeginGeneration`, `completeGenerationAndPublish`, `failGenerationAndPublish`, `deleteById`, `publishIdempotentSuccess`, `publishRetryExhausted`, `publishGenerationFailure`, `publishCompensated`, `publishCompensationFailure`. Mock repository, ports, and metrics.

- [ ] **Step 2: Write SagaCommandHandlerTest**

Test: success path, idempotency (already COMPLETED), max retries exceeded, generation failure, circuit breaker open, HTTP error, compensation success, idempotent compensation, compensation failure, concurrent modification, unparsed DLQ recovery.

- [ ] **Step 3: Write infrastructure tests**

For each adapter:
- `RestTemplateSignedXmlFetcherTest` — success, null response, circuit breaker fallback
- `FopCancellationNotePdfGeneratorTest` — constructor validation, semaphore, valid XML, malformed XML, size limit
- `PdfA3ConverterTest` — constructor, null/empty PDF
- `MinioStorageAdapterTest` — upload, delete, URL resolution, filename sanitization
- `EventPublisherTest` — outbox event publishing with correct topic
- `SagaReplyPublisherTest` — success/failure/compensated replies
- `KafkaCommandMapperTest` — identity mapping
- `CamelRouteConfigTest` — JSON serialization of all event types
- `MinioCleanupServiceTest` — cleanup scheduling
- `FontHealthCheckTest` — font validation at startup

- [ ] **Step 4: Run all tests and verify coverage**

Run: `cd /home/wpanther/projects/etax/invoice-microservices/services/cancellationnote-pdf-generation-service && mvn verify`
Expected: All tests pass, JaCoCo coverage >= 90%

- [ ] **Step 5: Commit**

```bash
git add src/test/
git commit -m "test: add all test classes for 90% coverage"
```
