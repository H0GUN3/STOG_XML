package com.stog.app.trip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public final class TripFlowStateTest {
    @Test public void normalizedIntentReusesRequestIdUntilInputChangesOrAuthEnds() {
        TripFlowState state = new TripFlowState();
        state.updateDraft("  Je\u0301ju trip  ", "  Jeju ", "2026-10-10", "2026-10-12");

        TripFlowState.Pending first = state.pendingForSubmit();
        state.updateDraft("J\u00e9ju trip", "Jeju", "2026-10-10", "2026-10-12");
        assertEquals(first.requestId(), state.pendingForSubmit().requestId());

        state.updateDraft("Busan", "Jeju", "2026-10-10", "2026-10-12");
        assertNotEquals(first.requestId(), state.pendingForSubmit().requestId());
        state.authEnded();
        assertEquals("", state.draft().name());
        assertEquals(1L, state.generation());
    }

    @Test public void savedSmallStateRestoresDraftAndPendingIdAndRejectsImpossibleOrReversedDates() {
        TripFlowState state = new TripFlowState();
        state.updateDraft("제주", "제주", "2026-02-28", "2026-03-01");
        TripFlowState.Pending pending = state.pendingForSubmit();
        TripFlowState restored = new TripFlowState();
        restored.restore(state.save());
        assertEquals(pending.requestId(), restored.pendingForSubmit().requestId());
        assertFalse(TripViewModel.valid(new TripFlowState.Draft("제주", "제주", "2026-02-30", "2026-03-01")));
        assertFalse(TripViewModel.valid(new TripFlowState.Draft("제주", "제주", "2026-03-02", "2026-03-01")));
    }
}
