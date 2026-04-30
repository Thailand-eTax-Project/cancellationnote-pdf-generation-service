package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.cancellationnote.pdf.domain.model.CancellationNotePdfDocument;
import com.wpanther.cancellationnote.pdf.domain.model.GenerationStatus;
import com.wpanther.cancellationnote.pdf.domain.repository.CancellationNotePdfDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(CancellationNotePdfDocumentRepositoryAdapter.class)
class JpaCancellationNotePdfDocumentRepositoryImplTest {

    @Autowired
    private CancellationNotePdfDocumentRepository repository;

    @Test
    void saveAndFindById() {
        CancellationNotePdfDocument doc = CancellationNotePdfDocument.builder()
                .cancellationNoteId("cnl-001")
                .cancellationNoteNumber("CNL-2024-001")
                .build();
        CancellationNotePdfDocument saved = repository.save(doc);

        Optional<CancellationNotePdfDocument> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCancellationNoteId()).isEqualTo("cnl-001");
        assertThat(found.get().getStatus()).isEqualTo(GenerationStatus.PENDING);
    }

    @Test
    void findByCancellationNoteId_found() {
        repository.save(CancellationNotePdfDocument.builder()
                .cancellationNoteId("cnl-002")
                .cancellationNoteNumber("CNL-2024-002")
                .build());

        Optional<CancellationNotePdfDocument> found = repository.findByCancellationNoteId("cnl-002");
        assertThat(found).isPresent();
        assertThat(found.get().getCancellationNoteNumber()).isEqualTo("CNL-2024-002");
    }

    @Test
    void findByCancellationNoteId_notFound() {
        assertThat(repository.findByCancellationNoteId("nonexistent")).isEmpty();
    }

    @Test
    void deleteById_removesDocument() {
        CancellationNotePdfDocument doc = repository.save(
                CancellationNotePdfDocument.builder()
                        .cancellationNoteId("cnl-003")
                        .cancellationNoteNumber("CNL-2024-003")
                        .build());
        repository.deleteById(doc.getId());
        assertThat(repository.findById(doc.getId())).isEmpty();
    }
}