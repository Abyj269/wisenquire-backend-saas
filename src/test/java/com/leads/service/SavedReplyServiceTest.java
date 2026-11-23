package com.leads.service;

import com.leads.model.entity.SavedReply;
import com.leads.repository.SavedReplyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SavedReplyServiceTest {
    @Mock TenantAccess access;
    @Mock SavedReplyRepository repository;
    @InjectMocks SavedReplyService service;
    SavedReply reply(long company, boolean active) {
        var reply = new SavedReply(); reply.setId(5L); reply.setCompanyId(company); reply.setActive(active); return reply;
    }
    @Test void activePickerExcludesDisabledRepliesButAdminCanSeeThem() {
        var active = reply(1, true); var inactive = reply(1, false);
        when(repository.findByCompanyIdOrderByTitleAscIdAsc(1L)).thenReturn(List.of(active, inactive));
        assertEquals(List.of(active), service.list(1L, true));
        assertEquals(2, service.list(1L, false).size());
        verify(access, times(2)).company(1L);
    }
    @Test void cannotEditOrDeleteAnotherCompanyReply() {
        when(repository.findById(5L)).thenReturn(Optional.of(reply(2, true)));
        assertThrows(AccessDeniedException.class, () -> service.save(1L, 5L, new SavedReplyService.ReplyInput("Hi", "Hello", true)));
        assertThrows(AccessDeniedException.class, () -> service.delete(1L, 5L));
        verify(repository, never()).save(any()); verify(repository, never()).delete(any());
    }
    @Test void createUsesAuthorizedCompanyAndPreservesMultilineText() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.save(1L, null, new SavedReplyService.ReplyInput(" Greeting ", " Hello\nHow can we help? ", true));
        assertEquals(1L, result.getCompanyId()); assertEquals("Greeting", result.getTitle());
        assertEquals("Hello\nHow can we help?", result.getBody()); verify(access).company(1L);
    }
    @Test void deniedCompanyCannotReadReplies() {
        doThrow(new AccessDeniedException("Denied")).when(access).company(2L);
        assertThrows(AccessDeniedException.class, () -> service.list(2L, true));
        verifyNoInteractions(repository);
    }
    @Test void validatesBlankAndOversizedContent() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertEquals(2, validator.validate(new SavedReplyService.ReplyInput(" ", " ", true)).size());
            assertEquals(2, validator.validate(new SavedReplyService.ReplyInput("x".repeat(101), "x".repeat(4097), true)).size());
        }
    }
}
