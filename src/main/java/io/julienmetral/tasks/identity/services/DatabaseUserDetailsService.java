package io.julienmetral.tasks.identity.services;

import io.julienmetral.tasks.identity.repositories.UserRepository;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(
            UserRepository userRepository
    ) {
        this.userRepository = userRepository;
    }

    @Override
    @NullMarked
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        var user = userRepository
                .findByEmailIgnoreCase(email)
                .orElseThrow(
                        () -> new UsernameNotFoundException(
                                "User not found"
                        )
                );

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(
                        user.getRoles()
                                .stream()
                                .map(role ->
                                        new SimpleGrantedAuthority(
                                                "ROLE_" + role.name()
                                        )
                                )
                                .toList()
                )
                .build();
    }

    /**
     * Called by {@code DaoAuthenticationProvider} after a successful login when the stored hash uses an outdated
     * algorithm or parameters, with the password already re-encoded by the current encoder.
     */
    @Override
    @Transactional
    @NullMarked
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        userRepository
                .findByEmailIgnoreCase(user.getUsername())
                .orElseThrow(
                        () -> new UsernameNotFoundException(
                                "User not found"
                        )
                )
                .setPasswordHash(newPassword);

        return org.springframework.security.core.userdetails.User
                .withUserDetails(user)
                .password(newPassword)
                .build();
    }
}
