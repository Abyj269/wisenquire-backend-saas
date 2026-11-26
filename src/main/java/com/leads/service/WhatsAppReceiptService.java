package com.leads.service;

import com.leads.dto.whatsapp.WhatsAppWebhookPayload;
import com.leads.model.enums.MessageStatus;
import com.leads.repository.WhatsAppMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Set;

/** Durable receipt inbox: receipts can arrive before the outbound transaction commits. */
@Service
@RequiredArgsConstructor
public class WhatsAppReceiptService {
    private final JdbcTemplate jdbc;
    private final WhatsAppMessageRepository messages;
    private final ObjectProvider<WhatsAppCloudService> cloud;
    private final ObjectProvider<BroadcastService> broadcastService;
    private final SimpMessagingTemplate messaging;

    @Transactional
    public void record(String phoneNumberId, WhatsAppWebhookPayload.Status receipt) {
        if (receipt.getId() == null || receipt.getId().length() > 255 || receipt.getStatus() == null) return;
        String status = receipt.getStatus().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("SENT", "DELIVERED", "READ", "FAILED").contains(status)) return;
        long timestamp;
        try { timestamp = Long.parseLong(receipt.getTimestamp()); } catch (RuntimeException e) { return; }
        if (timestamp <= 0) return;
        Integer code = null;
        String detail = null;
        if (receipt.getErrors() != null && !receipt.getErrors().isEmpty()) {
            var error = receipt.getErrors().get(0);
            code = error.getCode();
            detail = error.getErrorData() != null ? error.getErrorData().getDetails() : error.getMessage();
            if (detail == null) detail = error.getTitle();
        }
        if (Integer.valueOf(131047).equals(code)) detail = "Reply window expired. Send an approved template and wait for the customer to reply.";
        if ("FAILED".equals(status) && detail == null) detail = "WhatsApp rejected this message. Check the business number configuration and recipient eligibility.";
        if (detail != null) detail = (code != null ? "Meta " + code + ": " : "") + detail;
        jdbc.update("INSERT INTO whatsapp_status_receipts(phone_number_id,wamid,status,occurred_at,error_code,error_detail) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                phoneNumberId, receipt.getId(), status, timestamp, code, detail == null ? null : detail.substring(0, Math.min(255, detail.length())));
    }

    @Scheduled(fixedDelayString = "${whatsapp.receipts.reconcile-ms:2000}", initialDelayString = "${whatsapp.receipts.initial-delay-ms:0}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = com.leads.jobs.JobLocks.WHATSAPP_RECEIPT_RECONCILE, lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    @Transactional
    public void reconcile() {
        com.leads.jobs.JobRuns.record(com.leads.jobs.ScheduledJob.WHATSAPP_RECEIPT_RECONCILE);
        // Exact receiving-number join also prevents a receipt from affecting another tenant's chat.
        var pending = jdbc.queryForList("SELECT r.id,r.wamid,r.status,r.error_detail FROM whatsapp_status_receipts r JOIN whatsapp_messages m ON m.wamid=r.wamid JOIN whatsapp_conversations c ON c.id=m.conversation_id AND c.receiving_phone_number_id=r.phone_number_id WHERE r.applied=false AND m.direction='OUTBOUND' ORDER BY r.occurred_at,r.id LIMIT 100 FOR UPDATE");
        for (var row : pending) {
            var message = messages.findByWamid((String) row.get("wamid")).orElseThrow();
            MessageStatus next = MessageStatus.valueOf((String) row.get("status"));
            if (shouldApply(message.getStatus(), next)) {
                message.setStatus(next);
                message.setErrorMessage(next == MessageStatus.FAILED ? (String) row.get("error_detail") : null);
                messages.save(message);
                var dto = cloud.getObject().mapMessageToDto(message);
                var users = WhatsAppCloudService.pushRecipients(message.getConversation()); // module 12 P1: OWN users' queues
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        messaging.convertAndSend("/topic/whatsapp/conversation/" + dto.getConversationId(), dto);
                        users.forEach(u -> messaging.convertAndSendToUser(u, WhatsAppCloudService.USER_QUEUE, dto));
                    }
                });
            }
            jdbc.update("UPDATE whatsapp_status_receipts SET applied=true WHERE id=?", row.get("id"));
        }

        // Reconcile receipts for Broadcast Recipients
        var broadcastPending = jdbc.queryForList("SELECT r.id, r.wamid, r.status, r.error_detail FROM whatsapp_status_receipts r JOIN broadcast_recipients br ON br.wamid=r.wamid WHERE r.applied=false ORDER BY r.occurred_at, r.id LIMIT 100 FOR UPDATE");
        for (var row : broadcastPending) {
            String wamid = (String) row.get("wamid");
            String status = (String) row.get("status");
            String errorDetail = (String) row.get("error_detail");
            if (broadcastService.getIfAvailable() != null) {
                broadcastService.getIfAvailable().updateRecipientStatusFromWebhook(wamid, status, errorDetail);
            }
            jdbc.update("UPDATE whatsapp_status_receipts SET applied=true WHERE id=?", row.get("id"));
        }
    }

    public static boolean shouldApply(MessageStatus current, MessageStatus next) {
        if (current == next || current == MessageStatus.READ) return false;
        if (next == MessageStatus.READ) return true;
        if (current == MessageStatus.DELIVERED) return false;
        if (next == MessageStatus.DELIVERED) return true;
        if (current == MessageStatus.FAILED) return false;
        return next == MessageStatus.SENT || next == MessageStatus.FAILED;
    }

    @Scheduled(fixedDelayString = "${whatsapp.receipts.cleanup-ms:3600000}", initialDelayString = "${whatsapp.receipts.initial-delay-ms:0}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = com.leads.jobs.JobLocks.WHATSAPP_RECEIPT_CLEANUP, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        com.leads.jobs.JobRuns.record(com.leads.jobs.ScheduledJob.WHATSAPP_RECEIPT_CLEANUP);
        jdbc.update("DELETE FROM whatsapp_status_receipts WHERE created_at < CURRENT_TIMESTAMP - INTERVAL 30 DAY LIMIT 1000");
    }
}
