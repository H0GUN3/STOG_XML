# R2a final screenshot inspection

All final screenshots are Pixel_8 API 36 from the passing strict real-local run at font scale 1.30. No invitation URI/token, provider credential, raw user UUID, email, or real-user data is rendered.

| File | Inspection |
| --- | --- |
| `01-admin-detail-portrait-light.png` | ADMIN detail, shared member count, portrait/light |
| `02-two-member-management-largefont.png` | actual backend member screen; actionable generic label/role row, no UUID/capability |
| `02b-two-member-management-largefont-landscape.png` | same actual member surface after landscape recreation at font 1.30 |
| `03-delete-confirmation-no-capability.png` | destructive confirmation without capability disclosure |
| `04-admin-detail-landscape.png` | detail landscape/recreation |
| `05-admin-detail-dark.png` | detail dark mode |

Focused fixture screenshots under `member-row-regression/` independently render both row positions at font 1.00 and 1.30; the strict real-backend test asserts adapter count 2 and an attached usable row.

The accepted final reachability proof is under `member-control-proof/`: 16 direct screenshots cover portrait/landscape, `나`/`민지`, role/removal controls and their exact dialogs at font 1.30. The test reached each control with real swipe gestures, asserted at least 90% global visibility, tapped through Espresso, observed the dialog, then canceled. `contact-sheet.png` was visually inspected and `SHA256SUMS.txt` binds the files. No UUID, invitation capability, provider credential, account token or real-user data is visible. Prior failed attempts remain in `../logs/member-control-proof-failed-attempts.md`, `../logs/member-control-landscape-final.log`, and `../logs/member-control-targeted-font-propagation-failure.log`.
