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
