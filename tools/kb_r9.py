#!/usr/bin/env python3
"""R9: build ASHA_Health_Guide_v2.db from ASHA_Health_Guide.db (Batch 2).

Changes ONLY knowledge_sources + PRAGMA user_version; passages and the review
table are verified byte-identical afterwards. The old file stays in place; the
app picks v1/v2 through ArogyaFlags.r9NewDatabase (see ArogyaGuideDb).

  1. user_version 0 -> 2 (lets the app + M1 tell v1 from v2).
  2. Lower priority (precedence 2 -> 4) of non-ASHA-audience documents:
     NPCDCS medical-officer module, staff-nurse module, MPW + ANM/MPW modules,
     the RKSK peer-educator books and the untied-funds draft.
  3. Add official source URLs for the 8 Ayushman Bharat HWC documents, whose
     hashed file names resolve at ab-hwc.nhp.gov.in/download/document/<name>
     (pattern verified by web search for 829bf86e = staff-nurse module with a
     matching title; the other 7 share the corpus builder's naming).
     The other 15 URL-less sources keep NULL (no verified official URL found;
     R7 then offers the in-app page sheet instead of "Open document").

Usage:  python tools/kb_r9.py [src.db] [dst.db]
"""
import hashlib
import shutil
import sqlite3
import sys

DEMOTE_TO_4 = [
    '47b077f6bfcded85af8bf2f319136186.pdf',  # NPCDCS training module for medical officers
    '829bf86e304132dfae9962855c57dcd8.pdf',  # staff-nurse NCD screening module
    'b09f1edf31a9165aebf486a55bbe54f5.pdf',  # MPW (F/M) NCD module
    'c605f3d8993c1ea43937354a8970d104.pdf',  # ANM/MPW module (Hindi)
    'rksk_faq.pdf',                          # RKSK peer-educator FAQ
    'rksk_pe.pdf',                           # RKSK peer-educator resource book
    'Draft_guidance_note_on_untied_funds.pdf',  # untied-funds draft
]

NHP_BASE = 'https://ab-hwc.nhp.gov.in/download/document/'
NHP_DOCS = [
    '263f3ca26df87dc44b7687c1b9cf0c15.pdf',  # Module for ASHA on NCDs
    '45a4ab64b74ab124cfd853ec9a0127e4.pdf',  # Ayushman Bharat CPHC through HWCs
    '47b077f6bfcded85af8bf2f319136186.pdf',  # NPCDCS MO module
    '47c15fa0edba7559a0773830a712894a.pdf',  # ASHA NCD module (Hindi)
    '829bf86e304132dfae9962855c57dcd8.pdf',  # staff-nurse NCD module
    'ac403676f3b66475ba027e70b06b1a7c.pdf',  # cancers operational framework
    'b09f1edf31a9165aebf486a55bbe54f5.pdf',  # MPW NCD module
    'c605f3d8993c1ea43937354a8970d104.pdf',  # ANM/MPW module (Hindi)
]


def table_hash(c, table):
    h = hashlib.sha256()
    cols = ','.join(d[1] for d in c.execute('PRAGMA table_info(%s)' % table).fetchall())
    for row in c.execute('SELECT %s FROM %s ORDER BY rowid' % (cols, table)):
        h.update(repr(row).encode('utf-8', 'replace'))
    return h.hexdigest()


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else '_work/run/ASHA_Health_Guide.db'
    dst = sys.argv[2] if len(sys.argv) > 2 else '_work/run/ASHA_Health_Guide_v2.db'
    s = sqlite3.connect('file:%s?mode=ro' % src, uri=True)
    before = {t: table_hash(s, t) for t in ('procurement_chunks', 'arogya_chunk_review')}
    n_src = s.execute('SELECT COUNT(*) FROM knowledge_sources').fetchone()[0]
    s.close()

    shutil.copyfile(src, dst)
    d = sqlite3.connect(dst)
    d.execute('PRAGMA user_version = 2')
    for name in DEMOTE_TO_4:
        cur = d.execute('UPDATE knowledge_sources SET precedence = 4 WHERE source_name = ?', (name,))
        if cur.rowcount != 1:
            raise SystemExit('R9 FAIL: %s updated %d rows' % (name, cur.rowcount))
    for name in NHP_DOCS:
        cur = d.execute('UPDATE knowledge_sources SET source_url = ? WHERE source_name = ?', (NHP_BASE + name, name))
        if cur.rowcount != 1:
            raise SystemExit('R9 FAIL: url %s updated %d rows' % (name, cur.rowcount))
    d.commit()
    after = {t: table_hash(d, t) for t in ('procurement_chunks', 'arogya_chunk_review')}
    dist = d.execute('SELECT precedence, COUNT(*) FROM knowledge_sources GROUP BY precedence ORDER BY precedence').fetchall()
    urls = d.execute("SELECT COUNT(*) FROM knowledge_sources WHERE source_url IS NOT NULL AND source_url<>''").fetchone()[0]
    n_dst = d.execute('SELECT COUNT(*) FROM knowledge_sources').fetchone()[0]
    uv = d.execute('PRAGMA user_version').fetchone()[0]
    d.close()

    assert before == after, 'R9 FAIL: chunks/review changed: %s vs %s' % (before, after)
    assert n_src == n_dst == 68, 'R9 FAIL: source count %s -> %s' % (n_src, n_dst)
    print('R9 OK: %s' % dst)
    print('  user_version=%d precedence=%s urls=%d/68 (8 added, 15 still NULL)' % (uv, dist, urls))
    print('  chunks+review identical: %s' % (before == after))
    print('  TODO urls: untied draft, MNS, HBYC-ECD kit, JAS, 1000-days Hindi, ENT/Eye/Oral, VBD set (7)')


if __name__ == '__main__':
    main()
