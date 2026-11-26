package com.leads.service;

import com.leads.model.entity.SavedReply;
import com.leads.repository.SavedReplyRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * Saved replies are company-wide content. Reads: company scope, so every agent (OWN included) can use
 * them. Writes (module 15 P1, F-12): {@link LeadSettingsWriteScope}, so with flag
 * {@code lead_settings.company_wide_writes} ON a branch-level user is refused; OFF: company scope, as before.
 */
@Service @RequiredArgsConstructor
public class SavedReplyService {
    private final TenantAccess access;
    private final LeadSettingsWriteScope writeScope;
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
        writeScope.check(companyId);
        SavedReply reply = id == null ? new SavedReply() : owned(companyId, id);
        reply.setCompanyId(companyId);
        reply.setTitle(input.title().trim());
        reply.setBody(input.body().trim());
        reply.setActive(input.active());
        return repository.save(reply);
    }
    @Transactional
    public void delete(Long companyId, Long id) {
        writeScope.check(companyId);
        repository.delete(owned(companyId, id));
    }
    private SavedReply owned(Long companyId, Long id) {
        SavedReply reply = access.record(SavedReply.class, id); // module 15: the reply's own company is in scope
        // and it is the company the request names (a reply never moves between companies)
        if (!companyId.equals(reply.getCompanyId())) throw new org.springframework.security.access.AccessDeniedException("Access denied.");
        return reply;
    }
}
