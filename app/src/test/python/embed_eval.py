"""Semantic search check for docs/embeddings.md: keyword search (Inkling's FtsQuery rule) against EmbeddingGemma 2
on LiteRT-LM, and the hybrid of both, on the made-up notebook in embed_eval.tsv. Desktop CPU, not the phone.

    python -I embed_eval.py MODEL.litertlm EVAL_TSV [DIM ...] [--golden OUT_TSV]

Needs `pip install litert-lm==0.19.0`. Prints recall@5, MRR and ms per note for each output size. --golden also
writes the 256-d vectors of every note and query (N id v1 v2 ..., Q query v1 v2 ...) for SemanticSearchTest.
"""
import re
import sys
import time

import litert_lm as L

args = sys.argv[1:]
golden = None
if "--golden" in args:
    i = args.index("--golden")
    golden = args[i + 1]
    del args[i:i + 2]
model, tsv = args[0], args[1]
dims = [int(d) for d in args[2:]] or [768, 256]
notes, queries = [], []
for line in open(tsv, encoding="utf-8"):
    if line.startswith("#") or not line.strip():
        continue
    f = line.rstrip("\n").split("\t")
    if f[0] == "N":
        notes.append((int(f[1]), f[3], f[4]))
    else:
        queries.append((f[1], [int(x) for x in f[2].split(",")]))

WORD = re.compile(r"[^\w]+")


def words(s):
    return [w for w in WORD.split(s.lower()) if w]


def keyword(q):
    """FtsQuery: every word as a prefix, all required, newest first (here: file order)."""
    ws = words(q)[:8]
    hits = []
    for nid, t, b in notes:
        toks = words(t + " " + b)
        if all(any(tok.startswith(w) for tok in toks) for w in ws):
            hits.append(nid)
    return hits


def doc_text(t, b):
    return f"title: {t} | text: {b}"


def query_text(q):
    return f"task: search result | query: {q}"


def rrf(*lists, k=60):
    """Reciprocal rank fusion, as HybridSearch.kt does."""
    score = {}
    for lst in lists:
        for r, nid in enumerate(lst):
            score[nid] = score.get(nid, 0.0) + 1.0 / (k + r + 1)
    return sorted(score, key=lambda n: -score[n])


def metrics(rank_fn):
    rec, mrr = 0.0, 0.0
    for q, rel in queries:
        ranked = rank_fn(q)
        rec += len(set(ranked[:5]) & set(rel)) / len(rel)
        mrr += next((1.0 / (i + 1) for i, n in enumerate(ranked) if n in rel), 0.0)
    return rec / len(queries), mrr / len(queries)


t0 = time.perf_counter()
eng = L.EmbeddingEngine(model, backend=L.Backend.CPU(), max_input_length=256)
eng.__enter__() if hasattr(eng, "__enter__") else None
print(f"load {1000 * (time.perf_counter() - t0):.0f} ms")
r, m = metrics(keyword)
print(f"keyword  recall@5 {r:.2f}  MRR {m:.2f}  (no hit for {sum(1 for q, _ in queries if not keyword(q))} of {len(queries)} queries)")
for dim in dims:
    opt = L.EmbeddingOptions(normalize=True, output_size=dim)
    eng.compute_embedding(doc_text("warm", "up"), options=opt)
    t0 = time.perf_counter()
    vecs = {nid: eng.compute_embedding(doc_text(t, b), options=opt).embedding for nid, t, b in notes}
    per = 1000 * (time.perf_counter() - t0) / len(notes)
    qv = {}
    t0 = time.perf_counter()
    for q, _ in queries:
        qv[q] = eng.compute_embedding(query_text(q), options=opt).embedding
    perq = 1000 * (time.perf_counter() - t0) / len(queries)

    def sem(q, dim=dim):
        v = qv[q]
        return sorted(vecs, key=lambda n: -sum(a * b for a, b in zip(v, vecs[n])))

    r, m = metrics(sem)
    rh, mh = metrics(lambda q: rrf(keyword(q), sem(q)))
    print(f"{dim}d  {per:.1f} ms/note  {perq:.1f} ms/query  semantic recall@5 {r:.2f} MRR {m:.2f}  "
          f"hybrid recall@5 {rh:.2f} MRR {mh:.2f}  {4 * dim} bytes/note")

if golden:
    opt = L.EmbeddingOptions(normalize=True, output_size=256)
    with open(golden, "w", encoding="utf-8") as out:
        out.write("# 256-d EmbeddingGemma 2 text 270m vectors of embed_eval.tsv, from embed_eval.py --golden\n")
        for nid, t, b in notes:
            v = eng.compute_embedding(doc_text(t, b), options=opt).embedding
            out.write("N\t%d\t%s\n" % (nid, " ".join("%.5f" % x for x in v)))
        for q, _ in queries:
            v = eng.compute_embedding(query_text(q), options=opt).embedding
            out.write("Q\t%s\t%s\n" % (q, " ".join("%.5f" % x for x in v)))
