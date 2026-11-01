# The on-device model pool (LFMP-8)

Since 1 November 2026 every model on the phone runs on LiteRT. llama.cpp and its GGUF files are gone from the phone:
the owner found them slow and hard on the battery. When Inkling needs a model, it tries in this order:

1. **The homelab server**, when Edge mode is on and the server answers (docs/spec.md, Edge mode).
2. **LiteRT on the phone**.
3. For the small classifiers only: the phone's simple rules.

## Which model does what

| Job | Model and file | Runtime | Runs on | On the phone |
|---|---|---|---|---|
| Chat, tools, writing, organizer jobs | LFM3-1.7B, `LFM3-1.7B_mixed_int4_ekv4096.litertlm` | LiteRT-LM | GPU (OpenCL), else CPU | 1.44 GB |
| Dictation, read-aloud, Talk | LFM3-Omni-2B, `LFM3-Omni-2B_int8.litertlm` plus its voice graphs | LiteRT (OmniVoice) | CPU | 3.23 GB + 0.79 GB |
| Photos, short videos, documents | LFM3-VL-2B, `LFM3-VL-2B_int4.litertlm` | LiteRT-LM | GPU, else CPU | 2.07 GB |
| Capture sorting, Talk intent, sensitive tag (when the server is away) | d1-omni-600M (see below) | LiteRT | CPU | see below |
| Search by meaning (LFMP-10) | EmbeddingGemma 2 text 270m, `embeddinggemma-2-text-270m.litertlm` | LiteRT-LM (EmbeddingEngine) | CPU | 165 MB; about 330 MB in memory |
| Token counts, Omni's prompts | LFM3's `tokenizer.json` | `BpeTokenizer` (Kotlin) | CPU | 18 MB |

All files come from [litert-community](https://huggingface.co/litert-community) on Hugging Face. Each is pinned in
`ModelCatalog.kt` and `ipc/PinnedFiles.kt` by repo revision, path, size and SHA-256. Inkling Net downloads them; it
resumes a cut download and checks the hash before the file is used. No model file is ever committed.

## Rules

- **The GPU first.** Auto puts chat and VL on the GPU wherever `libOpenCL.so` loads. The CPU is used when there is no
  GPU (the emulator), when the GPU fails its load or check questions, or when the Benchmark finds the CPU at least 10%
  quicker. Omni's voice graphs run on the CPU: the depth decoder at fp16 on a phone GPU is not proven (its card).
- **One engine per conversation.** LFM3's short convolutions carry state from one LiteRT-LM conversation into the
  next (LiteRT-LM issue #3165). A chat that extends the last prompt keeps its session. Any other prompt starts a
  fresh engine. So does every picture question.
- **Context.** The chat file holds 4,096 tokens. VL holds 2,048, which is room for 5 pictures: a video is cut to 5
  frames, spread over the clip. Omni holds 4,096.
- **Pictures.** VL takes 512×512 only. Inkling scales each picture to that size itself, with filtering. LiteRT-LM's own
  stretch is cruder.
- **Heat.** While the phone is hot, a model loaded on the CPU takes fewer threads. The GPU has no thread count.
- **Old files.** GGUF files from before LFMP-8 are listed in Settings → Models, with one button to delete them.

## Not used, and why

- **LFM3-Audio-2B-LiteRT.** It is small (1.53 GB, int4) and fast at speech on its card. But it has no reference
  speech loop to port: its card leaves the loop to the app. Omni covers voice with one model. Worth a follow-up if
  Omni's speech is too slow on the Pixel.
- **LFM3-1.7B int8 files and the 2,048-token caches.** The mixed int4 file with a 4,096-token cache is kept, as
  before (docs/acceleration.md).
- **LFM3-VL-2B float16.** About 2.5 times slower on a phone GPU, and 4.4 GB.
- **d1-3B.** Desktop only: a 12 GB Galaxy S26 ran out of memory (its card).

## Licence

EmbeddingGemma 2 (search by meaning, docs/embeddings.md) is Apache-2.0.

Every file here is under the LFM Open License v1.0. Section 5 only limits commercial use by an organisation with
$10M or more a year in revenue. A free, open-source app used by one person is within it. The runtimes, LiteRT-LM
0.19.0 and LiteRT 2.3.0, are Apache-2.0. LiteRT's `ai-delivery` dependency (Play Store delivery of NPU models) is
excluded, so there are no Play Services. `checkDebugDependencies` lists the two runtimes, and `checkDebugNoNetwork`
still holds.

## Measured

The owner chose to switch without a benchmark: GGUF on the phone was slow and battery-hungry. The figures below are
the model cards' own phone numbers, not ours:

| Model | Phone | Decode tok/s | First token |
|---|---|---:|---:|
| LFM3-1.7B mixed int4 | Galaxy S26 GPU | 32.4 | 0.81 s (1,024-token prompt) |
| LFM3-VL-2B int4 | Galaxy S26 GPU | 37.9 | 0.86 s (image + question) |
| LFM3-Omni-2B int8 | Galaxy S26 GPU | 27.0 | 0.93 s |

Omni's speech took 0.75–1.6 s per second of speech on the card's phones, so it can run slower than real time.
Talk's jitter buffer waits for it. Settings → Models → Benchmark and the voice benchmark measure the owner's own
phone.
