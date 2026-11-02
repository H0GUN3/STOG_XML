package com.stog.backend;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration
class PasswordSecurityConfig {
    static final String ENCODER_ID = "pbkdf2-stog-v1";

    @Bean
    PasswordEncoder passwordEncoder() {
        Pbkdf2PasswordEncoder pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 600_000, 256);
        pbkdf2.setAlgorithm(Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder(ENCODER_ID, Map.of(ENCODER_ID, pbkdf2));
    }
}
