import re, sys
x = open(sys.argv[1], encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*?text="([^"]+)"[^>]*?bounds="([^"]+)"', x):
    t = m.group(1)[:70].encode('ascii', 'replace').decode()
    print(t, '|', m.group(2))
