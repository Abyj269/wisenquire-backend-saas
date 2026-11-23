package com.leads.controller;

import com.leads.dto.quotation.QuotationDto;
import com.leads.dto.quotation.QuotationRequest;
import com.leads.model.enums.QuotationStatus;
import com.leads.service.QuotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/quotations")
@RequiredArgsConstructor
@Tag(name = "Quotations", description = "Basic price-quote module against leads — line items, PDF generation, and sending straight into the lead's WhatsApp chat")
public class QuotationController {

    private final QuotationService quotationService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:ADD')")
    @Operation(summary = "Create Quotation", description = "Creates a quotation against a lead with its line items; totals are always recomputed server-side.")
    public ResponseEntity<QuotationDto> createQuotation(@Valid @RequestBody QuotationRequest req) {
        return ResponseEntity.ok(quotationService.createQuotation(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:EDIT')")
    @Operation(summary = "Update Quotation", description = "Replaces a quotation's buyer details and line items; totals are recomputed server-side.")
    public ResponseEntity<QuotationDto> updateQuotation(@PathVariable Long id, @Valid @RequestBody QuotationRequest req) {
        return ResponseEntity.ok(quotationService.updateQuotation(id, req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:VIEW')")
    @Operation(summary = "Get Quotation by ID")
    public ResponseEntity<QuotationDto> getQuotation(@PathVariable Long id) {
        return ResponseEntity.ok(quotationService.getQuotation(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:VIEW')")
    @Operation(summary = "List Quotations", description = "Paginated quotation list, optionally filtered by lead, status, a keyword (quote #/lead #/customer name), and a created-date range.")
    public ResponseEntity<Page<QuotationDto>> listQuotations(
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long leadId,
            @RequestParam(required = false) QuotationStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate fromDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Sort sort = Sort.by("createdAt").descending();
        return ResponseEntity.ok(quotationService.listQuotations(companyId, leadId, status, search, fromDate, toDate, PageRequest.of(page, size, sort)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:DELETE')")
    @Operation(summary = "Delete Quotation")
    public ResponseEntity<Void> deleteQuotation(@PathVariable Long id) {
        quotationService.deleteQuotation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:VIEW')")
    @Operation(summary = "Download Quotation PDF")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        byte[] pdf = quotationService.renderPdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("inline", "Quotation-" + id + ".pdf");
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    @PostMapping("/{id}/send-whatsapp")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('QUOTATIONS:EDIT')")
    @Operation(summary = "Send Quotation via WhatsApp", description = "Renders the quotation as a PDF and sends it as a document message into the lead's WhatsApp conversation, marking the quotation Sent.")
    public ResponseEntity<QuotationDto> sendViaWhatsApp(@PathVariable Long id, @RequestBody(required = false) SendWhatsAppRequest req) {
        Long conversationId = req != null ? req.getConversationId() : null;
        return ResponseEntity.ok(quotationService.sendViaWhatsApp(id, conversationId));
    }

    @lombok.Getter
    @lombok.Setter
    public static class SendWhatsAppRequest {
        private Long conversationId;
    }
}
