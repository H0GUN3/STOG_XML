package com.stog.backend;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RestrictedOwnerConfigurationTest {
    private static final String CONFIGURATION_ERROR =
        "STOG_AUTH_RESTRICTED_OWNER_USER_ID must be a canonical UUID when restricted-owner mode is enabled";

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(KakaoTokenVerifier.class, () -> mock(KakaoTokenVerifier.class))
        .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
        .withBean(PasswordBlocklist.class, () -> mock(PasswordBlocklist.class))
        .withUserConfiguration(AuthService.class);

    @Test
    void enabledModeWithoutOwnerFailsApplicationContextClosed() {
        contexts.withPropertyValues("stog.auth.restricted-owner.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(CONFIGURATION_ERROR);
            });
    }

    @Test
    void enabledModeWithMalformedOwnerFailsWithoutDisclosingConfiguredValue() {
        String sentinel = "sentinel-not-a-uuid";
        contexts.withPropertyValues(
                "stog.auth.restricted-owner.enabled=true",
                "stog.auth.restricted-owner.user-id=" + sentinel)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(CONFIGURATION_ERROR);
                assertThat(stackTrace(context.getStartupFailure())).doesNotContain(sentinel);
            });
    }

    @Test
    void enabledModeRejectsShortenedNoncanonicalUuid() {
        String shortened = "1-1-1-1-1";
        contexts.withPropertyValues(
                "stog.auth.restricted-owner.enabled=true",
                "stog.auth.restricted-owner.user-id=" + shortened)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(CONFIGURATION_ERROR);
                assertThat(stackTrace(context.getStartupFailure())).doesNotContain(shortened);
            });
    }

    @Test
    void enabledModeAcceptsUppercaseCanonicalUuid() {
        String uppercaseCanonical = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        contexts.withPropertyValues(
                "stog.auth.restricted-owner.enabled=true",
                "stog.auth.restricted-owner.user-id=" + uppercaseCanonical)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AuthService.class);
            });
    }

    @Test
    void disabledModeDoesNotRequireOwnerConfiguration() {
        contexts.withPropertyValues("stog.auth.restricted-owner.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(AuthService.class);
            });
    }

    private static String stackTrace(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}
