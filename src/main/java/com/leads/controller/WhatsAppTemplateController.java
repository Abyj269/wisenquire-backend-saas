package com.leads.controller;

import com.leads.dto.whatsapp.WhatsAppTemplateCreateRequest;
import com.leads.dto.whatsapp.WhatsAppTemplateDto;
import com.leads.dto.whatsapp.WhatsAppTemplateUsageDto;
import com.leads.service.WhatsAppTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/whatsapp/templates")
@RequiredArgsConstructor
@Tag(name = "WhatsApp Message Templates", description = "Create, list, and delete Meta-approved WhatsApp message templates per branch")
public class WhatsAppTemplateController {

    private final WhatsAppTemplateService templateService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_TEMPLATES:VIEW')")
    @Operation(summary = "List a connected number's WhatsApp message templates (live from Meta)")
    public ResponseEntity<List<WhatsAppTemplateDto>> list(@RequestParam Long configId) {
        return ResponseEntity.ok(templateService.listTemplates(configId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_TEMPLATES:ADD')")
    @Operation(summary = "Submit a new WhatsApp message template for Meta approval")
    public ResponseEntity<WhatsAppTemplateDto> create(@Valid @RequestBody WhatsAppTemplateCreateRequest req) {
        return ResponseEntity.ok(templateService.createTemplate(req));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_TEMPLATES:EDIT')")
    @Operation(summary = "Edit an existing template's content and resubmit it for review — the name and language can't be changed this way, create a new template for that")
    public ResponseEntity<WhatsAppTemplateDto> update(@PathVariable String templateId, @Valid @RequestBody WhatsAppTemplateCreateRequest req) {
        return ResponseEntity.ok(templateService.updateTemplate(templateId, req));
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_TEMPLATES:DELETE')")
    @Operation(summary = "Delete a WhatsApp message template")
    public ResponseEntity<Void> delete(@PathVariable String name, @RequestParam Long configId) {
        templateService.deleteTemplate(configId, name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/media", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_TEMPLATES:ADD')")
    @Operation(summary = "Upload sample media for a template Image/Video/Document header or a Carousel card, returning its header_handle")
    public ResponseEntity<Map<String, String>> uploadMedia(@RequestParam Long configId, @RequestParam MultipartFile file) throws IOException {
        String handle = templateService.uploadHeaderMedia(configId, file.getBytes(), file.getContentType(), file.getOriginalFilename());
        return ResponseEntity.ok(Map.of("handle", handle));
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_TEMPLATES:VIEW')")
    @Operation(summary = "Local per-template send counts for a connected number, optionally scoped to a date range (yyyy-MM-dd). Every template is still listed by GET /whatsapp/templates regardless of this range — it only affects the counts here.")
    public ResponseEntity<List<WhatsAppTemplateUsageDto>> usage(
            @RequestParam Long configId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDateTime fromDate = from != null && !from.isBlank() ? LocalDate.parse(from).atStartOfDay() : null;
        LocalDateTime toDate = to != null && !to.isBlank() ? LocalDate.parse(to).plusDays(1).atStartOfDay() : null;
        return ResponseEntity.ok(templateService.getUsage(configId, fromDate, toDate));
    }
}
