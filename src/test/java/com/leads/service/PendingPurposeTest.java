package com.leads.service;

import com.leads.dto.lead.LeadCreateRequest;
import com.leads.model.entity.Lead;
import com.leads.repository.LeadRepository;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PendingPurposeTest {
    @Mock TenantAccess access;
    @Mock LeadRepository leads;
    @InjectMocks LeadService service;
    @Captor ArgumentCaptor<Specification<Lead>> specification;

    @Test void inboundChatCannotInventAPurposeWhileManualCreationRetainsValidation() {
        var spy = spy(service);
        var request = LeadCreateRequest.builder().purposeId(99L).build();
        doReturn(null).when(spy).createLeadInternal(request, true);
        spy.createLeadFromInboundChat(request);
        assertNull(request.getPurposeId());
        verify(spy).createLeadInternal(request, true);
        request.setPurposeId(99L);
        doReturn(null).when(spy).createLeadInternal(request, false);
        spy.createLead(request);
        assertEquals(99L, request.getPurposeId());
        verify(spy).createLeadInternal(request, false);
    }

    @SuppressWarnings("unchecked")
    @Test void dashboardBacklogIncludesOlderLeadsAndKeepsCompanyBranchScope() {
        when(leads.findAll(any(Specification.class))).thenReturn(List.of());
        when(leads.count(any(Specification.class))).thenReturn(7L);
        var result = service.getCompanyMetrics(1L, 2L, LocalDate.of(2026,9,1), LocalDate.of(2026,9,17));
        assertEquals(0, result.getTotalLeads());
        assertEquals(7, result.getPendingPurposeLeads());
        verify(leads).count(specification.capture());
        Root<Lead> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        specification.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);
        verify(cb).equal(root.get("company").get("id"), 1L);
        verify(cb).equal(root.get("branch").get("id"), 2L);
        verify(cb).isNull(root.get("purpose"));
        verify(root, never()).get("createdAt");
        verify(access, times(2)).scope(1L, 2L);
    }
}
