package com.stog.app.trip;

import java.text.Normalizer;
import java.util.UUID;

/** Small, credential-free state kept by the authenticated trip surface. */
public final class TripFlowState {
    public record Draft(String name, String regionLabel, String startDate, String endDate) {}
    public record Pending(Draft normalizedDraft, String requestId) {}
    public record Saved(Draft draft, Pending pending) {}

    private Draft draft = new Draft("", "", "", "");
    private Pending pending;
    private long generation;

    public void updateDraft(String name, String regionLabel, String startDate, String endDate) {
        Draft next = new Draft(value(name), value(regionLabel), value(startDate), value(endDate));
        if (pending != null && !pending.normalizedDraft().equals(normalize(next))) pending = null;
        draft = next;
    }

    public Draft draft() { return draft; }
    public Pending pendingForSubmit() {
        Draft normalized = normalize(draft);
        if (pending == null) pending = new Pending(normalized, UUID.randomUUID().toString());
        return pending;
    }
    public void clearAfterDefinitiveEnd() { pending = null; }
    public void authEnded() { generation++; pending = null; draft = new Draft("", "", "", ""); }
    public long generation() { return generation; }
    public Saved save() { return new Saved(draft, pending); }
    public void restore(Saved saved) {
        if (saved == null) return;
        draft = saved.draft() == null ? new Draft("", "", "", "") : saved.draft();
        pending = saved.pending();
    }

    static Draft normalize(Draft draft) {
        return new Draft(normalizeText(draft.name()), normalizeText(draft.regionLabel()),
            value(draft.startDate()).trim(), value(draft.endDate()).trim());
    }

    private static String normalizeText(String value) {
        return Normalizer.normalize(value(value), Normalizer.Form.NFC).strip();
    }
    private static String value(String value) { return value == null ? "" : value; }
}
