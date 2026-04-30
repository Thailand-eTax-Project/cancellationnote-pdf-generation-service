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
