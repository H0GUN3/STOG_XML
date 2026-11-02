package com.stog.app.auth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TripItemReadStateTest {
    @Test public void canonicalUuidPairIsAcceptedAndPreserved() {
        TripItemReadState.Input input = TripItemReadState.Input.parse(
            "940e857d-b025-4f72-b420-2ee354a514ad",
            "bc3a32fb-230c-4f43-83ca-ce1903eb1545");

        assertTrue(input.valid());
        assertEquals("940e857d-b025-4f72-b420-2ee354a514ad", input.tripId());
        assertEquals("bc3a32fb-230c-4f43-83ca-ce1903eb1545", input.tripItemId());
    }

    @Test public void blankMalformedAndNoncanonicalUuidPairsAreRejected() {
        assertFalse(TripItemReadState.Input.parse("", "bc3a32fb-230c-4f43-83ca-ce1903eb1545").valid());
        assertFalse(TripItemReadState.Input.parse("not-a-uuid", "bc3a32fb-230c-4f43-83ca-ce1903eb1545").valid());
        assertFalse(TripItemReadState.Input.parse("940E857D-B025-4F72-B420-2EE354A514AD",
            "bc3a32fb-230c-4f43-83ca-ce1903eb1545").valid());
    }
}
