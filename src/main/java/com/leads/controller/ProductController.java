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
@com.leads.audit.Audited(entity = "products") // 01 Part B item 9: writes are audited
@RequestMapping("/products")
@RequiredArgsConstructor
@Tag(name = "Product Master Catalog", description = "Endpoints for managing products to link with leads")
public class ProductController {

    private final ProductMasterService productService;

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('PRODUCT_CATALOG:VIEW') or hasAuthority('LEADS:VIEW')")
    @Operation(summary = "Get Products by Company", description = "Retrieves active products in the company catalog. "
            + "includeInactive=true (module 05: the catalogue screen's 'Show inactive') also returns deactivated products and needs PRODUCT_CATALOG:VIEW; "
            + "lead and quotation pickers never send it.")
    public ResponseEntity<List<ProductMasterDto>> getProducts(@RequestParam Long companyId,
                                                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(productService.getProductsByCompany(companyId, includeInactive));
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
    @Operation(summary = "Delete Product", description = "Removes an unused product (outcome DELETED). A product used by a lead or a quotation "
            + "is kept for their history and set inactive instead (outcome DEACTIVATED): it disappears from the catalogue and the pickers. "
            + "With flag products.company_wide_writes ON, product writes need company-wide scope (403 FORBIDDEN for branch-level users).")
    public ResponseEntity<com.leads.dto.settings.ProductDeleteResult> deleteProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.deleteProduct(id));
    }
}
