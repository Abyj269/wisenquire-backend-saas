package com.leads.controller;

import com.leads.dto.ai.*;
import com.leads.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Day-to-day agent-facing AI actions: suggest a reply, improve a draft,
 * translate a message on demand, read/refresh a lead's conversion score,
 * summarize a thread, transcribe a voice note. Plain synchronous methods,
 * like every other controller in this codebase — an earlier version routed
 * these through a WebAsyncTask on a dedicated executor to avoid tying up a
 * Tomcat request thread for the duration of the provider call, but mixing
 * Spring Security's SecurityContext with a custom executor thread plus an
 * async servlet re-dispatch caused a spurious 401 (and a resulting
 * front-end auto-logout) on at least the improve-draft path. Removed in
 * favor of the same synchronous style already proven everywhere else in
 * this app; Tomcat's default thread pool has ample headroom for occasional
 * AI calls at this app's scale.
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "Agent-facing AI actions: reply suggestions, message improvement, translation, conversion scoring, summaries")
public class AiAssistantController {

    private final AiComposerAssistService composerAssistService;
    private final AiTranslationService translationService;
    private final AiConversionScoringService conversionScoringService;
    private final AiConversationSummaryService summaryService;
    private final AiTranscriptionService transcriptionService;

    @PostMapping("/conversations/{conversationId}/suggest-reply")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Suggest a reply to the customer's latest message — populates the composer only, never sends")
    public ResponseEntity<AiTextResultDto> suggestReply(@PathVariable Long conversationId) {
        return ResponseEntity.ok(composerAssistService.suggestReply(conversationId));
    }

    @PostMapping("/conversations/{conversationId}/improve-draft")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Improve an agent-typed draft reply for clarity/professionalism")
    public ResponseEntity<AiTextResultDto> improveDraft(@PathVariable Long conversationId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(composerAssistService.improveDraft(conversationId, body.get("draftText")));
    }

    @PostMapping("/conversations/{conversationId}/summarize")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Summarize a chat thread — e.g. for an agent catching up after a reassignment")
    public ResponseEntity<AiTextResultDto> summarize(@PathVariable Long conversationId) {
        return ResponseEntity.ok(summaryService.summarize(conversationId));
    }

    @GetMapping("/messages/{messageId}/translation")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Fetch a message's cached translation, if one has already been requested")
    public ResponseEntity<AiTranslationDto> getTranslation(@PathVariable Long messageId) {
        return translationService.getCached(messageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/messages/{messageId}/translation")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Translate a message to English on demand — idempotent, cached after the first call")
    public ResponseEntity<AiTranslationDto> translate(@PathVariable Long messageId) {
        return ResponseEntity.ok(translationService.translate(messageId));
    }

    @PostMapping("/messages/{messageId}/transcription")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Transcribe a voice-note message on demand — OpenAI-only, idempotent, cached after the first call")
    public ResponseEntity<AiTranscriptionDto> transcribe(@PathVariable Long messageId) {
        return ResponseEntity.ok(transcriptionService.transcribe(messageId));
    }

    @GetMapping("/leads/{leadNumber}/conversion-score")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:VIEW')")
    @Operation(summary = "Read a lead's cached AI conversion-strength score, if one has been generated")
    public ResponseEntity<AiConversionScoreDto> getConversionScore(@PathVariable String leadNumber) {
        return conversionScoringService.getCached(leadNumber)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/leads/{leadNumber}/conversion-score/refresh")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('AI_ASSISTANT:EDIT')")
    @Operation(summary = "Recompute a lead's AI conversion-strength score from its chat history — manual only, the most expensive AI call, never scheduled")
    public ResponseEntity<AiConversionScoreDto> refreshConversionScore(@PathVariable String leadNumber) {
        return ResponseEntity.ok(conversionScoringService.refresh(leadNumber));
    }
}
