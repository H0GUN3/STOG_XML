package com.stog.app.auth;

import java.util.UUID;

public record TripItemReadState(Kind kind, AuthApiClient.TripItem item) {
    public enum Kind {
        IDLE,
        LOADING,
        CONTENT,
        INVALID_INPUT,
        TRIP_NOT_FOUND,
        ITEM_NOT_FOUND,
        RETRYABLE_ERROR,
        AUTH_ENDED
    }

    public static TripItemReadState of(Kind kind) { return new TripItemReadState(kind, null); }
    public static TripItemReadState content(AuthApiClient.TripItem item) {
        return new TripItemReadState(Kind.CONTENT, item);
    }

    public record Input(boolean valid, String tripId, String tripItemId) {
        public static Input parse(String tripId, String tripItemId) {
            String trip = tripId == null ? "" : tripId.trim();
            String item = tripItemId == null ? "" : tripItemId.trim();
            return new Input(canonical(trip) && canonical(item), trip, item);
        }

        private static boolean canonical(String value) {
            try {
                return !value.isEmpty() && UUID.fromString(value).toString().equals(value);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }
}
