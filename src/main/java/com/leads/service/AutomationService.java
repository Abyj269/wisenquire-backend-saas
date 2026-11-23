package com.leads.service;

import com.leads.dto.automation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AutomationService {
    Page<AutomationListDto> getAutomations(Long companyId, Long branchId, String status, Pageable pageable);
    AutomationDto getAutomationById(Long id);
    AutomationDto createAutomation(AutomationCreateRequest request, Long currentUserId);
    AutomationDto updateAutomation(Long id, AutomationCreateRequest request);
    AutomationDto publishAutomation(Long id);
    AutomationDto pauseAutomation(Long id);
    AutomationDto duplicateAutomation(Long id, Long currentUserId);
    void deleteAutomation(Long id);
    Page<AutomationExecutionDto> getExecutions(Long automationId, Pageable pageable);
    AutomationExecutionDto getExecution(Long automationId, Long executionId);
    AutomationAnalyticsDto getAnalytics(Long automationId);
}
