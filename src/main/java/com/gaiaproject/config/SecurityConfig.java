package com.example.gaiaproject.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Swagger/OpenAPI는 인증 없이 접근 허용
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 헬스 체크 같은 것도 열고 싶으면 여기에 추가
                        .requestMatchers("/health").permitAll()
                        .anyRequest().authenticated()
                )
                // 일단은 가장 단순하게 Basic Auth로 잠가두자 (JWT 전 단계)
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
