package com.leads.config;

import com.leads.model.entity.RolePermission;
import com.leads.model.entity.User;
import com.leads.model.enums.RoleLevel;
import com.leads.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    // Method name is fixed by the UserDetailsService contract — the "username"
    // it resolves is actually the user's email, which is the sole login/JWT
    // identity in this app (see JwtTokenProvider, AuthService).
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
        return toUserDetails(user);
    }

    /** Builds the Spring Security principal from an already-loaded user (no query). */
    public UserDetails toUserDetails(User user) {

        Set<GrantedAuthority> authorities = new HashSet<>();
        // Add Role Level authority
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().getRoleLevel().name()));

        // A user is treated as a super admin either via the dedicated flag
        // or by holding a role whose level is itself SUPER_ADMIN — matches
        // AuthService.hasPermission()'s bypass on the frontend, which already
        // treats both as full access. Without this, a SUPER_ADMIN-level role
        // without the separate flag set would see every nav link/route in
        // the UI but get 403s from every @PreAuthorize check that isn't
        // covered by its own (possibly incomplete) RolePermission rows.
        if (Boolean.TRUE.equals(user.getIsSuperAdmin()) || user.getRole().getRoleLevel() == RoleLevel.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        }

        // Add minute permissions
        for (RolePermission permission : user.getRole().getPermissions()) {
            String mod = permission.getModuleName().toUpperCase();
            if (Boolean.TRUE.equals(permission.getCanView())) authorities.add(new SimpleGrantedAuthority(mod + ":VIEW"));
            if (Boolean.TRUE.equals(permission.getCanAdd())) authorities.add(new SimpleGrantedAuthority(mod + ":ADD"));
            // Module 16 P1 (owner decision A): no permission column maps to :SEND, so only a super admin could
            // send a broadcast by hand. Whoever may create a broadcast may send it ("Save & Send Now").
            if ("BROADCASTS".equals(mod) && Boolean.TRUE.equals(permission.getCanAdd())) authorities.add(new SimpleGrantedAuthority("BROADCASTS:SEND"));
            if (Boolean.TRUE.equals(permission.getCanEdit())) authorities.add(new SimpleGrantedAuthority(mod + ":EDIT"));
            if (Boolean.TRUE.equals(permission.getCanDelete())) authorities.add(new SimpleGrantedAuthority(mod + ":DELETE"));
            if (Boolean.TRUE.equals(permission.getCanExport())) authorities.add(new SimpleGrantedAuthority(mod + ":EXPORT"));
            if (Boolean.TRUE.equals(permission.getCanApprove())) authorities.add(new SimpleGrantedAuthority(mod + ":APPROVE"));
            if (Boolean.TRUE.equals(permission.getCanAssign())) authorities.add(new SimpleGrantedAuthority(mod + ":ASSIGN"));
            if (Boolean.TRUE.equals(permission.getCanPrint())) authorities.add(new SimpleGrantedAuthority(mod + ":PRINT"));
        }

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Boolean.TRUE.equals(user.getIsActive()) && (user.getOrganization() == null || Boolean.TRUE.equals(user.getOrganization().getIsActive())), 
                true,
                true,
                true,
                authorities
        );
    }
}
