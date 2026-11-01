# V2 visit flow (offline, same save as UI)

- records: sample village only; reloaded at end
- limit: registry reopen is file-exists + host codec tests; full decrypt-reopen is a manual check
- checks: 8/17 passed

| area | check | ok | ms | detail |
|---|---|---|---|---|
| setup | stores loaded | PASS | 0 | registry=true queue=true |
| ritu | save day 14 | FAIL | 0 | Visit needs review before completion |
| ritu | save again (idempotent) | FAIL | 0 | Visit needs review before completion |
| ritu | one form entry | PASS | 0 | entries=1 |
| ritu | mcp weight card | PASS | 0 | weights=3 |
| ritu | one diary entry | PASS | 0 | diary=1 |
| ritu | one visit record | FAIL | 0 | visits=2 |
| ritu | next due = day 21 | FAIL | 0 | next=2026-10-27 want=2026-11-11 |
| ritu | reopened from disk | PASS | 0 | disk=50 |
| ritu | form PDF renders | PASS | 0 | bytes=65605 |
| anc | save anc column 1 | FAIL | 0 | Visit needs review before completion |
| anc | save again (idempotent) | FAIL | 0 | Visit needs review before completion |
| anc | anc card entry | PASS | 0 | anc=[1] |
| anc | ifa given once (no double count) | FAIL | 0 | ifa 260 → 200 (want -30) |
| anc | mcp ifa total once | FAIL | 0 | mcp ifa 0 → 60 |
| anc | one visit record | FAIL | 0 | visits=0 |
| persist | registry file written | PASS | 0 | bytes=72316 |
