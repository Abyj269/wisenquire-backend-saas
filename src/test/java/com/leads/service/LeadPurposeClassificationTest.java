package com.leads.service;

import com.leads.dto.settings.LeadPurposeDto;
import com.leads.model.entity.*;
import com.leads.model.enums.PurposeClassification;
import com.leads.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeadPurposeClassificationTest {
    final LeadPurposeRepository purposes = mock(LeadPurposeRepository.class);
    final CompanyRepository companies = mock(CompanyRepository.class);
    final LeadRepository leads = mock(LeadRepository.class);
    final LeadSettingsService service = new LeadSettingsService(mock(TenantAccess.class), mock(LeadStatusRepository.class), purposes,
        mock(LeadSourceRepository.class), mock(LeadLostReasonRepository.class), mock(CompanyLeadSettingsRepository.class), companies, leads);

    @ParameterizedTest @EnumSource(PurposeClassification.class)
    void savesAndReturnsClassificationWithoutChangingLeadOutcomes(PurposeClassification classification) {
        when(companies.findById(1L)).thenReturn(Optional.of(Company.builder().id(1L).build()));
        when(purposes.save(any())).thenAnswer(i -> i.getArgument(0));
        var result = service.createPurpose(1L, LeadPurposeDto.builder().purposeName("Product inquiry").classification(classification).build());
        assertEquals(classification, result.getClassification());
        verifyNoInteractions(leads);
    }
    @Test void omittedClassificationDefaultsOnCreateAndIsPreservedOnUpdate() {
        when(companies.findById(1L)).thenReturn(Optional.of(Company.builder().id(1L).build()));
        when(purposes.save(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals(PurposeClassification.POTENTIAL, service.createPurpose(1L, LeadPurposeDto.builder().purposeName("Inquiry").build()).getClassification());
        var existing = LeadPurpose.builder().id(2L).classification(PurposeClassification.HIGH_POTENTIAL).build();
        when(purposes.findById(2L)).thenReturn(Optional.of(existing));
        assertEquals(PurposeClassification.HIGH_POTENTIAL, service.updatePurpose(2L, LeadPurposeDto.builder().purposeName("Renamed").sortOrder(0).isActive(true).build()).getClassification());
        assertEquals(PurposeClassification.IRRELEVANT, service.updatePurpose(2L, LeadPurposeDto.builder().purposeName("Outside scope").classification(PurposeClassification.IRRELEVANT).sortOrder(0).isActive(true).build()).getClassification());
        verifyNoInteractions(leads);
    }
}
