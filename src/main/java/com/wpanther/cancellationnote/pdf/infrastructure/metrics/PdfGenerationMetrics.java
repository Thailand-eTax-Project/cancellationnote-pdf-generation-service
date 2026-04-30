package com.wpanther.cancellationnote.pdf.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PdfGenerationMetrics {

    final Counter generatedCounter;
    final Counter failedCounter;
    final Counter retryExhaustedCounter;

    public PdfGenerationMetrics(MeterRegistry registry) {
        this.generatedCounter = Counter.builder("pdf.generation.cancellation_note.generated")
                .description("Number of cancellation note PDFs successfully generated")
                .register(registry);
        this.failedCounter = Counter.builder("pdf.generation.cancellation_note.failed")
                .description("Number of cancellation note PDF generation failures")
                .register(registry);
        this.retryExhaustedCounter = Counter.builder("pdf.generation.cancellation_note.retry_exhausted")
                .description("Number of cancellation note PDF generation attempts that exhausted retries")
                .register(registry);
    }

    public void recordGenerated() { generatedCounter.increment(); }
    public void recordFailed() { failedCounter.increment(); }
    public void recordRetryExhausted(String sagaId, String documentId, String documentNumber) {
        retryExhaustedCounter.increment();
    }
}
