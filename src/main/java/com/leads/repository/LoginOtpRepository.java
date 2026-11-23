package com.leads.repository;

import com.leads.model.entity.LoginOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoginOtpRepository extends JpaRepository<LoginOtp, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<LoginOtp> findByChallengeId(String challengeId);

    List<LoginOtp> findByUserIdAndIsUsedFalseOrderByCreatedAtDesc(Long userId);
}
