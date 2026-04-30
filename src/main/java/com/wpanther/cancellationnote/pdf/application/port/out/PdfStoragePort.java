package com.wpanther.cancellationnote.pdf.application.port.out;

public interface PdfStoragePort {

    String store(String cancellationNoteNumber, byte[] pdfBytes);

    void delete(String documentPath);

    String resolveUrl(String documentPath);
}
