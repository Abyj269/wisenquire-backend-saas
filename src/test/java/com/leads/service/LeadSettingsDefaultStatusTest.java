package com.leads.service;

import com.leads.dto.settings.CompanyLeadSettingsDto;
import com.leads.model.entity.Company;
import com.leads.model.entity.CompanyLeadSettings;
import com.leads.model.entity.LeadStatus;
import com.leads.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** F-10 (Phase -1 hotfix): the company's default lead status cannot point at another tenant's or company's status. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeadSettingsDefaultStatusTest {
    @Mock TenantAccess access;
    @Mock LeadStatusRepository statusRepo;
    @Mock CompanyLeadSettingsRepository settingsRepo;
    @Mock CompanyRepository companyRepo;
    @InjectMocks LeadSettingsService service;

    final Company companyA = Company.builder().id(10L).build();
    final Company companyB = Company.builder().id(20L).build();

    @BeforeEach void existingSettings() {
        when(settingsRepo.findByCompanyId(10L)).thenReturn(Optional.of(CompanyLeadSettings.builder().id(1L).company(companyA).build()));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    CompanyLeadSettingsDto dto(Long defaultStatusId) {
        var d = new CompanyLeadSettingsDto();
        d.setLinkProductEnabled(false); d.setAutoAssignEnabled(false);
        d.setDefaultStatusId(defaultStatusId);
        return d;
    }

    @Test void foreignTenantStatusIsRejectedAndNothingSaved() {
        when(access.record(LeadStatus.class, 99L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.updateCompanyLeadSettings(10L, dto(99L)));
        verify(settingsRepo, never()).save(any());
        verify(statusRepo, never()).findById(any());
    }

    @Test void statusOfAnotherCompanyInSameOrgIsRejected() {
        var other = LeadStatus.builder().id(50L).company(companyB).build();
        when(access.record(LeadStatus.class, 50L)).thenReturn(other);
        doThrow(new IllegalArgumentException("Linked record belongs to another company.")).when(access).sameCompany(10L, companyB);
        assertThrows(IllegalArgumentException.class, () -> service.updateCompanyLeadSettings(10L, dto(50L)));
        verify(settingsRepo, never()).save(any());
    }

    @Test void ownStatusIsAccepted() {
        var own = LeadStatus.builder().id(30L).company(companyA).statusName("New").build();
        when(access.record(LeadStatus.class, 30L)).thenReturn(own);
        service.updateCompanyLeadSettings(10L, dto(30L));
        verify(access).sameCompany(10L, companyA);
        verify(settingsRepo).save(argThat(s -> s.getDefaultStatus() == own));
    }
}
