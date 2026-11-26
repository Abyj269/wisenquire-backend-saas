package com.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leads.dto.whatsapp.WhatsAppConfigDto;
import com.leads.dto.whatsapp.WhatsAppConfigUpsertRequest;
import com.leads.dto.whatsapp.WhatsAppTestConnectionResult;
import com.leads.exception.WhatsAppApiException;
import com.leads.model.entity.Branch;
import com.leads.model.entity.Company;
import com.leads.model.entity.WhatsAppConfiguration;
import com.leads.repository.BranchRepository;
import com.leads.repository.WhatsAppConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WhatsAppConfigService {
    private final TenantAccess access;

    private final WhatsAppConfigurationRepository configRepo;
    private final BranchRepository branchRepo;
    private final WhatsAppGraphApiClient graphApiClient;
    private final org.springframework.transaction.PlatformTransactionManager txManager;

    @Transactional(readOnly = true)
    public List<WhatsAppConfigDto> getConfigsByCompany(Long companyId) {
        access.company(companyId);
        return configRepo.findByCompanyIdOrderByIdAsc(companyId)
                .stream()
                .filter(c -> access.canBranch(c.getBranch()))
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WhatsAppConfigDto> getConfigsByBranch(Long branchId) {
        access.branch(branchId);
        return configRepo.findByBranchIdOrderByIdAsc(branchId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public WhatsAppConfigDto saveConfig(WhatsAppConfigUpsertRequest req) {
        access.branch(req.getBranchId());
        if (req.getId() != null) access.record(WhatsAppConfiguration.class, req.getId());
        Branch branch = branchRepo.findById(req.getBranchId())
                .orElseThrow(() -> new IllegalArgumentException("Branch not found: " + req.getBranchId()));
        Company company = branch.getCompany();

        boolean isNew = req.getId() == null;
        WhatsAppConfiguration config;
        if (isNew) {
            config = WhatsAppConfiguration.builder().branch(branch).company(company).build();
        } else {
            config = configRepo.findById(req.getId())
                    .orElseThrow(() -> new IllegalArgumentException("WhatsApp configuration not found: " + req.getId()));
            if (!config.getBranch().getId().equals(branch.getId()) || !config.getPhoneNumberId().equals(req.getPhoneNumberId())) {
                throw new IllegalArgumentException("An existing connection's branch and receiving number cannot change because conversations retain that routing. Create a separate connection.");
            }
            config.setBranch(branch);
        }

        config.setPhoneNumberId(req.getPhoneNumberId());
        config.setWabaId(req.getWabaId());
        config.setAppId(req.getAppId());
        config.setVerifyToken(req.getVerifyToken());
        if (req.getAppSecret() != null && !req.getAppSecret().isBlank()) config.setAppSecret(req.getAppSecret());
        if (config.getAppSecret() == null || config.getAppSecret().isBlank()) throw new IllegalArgumentException("App secret is required to authenticate incoming webhooks.");
        config.setDisplayPhoneNumber(req.getDisplayPhoneNumber());
        config.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);

        if (req.getAccessToken() != null && !req.getAccessToken().isBlank()) {
            config.setAccessToken(req.getAccessToken());
        } else if (isNew) {
            throw new IllegalArgumentException("Access Token is required for a new WhatsApp connection.");
        }

        return mapToDto(configRepo.save(config));
    }

    @Transactional
    public void deleteConfig(Long id) {
        access.record(com.leads.model.entity.WhatsAppConfiguration.class, id);
        if (!configRepo.existsById(id)) {
            throw new IllegalArgumentException("WhatsApp configuration not found: " + id);
        }
        configRepo.deleteById(id);
    }

    /**
     * Module 11 P1 (CLAUDE.md hard rule 2): the scope check and the load run in a short read-only
     * transaction that is CLOSED before the Meta call; the call uses the loaded values only
     * (phone number id, token, app id). Was @Transactional(readOnly) around the HTTP call.
     */
    public WhatsAppTestConnectionResult testConnection(Long id) {
        var readOnly = new org.springframework.transaction.support.TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
        WhatsAppConfiguration config = readOnly.execute(st -> {
            access.record(com.leads.model.entity.WhatsAppConfiguration.class, id);
            return configRepo.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("WhatsApp configuration not found: " + id));
        });
        try {
            JsonNode info = graphApiClient.getPhoneNumberInfo(config);
            return WhatsAppTestConnectionResult.builder()
                    .success(true)
                    .message("Connected successfully to Meta WhatsApp Cloud API.")
                    .verifiedName(textOrNull(info, "verified_name"))
                    .displayPhoneNumber(textOrNull(info, "display_phone_number"))
                    .qualityRating(textOrNull(info, "quality_rating"))
                    .build();
        } catch (WhatsAppApiException ex) {
            return WhatsAppTestConnectionResult.builder()
                    .success(false)
                    .message(ex.getMessage())
                    .build();
        }
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null) return null; // an empty answer from Meta (or a stub) is not a server error
        JsonNode f = node.get(field);
        return f != null ? f.asText() : null;
    }

    private WhatsAppConfigDto mapToDto(WhatsAppConfiguration c) {
        return WhatsAppConfigDto.builder()
                .id(c.getId())
                .companyId(c.getCompany().getId())
                .branchId(c.getBranch().getId())
                .branchName(c.getBranch().getName())
                .branchCode(c.getBranch().getCode())
                .phoneNumberId(c.getPhoneNumberId())
                .wabaId(c.getWabaId())
                .appId(c.getAppId())
                .accessTokenMasked(maskToken(c.getAccessToken()))
                .verifyToken(c.getVerifyToken())
                .hasAppSecret(c.getAppSecret() != null && !c.getAppSecret().isBlank())
                .displayPhoneNumber(c.getDisplayPhoneNumber())
                .isActive(c.getIsActive())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) return "••••••••";
        return token.substring(0, 4) + "••••••••" + token.substring(token.length() - 4);
    }
}
