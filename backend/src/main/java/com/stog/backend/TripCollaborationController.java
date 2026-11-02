package com.stog.backend;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TripCollaborationController {
    private final TripCollaborationService service;

    public TripCollaborationController(TripCollaborationService service) {
        this.service = service;
    }

    @PutMapping(value = "/trips/{tripId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TripResponse> updateTrip(@PathVariable String tripId, @Valid @RequestBody UpdateTripRequest request,
            Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        String name = TripProductController.normalizedText(request.name());
        String region = TripProductController.normalizedText(request.regionLabel());
        LocalDate start = TripProductController.date(request.startDate());
        LocalDate end = TripProductController.date(request.endDate());
        if (start.isAfter(end)) throw invalid();
        long version = version(request.expectedPlanVersion());
        TripCollaborationService.TripView result = service.updateTrip(principal,
            TripProductController.canonicalUuid(tripId), name, region, start, end, version,
            TripProductController.canonicalUuid(request.requestId()));
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff").body(tripResponse(result));
    }

    @GetMapping("/trips/{tripId}/members")
    ResponseEntity<MemberListResponse> members(@PathVariable String tripId, Authentication authentication,
            HttpServletRequest request) {
        UUID principal = principal(authentication);
        UUID trip = TripProductController.canonicalUuid(tripId);
        Map<String, String[]> query = requireQueries(request, Set.of("limit", "cursor"));
        String cursor = optional(query, "cursor");
        if (cursor != null && cursor.isEmpty()) throw invalidCursor();
        int limit = parseLimit(optional(query, "limit"), cursor != null);
        TripCollaborationService.MemberPage page = service.members(principal, trip, limit, cursor);
        return ResponseEntity.ok(new MemberListResponse(page.tripId(), page.planVersion(), page.memberCount(),
            page.items().stream().map(item -> new MemberResponse(item.memberUserId(), item.role(),
                item.memberLabel(), item.self())).toList(), page.nextCursor()));
    }

    @PutMapping(value = "/trips/{tripId}/members/{memberUserId}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RoleResponse> updateRole(@PathVariable String tripId, @PathVariable String memberUserId,
            @Valid @RequestBody RoleRequest request, Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        if (!"ADMIN".equals(request.role()) && !"MEMBER".equals(request.role())) throw invalid();
        TripCollaborationService.RoleResult result = service.updateRole(principal,
            TripProductController.canonicalUuid(tripId), TripProductController.canonicalUuid(memberUserId),
            request.role(), version(request.expectedPlanVersion()),
            TripProductController.canonicalUuid(request.requestId()));
        return ResponseEntity.ok(new RoleResponse(result.result(), result.memberUserId(), result.role(), result.planVersion()));
    }

    @DeleteMapping(value = "/trips/{tripId}/members/{memberUserId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> removeMember(@PathVariable String tripId, @PathVariable String memberUserId,
            @Valid @RequestBody VersionRequest request, Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        service.removeMember(principal, TripProductController.canonicalUuid(tripId),
            TripProductController.canonicalUuid(memberUserId), version(request.expectedPlanVersion()),
            TripProductController.canonicalUuid(request.requestId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/trips/{tripId}/leave", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> leave(@PathVariable String tripId, @Valid @RequestBody VersionRequest request,
            Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        service.leave(principal, TripProductController.canonicalUuid(tripId), version(request.expectedPlanVersion()),
            TripProductController.canonicalUuid(request.requestId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/trips/{tripId}/invitation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<InviteResponse> issue(@PathVariable String tripId, @Valid @RequestBody IssueRequest request,
            Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        String token = issueToken(request.invitationToken());
        TripCollaborationService.InviteResult result = service.issue(principal,
            TripProductController.canonicalUuid(tripId), TripCollaborationService.tokenHash(token), token,
            TripProductController.canonicalUuid(request.requestId()));
        InviteResponse response = new InviteResponse(result.result(), result.inviteUri(), result.expiresAt().toInstant());
        return ResponseEntity.status(result.created() ? 201 : 200).body(response);
    }

    @DeleteMapping(value = "/trips/{tripId}/invitation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SimpleResponse> revoke(@PathVariable String tripId, @Valid @RequestBody RequestOnly request,
            Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        TripCollaborationService.SimpleResult result = service.revoke(principal,
            TripProductController.canonicalUuid(tripId), TripProductController.canonicalUuid(request.requestId()));
        return ResponseEntity.ok(new SimpleResponse(result.result()));
    }

    @PostMapping(value = "/trip-invitations/join", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JoinResponse> join(@Valid @RequestBody JoinRequest request, Authentication authentication,
            HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        UUID requestId = TripProductController.canonicalUuid(request.requestId());
        String label = memberLabel(request.memberLabel());
        String token = joinToken(request.invitationToken());
        TripCollaborationService.JoinResult result = service.join(principal,
            TripCollaborationService.tokenHash(token), label, requestId);
        JoinResponse response = new JoinResponse(result.result(), result.tripId(), result.role(),
            result.memberLabel(), result.planVersion());
        return ResponseEntity.status(result.joined() ? 201 : 200).body(response);
    }

    @DeleteMapping(value = "/trips/{tripId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> deleteTrip(@PathVariable String tripId, @Valid @RequestBody VersionRequest request,
            Authentication authentication, HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        service.deleteTrip(principal, TripProductController.canonicalUuid(tripId),
            version(request.expectedPlanVersion()), TripProductController.canonicalUuid(request.requestId()));
        return ResponseEntity.noContent().build();
    }

    private static UUID principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new TripCollaborationException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        return user.userId();
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw invalid();
        return value;
    }

    private static String memberLabel(String raw) {
        String value = normalize(raw);
        long length = value.codePoints().count();
        if (length < 1 || length > 40 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private static String normalize(String raw) {
        if (raw == null) throw invalid();
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        int start = 0, end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!(Character.isWhitespace(cp) || Character.isSpaceChar(cp))) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!(Character.isWhitespace(cp) || Character.isSpaceChar(cp))) break;
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    private static String issueToken(String token) {
        if (!canonicalToken(token)) throw invalid();
        return token;
    }

    private static String joinToken(String token) {
        if (!canonicalToken(token)) throw new TripCollaborationException(404,
            "INVITATION_NOT_FOUND_OR_NOT_USABLE", "The invitation was not found or is not usable");
        return token;
    }

    private static boolean canonicalToken(String token) {
        if (token == null || token.length() != 43 || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            return bytes.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Map<String, String[]> requireQueries(HttpServletRequest request, Set<String> allowed) {
        Map<String, String[]> parameters = request.getParameterMap();
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            if (!allowed.contains(parameter.getKey()) || parameter.getValue().length != 1) throw invalid();
        }
        return parameters;
    }

    private static String optional(Map<String, String[]> query, String key) {
        String[] values = query.get(key);
        return values == null ? null : values[0];
    }

    private static int parseLimit(String value, boolean cursorPresent) {
        if (value == null) return cursorPresent ? -1 : 20;
        if (!value.matches("[0-9]+") || value.length() > 2) throw invalid();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 50) throw invalid();
            return parsed;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private static TripResponse tripResponse(TripCollaborationService.TripView value) {
        return new TripResponse(value.tripId(), value.name(), value.regionLabel(), value.startDate(), value.endDate(),
            value.status(), value.asOfDate(), value.role(), value.memberCount(), value.planVersion(), value.cover());
    }
    private static TripCollaborationException invalid() { return new TripCollaborationException(400,
        "INVALID_REQUEST", "The request is invalid"); }
    private static TripCollaborationException invalidCursor() { return new TripCollaborationException(400,
        "INVALID_CURSOR", "The cursor is invalid"); }

    record UpdateTripRequest(@NotNull String name, @JsonProperty("region_label") @NotNull String regionLabel,
        @JsonProperty("start_date") @NotNull String startDate, @JsonProperty("end_date") @NotNull String endDate,
        @JsonProperty("expected_plan_version") @NotNull Long expectedPlanVersion,
        @JsonProperty("request_id") @NotNull String requestId) {}
    record RoleRequest(@NotNull String role,
        @JsonProperty("expected_plan_version") @NotNull Long expectedPlanVersion,
        @JsonProperty("request_id") @NotNull String requestId) {}
    record VersionRequest(@JsonProperty("expected_plan_version") @NotNull Long expectedPlanVersion,
        @JsonProperty("request_id") @NotNull String requestId) {}
    record IssueRequest(@JsonProperty("invitation_token") @NotNull String invitationToken,
        @JsonProperty("request_id") @NotNull String requestId) {}
    record RequestOnly(@JsonProperty("request_id") @NotNull String requestId) {}
    record JoinRequest(@JsonProperty("invitation_token") @NotNull String invitationToken,
        @JsonProperty("member_label") @NotNull String memberLabel,
        @JsonProperty("request_id") @NotNull String requestId) {}

    record TripResponse(@JsonProperty("trip_id") UUID tripId, String name,
        @JsonProperty("region_label") String regionLabel, @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate, String status, @JsonProperty("as_of_date") LocalDate asOfDate,
        String role, @JsonProperty("member_count") long memberCount,
        @JsonProperty("plan_version") long planVersion, TripCoverService.CoverView cover) {}
    record MemberResponse(@JsonProperty("member_user_id") UUID memberUserId, String role,
        @JsonProperty("member_label") String memberLabel, @JsonProperty("is_self") boolean self) {}
    record MemberListResponse(@JsonProperty("trip_id") UUID tripId,
        @JsonProperty("plan_version") long planVersion, @JsonProperty("member_count") long memberCount,
        List<MemberResponse> items, @JsonProperty("next_cursor") String nextCursor) {}
    record RoleResponse(String result, @JsonProperty("member_user_id") UUID memberUserId, String role,
        @JsonProperty("plan_version") long planVersion) {}
    record InviteResponse(String result, @JsonProperty("invite_uri") String inviteUri,
        @JsonProperty("expires_at") java.time.Instant expiresAt) {}
    record SimpleResponse(String result) {}
    record JoinResponse(String result, @JsonProperty("trip_id") UUID tripId, String role,
        @JsonProperty("member_label") String memberLabel, @JsonProperty("plan_version") long planVersion) {}
}
