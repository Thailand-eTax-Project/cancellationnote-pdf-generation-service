package com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class KafkaCommandMapperTest {

    private final KafkaCommandMapper mapper = new KafkaCommandMapper();

    @Test
    void toProcess_returnsSameInstance() {
        KafkaCancellationNoteProcessCommand cmd = new KafkaCancellationNoteProcessCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr", "doc-1", "CN-001", "http://url");
        assertThat(mapper.toProcess(cmd)).isSameAs(cmd);
    }

    @Test
    void toCompensate_returnsSameInstance() {
        KafkaCancellationNoteCompensateCommand cmd = new KafkaCancellationNoteCompensateCommand(
                "saga-1", SagaStep.GENERATE_CANCELLATION_NOTE_PDF, "corr", "doc-1");
        assertThat(mapper.toCompensate(cmd)).isSameAs(cmd);
    }
}
