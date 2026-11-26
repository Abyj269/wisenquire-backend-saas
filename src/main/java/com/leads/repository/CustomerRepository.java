package com.leads.repository;

import com.leads.model.entity.Customer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    /** Module 16 P1 (P-10): one keyset page of a company's broadcast audience: [id, mobileNormalized, name]. */
    @Query("SELECT c.id, c.mobileNormalized, c.name FROM Customer c WHERE c.company.id = :companyId AND c.id > :after ORDER BY c.id")
    List<Object[]> broadcastAudiencePage(@Param("companyId") Long companyId, @Param("after") Long after, Pageable page);

    /** Module 16 P1 (P-10): the company's customers with these normalized numbers: [id, mobileNormalized, name]. */
    @Query("SELECT c.id, c.mobileNormalized, c.name FROM Customer c WHERE c.company.id = :companyId AND c.mobileNormalized IN :phones ORDER BY c.id")
    List<Object[]> broadcastAudienceByPhones(@Param("companyId") Long companyId, @Param("phones") java.util.Collection<String> phones);
    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM customers WHERE company_id=:companyId AND mobile_normalized LIKE 'legacy-%' AND REGEXP_REPLACE(mobile_number, '[^0-9]', '')=:digits", nativeQuery=true)
    java.util.Optional<Customer> findExactLegacy(@org.springframework.data.repository.query.Param("companyId") Long companyId,@org.springframework.data.repository.query.Param("digits") String digits);


    Optional<Customer> findByCompanyIdAndMobileNormalized(Long companyId, String mobileNormalized);

    List<Customer> findByCompanyId(Long companyId);

    List<Customer> findByCompanyIdAndHomeBranchId(Long companyId, Long branchId);

    // Module 07 P1: the typeahead is a Specification in CustomerService.suggest (branch + OWN scope, F-11).

    long countByCompanyId(Long companyId);
}
