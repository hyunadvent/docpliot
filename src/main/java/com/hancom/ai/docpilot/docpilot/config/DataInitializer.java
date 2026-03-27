package com.hancom.ai.docpilot.docpilot.config;

import com.hancom.ai.docpilot.docpilot.entity.UserEntity;
import com.hancom.ai.docpilot.docpilot.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername("admin")) {
            userRepository.save(UserEntity.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin"))
                    .email("admin@docpilot.local")
                    .name("Super Admin")
                    .role("ROLE_ADMIN")
                    .approved(true)
                    .createdAt(LocalDateTime.now())
                    .build());
            log.info("기본 관리자 계정 생성: admin/admin");
        }
    }
}
