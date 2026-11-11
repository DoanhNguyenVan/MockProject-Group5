package com.boxcinema.security;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security UserDetails wrapper bridging BOX Cinema UserSummaryDto
 * with Role-based GrantedAuthority checks.
 */
public class BoxCinemaUserDetails implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UserSummaryDto userSummary;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public BoxCinemaUserDetails(UserSummaryDto userSummary) {
        this(userSummary, "", true, true);
    }

    public BoxCinemaUserDetails(User user) {
        this(
                new UserSummaryDto(
                        user.getUserId(),
                        user.getFullName(),
                        user.getEmail(),
                        user.getPhone(),
                        user.getRole()
                ),
                user.getPasswordHash() != null ? user.getPasswordHash() : "",
                "ACTIVE".equalsIgnoreCase(user.getAccountStatus()),
                !"LOCKED".equalsIgnoreCase(user.getAccountStatus())
                        && !"SUSPENDED".equalsIgnoreCase(user.getAccountStatus())
        );
    }

    public BoxCinemaUserDetails(UserSummaryDto userSummary,
                                String passwordHash,
                                boolean enabled,
                                boolean accountNonLocked) {
        this.userSummary = userSummary;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    public UserSummaryDto getUserSummary() {
        return userSummary;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String role = (userSummary != null && userSummary.getRole() != null && !userSummary.getRole().isBlank())
                ? userSummary.getRole().trim().toUpperCase()
                : "CUSTOMER";
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return List.of(new SimpleGrantedAuthority(authority));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return userSummary != null ? userSummary.getEmail() : "";
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

