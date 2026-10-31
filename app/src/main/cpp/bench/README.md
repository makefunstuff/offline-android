# talk_bench: speech stages on a desktop

Checks `talk.cpp` outside the app: Omni speaks a question, hears it back (ASR), then answers it in a two-turn
conversation, and prints each stage's time. Not built into the app.

    cmake -G Ninja -S app/src/main/cpp/llama.cpp -B /tmp/hb -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
          -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF && ninja -C /tmp/hb mtmd llama
    C=app/src/main/cpp
    g++ -O2 -std=c++17 -I$C -I$C/llama.cpp/include -I$C/llama.cpp/ggml/include -I$C/llama.cpp/tools/mtmd \
        $C/bench/talk_bench.cpp $C/talk.cpp -o talk_bench \
        -Wl,--start-group $(find /tmp/hb -name '*.a') -Wl,--end-group -fopenmp -lpthread
    taskset -c 0-5 ./talk_bench LFM3-Omni-2B-Q4_0.gguf mmproj-LFM3-Omni-2B-Q4_K_M.gguf 5 /tmp/out [codec_thread] [codec_batch]

`NOCACHE=1` reads the whole conversation every turn, as the code before LP-30 did; its audio then matches that code
sample for sample (checked: TTS and both turns, max difference 0.0).

Ryzen 5 9600X, 6 cores, 5 threads, 31 Oct 2026 (one speech frame is 80 ms of audio):

| Stage | Time |
|---|---|
| Backbone step per frame | 18.9 ms (75% of a frame) |
| Depthformer, 3 passes + feedback | 5.9 ms (22%) |
| Codec + ISTFT | 1.6 ms (6%) |
| Text token | 18.4 ms |
| Turn 1 read-in, before / with the cache | 214 ms (93 tokens) / 73 ms (22 new) |
| Turn 2 read-in, before / with the cache | 286 ms (134 tokens) / 136 ms (51 new) |
| Speech made, real-time factor | 0.39–0.41 |

The codec on its own thread (`codec_thread 1`) slowed it: the codec went from 1.6 to 4.8 ms a frame and the backbone
from 18.9 to 20.2 ms, as two thread teams shared six cores. So the codec runs inline.
