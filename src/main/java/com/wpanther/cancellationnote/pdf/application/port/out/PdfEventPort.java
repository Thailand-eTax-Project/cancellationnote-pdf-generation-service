package com.wpanther.cancellationnote.pdf.application.port.out;

import com.wpanther.cancellationnote.pdf.application.dto.event.CancellationNotePdfGeneratedEvent;

public interface PdfEventPort {
    void publishGenerated(CancellationNotePdfGeneratedEvent event);
}
