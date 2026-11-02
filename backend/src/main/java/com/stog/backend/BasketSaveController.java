package com.stog.backend;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips/{tripId}/trip-items")
public class BasketSaveController {
    private final BasketSaveService service;

    public BasketSaveController(BasketSaveService service) {
        this.service = service;
    }

    @GetMapping("/{tripItemId}")
    ResponseEntity<CurrentItemResponse> current(@PathVariable String tripId, @PathVariable String tripItemId,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BasketSaveException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        BasketSaveService.CurrentItem item = service.currentItem(user.userId(), canonicalUuid(tripId),
            canonicalUuid(tripItemId));
        return ResponseEntity.ok(new CurrentItemResponse(item.tripItemId(), item.placeId(), item.state(),
            item.scheduleDate(), item.sortOrder(), item.planVersion()));
    }

    @PostMapping
    ResponseEntity<Response> save(@PathVariable String tripId, @Valid @RequestBody Request request,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BasketSaveException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        }
        UUID parsedTripId = canonicalUuid(tripId);
        UUID placeId = canonicalUuid(request.placeId());
        UUID requestId = canonicalUuid(request.requestId());
        BasketSaveService.Result result = service.save(user.userId(), parsedTripId, placeId,
            strictNonnegativeLong(request.expectedPlanVersion()), requestId);
        Response body = new Response(result.result(), result.tripItemId(), result.placeId(), result.planVersion());
        return ResponseEntity.status("CREATED".equals(result.result()) ? 201 : 200).body(body);
    }

    private static long strictNonnegativeLong(Object value) {
        long parsed;
        if (value instanceof Integer integer) {
            parsed = integer.longValue();
        } else if (value instanceof Long longValue) {
            parsed = longValue;
        } else if (value instanceof BigInteger bigInteger && bigInteger.bitLength() <= 63) {
            parsed = bigInteger.longValue();
        } else {
            throw new BasketSaveException(400, "INVALID_REQUEST", "The request is invalid");
        }
        if (parsed < 0) {
            throw new BasketSaveException(400, "INVALID_REQUEST", "The request is invalid");
        }
        return parsed;
    }

    private static UUID canonicalUuid(String value) {
        if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new BasketSaveException(400, "INVALID_REQUEST", "The request is invalid");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new BasketSaveException(400, "INVALID_REQUEST", "The request is invalid");
        }
    }

    record Request(
        @JsonProperty("place_id") @NotBlank String placeId,
        @JsonProperty("expected_plan_version") @NotNull Object expectedPlanVersion,
        @JsonProperty("request_id") @NotBlank String requestId) {}

    record Response(
        String result,
        @JsonProperty("trip_item_id") UUID tripItemId,
        @JsonProperty("place_id") UUID placeId,
        @JsonProperty("plan_version") long planVersion) {}

    record CurrentItemResponse(
        @JsonProperty("trip_item_id") UUID tripItemId,
        @JsonProperty("place_id") UUID placeId,
        String state,
        @JsonProperty("schedule_date") LocalDate scheduleDate,
        @JsonProperty("sort_order") Long sortOrder,
        @JsonProperty("plan_version") long planVersion) {}
}
