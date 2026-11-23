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

    private final ProductMasterRepository productRepo;
    private final CompanyRepository companyRepo;

    @Transactional(readOnly = true)
    public List<ProductMasterDto> getProductsByCompany(Long companyId) {
        access.company(companyId);
        return productRepo.findByCompanyIdAndIsActiveTrueOrderByProductNameAsc(companyId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductMasterDto createProduct(Long companyId, ProductMasterDto dto) {
        access.company(companyId);
        Company company = companyRepo.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));

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
        access.record(com.leads.model.entity.ProductMaster.class, id);
        ProductMaster product = productRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));

        product.setProductCode(dto.getProductCode().toUpperCase());
        product.setProductName(dto.getProductName());
        product.setSku(dto.getSku());
        product.setCategory(dto.getCategory());
        product.setUnitPrice(dto.getUnitPrice());
        product.setCurrency(dto.getCurrency());
        product.setTaxPercentage(dto.getTaxPercentage());
        product.setDescription(dto.getDescription());
        product.setIsActive(dto.getIsActive());

        return mapToDto(productRepo.save(product));
    }

    @Transactional
    public void deleteProduct(Long id) {
        access.record(com.leads.model.entity.ProductMaster.class, id);
        productRepo.deleteById(id);
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
