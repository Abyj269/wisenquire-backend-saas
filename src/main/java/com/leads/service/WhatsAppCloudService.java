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
                // Module 12 P1: an unknown or inactive receiving number skips THIS change only (was: the whole batch failed).
                var config = configRepo.findByPhoneNumberId(number).filter(c -> Boolean.TRUE.equals(c.getIsActive())).orElse(null);
                if (config == null) {
                    log.warn("Webhook change for unknown or inactive receiving number {} skipped", number);
                    continue;
                }
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
                        // S3-d: the auto-reply manages its own short transactions; no transaction is open during its Graph call.
                        try { messageRepo.findByWamid(msg.getId()).map(saved -> saved.getConversation().getId()).ifPresent(cid -> maybeSendAutoReply(cid, number)); }
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
     * The branch auto-reply after an inbound message. S3-d: decided in a short read, then reserved, sent
     * with no transaction open, and recorded; any failure is logged and leaves no history row (as before).
     * Called OUTSIDE the webhook's per-message transaction.
     */
    private void maybeSendAutoReply(Long conversationId, String receivingNumber) {
        record Plan(WhatsAppConfiguration config, String waId, WhatsAppScheduleService.ResolvedAutoReply reply, LocalDateTime companyNow) { }
        Long reservedId = null;
        try {
            var readOnly = tx();
            readOnly.setReadOnly(true);
            Plan plan = readOnly.execute(st -> {
                WhatsAppConversation conversation = conversationRepo.findById(conversationId).orElse(null);
                WhatsAppConfiguration config = configRepo.findByPhoneNumberId(receivingNumber).orElse(null);
                if (conversation == null || config == null || conversation.getBranch() == null) return null;
                if (!Boolean.TRUE.equals(config.getIsActive()) || !WhatsAppMessagingPolicy.isOpen(conversation.getLastCustomerMessageEpoch(), java.time.Instant.now())) return null;
                LocalDateTime companyNow = scheduleService.nowInCompanyZone(conversation.getCompany());
                if (conversation.getLastAutoReplyAt() != null && conversation.getLastAutoReplyAt().toLocalDate().isEqual(companyNow.toLocalDate())) return null; // already replied today
                var resolved = scheduleService.resolveAutoReply(conversation.getBranch());
                if (resolved.isEmpty() || resolved.get().text() == null || resolved.get().text().isBlank()) return null;
                return new Plan(config, conversation.getCustomerWaId(), resolved.get(), companyNow);
            });
            if (plan == null) return;
            var reply = plan.reply();
            boolean withMedia = reply.mediaData() != null && !reply.mediaData().isBlank();
            String mediaType = withMedia && reply.mediaMimeType() != null && reply.mediaMimeType().startsWith("video/") ? "video" : "image";
            Reserved reserved = reserve(conversationId, null, conversation -> WhatsAppMessage.builder()
                    .messageType(withMedia ? mediaType.toUpperCase() : "TEXT").mediaMimeType(reply.mediaMimeType())
                    .isAutoReply(true).bodyText(reply.text()));
            reservedId = reserved.messageId();
            Sent sent = attempt(() -> {
                if (withMedia) {
                    String metaMediaId = graphApiClient.uploadMedia(plan.config(), decodeDataUri(reply.mediaData()), reply.mediaMimeType(), "auto-reply");
                    return new String[]{graphApiClient.sendMediaMessage(plan.config(), plan.waId(), mediaType, metaMediaId, reply.text(), null), metaMediaId};
                }
                return new String[]{graphApiClient.sendTextMessage(plan.config(), plan.waId(), reply.text())};
            });
            if (sent.status() != MessageStatus.ACCEPTED) throw new WhatsAppApiException(sent.error());
            complete(reservedId, sent, conversation -> {
                conversation.setLastAutoReplyAt(plan.companyNow());
                conversation.setLastMessageText(reply.text());
            });
        } catch (Exception ex) {
            if (reservedId != null) {
                try { discard(reservedId); } catch (RuntimeException ignored) { /* best effort */ }
            }
            log.warn("Auto-reply skipped for conversation {}: {}", conversationId, ex.getMessage());
        }
    }

    /**
     * Sends a plain-text message on behalf of an automation (no acting user): same 24-hour-window rule as
     * an agent's reply, recorded as an automatic message and broadcast. Throws with a readable reason when
     * it can't be sent (no history row then), as before. S3-d: no transaction during the Graph call.
     */
    public void sendAutomationText(Long conversationId, String text) {
        SendTarget target = automationTarget(conversationId);
        Reserved reserved = reserve(conversationId, null, conversation -> WhatsAppMessage.builder()
                .messageType("TEXT").isAutoReply(true).bodyText(text));
        Sent sent = attempt(() -> new String[]{graphApiClient.sendTextMessage(target.config(), target.customerWaId(), text)});
        finishAutomation(reserved.messageId(), sent, text);
    }

    /** Window open and number connected, read in a short transaction (IllegalStateException otherwise, as before). */
    private SendTarget automationTarget(Long conversationId) {
        SendTarget target;
        try {
            target = readTarget(conversationId, false, conversation -> {
                if (!WhatsAppMessagingPolicy.isOpen(conversation.getLastCustomerMessageEpoch(), java.time.Instant.now())) {
                    throw new IllegalStateException("The customer's 24-hour reply window is closed.");
                }
            });
        } catch (IllegalArgumentException notFound) {
            throw new IllegalStateException("Conversation no longer exists.");
        }
        if (target.config() == null) throw new IllegalStateException("This conversation's WhatsApp number isn't connected.");
        return target;
    }

    private void finishAutomation(Long messageId, Sent sent, String text) {
        if (sent.status() != MessageStatus.ACCEPTED) {
            discard(messageId);
            throw new WhatsAppApiException(sent.error() != null ? sent.error() : "WhatsApp did not accept the message.");
        }
        complete(messageId, sent, conversation -> conversation.setLastMessageText(text));
    }

    public void sendAutomationInteractive(Long conversationId, String text, String interactiveType, com.fasterxml.jackson.databind.JsonNode action) {
        SendTarget target = automationTarget(conversationId);
        String msgType;
        java.util.concurrent.Callable<String[]> call;
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
            call = () -> new String[]{graphApiClient.sendInteractiveButtons(target.config(), target.customerWaId(), text, buttons)};
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
            call = () -> new String[]{graphApiClient.sendInteractiveList(target.config(), target.customerWaId(), text, btnText, sectionTitle, rows)};
        } else {
            throw new IllegalArgumentException("Unknown interactive type: " + interactiveType);
        }
        Reserved reserved = reserve(conversationId, null, conversation -> WhatsAppMessage.builder()
                .messageType(msgType).isAutoReply(true).bodyText(text));
        finishAutomation(reserved.messageId(), attempt(call), text);
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


    // ==== S3-d (module 12 P1 part 2, CLAUDE.md hard rule 2) ===========================================
    // A send is: (1) a short transaction that checks the conversation and reserves a PENDING message
    // (the unique (conversation_id, client_message_id) key still stops double sends); (2) the Graph
    // calls with NO transaction or row lock open; (3) a short transaction that records the result and
    // updates the conversation. Receipts that arrive before (3) are applied by the reconciler later.

    /** Where a send goes, read in a short transaction: the config (null = not connected) and the customer. */
    private record SendTarget(WhatsAppConfiguration config, String customerWaId, WhatsAppMessageDto existing) { }

    /** The reserved PENDING message, or the earlier message for a repeated clientMessageId. */
    private record Reserved(Long messageId, WhatsAppMessageDto existing) { }

    /** What Graph answered. */
    private record Sent(String wamid, MessageStatus status, String error, String mediaId) {
        static Sent notConnected() {
            return new Sent("wamid.local." + java.util.UUID.randomUUID(), MessageStatus.FAILED,
                    "This conversation's WhatsApp number isn't connected — set it up under WhatsApp Setup.", null);
        }
    }

    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(transactions);
    }

    private WhatsAppConversation lockedConversation(Long conversationId) {
        WhatsAppConversation conversation = conversationRepo.findForSending(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        entityManager.refresh(conversation, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE); // held only inside this short transaction
        return conversation;
    }

    private WhatsAppConfiguration activeConfigOf(WhatsAppConversation conversation) {
        WhatsAppConfiguration config = conversation.getReceivingPhoneNumberId() != null
                ? configRepo.findByPhoneNumberId(conversation.getReceivingPhoneNumberId()).orElse(null) : null;
        return config != null && Boolean.TRUE.equals(config.getIsActive()) ? config : null;
    }

    /** Step 1: checks inside a short transaction, then saves a PENDING message (or returns the earlier one). */
    private Reserved reserve(Long conversationId, String clientMessageId,
                             java.util.function.Function<WhatsAppConversation, WhatsAppMessage.WhatsAppMessageBuilder> draft) {
        return tx().execute(st -> {
            WhatsAppConversation conversation = lockedConversation(conversationId);
            if (clientMessageId != null) {
                var existing = messageRepo.findByConversationIdAndClientMessageId(conversationId, clientMessageId);
                if (existing.isPresent()) return new Reserved(null, mapMessageToDto(existing.get()));
            }
            WhatsAppMessage pending = draft.apply(conversation)
                    .conversation(conversation)
                    .direction(MessageDirection.OUTBOUND)
                    .wamid("wamid.pending." + java.util.UUID.randomUUID())
                    .status(MessageStatus.PENDING)
                    .build();
            return new Reserved(messageRepo.save(pending).getId(), null);
        });
    }

    /** Step 2: the Graph call, with no transaction open; Meta failures become FAILED/UNKNOWN as before. */
    private Sent attempt(java.util.concurrent.Callable<String[]> graphCall) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A WhatsApp send was started inside a transaction (CLAUDE.md hard rule 2).");
        }
        try {
            String[] r = graphCall.call(); // [wamid, mediaId]
            return new Sent(r[0], MessageStatus.ACCEPTED, null, r.length > 1 ? r[1] : null);
        } catch (WhatsAppApiException ex) {
            String error = ex.getMessage() != null && ex.getMessage().length() > 255 ? ex.getMessage().substring(0, 255) : ex.getMessage();
            return new Sent("wamid.failed." + java.util.UUID.randomUUID(),
                    ex.getCause() instanceof org.springframework.web.client.ResourceAccessException ? MessageStatus.UNKNOWN : MessageStatus.FAILED, error, null);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Step 3: records the result, updates the conversation and broadcasts after commit. */
    private WhatsAppMessageDto complete(Long messageId, Sent sent, java.util.function.Consumer<WhatsAppConversation> conversationUpdate) {
        return tx().execute(st -> {
            WhatsAppMessage message = messageRepo.findById(messageId).orElseThrow();
            WhatsAppConversation conversation = lockedConversation(message.getConversation().getId());
            message.setWamid(sent.wamid());
            message.setStatus(sent.status());
            message.setErrorMessage(sent.error());
            if (sent.mediaId() != null) message.setMediaId(sent.mediaId());
            WhatsAppMessage saved = messageRepo.save(message);
            conversationUpdate.accept(conversation);
            conversation.setLastMessageAt(LocalDateTime.now());
            conversationRepo.save(conversation);
            WhatsAppMessageDto dto = mapMessageToDto(saved);
            dto.setClientMessageId(saved.getClientMessageId());
            broadcastConversationUpdate(conversation, saved);
            return dto;
        });
    }

    /** Automation/auto-reply failures leave no history row (as before): the reservation is removed. */
    private void discard(Long messageId) {
        tx().executeWithoutResult(st -> messageRepo.deleteById(messageId));
    }

    private SendTarget readTarget(Long conversationId, boolean checkAccess, java.util.function.Consumer<WhatsAppConversation> checks) {
        return readTarget(conversationId, checkAccess, null, checks);
    }

    /** A repeated clientMessageId returns the stored message before any other check (as before S3-d). */
    private SendTarget readTarget(Long conversationId, boolean checkAccess, String clientMessageId, java.util.function.Consumer<WhatsAppConversation> checks) {
        // A normal (not read-only) short transaction: the clientMessageId lookup takes a row lock (FOR UPDATE).
        return tx().execute(st -> {
            if (checkAccess) access.record(WhatsAppConversation.class, conversationId);
            WhatsAppConversation conversation = conversationRepo.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
            if (clientMessageId != null) {
                var existing = messageRepo.findByConversationIdAndClientMessageId(conversationId, clientMessageId);
                if (existing.isPresent()) return new SendTarget(null, null, mapMessageToDto(existing.get()));
            }
            checks.accept(conversation);
            return new SendTarget(activeConfigOf(conversation), conversation.getCustomerWaId(), null);
        });
    }

    /** Agent reply or template send. S3-d: no transaction or lock is open during the Graph calls. */
    public WhatsAppMessageDto sendMessage(SendMessageRequest req) {
        String messageType = req.getMessageType() != null ? req.getMessageType().toUpperCase() : "TEXT";
        if (!java.util.Set.of("TEXT", "TEMPLATE").contains(messageType)) throw new IllegalArgumentException("Use the media upload endpoint for attachments.");
        validateClientId(req.getClientMessageId());
        // Reply from the exact number the customer originally messaged (a branch can have several numbers).
        SendTarget target = readTarget(req.getConversationId(), true, req.getClientMessageId(), conversation -> {
            if (!"TEMPLATE".equals(messageType)) WhatsAppMessagingPolicy.requireOpen(conversation);
        });
        if (target.existing() != null) return target.existing();

        // The live template is checked before anything is saved (a wrong template is a 400, not a FAILED message).
        com.fasterxml.jackson.databind.JsonNode liveTemplate = null;
        if ("TEMPLATE".equals(messageType) && target.config() != null) {
            var live = graphApiClient.listTemplates(target.config()).stream().filter(t ->
                java.util.Objects.equals(req.getTemplateName(), t.path("name").asText()) &&
                java.util.Objects.equals(req.getTemplateLanguage() == null ? "en_US" : req.getTemplateLanguage(), t.path("language").asText()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Template not found in this number's business account. Refresh templates."));
            WhatsAppTemplateSendPolicy.validate(live, req);
            liveTemplate = live;
        }
        final com.fasterxml.jackson.databind.JsonNode template = liveTemplate;
        User currentUser = authService.getCurrentUser();

        Reserved reserved = reserve(req.getConversationId(), req.getClientMessageId(), conversation -> WhatsAppMessage.builder()
                .messageType(messageType)
                .templateName("TEMPLATE".equals(messageType) ? req.getTemplateName() : null)
                .templateCategory(template != null ? template.path("category").asText(null) : null)
                .templateLanguage(template != null ? template.path("language").asText(null) : null)
                .clientMessageId(req.getClientMessageId())
                .bodyText(req.getMessage())
                .mediaUrl(req.getMediaUrl())
                .sentByUser(currentUser));
        if (reserved.existing() != null) return reserved.existing();

        Sent sent = target.config() == null ? Sent.notConnected() : attempt(() -> {
            List<Map<String, Object>> templateComponents = null;
            if (req.getTemplateBodyParams() != null && !req.getTemplateBodyParams().isEmpty()) {
                List<Map<String, Object>> parameters = new ArrayList<>();
                List<String> names = new ArrayList<>();
                if (template != null && "NAMED".equals(template.path("parameter_format").asText()))
                    for (var c : template.path("components")) if ("BODY".equals(c.path("type").asText())) names = WhatsAppTemplateValidation.variableNames(c.path("text").asText());
                for (int i = 0; i < req.getTemplateBodyParams().size(); i++) {
                    Map<String, Object> parameter = new java.util.LinkedHashMap<>();
                    parameter.put("type", "text"); parameter.put("text", req.getTemplateBodyParams().get(i));
                    if (!names.isEmpty()) parameter.put("parameter_name", names.get(i));
                    parameters.add(parameter);
                }
                templateComponents = List.of(Map.of("type", "body", "parameters", parameters));
            }
            String wamid = "TEMPLATE".equals(messageType)
                    ? graphApiClient.sendTemplateMessage(target.config(), target.customerWaId(), req.getTemplateName(),
                            req.getTemplateLanguage() != null ? req.getTemplateLanguage() : "en_US", templateComponents)
                    : graphApiClient.sendTextMessage(target.config(), target.customerWaId(), req.getMessage());
            return new String[]{wamid};
        });

        return complete(reserved.messageId(), sent, conversation -> {
            conversation.setLastMessageText(req.getMessage());
            conversation.setLastMessageSentBy(currentUser);
        });
    }

    /** Agent attachment send (also used by the quotation PDF send). S3-d: upload and send outside any transaction. */
    public WhatsAppMessageDto sendMediaMessage(Long conversationId, byte[] fileBytes, String originalFilename, String contentType, String caption, String clientMessageId) {
        validateClientId(clientMessageId);
        String mimeType = WhatsAppAttachmentPolicy.validate(fileBytes, originalFilename, contentType, caption);
        String mediaType = mimeType.startsWith("image/") ? "image"
                : mimeType.startsWith("video/") ? "video"
                : mimeType.startsWith("audio/") ? "audio"
                : "document";
        SendTarget target = readTarget(conversationId, true, clientMessageId, WhatsAppMessagingPolicy::requireOpen);
        if (target.existing() != null) return target.existing();
        User currentUser = authService.getCurrentUser();

        Reserved reserved = reserve(conversationId, clientMessageId, conversation -> WhatsAppMessage.builder()
                .messageType(mediaType.toUpperCase())
                .clientMessageId(clientMessageId)
                .bodyText(caption)
                .mediaMimeType(mimeType)
                .mediaFileName(originalFilename)
                .sentByUser(currentUser));
        if (reserved.existing() != null) return reserved.existing();

        Sent sent = target.config() == null ? Sent.notConnected() : attempt(() -> {
            String metaMediaId = graphApiClient.uploadMedia(target.config(), fileBytes, mimeType, originalFilename);
            return new String[]{graphApiClient.sendMediaMessage(target.config(), target.customerWaId(), mediaType, metaMediaId, caption, originalFilename), metaMediaId};
        });

        // We already hold the bytes we just uploaded: cache them so the sender's own view never round-trips to Meta.
        if (sent.status() == MessageStatus.ACCEPTED) mediaService.cacheOutboundMedia(reserved.messageId(), mimeType, fileBytes);
        return complete(reserved.messageId(), sent, conversation -> {
            conversation.setLastMessageText(caption != null && !caption.isBlank() ? caption : previewTextFor(mediaType, originalFilename));
            conversation.setLastMessageSentBy(currentUser);
        });
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
        // D14 (module 12 P1): OWN users list what record(WhatsAppConversation) lets them open.
        final Long ownMe = access.ownerFilter();
        final boolean ownUnassigned = ownMe != null && access.ownIncludesUnassigned();
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List<Long> branchIds = access.branchIds();
            if (branchIds != null) predicates.add(root.get("branch").get("id").in(branchIds));
            if (ownMe != null) {
                var agent = root.join("assignedAgent", jakarta.persistence.criteria.JoinType.LEFT);
                var leadOwner = root.join("lead", jakarta.persistence.criteria.JoinType.LEFT).join("assignedTo", jakarta.persistence.criteria.JoinType.LEFT);
                Predicate mine = cb.or(cb.equal(agent.get("id"), ownMe), cb.equal(leadOwner.get("id"), ownMe));
                predicates.add(ownUnassigned ? cb.or(mine, cb.and(cb.isNull(agent.get("id")), cb.isNull(leadOwner.get("id")))) : mine);
            }
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
                // Module 12 P1: the chat's agent is me OR its lead is mine (was: the lead's assignee only), so
                // the filter matches the OWN rule and a manager who is a chat's agent sees it here too.
                var meAgent = root.join("assignedAgent", jakarta.persistence.criteria.JoinType.LEFT);
                var meLeadOwner = root.join("lead", jakarta.persistence.criteria.JoinType.LEFT).join("assignedTo", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.or(cb.equal(meAgent.get("id"), currentUserId), cb.equal(meLeadOwner.get("id"), currentUserId)));
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

        // D14 (module 12 P1): an existing conversation must pass the OWN check (it was returned to anyone
        // who knew the phone number); a new one started by an OWN user is theirs.
        var existing = conversationRepo.findByCompanyIdAndCustomerWaIdAndReceivingPhoneNumberId(config.getCompany().getId(), customerWaId, config.getPhoneNumberId());
        if (existing.isPresent()) access.record(WhatsAppConversation.class, existing.get().getId());
        final User ownStarter = access.ownerFilter() != null ? access.actor() : null;
        WhatsAppConversation conversation = existing
                .orElseGet(() -> conversationRepo.save(WhatsAppConversation.builder()
                        .assignedAgent(ownStarter)
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
                .filter(access::canSeeConversation) // D14 (module 12 P1)
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
        pushToUsers(pushRecipients(conversation), convDto);
    }

    /** Module 12 P1: the per-user queue OWN users subscribe to instead of the company/branch topics. */
    public static final String USER_QUEUE = "/queue/whatsapp";

    /**
     * Module 12 P1 (D14 OWN-PUSH): who gets a conversation update on their own queue: the chat's agent and
     * the linked lead's assignee (STOMP principal = email until module 02 makes it the user id).
     */
    static java.util.Set<String> pushRecipients(WhatsAppConversation c) {
        java.util.Set<String> to = new java.util.LinkedHashSet<>();
        if (c == null) return to;
        if (c.getAssignedAgent() != null && c.getAssignedAgent().getEmail() != null) to.add(c.getAssignedAgent().getEmail());
        if (c.getLead() != null && c.getLead().getAssignedTo() != null && c.getLead().getAssignedTo().getEmail() != null) to.add(c.getLead().getAssignedTo().getEmail());
        return to;
    }

    /** After commit (like broadcast), so a rolled-back change is never pushed. */
    void pushToUsers(java.util.Set<String> users, Object payload) {
        if (users.isEmpty()) return;
        Runnable publish = () -> users.forEach(u -> messagingTemplate.convertAndSendToUser(u, USER_QUEUE, payload));
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
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
