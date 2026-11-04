# Gold-100 benchmark (laptop, 2026-11-03)

DB: _work/run/ASHA_Health_Guide_v2.db | items: 100 | key: absent

## 1. Integrity (v2 DB)
- sources: 293, resolved: 293 (by chunk_id col: 0, by row id: 293, by src+page+quote: 0, missing: 0)
- page matches DB row: 293/293 | quote found in chunk: 293/293

## 2. Keyword match (C1 keyword half only; semantic cosine is phone-only)
- EN: top-1 100/100, strong 100, medium 0, below 0
- HI: top-1 99/100, strong 100, medium 0, below 0

## 3. Retrieval hit (right page in evidence)
- BASELINE (laptop keyword top-5; NOT the phone E5 pipeline): 46/100
- GOLD-ON (pinned when matched; needs the phone match score): 100/100 have their page pinned once resolved
- Phone E5 retrieval off-vs-on: to be measured manually (checklist A/B + AROGYA_GOLD logs)

## 4. Cloud generation: NOT MEASURED (no META_API_KEY in the environment)
## 5. Ten worst: n/a (no cloud runs)
