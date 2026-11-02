package com.stog.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class TripPlanEventPublisher {
    private static final Logger logger = LoggerFactory.getLogger(TripPlanEventPublisher.class);
    private final ApplicationEventPublisher events;

    public TripPlanEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publishAfterCommit(TripPlanChangedEvent event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException exception) {
            logger.warn("Post-commit trip-plan notification failed for trip {} at version {}",
                event.tripId(), event.planVersion(), exception);
        }
    }
}
