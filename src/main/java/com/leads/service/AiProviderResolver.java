package com.leads.service;

import com.leads.model.entity.AiProviderConfig;
import com.leads.model.enums.AiProviderType;
import com.leads.repository.AiProviderConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the branch's currently-selected {@link AiProviderConfig} plus its
 * matching {@link AiChatProvider} client — Spring autowires every
 * implementation, indexed once by {@link AiChatProvider#type()}, same pattern
 * as {@link AdsProviderClientFactory}.
 */
@Service
@RequiredArgsConstructor
public class AiProviderResolver {

    private final List<AiChatProvider> providers;
    private final AiProviderConfigRepository configRepo;
    private final TenantAccess access;
    private Map<AiProviderType, AiChatProvider> byType;

    @Value
    public static class Resolved {
        AiChatProvider client;
        AiProviderConfig config;
    }

    public Resolved resolveForBranch(Long branchId) {
        access.branch(branchId);
        AiProviderConfig config = configRepo.findByBranchIdAndIsSelectedTrueAndIsActiveTrue(branchId)
                .orElseThrow(() -> new IllegalStateException(
                        "No AI provider is configured for this branch. Ask an admin to set one up in AI Settings."));
        return new Resolved(clientFor(config.getProvider()), config);
    }

    private AiChatProvider clientFor(AiProviderType type) {
        if (byType == null) {
            Map<AiProviderType, AiChatProvider> map = new EnumMap<>(AiProviderType.class);
            for (AiChatProvider p : providers) map.put(p.type(), p);
            byType = map;
        }
        AiChatProvider client = byType.get(type);
        if (client == null) throw new IllegalStateException("No AiChatProvider registered for provider " + type);
        return client;
    }
}
