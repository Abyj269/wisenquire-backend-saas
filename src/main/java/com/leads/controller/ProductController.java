package com.leads.controller;

import com.leads.dto.settings.ProductMasterDto;
import com.leads.service.ProductMasterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
@Tag(name = "Product Master Catalog", description = "Endpoints for managing products to link with leads")
public class ProductController {

    private final ProductMasterService productService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('PRODUCT_CATALOG:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Products by Company", description = "Retrieves active products in the company catalog.")
    public ResponseEntity<List<ProductMasterDto>> getProducts(@RequestParam Long companyId) {
        return ResponseEntity.ok(productService.getProductsByCompany(companyId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('PRODUCT_CATALOG:ADD')")
    @Operation(summary = "Create Product", description = "Creates a product with SKU, category, and unit price.")
    public ResponseEntity<ProductMasterDto> createProduct(@RequestParam Long companyId, @Valid @RequestBody ProductMasterDto dto) {
        return ResponseEntity.ok(productService.createProduct(companyId, dto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('PRODUCT_CATALOG:EDIT')")
    @Operation(summary = "Update Product")
    public ResponseEntity<ProductMasterDto> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductMasterDto dto) {
        return ResponseEntity.ok(productService.updateProduct(id, dto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('PRODUCT_CATALOG:DELETE')")
    @Operation(summary = "Delete Product")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok().build();
    }
}
