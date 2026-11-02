package com.stog.backend;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class OpaqueAccessTokenFilter extends OncePerRequestFilter {
    private final AuthService service;

    OpaqueAccessTokenFilter(AuthService service) {
        this.service = service;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && authorization.length() <= 4103) {
            String token = authorization.substring(7);
            if (!token.isEmpty() && token.indexOf(' ') < 0) {
                try {
                    AuthenticatedUser user = service.authenticateAccess(token);
                    if (user != null) {
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(user, null, List.of()));
                    }
                } catch (DataAccessException exception) {
                    response.setStatus(503);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setHeader("Cache-Control", "no-store");
                    response.setHeader("Pragma", "no-cache");
                    response.getWriter().write("{\"error\":{\"code\":\"DATABASE_OPERATION_FAILED\",\"message\":\"The request could not be completed\"}}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
