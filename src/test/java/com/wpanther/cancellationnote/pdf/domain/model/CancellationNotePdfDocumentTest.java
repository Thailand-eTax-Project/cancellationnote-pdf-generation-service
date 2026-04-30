package com.wpanther.cancellationnote.pdf.domain.model;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CancellationNotePdfDocument Unit Tests")
class CancellationNotePdfDocumentTest {

    private CancellationNotePdfDocument.Builder defaultBuilder() {
        return CancellationNotePdfDocument.builder()
                .cancellationNoteId("doc-123")
                .cancellationNoteNumber("CN-2024-001");
    }

    @Nested
    @DisplayName("Builder and Creation")
    class BuilderTests {

        @Test
        @DisplayName("Should create document with required fields")
        void shouldCreateWithRequiredFields() {
            CancellationNotePdfDocument doc = defaultBuilder().build();

            assertThat(doc.getId()).isNotNull();
            assertThat(doc.getCancellationNoteId()).isEqualTo("doc-123");
            assertThat(doc.getCancellationNoteNumber()).isEqualTo("CN-2024-001");
            assertThat(doc.getStatus()).isEqualTo(GenerationStatus.PENDING);
            assertThat(doc.getFileSize()).isZero();
            assertThat(doc.getRetryCount()).isZero();
            assertThat(doc.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should throw when cancellationNoteId is null")
        void shouldThrowWhenCancellationNoteIdNull() {
            assertThatThrownBy(() -> CancellationNotePdfDocument.builder()
                    .cancellationNoteId(null)
                    .cancellationNoteNumber("CN-001")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Cancellation Note ID is required");
        }

        @Test
        @DisplayName("Should throw when cancellationNoteId is blank")
        void shouldThrowWhenCancellationNoteIdBlank() {
            assertThatThrownBy(() -> CancellationNotePdfDocument.builder()
                    .cancellationNoteId("  ")
                    .cancellationNoteNumber("CN-001")
                    .build())
                    .isInstanceOf(CancellationNotePdfGenerationException.class)
                    .hasMessageContaining("Cancellation Note ID cannot be blank");
        }

        @Test
        @DisplayName("Should throw when cancellationNoteNumber is null")
        void shouldThrowWhenCancellationNoteNumberNull() {
            assertThatThrownBy(() -> CancellationNotePdfDocument.builder()
                    .cancellationNoteId("doc-123")
                    .cancellationNoteNumber(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Cancellation Note number is required");
        }

        @Test
        @DisplayName("Should throw when cancellationNoteNumber is blank")
        void shouldThrowWhenCancellationNoteNumberBlank() {
            assertThatThrownBy(() -> CancellationNotePdfDocument.builder()
                    .cancellationNoteId("doc-123")
                    .cancellationNoteNumber("  ")
                    .build())
                    .isInstanceOf(CancellationNotePdfGenerationException.class)
                    .hasMessageContaining("Cancellation Note number cannot be blank");
        }

        @Test
        @DisplayName("Should use provided UUID when set")
        void shouldUseProvidedUuid() {
            UUID id = UUID.randomUUID();
            CancellationNotePdfDocument doc = defaultBuilder().id(id).build();
            assertThat(doc.getId()).isEqualTo(id);
        }
    }

    @Nested
    @DisplayName("State Transitions")
    class StateTransitionTests {

        @Test
        @DisplayName("PENDING -> GENERATING via startGeneration()")
        void shouldTransitionToGenerating() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.startGeneration();
            assertThat(doc.getStatus()).isEqualTo(GenerationStatus.GENERATING);
        }

        @Test
        @DisplayName("Cannot startGeneration from GENERATING")
        void shouldNotStartFromGenerating() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.startGeneration();
            assertThatThrownBy(doc::startGeneration)
                    .isInstanceOf(CancellationNotePdfGenerationException.class);
        }

        @Test
        @DisplayName("GENERATING -> COMPLETED via markCompleted()")
        void shouldTransitionToCompleted() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.startGeneration();
            doc.markCompleted("path/cancellationnote.pdf", "http://minio/cancellationnote.pdf", 12345L);

            assertThat(doc.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
            assertThat(doc.getDocumentPath()).isEqualTo("path/cancellationnote.pdf");
            assertThat(doc.getDocumentUrl()).isEqualTo("http://minio/cancellationnote.pdf");
            assertThat(doc.getFileSize()).isEqualTo(12345L);
            assertThat(doc.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("Cannot markCompleted from PENDING")
        void shouldNotCompleteFromPending() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            assertThatThrownBy(() -> doc.markCompleted("p", "u", 1L))
                    .isInstanceOf(CancellationNotePdfGenerationException.class);
        }

        @Test
        @DisplayName("markCompleted requires non-null path")
        void shouldNotCompleteWithNullPath() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.startGeneration();
            assertThatThrownBy(() -> doc.markCompleted(null, "url", 1L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("markCompleted requires positive fileSize")
        void shouldNotCompleteWithZeroSize() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.startGeneration();
            assertThatThrownBy(() -> doc.markCompleted("p", "u", 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("any -> FAILED via markFailed()")
        void shouldTransitionToFailed() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.markFailed("Something went wrong");

            assertThat(doc.getStatus()).isEqualTo(GenerationStatus.FAILED);
            assertThat(doc.getErrorMessage()).isEqualTo("Something went wrong");
            assertThat(doc.isFailed()).isTrue();
            assertThat(doc.isCompleted()).isFalse();
            assertThat(doc.isSuccessful()).isFalse();
        }

        @Test
        @DisplayName("markFailed from GENERATING")
        void shouldFailFromGenerating() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.startGeneration();
            doc.markFailed("FOP error");
            assertThat(doc.getStatus()).isEqualTo(GenerationStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Retry Tracking")
    class RetryTests {

        @Test
        @DisplayName("incrementRetryCount increments by one")
        void shouldIncrementRetry() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            assertThat(doc.getRetryCount()).isZero();
            doc.incrementRetryCount();
            assertThat(doc.getRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("incrementRetryCountTo sets to target if higher")
        void shouldAdvanceToTarget() {
            CancellationNotePdfDocument doc = defaultBuilder().retryCount(2).build();
            doc.incrementRetryCountTo(5);
            assertThat(doc.getRetryCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("incrementRetryCountTo does not decrease")
        void shouldNotDecrease() {
            CancellationNotePdfDocument doc = defaultBuilder().retryCount(5).build();
            doc.incrementRetryCountTo(3);
            assertThat(doc.getRetryCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("setRetryCount sets exact value")
        void shouldSetExact() {
            CancellationNotePdfDocument doc = defaultBuilder().build();
            doc.setRetryCount(7);
            assertThat(doc.getRetryCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("isMaxRetriesExceeded returns true when equal")
        void maxRetriesEqual() {
            CancellationNotePdfDocument doc = defaultBuilder().retryCount(3).build();
            assertThat(doc.isMaxRetriesExceeded(3)).isTrue();
        }

        @Test
        @DisplayName("isMaxRetriesExceeded returns false when under")
        void maxRetriesUnder() {
            CancellationNotePdfDocument doc = defaultBuilder().retryCount(2).build();
            assertThat(doc.isMaxRetriesExceeded(3)).isFalse();
        }
    }

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("Equal by ID")
        void equalById() {
            UUID id = UUID.randomUUID();
            CancellationNotePdfDocument a = defaultBuilder().id(id).build();
            CancellationNotePdfDocument b = defaultBuilder().id(id).cancellationNoteNumber("DIFFERENT").build();
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Not equal by different ID")
        void notEqualByDifferentId() {
            CancellationNotePdfDocument a = defaultBuilder().build();
            CancellationNotePdfDocument b = defaultBuilder().build();
            assertThat(a).isNotEqualTo(b);
        }
    }
}
