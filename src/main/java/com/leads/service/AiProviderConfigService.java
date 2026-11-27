package com.leads.service;

import com.leads.dto.ai.AiChatMessage;
import com.leads.dto.ai.AiChatRequest;
import com.leads.dto.ai.AiChatResponse;
import com.leads.dto.ai.AiProviderConfigDto;
import com.leads.dto.ai.AiProviderConfigUpsertRequest;
import com.leads.dto.ai.AiTestConnectionResult;
import com.leads.exception.AiProviderApiException;
import com.leads.model.entity.AiProviderConfig;
import com.leads.model.entity.Branch;
import com.leads.model.entity.Company;
import com.leads.model.enums.AiChatRole;
import com.leads.model.enums.AiProviderType;
import com.leads.repository.AiProviderConfigRepository;
import com.leads.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Branch-wise AI provider credential CRUD — structurally mirrors {@link WhatsAppConfigService}. */
@Service
@RequiredArgsConstructor
public class AiProviderConfigService {
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final AiMeteredCalls meteredCalls;

    private final TenantAccess access;
    private final AiProviderConfigRepository configRepo;
    private final BranchRepository branchRepo;
    private final List<AiChatProvider> providers;
    private Map<AiProviderType, AiChatProvider> providersByType;

    @Transactional(readOnly = true)
    public List<AiProviderConfigDto> getConfigsByCompany(Long companyId) {
        access.company(companyId);
        return configRepo.findByCompanyIdOrderByIdAsc(companyId).stream()
                .filter(c -> access.canBranch(c.getBranch()))
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AiProviderConfigDto> getConfigsByBranch(Long branchId) {
        access.branch(branchId);
        return configRepo.findByBranchIdOrderByIdAsc(branchId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public AiProviderConfigDto saveConfig(AiProviderConfigUpsertRequest req) {
        access.branch(req.getBranchId());
        if (req.getId() != null) access.record(AiProviderConfig.class, req.getId());
        Branch branch = branchRepo.findById(req.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + req.getBranchId()));
        Company company = branch.getCompany();

        boolean isNew = req.getId() == null;
        AiProviderConfig config;
        if (isNew) {
            configRepo.findByBranchIdAndProvider(branch.getId(), req.getProvider()).ifPresent(existing -> {
                throw new IllegalArgumentException("This branch already has a " + req.getProvider() + " connection — edit it instead of creating another.");
            });
            config = AiProviderConfig.builder().branch(branch).company(company).provider(req.getProvider()).build();
        } else {
            config = configRepo.findById(req.getId())
                    .orElseThrow(() -> new IllegalArgumentException("AI provider configuration not found: " + req.getId()));
            if (!config.getBranch().getId().equals(branch.getId()) || config.getProvider() != req.getProvider()) {
                throw new IllegalArgumentException("An existing connection's branch and provider cannot change. Create a separate connection instead.");
            }
        }

        config.setModelName(req.getModelName());
        config.setApiBaseUrlOverride(req.getApiBaseUrlOverride());
        config.setProviderAccountRef(req.getProviderAccountRef());
        config.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);

        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            config.setApiKey(req.getApiKey());
        } else if (isNew) {
            throw new IllegalArgumentException("An API key is required for a new AI provider connection.");
        }

        AiProviderConfig saved = configRepo.save(config);

        // A branch's very first-ever connection is auto-selected so a
        // freshly-configured branch doesn't sit with AI features silently
        // disabled until an admin makes a separate "select" click.
        if (isNew && configRepo.findByBranchIdOrderByIdAsc(branch.getId()).size() == 1) {
            saved.setIsSelected(true);
            saved = configRepo.save(saved);
        }

        return mapToDto(saved);
    }

    @Transactional
    public AiProviderConfigDto selectProvider(Long id) {
        AiProviderConfig target = access.record(AiProviderConfig.class, id);
        if (!Boolean.TRUE.equals(target.getIsActive())) {
            throw new IllegalArgumentException("Activate this connection before selecting it.");
        }
        for (AiProviderConfig other : configRepo.findByBranchIdOrderByIdAsc(target.getBranch().getId())) {
            boolean shouldBeSelected = other.getId().equals(target.getId());
            if (other.getIsSelected() == null || other.getIsSelected() != shouldBeSelected) {
                other.setIsSelected(shouldBeSelected);
                configRepo.save(other);
            }
        }
        return mapToDto(configRepo.findById(id).orElseThrow());
    }

    @Transactional
    public void deleteConfig(Long id) {
        access.record(AiProviderConfig.class, id);
        if (!configRepo.existsById(id)) {
            throw new IllegalArgumentException("AI provider configuration not found: " + id);
        }
        configRepo.deleteById(id);
    }

    /** Module 18 P1 part 2 (CLAUDE.md hard rule 2): the config is read in a short read; the provider call runs with none open. */
    public AiTestConnectionResult testConnection(Long id) {
        var read = new org.springframework.transaction.support.TransactionTemplate(transactions);
        read.setReadOnly(true);
        AiProviderConfig config = read.execute(st -> {
            AiProviderConfig c = access.record(AiProviderConfig.class, id);
            c.getApiKey(); // loaded now: used detached for the call
            return c;
        });
        try {
            AiChatResponse response = meteredCalls.chat("CONNECTION_TEST", clientFor(config.getProvider()), config, AiChatRequest.builder()
                    .systemPrompt("Reply with exactly one word: OK")
                    .messages(List.of(AiChatMessage.builder().role(AiChatRole.USER).content("Are you working?").build()))
                    .maxOutputTokens(5)
                    .build());
            return AiTestConnectionResult.builder()
                    .success(true)
                    .message("Connected successfully to " + config.getProvider() + " (" + config.getModelName() + "). Response: "
                            + (response.getContent() != null ? response.getContent().trim() : ""))
                    .build();
        } catch (AiProviderApiException ex) {
            return AiTestConnectionResult.builder().success(false).message(ex.getMessage()).build();
        }
    }

    // Test Connection must work even for a not-yet-selected config, unlike
    // AiProviderResolver.resolveForBranch which only resolves the branch's
    // currently-selected+active one — so this looks the client up by type
    // directly, same EnumMap-of-injected-beans pattern as AiProviderResolver.
    private AiChatProvider clientFor(AiProviderType type) {
        if (providersByType == null) {
            Map<AiProviderType, AiChatProvider> map = new EnumMap<>(AiProviderType.class);
            for (AiChatProvider p : providers) map.put(p.type(), p);
            providersByType = map;
        }
        AiChatProvider client = providersByType.get(type);
        if (client == null) throw new IllegalStateException("No AiChatProvider registered for provider " + type);
        return client;
    }

    private AiProviderConfigDto mapToDto(AiProviderConfig c) {
        return AiProviderConfigDto.builder()
                .id(c.getId())
                .companyId(c.getCompany().getId())
                .branchId(c.getBranch().getId())
                .branchName(c.getBranch().getName())
                .provider(c.getProvider())
                .modelName(c.getModelName())
                .apiKeyMasked(maskKey(c.getApiKey()))
                .apiBaseUrlOverride(c.getApiBaseUrlOverride())
                .providerAccountRef(c.getProviderAccountRef())
                .isActive(c.getIsActive())
                .isSelected(c.getIsSelected())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return "••••••••";
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }
}
