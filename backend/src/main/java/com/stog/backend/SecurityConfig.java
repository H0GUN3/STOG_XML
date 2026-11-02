package com.stog.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<AuthService> authService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/kakao/exchange",
                    "/api/auth/refresh", "/api/auth/logout").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint()))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        AuthService service = authService.getIfAvailable();
        http.addFilterBefore(new AuthRequestSizeFilter(), AnonymousAuthenticationFilter.class);
        if (service != null) http.addFilterBefore(new OpaqueAccessTokenFilter(service), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required\"}}");
        };
    }

}
