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
