"""Writes app/src/test/resources/bpe_golden.tsv: ids from Hugging Face `tokenizers` for BpeTokenizerTest.

    python -I bpe_golden.py LFM3_TOKENIZER_JSON D1_TOKENIZER_JSON OUT_TSV

Lines (tab-separated, text escaped as \\t \\n \\r \\\\ \\uXXXX \\UXXXXXXXX):
    S name size
    E name text ids                 encode(text, add_special_tokens=False)
    D name ids skip_special text    decode(ids, skip_special_tokens=...)
    I name token id                 token_to_id(token)
"""
import random
import sys
import unicodedata

from tokenizers import Tokenizer

CASES = [
    "", " ", "a", "Hello", "Hello, world!", "  leading and trailing  ", "multiple     spaces   here",
    "tabs\tand\t\ttabs", "line one\nline two\n\nline four\r\nwindows", "\n", "\n\n\n", " \n \n ", "\t \t\n",
    "It's they're we've I'm you'll he'd SHE'S IT'LL", "don't 'quote' \"double\"",
    "1 12 123 1234 12345 1234567890", "3.14159 -42 +7 1e10 0x1F", "2026-11-01 09:55:00, 01/11/2026, 9:30am",
    "Price: $1,299.99 (20% off) €5 £3 ¥100",
    "Emoji: 😀😃😄 👍🏽 👨‍👩‍👧‍👦 🇩🇪 ❤️", "😀", "a😀b", "Привет, как дела? Всё хорошо.", "Съешь же ещё этих мягких французских булок",
    "中文测试：你好，世界！", "日本語のテキストです。カタカナ、ひらがな。", "한국어 텍스트", "مرحبا بالعالم", "שלום עולם",
    "Ελληνικά κείμενα", "नमस्ते दुनिया", "ไทย ภาษา", "Ünïcödé naïve café résumé", "é combining à",
    "def f(x):\n    return x ** 2  # square\n", "for (int i = 0; i < n; ++i) { sum += a[i]; }",
    "<div class=\"x\">&amp; &lt;tag&gt;</div>", "{\"key\": [1, 2, {\"n\": null}], \"ok\": true}",
    "https://example.com/path?q=1&r=two#frag", "user@example.com", "snake_case camelCase PascalCase kebab-case",
    "...!!!???---***", "a.b.c", "!!!\n\n", "x  \n  y", "word nbsp　ideographic sep\u0085nel\u000bvt\u000cff",
    "zero​width﻿bom᠎mvs\u001cfs", "'s 'S 'ſ 'K", "mixed123abc456 abc123", "١٢٣ ٤٥٦ ①②③ Ⅻ ½",
    "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\nHi!<|im_end|>\n<|im_start|>assistant\n",
    "<|im_start|>assistant\n<think>\nLet me think.\n</think>\n\nThe answer is 42.<|im_end|>",
    "<|startoftext|><|im_start|>user\nWhat time is it?<|im_end|>", "<|im_start|><|im_end|><|im_start|>",
    "text<|im_end|>text", "<|im_start", "<|im_start|", "<|im_start|>>", "<<|im_end|>",
    "<|tool_call_start|>[get_weather(city=\"Berlin\")]<|tool_call_end|>", "<tools>\n{\"name\": \"x\"}\n</tools>",
    "<|audio_start|><|audio|><|audio_out|>", "<|reserved_7|><|mask|> <|reserved_0|>x<|reserved_12|>",
    "<|pad|><|endoftext|>", "<|image_start|><image><|image_end|>",
    "A long paragraph about tokenizers. " * 40,
    " " * 100, "a" * 300, "\U0001F600" * 50, "1" * 50, "\n" * 30, "-" * 64, "=" * 40 + "\n",
]


def esc(s):
    out = []
    for ch in s:
        o = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif unicodedata.category(ch)[0] in "CZ" and ch != " ":
            out.append("\\u%04x" % o if o < 0x10000 else "\\U%08x" % o)
        else:
            out.append(ch)
    return "".join(out)


def fuzz(rng, n):
    pool = list("abcXYZ 019'\n\t.,!?-_<|>") + ["  ", "'s", "'LL", " ", "é", "ж", "中", "😀", "́", "　",
                                                "<|im_start|>", "<|im_end|>", "<think>", "<|mask|>", "12345"]
    return ["".join(rng.choice(pool) for _ in range(rng.randint(1, 40))) for _ in range(n)]


def main():
    lfm3, d1, out = sys.argv[1:4]
    rng = random.Random(7)
    cases = CASES + fuzz(rng, 120)
    lines = []
    for name, path in [("lfm3", lfm3), ("d1", d1)]:
        t = Tokenizer.from_file(path)
        lines.append("S\t%s\t%d" % (name, t.get_vocab_size(with_added_tokens=True)))
        for s in cases:
            ids = t.encode(s, add_special_tokens=False).ids
            lines.append("E\t%s\t%s\t%s" % (name, esc(s), ",".join(map(str, ids))))
            for skip in (False, True):
                lines.append("D\t%s\t%s\t%d\t%s" % (name, ",".join(map(str, ids)), skip, esc(t.decode(ids, skip_special_tokens=skip))))
        # Pieces of characters: a cut emoji or Cyrillic letter decodes to U+FFFD.
        for s in ["😀👍🏽", "Привет", "中文"]:
            ids = t.encode(s, add_special_tokens=False).ids
            for k in range(1, len(ids)):
                lines.append("D\t%s\t%s\t0\t%s" % (name, ",".join(map(str, ids[:k])), esc(t.decode(ids[:k], skip_special_tokens=False))))
        for tok in ["<|im_start|>", "<|im_end|>", "<think>", "<|mask|>", "<|reserved_7|>", "Hello", "ĠHello", "Ċ", "nope-not-a-token"]:
            i = t.token_to_id(tok)
            lines.append("I\t%s\t%s\t%s" % (name, esc(tok), "" if i is None else i))
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


main()
