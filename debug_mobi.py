import mkmobi
raw = b'ABABABAB'
comp = mkmobi.palm_compress(raw)
print('comp hex', comp.hex(), 'len', len(comp))
out = bytearray(); p = 0
while p < len(comp):
    c = comp[p]; p += 1
    if 1 <= c <= 8:
        end = min(p + c, len(comp)); out += comp[p:end]; p = end
        print('run', c, '->', bytes(out[-c:]))
    elif c < 128:
        out.append(c); print('lit', chr(c), 'out', bytes(out))
    elif c >= 192:
        out.append(0x20); out.append(c ^ 128); print('sp+char', 'out', bytes(out))
    else:
        if p < len(comp):
            c2 = (c << 8) | comp[p]; p += 1
            m = (c2 >> 3) & 0x07FF; nn = (c2 & 7) + 3
            if m > nn:
                for k in range(nn):
                    s = wpos(out) - m + k
                    if s >= 0: out.append(out[s])
                print('bref m>nn', m, nn, 'out', bytes(out))
            else:
                for k in range(nn):
                    s = wpos(out) - m
                    if s >= 0: out.append(out[s])
                print('bref overlap', m, nn, 'out', bytes(out))
print('FINAL', bytes(out), 'OK' if bytes(out) == raw else 'BAD')

def wpos(o): return len(o)
