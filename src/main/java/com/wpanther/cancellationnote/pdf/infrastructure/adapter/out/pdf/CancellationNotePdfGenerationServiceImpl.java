package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.pdf;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;
import com.wpanther.cancellationnote.pdf.domain.service.CancellationNotePdfGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

@Service
@Slf4j
public class CancellationNotePdfGenerationServiceImpl implements CancellationNotePdfGenerationService {

    private static final String RSM_NS =
        "urn:etda:uncefact:data:standard:CancellationNote_CrossIndustryInvoice:2";
    private static final String RAM_NS =
        "urn:etda:uncefact:data:standard:CancellationNote_ReusableAggregateBusinessInformationEntity:2";
    private static final String GRAND_TOTAL_XPATH =
        "/rsm:CancellationNote_CrossIndustryInvoice" +
        "/rsm:SupplyChainTradeTransaction" +
        "/ram:ApplicableHeaderTradeSettlement" +
        "/ram:SpecifiedTradeSettlementHeaderMonetarySummation" +
        "/ram:GrandTotalAmount";

    private static final NamespaceContext NS_CONTEXT = new NamespaceContext() {
        @Override
        public String getNamespaceURI(String prefix) {
            return switch (prefix) {
                case "rsm" -> RSM_NS;
                case "ram" -> RAM_NS;
                default    -> XMLConstants.NULL_NS_URI;
            };
        }
        @Override public String getPrefix(String ns) { return null; }
        @Override public Iterator<String> getPrefixes(String ns) { return Collections.emptyIterator(); }
    };

    private final FopCancellationNotePdfGenerator fopPdfGenerator;
    private final PdfA3Converter pdfA3Converter;

    public CancellationNotePdfGenerationServiceImpl(FopCancellationNotePdfGenerator fopPdfGenerator,
                                                    PdfA3Converter pdfA3Converter) {
        this.fopPdfGenerator = fopPdfGenerator;
        this.pdfA3Converter  = pdfA3Converter;
    }

    @Override
    public byte[] generatePdf(String cancellationNoteNumber, String signedXml)
            throws CancellationNotePdfGenerationException {

        log.info("Starting PDF generation for cancellation note: {}", cancellationNoteNumber);

        if (signedXml == null || signedXml.isBlank()) {
            throw new CancellationNotePdfGenerationException(
                "signedXml is null or blank for cancellation note: " + cancellationNoteNumber);
        }

        try {
            BigDecimal grandTotal  = extractGrandTotal(signedXml, cancellationNoteNumber);
            String amountInWords   = ThaiAmountWordsConverter.toWords(grandTotal);
            log.debug("Grand total {} -> amountInWords: {}", grandTotal, amountInWords);

            Map<String, Object> params = Map.of("amountInWords", amountInWords);
            byte[] basePdf = fopPdfGenerator.generatePdf(signedXml, params);
            log.debug("Generated base PDF: {} bytes", basePdf.length);

            String xmlFilename = "cancellationnote-" + cancellationNoteNumber + ".xml";
            byte[] pdfA3 = pdfA3Converter.convertToPdfA3(basePdf, signedXml, xmlFilename, cancellationNoteNumber);
            log.info("Generated PDF/A-3 for cancellation note {}: {} bytes", cancellationNoteNumber, pdfA3.length);
            return pdfA3;

        } catch (CancellationNotePdfGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during PDF generation for cancellation note: {}", cancellationNoteNumber, e);
            throw new CancellationNotePdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    private BigDecimal extractGrandTotal(String signedXml, String cancellationNoteNumber)
            throws CancellationNotePdfGenerationException {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(NS_CONTEXT);
            String value = (String) xpath.evaluate(
                GRAND_TOTAL_XPATH,
                new InputSource(new StringReader(signedXml)),
                XPathConstants.STRING);
            if (value == null || value.isBlank()) {
                throw new CancellationNotePdfGenerationException(
                    "GrandTotalAmount not found in signed XML for cancellation note: " + cancellationNoteNumber);
            }
            return new BigDecimal(value.trim());
        } catch (XPathExpressionException e) {
            throw new CancellationNotePdfGenerationException(
                "Failed to extract GrandTotalAmount from signed XML: " + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new CancellationNotePdfGenerationException(
                "Invalid GrandTotalAmount in signed XML for cancellation note " + cancellationNoteNumber + ": " + e.getMessage(), e);
        }
    }
}
