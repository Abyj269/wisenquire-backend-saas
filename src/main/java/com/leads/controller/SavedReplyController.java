package com.leads.controller;
import com.leads.model.entity.SavedReply;
import com.leads.service.SavedReplyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/settings/saved-replies") @RequiredArgsConstructor
public class SavedReplyController {
    private final SavedReplyService service;
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:VIEW') or hasAuthority('WHATSAPP_LIVE_CHAT:VIEW')")
    public List<SavedReply> list(@RequestParam Long companyId, @RequestParam(defaultValue="true") boolean activeOnly) {
        return service.list(companyId, activeOnly);
    }
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:ADD')")
    public SavedReply create(@RequestParam Long companyId, @Valid @RequestBody SavedReplyService.ReplyInput input) {
        return service.save(companyId, null, input);
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:EDIT')")
    public SavedReply update(@RequestParam Long companyId, @PathVariable Long id, @Valid @RequestBody SavedReplyService.ReplyInput input) {
        return service.save(companyId, id, input);
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('LEAD_SETTINGS:DELETE')")
    public void delete(@RequestParam Long companyId, @PathVariable Long id) { service.delete(companyId, id); }
}
