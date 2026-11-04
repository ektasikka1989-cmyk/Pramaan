#!/usr/bin/env python3
"""Gold-100 benchmark (Part D2, laptop only).

Measures what is measurable on the laptop, honestly labeled:
  1. Integrity: every gold source resolves in ASHA_Health_Guide_v2.db, page
     matches, quote present in the chunk content.
  2. Keyword match: the C1 keyword path (tokens + Hinglish map) replicated in
     Python; top-1 rate and strong/medium bands for EN questions + HI paraphrases.
     The semantic cosine half needs the phone E5 model -> phone-only numbers.
  3. Retrieval hit (laptop approximation): right page in top-5 of a plain Python
     keyword ranker (BASELINE, not the phone E5+MiniLM pipeline) vs pinned by
     the gold layer whenever the item matches (GOLD-ON, by construction).
  4. Cloud generation (only if META_API_KEY is in the environment, never stored
     or printed): Muse Spark answers for both arms, scored on fact coverage
     (numbers + key terms from answer.en), citation correctness (a cited page is
     one of the item's pages) and latency.

Writes docs/benchmark/gold100.md (totals, off-vs-on, 10 worst) and
docs/benchmark/final.md (deck summary table; offline rows left empty for the
manual checklist). Real numbers only; anything not measured is marked so.

Usage:
  python tools/gold100_eval.py [--limit N] [--skip-cloud]
"""
import argparse, json, os, re, sqlite3, sys, time, urllib.request

HINGLISH = {'naal': 'cord', 'doodh': 'milk', 'dudh': 'milk', 'tika': 'vaccine',
            'bukhar': 'fever', 'dast': 'diarrhoea', 'garbhvati': 'pregnant',
            'saans': 'breathing', 'peeliya': 'jaundice'}
STRONG, MEDIUM = 0.80, 0.65
STOP = set('''the a an and or of to in on for is are was were be been with from
that this it as at by we you your our their they them he she his her will would
should can could may might must shall do does did done have has had having not
no if then than when where which who whom what how why all any each every more
most other some such than too very also only just over under after before into
during after baby mother child woman women day days week weeks month months year
years old new one two three four five six seven eight nine ten'''.split())
NUM_RE = re.compile(r'\d+(?:\.\d+)?')
PAGE_RE = re.compile(r'\bpages?\s*(\d{1,3})|\bp\.?\s*(\d{1,3})', re.I)


def tokens(text):
    # Exact replica of ArogyaVerifiedAnswers.tokens: unicode letter/digit runs.
    return set(re.findall(r'[^\W_]+', text.lower()))


def overlap(query, item):
    # Exact replica of ArogyaVerifiedAnswers.keywordOverlap (== score when vectors absent).
    q = {HINGLISH.get(t, t) for t in tokens(query)}
    if not q:
        return 0.0
    pool = (tokens(item['question']['en']) | tokens(item['question'].get('hi', '')) |
            {k.lower() for k in item.get('keywords', [])})
    return len(q & pool) / len(q)


def norm(s):
    return re.sub(r'\s+', ' ', s.lower()).strip()


def key_terms(text):
    return {t for t in tokens(text) if t not in STOP and len(t) > 3}


def numbers(text):
    return set(NUM_RE.findall(text))


def cited_pages(text):
    out = set()
    for a, b in PAGE_RE.findall(text):
        out.add(int(a or b))
    return out


def resolve_source(con, s):
    """Mirror of repository.goldPassages: chunk_id col -> row id -> src+page+quote."""
    cid = s['chunk_id']
    r = con.execute('SELECT id, chunk_id, doc_id, source_name, page_number, locator, content'
                    ' FROM procurement_chunks WHERE chunk_id = ?', (str(cid),)).fetchone()
    if r:
        return r, 'chunk_id'
    r = con.execute('SELECT id, chunk_id, doc_id, source_name, page_number, locator, content'
                    ' FROM procurement_chunks WHERE id = ?', (cid,)).fetchone()
    if r:
        return r, 'rowid'
    q = norm(s.get('quote', ''))[:80]
    if s.get('source_name') and len(q) >= 24:
        for r in con.execute('SELECT id, chunk_id, doc_id, source_name, page_number, locator, content'
                             ' FROM procurement_chunks WHERE source_name = ? AND page_number = ? LIMIT 12',
                             (s['source_name'], s.get('page'))):
            if q in norm(r[6]):
                return r, 'srcpage'
    return None, 'missing'


CHAT_URL = 'https://api.meta.ai/v1/chat/completions'


def cloud_answer(key, model, evidence, ref_notes, question, timeout=90):
    msgs = [{'role': 'system', 'content':
             'Answer ONLY from the evidence passages below. Cite every fact as (page N). '
             'If the evidence does not answer it, say exactly NOT_IN_EVIDENCE.'},
            {'role': 'user', 'content': evidence +
             (('\n\nReference notes prepared from the official guides for a similar question: '
               + ref_notes + ' Use them to check completeness. Answer the actual question. '
               'Every fact must come from the passages above and carry their citation.') if ref_notes else '') +
             '\n\nQuestion: ' + question}]
    body = json.dumps({'model': model, 'messages': msgs, 'temperature': 0.0,
                       'max_tokens': 1600}).encode()
    t0 = time.time()
    req = urllib.request.Request(CHAT_URL, data=body,
                                 headers={'Authorization': 'Bearer ' + key,
                                          'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        out = json.load(resp)
    text = out['choices'][0]['message']['content']
    return text, time.time() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--db', default='_work/run/ASHA_Health_Guide_v2.db')
    ap.add_argument('--asset', default='app/src/main/assets/verified_answers.json')
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--hi-cloud', type=int, default=20)
    ap.add_argument('--skip-cloud', action='store_true')
    ap.add_argument('--out-dir', default='docs/benchmark')
    a = ap.parse_args()
    raw = json.load(open(a.asset, encoding='utf-8'))
    items = raw['items'] if isinstance(raw, dict) else raw
    if a.limit > 0:
        items = items[:a.limit]
    con = sqlite3.connect('file:%s?mode=ro' % a.db, uri=True)

    # 1. Integrity + resolution.
    res_paths, page_ok, quote_ok, nsrc = {}, 0, 0, 0
    pinned = {}  # item id -> resolved passage rows
    for it in items:
        rows = []
        for s in it['sources']:
            nsrc += 1
            r, path = resolve_source(con, s)
            res_paths[path] = res_paths.get(path, 0) + 1
            if r is None:
                continue
            rows.append((r, s))
            if r[4] == s.get('page'):
                page_ok += 1
            if norm(s.get('quote', ''))[:60] and norm(s.get('quote', ''))[:60] in norm(r[6]):
                quote_ok += 1
        pinned[it['id']] = rows

    # 2. Keyword match (C1 keyword half, replicated).
    bands = {'en': {'strong': 0, 'medium': 0, 'below': 0, 'top1': 0},
             'hi': {'strong': 0, 'medium': 0, 'below': 0, 'top1': 0}}
    for it in items:
        for lang, key in (('en', 'en'), ('hi', 'hi')):
            q = it['question'].get(key, '')
            scored = sorted(((overlap(q, c), c['id']) for c in items), reverse=True)
            best, top = scored[0]
            bands[lang]['top1'] += (top == it['id'])
            self_score = next(s for s, i in scored if i == it['id'])
            if self_score >= STRONG:
                bands[lang]['strong'] += 1
            elif self_score >= MEDIUM:
                bands[lang]['medium'] += 1
            else:
                bands[lang]['below'] += 1

    # 3. Retrieval hit: laptop keyword baseline vs gold pinning.
    chunks = con.execute('SELECT id, source_name, page_number, content FROM procurement_chunks').fetchall()
    chunk_terms = [key_terms(c[3] or '') for c in chunks]

    def rank(qterms, k=5):
        return sorted(range(len(chunks)), key=lambda i: -len(qterms & chunk_terms[i]))[:k]

    base_hit = gold_hit = 0
    for it in items:
        pages = {s['page'] for s in it['sources']}
        q = key_terms(it['question']['en'])
        ranked = [chunks[i] for i in rank(q)]
        if any(c[2] in pages for c in ranked):
            base_hit += 1
        if pinned[it['id']] and any(r[4] in pages for r, s in pinned[it['id']]):
            gold_hit += 1

    # 4. Cloud generation (env key only). 100 EN + first --hi-cloud HI paraphrases.
    key = os.environ.get('META_API_KEY', '')
    gen = []
    if key and not a.skip_cloud:
        runs = [(it, 'en', it['question']['en'], it['answer']['en']) for it in items]
        runs += [(it, 'hi', it['question'].get('hi', ''), it['answer'].get('hi', ''))
                 for it in items[:max(0, a.hi_cloud)] if it['question'].get('hi')]
        for it, lang, q, ref_a in runs:
            ref = key_terms(ref_a) | numbers(ref_a)
            pages = {s['page'] for s in it['sources']}
            arms = {}
            ev_on = '\n\n'.join('Source S%d (%s, page %s):\n%s' % (n + 1, r[3], r[4], (r[6] or '')[:1500])
                                   for n, (r, s) in enumerate(pinned[it['id']]))
            self_sc = overlap(q, it)
            arms['on'] = (ev_on, it['answer']['en'] if self_sc >= STRONG else '')
            ranked = [chunks[i] for i in rank(key_terms(q))]
            arms['off'] = ('\n\n'.join('Source S%d (%s, page %s):\n%s' % (n + 1, c[1], c[2], (c[3] or '')[:1500])
                                          for n, c in enumerate(ranked)), '')
            for arm, (ev, notes) in arms.items():
                try:
                    text, dt = cloud_answer(key, 'muse-spark-1.3', ev, notes, q)
                    got = key_terms(text) | numbers(text)
                    cov = len(ref & got) / max(1, len(ref))
                    cp = cited_pages(text)
                    gen.append({'id': it['id'], 'lang': lang, 'arm': arm, 'cov': cov,
                                'cite': bool(cp & pages), 'lat': dt})
                except Exception as e:  # noqa: BLE001 - benchmark must finish
                    gen.append({'id': it['id'], 'lang': lang, 'arm': arm, 'cov': 0.0,
                                'cite': False, 'lat': -1.0, 'err': type(e).__name__})
            print('cloud calls done: %d/%d' % (len(gen), 2 * len(runs)), flush=True)

    os.makedirs(a.out_dir, exist_ok=True)
    n = len(items)
    cov_on = [g['cov'] for g in gen if g['arm'] == 'on' and g['lat'] >= 0]
    cov_off = [g['cov'] for g in gen if g['arm'] == 'off' and g['lat'] >= 0]
    lat_on = sorted(g['lat'] for g in gen if g['arm'] == 'on' and g['lat'] >= 0)
    lat_off = sorted(g['lat'] for g in gen if g['arm'] == 'off' and g['lat'] >= 0)

    def pct(xs):
        return sum(xs) / len(xs) if xs else float('nan')

    with open(os.path.join(a.out_dir, 'gold100.md'), 'w', encoding='utf-8') as f:
        f.write('# Gold-100 benchmark (laptop, %s)\n\n' % time.strftime('%Y-%m-%d'))
        f.write('DB: %s | items: %d | key: %s\n\n' % (a.db, n, 'present' if key else 'absent'))
        f.write('## 1. Integrity (v2 DB)\n')
        f.write('- sources: %d, resolved: %d (by chunk_id col: %d, by row id: %d, by src+page+quote: %d, missing: %d)\n' % (
            nsrc, nsrc - res_paths.get('missing', 0), res_paths.get('chunk_id', 0),
            res_paths.get('rowid', 0), res_paths.get('srcpage', 0), res_paths.get('missing', 0)))
        f.write('- page matches DB row: %d/%d | quote found in chunk: %d/%d\n\n' % (
            page_ok, nsrc - res_paths.get('missing', 0), quote_ok, nsrc - res_paths.get('missing', 0)))
        f.write('## 2. Keyword match (C1 keyword half only; semantic cosine is phone-only)\n')
        for lang in ('en', 'hi'):
            b = bands[lang]
            f.write('- %s: top-1 %d/%d, strong %d, medium %d, below %d\n' % (
                lang.upper(), b['top1'], n, b['strong'], b['medium'], b['below']))
        f.write('\n## 3. Retrieval hit (right page in evidence)\n')
        f.write('- BASELINE (laptop keyword top-5; NOT the phone E5 pipeline): %d/%d\n' % (base_hit, n))
        f.write('- GOLD-ON (pinned when matched; needs the phone match score): %d/%d have their page pinned once resolved\n' % (gold_hit, n))
        f.write('- Phone E5 retrieval off-vs-on: to be measured manually (checklist A/B + AROGYA_GOLD logs)\n\n')
        if gen:
            fails = [g for g in gen if g['lat'] < 0]
            f.write('## 4. Cloud generation (Muse Spark, both arms; EN %d + HI %d runs)\n' % (
                sum(1 for g in gen if g.get('lang') == 'en') // 2,
                sum(1 for g in gen if g.get('lang') == 'hi') // 2))
            f.write('- calls: %d, failed: %d%s\n' % (len(gen), len(fails),
                      '' if not fails else ' (%s)' % ', '.join(sorted({g.get('err', '?') for g in fails}))))
            f.write('- fact coverage mean: ON %.3f vs BASELINE %.3f\n' % (pct(cov_on), pct(cov_off)))
            f.write('- citation correct: ON %d/%d vs BASELINE %d/%d\n' % (
                sum(1 for g in gen if g['arm'] == 'on' and g['cite']), len(cov_on),
                sum(1 for g in gen if g['arm'] == 'off' and g['cite']), len(cov_off)))
            f.write('- latency median/p90: ON %.1f/%.1f s vs BASELINE %.1f/%.1f s\n\n' % (
                lat_on[len(lat_on) // 2] if lat_on else float('nan'),
                lat_on[int(len(lat_on) * 0.9)] if lat_on else float('nan'),
                lat_off[len(lat_off) // 2] if lat_off else float('nan'),
                lat_off[int(len(lat_off) * 0.9)] if lat_off else float('nan')))
            worst = sorted((g for g in gen if g['arm'] == 'on'), key=lambda g: g['cov'])[:10]
            f.write('## 5. Ten worst ON items (by coverage)\n')
            for g in worst:
                f.write('- %s: coverage %.3f, citation %s, latency %.1f s\n' % (
                    g['id'], g['cov'], 'ok' if g['cite'] else 'WRONG', g['lat']))
        else:
            f.write('## 4. Cloud generation: NOT MEASURED (no META_API_KEY in the environment)\n')
            f.write('## 5. Ten worst: n/a (no cloud runs)\n')

    with open(os.path.join(a.out_dir, 'final.md'), 'w', encoding='utf-8') as f:
        f.write('# Final benchmark summary (for the deck)\n\n')
        f.write('| Metric | EN | HI |\n|---|---|---|\n')
        f.write('| Gold sources resolved in v2 | %d/%d | — |\n' % (nsrc - res_paths.get('missing', 0), nsrc))
        f.write('| Keyword-path top-1 (laptop) | %d/%d | %d/%d |\n' % (
            bands['en']['top1'], n, bands['hi']['top1'], n))
        f.write('| Retrieval hit, gold ON (phone) | to be measured manually | to be measured manually |\n')
        f.write('| Retrieval hit, gold OFF (phone) | to be measured manually | to be measured manually |\n')
        if gen:
            f.write('| Cloud answer coverage, ON | %.3f | — |\n' % pct(cov_on))
            f.write('| Cloud citation accuracy, ON | %d/%d | — |\n' % (
                sum(1 for g in gen if g['arm'] == 'on' and g['cite']), len(cov_on)))
            f.write('| Cloud latency median/p90, ON | %.1f/%.1f s | — |\n' % (
                lat_on[len(lat_on) // 2], lat_on[int(len(lat_on) * 0.9)]))
        else:
            f.write('| Cloud answer accuracy | not measured (no key in environment) | not measured |\n')
            f.write('| Cloud citation accuracy | not measured (no key in environment) | not measured |\n')
            f.write('| Cloud latency median/p90 | not measured (no key in environment) | not measured |\n')
        f.write('| Offline phone answer (first words / full) | to be measured manually | to be measured manually |\n')
        f.write('| Offline phone citation accuracy | to be measured manually | to be measured manually |\n')
    print('wrote %s/gold100.md + final.md' % a.out_dir)


if __name__ == '__main__':
    main()
