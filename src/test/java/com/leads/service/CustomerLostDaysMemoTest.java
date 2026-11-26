package com.leads.service;

import com.leads.model.entity.Company;
import com.leads.model.entity.CompanyLeadSettings;
import com.leads.model.entity.Customer;
import com.leads.repository.CompanyLeadSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;

/** Module 07 P1 (P-11): the company's lost-after setting is read once per transaction, not once per customer row. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerLostDaysMemoTest {

    @Mock TenantAccess access;
    @Mock CompanyLeadSettingsRepository settingsRepo;
    @Spy CompanyClock companyClock = new CompanyClock(Clock.system(CompanyClock.SERVER_ZONE));
    @InjectMocks CustomerService service;

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // afterCompletion would unbind; emulate the end of the transaction
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(0));
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Customer converted(Company company) {
        return Customer.builder().id(1L).company(company).name("C").mobileNumber("+971500000000").mobileNormalized("971500000000")
                .convertedLeadsCount(1).totalLeadsCount(2).lastLeadAt(LocalDateTime.now().minusDays(5)).build();
    }

    @Test
    void oneSettingsQueryPerCompanyInsideATransaction() {
        Company company = Company.builder().id(7L).build();
        when(settingsRepo.findByCompanyId(7L)).thenReturn(Optional.of(CompanyLeadSettings.builder().customerLostAfterDays(30).build()));
        TransactionSynchronizationManager.initSynchronization();
        for (int i = 0; i < 20; i++) service.mapToSummaryDto(converted(company));
        verify(settingsRepo, times(1)).findByCompanyId(7L);
    }

    @Test
    void withoutATransactionEachCallStillReadsTheSetting() {
        Company company = Company.builder().id(8L).build();
        when(settingsRepo.findByCompanyId(8L)).thenReturn(Optional.empty());
        service.mapToSummaryDto(converted(company));
        service.mapToSummaryDto(converted(company));
        verify(settingsRepo, times(2)).findByCompanyId(8L);
    }
}
