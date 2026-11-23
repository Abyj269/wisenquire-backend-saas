package com.leads.service;
import com.leads.model.entity.*;
import com.leads.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboxActionsTest {
    @Mock TenantAccess access;
    @Mock WhatsAppConversationRepository conversationRepo;
    @Mock CustomerRepository customerRepo;
    @Mock SimpMessagingTemplate messagingTemplate;
    @InjectMocks WhatsAppCloudService service;
    WhatsAppConversation conversation() {
        return WhatsAppConversation.builder().id(1L).company(Company.builder().id(2L).build())
                .customerWaId("971500000000").unreadCount(0).build();
    }
    @Test void manualUnreadPersistsWithoutInventingAnIncomingMessageAndReadClearsIt() {
        var c = conversation(); when(conversationRepo.findForSending(1L)).thenReturn(Optional.of(c));
        var dto = service.updateInboxState(1L, null, true);
        assertTrue(c.isManuallyUnread()); assertEquals(0, c.getUnreadCount()); assertEquals(1, dto.getUnreadCount());
        service.markConversationRead(1L);
        assertFalse(c.isManuallyUnread()); assertEquals(0, c.getUnreadCount());
        verify(access, times(2)).record(WhatsAppConversation.class, 1L);
    }
    @Test void pinAndUnpinPreserveUnreadMessages() {
        var c = conversation(); c.setUnreadCount(4);
        when(conversationRepo.findForSending(1L)).thenReturn(Optional.of(c));
        assertTrue(service.updateInboxState(1L, true, null).isPinned());
        assertEquals(4, c.getUnreadCount());
        assertFalse(service.updateInboxState(1L, false, null).isPinned());
        assertEquals(4, c.getUnreadCount());
    }
    @Test void deniedConversationCannotBePinnedOrMarkedUnread() {
        doThrow(new AccessDeniedException("Denied")).when(access).record(WhatsAppConversation.class, 1L);
        assertThrows(AccessDeniedException.class, () -> service.updateInboxState(1L, true, null));
        assertThrows(AccessDeniedException.class, () -> service.updateInboxState(1L, null, true));
        verifyNoInteractions(conversationRepo, messagingTemplate);
    }
}
