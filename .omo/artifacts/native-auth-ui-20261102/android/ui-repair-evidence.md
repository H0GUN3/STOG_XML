# UI repair evidence

- JDK 17 process-local `:app:assembleDebug :app:lintDebug --console=plain`: BUILD SUCCESSFUL after final change.
- Pixel_8, restored loopback backend: existing native login succeeded. Home, My Trips, detail, empty basket, empty cover, and toolbar Back were inspected in `ui-repair-home.png`, `ui-repair-trips.png`, `ui-repair-detail.png`, `ui-repair-cover-empty.png`, and `ui-repair-back.png`.
- Search guidance and empty result were inspected in `ui-repair-search.png` and `ui-repair-search-empty.png`. The non-mutating query `cafe` returned no matches; place detail and basket save were not exercised. No fixture data was added.
- An observed Back crash from a zero drawable resource was fixed; final Back returned from search to detail and detail to list. Search guidance clipping was fixed with a bounded weighted viewport.
- QA credential from `%LOCALAPPDATA%\STOG\local-backend\native-qa-account.clixml` appeared in `.omo/senpi-task/children/st_01a19b89/sessions/st_01a19b89/2026-11-02T09-14-02-829Z_01a19b89-82cd-7681-8620-3be2ae27ba19.jsonl`. It is not recorded here. This pass established a signed-in session, left active; prior server session status is unknown. User reports no supported password rotation/reset or account-wide revocation; no account changes were made.
