package com.leads.repository;

import com.leads.model.entity.WhatsAppHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WhatsAppHolidayRepository extends JpaRepository<WhatsAppHoliday, Long> {

    @Query("SELECT DISTINCT h FROM WhatsAppHoliday h LEFT JOIN FETCH h.branches WHERE h.company.id = :companyId ORDER BY h.startDate")
    List<WhatsAppHoliday> findByCompanyIdOrderByStartDate(@Param("companyId") Long companyId);

    // Used by WhatsAppScheduleService.resolveAutoReply — every holiday whose
    // range covers `date` and that lists this branch. Ordered by id so
    // "first match wins" is deterministic if two holidays ever overlap.
    @Query("SELECT h FROM WhatsAppHoliday h JOIN h.branches b " +
            "WHERE b.id = :branchId AND :date BETWEEN h.startDate AND h.endDate ORDER BY h.id")
    List<WhatsAppHoliday> findActiveForBranch(@Param("branchId") Long branchId, @Param("date") LocalDate date);
}
