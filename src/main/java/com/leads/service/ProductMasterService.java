package com.leads.service;

import com.leads.dto.settings.ProductMasterDto;
import com.leads.model.entity.Company;
import com.leads.model.entity.ProductMaster;
import com.leads.repository.CompanyRepository;
import com.leads.repository.ProductMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductMasterService {
    private final TenantAccess access;
    private final ProductWriteScope writeScope;

    private final ProductMasterRepository productRepo;
    private final CompanyRepository companyRepo;

    @Transactional(readOnly = true)
    public List<ProductMasterDto> getProductsByCompany(Long companyId) {
        return getProductsByCompany(companyId, false);
    }

    /**
     * Module 05 P1: {@code includeInactive} lists deactivated products too, so the catalogue screen can
     * show and reactivate them. It needs PRODUCT_CATALOG:VIEW (LEADS:VIEW alone is enough for the pickers' active list).
     */
    @Transactional(readOnly = true)
    public List<ProductMasterDto> getProductsByCompany(Long companyId, boolean includeInactive) {
        access.company(companyId);
        if (includeInactive) access.permission("PRODUCT_CATALOG:VIEW");
        return (includeInactive ? productRepo.findByCompanyIdOrderByProductNameAsc(companyId)
                        : productRepo.findByCompanyIdAndIsActiveTrueOrderByProductNameAsc(companyId))
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductMasterDto createProduct(Long companyId, ProductMasterDto dto) {
        Company company = writeScope.check(companyId);

        ProductMaster product = ProductMaster.builder()
                .company(company)
                .productCode(dto.getProductCode().toUpperCase())
                .productName(dto.getProductName())
                .sku(dto.getSku())
                .category(dto.getCategory())
                .unitPrice(dto.getUnitPrice())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : company.getCurrency())
                .taxPercentage(dto.getTaxPercentage())
                .description(dto.getDescription())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .build();

        return mapToDto(productRepo.save(product));
    }

    @Transactional
    public ProductMasterDto updateProduct(Long id, ProductMasterDto dto) {
        ProductMaster product = access.record(com.leads.model.entity.ProductMaster.class, id);
        writeScope.check(product.getCompany().getId());

        product.setProductCode(dto.getProductCode().toUpperCase());
        product.setProductName(dto.getProductName());
        product.setSku(dto.getSku());
        product.setCategory(dto.getCategory());
        product.setUnitPrice(dto.getUnitPrice()); // @NotNull on the DTO
        // A missing currency keeps the product's currency: null would break the lead/quotation currency match.
        if (dto.getCurrency() != null) product.setCurrency(dto.getCurrency());
        product.setTaxPercentage(dto.getTaxPercentage());
        product.setDescription(dto.getDescription());
        // NOT NULL column: a missing isActive keeps its value (was a 500 on the constraint).
        if (dto.getIsActive() != null) product.setIsActive(dto.getIsActive());

        return mapToDto(productRepo.save(product));
    }

    @Transactional
    public com.leads.dto.settings.ProductDeleteResult deleteProduct(Long id) {
        ProductMaster product = access.record(com.leads.model.entity.ProductMaster.class, id);
        writeScope.check(product.getCompany().getId());
        // Module 05 P1: lead_products and quotation_items reference products with plain FKs, so a hard
        // delete of a used product failed (500). Keep it for their history and hide it instead.
        if (productRepo.usedByLeads(id) || productRepo.usedByQuotations(id)) {
            product.setIsActive(false);
            productRepo.save(product);
            return new com.leads.dto.settings.ProductDeleteResult(com.leads.dto.settings.ProductDeleteResult.Outcome.DEACTIVATED);
        }
        productRepo.delete(product);
        return new com.leads.dto.settings.ProductDeleteResult(com.leads.dto.settings.ProductDeleteResult.Outcome.DELETED);
    }

    private ProductMasterDto mapToDto(ProductMaster p) {
        return ProductMasterDto.builder()
                .id(p.getId())
                .companyId(p.getCompany().getId())
                .productCode(p.getProductCode())
                .productName(p.getProductName())
                .sku(p.getSku())
                .category(p.getCategory())
                .unitPrice(p.getUnitPrice())
                .currency(p.getCurrency())
                .taxPercentage(p.getTaxPercentage())
                .description(p.getDescription())
                .isActive(p.getIsActive())
                .build();
    }
}
