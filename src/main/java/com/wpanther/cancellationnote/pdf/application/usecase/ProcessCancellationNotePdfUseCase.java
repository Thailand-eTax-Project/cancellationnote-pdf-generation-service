package com.wpanther.cancellationnote.pdf.application.usecase;

import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteProcessCommand;

public interface ProcessCancellationNotePdfUseCase {
    void handle(KafkaCancellationNoteProcessCommand command);
}
