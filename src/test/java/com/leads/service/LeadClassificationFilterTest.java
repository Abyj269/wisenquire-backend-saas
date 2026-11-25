package com.leads.service;

import com.leads.model.entity.Lead;
import com.leads.model.enums.PurposeClassification;
import com.leads.repository.LeadRepository;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeadClassificationFilterTest {
    @org.junit.jupiter.api.Test
    @SuppressWarnings("unchecked")
    void missingPurposeIsFilteredBeforePaginationWithCompanyAndBranchScope() {
        when(leads.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        service.getLeads(1L, 2L, null, null, null, null, null, null, null, null, true, PageRequest.of(0, 15));
        verify(access).scope(1L, 2L);
        verify(leads).findAll(specification.capture(), any(Pageable.class));
        Root<Lead> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        specification.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);
        verify(cb).equal(root.get("company").get("id"), 1L);
        verify(cb).equal(root.get("branch").get("id"), 2L);
        verify(cb).isNull(root.get("purpose"));
        verify(root, never()).get("createdAt");
        verify(root, never()).get("assignedTo");
    }
    @Mock TenantAccess access;
    @Mock LeadRepository leads;
    /** Module 06: date bounds go through the company clock (a real one; the server zone). */
    @org.mockito.Spy CompanyClock companyClock = new CompanyClock(java.time.Clock.system(CompanyClock.SERVER_ZONE));
    @InjectMocks LeadService service;
    @Captor ArgumentCaptor<Specification<Lead>> specification;

    @SuppressWarnings("unchecked")
    @ParameterizedTest @EnumSource(PurposeClassification.class)
    void classificationIsAppliedAlongsideTenantAndExistingFiltersBeforePagination(PurposeClassification classification) {
        when(leads.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        service.getLeads(1L, 2L, 3L, 4L, null, null, null, false, classification, null, null, PageRequest.of(0, 15));
        verify(access).scope(1L, 2L);
        verify(leads).findAll(specification.capture(), any(Pageable.class));
        Root<Lead> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        specification.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);
        verify(cb).equal(root.get("company").get("id"), 1L);
        verify(cb).equal(root.get("branch").get("id"), 2L);
        verify(cb).equal(root.get("status").get("id"), 3L);
        verify(cb).equal(root.get("source").get("id"), 4L);
        verify(cb).equal(root.get("purpose").get("classification"), classification);
    }
}
