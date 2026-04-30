package com.wpanther.cancellationnote.pdf.domain.service;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;

public interface CancellationNotePdfGenerationService {

    byte[] generatePdf(String cancellationNoteNumber, String signedXml)
        throws CancellationNotePdfGenerationException;
}
