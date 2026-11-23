package com.leads.repository;

import com.leads.model.entity.BroadcastRecipient;
import com.leads.model.enums.BroadcastRecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BroadcastRecipientRepository extends JpaRepository<BroadcastRecipient, Long> {

    Page<BroadcastRecipient> findByBroadcastId(Long broadcastId, Pageable pageable);

    Optional<BroadcastRecipient> findByWamid(String wamid);

    List<BroadcastRecipient> findByBroadcastIdAndStatus(Long broadcastId, BroadcastRecipientStatus status);

    long countByBroadcastIdAndStatus(Long broadcastId, BroadcastRecipientStatus status);
}
