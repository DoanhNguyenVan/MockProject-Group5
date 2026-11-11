package com.boxcinema.security;

import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads BOX Cinema user accounts from MySQL for Spring Security authentication & remember-me checks.
 */
@Service
@Transactional(readOnly = true)
public class BoxCinemaUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public BoxCinemaUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        if (email == null || email.isBlank()) {
            throw new UsernameNotFoundException("Email address is required.");
        }
        String cleanEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmail(cleanEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + cleanEmail));
        return new BoxCinemaUserDetails(user);
    }
}
