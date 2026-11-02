# Final member-control proof attempts (BLOCKED)

All runs used an owned Pixel_8 API 36 emulator, font scale 1.30 for the new path, the local in-process HTTP fixture, and no backend/PostgreSQL/provider/public network. Experimental test changes were reverted after the third failed attempt.

## Attempt 1

- Portrait: the proof checked the owner control after the prior programmatic `assertRowAtPosition(1)` had left the RecyclerView attached to the second row; swiping the outer ScrollView could not reattach the owner row.
- Landscape: changing system rotation settings before launch did not produce a landscape Activity (`expected 2, was 1`).
- Harness cleanup had an offline-device reverse query fault after the emulator was killed.

## Attempt 2

- Portrait: relaxing the global-bounds equality did not fix the wrong scroll target; owner control remained unreachable.
- Landscape: subscribing to the old Activity's configuration callback timed out because orientation caused Activity recreation.
- Cleanup was corrected to avoid querying reverse state from an offline owned device.

## Attempt 3

- The proof removed programmatic row positioning and injected real swipe gestures inside the RecyclerView's visible global bounds.
- Portrait reached the owner role path, but Espresso rejected the owner removal click because the button's global visible area was below its 90% click constraint.
- The unchanged default screenshot test hit a stale MediaStore filename uniqueness fault from prior failed runs; this was a harness artifact, not product evidence.
- Landscape recreated, but the fixture list event did not arrive within the bounded wait, so no landscape control proof was accepted.
- Exact retained runner output: `member-control-targeted.log`; Android test XML: `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_8(AVD) - 16-_app-.xml`.

## Production correction criterion

With no programmatic RecyclerView positioning and no direct listener invocation, real swipes must make each role/removal button for `나` and `민지` at least 90% globally visible, then an Espresso click must open the exact confirmation dialog in font-1.30 portrait and landscape. The relevant layout boundary is the outer `fragment_main.xml` ScrollView containing the nested `fragment_trip_members.xml` wrap-content RecyclerView and vertically stacked controls from `item_trip_member.xml`.
