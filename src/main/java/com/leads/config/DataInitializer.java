package com.leads.config;

import com.leads.model.entity.Role;
import com.leads.model.entity.User;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.RoleRepository;
import com.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Seeds only the platform's first login — a Super Admin, with no
// Organization/Company/Branch of their own (organization=null, matching the
// "org-less, god-mode" Super Admin design already used throughout the RBAC
// model). Everything else — the first Organization, its Companies, and
// Branches — is created afterward through the running app: log in as this
// Super Admin and use Settings > Tenant Admin to create them, which exercises
// the same POST /tenant/organizations|companies|branches endpoints a real
// customer onboarding would use, rather than being pre-seeded as demo data.
//
// Deliberately runs in every profile, including prod — unlike the old demo
// seed this replaced, it creates no organization/company/leads/WhatsApp data,
// only the one account needed to log in at all, and is idempotent (skips
// once any user exists) so it's a no-op after the very first boot.
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.security.super-admin.email:akhil@germanparts.ae}")
    private String superAdminEmail;

    @Value("${app.security.super-admin.password:Admin@123}")
    private String superAdminPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.count() > 0) {
            log.info("Database already has users. Skipping Super Admin seeding.");
            return;
        }

        log.info("Seeding the platform's first login: Super Admin ({})...", superAdminEmail);

        Role superAdminRole = roleRepo.save(Role.builder()
                .organization(null)
                .roleName("Super Technical Administrator")
                .roleLevel(RoleLevel.SUPER_ADMIN)
                .description("System owner god mode with global control")
                .isSystemRole(true)
                .build());

        userRepo.save(User.builder()
                .organization(null)
                .role(superAdminRole)
                .email(superAdminEmail)
                .password(passwordEncoder.encode(superAdminPassword))
                .firstName("Super")
                .lastName("Administrator")
                .isSuperAdmin(true)
                .isActive(true)
                // Logs in from anywhere via the email OTP challenge rather than
                // a branch IP allowlist (which wouldn't apply to a platform-wide
                // role with no branch of its own anyway).
                .isTrustedUser(true)
                .build());

        log.info("Super Admin seeded. Log in as '{}' and use Settings > Tenant Admin to create your first Organization, Company, and Branch.", superAdminEmail);
    }
}
