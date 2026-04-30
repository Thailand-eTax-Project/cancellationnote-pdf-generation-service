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
