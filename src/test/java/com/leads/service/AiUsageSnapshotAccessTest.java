package com.leads.service;

import com.leads.repository.AiUsageCounterRepository;
import com.leads.repository.BranchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** F-14 (Phase -1 hotfix): a branch's AI usage snapshot is only readable inside the caller's scope. */
@ExtendWith(MockitoExtension.class)
class AiUsageSnapshotAccessTest {
    @Mock AiUsageCounterRepository counterRepo;
    @Mock AiBranchSettingsService branchSettingsService;
    @Mock WhatsAppScheduleService scheduleService;
    @Mock BranchRepository branchRepo;
    @Mock TenantAccess access;
    @InjectMocks AiUsageGuardService service;

    @Test void foreignBranchIsRejectedBeforeAnyLookup() {
        when(access.branch(77L)).thenThrow(new AccessDeniedException("denied"));
        assertThrows(AccessDeniedException.class, () -> service.getUsageSnapshot(77L));
        verifyNoInteractions(branchRepo, counterRepo, branchSettingsService);
    }
}
