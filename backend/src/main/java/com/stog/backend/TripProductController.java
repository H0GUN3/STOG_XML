package com.stog.backend;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
public class TripProductController {
    private final TripProductService service;

    public TripProductController(TripProductService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TripResponse> create(@Valid @RequestBody CreateRequest request, Authentication authentication,
            HttpServletRequest servletRequest) {
        UUID principal = principal(authentication);
        requireQueries(servletRequest, Set.of());
        String name = normalizedText(request.name());
        String region = normalizedText(request.regionLabel());
        LocalDate start = date(request.startDate());
        LocalDate end = date(request.endDate());
        if (start.isAfter(end)) throw invalidRequest();
        UUID requestId = canonicalUuid(request.requestId());
        TripProductService.CreateResult result = service.create(principal, name, region, start, end, requestId);
        return privateJson(result.created() ? 201 : 200, response(result.trip()));
    }

    @GetMapping
    ResponseEntity<TripListResponse> trips(Authentication authentication, HttpServletRequest request) {
        UUID principal = principal(authentication);
        Map<String, String[]> query = requireQueries(request, Set.of("limit", "cursor"));
        String cursor = optional(query, "cursor");
        if (cursor != null && cursor.isEmpty()) throw invalidCursor();
        int limit = parseLimit(optional(query, "limit"), cursor != null);
        TripProductService.TripPage page = service.trips(principal, limit, cursor);
        return privateJson(200, new TripListResponse(page.asOfDate(), page.items().stream()
            .map(TripProductController::response).toList(), page.nextCursor()));
    }

    @GetMapping("/{tripId}")
    ResponseEntity<TripResponse> detail(@PathVariable String tripId, Authentication authentication,
            HttpServletRequest request) {
        UUID principal = principal(authentication);
        requireQueries(request, Set.of());
        return privateJson(200, response(service.detail(principal, canonicalUuid(tripId))));
    }

    @GetMapping("/{tripId}/trip-items")
    ResponseEntity<BasketResponse> basket(@PathVariable String tripId, Authentication authentication,
            HttpServletRequest request) {
        UUID principal = principal(authentication);
        UUID parsedTrip = canonicalUuid(tripId);
        Map<String, String[]> query = requireQueries(request, Set.of("state", "limit", "cursor"));
        if (!"BASKET".equals(required(query, "state"))) throw invalidRequest();
        String cursor = optional(query, "cursor");
        if (cursor != null && cursor.isEmpty()) throw invalidCursor();
        int limit = parseLimit(optional(query, "limit"), cursor != null);
        TripProductService.BasketPage page = service.basket(principal, parsedTrip, limit, cursor);
        List<BasketItemResponse> items = page.items().stream().map(item -> new BasketItemResponse(
            item.tripItemId(), item.placeId(), item.state(), item.scheduleDate(), item.sortOrder(), item.createdAt()))
            .toList();
        return ResponseEntity.ok(new BasketResponse(page.tripId(), page.planVersion(), items, page.nextCursor()));
    }

    private static UUID principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new TripProductException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        return user.userId();
    }

    private static Map<String, String[]> requireQueries(HttpServletRequest request, Set<String> allowed) {
        Map<String, String[]> parameters = request.getParameterMap();
        for (Map.Entry<String, String[]> parameter : parameters.entrySet()) {
            if (!allowed.contains(parameter.getKey()) || parameter.getValue().length != 1) throw invalidRequest();
        }
        return parameters;
    }

    private static String required(Map<String, String[]> query, String key) {
        String value = optional(query, key);
        if (value == null) throw invalidRequest();
        return value;
    }

    private static String optional(Map<String, String[]> query, String key) {
        String[] values = query.get(key);
        return values == null ? null : values[0];
    }

    private static int parseLimit(String value, boolean cursorPresent) {
        if (value == null) return cursorPresent ? -1 : 20;
        if (!value.matches("[0-9]+") || value.length() > 2) throw invalidRequest();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 50) throw invalidRequest();
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalidRequest();
        }
    }

    static String normalizedText(String raw) {
        if (raw == null) throw invalidRequest();
        String value = Normalizer.normalize(raw, Normalizer.Form.NFC);
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!unicodeWhitespace(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!unicodeWhitespace(cp)) break;
            end -= Character.charCount(cp);
        }
        value = value.substring(start, end);
        long count = value.codePoints().count();
        if (count < 1 || count > 100 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidRequest();
        }
        return value;
    }

    private static boolean unicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalidRequest();
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw invalidRequest();
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalidRequest();
        }
    }

    static UUID canonicalUuid(String value) {
        if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) throw invalidRequest();
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) throw invalidRequest();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalidRequest();
        }
    }

    private static TripResponse response(TripProductService.TripView trip) {
        return new TripResponse(trip.tripId(), trip.name(), trip.regionLabel(), trip.startDate(), trip.endDate(),
            trip.status(), trip.asOfDate(), trip.role(), trip.memberCount(), trip.planVersion(), trip.cover());
    }

    private static TripItemResponse response(TripProductService.TripItem trip) {
        return new TripItemResponse(trip.tripId(), trip.name(), trip.regionLabel(), trip.startDate(), trip.endDate(),
            trip.status(), trip.role(), trip.memberCount(), trip.planVersion(), trip.cover());
    }

    private static <T> ResponseEntity<T> privateJson(int status, T body) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff").body(body);
    }

    private static TripProductException invalidRequest() {
        return new TripProductException(400, "INVALID_REQUEST", "The request is invalid");
    }

    private static TripProductException invalidCursor() {
        return new TripProductException(400, "INVALID_CURSOR", "The cursor is invalid");
    }

    record CreateRequest(@NotNull String name, @JsonProperty("region_label") @NotNull String regionLabel,
        @JsonProperty("start_date") @NotNull String startDate,
        @JsonProperty("end_date") @NotNull String endDate,
        @JsonProperty("request_id") @NotNull String requestId) {}

    record TripResponse(@JsonProperty("trip_id") UUID tripId, String name,
        @JsonProperty("region_label") String regionLabel,
        @JsonProperty("start_date") LocalDate startDate, @JsonProperty("end_date") LocalDate endDate,
        String status, @JsonProperty("as_of_date") LocalDate asOfDate, String role,
        @JsonProperty("member_count") long memberCount, @JsonProperty("plan_version") long planVersion,
        TripCoverService.CoverView cover) {}

    record TripItemResponse(@JsonProperty("trip_id") UUID tripId, String name,
        @JsonProperty("region_label") String regionLabel,
        @JsonProperty("start_date") LocalDate startDate, @JsonProperty("end_date") LocalDate endDate,
        String status, String role, @JsonProperty("member_count") long memberCount,
        @JsonProperty("plan_version") long planVersion, TripCoverService.CoverView cover) {}

    record TripListResponse(@JsonProperty("as_of_date") LocalDate asOfDate, List<TripItemResponse> items,
        @JsonProperty("next_cursor") String nextCursor) {}

    record BasketItemResponse(@JsonProperty("trip_item_id") UUID tripItemId,
        @JsonProperty("place_id") UUID placeId, String state,
        @JsonProperty("schedule_date") LocalDate scheduleDate, @JsonProperty("sort_order") Long sortOrder,
        @JsonProperty("created_at") java.time.Instant createdAt) {}

    record BasketResponse(@JsonProperty("trip_id") UUID tripId,
        @JsonProperty("plan_version") long planVersion, List<BasketItemResponse> items,
        @JsonProperty("next_cursor") String nextCursor) {}
}
