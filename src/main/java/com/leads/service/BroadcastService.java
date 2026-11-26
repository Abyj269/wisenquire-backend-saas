package com.leads.service;

import com.leads.dto.broadcast.BroadcastCreateRequest;
import com.leads.dto.broadcast.BroadcastDto;
import com.leads.dto.broadcast.BroadcastRecipientDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BroadcastService {
    BroadcastDto createBroadcast(BroadcastCreateRequest request);
    BroadcastDto getBroadcastById(Long id);
    Page<BroadcastDto> getBroadcasts(Long companyId, Long branchId, Pageable pageable);
    BroadcastDto sendBroadcast(Long broadcastId);
    Page<BroadcastRecipientDto> getBroadcastLogs(Long broadcastId, Pageable pageable);
    void deleteBroadcast(Long broadcastId);
    void updateRecipientStatusFromWebhook(String wamid, String status, String errorDetail);

    /** Module 16 P1: resumes a stalled chunked broadcast; false when it is not stalled, not chunked, or another node took it. */
    boolean resumeStalled(Long broadcastId);
}
