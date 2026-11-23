package com.leads.service;
import com.leads.dto.ai.*;
import com.leads.model.entity.AiProviderConfig;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiMeteredCallsTest {
    AiUsageLedger ledger = mock(AiUsageLedger.class);
    AiMeteredCalls calls = new AiMeteredCalls(ledger);
    AiChatProvider provider = mock(AiChatProvider.class);
    AiProviderConfig config = AiProviderConfig.builder().modelName("test-model").build();
    AiUsageLedger.Ticket ticket = new AiUsageLedger.Ticket("request", new BigDecimal("2"), new BigDecimal("8"));

    @Test void estimatesUseInputAndOutputRatesIndependently() {
        assertEquals(new BigDecimal("0.0060000000"), AiUsageLedger.estimate(ticket, 1000, 500));
    }
    @Test void missingUsageOrPricingRemainsUnknownWhileExplicitFreeRatesAreZero() {
        assertNull(AiUsageLedger.estimate(ticket,null,500));
        assertNull(AiUsageLedger.estimate(new AiUsageLedger.Ticket("id",null,null),1000,500));
        assertNull(AiUsageLedger.estimate(ticket,-1,500));
        assertEquals(0, AiUsageLedger.estimate(new AiUsageLedger.Ticket("id",BigDecimal.ZERO,BigDecimal.ZERO),1000,500).signum());
    }
    @Test void successfulCallRecordsReportedUsageExactlyOnce() {
        var request = AiChatRequest.builder().build();
        var response = AiChatResponse.builder().content("answer").promptTokens(120).completionTokens(30).build();
        when(ledger.start(config,"test-model","TRANSLATION")).thenReturn(ticket);
        when(provider.complete(config,request)).thenReturn(response);
        assertSame(response,calls.chat("TRANSLATION",provider,config,request));
        verify(ledger).finish(ticket,"SUCCEEDED",120,30);
        verify(provider,times(1)).complete(config,request);
    }
    @Test void providerFailureIsRecordedWithUnknownCostAndOriginalErrorPropagates() {
        var request = AiChatRequest.builder().build(); var error = new IllegalStateException("Provider unavailable");
        when(ledger.start(config,"test-model","CONNECTION_TEST")).thenReturn(ticket);
        when(provider.complete(config,request)).thenThrow(error);
        assertSame(error,assertThrows(IllegalStateException.class,()->calls.chat("CONNECTION_TEST",provider,config,request)));
        verify(ledger).finish(ticket,"FAILED",null,null);
    }
    @Test void ledgerFailureBeforeCallDoesNotSpendUntrackedTokens() {
        when(ledger.start(config,"test-model","TRANSLATION")).thenThrow(new IllegalStateException("Storage unavailable"));
        assertThrows(IllegalStateException.class,()->calls.chat("TRANSLATION",provider,config,AiChatRequest.builder().build()));
        verifyNoInteractions(provider);
    }
    @Test void audioCallsAreCountedWithoutInventingTokenUsage() {
        var audio = mock(AiTranscriptionProvider.class); byte[] data = {1};
        when(ledger.start(config,"whisper-1","TRANSCRIPTION")).thenReturn(ticket);
        when(audio.transcribe(config,data,"audio/ogg","voice.ogg")).thenReturn("Hello");
        assertEquals("Hello",calls.transcribe(audio,config,data,"audio/ogg","voice.ogg"));
        verify(ledger).finish(ticket,"SUCCEEDED",null,null);
    }
}
