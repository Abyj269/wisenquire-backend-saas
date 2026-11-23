package com.leads.service;

import com.leads.config.JwtTokenProvider;
import com.leads.dto.auth.AuthResponse;
import com.leads.dto.auth.LoginRequest;
import com.leads.dto.auth.OtpVerifyRequest;
import com.leads.dto.auth.UserProfileDto;
import com.leads.exception.LoginNotAllowedException;
import com.leads.model.entity.Branch;
import com.leads.model.entity.BranchTrustedIp;
import com.leads.model.entity.Company;
import com.leads.model.entity.LoginOtp;
import com.leads.model.entity.Role;
import com.leads.model.entity.RolePermission;
import com.leads.model.entity.User;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.BranchRepository;
import com.leads.repository.BranchTrustedIpRepository;
import com.leads.repository.LoginOtpRepository;
import com.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final BranchRepository branchRepository;
    private final BranchTrustedIpRepository branchTrustedIpRepository;
    private final LoginOtpRepository loginOtpRepository;
    private final EmailService emailService;
    private final JwtTokenProvider tokenProvider;
    private final RequestRateLimiter rateLimiter;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Value("${app.security.otp.length:4}")
    private int otpLength;

    @Value("${app.security.otp.expiry-minutes:5}")
    private int otpExpiryMinutes;

    @Value("${app.security.otp.expose-in-response:false}")
    private boolean exposeOtpInResponse;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public AuthResponse login(LoginRequest loginRequest, String clientIp) {
        rateLimiter.check("login-ip:" + clientIp, 30, 60000);
        rateLimiter.check("login-user:" + loginRequest.getEmail().toLowerCase(java.util.Locale.ROOT), 10, 60000);
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getEmail(),
                        loginRequest.getPassword()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        User user = findByEmail(loginRequest.getEmail());

        // IP restriction is an optional per-branch flag — it only applies at
        // all once at least one of the user's branches has it switched on.
        List<Long> restrictedBranchIds = resolveIpRestrictedBranchIds(user);
        boolean ipCheckActive = !restrictedBranchIds.isEmpty();
        boolean clientIpTrusted = ipCheckActive && isIpTrusted(restrictedBranchIds, clientIp);

        if (Boolean.TRUE.equals(user.getIsTrustedUser())) {
            // A trusted user logging in from an already-permitted IP doesn't
            // need the extra OTP factor — the IP itself already vouches for
            // them. Only fall back to OTP when that isn't the case (including
            // when IP restriction isn't active at all for their branches).
            if (clientIpTrusted) {
                return buildTokenResponse(user);
            }
            return issueOtpChallenge(user);
        }

        if (ipCheckActive && !clientIpTrusted) {
            throw new LoginNotAllowedException(
                    "Login is not permitted from this location (" + clientIp + "). " +
                    "Contact your administrator to add this IP to your branch's trusted list.");
        }

        return buildTokenResponse(user);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public AuthResponse verifyOtp(OtpVerifyRequest req) {
        rateLimiter.check("otp:" + req.getChallengeId(), 8, 300000);
        LoginOtp latest = loginOtpRepository.findByChallengeId(req.getChallengeId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired login challenge."));
        User user = latest.getUser();
        if (!user.getEmail().equalsIgnoreCase(req.getEmail()) || Boolean.TRUE.equals(latest.getIsUsed())
                || latest.getAttempts() >= 5 || latest.getExpiresAt().isBefore(LocalDateTime.now())
                || !Boolean.TRUE.equals(user.getIsActive())
                || !java.util.Objects.equals(latest.getSessionVersion(), user.getSessionVersion())
                || (user.getOrganization()!=null && !Boolean.TRUE.equals(user.getOrganization().getIsActive()))) {
            throw new IllegalArgumentException("Invalid or expired login challenge.");
        }
        latest.setAttempts(latest.getAttempts() + 1);
        if (!passwordEncoder.matches(req.getOtp().trim(), latest.getCode())) {
            loginOtpRepository.saveAndFlush(latest);
            throw new IllegalArgumentException("Invalid OTP code.");
        }
        latest.setIsUsed(true);
        loginOtpRepository.saveAndFlush(latest);
        return buildTokenResponse(user);
    }

    private AuthResponse issueOtpChallenge(User user) {
        rateLimiter.check("otp-issue:" + user.getId(), 1, 60000);
        loginOtpRepository.findByUserIdAndIsUsedFalseOrderByCreatedAtDesc(user.getId()).forEach(old -> old.setIsUsed(true));
        String code = generateOtpCode();
        LoginOtp otp = LoginOtp.builder()
                .user(user)
                .code(passwordEncoder.encode(code))
                .challengeId(java.util.UUID.randomUUID().toString())
                .sessionVersion(user.getSessionVersion())
                .expiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes))
                .isUsed(false)
                .build();
        loginOtpRepository.save(otp);

        if (!exposeOtpInResponse) emailService.sendOtpEmail(user.getEmail(), code, otpExpiryMinutes);

        return AuthResponse.builder()
                .otpRequired(true)
                .otpPendingEmail(user.getEmail())
                .challengeId(otp.getChallengeId())
                .devOtpHint(exposeOtpInResponse ? code : null)
                .build();
    }

    /**
     * Branches where an admin has explicitly switched on IP restriction
     * (Branch.ipRestrictionEnabled) within this user's tenant scope. An empty
     * result means IP is not checked at all for this login, regardless of
     * whether any BranchTrustedIp rows exist. Super Admin and
     * Organization-level users have no fixed branch scope, so this is always
     * empty for them.
     */
    private List<Long> resolveIpRestrictedBranchIds(User user) {
        if (Boolean.TRUE.equals(user.getIsSuperAdmin()) || user.getRole().getRoleLevel() == RoleLevel.ORGANIZATION) {
            return List.of();
        }

        Collection<Branch> scopedBranches;
        if (user.getRole().getRoleLevel() == RoleLevel.COMPANY) {
            List<Long> companyIds = user.getAllowedCompanies().stream().map(Company::getId).collect(Collectors.toList());
            scopedBranches = companyIds.isEmpty() ? List.of() : branchRepository.findByCompanyIdIn(companyIds);
        } else {
            scopedBranches = user.getAllowedBranches();
        }

        return scopedBranches.stream()
                .filter(b -> Boolean.TRUE.equals(b.getIpRestrictionEnabled()))
                .map(Branch::getId)
                .collect(Collectors.toList());
    }

    private boolean isIpTrusted(List<Long> branchIds, String clientIp) {
        List<BranchTrustedIp> trustedIps = branchTrustedIpRepository.findByBranchIdInAndIsActiveTrue(branchIds);
        return trustedIps.stream().anyMatch(ip -> ip.getIpAddress().trim().equalsIgnoreCase(clientIp));
    }

    private String generateOtpCode() {
        int max = (int) Math.pow(10, otpLength);
        int code = RANDOM.nextInt(max);
        return String.format("%0" + otpLength + "d", code);
    }

    private User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    private AuthResponse buildTokenResponse(User user) {
        String token = tokenProvider.generateToken(user);
        UserProfileDto profile = buildUserProfileDto(user);

        return AuthResponse.builder()
                .token(token)
                .expiresIn(86400000L)
                .user(profile)
                .build();
    }

    @Transactional(readOnly = true)
    public UserProfileDto getCurrentUserProfile(String email) {
        return buildUserProfileDto(findByEmail(email));
    }

    @Transactional(readOnly = true)
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        // Authentication.getName() is the email — see CustomUserDetailsService,
        // which builds the security principal from User.email.
        return userRepository.findByEmail(authentication.getName()).orElse(null);
    }

    public UserProfileDto buildUserProfileDto(User user) {
        Role role = user.getRole();
        Map<String, UserProfileDto.PermissionSetDto> permissions = new HashMap<>();
        for (RolePermission p : role.getPermissions()) {
            permissions.put(p.getModuleName(), UserProfileDto.PermissionSetDto.builder()
                    .canView(p.getCanView())
                    .canAdd(p.getCanAdd())
                    .canEdit(p.getCanEdit())
                    .canDelete(p.getCanDelete())
                    .canExport(p.getCanExport())
                    .canApprove(p.getCanApprove())
                    .canAssign(p.getCanAssign())
                    .canPrint(p.getCanPrint())
                    .build());
        }

        List<UserProfileDto.CompanyScopeDto> companies = user.getAllowedCompanies().stream()
                .map(c -> UserProfileDto.CompanyScopeDto.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .code(c.getCode())
                        .logoUrl(c.getLogoUrl())
                        .timezone(c.getTimezone())
                        .build())
                .collect(Collectors.toList());

        List<UserProfileDto.BranchScopeDto> branches = user.getAllowedBranches().stream()
                .map(b -> UserProfileDto.BranchScopeDto.builder()
                        .id(b.getId())
                        .companyId(b.getCompany().getId())
                        .name(b.getName())
                        .code(b.getCode())
                        .build())
                .collect(Collectors.toList());

        return UserProfileDto.builder()
                .gettingStartedSeen(user.isGettingStartedSeen())
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .isSuperAdmin(user.getIsSuperAdmin())
                .isTrustedUser(user.getIsTrustedUser())
                .organizationId(user.getOrganization() != null ? user.getOrganization().getId() : null)
                .organizationName(user.getOrganization() != null ? user.getOrganization().getName() : null)
                .roleId(role.getId())
                .roleName(role.getRoleName())
                .roleLevel(role.getRoleLevel())
                .allowedCompanies(companies)
                .allowedBranches(branches)
                .permissions(permissions)
                .build();
    }
}
