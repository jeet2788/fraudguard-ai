package com.fraud.controller;

import com.fraud.model.AppUser;
import com.fraud.repository.UserRepository;
import com.fraud.security.JwtAuthFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.*;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final UserDetailsService    userDetailsService;
    private final UserRepository        userRepo;
    private final PasswordEncoder       passwordEncoder;
    private final com.fraud.security.JwtUtil jwtUtil;   // package-private bean

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));

        var user  = userDetailsService.loadUserByUsername(req.getUsername());
        var token = jwtUtil.generate(user);
        var roles = user.getAuthorities().stream()
                        .map(a -> a.getAuthority()).toList();

        return ResponseEntity.ok(new LoginResponse(token, "Bearer", 86400L,
                                                    req.getUsername(), roles));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        if (userRepo.existsByUsername(req.getUsername()))
            return ResponseEntity.badRequest().body("Username already taken");
        if (userRepo.existsByEmail(req.getEmail()))
            return ResponseEntity.badRequest().body("Email already registered");

        AppUser user = AppUser.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .email(req.getEmail())
                .roles(req.getRoles() != null ? Set.copyOf(req.getRoles())
                                              : Set.of("ROLE_ANALYST"))
                .createdAt(Instant.now())
                .build();
        userRepo.save(user);
        return ResponseEntity.ok("User registered: " + user.getUsername());
    }

    // ── Inner DTOs ──────────────────────────────────────────────────────────
    @Data public static class LoginRequest {
        @NotBlank String username;
        @NotBlank String password;
    }

    @Data public static class RegisterRequest {
        @NotBlank @Size(min=3, max=32) String username;
        @NotBlank @Size(min=6)         String password;
        @Email @NotBlank               String email;
        List<String>                   roles;
    }

    record LoginResponse(String token, String tokenType, long expiresIn,
                         String username, List<String> roles) {}
}
