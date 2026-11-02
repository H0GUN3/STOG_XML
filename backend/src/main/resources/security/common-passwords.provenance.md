# Native password blocklist provenance

- Policy id: `stog-native-password-blocklist-v2`
- Upstream project: `danielmiessler/SecLists`
- Upstream file:
  `Passwords/Common-Credentials/10k-most-common.txt`
- Pinned commit:
  `913b327317496d062bcc7cace524aaad8a693be2`
- Commit date: `2026-09-08`
- Raw source:
  `https://raw.githubusercontent.com/danielmiessler/SecLists/913b327317496d062bcc7cace524aaad8a693be2/Passwords/Common-Credentials/10k-most-common.txt`
- License: MIT, copyright Daniel Miessler (2018); bundled copy:
  `common-passwords.LICENSE.txt`
- Original UTF-8 bytes: `73026`
- Original line count: `10001`
- Original SHA-256:
  `68782d6a4a19a4768d5f15dd66bd534e7a33055cc755411e33f16d18c50fdcce`
- Repository representation: exact original bytes, gzip-compressed and Base64
  wrapped in `common-passwords.txt.gz.b64`
- Runtime network/API dependency: none

Startup decodes and decompresses the repository resource, verifies the pinned
SHA-256 and exactly 10,001 unique NFC/lowercase entries, and fails closed on any
missing, malformed, truncated or substituted data.
