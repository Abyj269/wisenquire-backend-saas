package com.leads.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/** Module 17 P1: a hand-off refused by a full automation queue is recorded, not lost. */
@ExtendWith(MockitoExtension.class)
class AutomationHandOffTest {

    @Mock AutomationEngineService engine;
    @InjectMocks WhatsAppCloudService service;

    /** The engine is a @Lazy field (it breaks a bean cycle), so constructor injection does not set it. */
    @org.junit.jupiter.api.BeforeEach
    void wireEngine() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "automationEngineService", engine);
    }

    @Test
    void aFullQueueRecordsTheSkippedRuns() {
        doThrow(new TaskRejectedException("queue full")).when(engine).onIncomingWhatsAppMessage(5L);
        assertDoesNotThrow(() -> service.handOffToAutomations(5L));
        verify(engine).recordQueueFull(5L);
    }

    @Test
    void aNormalHandOffRecordsNothing() {
        service.handOffToAutomations(6L);
        verify(engine).onIncomingWhatsAppMessage(6L);
        verify(engine, never()).recordQueueFull(any());
    }

    @Test
    void aFailingRecordIsOnlyLogged() {
        doThrow(new TaskRejectedException("queue full")).when(engine).onIncomingWhatsAppMessage(7L);
        doThrow(new IllegalStateException("db down")).when(engine).recordQueueFull(7L);
        assertDoesNotThrow(() -> service.handOffToAutomations(7L));
    }
}
