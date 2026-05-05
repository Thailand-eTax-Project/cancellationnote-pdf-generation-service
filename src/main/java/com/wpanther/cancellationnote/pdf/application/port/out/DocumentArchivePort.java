package com.wpanther.cancellationnote.pdf.application.port.out;

import com.wpanther.cancellationnote.pdf.application.dto.event.DocumentArchiveEvent;

/**
 * Port for publishing document archive events to the message broker.
 */
public interface DocumentArchivePort {
    void publish(DocumentArchiveEvent event);
}