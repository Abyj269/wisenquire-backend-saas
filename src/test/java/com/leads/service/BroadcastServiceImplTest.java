package com.leads.service;

import com.leads.dto.broadcast.BroadcastCreateRequest;
import com.leads.model.entity.*;
import com.leads.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** F-1 (Phase -1 hotfix): every Broadcast entry point must pass TenantAccess before touching data. */
@ExtendWith(MockitoExtension.class)
class BroadcastServiceImplTest {
    @Mock BroadcastRepository broadcastRepo;
    @Mock BroadcastRecipientRepository recipientRepo;
    @Mock CompanyRepository companyRepo;
    @Mock BranchRepository branchRepo;
    @Mock WhatsAppConfigurationRepository configRepo;
    @Mock CustomerRepository customerRepo;
    @Mock LeadRepository leadRepo;
    @Mock TenantAccess access;
    @Mock AuthService authService;
    @Mock org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor broadcastExecutor;
    @InjectMocks BroadcastServiceImpl service;

    /** Module 16 part 2: a LEVEL caller (a mocked Long would otherwise be 0, i.e. "OWN user 0"). */
    @org.junit.jupiter.api.BeforeEach
    void levelCaller() { lenient().when(access.ownerFilter()).thenReturn(null); }

    final Pageable page = PageRequest.of(0, 20);
    final Company companyA = Company.builder().id(10L).build();
    final Company companyB = Company.builder().id(20L).build();
    final Branch branchA1 = Branch.builder().id(11L).company(companyA).build();
    final Branch branchA2 = Branch.builder().id(12L).company(companyA).build();

    BroadcastCreateRequest request(Long companyId, Long branchId, Long configId) {
        var r = new BroadcastCreateRequest();
        r.setCompanyId(companyId); r.setBranchId(branchId); r.setWhatsappConfigurationId(configId);
        r.setName("Promo"); r.setTemplateName("promo");
        return r;
    }

    void assertNoDataTouched() {
        verify(broadcastRepo, never()).save(any());
        verify(recipientRepo, never()).saveAll(any());
        verifyNoInteractions(broadcastExecutor);
    }

    // ---- create ----
    @Test void createRejectsCompanyOutsideScope() {
        when(access.company(20L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.createBroadcast(request(20L, null, 5L)));
        verify(access, never()).record(eq(WhatsAppConfiguration.class), any());
        assertNoDataTouched();
    }

    @Test void createRejectsWhatsAppNumberOutsideScope() {
        when(access.company(10L)).thenReturn(companyA);
        when(access.record(WhatsAppConfiguration.class, 99L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.createBroadcast(request(10L, null, 99L)));
        assertNoDataTouched();
    }

    @Test void createRejectsWhatsAppNumberOfAnotherCompany() {
        when(access.company(10L)).thenReturn(companyA);
        var foreign = WhatsAppConfiguration.builder().id(5L).company(companyB)
                .branch(Branch.builder().id(21L).company(companyB).build()).build();
        when(access.record(WhatsAppConfiguration.class, 5L)).thenReturn(foreign);
        assertThrows(AccessDeniedException.class, () -> service.createBroadcast(request(10L, null, 5L)));
        assertNoDataTouched();
    }

    @Test void createRejectsWhatsAppNumberOfAnotherBranch() {
        when(access.company(10L)).thenReturn(companyA);
        when(access.branch(11L)).thenReturn(branchA1);
        var otherBranchCfg = WhatsAppConfiguration.builder().id(5L).company(companyA).branch(branchA2).build();
        when(access.record(WhatsAppConfiguration.class, 5L)).thenReturn(otherBranchCfg);
        assertThrows(AccessDeniedException.class, () -> service.createBroadcast(request(10L, 11L, 5L)));
        assertNoDataTouched();
    }

    @Test void createWithoutBranchRequiresCompanyWideScope() {
        when(access.company(10L)).thenReturn(companyA);
        doThrow(new AccessDeniedException("branch user")).when(access).companyWide(10L);
        assertThrows(AccessDeniedException.class, () -> service.createBroadcast(request(10L, null, 5L)));
        assertNoDataTouched();
    }

    @Test void createRejectsBranchOfAnotherCompany() {
        when(access.company(10L)).thenReturn(companyA);
        var branchB = Branch.builder().id(21L).company(companyB).build();
        when(access.branch(21L)).thenReturn(branchB);
        doThrow(new IllegalArgumentException("other company")).when(access).sameCompany(10L, companyB);
        assertThrows(IllegalArgumentException.class, () -> service.createBroadcast(request(10L, 21L, 5L)));
        assertNoDataTouched();
    }

    // ---- read / send / delete by id ----
    @Test void getByIdChecksScopeBeforeLoading() {
        when(access.record(Broadcast.class, 7L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.getBroadcastById(7L));
        verify(broadcastRepo, never()).findById(any());
    }

    @Test void sendChecksScopeBeforeLoadingOrQueueing() {
        when(access.record(Broadcast.class, 7L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.sendBroadcast(7L));
        verify(broadcastRepo, never()).findById(any());
        assertNoDataTouched();
    }

    @Test void deleteChecksScopeBeforeLoading() {
        when(access.record(Broadcast.class, 7L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.deleteBroadcast(7L));
        verify(broadcastRepo, never()).findById(any());
        verify(broadcastRepo, never()).save(any());
    }

    @Test void logsCheckScopeBeforeReadingRecipients() {
        when(access.record(Broadcast.class, 7L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.getBroadcastLogs(7L, page));
        verifyNoInteractions(recipientRepo);
    }

    @Test void logsOfDeletedBroadcastAreNotReturned() {
        var deleted = Broadcast.builder().id(7L).company(companyA).deletedAt(LocalDateTime.now()).build();
        when(access.record(Broadcast.class, 7L)).thenReturn(deleted);
        assertThrows(IllegalArgumentException.class, () -> service.getBroadcastLogs(7L, page));
        verifyNoInteractions(recipientRepo);
    }

    @Test void logsOfPermittedBroadcastAreReturned() {
        when(access.record(Broadcast.class, 7L)).thenReturn(Broadcast.builder().id(7L).company(companyA).build());
        when(recipientRepo.findByBroadcastId(7L, page)).thenReturn(Page.empty(page));
        assertTrue(service.getBroadcastLogs(7L, page).isEmpty());
    }

    // ---- list ----
    @Test void listRejectsCompanyOutsideScope() {
        doThrow(new AccessDeniedException("denied")).when(access).scope(20L, null);
        assertThrows(AccessDeniedException.class, () -> service.getBroadcasts(20L, null, page));
        verifyNoInteractions(broadcastRepo);
    }

    @Test void listForBranchUserWithoutBranchIsLimitedToPermittedBranches() {
        when(access.branchIds()).thenReturn(List.of(11L));
        when(broadcastRepo.findByCompanyIdAndBranchIdInAndDeletedAtIsNull(10L, List.of(11L), page)).thenReturn(Page.empty(page));
        service.getBroadcasts(10L, null, page);
        verify(access).scope(10L, null);
        verify(broadcastRepo, never()).findByCompanyIdAndDeletedAtIsNull(any(), any());
    }

    @Test void listForBranchUserWithNoActiveBranchesIsEmpty() {
        when(access.branchIds()).thenReturn(List.of());
        assertTrue(service.getBroadcasts(10L, null, page).isEmpty());
        verifyNoInteractions(broadcastRepo);
    }

    @Test void listForCompanyLevelUserReturnsWholeCompany() {
        when(access.branchIds()).thenReturn(null);
        when(broadcastRepo.findByCompanyIdAndDeletedAtIsNull(10L, page)).thenReturn(Page.empty(page));
        service.getBroadcasts(10L, null, page);
        verify(broadcastRepo).findByCompanyIdAndDeletedAtIsNull(10L, page);
    }

    @Test void listWithBranchChecksThatBranch() {
        when(broadcastRepo.findByCompanyIdAndBranchIdAndDeletedAtIsNull(10L, 11L, page)).thenReturn(Page.empty(page));
        service.getBroadcasts(10L, 11L, page);
        verify(access).scope(10L, 11L);
    }
}
