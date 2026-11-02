package com.stog.backend;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class KakaoTokenVerifier {
    static final URI OFFICIAL_TOKEN_INFO_URI = URI.create("https://kapi.kakao.com/v1/user/access_token_info");
    static final URI OFFICIAL_USER_INFO_URI = URI.create("https://kapi.kakao.com/v2/user/me");

    private final ObjectMapper json;
    private final HttpClient client;
    private final boolean enabled;
    private final String expectedAppId;
    private final boolean fixtureMode;
    private final URI tokenInfoUri;
    private final URI userInfoUri;

    KakaoTokenVerifier(ObjectMapper json,
            @Value("${stog.auth.kakao.enabled:false}") boolean enabled,
            @Value("${stog.auth.kakao.expected-app-id:}") String expectedAppId,
            @Value("${stog.auth.kakao.fixture-mode:false}") boolean fixtureMode,
            @Value("${stog.auth.kakao.token-info-uri:https://kapi.kakao.com/v1/user/access_token_info}") URI tokenInfoUri,
            @Value("${stog.auth.kakao.user-info-uri:https://kapi.kakao.com/v2/user/me}") URI userInfoUri) {
        this.json = json;
        this.enabled = enabled;
        this.expectedAppId = expectedAppId;
        this.fixtureMode = fixtureMode;
        this.tokenInfoUri = tokenInfoUri;
        this.userInfoUri = userInfoUri;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    VerifiedIdentity verify(String token) {
        if (!enabled || !isCanonicalPositiveDecimal(expectedAppId)
                || !areTrustedDestinations(fixtureMode, tokenInfoUri, userInfoUri)) {
            throw AuthException.unavailable();
        }
        ProviderResponse tokenInfo = get(tokenInfoUri, token);
        if (tokenInfo.status() == 401 || tokenInfo.status() == 403) throw AuthException.required();
        if (tokenInfo.status() != 200) throw AuthException.unavailable();

        JsonNode info = parse(tokenInfo.body());
        String subject = canonicalInteger(info.get("id"));
        String appId = canonicalInteger(info.get("app_id"));
        long expiresIn = positiveLong(info.get("expires_in"));
        if (!expectedAppId.equals(appId)) throw AuthException.required();

        ProviderResponse userInfo = get(userInfoUri, token);
        if (userInfo.status() == 401 || userInfo.status() == 403) throw AuthException.required();
        if (userInfo.status() != 200) throw AuthException.unavailable();
        String userSubject = canonicalInteger(parse(userInfo.body()).get("id"));
        if (!subject.equals(userSubject)) throw AuthException.required();
        return new VerifiedIdentity(subject, expiresIn);
    }

    private ProviderResponse get(URI uri, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new ProviderResponse(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw AuthException.unavailable();
        } catch (IOException | IllegalArgumentException exception) {
            throw AuthException.unavailable();
        }
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) throw AuthException.unavailable();
            return node;
        } catch (AuthException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw AuthException.unavailable();
        }
    }

    private static long positiveLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
            throw AuthException.unavailable();
        }
        return node.asLong();
    }

    private static String canonicalInteger(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) throw AuthException.unavailable();
        String value = node.asText();
        if (!isCanonicalPositiveDecimal(value)) throw AuthException.unavailable();
        return value;
    }

    static boolean areTrustedDestinations(boolean fixtureMode, URI tokenInfoUri, URI userInfoUri) {
        if (!fixtureMode) {
            return exactUri(tokenInfoUri, OFFICIAL_TOKEN_INFO_URI) && exactUri(userInfoUri, OFFICIAL_USER_INFO_URI);
        }
        return isLiteralLoopbackFixtureUri(tokenInfoUri) && isLiteralLoopbackFixtureUri(userInfoUri);
    }

    private static boolean exactUri(URI actual, URI expected) {
        return actual != null && actual.toASCIIString().equals(expected.toASCIIString());
    }

    private static boolean isLiteralLoopbackFixtureUri(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
            return false;
        }
        String authority = uri.getRawAuthority();
        return authority != null && (authority.equals("127.0.0.1:" + uri.getPort())
            || authority.equals("[::1]:" + uri.getPort()));
    }

    private static boolean isCanonicalPositiveDecimal(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,31}")) return false;
        try { return new BigInteger(value).signum() > 0; }
        catch (NumberFormatException exception) { return false; }
    }

    record VerifiedIdentity(String subject, long credentialExpiresIn) {}
    private record ProviderResponse(int status, String body) {}
}
