# On-device embeddings (LFMP-10)

A short note for any Android app that wants search by meaning on the phone, with no network and no Play Services.
Wren's code: `llm/Embedder.kt` (model), `data/SemanticIndex.kt` (index), `data/HybridSearch.kt` (search).

## Runtime and model

- **Runtime:** LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.19.0` (Apache-2.0), class `EmbeddingEngine`.
  The same library Wren already uses for chat; no extra native code. Its `ai-delivery` (Play) part is not needed.
- **Model:** [litert-community/embeddinggemma-2-text-270m-litert-lm](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm),
  file `embeddinggemma-2-text-270m.litertlm`, 164,626,432 bytes, SHA-256
  `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb`, revision
  `9be6e8b90982095dc05c2bd162e4b954ee4dbac7`. Downloaded at run time, never committed.
- **Licence:** Apache-2.0, both the LiteRT file and google/embeddinggemma-2 (not the Gemma terms). Fine for a public,
  free app.
- **Only LiteRT.** No GGUF on the phone: slow and hard on the battery.

## Which variant

All three EmbeddingGemma 2 files share the same text part (130M transformer + 140M embedder, int4), so text quality is
the same; the bigger ones add picture (170M) and sound (300M) encoders.

| File | Download | Text | Pictures | Pick |
|---|---:|:-:|:-:|---|
| text 270m | 165 MB | ✓ | | **Yes**: notes are text |
| text-vision 440m | 388 MB | ✓ | ✓ | Only for photo search |
| 740m | 485 MB | ✓ | ✓ + audio, video | No |

There are also chip-specific files (`_Google_Tensor_G5` for the Pixel 10, Qualcomm, MediaTek) for the NPU/TPU: 8.3 ms
on a Pixel 11 TPU on the card. They need the vendor's dispatch library; Wren uses the CPU file. A follow-up if the
CPU is ever too slow.

## Numbers

| | Value | Where |
|---|---:|---|
| One note, CPU | 14.4 ms | desktop CPU, our test (256 tokens max) |
| One note, CPU | 27.1 ms | Galaxy S26 (model card, 128 tokens) |
| One note, TPU | 8.3 ms | Pixel 11 Pro (model card, G-series file) |
| Load | 0.1 s | desktop, from cache |
| Memory | 321 MB | desktop, peak RSS; 334 MB on S26 (card) |
| Index on disk | 1 KB a note | 256 floats; 10,000 notes = 10 MB |

Retrieval on a made-up notebook (40 notes, 30 questions worded unlike the notes,
`app/src/test/resources/embed_eval.tsv`, run with `app/src/test/python/embed_eval.py`):

| Search | Recall@5 | MRR |
|---|---:|---:|
| Keywords (all words must match) | 0.03 | 0.03 |
| Meaning, 768 numbers | 0.98 | 0.98 |
| Meaning, 256 numbers (used) | 0.98 | 0.98 |
| Meaning, 128 numbers | 0.95 | 0.93 |
| Hybrid, 256 (used) | 0.98 | 0.98 |

The owner's phone: Settings → Models → Search by meaning shows notes indexed, index size, ms a note and the last
search's time. Debug builds: `adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_EMBED --es q "…"` (log tag `lfm-embed`).

## API shape

```kotlin
val engine = EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, backend = Backend.CPU(threads),
    cacheDir = cacheDir, maxInputLength = 256)).apply { initialize() }
val options = EmbeddingOptions(normalize = true, outputSize = 256)      // Matryoshka: 128, 256, 512 or 768
fun embed(text: String): FloatArray = engine.computeEmbedding(listOf(InputData.Text(text)), options).embedding
engine.close()
```

- **Prompts matter.** Documents: `title: {title or "none"} | text: {body}`. Queries: `task: search result | query:
  {query}`. Other tasks (classification, clustering, similarity) have their own prefixes on the model card.
- **Score:** vectors are unit length, so similarity is the dot product. Scores bunch up (right note 0.61–0.86,
  unrelated top hits 0.57–0.68), so keep matches at least 0.60 and within 0.07 of the best rather than one fixed cut.
- **Hybrid:** fuse keyword and meaning lists by reciprocal rank (`1/(60 + rank)` summed), not by raw scores.
- **Index:** store `(id, model, textHash, vector bytes)` next to your data; re-embed only when the hash changes, drop
  deleted ids, and keep the vectors in memory for brute-force search (fine to tens of thousands of items).
- **Threads:** one call at a time per engine; batch the backlog in small groups so a search waits at most one batch.
