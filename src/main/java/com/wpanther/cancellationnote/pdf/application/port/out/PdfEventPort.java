package com.wpanther.cancellationnote.pdf.application.port.out;

import com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.messaging.CancellationNotePdfGeneratedEvent;

public interface PdfEventPort {
    void publishGenerated(CancellationNotePdfGeneratedEvent event);
}
