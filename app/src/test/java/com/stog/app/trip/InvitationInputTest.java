package com.stog.app.trip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class InvitationInputTest {
    private static final String TOKEN = "n4mYxW5u4nS5JH1xQjQfn0zhvCq8kG9GqfdrkZ5ve08";

    @Test public void acceptsCanonicalRawTokenAndOnlyTheExactCustomUri() {
        assertEquals(TOKEN, InvitationInput.parse(TOKEN));
        assertEquals(TOKEN, InvitationInput.parse("stog://trip-invitations/" + TOKEN));
        assertNull(InvitationInput.parse("stog://trip-invitations/" + TOKEN + "?forged=1"));
        assertNull(InvitationInput.parse("https://trip-invitations/" + TOKEN));
        assertNull(InvitationInput.parse(TOKEN + "="));
    }
}
