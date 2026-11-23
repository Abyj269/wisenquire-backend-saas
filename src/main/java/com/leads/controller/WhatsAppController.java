package com.leads.controller;

import com.leads.dto.lead.LeadDto;
import com.leads.dto.whatsapp.*;
import com.leads.service.WhatsAppCloudService;
import com.leads.service.WhatsAppMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/whatsapp")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "WhatsApp Cloud API Integration", description = "Meta WhatsApp Business Cloud API webhooks, live chat, and lead creation")
public class WhatsAppController {

    private final WhatsAppCloudService whatsAppService;
    private final WhatsAppMediaService mediaService;
    private final com.leads.service.WebhookSignatureVerifier signatures;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.leads.service.WhatsAppTemplateEventService templateEvents;

    @GetMapping("/webhook")
    @Operation(summary = "Meta Webhook Verification", description = "Public endpoint used by Meta to verify webhook callback URL using hub.verify_token.")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge
    ) {
        log.debug("WhatsApp webhook verification request.");
        if (whatsAppService.verifyWebhookToken(mode, verifyToken)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification token mismatch");
    }

    @PostMapping("/webhook")
    @Operation(summary = "Meta Webhook Event Receiver", description = "Receives real-time incoming WhatsApp messages and delivery receipts from Meta Cloud.")
    public ResponseEntity<String> receiveWebhook(@RequestBody byte[] raw, @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) throws IOException {
        signatures.verify(raw, signature);
        templateEvents.recordVerified(mapper.readTree(raw));
        whatsAppService.processWebhookPayload(mapper.readValue(raw, WhatsAppWebhookPayload.class));
        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get WhatsApp Conversations", description = "Returns a page of the inbox's conversations for the selected company/number, plus filter-pill counts. filter is ALL (default), UNREAD, or ACTIVE_LEADS. A non-blank search ranks contact (name/number) matches ahead of message-only matches and returns a single capped, unpaginated result instead of a page.")
    public ResponseEntity<ConversationListResponseDto> getConversations(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long configId,
            @RequestParam(defaultValue = "ALL") String filter,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(whatsAppService.getConversations(companyId, configId, filter, search, page, size));
    }

    @GetMapping("/conversations/find")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW') or hasAuthority('LEADS:VIEW') or hasAuthority('CUSTOMERS:VIEW')")
    @Operation(summary = "Find Conversation by Phone", description = "Looks up the WhatsApp conversation for a phone number (country code ignored) — backs the Lead/Customer chat side panel.")
    public ResponseEntity<WhatsAppConversationDto> findConversationByPhone(@RequestParam Long companyId, @RequestParam String phone) {
        return ResponseEntity.ok(whatsAppService.findConversationByPhone(companyId, phone));
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW') or hasAuthority('LEADS:VIEW') or hasAuthority('CUSTOMERS:VIEW')")
    public ResponseEntity<WhatsAppConversationDto> getConversation(@PathVariable Long id) {
        return ResponseEntity.ok(whatsAppService.getConversation(id));
    }

    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Conversation Messages", description = "Returns a page of chronological chat messages (newest page first) with status indicators.")
    public ResponseEntity<Page<WhatsAppMessageDto>> getConversationMessages(
            @PathVariable Long id,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(whatsAppService.getConversationMessages(id, pageable));
    }

    @PostMapping("/conversations/start")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:ADD') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Start a Business-Initiated Conversation", description = "Finds or creates the conversation for a phone number on a connected WhatsApp number, so an opening template message can then be sent into it via /messages/send — the entry point for messaging a customer who hasn't messaged in yet.")
    public ResponseEntity<WhatsAppConversationDto> startConversation(@Valid @RequestBody StartConversationRequest req) {
        return ResponseEntity.ok(whatsAppService.startConversation(req));
    }

    @PostMapping("/messages/send")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:ADD') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Send WhatsApp Message", description = "Sends outbound reply to customer and broadcasts via WebSocket.")
    public ResponseEntity<WhatsAppMessageDto> sendMessage(@Valid @RequestBody SendMessageRequest req) {
        return ResponseEntity.ok(whatsAppService.sendMessage(req));
    }

    @PostMapping(value = "/messages/send-media", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:ADD') or hasAuthority('LEADS:EDIT')")
    @Operation(summary = "Send WhatsApp Media Message", description = "Uploads a file to Meta and sends it as an image/video/audio/document message, inferred from its content type.")
    public ResponseEntity<WhatsAppMessageDto> sendMediaMessage(
            @RequestParam Long conversationId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) String clientMessageId) throws IOException {
        return ResponseEntity.ok(whatsAppService.sendMediaMessage(
                conversationId, file.getBytes(), file.getOriginalFilename(), file.getContentType(), caption, clientMessageId));
    }

    @GetMapping("/messages/{messageId}/media")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Fetch Message Media", description = "Resolves and streams a message's attached media (image/video/audio/document/sticker), caching it locally after the first fetch.")
    public ResponseEntity<byte[]> getMessageMedia(@PathVariable Long messageId) {
        WhatsAppMediaService.CachedMedia media = mediaService.resolve(messageId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(media.mimeType() != null ? media.mimeType() : "application/octet-stream"));
        if (media.filename() != null) {
            headers.setContentDispositionFormData("inline", media.filename());
        }
        return new ResponseEntity<>(media.data(), headers, HttpStatus.OK);
    }

    @PostMapping("/conversations/{id}/mark-read")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW')")
    @Operation(summary = "Mark Conversation as Read")
    public ResponseEntity<Void> markRead(@PathVariable Long id) {
        whatsAppService.markConversationRead(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/conversations/{id}/pin")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW')")
    public WhatsAppConversationDto pin(@PathVariable Long id, @RequestParam boolean pinned) {
        return whatsAppService.updateInboxState(id, pinned, null);
    }

    @PostMapping("/conversations/{id}/mark-unread")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW')")
    public WhatsAppConversationDto markUnread(@PathVariable Long id) {
        return whatsAppService.updateInboxState(id, null, true);
    }

    @PostMapping("/conversations/{id}/block")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('WHATSAPP_LIVE_CHAT:EDIT')")
    @Operation(summary = "Block Customer", description = "Hides this customer's WhatsApp conversation from the inbox from now on. Message history is kept; unblock from the Customer list to restore it.")
    public ResponseEntity<Void> blockConversationCustomer(@PathVariable Long id) {
        whatsAppService.blockConversationCustomer(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/create-lead")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEADS:ADD') or hasAuthority('WHATSAPP_LIVE_CHAT:ADD')")
    @Operation(summary = "Create Lead Directly from WhatsApp Chat", description = "Extracts contact from WhatsApp chat, automatically sets source to WHATSAPP, and attaches conversation to lead timeline.")
    public ResponseEntity<LeadDto> createLeadFromChat(@Valid @RequestBody CreateLeadFromChatRequest req) {
        return ResponseEntity.ok(whatsAppService.createLeadFromWhatsAppChat(req));
    }
}
