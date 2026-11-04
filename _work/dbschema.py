import sqlite3
c = sqlite3.connect(r'C:\Pramaan - Copy\_work\run\ASHA_Health_Guide_v2.db')
for t in ('knowledge_sources', 'procurement_chunks'):
    print('=== ' + t + ' ===')
    row = c.execute("SELECT sql FROM sqlite_master WHERE name=?", (t,)).fetchone()
    print(row[0] if row else 'MISSING')
print('sources:', c.execute('SELECT COUNT(*) FROM knowledge_sources').fetchone()[0])
print('chunks:', c.execute('SELECT COUNT(*) FROM procurement_chunks').fetchone()[0])
print('--- authorities ---')
for r in c.execute('SELECT authority, COUNT(*) FROM knowledge_sources GROUP BY authority'):
    print(r)
print('--- sample sources ---')
cols = [d[1] for d in c.execute('PRAGMA table_info(knowledge_sources)')]
print(cols)
for r in c.execute('SELECT * FROM knowledge_sources LIMIT 3'):
    print(r)
print('--- chunk cols ---')
print([d[1] for d in c.execute('PRAGMA table_info(procurement_chunks)')])
print('lang:', c.execute('SELECT language, COUNT(*) FROM knowledge_sources GROUP BY language').fetchall())
print('urls:', c.execute("SELECT SUM(source_url IS NOT NULL AND source_url <> ''), SUM(register_url IS NOT NULL AND register_url <> ''), SUM((source_url IS NULL OR source_url = '') AND (register_url IS NULL OR register_url = '')) FROM knowledge_sources").fetchone())
print('pages:', c.execute("SELECT COUNT(DISTINCT source_name || '#' || page_number) FROM procurement_chunks WHERE page_number IS NOT NULL").fetchone()[0])
print('per-doc:', c.execute('SELECT source_name, COUNT(*), COUNT(DISTINCT page_number) FROM procurement_chunks GROUP BY source_name LIMIT 5').fetchall())
print('join-by-source_name:', c.execute('SELECT COUNT(*) FROM procurement_chunks p JOIN knowledge_sources s ON s.source_name = p.source_name').fetchone()[0])
print('join-by-doc_id:', c.execute('SELECT COUNT(*) FROM procurement_chunks p JOIN knowledge_sources s ON s.source_name = p.doc_id').fetchone()[0])
print('doc-sample:', c.execute('SELECT DISTINCT doc_id FROM procurement_chunks LIMIT 5').fetchall())
print('orig-sample:', c.execute('SELECT DISTINCT substr(original_chunk_id,1,40) FROM procurement_chunks LIMIT 5').fetchall())
