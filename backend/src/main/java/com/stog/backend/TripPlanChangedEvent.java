package com.stog.backend;

import java.util.List;
import java.util.UUID;

public record TripPlanChangedEvent(UUID tripId, long planVersion, String changedScope,
        List<UUID> recipientUserIds) {
    public TripPlanChangedEvent {
        recipientUserIds = List.copyOf(recipientUserIds);
    }

    public TripPlanChangedEvent(UUID tripId, long planVersion) {
        this(tripId, planVersion, "TRIP_ITEMS", List.of());
    }

    public TripPlanChangedEvent(UUID tripId, long planVersion, String changedScope) {
        this(tripId, planVersion, changedScope, List.of());
    }
}
