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
