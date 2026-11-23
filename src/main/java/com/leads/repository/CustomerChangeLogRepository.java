package com.leads.repository;

import com.leads.model.entity.CustomerChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerChangeLogRepository extends JpaRepository<CustomerChangeLog, Long> {
    List<CustomerChangeLog> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
