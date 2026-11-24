package com.leads.service;

import com.leads.model.entity.*;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders a real quotation PDF (Thymeleaf + openhtmltopdf + pdfbox). Added in Phase 0 when
 * commons-logging was excluded from pdfbox: pdfbox must still work with Spring's spring-jcl
 * providing the org.apache.commons.logging API. Until now, the PDF was only ever mocked in tests.
 */
class QuotationPdfRenderTest {

    @Test void rendersAPdfWithPdfboxLoggingThroughSpringJcl() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        var company = Company.builder().id(1L).name("Test Co").build();
        var branch = Branch.builder().id(2L).company(company).address("Street 1").phone("+971000000").build();
        var lead = Lead.builder().id(3L).leadNumber("LD-1-2026-000001").branch(branch).company(company).build();
        var quotation = Quotation.builder()
                .id(4L).quotationNumber("QT-1-2026-000001").company(company).lead(lead)
                .customerName("Customer").currency("AED")
                .subtotal(BigDecimal.TEN).totalDiscount(BigDecimal.ZERO).totalTax(BigDecimal.ZERO).grandTotal(BigDecimal.TEN)
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.of(2026, 11, 24, 10, 0))
                .build();

        byte[] pdf = new QuotationPdfService(engine).render(quotation, null);

        assertTrue(pdf.length > 500, "PDF should not be empty");
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test void commonsLoggingApiComesFromSpringJcl() throws Exception {
        // The org.apache.commons.logging API pdfbox uses must be Spring's spring-jcl, not commons-logging.jar.
        var location = Class.forName("org.apache.commons.logging.LogFactory")
                .getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(location.contains("spring-jcl"), "LogFactory loaded from " + location);
    }
}
