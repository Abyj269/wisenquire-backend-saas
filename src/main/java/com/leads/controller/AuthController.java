package com.leads.controller;

import com.leads.dto.auth.AuthResponse;
import com.leads.dto.auth.LoginRequest;
import com.leads.dto.auth.OtpVerifyRequest;
import com.leads.dto.auth.UserProfileDto;
import com.leads.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Endpoints for user login and profile inspection")
public class AuthController {

    private final AuthService authService;
    private final com.leads.service.TenantAccess access;
    private final com.leads.repository.UserRepository userRepository;

    @PostMapping("/getting-started/seen")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    @org.springframework.transaction.annotation.Transactional
    public void markGettingStartedSeen() {
        var user = access.actor();
        user.setGettingStartedSeen(true);
        userRepository.save(user);
    }

    @PostMapping("/login")
    @Operation(summary = "User Login", description = "Authenticates user by email and password. Returns a JWT directly, or an OTP challenge (otpRequired: true) for trusted users.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request) {
        return ResponseEntity.ok(authService.login(loginRequest, resolveClientIp(request)));
    }

    @PostMapping("/verify-otp")
    @Operation(summary = "Verify Login OTP", description = "Completes a trusted user's login by confirming the 4-digit code emailed to them.")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        return ResponseEntity.ok(authService.verifyOtp(req));
    }

    private String resolveClientIp(HttpServletRequest request) {
        // The servlet remote address is trusted; arbitrary forwarding headers are not.
        return request.getRemoteAddr();
    }

    @GetMapping("/profile")
    @Operation(summary = "Get Current User Profile", description = "Returns the current authenticated user details and permission matrix.")
    public ResponseEntity<UserProfileDto> getProfile(@AuthenticationPrincipal UserDetails userDetails) {
        // UserDetails.getUsername() is Spring Security's own interface method —
        // it holds the user's email here, since CustomUserDetailsService builds
        // the principal from User.email (the sole login/security identity).
        return ResponseEntity.ok(authService.getCurrentUserProfile(userDetails.getUsername()));
    }
}
