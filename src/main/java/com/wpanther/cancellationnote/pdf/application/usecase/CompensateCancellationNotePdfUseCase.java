package com.wpanther.cancellationnote.pdf.application.usecase;

import com.wpanther.cancellationnote.pdf.infrastructure.adapter.in.kafka.KafkaCancellationNoteCompensateCommand;

public interface CompensateCancellationNotePdfUseCase {
    void handle(KafkaCancellationNoteCompensateCommand command);
}
