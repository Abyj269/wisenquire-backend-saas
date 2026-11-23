package com.leads.controller;

import com.leads.dto.reports.*;
import com.leads.service.ReportsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@Tag(name = "Reports & Analytics", description = "Owner/decision-maker facing aggregate reports across customers, staff, lead sources, branches, response time, and pipeline value.")
public class ReportsController {

    private final ReportsService reportsService;

    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Customer Acquisition & Retention Report", description = "New customers acquired, existing customers regained, and lost customers, per the company's configurable lost-after-days threshold.")
    public ResponseEntity<CustomerReportDto> getCustomerReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportsService.getCustomerReport(companyId, branchId, fromDate, toDate));
    }

    @GetMapping("/staff-performance")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Staff Performance Report", description = "Per-agent leads handled, conversion rate, response time, WhatsApp activity, and a composite performance score.")
    public ResponseEntity<StaffPerformanceReportDto> getStaffPerformanceReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportsService.getStaffPerformanceReport(companyId, branchId, fromDate, toDate));
    }

    @GetMapping("/lead-sources")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Lead Source ROI Report", description = "Leads, conversions, conversion rate, and revenue per lead source.")
    public ResponseEntity<List<LeadSourceRoiDto>> getLeadSourceRoiReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportsService.getLeadSourceRoiReport(companyId, branchId, fromDate, toDate));
    }

    @GetMapping("/branches")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Branch Comparison Report", description = "Compares every branch in the company side by side; branch scoping intentionally doesn't apply to this one report.")
    public ResponseEntity<List<BranchComparisonDto>> getBranchComparisonReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportsService.getBranchComparisonReport(companyId, fromDate, toDate));
    }

    @GetMapping("/response-time")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Response Time & SLA Report", description = "First-response speed per agent against a fixed SLA benchmark, approximated from the lead activity timeline.")
    public ResponseEntity<ResponseTimeReportDto> getResponseTimeReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportsService.getResponseTimeReport(companyId, branchId, fromDate, toDate));
    }

    @GetMapping("/revenue-forecast")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('REPORTS_ANALYTICS:VIEW')")
    @Operation(summary = "Revenue & Pipeline Forecast Report", description = "Pipeline value by stage plus a simple forecast: open pipeline value weighted by the period's overall conversion rate.")
    public ResponseEntity<RevenueForecastDto> getRevenueForecastReport(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ResponseEntity.ok(reportsService.getRevenueForecastReport(companyId, branchId, fromDate, toDate));
    }
}
