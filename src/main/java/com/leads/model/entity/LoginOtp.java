package com.leads.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A one-time 4-digit code issued to a trusted user's email at login time, in
 * place of the branch-IP check that applies to everyone else. Single-use and
 * short-lived — verified in AuthService.verifyOtp().
 */
@Entity
@Table(name = "login_otps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(length = 36, unique = true)
    private String challengeId;
    @Builder.Default
    private Integer attempts = 0;
    @Builder.Default
    private Long sessionVersion = 0L;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isUsed = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
