package com.wpanther.cancellationnote.pdf.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PdfGenerationMetricsTest {

    @Test
    void constructor_registersCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        assertThatCode(() -> new PdfGenerationMetrics(registry)).doesNotThrowAnyException();
    }

    @Test
    void recordGenerated_incrementsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PdfGenerationMetrics metrics = new PdfGenerationMetrics(registry);
        metrics.recordGenerated();
        assertThat(metrics.generatedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordFailed_incrementsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PdfGenerationMetrics metrics = new PdfGenerationMetrics(registry);
        metrics.recordFailed();
        assertThat(metrics.failedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordRetryExhausted_incrementsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PdfGenerationMetrics metrics = new PdfGenerationMetrics(registry);
        metrics.recordRetryExhausted("saga-1", "cnl-001", "CNL-2024-001");
        assertThat(metrics.retryExhaustedCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordRetryExhausted_multipleCalls_accumulate() {
        MeterRegistry registry = new SimpleMeterRegistry();
        PdfGenerationMetrics metrics = new PdfGenerationMetrics(registry);
        metrics.recordRetryExhausted("saga-1", "cnl-001", "CNL-2024-001");
        metrics.recordRetryExhausted("saga-2", "cnl-002", "CNL-2024-002");
        assertThat(metrics.retryExhaustedCounter.count()).isEqualTo(2.0);
    }
}