package com.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.lead.LeadCreateRequest;
import com.leads.dto.lead.LeadDto;
import com.leads.dto.whatsapp.*;
import com.leads.exception.WhatsAppApiException;
import com.leads.model.entity.*;
import com.leads.model.enums.ActivityType;
import com.leads.model.enums.MessageDirection;
import com.leads.model.enums.MessageStatus;
import com.leads.model.enums.StageType;
import com.leads.repository.*;
import com.leads.util.PhoneNumberUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppCloudService {
    private final TenantAccess access;
    private final WhatsAppReceiptService receipts;
    private final jakarta.persistence.EntityManager entityManager;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    private final WhatsAppConfigurationRepository configRepo;
    private final WhatsAppConversationRepository conversationRepo;
    private final WhatsAppMessageRepository messageRepo;
    private final CompanyRepository companyRepo;
    private final BranchRepository branchRepo;
    private final CustomerRepository customerRepo;
    private final LeadRepository leadRepo;
    private final LeadSourceRepository sourceRepo;
    private final LeadService leadService;
    private final LeadSettingsService leadSettingsService;
    private final CustomerService customerService;
    private final AuthService authService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final WhatsAppGraphApiClient graphApiClient;
    private final WhatsAppMediaService mediaService;
    private final WhatsAppScheduleService scheduleService;
    private final LanguageDetectionService languageDetectionService;

    @Value("${whatsapp.cloud-api.verify-token:LEAD_SYSTEM_SECURE_VERIFY_TOKEN_2026}")
    private String defaultVerifyToken;

    // Injected lazily to break the circular dependency:
    // AutomationEngineService → WhatsAppGraphApiClient
    // WhatsAppCloudService → AutomationEngineService
    @Autowired
    @Lazy
    private AutomationEngineService automationEngineService;

    /**
     * A free-text inbox search isn't offset-paginated (see {@link #getConversations}) —
     * it ranks contact (name/number) matches ahead of message-only matches,
     * which requires the full matching set in memory to sort. This caps that
     * set so a search that matches almost the entire inbox can't blow up the
     * response; result sets beyond this are vanishingly rare in practice.
     */
    private static final int SEARCH_RESULT_CAP = 100;

    public boolean verifyWebhookToken(String mode, String token) {
        if (!"subscribe".equals(mode) || token == null) return false;
        // Meta's webhook subscription is app-level, not per-phone-number, so a
        // match against the fallback default OR any branch's own verify token
        // (in case a company's Meta App uses its own) both count as valid.
        return defaultVerifyToken.equals(token) || configRepo.findByVerifyToken(token).isPresent();
    }

    public void processWebhookPayload(WhatsAppWebhookPayload payload) {
        if (payload == null || payload.getEntry() == null) return;
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        for (var entry : payload.getEntry()) {
            if (entry.getChanges() == null) continue;
            for (var change : entry.getChanges()) {
                var val = change.getValue();
                if (val == null || val.getMetadata() == null) continue;
                String number = val.getMetadata().getPhoneNumberId();
                var config = configRepo.findByPhoneNumberId(number).filter(c -> Boolean.TRUE.equals(c.getIsActive())).orElseThrow(() -> new IllegalArgumentException("Unknown or inactive receiving number."));
                Long companyId = config.getCompany().getId();
                TenantAccess.inCompany(companyId, () -> {
                    if (val.getMessages()!=null) for (var msg:val.getMessages()) {
                        // Only a first-time delivery may trigger automations - Meta
                        // redelivers webhooks, and a redelivery must not re-run them.
                        boolean[] stored = {false};
                        tx.executeWithoutResult(status -> {
                            var current=configRepo.findByPhoneNumberId(number).orElseThrow();
                            if ("reaction".equals(msg.getType()) && msg.getReaction()!=null) {
                                messageRepo.findByWamid(msg.getReaction().getMessageId()).ifPresent(target -> {
                                    access.record(WhatsAppConversation.class,target.getConversation().getId());
                                    processInboundReaction(msg.getReaction());
                                });
                            } else stored[0] = processInboundMessage(current.getCompany(),current,number,val.getContacts(),msg);
                        });
                        // Ancillary failure cannot roll back an already committed inbound message.
                        try { tx.executeWithoutResult(status -> messageRepo.findByWamid(msg.getId()).ifPresent(saved -> maybeAutoCreateLead(saved.getConversation().getCompany(),saved.getConversation()))); }
                        catch (RuntimeException ex) { log.warn("Auto-lead processing failed for inbound {}",msg.getId()); }
                        try { tx.executeWithoutResult(status -> messageRepo.findByWamid(msg.getId()).ifPresent(saved -> maybeSendAutoReply(saved.getConversation().getCompany(),saved.getConversation(),configRepo.findByPhoneNumberId(number).orElseThrow()))); }
                        catch (RuntimeException ex) { log.warn("Auto-reply processing failed for inbound {}",msg.getId()); }
                        // Hand off after the inbound transaction has committed, by id -
                        // the engine runs on its own thread and reloads everything.
                        if (stored[0]) {
                            try { messageRepo.findByWamid(msg.getId()).ifPresent(saved -> automationEngineService.onIncomingWhatsAppMessage(saved.getId())); }
                            catch (RuntimeException ex) { log.warn("Automation hand-off failed for inbound {}: {}",msg.getId(),ex.getMessage()); }
                        }
                    }
                    if(val.getStatuses()!=null) for(var receipt:val.getStatuses()) tx.executeWithoutResult(status -> {
                        receipts.record(number, receipt);
                    });
                });
            }
        }
    }

    /** @return true when this delivery stored a new message, false for an ignored redelivery. */
    private boolean processInboundMessage(Company company, WhatsAppConfiguration config, String phoneNumberId, List<WhatsAppWebhookPayload.Contact> contacts, WhatsAppWebhookPayload.Message msg) {
        // Meta may redeliver the same webhook event — skip if we've already
        // recorded this wamid rather than letting the DB's unique constraint
        // (idx_msg_wamid) throw and roll back the whole webhook payload's
        // transaction, which would also undo any other messages/statuses
        // batched in the same delivery.
        if (msg.getId() != null && messageRepo.findByWamid(msg.getId()).isPresent()) {
            log.info("Ignoring duplicate inbound WhatsApp webhook delivery for wamid: {}", msg.getId());
            return false;
        }

        String fromPhone = msg.getFrom();
        String customerName = "WhatsApp User";

        if (contacts != null) {
            for (WhatsAppWebhookPayload.Contact c : contacts) {
                if (fromPhone.equals(c.getWaId()) && c.getProfile() != null && c.getProfile().getName() != null) {
                    customerName = c.getProfile().getName();
                }
            }
        }

        final String resolvedCustomerName = customerName;

        // A customer's conversation is keyed by (company, their number, AND
        // which of our numbers they messaged) — a branch can now own several
        // numbers, so the same customer texting two different numbers must
        // stay two separate conversations rather than merging into one.
        WhatsAppConversation conversation = conversationRepo
                .findByCompanyIdAndCustomerWaIdAndReceivingPhoneNumberId(company.getId(), fromPhone, phoneNumberId)
                .orElseGet(() -> {
                    Branch branch = config != null ? config.getBranch()
                            : branchRepo.findByCompanyId(company.getId()).stream().findFirst().orElse(null);
                    return WhatsAppConversation.builder()
                            .company(company)
                            .branch(branch)
                            .receivingPhoneNumberId(phoneNumberId)
                            .customerWaId(fromPhone)
                            .customerName(resolvedCustomerName)
                            .chatStatus("OPEN")
                            .unreadCount(0)
                            .build();
                });

        String msgType = msg.getType() != null ? msg.getType().toUpperCase() : "TEXT";
        WhatsAppMessage.WhatsAppMessageBuilder builder = WhatsAppMessage.builder()
                .conversation(conversation)
                .wamid(msg.getId())
                .direction(MessageDirection.INBOUND)
                .messageType(msgType)
                .status(MessageStatus.DELIVERED);

        String textBody = populateInboundContent(builder, msg, msgType);

        // Free local heuristic only — no AI call — purely to decide whether
        // the chat UI's translate icon should appear. The actual (paid)
        // translation happens later, on demand, only if an agent clicks it.
        if ("TEXT".equals(msgType) && textBody != null) {
            builder.detectedLanguage(languageDetectionService.detect(textBody));
        }

        // "Click to WhatsApp Ad" referral — Meta nests it as either a
        // sibling of the message or under context depending on API version,
        // so both are checked. Only present on the message that started the
        // ad-originated conversation, but mirrored onto the conversation
        // itself so the conversation list can badge it without re-scanning
        // every message.
        WhatsAppWebhookPayload.Referral referral = msg.getReferral() != null
                ? msg.getReferral()
                : (msg.getContext() != null ? msg.getContext().getReferral() : null);
        // Only a genuine "ad" referral carries a real Meta ad id in source_id
        // — a boosted/organic "post" referral's source_id is a composite
        // {page_id}_{post_id}, which would corrupt the Ads module's
        // lead-to-ad join if ever written to ad_source_id. The "_" check is
        // defensive in case source_type is ever missing/blank.
        boolean isAdReferral = referral != null
                && "ad".equalsIgnoreCase(referral.getSourceType())
                && referral.getSourceId() != null
                && !referral.getSourceId().contains("_");
        if (referral != null) {
            builder.isFromAd(true)
                    .adHeadline(referral.getHeadline())
                    .adSourceUrl(referral.getSourceUrl())
                    .adSourceType(referral.getSourceType())
                    .ctwaClid(referral.getCtwaClid());
            if (isAdReferral) {
                builder.adSourceId(referral.getSourceId());
            }
        }
        if (msg.getContext() != null && msg.getContext().getId() != null) {
            builder.contextWamid(msg.getContext().getId());
        }

        conversation.setCustomerName(customerName);
        Long inboundEpoch = WhatsAppMessagingPolicy.inboundEpoch(msg.getTimestamp(), java.time.Instant.now());
        if (!"SYSTEM".equals(msgType) && inboundEpoch != null && (conversation.getLastCustomerMessageEpoch() == null
                || inboundEpoch > conversation.getLastCustomerMessageEpoch())) conversation.setLastCustomerMessageEpoch(inboundEpoch);
        conversation.setLastMessageText(textBody);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setUnreadCount(conversation.getUnreadCount() + 1);
        if (referral != null && !Boolean.TRUE.equals(conversation.getIsFromAd())) {
            conversation.setIsFromAd(true);
            conversation.setAdHeadline(referral.getHeadline());
            conversation.setAdSourceUrl(referral.getSourceUrl());
            conversation.setCtwaClid(referral.getCtwaClid());
            if (isAdReferral) {
                conversation.setAdSourceId(referral.getSourceId());
            }
        }
        conversationRepo.save(conversation);

        WhatsAppMessage savedMsg = messageRepo.save(builder.build());

        // Runs before the broadcast below so, when it does link a lead, that
        // single broadcast already carries the updated conversation (lead
        // badge shows immediately) instead of needing a second round-trip.
        // Follow-up work runs only after this inbound transaction commits.

        // Realtime notification via STOMP WebSocket
        broadcastConversationUpdate(conversation, savedMsg);
        return true;
    }

    /**
     * Auto-creates a lead for a WhatsApp contact with no active lead yet,
     * when the company has opted in (CompanyLeadSettings
     * .whatsappAutoCreateLeadEnabled) — otherwise lead creation from
     * WhatsApp stays the existing manual "Create Lead from Chat" flow.
     * <p>
     * Best-effort by design: any failure here (most commonly a company that
     * requires lead fields — email, job title, a dynamic field — that a bare
     * inbound message can't supply) is logged and swallowed rather than
     * propagated, so it can never roll back the inbound message this method
     * is called after saving. Auto-creation is a convenience layered on top
     * of message ingestion, never a precondition for it.
     */
    private void maybeAutoCreateLead(Company company, WhatsAppConversation conversation) {
        // A still-open linked lead means this chat is already being worked —
        // leave it alone. A Converted/Lost lead is a finished engagement, so
        // a new inbound message is treated as a fresh opportunity instead.
        if (conversation.getLead() != null && !isLeadClosed(conversation.getLead())) return;

        if (!Boolean.TRUE.equals(leadSettingsService.getCompanyLeadSettings(company.getId()).getWhatsappAutoCreateLeadEnabled())) {
            return;
        }

        try {
            String normalized = PhoneNumberUtil.lastSignificantDigits(conversation.getCustomerWaId());
            Customer existingCustomer = customerRepo.findByCompanyIdAndMobileNormalized(company.getId(), normalized).orElse(null);
            if (existingCustomer != null && leadRepo.existsActiveLeadForCustomer(existingCustomer.getId())) {
                // Already being worked elsewhere (another branch/source) —
                // don't spawn a second, competing lead for the same customer.
                return;
            }

            Branch branch = conversation.getBranch() != null ? conversation.getBranch()
                    : branchRepo.findByCompanyId(company.getId()).stream().findFirst().orElse(null);
            if (branch == null) return;

            String sourceCode = Boolean.TRUE.equals(conversation.getIsFromAd()) ? "WHATSAPP_MARKETING" : "WHATSAPP";
            LeadSource source = leadSettingsService.getSystemSource(company.getId(), sourceCode);

            LeadCreateRequest leadReq = LeadCreateRequest.builder()
                    .companyId(company.getId())
                    .branchId(branch.getId())
                    .firstName(conversation.getCustomerName())
                    .phone(conversation.getCustomerWaId())
                    .whatsappNumber(conversation.getCustomerWaId())
                    .sourceId(source != null ? source.getId() : null)
                    .assignedToUserId(conversation.getAssignedAgent() != null ? conversation.getAssignedAgent().getId() : null)
                    .notes("Lead automatically created from an inbound WhatsApp message.")
                    .build();

            LeadDto leadDto = leadService.createLeadFromInboundChat(leadReq);

            conversation.setLead(leadRepo.getReferenceById(leadDto.getId()));
            conversationRepo.save(conversation);
            leadService.linkAdAndClid(leadDto.getId(), company.getId(), conversation.getAdSourceId(), conversation.getCtwaClid());

            leadService.recordActivity(leadRepo.getReferenceById(leadDto.getId()), ActivityType.WHATSAPP_MSG,
                    "Auto-Created from WhatsApp",
                    "Lead automatically created and linked from an inbound WhatsApp conversation with "
                            + conversation.getCustomerName() + " (" + conversation.getCustomerWaId() + ")", null);
        } catch (Exception ex) {
            log.warn("Auto lead-creation from WhatsApp skipped for conversation {}: {}", conversation.getId(), ex.getMessage());
        }
    }

    /** Converted, Lost and Closed are finished engagements, including the explicit conversion flag. */
    private boolean isLeadClosed(Lead lead) {
        return Boolean.TRUE.equals(lead.getIsConverted())
                || (lead.getStatus() != null && java.util.Set.of(StageType.CONVERTED, StageType.LOST, StageType.CLOSED).contains(lead.getStatus().getStageType()));
    }

    /**
     * Sends the working-hours/non-working-hours/holiday auto-reply for this
     * branch, if {@link WhatsAppScheduleService#resolveAutoReply} finds one
     * configured and enabled — at most once per conversation per
     * (company-local) calendar day.
     * <p>
     * Best-effort by design, same non-blocking spirit as
     * {@link #maybeAutoCreateLead}: any failure here is logged and
     * swallowed rather than propagated, since it must never roll back the
     * inbound message it's attached after.
     */
    private void maybeSendAutoReply(Company company, WhatsAppConversation conversation, WhatsAppConfiguration config) {
        if (config == null || conversation.getBranch() == null) return;
        if (!Boolean.TRUE.equals(config.getIsActive()) || !WhatsAppMessagingPolicy.isOpen(conversation.getLastCustomerMessageEpoch(), java.time.Instant.now())) return;

        try {
            LocalDateTime companyNow = scheduleService.nowInCompanyZone(company);
            if (conversation.getLastAutoReplyAt() != null
                    && conversation.getLastAutoReplyAt().toLocalDate().isEqual(companyNow.toLocalDate())) {
                return; // already replied today
            }

            Optional<WhatsAppScheduleService.ResolvedAutoReply> resolved = scheduleService.resolveAutoReply(conversation.getBranch());
            if (resolved.isEmpty()) return;
            WhatsAppScheduleService.ResolvedAutoReply reply = resolved.get();
            if (reply.text() == null || reply.text().isBlank()) return;

            String wamid;
            String replyMediaId = null;
            String replyType = "TEXT";
            String mediaMimeType = reply.mediaMimeType();
            if (reply.mediaData() != null && !reply.mediaData().isBlank()) {
                byte[] bytes = decodeDataUri(reply.mediaData());
                String mediaType = mediaMimeType != null && mediaMimeType.startsWith("video/") ? "video" : "image";
                String metaMediaId = graphApiClient.uploadMedia(config, bytes, mediaMimeType, "auto-reply");
                replyMediaId = metaMediaId; replyType = mediaType.toUpperCase();
                wamid = graphApiClient.sendMediaMessage(config, conversation.getCustomerWaId(), mediaType, metaMediaId, reply.text(), null);
            } else {
                wamid = graphApiClient.sendTextMessage(config, conversation.getCustomerWaId(), reply.text());
            }

            WhatsAppMessage autoReplyMsg = WhatsAppMessage.builder()
                    .conversation(conversation)
                    .wamid(wamid)
                    .direction(MessageDirection.OUTBOUND)
                    .messageType(replyType)
                    .mediaId(replyMediaId)
                    .mediaMimeType(mediaMimeType)
                    .isAutoReply(true)
                    .bodyText(reply.text())
                    .status(MessageStatus.ACCEPTED)
                    .build();
            WhatsAppMessage savedAutoReply = messageRepo.save(autoReplyMsg);

            conversation.setLastAutoReplyAt(companyNow);
            conversation.setLastMessageText(reply.text());
            conversation.setLastMessageAt(LocalDateTime.now());
            conversationRepo.save(conversation);

            broadcastConversationUpdate(conversation, savedAutoReply);
        } catch (Exception ex) {
            log.warn("Auto-reply skipped for conversation {}: {}", conversation.getId(), ex.getMessage());
        }
    }

    /**
     * Sends a plain-text message on behalf of an automation (no acting user):
     * same 24-hour-window rule as an agent's reply, recorded in the chat
     * history as an automatic message, and broadcast to open inboxes.
     * Throws with a readable reason when it can't be sent; the engine records
     * that reason on the execution step.
     */
    @Transactional
    public void sendAutomationText(Long conversationId, String text) {
        WhatsAppConversation conversation = conversationRepo.findForSending(conversationId)
                .orElseThrow(() -> new IllegalStateException("Conversation no longer exists."));
        entityManager.refresh(conversation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!WhatsAppMessagingPolicy.isOpen(conversation.getLastCustomerMessageEpoch(), java.time.Instant.now())) {
            throw new IllegalStateException("The customer's 24-hour reply window is closed.");
        }
        WhatsAppConfiguration config = conversation.getReceivingPhoneNumberId() != null
                ? configRepo.findByPhoneNumberId(conversation.getReceivingPhoneNumberId()).orElse(null) : null;
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            throw new IllegalStateException("This conversation's WhatsApp number isn't connected.");
        }

        String wamid = graphApiClient.sendTextMessage(config, conversation.getCustomerWaId(), text);

        WhatsAppMessage saved = messageRepo.save(WhatsAppMessage.builder()
                .conversation(conversation)
                .wamid(wamid)
                .direction(MessageDirection.OUTBOUND)
                .messageType("TEXT")
                .isAutoReply(true)
                .bodyText(text)
                .status(MessageStatus.ACCEPTED)
                .build());
        conversation.setLastMessageText(text);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepo.save(conversation);
        broadcastConversationUpdate(conversation, saved);
    }

    @Transactional
    public void sendAutomationInteractive(Long conversationId, String text, String interactiveType, com.fasterxml.jackson.databind.JsonNode action) {
        WhatsAppConversation conversation = conversationRepo.findForSending(conversationId)
                .orElseThrow(() -> new IllegalStateException("Conversation no longer exists."));
        entityManager.refresh(conversation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!WhatsAppMessagingPolicy.isOpen(conversation.getLastCustomerMessageEpoch(), java.time.Instant.now())) {
            throw new IllegalStateException("The customer's 24-hour reply window is closed.");
        }
        WhatsAppConfiguration config = conversation.getReceivingPhoneNumberId() != null
                ? configRepo.findByPhoneNumberId(conversation.getReceivingPhoneNumberId()).orElse(null) : null;
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            throw new IllegalStateException("This conversation's WhatsApp number isn't connected.");
        }

        String wamid;
        String msgType;
        if ("QUICK_REPLY".equalsIgnoreCase(interactiveType)) {
            msgType = "BUTTON";
            List<Map<String, String>> buttons = new ArrayList<>();
            var btnNodes = action.path("quickReplyButtons");
            if (btnNodes.isArray()) {
                for (var b : btnNodes) {
                    buttons.add(Map.of(
                            "id", b.path("id").asText(""),
                            "title", b.path("title").asText("")
                    ));
                }
            }
            if (buttons.isEmpty()) throw new IllegalStateException("Quick Reply requires at least 1 button.");
            wamid = graphApiClient.sendInteractiveButtons(config, conversation.getCustomerWaId(), text, buttons);
        } else if ("LIST".equalsIgnoreCase(interactiveType) || "LIST_MESSAGE".equalsIgnoreCase(interactiveType)) {
            msgType = "INTERACTIVE";
            String btnText = action.path("listButtonText").asText("View Options");
            String sectionTitle = action.path("listSectionTitle").asText("");
            List<Map<String, String>> rows = new ArrayList<>();
            var rowNodes = action.path("listRows");
            if (rowNodes.isArray()) {
                for (var r : rowNodes) {
                    Map<String, String> row = new java.util.HashMap<>();
                    row.put("id", r.path("id").asText(""));
                    row.put("title", r.path("title").asText(""));
                    if (r.hasNonNull("description") && !r.get("description").asText().isBlank()) {
                        row.put("description", r.get("description").asText());
                    }
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) throw new IllegalStateException("Interactive List requires at least 1 row option.");
            wamid = graphApiClient.sendInteractiveList(config, conversation.getCustomerWaId(), text, btnText, sectionTitle, rows);
        } else {
            throw new IllegalArgumentException("Unknown interactive type: " + interactiveType);
        }

        WhatsAppMessage saved = messageRepo.save(WhatsAppMessage.builder()
                .conversation(conversation)
                .wamid(wamid)
                .direction(MessageDirection.OUTBOUND)
                .messageType(msgType)
                .isAutoReply(true)
                .bodyText(text)
                .status(MessageStatus.ACCEPTED)
                .build());
        conversation.setLastMessageText(text);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversationRepo.save(conversation);
        broadcastConversationUpdate(conversation, saved);
    }

    /**
     * Assigns a conversation to an agent on behalf of an automation. The agent
     * must be an active user allowed into the conversation's company/branch.
     */
    @Transactional
    public void assignFromAutomation(Long conversationId, Long agentUserId) {
        WhatsAppConversation conversation = conversationRepo.findForSending(conversationId)
                .orElseThrow(() -> new IllegalStateException("Conversation no longer exists."));
        entityManager.refresh(conversation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        User agent = entityManager.find(User.class, agentUserId);
        boolean branchRestricted = agent != null && !access.isSuper(agent)
                && agent.getRole().getRoleLevel() == com.leads.model.enums.RoleLevel.BRANCH;
        if (agent == null || !Boolean.TRUE.equals(agent.getIsActive())
                || !access.canCompany(agent, conversation.getCompany())
                || (branchRestricted && (conversation.getBranch() == null
                    || agent.getAllowedBranches().stream().noneMatch(b -> b.getId().equals(conversation.getBranch().getId()))))) {
            throw new IllegalStateException("The selected agent can't be assigned to this conversation.");
        }
        conversation.setAssignedAgent(agent);
        conversationRepo.save(conversation);

        // Synchronize assignment with linked CRM Lead if present
        if (conversation.getLead() != null) {
            try {
                leadService.assignLeadFromAutomation(conversation.getLead().getId(), agent);
            } catch (Exception ex) {
                log.warn("Failed synchronizing lead assignment for conversation {}: {}", conversation.getId(), ex.getMessage());
            }
        }

        broadcast("/topic/whatsapp/company/" + conversation.getCompany().getId(), mapConversationToDto(conversation));
    }

    private byte[] decodeDataUri(String dataUri) {
        int comma = dataUri.indexOf(',');
        String base64 = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
        return java.util.Base64.getDecoder().decode(base64);
    }

    /**
     * Fills in the type-specific fields on an inbound message and returns
     * its chat-preview display text. Split out of processInboundMessage so
     * each Cloud API message shape (media, location, contacts, interactive
     * reply, quick-reply button, or plain text) has one clear branch instead
     * of a widening if/else chain mixed into the conversation/entity setup.
     */
    private String populateInboundContent(WhatsAppMessage.WhatsAppMessageBuilder builder, WhatsAppWebhookPayload.Message msg, String msgType) {
        if (msg.getText() != null) {
            String body = msg.getText().getBody();
            builder.bodyText(body);
            return body;
        }
        if (msg.getImage() != null) {
            builder.mediaId(msg.getImage().getId()).mediaMimeType(msg.getImage().getMime_type())
                    .bodyText(msg.getImage().getCaption());
            return msg.getImage().getCaption() != null ? msg.getImage().getCaption() : "📷 Photo";
        }
        if (msg.getVideo() != null) {
            builder.mediaId(msg.getVideo().getId()).mediaMimeType(msg.getVideo().getMime_type())
                    .bodyText(msg.getVideo().getCaption());
            return msg.getVideo().getCaption() != null ? msg.getVideo().getCaption() : "🎥 Video";
        }
        if (msg.getAudio() != null) {
            builder.mediaId(msg.getAudio().getId()).mediaMimeType(msg.getAudio().getMime_type());
            return "🎤 Voice message";
        }
        if (msg.getDocument() != null) {
            String filename = msg.getDocument().getFilename() != null ? msg.getDocument().getFilename() : "file";
            builder.mediaId(msg.getDocument().getId()).mediaMimeType(msg.getDocument().getMime_type())
                    .mediaFileName(filename).bodyText(msg.getDocument().getCaption());
            return "📎 " + filename;
        }
        if (msg.getSticker() != null) {
            builder.mediaId(msg.getSticker().getId()).mediaMimeType(msg.getSticker().getMime_type());
            return "Sticker";
        }
        if (msg.getLocation() != null) {
            WhatsAppWebhookPayload.Location loc = msg.getLocation();
            builder.latitude(loc.getLatitude()).longitude(loc.getLongitude())
                    .locationName(loc.getName()).locationAddress(loc.getAddress());
            return "📍 " + (loc.getName() != null ? loc.getName() : "Shared location");
        }
        if (msg.getContacts() != null && !msg.getContacts().isEmpty()) {
            // A single "contacts" message can carry several vCards — kept as
            // a structured list (name + every phone number) rather than
            // flattened into bodyText, so the UI can render one card per
            // contact instead of a bare name with no way to reach them.
            List<WhatsAppContactDto> contacts = msg.getContacts().stream()
                    .map(c -> WhatsAppContactDto.builder()
                            .name(c.getName() != null && c.getName().getFormattedName() != null
                                    ? c.getName().getFormattedName() : "Unnamed contact")
                            .phones(c.getPhones() == null ? List.of() : c.getPhones().stream()
                                    .map(WhatsAppWebhookPayload.ContactPhone::getPhone)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(Collectors.toList()))
                            .build())
                    .collect(Collectors.toList());

            try {
                builder.contactsJson(objectMapper.writeValueAsString(contacts));
            } catch (Exception ex) {
                log.warn("Failed serializing shared contacts for wamid {} — falling back to plain text.", msg.getId(), ex);
            }

            String names = contacts.stream().map(WhatsAppContactDto::getName).collect(Collectors.joining(", "));
            builder.bodyText(names);
            return "👤 " + (contacts.size() > 1 ? contacts.size() + " contacts shared" : "Contact: " + names);
        }
        if (msg.getInteractive() != null) {
            WhatsAppWebhookPayload.InteractiveReply reply = "list_reply".equals(msg.getInteractive().getType())
                    ? msg.getInteractive().getListReply() : msg.getInteractive().getButtonReply();
            String title = reply != null ? reply.getTitle() : "Selected an option";
            builder.bodyText(title);
            return title;
        }
        if (msg.getButton() != null) {
            String text = msg.getButton().getText();
            builder.bodyText(text);
            return text;
        }
        // type=unsupported (WhatsApp client feature the Cloud API can't relay
        // content for — polls, view-once media, etc.) always carries Meta's
        // own errors[] explanation; use it instead of a bare "UNSUPPORTED"
        // label whenever it's there, for any msgType, not just this one.
        if (msg.getErrors() != null && !msg.getErrors().isEmpty()) {
            WhatsAppWebhookPayload.MessageError err = msg.getErrors().get(0);
            String reason = err.getError_data() != null && err.getError_data().getDetails() != null
                    ? err.getError_data().getDetails()
                    : (err.getMessage() != null ? err.getMessage() : err.getTitle());
            if (reason != null && !reason.isBlank()) {
                String text = "Unsupported message: " + reason;
                builder.bodyText(text);
                return text;
            }
        }
        builder.bodyText("[Unsupported message type: " + msgType + "]");
        return "[Unsupported message: " + msgType + "]";
    }

    /**
     * A reaction event refers to an existing message by wamid rather than
     * starting a new bubble — the target message is updated in place and
     * re-broadcast so the UI patches the existing bubble instead of
     * appending a duplicate. An empty emoji means the reaction was removed.
     */
    @Transactional
    public void processInboundReaction(WhatsAppWebhookPayload.Reaction reaction) {
        if (reaction.getMessageId() == null) return;
        messageRepo.findByWamid(reaction.getMessageId()).ifPresentOrElse(target -> {
            target.setReactionEmoji(reaction.getEmoji() == null || reaction.getEmoji().isBlank() ? null : reaction.getEmoji());
            target.setReactionTargetWamid(reaction.getMessageId());
            messageRepo.save(target);
            broadcast("/topic/whatsapp/conversation/" + target.getConversation().getId(), mapMessageToDto(target));
        }, () -> log.warn("Reaction '{}' targets unknown wamid '{}' — ignored.", reaction.getEmoji(), reaction.getMessageId()));
    }

    @Transactional
    public WhatsAppMessageDto sendMessage(SendMessageRequest req) {
        access.record(com.leads.model.entity.WhatsAppConversation.class, req.getConversationId());
        WhatsAppConversation conversation = conversationRepo.findForSending(req.getConversationId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + req.getConversationId()));
        entityManager.refresh(conversation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

        validateClientId(req.getClientMessageId());
        if (req.getClientMessageId() != null) {
            var existing = messageRepo.findByConversationIdAndClientMessageId(conversation.getId(), req.getClientMessageId());
            if (existing.isPresent()) return mapMessageToDto(existing.get());
        }

        User currentUser = authService.getCurrentUser();
        String messageType = req.getMessageType() != null ? req.getMessageType().toUpperCase() : "TEXT";
        if (!java.util.Set.of("TEXT", "TEMPLATE").contains(messageType)) throw new IllegalArgumentException("Use the media upload endpoint for attachments.");
        if (!"TEMPLATE".equals(messageType)) WhatsAppMessagingPolicy.requireOpen(conversation);

        // Reply from the exact number the customer originally messaged (a
        // branch can now have several numbers, so "the branch's number" is no
        // longer unambiguous — the conversation's own receivingPhoneNumberId
        // is the only reliable way to pick the right one). If that number
        // isn't configured (or Meta rejects the call — bad/expired token,
        // etc.), the message is still recorded so the agent's reply isn't
        // lost, but marked FAILED with the reason instead of silently
        // pretending it was delivered.
        WhatsAppConfiguration config = conversation.getReceivingPhoneNumberId() != null
                ? configRepo.findByPhoneNumberId(conversation.getReceivingPhoneNumberId()).orElse(null)
                : null;

        com.fasterxml.jackson.databind.JsonNode liveTemplate = null;
        if ("TEMPLATE".equals(messageType) && config != null && Boolean.TRUE.equals(config.getIsActive())) {
            var live = graphApiClient.listTemplates(config).stream().filter(t ->
                java.util.Objects.equals(req.getTemplateName(), t.path("name").asText()) &&
                java.util.Objects.equals(req.getTemplateLanguage() == null ? "en_US" : req.getTemplateLanguage(), t.path("language").asText()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Template not found in this number's business account. Refresh templates."));
            WhatsAppTemplateSendPolicy.validate(live, req);
            liveTemplate = live;
        }
        String wamid;
        MessageStatus status;
        String errorMessage = null;
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            // UUID suffix, not just a millisecond timestamp — wamid now has a
            // DB-level unique constraint (idx_msg_wamid), and two failed
            // sends landing in the same millisecond would otherwise collide.
            wamid = "wamid.local." + java.util.UUID.randomUUID();
            status = MessageStatus.FAILED;
            errorMessage = "This conversation's WhatsApp number isn't connected — set it up under WhatsApp Setup.";
        } else {
            try {
                List<Map<String, Object>> templateComponents = null;
                if (req.getTemplateBodyParams() != null && !req.getTemplateBodyParams().isEmpty()) {
                    List<Map<String, Object>> parameters = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    if (liveTemplate != null && "NAMED".equals(liveTemplate.path("parameter_format").asText()))
                        for (var c : liveTemplate.path("components")) if ("BODY".equals(c.path("type").asText())) names = WhatsAppTemplateValidation.variableNames(c.path("text").asText());
                    for (int i = 0; i < req.getTemplateBodyParams().size(); i++) {
                        Map<String, Object> parameter = new java.util.LinkedHashMap<>();
                        parameter.put("type", "text"); parameter.put("text", req.getTemplateBodyParams().get(i));
                        if (!names.isEmpty()) parameter.put("parameter_name", names.get(i));
                        parameters.add(parameter);
                    }
                    templateComponents = List.of(Map.of(
                            "type", "body",
                            "parameters", parameters
                    ));
                }
                wamid = "TEMPLATE".equals(messageType)
                        ? graphApiClient.sendTemplateMessage(config, conversation.getCustomerWaId(), req.getTemplateName(),
                                req.getTemplateLanguage() != null ? req.getTemplateLanguage() : "en_US", templateComponents)
                        : graphApiClient.sendTextMessage(config, conversation.getCustomerWaId(), req.getMessage());
                status = MessageStatus.ACCEPTED;
            } catch (WhatsAppApiException ex) {
                wamid = "wamid.failed." + java.util.UUID.randomUUID();
                status = ex.getCause() instanceof org.springframework.web.client.ResourceAccessException ? MessageStatus.UNKNOWN : MessageStatus.FAILED;
                errorMessage = ex.getMessage() != null && ex.getMessage().length() > 255
                        ? ex.getMessage().substring(0, 255) : ex.getMessage();
            }
        }

        // Save Outbound Message
        WhatsAppMessage message = WhatsAppMessage.builder()
                .conversation(conversation)
                .wamid(wamid)
                .direction(MessageDirection.OUTBOUND)
                .messageType(messageType)
                .templateName("TEMPLATE".equals(messageType) ? req.getTemplateName() : null)
                .templateCategory(liveTemplate != null ? liveTemplate.path("category").asText(null) : null)
                .templateLanguage(liveTemplate != null ? liveTemplate.path("language").asText(null) : null)
                .clientMessageId(req.getClientMessageId())
                .bodyText(req.getMessage())
                .mediaUrl(req.getMediaUrl())
                .status(status)
                .errorMessage(errorMessage)
                .sentByUser(currentUser)
                .build();

        WhatsAppMessage saved = messageRepo.save(message);

        conversation.setLastMessageText(req.getMessage());
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setLastMessageSentBy(currentUser);
        conversationRepo.save(conversation);

        WhatsAppMessageDto messageDto = mapMessageToDto(saved);
        messageDto.setClientMessageId(req.getClientMessageId());

        // Realtime broadcast to live chat UI
        broadcast("/topic/whatsapp/conversation/" + conversation.getId(), messageDto);
        broadcast("/topic/whatsapp/company/" + conversation.getCompany().getId(), mapConversationToDto(conversation));

        return messageDto;
    }

    @Transactional
    public WhatsAppMessageDto sendMediaMessage(Long conversationId, byte[] fileBytes, String originalFilename, String contentType, String caption, String clientMessageId) {
        access.record(com.leads.model.entity.WhatsAppConversation.class, conversationId);
        WhatsAppConversation conversation = conversationRepo.findForSending(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        entityManager.refresh(conversation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

        validateClientId(clientMessageId);
        if (clientMessageId != null) {
            var existing = messageRepo.findByConversationIdAndClientMessageId(conversationId, clientMessageId);
            if (existing.isPresent()) return mapMessageToDto(existing.get());
        }

        WhatsAppMessagingPolicy.requireOpen(conversation);

        User currentUser = authService.getCurrentUser();
        String mimeType = WhatsAppAttachmentPolicy.validate(fileBytes, originalFilename, contentType, caption);
        String mediaType = mimeType.startsWith("image/") ? "image"
                : mimeType.startsWith("video/") ? "video"
                : mimeType.startsWith("audio/") ? "audio"
                : "document";

        WhatsAppConfiguration config = conversation.getReceivingPhoneNumberId() != null
                ? configRepo.findByPhoneNumberId(conversation.getReceivingPhoneNumberId()).orElse(null)
                : null;

        String wamid;
        String metaMediaId = null;
        MessageStatus status;
        String errorMessage = null;
        if (config == null || !Boolean.TRUE.equals(config.getIsActive())) {
            wamid = "wamid.local." + java.util.UUID.randomUUID();
            status = MessageStatus.FAILED;
            errorMessage = "This conversation's WhatsApp number isn't connected — set it up under WhatsApp Setup.";
        } else {
            try {
                metaMediaId = graphApiClient.uploadMedia(config, fileBytes, mimeType, originalFilename);
                wamid = graphApiClient.sendMediaMessage(config, conversation.getCustomerWaId(), mediaType, metaMediaId, caption, originalFilename);
                status = MessageStatus.ACCEPTED;
            } catch (WhatsAppApiException ex) {
                wamid = "wamid.failed." + java.util.UUID.randomUUID();
                status = ex.getCause() instanceof org.springframework.web.client.ResourceAccessException ? MessageStatus.UNKNOWN : MessageStatus.FAILED;
                errorMessage = ex.getMessage() != null && ex.getMessage().length() > 255
                        ? ex.getMessage().substring(0, 255) : ex.getMessage();
            }
        }

        WhatsAppMessage message = WhatsAppMessage.builder()
                .conversation(conversation)
                .wamid(wamid)
                .direction(MessageDirection.OUTBOUND)
                .messageType(mediaType.toUpperCase())
                .clientMessageId(clientMessageId)
                .bodyText(caption)
                .mediaId(metaMediaId)
                .mediaMimeType(mimeType)
                .mediaFileName(originalFilename)
                .status(status)
                .errorMessage(errorMessage)
                .sentByUser(currentUser)
                .build();

        WhatsAppMessage saved = messageRepo.save(message);

        // We already hold the bytes we just uploaded — cache them locally
        // now so the sender's own view of this message never needs a
        // round trip back to Meta to render it.
        if (status == MessageStatus.ACCEPTED) {
            mediaService.cacheOutboundMedia(saved.getId(), mimeType, fileBytes);
        }

        conversation.setLastMessageText(caption != null && !caption.isBlank() ? caption : previewTextFor(mediaType, originalFilename));
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setLastMessageSentBy(currentUser);
        conversationRepo.save(conversation);

        WhatsAppMessageDto messageDto = mapMessageToDto(saved);
        messageDto.setClientMessageId(clientMessageId);
        broadcast("/topic/whatsapp/conversation/" + conversation.getId(), messageDto);
        broadcast("/topic/whatsapp/company/" + conversation.getCompany().getId(), mapConversationToDto(conversation));

        return messageDto;
    }

    private String previewTextFor(String mediaType, String filename) {
        return switch (mediaType) {
            case "image" -> "📷 Photo";
            case "video" -> "🎥 Video";
            case "audio" -> "🎤 Voice message";
            default -> "📎 " + (filename != null ? filename : "file");
        };
    }

    private void validateClientId(String id) {
        if (id != null && (id.isBlank() || id.length() > 100)) throw new IllegalArgumentException("Invalid client message ID.");
    }

    @Transactional
    public LeadDto createLeadFromWhatsAppChat(CreateLeadFromChatRequest req) {
        access.record(com.leads.model.entity.WhatsAppConversation.class, req.getConversationId());
        WhatsAppConversation conversation = conversationRepo.findById(req.getConversationId())
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + req.getConversationId()));

        // An explicit source pick (from the "Start New Conversation" flow,
        // which isn't necessarily a genuine inbound-WhatsApp lead) always
        // wins. Otherwise, same ad-vs-plain distinction maybeAutoCreateLead
        // already makes — a manually-created lead from an ad-originated
        // conversation must still land on WHATSAPP_MARKETING or it's
        // silently unattributable.
        Long companyId = conversation.getCompany().getId();
        LeadSource source;
        if (req.getSourceId() != null) {
            source = sourceRepo.findById(req.getSourceId()).orElse(null);
        } else {
            String sourceCode = Boolean.TRUE.equals(conversation.getIsFromAd()) ? "WHATSAPP_MARKETING" : "WHATSAPP";
            source = leadSettingsService.getSystemSource(companyId, sourceCode);
        }

        LeadCreateRequest leadReq = LeadCreateRequest.builder()
                .companyId(companyId)
                .branchId(req.getBranchId() != null ? req.getBranchId() : (conversation.getBranch() != null ? conversation.getBranch().getId() : 1L))
                .firstName(req.getFirstName())
                .lastName(req.getLastName())
                .email(req.getEmail())
                .phone(req.getPhone())
                .whatsappNumber(conversation.getCustomerWaId())
                .customerCompanyName(req.getCustomerCompanyName())
                .jobTitle(req.getJobTitle())
                .sourceId(source != null ? source.getId() : null)
                .purposeId(req.getPurposeId())
                .statusId(req.getStatusId())
                .assignedToUserId(req.getAssignedToUserId() != null ? req.getAssignedToUserId()
                        : (conversation.getAssignedAgent() != null ? conversation.getAssignedAgent().getId() : null))
                .estimatedDealValue(req.getEstimatedDealValue())
                .closingDate(req.getClosingDate())
                .notes(req.getNotes() != null ? req.getNotes() : "Lead automatically initiated from WhatsApp Chat with " + conversation.getCustomerName())
                .dynamicValues(req.getDynamicValues())
                .build();

        LeadDto leadDto = leadService.createLead(leadReq);

        // Set lead on conversation. Must be a real JPA reference (not a
        // bare Lead.builder().id(...).build() stub) - Lead is @Version'd,
        // and Hibernate's flush-time cascade check can't tell a manually
        // built stub apart from a transient (unsaved) entity when its
        // version field is null, throwing PropertyValueException
        // ("uninitialized version value") on the next flush instead of
        // treating it as the existing row it actually is.
        conversation.setLead(leadRepo.getReferenceById(leadDto.getId()));
        conversationRepo.save(conversation);
        leadService.linkAdAndClid(leadDto.getId(), companyId, conversation.getAdSourceId(), conversation.getCtwaClid());

        // Record WhatsApp chat history link on lead timeline
        User currentUser = authService.getCurrentUser();
        leadService.recordActivity(leadRepo.getReferenceById(leadDto.getId()), ActivityType.WHATSAPP_MSG,
                "WhatsApp Lead Linked",
                "Converted from live WhatsApp conversation with " + conversation.getCustomerName() + " (" + conversation.getCustomerWaId() + ")", currentUser);

        // Broadcast conversation update with linked lead
        broadcast("/topic/whatsapp/company/" + conversation.getCompany().getId(), mapConversationToDto(conversation));

        return leadDto;
    }

    /**
     * The WhatsApp Inbox's conversation list — filter pills (all/unread/
     * active leads), a free-text search across contact name, number, and
     * message history, and (outside of search) real offset pagination for
     * lazy-load-on-scroll, so an inbox with thousands of contacts never
     * loads them all at once.
     * <p>
     * Search is handled differently: ranking contact matches ahead of
     * message-only matches (mirroring WhatsApp's own search UX) needs the
     * whole matching set in memory to sort, so it's a single capped fetch
     * (see {@link #SEARCH_RESULT_CAP}) rather than offset pagination.
     */
    @Transactional(readOnly = true)
    public ConversationListResponseDto getConversations(Long companyId, Long configId, String filter, String search, int page, int size) {
        access.company(companyId);
        if (configId != null) access.record(WhatsAppConfiguration.class, configId);
        String phoneNumberId = configId != null
                ? configRepo.findById(configId)
                        .orElseThrow(() -> new IllegalArgumentException("WhatsApp number not found: " + configId))
                        .getPhoneNumberId()
                : null;

        size = Math.max(1, Math.min(size, 100));
        page = Math.max(0, page);
        Long currentUserId = authService.getCurrentUser().getId();
        ConversationCountsDto counts = ConversationCountsDto.builder()
                .all(conversationRepo.count(buildConversationSpecification(companyId, phoneNumberId, "ALL", null, currentUserId)))
                .unread(conversationRepo.count(buildConversationSpecification(companyId, phoneNumberId, "UNREAD", null, currentUserId)))
                .activeLeads(conversationRepo.count(buildConversationSpecification(companyId, phoneNumberId, "ACTIVE_LEADS", null, currentUserId)))
                .assignedToMe(conversationRepo.count(buildConversationSpecification(companyId, phoneNumberId, "ASSIGNED_TO_ME", null, currentUserId)))
                .build();

        String trimmedSearch = search != null ? search.trim() : null;
        boolean searching = trimmedSearch != null && !trimmedSearch.isEmpty();
        Specification<WhatsAppConversation> spec = buildConversationSpecification(companyId, phoneNumberId, filter, searching ? trimmedSearch : null, currentUserId);

        List<WhatsAppConversation> hydrated;
        long totalElements;
        boolean hasMore;

        Page<WhatsAppConversation> pageResult = conversationRepo.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "pinned", "lastMessageAt", "id")));
        hydrated = hydrateLeadAndAgent(pageResult.getContent());
        totalElements = pageResult.getTotalElements();
        hasMore = pageResult.hasNext();

        List<WhatsAppConversationDto> dtos = hydrated.stream().map(this::mapConversationToDto).collect(Collectors.toList());
        return ConversationListResponseDto.builder().content(dtos).totalElements(totalElements).hasMore(hasMore).counts(counts).build();
    }

    private Specification<WhatsAppConversation> buildConversationSpecification(Long companyId, String phoneNumberId, String filter, String search, Long currentUserId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List<Long> branchIds = access.branchIds();
            if (branchIds != null) predicates.add(root.get("branch").get("id").in(branchIds));
            predicates.add(cb.equal(root.get("company").get("id"), companyId));

            // Blocked customers drop out of the inbox entirely (CRM chat
            // "Block Customer" action) - matched by phone number since
            // WhatsAppConversation has no direct Customer FK (a conversation
            // can exist long before any lead/customer record does).
            jakarta.persistence.criteria.Subquery<Long> blockedSub = query.subquery(Long.class);
            jakarta.persistence.criteria.Root<Customer> blockedCustomerRoot = blockedSub.from(Customer.class);
            blockedSub.select(blockedCustomerRoot.get("id"));
            blockedSub.where(cb.and(
                    cb.equal(blockedCustomerRoot.get("company").get("id"), companyId),
                    cb.equal(blockedCustomerRoot.get("mobileNormalized"), root.get("customerWaId")),
                    cb.isTrue(blockedCustomerRoot.get("isBlocked"))
            ));
            predicates.add(cb.not(cb.exists(blockedSub)));

            if (phoneNumberId != null) {
                predicates.add(cb.equal(root.get("receivingPhoneNumberId"), phoneNumberId));
            }

            if ("UNREAD".equalsIgnoreCase(filter)) {
                predicates.add(cb.or(cb.greaterThan(root.get("unreadCount"), 0), cb.isTrue(root.get("manuallyUnread"))));
            } else if ("ACTIVE_LEADS".equalsIgnoreCase(filter)) {
                // Same terminal stages as the inbox linked-lead badge and avatar ring.
                Join<WhatsAppConversation, Lead> leadJoin = root.join("lead", jakarta.persistence.criteria.JoinType.INNER);
                predicates.add(cb.isFalse(leadJoin.get("isConverted")));
                predicates.add(leadJoin.get("status").get("stageType").in(StageType.CONVERTED, StageType.LOST, StageType.CLOSED).not());
            } else if ("ASSIGNED_TO_ME".equalsIgnoreCase(filter)) {
                Join<WhatsAppConversation, Lead> leadJoin = root.join("lead", jakarta.persistence.criteria.JoinType.INNER);
                predicates.add(cb.equal(leadJoin.get("assignedTo").get("id"), currentUserId));
            }

            if (search != null) {
                String likeTerm = "%" + search.toLowerCase() + "%";
                String digits = search.replaceAll("\\D", "");
                List<Predicate> searchOr = new ArrayList<>();
                searchOr.add(cb.like(cb.lower(root.get("customerName")), likeTerm));
                if (!digits.isEmpty()) {
                    searchOr.add(cb.like(root.get("customerWaId"), "%" + digits + "%"));
                }
                Subquery<Long> messageMatch = query.subquery(Long.class);
                var messageRoot = messageMatch.from(WhatsAppMessage.class);
                messageMatch.select(messageRoot.get("id"))
                        .where(cb.equal(messageRoot.get("conversation"), root), cb.like(cb.lower(messageRoot.get("bodyText")), likeTerm));
                searchOr.add(cb.exists(messageMatch));
                predicates.add(cb.or(searchOr.toArray(new Predicate[0])));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private boolean isContactMatch(WhatsAppConversation c, String lowerSearch, String searchDigits) {
        boolean nameMatch = c.getCustomerName() != null && c.getCustomerName().toLowerCase().contains(lowerSearch);
        boolean numberMatch = !searchDigits.isEmpty() && c.getCustomerWaId() != null && c.getCustomerWaId().contains(searchDigits);
        return nameMatch || numberMatch;
    }

    /**
     * lead/assignedAgent are LAZY, and a Specification-driven Page query
     * can't carry its own fetch joins (Spring Data would apply them to the
     * derived COUNT query too) — so a page of ids is hydrated in one extra
     * bulk query instead, same N+1-avoidance as the fetch joins on the
     * simpler company-wide/number-wide finder methods above.
     */
    private List<WhatsAppConversation> hydrateLeadAndAgent(List<WhatsAppConversation> page) {
        if (page.isEmpty()) return page;
        List<Long> ids = page.stream().map(WhatsAppConversation::getId).collect(Collectors.toList());
        Map<Long, WhatsAppConversation> byId = conversationRepo.findByIdInWithLeadAndAgent(ids).stream()
                .collect(Collectors.toMap(WhatsAppConversation::getId, c -> c));
        return page.stream().map(c -> byId.getOrDefault(c.getId(), c)).collect(Collectors.toList());
    }

    /**
     * Gets-or-creates the conversation for a phone number on a specific
     * connected WhatsApp number — the entry point for a business-initiated
     * chat (the customer has never messaged in), as opposed to the normal
     * inbound-first path in {@link #processInboundMessage}. Sending the
     * actual opening template message is a separate call to
     * {@link #sendMessage} against the conversation id this returns, so it
     * reuses all of that method's Graph API/status/broadcast handling
     * instead of duplicating it here.
     */
    @Transactional
    public WhatsAppConversationDto startConversation(StartConversationRequest req) {
        access.record(com.leads.model.entity.WhatsAppConfiguration.class, req.getConfigId());
        WhatsAppConfiguration config = configRepo.findById(req.getConfigId())
                .orElseThrow(() -> new IllegalArgumentException("WhatsApp number not found: " + req.getConfigId()));

        String customerWaId = req.getPhone().replaceAll("\\D", "");
        if (customerWaId.length() < 8) {
            throw new IllegalArgumentException("Enter a valid phone number, including country code.");
        }

        WhatsAppConversation conversation = conversationRepo
                .findByCompanyIdAndCustomerWaIdAndReceivingPhoneNumberId(config.getCompany().getId(), customerWaId, config.getPhoneNumberId())
                .orElseGet(() -> conversationRepo.save(WhatsAppConversation.builder()
                        .company(config.getCompany())
                        .branch(config.getBranch())
                        .receivingPhoneNumberId(config.getPhoneNumberId())
                        .customerWaId(customerWaId)
                        .customerName(req.getCustomerName().trim())
                        .chatStatus("OPEN")
                        .unreadCount(0)
                        .build()));

        return mapConversationToDto(conversation);
    }

    /** Backs the Lead/Customer chat side panel — finds the conversation for a phone number ignoring country code. */
    @Transactional(readOnly = true)
    public WhatsAppConversationDto findConversationByPhone(Long companyId, String phone) {
        access.company(companyId);
        String normalized = PhoneNumberUtil.lastSignificantDigits(phone);
        return conversationRepo.findByCompanyIdOrderByLastMessageAtDesc(companyId).stream()
                .filter(c -> c.getBranch() != null && access.canBranch(c.getBranch()))
                .filter(c -> PhoneNumberUtil.lastSignificantDigits(c.getCustomerWaId()).equals(normalized))
                .findFirst()
                .map(this::mapConversationToDto)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public WhatsAppConversationDto getConversation(Long id) {
        return mapConversationToDto(access.record(WhatsAppConversation.class, id));
    }

    @Transactional(readOnly = true)
    public Page<WhatsAppMessageDto> getConversationMessages(Long conversationId, Pageable pageable) {
        access.record(com.leads.model.entity.WhatsAppConversation.class, conversationId);
        // Query DESC (newest first, cheap via idx_msg_conversation_created) then
        // reverse the page's contents to ascending for chronological display —
        // avoids an unpaginated full-history scan on long-lived conversations.
        Page<WhatsAppMessage> page = messageRepo.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId, pageable);
        List<WhatsAppMessageDto> ascending = page.getContent().stream()
                .map(this::mapMessageToDto)
                .collect(Collectors.toList());
        Collections.reverse(ascending);
        return new PageImpl<>(ascending, pageable, page.getTotalElements());
    }

    @Transactional
    public void markConversationRead(Long conversationId) {
        access.record(com.leads.model.entity.WhatsAppConversation.class, conversationId);
        conversationRepo.findForSending(conversationId).ifPresent(c -> {
            c.setUnreadCount(0); c.setManuallyUnread(false);
            conversationRepo.save(c);
            // Broadcast so every open tab/session (header unread badge,
            // dashboard alert, other agents' inbox lists) drops this
            // conversation's count immediately instead of staying stale until
            // their next full conversations reload.
            broadcast("/topic/whatsapp/company/" + c.getCompany().getId(), mapConversationToDto(c));
        });
    }

    /**
     * "Block Customer" from the chat's overflow menu. Hides this customer's
     * conversation from the inbox from now on (see
     * buildConversationSpecification's blocked-customer exclusion) without
     * deleting any message history - unblocking (from the Customer list)
     * restores it.
     */
    @Transactional
    public void blockConversationCustomer(Long conversationId) {
        WhatsAppConversation conversation = access.record(com.leads.model.entity.WhatsAppConversation.class, conversationId);
        customerService.blockCustomerByPhone(conversation.getCompany(), conversation.getBranch(),
                conversation.getCustomerName(), conversation.getCustomerWaId());
    }

    private void broadcastConversationUpdate(WhatsAppConversation conversation, WhatsAppMessage newMsg) {
        WhatsAppConversationDto convDto = mapConversationToDto(conversation);
        WhatsAppMessageDto msgDto = mapMessageToDto(newMsg);

        broadcast("/topic/whatsapp/company/" + conversation.getCompany().getId(), convDto);
        broadcast("/topic/whatsapp/conversation/" + conversation.getId(), msgDto);
    }

    private void broadcast(String topic, Object payload) {
        Runnable publish = () -> {
            messagingTemplate.convertAndSend(topic, payload);
            if (payload instanceof WhatsAppConversationDto dto && dto.getBranchId()!=null && topic.contains("/company/"))
                messagingTemplate.convertAndSend("/topic/whatsapp/branch/"+dto.getBranchId(), dto);
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
    }

    @Transactional
    public WhatsAppConversationDto updateInboxState(Long id, Boolean pinned, Boolean unread) {
        access.record(WhatsAppConversation.class, id);
        WhatsAppConversation conversation = conversationRepo.findForSending(id)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found."));
        if (pinned != null) conversation.setPinned(pinned);
        if (unread != null) conversation.setManuallyUnread(unread);
        conversationRepo.save(conversation);
        WhatsAppConversationDto dto = mapConversationToDto(conversation);
        broadcast("/topic/whatsapp/company/" + conversation.getCompany().getId(), dto);
        return dto;
    }

    public WhatsAppConversationDto mapConversationToDto(WhatsAppConversation c) {
        if (c == null) return null;
        Lead lead = c.getLead();
        Long customerId = lead != null && lead.getCustomer() != null ? lead.getCustomer().getId() : null;
        if (customerId == null) {
            String normalized = PhoneNumberUtil.lastSignificantDigits(c.getCustomerWaId());
            customerId = customerRepo.findByCompanyIdAndMobileNormalized(c.getCompany().getId(), normalized)
                    .map(Customer::getId).orElse(null);
        }
        return WhatsAppConversationDto.builder()
                .id(c.getId())
                .companyId(c.getCompany().getId())
                .branchId(c.getBranch() != null ? c.getBranch().getId() : null)
                .branchName(c.getBranch() != null ? c.getBranch().getName() : null)
                .customerWaId(c.getCustomerWaId())
                .customerName(c.getCustomerName())
                .leadId(lead != null ? lead.getId() : null)
                .leadNumber(lead != null ? lead.getLeadNumber() : null)
                .leadIsClosed(lead != null && isLeadClosed(lead))
                .leadPurposeClassification(lead != null && !isLeadClosed(lead) && lead.getPurpose() != null ? lead.getPurpose().getClassification() : null)
                .leadAssignedToUserId(lead != null && lead.getAssignedTo() != null ? lead.getAssignedTo().getId() : null)
                .leadAssignedToName(lead != null && lead.getAssignedTo() != null ? lead.getAssignedTo().getFullName() : null)
                .customerId(customerId)
                .assignedAgentId(c.getAssignedAgent() != null ? c.getAssignedAgent().getId() : null)
                .assignedAgentName(c.getAssignedAgent() != null ? c.getAssignedAgent().getFullName() : null)
                .lastMessageText(c.getLastMessageText())
                .lastMessageSentByName(c.getLastMessageSentBy() != null ? c.getLastMessageSentBy().getFullName() : null)
                .lastCustomerMessageEpoch(c.getLastCustomerMessageEpoch())
                .replyWindowExpiresAt(c.getLastCustomerMessageEpoch() == null ? null : java.time.Instant.ofEpochSecond(c.getLastCustomerMessageEpoch()).plusSeconds(86400))
                .serverTime(java.time.Instant.now())
                .freeFormAllowed(WhatsAppMessagingPolicy.isOpen(c.getLastCustomerMessageEpoch(), java.time.Instant.now()))
                .lastMessageAt(c.getLastMessageAt())
                .unreadCount(c.isManuallyUnread() ? Math.max(1, c.getUnreadCount()) : c.getUnreadCount()).pinned(c.isPinned()).manuallyUnread(c.isManuallyUnread())
                .chatStatus(c.getChatStatus())
                .isFromAd(c.getIsFromAd())
                .adHeadline(c.getAdHeadline())
                .adSourceUrl(c.getAdSourceUrl())
                .build();
    }

    public WhatsAppMessageDto mapMessageToDto(WhatsAppMessage m) {
        if (m == null) return null;
        return WhatsAppMessageDto.builder()
                .id(m.getId())
                .conversationId(m.getConversation().getId())
                .wamid(m.getWamid())
                .clientMessageId(m.getClientMessageId())
                .direction(m.getDirection())
                .messageType(m.getMessageType())
                .bodyText(m.getBodyText())
                .mediaUrl(m.getMediaUrl())
                .mediaMimeType(m.getMediaMimeType())
                .mediaFileName(m.getMediaFileName())
                .mediaId(m.getMediaId())
                .latitude(m.getLatitude())
                .longitude(m.getLongitude())
                .locationName(m.getLocationName())
                .locationAddress(m.getLocationAddress())
                .contacts(parseContactsJson(m.getContactsJson()))
                .reactionEmoji(m.getReactionEmoji())
                .reactionTargetWamid(m.getReactionTargetWamid())
                .contextWamid(m.getContextWamid())
                .isFromAd(m.getIsFromAd())
                .adHeadline(m.getAdHeadline())
                .adSourceUrl(m.getAdSourceUrl())
                .adSourceType(m.getAdSourceType())
                .ctwaClid(m.getCtwaClid())
                .adSourceId(m.getAdSourceId())
                .status(m.getStatus())
                .errorMessage(m.getErrorMessage())
                .sentByUserId(m.getSentByUser() != null ? m.getSentByUser().getId() : null)
                .sentByUserName(m.getSentByUser() != null ? m.getSentByUser().getFullName() : null)
                .isAutoReply(m.getIsAutoReply())
                .broadcastId(m.getBroadcast() != null ? m.getBroadcast().getId() : null)
                .detectedLanguage(m.getDetectedLanguage())
                .createdAt(m.getCreatedAt())
                .build();
    }

    private List<WhatsAppContactDto> parseContactsJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, WhatsAppContactDto.class));
        } catch (Exception ex) {
            log.warn("Failed parsing stored contactsJson — returning none.", ex);
            return null;
        }
    }
}
