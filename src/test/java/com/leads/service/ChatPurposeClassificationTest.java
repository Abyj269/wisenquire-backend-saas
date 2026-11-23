package com.leads.service;

import com.leads.model.entity.*;
import com.leads.model.enums.*;
import com.leads.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ChatPurposeClassificationTest {
    @Mock CustomerRepository customers;
    @InjectMocks WhatsAppCloudService service;
    WhatsAppConversation conversation(Lead lead) {
        return WhatsAppConversation.builder().id(1L).company(Company.builder().id(1L).build()).lead(lead).customerWaId("971501234567").build();
    }
    Lead lead(PurposeClassification classification, StageType stage) {
        return Lead.builder().id(2L).customer(Customer.builder().id(3L).build())
            .purpose(LeadPurpose.builder().classification(classification).build())
            .status(LeadStatus.builder().stageType(stage).build()).build();
    }
    @ParameterizedTest @EnumSource(PurposeClassification.class)
    void activeLeadExposesItsPurposeClassification(PurposeClassification classification) {
        var dto = service.mapConversationToDto(conversation(lead(classification, StageType.OPEN)));
        assertFalse(dto.getLeadIsClosed());
        assertEquals(classification, dto.getLeadPurposeClassification());
    }
    @ParameterizedTest @EnumSource(value = StageType.class, names = {"CONVERTED", "LOST", "CLOSED"})
    void terminalLeadsHaveNoClassificationRing(StageType stage) {
        var dto = service.mapConversationToDto(conversation(lead(PurposeClassification.HIGH_POTENTIAL, stage)));
        assertTrue(dto.getLeadIsClosed()); assertNull(dto.getLeadPurposeClassification());
    }
    @Test void convertedFlagAndMissingPurposeNeverDisplayAnActiveClassification() {
        var lead = lead(PurposeClassification.HIGH_POTENTIAL, StageType.OPEN);
        lead.setIsConverted(true);
        assertNull(service.mapConversationToDto(conversation(lead)).getLeadPurposeClassification());
        lead.setIsConverted(false); lead.setPurpose(null);
        assertNull(service.mapConversationToDto(conversation(lead)).getLeadPurposeClassification());
        assertNull(service.mapConversationToDto(conversation(null)).getLeadPurposeClassification());
    }
}
