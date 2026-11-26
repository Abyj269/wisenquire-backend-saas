package com.leads.service;

import com.leads.model.entity.Branch;
import com.leads.model.entity.Quotation;
import com.leads.model.entity.User;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a Quotation to PDF: the entity is fed into a Thymeleaf template
 * (src/main/resources/templates/quotation-pdf.html) to produce HTML, which
 * openhtmltopdf then lays out and rasterizes to a PDF byte stream. The
 * TemplateEngine bean here is the one spring-boot-starter-thymeleaf
 * auto-configures (classpath:/templates/, .html suffix) — used standalone,
 * not through Spring MVC view resolution, so it's safe to have alongside a
 * REST-only API.
 */
@Service
@RequiredArgsConstructor
public class QuotationPdfService {

    private final TemplateEngine templateEngine;
    private final CompanyClock companyClock;

    /**
     * Module 08 P1 follow-up: a logo saved before save-time validation existed may be invalid. The PDF
     * then prints without a letterhead logo (and logs it) instead of failing with a 400.
     */
    static String printableLogo(String logo) {
        if (logo == null || logo.isBlank()) return null;
        try {
            com.leads.util.InputRules.logo(logo);
            return logo;
        } catch (IllegalArgumentException e) {
            org.slf4j.LoggerFactory.getLogger(QuotationPdfService.class).warn("Quotation PDF: stored logo is not a valid PNG/JPEG data URI; printing without it");
            return null;
        }
    }

    /** D15 (module 08 P1): "Printed at" is the company's local time (was the server clock). */
    String printedAtText(com.leads.model.entity.Company company) {
        return DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", java.util.Locale.ENGLISH).format(companyClock.now(company));
    }

    /** @param printedBy the user actually generating this PDF right now (download, or the sender for a WhatsApp send) — stamped on the footer, independent of who originally created the quotation. */
    public byte[] render(Quotation quotation, User printedBy) {
        Branch branch = quotation.getLead().getBranch();

        Context context = new Context();
        context.setVariable("company", quotation.getCompany());
        context.setVariable("quotation", quotation);
        context.setVariable("items", quotation.getItems());
        String logo = printableLogo(firstNonBlank(branch.getLogoUrl(), quotation.getCompany().getLogoUrl()));
        context.setVariable("letterheadLogoUrl", logo);
        context.setVariable("letterheadTrnNumber", firstNonBlank(branch.getTrnNumber(), quotation.getCompany().getTaxNumber()));
        context.setVariable("branchAddress", branch.getAddress());
        context.setVariable("branchPhone", branch.getPhone());
        context.setVariable("printedByName", printedBy != null ? printedBy.getFullName() : "System");
        context.setVariable("printedAt", printedAtText(quotation.getCompany()));
        context.setVariable("termsLines", splitLines(quotation.getTermsAndConditions()));
        // Formatted here rather than via Thymeleaf's #temporals in the template —
        // that utility needs the separate thymeleaf-extras-java8time dependency
        // and a registered dialect bean, neither of which is on the classpath.
        context.setVariable("createdAtFormatted", quotation.getCreatedAt() != null
                ? DateTimeFormatter.ofPattern("dd MMM yyyy").format(quotation.getCreatedAt())
                : "");

        String html = templateEngine.process("quotation-pdf", context);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate the quotation PDF.", e);
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    /** One bullet per non-blank line — handles both \n and \r\n so it doesn't matter which the browser's textarea sent. */
    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
