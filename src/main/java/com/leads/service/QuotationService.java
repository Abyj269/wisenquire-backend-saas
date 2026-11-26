package com.leads.service;

import com.leads.dto.quotation.QuotationDto;
import com.leads.dto.quotation.QuotationItemDto;
import com.leads.dto.quotation.QuotationRequest;
import com.leads.model.entity.*;
import com.leads.model.enums.ActivityType;
import com.leads.model.enums.QuotationStatus;
import com.leads.repository.LeadRepository;
import com.leads.repository.ProductMasterRepository;
import com.leads.repository.QuotationRepository;
import com.leads.repository.WhatsAppConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuotationService {
    private final TenantAccess access;
    private final CompanyClock companyClock;
    private final DocumentNumberService numbers;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final QuotationRepository quotationRepo;
    private final LeadRepository leadRepo;
    private final ProductMasterRepository productRepo;
    private final WhatsAppConversationRepository conversationRepo;
    private final WhatsAppCloudService whatsAppCloudService;
    private final QuotationPdfService pdfService;
    private final AuthService authService;
    private final LeadService leadService;

    @Transactional
    public QuotationDto createQuotation(QuotationRequest req) {
        access.record(com.leads.model.entity.Lead.class, req.getLeadId());
        Lead lead = leadRepo.findById(req.getLeadId())
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + req.getLeadId()));
        User currentUser = authService.getCurrentUser();

        Quotation quotation = Quotation.builder()
                .organization(lead.getOrganization())
                .company(lead.getCompany())
                .lead(lead)
                .quotationNumber(generateQuotationNumber(lead.getCompany().getId()))
                .customerName(req.getCustomerName() != null ? req.getCustomerName() : lead.getFullName())
                .customerPhone(req.getCustomerPhone() != null ? req.getCustomerPhone() : lead.getPhone())
                .customerEmail(req.getCustomerEmail() != null ? req.getCustomerEmail() : lead.getEmail())
                .customerCompanyName(req.getCustomerCompanyName() != null ? req.getCustomerCompanyName() : lead.getCustomerCompanyName())
                .customerTaxNumber(req.getCustomerTaxNumber())
                .customerAddress(req.getCustomerAddress())
                .notes(req.getNotes())
                .termsAndConditions(req.getTermsAndConditions())
                .currency(req.getCurrency() != null && !req.getCurrency().isBlank() ? req.getCurrency() : LeadService.resolveDefaultCurrency(lead))
                .status(QuotationStatus.DRAFT)
                .createdBy(currentUser)
                .build();

        applyItems(quotation, req.getItems());
        Quotation saved = quotationRepo.save(quotation);
        leadService.recordActivity(lead, ActivityType.QUOTATION, "Quotation Created",
                "Quotation " + saved.getQuotationNumber() + " created — total " + saved.getCurrency() + " " + saved.getGrandTotal(),
                currentUser);
        return mapToDto(saved);
    }

    @Transactional
    public QuotationDto updateQuotation(Long id, QuotationRequest req) {
        access.record(com.leads.model.entity.Quotation.class, id);
        Quotation quotation = quotationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quotation not found: " + id));
        User currentUser = authService.getCurrentUser();

        requireDraft(quotation);
        if (req.getLeadId() != null && !req.getLeadId().equals(quotation.getLead().getId())) {
            throw new IllegalArgumentException("A quotation cannot be moved to another lead.");
        }

        quotation.setCustomerName(req.getCustomerName());
        quotation.setCustomerPhone(req.getCustomerPhone());
        quotation.setCustomerEmail(req.getCustomerEmail());
        quotation.setCustomerCompanyName(req.getCustomerCompanyName());
        quotation.setCustomerTaxNumber(req.getCustomerTaxNumber());
        quotation.setCustomerAddress(req.getCustomerAddress());
        quotation.setNotes(req.getNotes());
        quotation.setTermsAndConditions(req.getTermsAndConditions());
        if (req.getCurrency() != null && !req.getCurrency().isBlank()) {
            quotation.setCurrency(req.getCurrency());
        }

        quotation.getItems().clear();
        applyItems(quotation, req.getItems());
        Quotation saved = quotationRepo.save(quotation);
        leadService.recordActivity(saved.getLead(), ActivityType.QUOTATION, "Quotation Updated",
                "Quotation " + saved.getQuotationNumber() + " updated — new total " + saved.getCurrency() + " " + saved.getGrandTotal(),
                currentUser);
        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public QuotationDto getQuotation(Long id) {
        return mapToDto(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<QuotationDto> listQuotations(Long companyId, Long leadId, QuotationStatus status, String search,
                                              java.time.LocalDate fromDate, java.time.LocalDate toDate, Pageable pageable) {
        access.company(companyId);
        com.leads.util.InputRules.dateRange(fromDate, toDate);
        pageable = com.leads.util.InputRules.page(pageable, "createdAt", "updatedAt", "quotationNumber", "grandTotal", "id");
        if (leadId != null) access.record(Lead.class, leadId);
        List<Long> permittedBranches = access.branchIds();
        // D14 (module 08 P1): an OWN user lists the quotations they may open: their lead's (or an
        // unassigned lead's with include_unassigned) or ones they created. Same rule as record(Quotation).
        final Long ownMe = access.ownerFilter();
        final boolean ownUnassigned = ownMe != null && access.ownIncludesUnassigned();
        // D15 (module 08 P1): "from"/"to" are company-local days.
        final Company zoneCompany = companyId != null ? access.company(companyId) : null;
        final java.time.LocalDateTime fromBound = fromDate == null ? null : companyClock.range(zoneCompany, fromDate, fromDate).start();
        final java.time.LocalDateTime toBound = toDate == null ? null : companyClock.range(zoneCompany, toDate, toDate).endExclusive();
        Specification<Quotation> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (permittedBranches != null) predicates.add(root.get("lead").get("branch").get("id").in(permittedBranches));
            if (ownMe != null) {
                var assignee = root.join("lead").join("assignedTo", jakarta.persistence.criteria.JoinType.LEFT);
                var creator = root.join("createdBy", jakarta.persistence.criteria.JoinType.LEFT);
                var mine = cb.or(cb.equal(assignee.get("id"), ownMe), cb.equal(creator.get("id"), ownMe));
                predicates.add(ownUnassigned ? cb.or(mine, cb.isNull(assignee.get("id"))) : mine);
            }
            if (companyId != null) predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (leadId != null) predicates.add(cb.equal(root.get("lead").get("id"), leadId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (fromBound != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromBound));
            if (toBound != null) predicates.add(cb.lessThan(root.get("createdAt"), toBound));
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("quotationNumber")), pattern),
                        cb.like(cb.lower(root.get("customerName")), pattern),
                        cb.like(cb.lower(root.get("lead").get("leadNumber")), pattern)
                ));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return quotationRepo.findAll(spec, pageable).map(this::mapToDto);
    }

    @Transactional
    public void deleteQuotation(Long id) {
        access.record(com.leads.model.entity.Quotation.class, id);
        requireDraft(findOrThrow(id));
        quotationRepo.deleteById(id);
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(Long id) {
        return pdfService.render(findOrThrow(id), authService.getCurrentUser());
    }

    /**
     * Renders the quotation as a PDF and pushes it into the lead's WhatsApp
     * conversation as a document message (WhatsAppCloudService.sendMediaMessage
     * already handles the Meta upload + send — no new WhatsApp-side plumbing
     * needed here). Flips status to SENT once it succeeds.
     */
    // KNOWN VIOLATION of CLAUDE.md hard rule 2 (found in module 08 P1, owner decision: fix in module 12):
    // this transaction stays open across sendMediaMessage's Meta upload/send, and sendMediaMessage is
    // itself @Transactional. Module 12 restructures the WhatsApp send path (outbox) and closes both.
    @Transactional(noRollbackFor = com.leads.exception.WhatsAppApiException.class)
    public QuotationDto sendViaWhatsApp(Long id, Long explicitConversationId) {
        Quotation quotation = findOrThrow(id);
        User currentUser = authService.getCurrentUser();

        Long conversationId = explicitConversationId;
        if (conversationId == null) {
            WhatsAppConversation conv = conversationRepo.findByLeadId(quotation.getLead().getId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "This lead doesn't have a WhatsApp conversation yet — start one before sending a quotation this way."));
            conversationId = conv.getId();
        }

        WhatsAppConversation recipient = access.record(WhatsAppConversation.class, conversationId);
        if (recipient.getLead() == null || !recipient.getLead().getId().equals(quotation.getLead().getId())
                || !recipient.getCompany().getId().equals(quotation.getCompany().getId())
                || !com.leads.util.PhoneNumberUtil.canonical(recipient.getCustomerWaId()).equals(
                        com.leads.util.PhoneNumberUtil.canonical(quotation.getCustomerPhone()))) {
            throw new IllegalArgumentException("Select the linked lead's conversation with the quotation customer's phone number.");
        }
        byte[] pdfBytes = pdfService.render(quotation, currentUser);
        String filename = "Quotation-" + quotation.getQuotationNumber() + ".pdf";
        String caption = "Quotation " + quotation.getQuotationNumber() + " — total " + quotation.getCurrency() + " " + quotation.getGrandTotal();
        var delivery = whatsAppCloudService.sendMediaMessage(conversationId, pdfBytes, filename, "application/pdf", caption, null);
        if (delivery == null || delivery.getStatus() == com.leads.model.enums.MessageStatus.FAILED
                || delivery.getStatus() == com.leads.model.enums.MessageStatus.UNKNOWN
                || delivery.getWamid() == null || delivery.getWamid().isBlank()) {
            throw new com.leads.exception.WhatsAppApiException("Quotation delivery failed. Check the conversation before retrying.");
        }

        quotation.setStatus(QuotationStatus.SENT);
        Quotation saved = quotationRepo.save(quotation);
        leadService.recordActivity(saved.getLead(), ActivityType.QUOTATION, "Quotation Sent via WhatsApp",
                "Quotation " + saved.getQuotationNumber() + " (" + saved.getCurrency() + " " + saved.getGrandTotal() + ") was sent to the customer via WhatsApp.",
                currentUser);
        return mapToDto(saved);
    }

    private Quotation findOrThrow(Long id) {
        access.record(com.leads.model.entity.Quotation.class, id);
        return quotationRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Quotation not found: " + id));
    }

    private void applyItems(Quotation quotation, List<QuotationItemDto> itemDtos) {
        com.leads.util.InputRules.currency(quotation.getCurrency());
        if (itemDtos == null || itemDtos.isEmpty() || itemDtos.size() > 200) {
            throw new IllegalArgumentException("A quotation requires between 1 and 200 items.");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        int sortOrder = 0;
        for (QuotationItemDto dto : itemDtos) {
            if (dto == null) throw new IllegalArgumentException("Quotation items cannot be null.");
            ProductMaster product = dto.getProductId() != null ? productRepo.findById(dto.getProductId()).orElse(null) : null;
            if (dto.getProductId() != null && (product == null || !Boolean.TRUE.equals(product.getIsActive())
                    || !product.getCompany().getId().equals(quotation.getCompany().getId())
                    || !quotation.getCurrency().equals(product.getCurrency()))) {
                throw new IllegalArgumentException("Select an active product from this company in the quotation currency.");
            }

            int quantity = dto.getQuantity() != null ? dto.getQuantity() : 1;
            BigDecimal unitPrice = dto.getUnitPrice() != null ? dto.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal discountPercent = dto.getDiscountPercent() != null ? dto.getDiscountPercent() : BigDecimal.ZERO;
            BigDecimal taxPercent = dto.getTaxPercent() != null ? dto.getTaxPercent() : BigDecimal.ZERO;
            if (quantity <= 0 || quantity > 1000000) throw new IllegalArgumentException("Item quantity must be between 1 and 1000000.");
            com.leads.util.InputRules.amount(unitPrice, "Unit price");
            com.leads.util.InputRules.percentage(discountPercent, "Discount");
            com.leads.util.InputRules.percentage(taxPercent, "Tax");

            BigDecimal lineBase = unitPrice.multiply(BigDecimal.valueOf(quantity));
            BigDecimal discountAmount = lineBase.multiply(discountPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal afterDiscount = lineBase.subtract(discountAmount);
            BigDecimal taxAmount = afterDiscount.multiply(taxPercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = afterDiscount.add(taxAmount);

            QuotationItem item = QuotationItem.builder()
                    .quotation(quotation)
                    .product(product)
                    .itemName(dto.getItemName() != null ? dto.getItemName() : (product != null ? product.getProductName() : ""))
                    .itemDescription(dto.getItemDescription())
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .discountPercent(discountPercent)
                    .discountAmount(discountAmount)
                    .taxPercent(taxPercent)
                    .taxAmount(taxAmount)
                    .lineTotal(lineTotal)
                    .sortOrder(sortOrder++)
                    .build();
            quotation.getItems().add(item);

            subtotal = subtotal.add(lineBase);
            totalDiscount = totalDiscount.add(discountAmount);
            totalTax = totalTax.add(taxAmount);
            grandTotal = grandTotal.add(lineTotal);
        }

        com.leads.util.InputRules.amount(subtotal, "Subtotal");
        com.leads.util.InputRules.amount(grandTotal, "Grand total");
        quotation.setSubtotal(subtotal);
        quotation.setTotalDiscount(totalDiscount);
        quotation.setTotalTax(totalTax);
        quotation.setGrandTotal(grandTotal);
    }

    private String generateQuotationNumber(Long companyId) {
        return numbers.next("QT", companyId);
    }

    private void requireDraft(Quotation quotation) {
        if (quotation.getStatus() != QuotationStatus.DRAFT) {
            throw new IllegalArgumentException("Sent quotations are immutable. Create a new quotation for revised terms.");
        }
    }

    public QuotationDto mapToDto(Quotation q) {
        List<QuotationItemDto> itemDtos = q.getItems().stream()
                .map(item -> QuotationItemDto.builder()
                        .id(item.getId())
                        .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                        .itemName(item.getItemName())
                        .itemDescription(item.getItemDescription())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .discountPercent(item.getDiscountPercent())
                        .discountAmount(item.getDiscountAmount())
                        .taxPercent(item.getTaxPercent())
                        .taxAmount(item.getTaxAmount())
                        .lineTotal(item.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        return QuotationDto.builder()
                .id(q.getId())
                .leadId(q.getLead().getId())
                .leadNumber(q.getLead().getLeadNumber())
                .leadName(q.getLead().getFullName())
                .companyId(q.getCompany().getId())
                .quotationNumber(q.getQuotationNumber())
                .customerName(q.getCustomerName())
                .customerPhone(q.getCustomerPhone())
                .customerEmail(q.getCustomerEmail())
                .customerCompanyName(q.getCustomerCompanyName())
                .customerTaxNumber(q.getCustomerTaxNumber())
                .customerAddress(q.getCustomerAddress())
                .notes(q.getNotes())
                .termsAndConditions(q.getTermsAndConditions())
                .subtotal(q.getSubtotal())
                .totalDiscount(q.getTotalDiscount())
                .totalTax(q.getTotalTax())
                .grandTotal(q.getGrandTotal())
                .currency(q.getCurrency())
                .status(q.getStatus())
                .createdByName(q.getCreatedBy() != null ? q.getCreatedBy().getFullName() : null)
                .items(itemDtos)
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt())
                .build();
    }
}
