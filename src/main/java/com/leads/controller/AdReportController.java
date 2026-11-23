package com.leads.controller;

import com.leads.dto.reports.AdPerformanceReportDto;
import com.leads.service.AdReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Kept separate from ReportsController rather than folding in — the per-ad/per-campaign query shape (and its two ad-repository collaborators) doesn't fit that class's existing lead-only report methods. */
@RestController
@RequestMapping("/reports/ads")
@RequiredArgsConstructor
@Tag(name = "Ad Performance Report", description = "Real spend, leads, conversions and ROAS per ad and per campaign — replaces the old WhatsApp Campaigns mock.")
public class AdReportController {

    private final AdReportService adReportService;

    @GetMapping("/performance")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Ad Performance Report", description = "Per-ad and per-campaign spend, leads, conversion rate, cost-per-lead and ROAS for the given date range/branch scope.")
    public ResponseEntity<AdPerformanceReportDto> getAdPerformanceReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(adReportService.getAdPerformanceReport(companyId, branchId, fromDate, toDate));
    }
}
