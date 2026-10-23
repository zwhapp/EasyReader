#!/usr/bin/env python3
"""用与 Kotlin MobiParser 等价的逻辑解码生成的 MOBI, 验证结构/中文无乱码。"""
import struct, sys

def u16(b, o): return (b[o] << 8) | b[o+1] if o+1 < len(b) else 0
def u32(b, o):
    if o+3 >= len(b): return 0
    return (b[o] << 24) | (b[o+1] << 16) | (b[o+2] << 8) | b[o+3]

def palm_decompress(rec):
    """与 Kotlin PalmdocReader 等价: 按 bytearray 实际长度定位回引, 避免 wpos 不同步。"""
    out = bytearray(); p = 0
    while p < len(rec):
        c = rec[p]; p += 1
        if 1 <= c <= 8:
            end = min(p + c, len(rec))
            out += rec[p:end]; p = end
        elif c < 128:
            out.append(c)
        elif c >= 192:
            out.append(0x20); out.append(c ^ 128)
        else:
            if p < len(rec):
                c2 = (c << 8) | rec[p]; p += 1
                m = (c2 >> 3) & 0x07FF
                nn = (c2 & 7) + 3
                # 统一分支: 源 = 当前长度 - 距离, 随输出递增逐字节复制
                # (重叠与非重叠均由 growing 源地址自然处理, 不加 +k)
                for k in range(nn):
                    src = len(out) - m
                    if src >= 0: out.append(out[src])
    return bytes(out)

def decode(path):
    data = open(path, "rb").read()
    assert data[0x3C:0x40] == b"BOOK" or data[0x40:0x44] == b"MOBI", "bad type/creator"
    rc = u16(data, 0x4C)
    offs = [u32(data, 0x4E + i*8) for i in range(rc)] + [len(data)]
    def rec(i): return data[offs[i]:offs[i+1]] if 0 <= i < rc else b""
    r0 = rec(0)
    comp = u16(r0, 0)
    trc = u16(r0, 8)
    assert StringCheck(r0, 0x10, 4) == b"MOBI", "no MOBI header"
    enc = u32(r0, 0x1C)
    cs = "utf-8" if enc == 65001 else "cp1252"
    no = u32(r0, 0x64); nl = u32(r0, 0x68)
    title = r0[no:no+nl].decode(cs).strip()
    # EXTH author
    author = ""
    p = 0x10 + u32(r0, 0x14)
    if r0[p:p+4] == b"EXTH":
        elen = u32(r0, p+4); ec = u32(r0, p+8); q = p + 12; n = 0
        while n < ec and q + 8 <= p + elen:
            t = u32(r0, q); ln = u32(r0, q+4)
            if ln < 8 or q+ln > len(r0): break
            if t == 100 or t == 101: author = r0[q+8:q+ln].decode(cs).strip()
            q += ln; n += 1
    body = bytearray()
    for i in range(1, min(trc, rc-1)+1):
        r = rec(i)
        if comp == 1: d = r
        elif comp == 2: d = palm_decompress(r)
        else: raise Exception("comp %d unsupported" % comp)
        body += d
    text = body.decode(cs, "replace")
    return title, author, text

def StringCheck(b, o, n):
    return b[o:o+n]

if __name__ == "__main__":
    for path, ver in [(sys.argv[1], 6), (sys.argv[2], 8)]:
        title, author, text = decode(path)
        print("FILE", path)
        print("  title :", title)
        print("  author:", author)
        print("  textlen:", len(text))
        print("  has 第一章:", "第一章" in text, "| has 乱码问题已经修复:", "乱码问题已经修复" in text)
        print("  sample:", repr(text[:40]))
