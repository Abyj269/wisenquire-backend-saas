package com.leads.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.dto.broadcast.*;
import com.leads.exception.WhatsAppApiException;
import com.leads.model.entity.*;
import com.leads.model.enums.BroadcastAudienceType;
import com.leads.model.enums.BroadcastRecipientStatus;
import com.leads.model.enums.BroadcastStatus;
import com.leads.model.enums.MessageDirection;
import com.leads.model.enums.MessageStatus;
import com.leads.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastServiceImpl implements BroadcastService {

    private final BroadcastRepository broadcastRepo;
    private final BroadcastRecipientRepository recipientRepo;
    private final CompanyRepository companyRepo;
    private final BranchRepository branchRepo;
    private final WhatsAppConfigurationRepository configRepo;
    private final CustomerRepository customerRepo;
    private final LeadRepository leadRepo;
    private final TenantAccess access;
    private final AuthService authService;
    private final WhatsAppGraphApiClient graphApiClient;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final WhatsAppConversationRepository conversationRepo;
    private final WhatsAppMessageRepository messageRepo;

    @Qualifier("broadcastExecutor")
    private final ExecutorService broadcastExecutor;

    @Override
    @Transactional
    public BroadcastDto createBroadcast(BroadcastCreateRequest request) {
        access.permission("BROADCASTS:ADD");

        // F-1: company, branch and WhatsApp number must all be inside the caller's scope and belong together.
        Company company = access.company(request.getCompanyId());

        Branch branch = null;
        if (request.getBranchId() != null) {
            branch = access.branch(request.getBranchId());
            access.sameCompany(company.getId(), branch.getCompany());
        } else {
            access.companyWide(company.getId());
        }

        WhatsAppConfiguration config = access.record(WhatsAppConfiguration.class, request.getWhatsappConfigurationId());
        boolean configMatches = Objects.equals(config.getCompany().getId(), company.getId())
                && (branch == null || Objects.equals(config.getBranch().getId(), branch.getId()));
        if (!configMatches) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "The selected WhatsApp number does not belong to this company/branch.");
        }

        User currentUser = authService.getCurrentUser();

        String componentsJson = null;
        if (request.getComponents() != null && !request.getComponents().isEmpty()) {
            try {
                componentsJson = objectMapper.writeValueAsString(request.getComponents());
            } catch (Exception ex) {
                log.warn("Error serializing components JSON", ex);
            }
        }

        BroadcastStatus initialStatus = request.getScheduledAt() != null && request.getScheduledAt().isAfter(LocalDateTime.now())
                ? BroadcastStatus.SCHEDULED
                : BroadcastStatus.DRAFT;

        Broadcast broadcast = Broadcast.builder()
                .company(company)
                .branch(branch)
                .whatsappConfiguration(config)
                .createdBy(currentUser)
                .name(request.getName())
                .templateName(request.getTemplateName())
                .templateLanguage(request.getTemplateLanguage() != null && !request.getTemplateLanguage().isBlank() ? request.getTemplateLanguage() : "en")
                .templateComponentsJson(componentsJson)
                .audienceType(request.getAudienceType())
                .audienceFilterJson(request.getAudienceFilterJson())
                .status(initialStatus)
                .scheduledAt(request.getScheduledAt())
                .build();

        Broadcast saved = broadcastRepo.save(broadcast);

        // Resolve recipients and save initial PENDING records
        List<BroadcastRecipient> recipients = resolveRecipients(saved, request);
        saved.setTotalRecipients(recipients.size());
        recipientRepo.saveAll(recipients);

        return mapToDto(broadcastRepo.save(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public BroadcastDto getBroadcastById(Long id) {
        access.permission("BROADCASTS:VIEW");
        access.record(Broadcast.class, id); // F-1
        Broadcast broadcast = broadcastRepo.findById(id)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Broadcast not found: " + id));
        return mapToDto(broadcast);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BroadcastDto> getBroadcasts(Long companyId, Long branchId, Pageable pageable) {
        access.permission("BROADCASTS:VIEW");
        access.scope(companyId, branchId); // F-1
        Page<Broadcast> page;
        if (branchId != null) {
            page = broadcastRepo.findByCompanyIdAndBranchIdAndDeletedAtIsNull(companyId, branchId, pageable);
        } else {
            List<Long> permittedBranches = access.branchIds(); // null = no branch restriction for this caller
            page = permittedBranches == null
                    ? broadcastRepo.findByCompanyIdAndDeletedAtIsNull(companyId, pageable)
                    : permittedBranches.isEmpty()
                        ? Page.empty(pageable)
                        : broadcastRepo.findByCompanyIdAndBranchIdInAndDeletedAtIsNull(companyId, permittedBranches, pageable);
        }
        return page.map(this::mapToDto);
    }

    @Override
    @Transactional
    public BroadcastDto sendBroadcast(Long broadcastId) {
        access.permission("BROADCASTS:SEND");
        access.record(Broadcast.class, broadcastId); // F-1 (also passes under the scheduler's inCompany scope)
        Broadcast broadcast = broadcastRepo.findById(broadcastId)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Broadcast not found: " + broadcastId));

        if (broadcast.getStatus() == BroadcastStatus.COMPLETED) {
            throw new IllegalStateException("Broadcast is already COMPLETED.");
        }
        // Guard against double-trigger (e.g. scheduler + manual send racing)
        if (broadcast.getStatus() == BroadcastStatus.SENDING) {
            log.warn("Broadcast ID {} is already SENDING — ignoring duplicate trigger.", broadcastId);
            return mapToDto(broadcast);
        }

        broadcast.setStatus(BroadcastStatus.SENDING);
        broadcast.setSentAt(LocalDateTime.now());
        Broadcast saved = broadcastRepo.save(broadcast);
        final Long savedId = saved.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("afterCommit: submitting broadcast ID {} to executor", savedId);
                    broadcastExecutor.submit(() -> executeBroadcastSend(savedId));
                }
            });
        } else {
            log.info("No active sync — submitting broadcast ID {} to executor directly", savedId);
            broadcastExecutor.submit(() -> executeBroadcastSend(savedId));
        }

        return mapToDto(saved);
    }

    public void executeBroadcastSend(Long broadcastId) {
        log.info("[BROADCAST] Starting broadcast send execution for ID {}", broadcastId);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            Broadcast broadcast;
            try {
                broadcast = broadcastRepo.findByIdWithConfig(broadcastId).orElse(null);
                if (broadcast == null || broadcast.getDeletedAt() != null) {
                    log.warn("[BROADCAST] Broadcast ID {} not found or deleted — aborting.", broadcastId);
                    return;
                }
            } catch (Exception ex) {
                log.error("[BROADCAST] Failed to load broadcast ID {} for sending", broadcastId, ex);
                return;
            }

            WhatsAppConfiguration config = broadcast.getWhatsappConfiguration();
            if (config == null) {
                log.error("[BROADCAST] No WhatsApp configuration linked to broadcast ID {} — marking FAILED", broadcastId);
                broadcast.setStatus(BroadcastStatus.FAILED);
                broadcastRepo.save(broadcast);
                return;
            }
            if (!Boolean.TRUE.equals(config.getIsActive())) {
                log.error("[BROADCAST] WhatsApp configuration ID {} is inactive for broadcast ID {} — marking FAILED",
                        config.getId(), broadcastId);
                broadcast.setStatus(BroadcastStatus.FAILED);
                broadcastRepo.save(broadcast);
                return;
            }

            log.info("[BROADCAST] Using WhatsApp config ID={} phoneNumberId={} for broadcast ID {}",
                    config.getId(), config.getPhoneNumberId(), broadcastId);

            // Resolve the exact Meta-registered language code (e.g. "en" vs "en_US")
            String targetLanguage = broadcast.getTemplateLanguage() != null ? broadcast.getTemplateLanguage() : "en";
            try {
                List<com.fasterxml.jackson.databind.JsonNode> liveTemplates = graphApiClient.listTemplates(config);
                log.info("[BROADCAST] Retrieved {} live Meta templates for broadcast ID {}", liveTemplates.size(), broadcastId);
                for (var t : liveTemplates) {
                    if (java.util.Objects.equals(broadcast.getTemplateName(), t.path("name").asText())) {
                        String metaLang = t.path("language").asText();
                        if (metaLang != null && !metaLang.isBlank()) {
                            targetLanguage = metaLang;
                            log.info("[BROADCAST] Resolved template '{}' Meta language: '{}'", broadcast.getTemplateName(), targetLanguage);
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("[BROADCAST] Could not query Meta live templates (lang verification skipped): {}", ex.getMessage());
            }

            // Parse the template components JSON stored at broadcast creation time.
            // These are component-level binding descriptors, not pre-filled values.
            // resolveComponentsForRecipient() substitutes per-recipient values at send time.
            List<BroadcastComponentDto> componentBindings = parseComponentDtos(broadcast.getTemplateComponentsJson());

            List<BroadcastRecipient> recipients = recipientRepo.findByBroadcastIdAndStatus(broadcastId, BroadcastRecipientStatus.PENDING);
            log.info("[BROADCAST] Found {} PENDING recipients for broadcast ID {}", recipients.size(), broadcastId);

            if (recipients.isEmpty()) {
                log.warn("[BROADCAST] No PENDING recipients for broadcast ID {} — marking COMPLETED with 0 sent", broadcastId);
                broadcast.setStatus(BroadcastStatus.COMPLETED);
                broadcast.setCompletedAt(LocalDateTime.now());
                broadcastRepo.save(broadcast);
                return;
            }

            int sentCount = 0;
            int failedCount = 0;

            for (BroadcastRecipient recipient : recipients) {
                try {
                    String cleanPhone = com.leads.util.PhoneNumberUtil.lastSignificantDigits(recipient.getRecipientPhone());
                    if (cleanPhone == null || cleanPhone.isBlank()) {
                        throw new IllegalArgumentException("Invalid or missing phone number: " + recipient.getRecipientPhone());
                    }

                    // ── Issue #2 fix: resolve variable bindings per recipient ──
                    List<Map<String, Object>> resolvedComponents = resolveComponentsForRecipient(componentBindings, recipient);

                    log.info("[BROADCAST] Sending template '{}' (lang={}) to '{}'",
                            broadcast.getTemplateName(), targetLanguage, cleanPhone);
                    String wamid = graphApiClient.sendTemplateMessage(
                            config,
                            cleanPhone,
                            broadcast.getTemplateName(),
                            targetLanguage,
                            resolvedComponents
                    );

                    log.info("[BROADCAST] ✓ Sent to '{}' — wamid: {}", cleanPhone, wamid);
                    recipient.setWamid(wamid);
                    recipient.setStatus(BroadcastRecipientStatus.SENT);
                    recipient.setSentAt(LocalDateTime.now());
                    sentCount++;

                    // ── Issue #3 fix: link broadcast message to WhatsApp chat ──
                    try {
                        linkBroadcastMessageToChat(config, cleanPhone, broadcast, recipient, wamid, targetLanguage);
                    } catch (Exception ex) {
                        // Never let chat-link errors abort the broadcast itself
                        log.warn("[BROADCAST] ⚠ Could not link wamid {} to chat for '{}': {}", wamid, cleanPhone, ex.getMessage());
                    }

                } catch (WhatsAppApiException ex) {
                    log.error("[BROADCAST] ✗ WhatsApp API error for '{}': {}", recipient.getRecipientPhone(), ex.getMessage());
                    recipient.setStatus(BroadcastRecipientStatus.FAILED);
                    recipient.setFailedAt(LocalDateTime.now());
                    recipient.setErrorMessage(ex.getMessage() != null && ex.getMessage().length() > 500
                            ? ex.getMessage().substring(0, 500) : ex.getMessage());
                    failedCount++;
                } catch (Exception ex) {
                    log.error("[BROADCAST] ✗ Unexpected error for '{}'", recipient.getRecipientPhone(), ex);
                    recipient.setStatus(BroadcastRecipientStatus.FAILED);
                    recipient.setFailedAt(LocalDateTime.now());
                    recipient.setErrorMessage("Unexpected error: " + ex.getMessage());
                    failedCount++;
                }
                recipientRepo.save(recipient);
            }

            broadcast.setSentCount(broadcast.getSentCount() + sentCount);
            broadcast.setFailedCount(broadcast.getFailedCount() + failedCount);
            broadcast.setStatus(BroadcastStatus.COMPLETED);
            broadcast.setCompletedAt(LocalDateTime.now());
            broadcastRepo.save(broadcast);
            log.info("[BROADCAST] ✓ Completed broadcast ID {}: sent={}, failed={}", broadcastId, sentCount, failedCount);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BroadcastRecipientDto> getBroadcastLogs(Long broadcastId, Pageable pageable) {
        access.permission("BROADCASTS:VIEW");
        Broadcast broadcast = access.record(Broadcast.class, broadcastId); // F-1
        if (broadcast.getDeletedAt() != null) {
            throw new IllegalArgumentException("Broadcast not found: " + broadcastId);
        }
        Page<BroadcastRecipient> page = recipientRepo.findByBroadcastId(broadcastId, pageable);
        return page.map(this::mapRecipientToDto);
    }

    @Override
    @Transactional
    public void deleteBroadcast(Long broadcastId) {
        access.permission("BROADCASTS:DELETE");
        access.record(Broadcast.class, broadcastId); // F-1
        Broadcast broadcast = broadcastRepo.findById(broadcastId)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Broadcast not found: " + broadcastId));
        broadcast.setDeletedAt(LocalDateTime.now());
        broadcastRepo.save(broadcast);
    }

    /**
     * Updates delivery/read/failure status for a single broadcast recipient from a Meta webhook receipt.
     *
     * Uses PROPAGATION_REQUIRES_NEW so each recipient update runs in its own independent transaction.
     * This is critical: without it, if two receipts arrive in the same 2-second reconcile window,
     * an OptimisticLockException on one recipient's broadcast.setDeliveredCount() save would roll
     * back ALL updates in the reconcile() transaction — losing every recipient's delivered/read data.
     *
     * Broadcast aggregate counts (deliveredCount, readCount, failedCount) are updated via direct
     * SQL increments to avoid optimistic locking conflicts when multiple recipients update the same
     * broadcast row concurrently.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateRecipientStatusFromWebhook(String wamid, String statusStr, String errorDetail) {
        if (wamid == null || statusStr == null) return;

        BroadcastRecipient recipient = recipientRepo.findByWamid(wamid).orElse(null);
        if (recipient == null) return;

        BroadcastRecipientStatus nextStatus;
        try {
            nextStatus = BroadcastRecipientStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            log.warn("[BROADCAST WEBHOOK] Unknown status '{}' for wamid={} — skipping", statusStr, wamid);
            return;
        }

        BroadcastRecipientStatus currentStatus = recipient.getStatus();
        if (currentStatus == nextStatus) {
            log.debug("[BROADCAST WEBHOOK] wamid={} already has status {} — no-op", wamid, currentStatus);
            return;
        }

        // Prevent regressing: READ > DELIVERED, DELIVERED > SENT, etc.
        if (currentStatus == BroadcastRecipientStatus.READ) return;
        if (currentStatus == BroadcastRecipientStatus.DELIVERED
                && nextStatus != BroadcastRecipientStatus.READ) return;

        Long broadcastId = recipient.getBroadcast().getId(); // proxy ID access — safe, no full load needed
        LocalDateTime now = LocalDateTime.now();

        log.info("[BROADCAST WEBHOOK] wamid={} status {} → {}", wamid, currentStatus, nextStatus);

        if (nextStatus == BroadcastRecipientStatus.DELIVERED) {
            recipient.setStatus(BroadcastRecipientStatus.DELIVERED);
            recipient.setDeliveredAt(now);
            recipientRepo.save(recipient);
            // Atomic SQL increment to avoid @Version / OptimisticLockException
            broadcastRepo.incrementDeliveredCount(broadcastId);

        } else if (nextStatus == BroadcastRecipientStatus.READ) {
            // If not already marked DELIVERED, count it as delivered too
            if (currentStatus != BroadcastRecipientStatus.DELIVERED) {
                if (recipient.getDeliveredAt() == null) recipient.setDeliveredAt(now);
                broadcastRepo.incrementDeliveredCount(broadcastId);
            }
            recipient.setStatus(BroadcastRecipientStatus.READ);
            recipient.setReadAt(now);
            recipientRepo.save(recipient);
            broadcastRepo.incrementReadCount(broadcastId);

        } else if (nextStatus == BroadcastRecipientStatus.FAILED) {
            recipient.setStatus(BroadcastRecipientStatus.FAILED);
            recipient.setFailedAt(now);
            recipient.setErrorMessage(errorDetail);
            recipientRepo.save(recipient);
            broadcastRepo.incrementFailedCount(broadcastId);
        }
    }

    private List<BroadcastRecipient> resolveRecipients(Broadcast broadcast, BroadcastCreateRequest request) {
        List<BroadcastRecipient> recipients = new ArrayList<>();
        Set<String> seenPhones = new HashSet<>();

        if (request.getAudienceType() == BroadcastAudienceType.ALL_CUSTOMERS) {
            List<Customer> customers = customerRepo.findByCompanyId(broadcast.getCompany().getId());
            for (Customer c : customers) {
                if (c.getMobileNormalized() != null && seenPhones.add(c.getMobileNormalized())) {
                    recipients.add(BroadcastRecipient.builder()
                            .broadcast(broadcast)
                            .customer(c)
                            .recipientPhone(c.getMobileNormalized())
                            .recipientName(c.getName())
                            .status(BroadcastRecipientStatus.PENDING)
                            .build());
                }
            }
        } else if (request.getAudienceType() == BroadcastAudienceType.ALL_LEADS) {
            List<Lead> leads = leadRepo.findByCompanyId(broadcast.getCompany().getId());
            for (Lead l : leads) {
                String phone = l.getPhone() != null ? l.getPhone() : l.getWhatsappNumber();
                if (phone != null && seenPhones.add(phone)) {
                    recipients.add(BroadcastRecipient.builder()
                            .broadcast(broadcast)
                            .lead(l)
                            .recipientPhone(phone)
                            .recipientName(l.getFirstName() + (l.getLastName() != null ? " " + l.getLastName() : ""))
                            .status(BroadcastRecipientStatus.PENDING)
                            .build());
                }
            }
        } else if (request.getAudienceType() == BroadcastAudienceType.CUSTOM_NUMBERS) {
            if (request.getCustomNumbers() != null && !request.getCustomNumbers().isEmpty()) {

                // Normalize all incoming phone numbers first so they match the DB format
                List<String> cleanedPhones = request.getCustomNumbers().stream()
                        .filter(p -> p != null && !p.isBlank())
                        .map(p -> com.leads.util.PhoneNumberUtil.lastSignificantDigits(p.trim()))
                        .filter(p -> !p.isBlank())
                        .distinct()
                        .collect(Collectors.toList());

                // Batch-load all customers for this company whose normalized phone is in
                // our set — one query instead of N individual lookups.
                // Build a phone → Customer map for O(1) lookup per recipient below.
                Map<String, Customer> phoneToCustomer = new HashMap<>();
                if (!cleanedPhones.isEmpty()) {
                    List<Customer> matchedCustomers = customerRepo.findByCompanyId(broadcast.getCompany().getId())
                            .stream()
                            .filter(c -> c.getMobileNormalized() != null
                                    && cleanedPhones.contains(c.getMobileNormalized()))
                            .collect(Collectors.toList());
                    matchedCustomers.forEach(c -> phoneToCustomer.put(c.getMobileNormalized(), c));
                    log.info("[BROADCAST] CUSTOM_NUMBERS: {} phones supplied, {} matched existing customers",
                            cleanedPhones.size(), phoneToCustomer.size());
                }

                for (String cleanPhone : cleanedPhones) {
                    if (seenPhones.add(cleanPhone)) {
                        Customer matchedCustomer = phoneToCustomer.get(cleanPhone);
                        // Use real customer name when available; fall back to the phone
                        // number itself (never the generic "Custom Contact" string, which
                        // would produce a meaningless {{1}} value in template messages).
                        String recipientName = matchedCustomer != null
                                ? matchedCustomer.getName()
                                : cleanPhone;
                        recipients.add(BroadcastRecipient.builder()
                                .broadcast(broadcast)
                                .customer(matchedCustomer)          // null if unmatched — that's fine
                                .recipientPhone(cleanPhone)
                                .recipientName(recipientName)
                                .status(BroadcastRecipientStatus.PENDING)
                                .build());
                    }
                }
            }
        } else {
            // For SPECIFIC_TAGS or SPECIFIC_SEGMENT fallback to active customers
            List<Customer> customers = customerRepo.findByCompanyId(broadcast.getCompany().getId());
            for (Customer c : customers) {
                if (c.getMobileNormalized() != null && seenPhones.add(c.getMobileNormalized())) {
                    recipients.add(BroadcastRecipient.builder()
                            .broadcast(broadcast)
                            .customer(c)
                            .recipientPhone(c.getMobileNormalized())
                            .recipientName(c.getName())
                            .status(BroadcastRecipientStatus.PENDING)
                            .build());
                }
            }
        }
        return recipients;
    }

    // ──────────────────────────────────────────────────────────────
    // Issue #2: Variable Binding Resolution
    // ──────────────────────────────────────────────────────────────

    /**
     * Parses the stored templateComponentsJson into a list of BroadcastComponentDto
     * descriptors containing binding keys (recipientName, recipientPhone, static).
     * Returns null if the JSON is absent or malformed.
     */
    private List<BroadcastComponentDto> parseComponentDtos(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<BroadcastComponentDto> dtos = objectMapper.readValue(json, new TypeReference<>() {});
            return (dtos == null || dtos.isEmpty()) ? null : dtos;
        } catch (Exception ex) {
            log.warn("[BROADCAST] Could not parse component bindings JSON: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Resolves variable bindings from the component descriptors per recipient.
     *
     * Each BroadcastParameterDto carries a "binding" key:
     *   "recipientName"  → recipient.getRecipientName()
     *   "recipientPhone" → recipient.getRecipientPhone()
     *   "static" or null → use the literal text field as-is
     *
     * This converts the binding descriptors into the concrete List<Map> structure
     * expected by WhatsAppGraphApiClient.sendTemplateMessage() / Meta Cloud API.
     */
    private List<Map<String, Object>> resolveComponentsForRecipient(
            List<BroadcastComponentDto> componentDtos, BroadcastRecipient recipient) {
        if (componentDtos == null || componentDtos.isEmpty()) return null;
        List<Map<String, Object>> result = componentDtos.stream().map(dto -> {
            Map<String, Object> map = new HashMap<>();
            map.put("type", dto.getType() != null ? dto.getType().toLowerCase() : "body");
            if (dto.getParameters() != null && !dto.getParameters().isEmpty()) {
                List<Map<String, Object>> params = dto.getParameters().stream().map(p -> {
                    Map<String, Object> pMap = new HashMap<>();
                    pMap.put("type", p.getType() != null ? p.getType() : "text");
                    // Resolve the binding to get the actual text value for this recipient
                    String resolvedText;
                    String binding = p.getBinding();
                    if ("recipientName".equals(binding)) {
                        resolvedText = recipient.getRecipientName() != null ? recipient.getRecipientName() : "";
                    } else if ("recipientPhone".equals(binding)) {
                        resolvedText = recipient.getRecipientPhone() != null ? recipient.getRecipientPhone() : "";
                    } else {
                        // "static" binding or no binding — use the literal text value
                        resolvedText = p.getText() != null ? p.getText() : "";
                    }
                    pMap.put("text", resolvedText);
                    return pMap;
                }).collect(Collectors.toList());
                map.put("parameters", params);
            }
            return map;
        }).collect(Collectors.toList());
        return result.isEmpty() ? null : result;
    }

    // ──────────────────────────────────────────────────────────────
    // Issue #3: Broadcast → WhatsApp Chat Linker
    // ──────────────────────────────────────────────────────────────

    /**
     * After a broadcast template is sent to a recipient, this method ensures
     * the message appears in the WhatsApp inbox so agents can see and reply.
     *
     * Flow:
     *  1. Look up an existing WhatsAppConversation for this phone + number
     *  2. If none exists, create one (same as when a customer first messages in)
     *  3. Create a WhatsAppMessage record (OUTBOUND, TEMPLATE, status=SENT)
     *     with broadcast_id set so the UI can show the "Broadcast" badge
     *  4. Update conversation.lastMessageAt and lastMessageText
     *
     * Errors here must NOT abort the broadcast — caller wraps in try-catch.
     */
    private void linkBroadcastMessageToChat(
            WhatsAppConfiguration config, String cleanPhone,
            Broadcast broadcast, BroadcastRecipient recipient,
            String wamid, String templateLanguage) {

        Long companyId = broadcast.getCompany().getId();
        String phoneNumberId = config.getPhoneNumberId();

        // Find or create the conversation for this recipient phone
        WhatsAppConversation conversation = conversationRepo
                .findByCompanyIdAndCustomerWaIdAndReceivingPhoneNumberId(companyId, cleanPhone, phoneNumberId)
                .orElseGet(() -> {
                    // Create a new conversation — the broadcast is the first outbound contact
                    String recipientName = recipient.getRecipientName() != null
                            ? recipient.getRecipientName() : cleanPhone;
                    WhatsAppConversation newConv = WhatsAppConversation.builder()
                            .company(broadcast.getCompany())
                            .branch(broadcast.getBranch())
                            .receivingPhoneNumberId(phoneNumberId)
                            .customerWaId(cleanPhone)
                            .customerName(recipientName)
                            .lead(recipient.getLead())
                            .chatStatus("OPEN")
                            .lastMessageAt(LocalDateTime.now())
                            .lastMessageText("[Broadcast: " + broadcast.getTemplateName() + "]")
                            .build();
                    log.info("[BROADCAST CHAT] Creating new conversation for phone={} company={}",
                            cleanPhone, companyId);
                    return conversationRepo.save(newConv);
                });

        // Create the WhatsAppMessage row for this broadcast template send
        WhatsAppMessage msg = WhatsAppMessage.builder()
                .conversation(conversation)
                .wamid(wamid)
                .direction(MessageDirection.OUTBOUND)
                .messageType("TEMPLATE")
                .templateName(broadcast.getTemplateName())
                .templateLanguage(templateLanguage)
                .bodyText("[Broadcast template: " + broadcast.getTemplateName() + "]")
                .status(MessageStatus.SENT)
                .broadcast(broadcast)
                .build();
        messageRepo.save(msg);

        // Update conversation's last message metadata so it floats to the top of the inbox
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setLastMessageText("[Broadcast: " + broadcast.getTemplateName() + "]");
        conversationRepo.save(conversation);

        log.info("[BROADCAST CHAT] ✓ Linked wamid={} to conversation ID={} for phone={}",
                wamid, conversation.getId(), cleanPhone);
    }

    private List<Map<String, Object>> parseComponents(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<BroadcastComponentDto> dtos = objectMapper.readValue(json, new TypeReference<>() {});
            if (dtos == null || dtos.isEmpty()) return null;
            List<Map<String, Object>> result = dtos.stream().map(dto -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", dto.getType() != null ? dto.getType().toLowerCase() : "body");
                if (dto.getParameters() != null && !dto.getParameters().isEmpty()) {
                    List<Map<String, Object>> params = dto.getParameters().stream().map(p -> {
                        Map<String, Object> pMap = new HashMap<>();
                        pMap.put("type", p.getType() != null ? p.getType() : "text");
                        pMap.put("text", p.getText());
                        return pMap;
                    }).collect(Collectors.toList());
                    map.put("parameters", params);
                }
                return map;
            }).collect(Collectors.toList());
            return result.isEmpty() ? null : result;
        } catch (Exception ex) {
            log.warn("Could not parse broadcast component JSON", ex);
            return null;
        }
    }

    private BroadcastDto mapToDto(Broadcast broadcast) {
        return BroadcastDto.builder()
                .id(broadcast.getId())
                .companyId(broadcast.getCompany().getId())
                .branchId(broadcast.getBranch() != null ? broadcast.getBranch().getId() : null)
                .branchName(broadcast.getBranch() != null ? broadcast.getBranch().getName() : null)
                .whatsappConfigurationId(broadcast.getWhatsappConfiguration() != null ? broadcast.getWhatsappConfiguration().getId() : null)
                .whatsappPhoneNumber(broadcast.getWhatsappConfiguration() != null ? broadcast.getWhatsappConfiguration().getDisplayPhoneNumber() : null)
                .name(broadcast.getName())
                .templateName(broadcast.getTemplateName())
                .templateLanguage(broadcast.getTemplateLanguage())
                .templateComponentsJson(broadcast.getTemplateComponentsJson())
                .audienceType(broadcast.getAudienceType())
                .audienceFilterJson(broadcast.getAudienceFilterJson())
                .status(broadcast.getStatus())
                .scheduledAt(broadcast.getScheduledAt())
                .sentAt(broadcast.getSentAt())
                .completedAt(broadcast.getCompletedAt())
                .totalRecipients(broadcast.getTotalRecipients())
                .sentCount(broadcast.getSentCount())
                .deliveredCount(broadcast.getDeliveredCount())
                .readCount(broadcast.getReadCount())
                .failedCount(broadcast.getFailedCount())
                .createdByUserName(broadcast.getCreatedBy() != null ? broadcast.getCreatedBy().getFirstName() + " " + broadcast.getCreatedBy().getLastName() : null)
                .createdAt(broadcast.getCreatedAt())
                .updatedAt(broadcast.getUpdatedAt())
                .build();
    }

    private BroadcastRecipientDto mapRecipientToDto(BroadcastRecipient recipient) {
        return BroadcastRecipientDto.builder()
                .id(recipient.getId())
                .broadcastId(recipient.getBroadcast().getId())
                .customerId(recipient.getCustomer() != null ? recipient.getCustomer().getId() : null)
                .leadId(recipient.getLead() != null ? recipient.getLead().getId() : null)
                .recipientPhone(recipient.getRecipientPhone())
                .recipientName(recipient.getRecipientName())
                .wamid(recipient.getWamid())
                .status(recipient.getStatus())
                .errorMessage(recipient.getErrorMessage())
                .sentAt(recipient.getSentAt())
                .deliveredAt(recipient.getDeliveredAt())
                .readAt(recipient.getReadAt())
                .failedAt(recipient.getFailedAt())
                .createdAt(recipient.getCreatedAt())
                .build();
    }
}
