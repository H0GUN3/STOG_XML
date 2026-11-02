package com.stog.backend;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TripCursorCodecTest {
    private static final Instant ISSUED = Instant.parse("2026-10-01T00:00:00Z");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TRIP = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");

    @Test
    void typedCursorRoundTripsOnlyForBoundPrincipalEndpointTripStateAndLimit() {
        TripCursorCodec codec = codecAt(ISSUED);
        String trip = codec.encodeTrip(USER, 20, LocalDate.of(2026, 10, 1), 1,
            LocalDate.of(2026, 10, 10), TRIP);
        var decoded = codec.decodeTrip(trip, USER, 20);
        assertEquals(LocalDate.of(2026, 10, 1), decoded.asOfDate());
        assertEquals(1, decoded.bucket());
        assertInvalid(() -> codec.decodeTrip(trip, OTHER, 20));
        assertInvalid(() -> codec.decodeTrip(trip, USER, 10));
        assertInvalid(() -> codec.decodeBasket(trip, USER, 20, TRIP));

        String basket = codec.encodeBasket(USER, 5, TRIP, 7, Instant.parse("2026-10-01T03:04:05.123456Z"), ITEM);
        var basketDecoded = codec.decodeBasket(basket, USER, 5, TRIP);
        assertEquals(7, basketDecoded.planVersion());
        assertEquals(Instant.parse("2026-10-01T03:04:05.123456Z"), basketDecoded.createdAt());
        assertInvalid(() -> codec.decodeBasket(basket, USER, 5, OTHER));
    }

    @Test
    void tamperedMalformedOversizeRestartAndExpiredCursorsAreInvalid() {
        TripCursorCodec issued = codecAt(ISSUED);
        String token = issued.encodeTrip(USER, 20, LocalDate.of(2026, 10, 1), 0,
            LocalDate.of(2026, 10, 2), TRIP);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        assertInvalid(() -> issued.decodeTrip(tampered, USER, 20));
        assertInvalid(() -> issued.decodeTrip("%%%", USER, 20));
        assertInvalid(() -> issued.decodeTrip("a".repeat(1025), USER, 20));
        byte[] restartedBytes = new byte[32];
        restartedBytes[0] = 1;
        TripCursorCodec restarted = new TripCursorCodec(new SecretKeySpec(restartedBytes, "AES"),
            new SecureRandom(), Clock.fixed(ISSUED.plusSeconds(1), ZoneOffset.UTC));
        assertInvalid(() -> restarted.decodeTrip(token, USER, 20));
        TripCursorCodec expired = new TripCursorCodec(key, new SecureRandom(),
            Clock.fixed(ISSUED.plus(Duration.ofMinutes(15)), ZoneOffset.UTC));
        assertInvalid(() -> expired.decodeTrip(token, USER, 20));
    }

    private TripCursorCodec codecAt(Instant instant) {
        return new TripCursorCodec(key, new SecureRandom(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static void assertInvalid(Runnable action) {
        TripProductException error = assertThrows(TripProductException.class, action::run);
        assertEquals(400, error.status());
        assertEquals("INVALID_CURSOR", error.code());
    }
}
