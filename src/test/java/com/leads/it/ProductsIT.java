package com.leads.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leads.flags.FeatureFlag;
import com.leads.flags.FeatureFlags;
import com.leads.it.fixture.Persona;
import com.leads.it.fixture.Slot;
import com.leads.model.entity.ProductMaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Module 05 P1: delete of a used product deactivates it; F-12 (products.company_wide_writes) OFF and ON; minimal updates. */
class ProductsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FeatureFlags flags;
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void tearDown() { flags.clearOverride(FeatureFlag.PRODUCTS_COMPANY_WIDE_WRITES, fixture.org('A')); }

    private MockHttpServletResponse call(Persona p, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + fixture.token('A', p))).andReturn().getResponse();
    }

    private MockHttpServletResponse send(Persona p, MockHttpServletRequestBuilder b, Map<String, ?> body) throws Exception {
        return call(p, b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private String deleteOutcome(Long product) throws Exception {
        MockHttpServletResponse r = call(Persona.ORG_ADMIN, delete("/products/{id}", product));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        return json.readTree(r.getContentAsString()).path("outcome").asText();
    }

    private boolean listed(Long product) throws Exception {
        MockHttpServletResponse r = call(Persona.ORG_ADMIN, get("/products").param("companyId", String.valueOf(fixture.company(Slot.A1a))));
        for (JsonNode p : json.readTree(r.getContentAsString())) if (p.path("id").asLong() == product) return true;
        return false;
    }

    // ---- delete -------------------------------------------------------------------------------

    @Test
    void anUnusedProductIsDeleted() throws Exception {
        Long product = fixture.freshProduct(Slot.A1a);
        assertEquals("DELETED", deleteOutcome(product));
        assertFalse(fixture.exists(ProductMaster.class, product));
    }

    @Test
    void aProductOnALeadIsDeactivatedNotDeleted() throws Exception {
        Long product = fixture.freshProduct(Slot.A1a);
        fixture.useProductOnLead(Slot.A1a, product);
        assertTrue(listed(product));
        assertEquals("DEACTIVATED", deleteOutcome(product), "was a 500 on the lead_products FK");
        assertEquals("false", fixture.state(ProductMaster.class, product, x -> String.valueOf(x.getIsActive())));
        assertFalse(listed(product), "hidden from the catalogue and the pickers");
    }

    @Test
    void aProductOnAQuotationIsDeactivatedNotDeleted() throws Exception {
        Long product = fixture.freshProduct(Slot.A1a);
        fixture.useProductOnQuotation(Slot.A1a, product);
        assertEquals("DEACTIVATED", deleteOutcome(product), "was a 500 on the quotation_items FK");
        assertTrue(fixture.exists(ProductMaster.class, product));
    }

    @Test
    void showInactiveListsDeactivatedProductsAndTheyCanBeReactivated() throws Exception {
        Long product = fixture.freshProduct(Slot.A1a);
        fixture.useProductOnLead(Slot.A1a, product);
        assertEquals("DEACTIVATED", deleteOutcome(product));
        String company = String.valueOf(fixture.company(Slot.A1a));
        MockHttpServletResponse all = call(Persona.ORG_ADMIN, get("/products").param("companyId", company).param("includeInactive", "true"));
        boolean found = false;
        for (JsonNode p : json.readTree(all.getContentAsString())) if (p.path("id").asLong() == product) found = !p.path("isActive").asBoolean();
        assertTrue(found, "listed, marked inactive");
        assertFalse(listed(product), "the default (pickers) still hides it");

        MockHttpServletResponse r = send(Persona.ORG_ADMIN, put("/products/{id}", product), Map.of("isActive", true, "unitPrice", 10,
                "productCode", fixture.state(ProductMaster.class, product, ProductMaster::getProductCode), "productName", "IT Reactivated"));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertTrue(listed(product), "reactivated");
    }

    // ---- F-12 ---------------------------------------------------------------------------------

    private int createAs(Persona p) throws Exception {
        return send(p, post("/products").param("companyId", String.valueOf(fixture.company(Slot.A1a))),
                Map.of("productCode", "IT-F12-" + fixture.phone().substring(6), "productName", "IT F-12", "unitPrice", 5)).getStatus();
    }

    @Test
    void withTheFlagOffBranchLevelEditorsKeepTheirRights() throws Exception {
        assertEquals(200, createAs(Persona.BRANCH_MGR), "old behaviour (flag default OFF)");
    }

    @Test
    void withTheFlagOnOnlyCompanyWideUsersWrite() throws Exception {
        flags.setOverride(FeatureFlag.PRODUCTS_COMPANY_WIDE_WRITES, fixture.org('A'), true, "it", "ProductsIT");
        assertEquals(403, createAs(Persona.BRANCH_MGR));
        assertEquals(403, createAs(Persona.AGENT_OWN_1));
        assertEquals(200, createAs(Persona.COMPANY_MGR));
        Long product = fixture.freshProduct(Slot.A1a);
        assertEquals(403, call(Persona.BRANCH_MGR, delete("/products/{id}", product)).getStatus(), "record-based writes too");
        assertTrue(fixture.exists(ProductMaster.class, product));
        assertEquals(200, call(Persona.AGENT_OWN_1, get("/products").param("companyId", String.valueOf(fixture.company(Slot.A1a)))).getStatus(),
                "everyone still reads");
    }

    // ---- update without optional fields ---------------------------------------------------------

    @Test
    void anUpdateThatLeavesOutIsActiveAndCurrencyKeepsThem() throws Exception {
        Long product = fixture.freshProduct(Slot.A1a);
        String currency = fixture.state(ProductMaster.class, product, ProductMaster::getCurrency);
        MockHttpServletResponse r = send(Persona.ORG_ADMIN, put("/products/{id}", product), Map.of(
                "productCode", fixture.state(ProductMaster.class, product, ProductMaster::getProductCode), "productName", "IT Minimal", "unitPrice", 7));
        assertEquals(200, r.getStatus(), r.getContentAsString());
        assertEquals("IT Minimal|true|" + currency, fixture.state(ProductMaster.class, product,
                x -> x.getProductName() + "|" + x.getIsActive() + "|" + x.getCurrency()));
    }
}
