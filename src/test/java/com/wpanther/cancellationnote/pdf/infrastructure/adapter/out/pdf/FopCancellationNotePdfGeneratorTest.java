package com.wpanther.cancellationnote.pdf.infrastructure.adapter.out.pdf;

import com.wpanther.cancellationnote.pdf.domain.exception.CancellationNotePdfGenerationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FopCancellationNotePdfGenerator Unit Tests")
class FopCancellationNotePdfGeneratorTest {

    // Minimal signed XML accepted by cancellationnote-direct.xsl
    private static final String MINIMAL_SIGNED_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<rsm:CancellationNote_CrossIndustryInvoice " +
        "    xmlns:ram=\"urn:etda:uncefact:data:standard:CancellationNote_ReusableAggregateBusinessInformationEntity:2\"" +
        "    xmlns:rsm=\"urn:etda:uncefact:data:standard:CancellationNote_CrossIndustryInvoice:2\">" +
        "  <rsm:ExchangedDocument>" +
        "    <ram:ID>CN-TEST-001</ram:ID>" +
        "    <ram:Name>ใบแจ้งยกเลิก</ram:Name>" +
        "    <ram:IssueDateTime>2024-01-15T00:00:00.0</ram:IssueDateTime>" +
        "  </rsm:ExchangedDocument>" +
        "  <rsm:SupplyChainTradeTransaction>" +
        "    <ram:ApplicableHeaderTradeAgreement>" +
        "      <ram:SellerTradeParty>" +
        "        <ram:Name>บริษัท ทดสอบ จำกัด</ram:Name>" +
        "        <ram:SpecifiedTaxRegistration><ram:ID>1234567890123</ram:ID></ram:SpecifiedTaxRegistration>" +
        "      </ram:SellerTradeParty>" +
        "      <ram:BuyerTradeParty>" +
        "        <ram:Name>ผู้ซื้อ</ram:Name>" +
        "        <ram:SpecifiedTaxRegistration><ram:ID>9876543210987</ram:ID></ram:SpecifiedTaxRegistration>" +
        "      </ram:BuyerTradeParty>" +
        "    </ram:ApplicableHeaderTradeAgreement>" +
        "    <ram:ApplicableHeaderTradeDelivery/>" +
        "    <ram:ApplicableHeaderTradeSettlement>" +
        "      <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>" +
        "      <ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CalculatedRate>7</ram:CalculatedRate></ram:ApplicableTradeTax>" +
        "      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
        "        <ram:LineTotalAmount>1000</ram:LineTotalAmount>" +
        "        <ram:AllowanceTotalAmount>0</ram:AllowanceTotalAmount>" +
        "        <ram:TaxBasisTotalAmount>1000</ram:TaxBasisTotalAmount>" +
        "        <ram:TaxTotalAmount>70</ram:TaxTotalAmount>" +
        "        <ram:GrandTotalAmount>1070</ram:GrandTotalAmount>" +
        "      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
        "    </ram:ApplicableHeaderTradeSettlement>" +
        "    <ram:IncludedSupplyChainTradeLineItem>" +
        "      <ram:AssociatedDocumentLineDocument><ram:LineID>1</ram:LineID></ram:AssociatedDocumentLineDocument>" +
        "      <ram:SpecifiedTradeProduct><ram:ID>P001</ram:ID><ram:Name>สินค้าทดสอบ</ram:Name></ram:SpecifiedTradeProduct>" +
        "      <ram:SpecifiedLineTradeAgreement>" +
        "        <ram:GrossPriceProductTradePrice><ram:ChargeAmount>1000</ram:ChargeAmount></ram:GrossPriceProductTradePrice>" +
        "      </ram:SpecifiedLineTradeAgreement>" +
        "      <ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode=\"PIECE\">1</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>" +
        "      <ram:SpecifiedLineTradeSettlement>" +
        "        <ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CalculatedRate>7</ram:CalculatedRate></ram:ApplicableTradeTax>" +
        "        <ram:SpecifiedTradeSettlementLineMonetarySummation><ram:NetLineTotalAmount>1000</ram:NetLineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>" +
        "      </ram:SpecifiedLineTradeSettlement>" +
        "    </ram:IncludedSupplyChainTradeLineItem>" +
        "  </rsm:SupplyChainTradeTransaction>" +
        "</rsm:CancellationNote_CrossIndustryInvoice>";

    @Test
    @DisplayName("Constructor succeeds and compiles XSL template")
    void constructor_compilesTemplateSuccessfully() {
        assertThatCode(() -> new FopCancellationNotePdfGenerator(2, 52428800L, new SimpleMeterRegistry()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor rejects maxConcurrentRenders < 1")
    void constructor_invalidMaxConcurrentRenders_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopCancellationNotePdfGenerator(0, 52428800L, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-renders");
    }

    @Test
    @DisplayName("Constructor rejects maxPdfSizeBytes < 1")
    void constructor_invalidMaxPdfSizeBytes_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopCancellationNotePdfGenerator(1, 0L, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-pdf-size-bytes");
    }

    @Test
    @DisplayName("Semaphore is initialised with the configured permit count")
    void constructor_semaphorePermitsMatchConfiguration() throws Exception {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(5, 52428800L, new SimpleMeterRegistry());
        Field f = FopCancellationNotePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore s = (Semaphore) f.get(gen);
        assertThat(s.availablePermits()).isEqualTo(5);
        assertThat(s.isFair()).isTrue();
    }

    @Test
    @DisplayName("checkFontAvailability() does not throw")
    void checkFontAvailability_doesNotThrow() {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        assertThatCode(() -> gen.checkFontAvailability()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("generatePdf(xml) on interrupted thread throws CancellationNotePdfGenerationException")
    void generatePdf_threadAlreadyInterrupted_throwsCancellationNotePdfGenerationException() {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gen.generatePdf(MINIMAL_SIGNED_XML))
                    .isInstanceOf(CancellationNotePdfGenerationException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Semaphore blocks callers when all permits are held")
    void generatePdf_semaphoreBlocksWhenAtCapacity() throws Exception {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        Field f = FopCancellationNotePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore sem = (Semaphore) f.get(gen);
        sem.acquire();

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = exec.submit(() -> {
                try { gen.generatePdf(MINIMAL_SIGNED_XML); }
                catch (CancellationNotePdfGenerationException ignored) {}
            });
            assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            sem.release();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("resolveBaseUri() returns a non-null absolute URI ending with '/'")
    void resolveBaseUri_returnsValidUri() throws Exception {
        FopCancellationNotePdfGenerator generator = new FopCancellationNotePdfGenerator(2, 52428800L, new SimpleMeterRegistry());
        Method method = FopCancellationNotePdfGenerator.class.getDeclaredMethod("resolveBaseUri");
        method.setAccessible(true);
        URI uri = (URI) method.invoke(generator);
        assertThat(uri).isNotNull();
        assertThat(uri.isAbsolute()).isTrue();
        assertThat(uri.toString()).endsWith("/");
    }

    @Test
    @DisplayName("Valid signed XML returns non-empty PDF bytes starting with %PDF")
    void generatePdf_validSignedXml_returnsPdfBytes() throws Exception {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 52428800L, new SimpleMeterRegistry());

        byte[] result = gen.generatePdf(MINIMAL_SIGNED_XML, Map.of("amountInWords", "หนึ่งพันเจ็ดสิบบาทถ้วน"));

        assertThat(result).isNotEmpty();
        assertThat(new String(result, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    @Test
    @DisplayName("generatePdf(xml) no-arg overload delegates successfully")
    void generatePdf_noParams_delegatesToParamsOverload() throws Exception {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        byte[] result = gen.generatePdf(MINIMAL_SIGNED_XML);
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("Malformed XML throws CancellationNotePdfGenerationException")
    void generatePdf_malformedXml_throwsCancellationNotePdfGenerationException() {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        assertThatThrownBy(() -> gen.generatePdf("this is not xml <<<"))
                .isInstanceOf(CancellationNotePdfGenerationException.class);
    }

    @Test
    @DisplayName("PDF exceeding max size throws CancellationNotePdfGenerationException")
    void generatePdf_pdfExceedsMaxSize_throwsCancellationNotePdfGenerationException() {
        FopCancellationNotePdfGenerator gen = new FopCancellationNotePdfGenerator(1, 1L, new SimpleMeterRegistry());
        assertThatThrownBy(() -> gen.generatePdf(MINIMAL_SIGNED_XML, null))
                .isInstanceOf(CancellationNotePdfGenerationException.class)
                .hasMessageContaining("exceeds max allowed size");
    }
}
