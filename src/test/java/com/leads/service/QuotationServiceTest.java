package com.leads.service;

import com.leads.dto.quotation.QuotationRequest;
import com.leads.model.entity.*;
import com.leads.model.enums.QuotationStatus;
import com.leads.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotationServiceTest {
    @Mock TenantAccess access;
    @Mock DocumentNumberService numbers;
    @Mock QuotationRepository quotationRepo;
    @Mock LeadRepository leadRepo;
    @Mock ProductMasterRepository productRepo;
    @Mock WhatsAppConversationRepository conversationRepo;
    @Mock WhatsAppCloudService whatsAppCloudService;
    @Mock QuotationPdfService pdfService;
    @Mock AuthService authService;
    @Mock LeadService leadService;
    @org.mockito.Spy CompanyClock companyClock = new CompanyClock(java.time.Clock.system(CompanyClock.SERVER_ZONE));
    /** S3-d (module 12): sendViaWhatsApp runs its own short transactions. */
    @Mock org.springframework.transaction.PlatformTransactionManager txManager;
    @InjectMocks QuotationService service;

    @Test void sentQuotationCannotBeDeleted() {
        var quote = Quotation.builder().status(QuotationStatus.SENT).build();
        when(quotationRepo.findById(7L)).thenReturn(Optional.of(quote));
        assertThrows(IllegalArgumentException.class, () -> service.deleteQuotation(7L));
        verify(quotationRepo, never()).deleteById(anyLong());
    }

    @Test void sentQuotationCannotBeEdited() {
        var quote = Quotation.builder().status(QuotationStatus.SENT).build();
        when(quotationRepo.findById(7L)).thenReturn(Optional.of(quote));
        assertThrows(IllegalArgumentException.class, () -> service.updateQuotation(7L, new QuotationRequest()));
        verify(quotationRepo, never()).save(any());
    }

    @Test void wrongLeadConversationCannotReceiveQuote() {
        var company = Company.builder().id(1L).build();
        var quote = Quotation.builder().company(company).lead(Lead.builder().id(2L).build())
                .customerPhone("971501234567").status(QuotationStatus.DRAFT).build();
        var conversation = WhatsAppConversation.builder().company(company).lead(Lead.builder().id(3L).build())
                .customerWaId("971501234567").build();
        when(quotationRepo.findById(7L)).thenReturn(Optional.of(quote));
        when(access.record(Quotation.class, 7L)).thenReturn(quote);
        when(access.record(WhatsAppConversation.class, 8L)).thenReturn(conversation);
        assertThrows(IllegalArgumentException.class, () -> service.sendViaWhatsApp(7L, 8L));
        verifyNoInteractions(pdfService, whatsAppCloudService);
    }

    @Test void failedDeliveryLeavesQuotationDraft() {
        var company = Company.builder().id(1L).build();
        var lead = Lead.builder().id(2L).build();
        var quote = Quotation.builder().company(company).lead(lead).quotationNumber("QT-1-2026-1")
                .customerPhone("971501234567").status(QuotationStatus.DRAFT).build();
        var conversation = WhatsAppConversation.builder().company(company).lead(lead)
                .customerWaId("971501234567").build();
        when(quotationRepo.findById(7L)).thenReturn(Optional.of(quote));
        when(access.record(Quotation.class, 7L)).thenReturn(quote);
        when(access.record(WhatsAppConversation.class, 8L)).thenReturn(conversation);
        when(pdfService.render(eq(quote), isNull())).thenReturn(new byte[]{1, 2});
        when(whatsAppCloudService.sendMediaMessage(eq(8L), any(byte[].class), anyString(), eq("application/pdf"), anyString(), isNull()))
                .thenReturn(com.leads.dto.whatsapp.WhatsAppMessageDto.builder()
                        .status(com.leads.model.enums.MessageStatus.FAILED).build());
        assertThrows(com.leads.exception.WhatsAppApiException.class, () -> service.sendViaWhatsApp(7L, 8L));
        assertEquals(QuotationStatus.DRAFT, quote.getStatus());
        verify(quotationRepo, never()).save(any());
        verifyNoInteractions(leadService);
    }
}
