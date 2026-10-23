#!/usr/bin/env python3
"""
构造合法的 MOBI/AZW3 测试文件，用于验证 MobiParser。
实现严格遵循 KindleUnpack 的解压约定:
  - PalmDOC 压缩(类型2): 字节取值区间解释
      1..8   字面量游程
      0,9..127 字面量
      128..191 回溯引用(2字节): c=128+m//32, b2=((m%32)<<3)|(n-3)
      192..255 空格+字符
  - 偏移严格对齐 MOBI 头结构。
"""
import struct
import sys
import os

def palm_compress(data: bytes) -> bytes:
    out = bytearray()
    n = len(data)
    i = 0
    while i < n:
        # 贪心最长匹配(长度3..10, 距离1..2047)
        best_m = 0
        best_len = 0
        start = max(0, i - 2047)
        for j in range(start, i):
            length = 0
            while length < 10 and i + length < n and data[j + length] == data[i + length]:
                length += 1
            if length >= 3 and length > best_len:
                best_len = length
                best_m = i - j
        if best_len >= 3:
            m = best_m
            nn = best_len
            c = 128 + (m // 32)
            b2 = ((m % 32) << 3) | (nn - 3)
            out.append(c)
            out.append(b2)
            i += nn
        else:
            b = data[i]
            if b == 0 or 9 <= b <= 0x7F:
                out.append(b)
            else:
                # 以长度为1的游程承载非常规字节
                out.append(1)
                out.append(b)
            i += 1
    return bytes(out)

def build_mobi(title, author, text, encoding=65001, mobi_version=6, compression=2):
    raw = text.encode("utf-8" if encoding == 65001 else "cp1252")
    record_size = 4096
    text_records = []
    pos = 0
    while pos < len(raw):
        chunk = raw[pos:pos + record_size]
        text_records.append(palm_compress(chunk))
        pos += record_size
    text_record_count = len(text_records)

    # ---- 记录0: PalmDOC头(16) + MOBI头(0xE4) + EXTH + 标题 ----
    palm = struct.pack(">HHIHHHH", compression, 0, len(raw), text_record_count,
                       record_size, 0, 0)
    mobi = bytearray(0xE4)
    mobi[0:4] = b"MOBI"
    struct.pack_into(">I", mobi, 4, 0xE4)        # header length
    struct.pack_into(">I", mobi, 8, 2)            # mobi type
    struct.pack_into(">I", mobi, 0x0C, encoding)  # text encoding
    struct.pack_into(">I", mobi, 0x10, 1)         # unique id
    struct.pack_into(">I", mobi, 0x14, 0xE4)      # mobi length field
    struct.pack_into(">I", mobi, 0x18, 0)
    struct.pack_into(">I", mobi, 0x1C, 0)
    struct.pack_into(">I", mobi, 0x20, 0)
    struct.pack_into(">I", mobi, 0x24, 0)
    struct.pack_into(">I", mobi, 0x28, 0)
    struct.pack_into(">I", mobi, 0x2C, 0)
    struct.pack_into(">I", mobi, 0x30, 0)
    struct.pack_into(">I", mobi, 0x34, 0)
    struct.pack_into(">I", mobi, 0x38, 0)
    struct.pack_into(">I", mobi, 0x3C, 0)
    struct.pack_into(">I", mobi, 0x40, 0)
    struct.pack_into(">I", mobi, 0x44, 0)
    struct.pack_into(">I", mobi, 0x48, 0)
    struct.pack_into(">I", mobi, 0x4C, 0)
    struct.pack_into(">I", mobi, 0x50, 0)
    struct.pack_into(">I", mobi, 0x54, 0)        # fullNameOffset (占位, 稍后填)
    struct.pack_into(">I", mobi, 0x58, 0)        # fullNameLength (占位)
    struct.pack_into(">I", mobi, 0x5C, 0)        # locale
    struct.pack_into(">I", mobi, 0x60, 0)
    struct.pack_into(">I", mobi, 0x64, 0)
    struct.pack_into(">I", mobi, 0x68, 0)
    struct.pack_into(">I", mobi, 0x6C, 0)        # first resource offset
    struct.pack_into(">I", mobi, 0x70, 0)        # huff offset
    struct.pack_into(">I", mobi, 0x74, 0)        # huff number
    # 0x78 mobi version
    struct.pack_into(">I", mobi, 0x78, mobi_version)
    # 0x80 exth flags (0x40 = EXTH present)
    struct.pack_into(">I", mobi, 0x80, 0x40)
    # 0xE4 srcs count (KF8)
    # 0xF2 trailing data flags = 0 (测试文件无尾部数据)
    struct.pack_into(">H", mobi, 0xE2, 0)

    # ---- EXTH ----
    def add(exth, rec_id, value: bytes):
        length = 8 + len(value)
        return exth + struct.pack(">II", rec_id, length) + value
    exth = bytearray(b"EXTH")
    exth += struct.pack(">I", 0)  # EXTH length (占位)
    exth += struct.pack(">I", 0)  # count (占位)
    exth = add(exth, 100, author.encode("utf-8"))
    exth = add(exth, 503, title.encode("utf-8"))
    exth_len = len(exth)
    struct.pack_into(">I", exth, 4, exth_len)
    struct.pack_into(">I", exth, 8, 2)  # count = 2 (100, 503)

    title_bytes = title.encode("utf-8")
    full_name_offset = 16 + len(mobi) + len(exth)
    struct.pack_into(">I", mobi, 0x54, full_name_offset)
    struct.pack_into(">I", mobi, 0x58, len(title_bytes))

    record0 = bytearray(palm) + mobi + exth + title_bytes
    # 4 字节对齐
    while len(record0) % 4 != 0:
        record0.append(0)

    records = [bytes(record0)] + text_records
    # 资源记录占位(封面), 便于 KF8 结构完整
    records.append(b"\x00" * 4)

    # ---- 组装 PDB ----
    num_records = len(records)
    name = title.encode("utf-8")[:31].ljust(32, b" ")[:32]
    header = bytearray()
    header += name
    header += struct.pack(">H", 0)        # attributes
    header += struct.pack(">H", 0)        # version
    header += struct.pack(">I", 0)        # creation date
    header += struct.pack(">I", 0)        # modification date
    header += struct.pack(">I", 0)        # last backup
    header += struct.pack(">I", 0)        # modification number
    header += struct.pack(">I", 0)        # app info id
    header += struct.pack(">I", 0)        # sort info id
    header += b"BOOK"                     # type
    header += b"MOBI"                     # creator
    header += struct.pack(">I", 0)        # unique id seed
    header += struct.pack(">I", 0)        # next record list id
    header += struct.pack(">H", num_records)

    body = b"".join(records)
    base = 78 + 8 * num_records  # PDB 头 + 记录表 之后的绝对起始偏移
    rec_list = bytearray()
    pos = 0
    for idx, rec in enumerate(records):
        rec_list += struct.pack(">I", base + pos) + struct.pack(">B", 0) + struct.pack(">I", idx)[1:]
        pos += len(rec)
    full = bytes(header) + bytes(rec_list) + body
    return full

def main():
    title = "测试书籍标题"
    author = "测试作者"
    # 含重复段落以触发回引(回溯引用)路径
    base = ("第一章 测试内容\n这是一段用于验证 MOBI 解析的样例文本。\n" * 3 +
            "第二章 更多内容\n乱码问题已经修复，回引与字面量都能正确解码。\n" * 3 +
            "第三章 结尾\n希望这一章也能被正确读取并显示。\n" * 3)
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    mobi_version = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    ext = sys.argv[3] if len(sys.argv) > 3 else "mobi"
    data = build_mobi(title, author, base, encoding=65001, mobi_version=mobi_version, compression=2)
    path = os.path.join(out_dir, "test_book." + ext)
    with open(path, "wb") as f:
        f.write(data)
    print("wrote", path, len(data), "bytes; version", mobi_version)

if __name__ == "__main__":
    main()
