package com.leads.service;

import com.leads.model.entity.SavedReply;
import com.leads.repository.SavedReplyRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor
public class SavedReplyService {
    private final TenantAccess access;
    private final SavedReplyRepository repository;
    public record ReplyInput(@NotBlank @Size(max=100) String title,
                             @NotBlank @Size(max=4096) String body, boolean active) {}

    @Transactional(readOnly=true)
    public List<SavedReply> list(Long companyId, boolean activeOnly) {
        access.company(companyId);
        return repository.findByCompanyIdOrderByTitleAscIdAsc(companyId).stream()
                .filter(reply -> !activeOnly || reply.isActive()).toList();
    }
    @Transactional
    public SavedReply save(Long companyId, Long id, ReplyInput input) {
        access.company(companyId);
        SavedReply reply = id == null ? new SavedReply() : owned(companyId, id);
        reply.setCompanyId(companyId);
        reply.setTitle(input.title().trim());
        reply.setBody(input.body().trim());
        reply.setActive(input.active());
        return repository.save(reply);
    }
    @Transactional
    public void delete(Long companyId, Long id) {
        access.company(companyId);
        repository.delete(owned(companyId, id));
    }
    private SavedReply owned(Long companyId, Long id) {
        SavedReply reply = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Saved reply not found."));
        if (!companyId.equals(reply.getCompanyId())) throw new org.springframework.security.access.AccessDeniedException("Access denied.");
        return reply;
    }
}
