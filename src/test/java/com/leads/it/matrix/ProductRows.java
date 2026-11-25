package com.leads.it.matrix;

import com.leads.model.entity.ProductMaster;

import java.util.List;

import static com.leads.it.matrix.BroadcastRows.str;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY;
import static com.leads.it.matrix.EndpointSpec.Scope.COMPANY_WIDE;
import static com.leads.it.matrix.EndpointSpec.json;
import static com.leads.it.matrix.EndpointSpec.withJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Module 05 P1: the 4 ProductController endpoints. The read is COMPANY scope (lead and quotation
 * forms need it). Writes are COMPANY_WIDE (F-12): with flag products.company_wide_writes ON
 * ({@link Matrix#FLAGS_ON}) the branch manager and the agents are refused on their own company.
 */
final class ProductRows {

    static List<EndpointSpec> specs() {
        return List.of(
                EndpointSpec.of("GET /products", COMPANY, (f, s, p) -> get("/products").param("companyId", str(f.company(s)))),
                EndpointSpec.of("POST /products", COMPANY_WIDE, (f, s, p) -> withJson(post("/products").param("companyId", str(f.company(s))),
                        json("productCode", "IT-NEW-" + f.phone().substring(6), "productName", "IT New Product", "unitPrice", 12.5))),
                EndpointSpec.of("PUT /products/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long id = f.freshProduct(s);
                    p.watch(() -> f.state(ProductMaster.class, id, x -> x.getProductName() + "|" + x.getUnitPrice() + "|" + x.getIsActive()));
                    return withJson(put("/products/{id}", id), json("productCode", f.state(ProductMaster.class, id, ProductMaster::getProductCode),
                            "productName", "IT Renamed Product", "unitPrice", 11));
                }),
                EndpointSpec.of("DELETE /products/{id}", COMPANY_WIDE, (f, s, p) -> {
                    Long id = f.freshProduct(s);
                    p.watch(() -> f.exists(ProductMaster.class, id) ? f.state(ProductMaster.class, id, x -> String.valueOf(x.getIsActive())) : "deleted");
                    return delete("/products/{id}", id);
                }));
    }

    private ProductRows() { }
}
