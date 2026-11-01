// A thin JNI over LiteRT's C API for OmniVoice (voice/OmniRt.kt): what LiteRT's Kotlin API (2.3.0) does not offer.
//  - a model from a byte range of a file (LiteRtCreateModelFromFd: mmapped, not copied), so the decoder, embedder and
//    audio encoder are read straight out of the 3.2 GB .litertlm;
//  - tensor buffers over memory of our own, handed to Kotlin as direct ByteBuffers (no copy per call), and one buffer
//    as both an input and an output of a call, so the decoder's KV and conv state are updated in place (LiteRT-LM's CPU
//    policy) instead of copied in and out on every step.
// libLiteRt.so is opened with dlopen: the build needs nothing of LiteRT's (litert_c_min.h declares what is used).

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "litert_c_min.h"

#define LOG_TAG "omni_rt"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Api {
    bool ok = false;
    std::string error;
#define FN(name) decltype(&::name) name = nullptr;
    FN(LiteRtCreateEnvironment)
    FN(LiteRtDestroyEnvironment)
    FN(LiteRtCreateModelFromFd)
    FN(LiteRtDestroyModel)
    FN(LiteRtGetNumModelSignatures)
    FN(LiteRtGetModelSignature)
    FN(LiteRtGetSignatureKey)
    FN(LiteRtGetNumSignatureInputs)
    FN(LiteRtGetSignatureInputName)
    FN(LiteRtGetSignatureInputTensorByIndex)
    FN(LiteRtGetNumSignatureOutputs)
    FN(LiteRtGetSignatureOutputName)
    FN(LiteRtGetSignatureOutputTensorByIndex)
    FN(LiteRtGetRankedTensorType)
    FN(LiteRtCreateOptions)
    FN(LiteRtDestroyOptions)
    FN(LiteRtSetOptionsHardwareAccelerators)
    FN(LiteRtAddOpaqueOptions)
    FN(LiteRtCreateOpaqueOptions)
    FN(LiteRtCreateCompiledModel)
    FN(LiteRtRunCompiledModel)
    FN(LiteRtDestroyCompiledModel)
    FN(LiteRtCreateTensorBufferFromHostMemory)
    FN(LiteRtDestroyTensorBuffer)
    FN(LiteRtGetStatusString)
#undef FN
};

Api &api() {
    static Api a = [] {
        Api r;
        void *lib = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) { r.error = std::string("dlopen libLiteRt.so: ") + dlerror(); return r; }
#define FN(name) \
        r.name = reinterpret_cast<decltype(r.name)>(dlsym(lib, #name)); \
        if (!r.name) { r.error = "libLiteRt.so has no " #name; return r; }
        FN(LiteRtCreateEnvironment)
        FN(LiteRtDestroyEnvironment)
        FN(LiteRtCreateModelFromFd)
        FN(LiteRtDestroyModel)
        FN(LiteRtGetNumModelSignatures)
        FN(LiteRtGetModelSignature)
        FN(LiteRtGetSignatureKey)
        FN(LiteRtGetNumSignatureInputs)
        FN(LiteRtGetSignatureInputName)
        FN(LiteRtGetSignatureInputTensorByIndex)
        FN(LiteRtGetNumSignatureOutputs)
        FN(LiteRtGetSignatureOutputName)
        FN(LiteRtGetSignatureOutputTensorByIndex)
        FN(LiteRtGetRankedTensorType)
        FN(LiteRtCreateOptions)
        FN(LiteRtDestroyOptions)
        FN(LiteRtSetOptionsHardwareAccelerators)
        FN(LiteRtAddOpaqueOptions)
        FN(LiteRtCreateOpaqueOptions)
        FN(LiteRtCreateCompiledModel)
        FN(LiteRtRunCompiledModel)
        FN(LiteRtDestroyCompiledModel)
        FN(LiteRtCreateTensorBufferFromHostMemory)
        FN(LiteRtDestroyTensorBuffer)
        FN(LiteRtGetStatusString)
#undef FN
        r.ok = true;
        return r;
    }();
    return a;
}

void throw_rt(JNIEnv *env, const std::string &msg) {
    LOGE("%s", msg.c_str());
    jclass c = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(c, msg.c_str());
}

std::string status_text(LiteRtStatus s) {
    const char *t = api().LiteRtGetStatusString ? api().LiteRtGetStatusString(s) : nullptr;
    return std::to_string(s) + (t ? std::string(" ") + t : std::string());
}

bool ready(JNIEnv *env) {
    if (api().ok) return true;
    throw_rt(env, api().error);
    return false;
}

struct Model {
    LiteRtModel model = nullptr;
    LiteRtCompiledModel compiled = nullptr;
};

struct Buffer {
    LiteRtTensorBuffer buffer = nullptr;
    void *mem = nullptr;
    size_t size = 0;
};

LiteRtSignature signature(JNIEnv *env, Model *m, jint sig) {
    LiteRtSignature s = nullptr;
    LiteRtStatus st = api().LiteRtGetModelSignature(m->model, (LiteRtParamIndex) sig, &s);
    if (st != 0) { throw_rt(env, "signature " + std::to_string(sig) + ": " + status_text(st)); return nullptr; }
    return s;
}

bool tensor_type(JNIEnv *env, Model *m, jint sig, jint index, jboolean output, LiteRtRankedTensorType *type) {
    LiteRtSignature s = signature(env, m, sig);
    if (!s) return false;
    LiteRtTensor t = nullptr;
    LiteRtStatus st = output ? api().LiteRtGetSignatureOutputTensorByIndex(s, (LiteRtParamIndex) index, &t)
                             : api().LiteRtGetSignatureInputTensorByIndex(s, (LiteRtParamIndex) index, &t);
    if (st == 0) st = api().LiteRtGetRankedTensorType(t, type);
    if (st != 0) { throw_rt(env, "tensor " + std::to_string(index) + ": " + status_text(st)); return false; }
    return true;
}

size_t element_bytes(int type) {
    switch (type) {
        case kLiteRtElementTypeUInt8: return 1;
        default: return 4; // float32, int32: every tensor these graphs pass in or out
    }
}

jobjectArray names(JNIEnv *env, Model *m, jint sig, bool output) {
    LiteRtSignature s = signature(env, m, sig);
    if (!s) return nullptr;
    LiteRtParamIndex n = 0;
    (output ? api().LiteRtGetNumSignatureOutputs : api().LiteRtGetNumSignatureInputs)(s, &n);
    jobjectArray out = env->NewObjectArray((jsize) n, env->FindClass("java/lang/String"), nullptr);
    for (LiteRtParamIndex i = 0; i < n; i++) {
        const char *name = nullptr;
        (output ? api().LiteRtGetSignatureOutputName : api().LiteRtGetSignatureInputName)(s, i, &name);
        env->SetObjectArrayElement(out, (jsize) i, env->NewStringUTF(name ? name : ""));
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeEnv(JNIEnv *env, jclass) {
    if (!ready(env)) return 0;
    LiteRtEnvironment e = nullptr;
    LiteRtStatus st = api().LiteRtCreateEnvironment(0, nullptr, &e);
    if (st != 0) { throw_rt(env, "environment: " + status_text(st)); return 0; }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeFreeEnv(JNIEnv *, jclass, jlong e) {
    if (e && api().ok) api().LiteRtDestroyEnvironment(reinterpret_cast<LiteRtEnvironment>(e));
}

// The graph at [offset, offset + size) of the file `fd`, compiled for the CPU (XNNPACK) with `threads` threads and,
// if `cache` is given, XNNPACK's weight cache in that file (packed weights mapped from it, once for every signature).
JNIEXPORT jlong JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeOpen(JNIEnv *env, jclass, jlong e, jint fd, jlong offset, jlong size,
                                              jint threads, jstring cache) {
    if (!ready(env)) return 0;
    auto *m = new Model();
    auto *lenv = reinterpret_cast<LiteRtEnvironment>(e);
    LiteRtStatus st = api().LiteRtCreateModelFromFd(lenv, fd, (size_t) offset, (size_t) size, &m->model);
    if (st != 0) { delete m; throw_rt(env, "model from fd: " + status_text(st)); return 0; }
    LiteRtOptions opts = nullptr;
    st = api().LiteRtCreateOptions(&opts);
    if (st == 0) st = api().LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorCpu);
    if (st == 0) {
        std::string toml = "num_threads = " + std::to_string(threads) + "\n";
        if (cache) {
            const char *c = env->GetStringUTFChars(cache, nullptr);
            toml += std::string("weight_cache_file_path = \"") + c + "\"\n";
            env->ReleaseStringUTFChars(cache, c);
        }
        char *payload = new char[toml.size() + 1];
        memcpy(payload, toml.c_str(), toml.size() + 1);
        LiteRtOpaqueOptions cpu = nullptr;
        st = api().LiteRtCreateOpaqueOptions("xnnpack", payload, [](void *p) { delete[] static_cast<char *>(p); }, &cpu);
        if (st == 0) st = api().LiteRtAddOpaqueOptions(opts, cpu);
        else delete[] payload;
    }
    if (st == 0) st = api().LiteRtCreateCompiledModel(lenv, m->model, opts, &m->compiled);
    if (opts) api().LiteRtDestroyOptions(opts);
    if (st != 0) {
        api().LiteRtDestroyModel(m->model);
        delete m;
        throw_rt(env, "compile: " + status_text(st));
        return 0;
    }
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeClose(JNIEnv *, jclass, jlong h) {
    auto *m = reinterpret_cast<Model *>(h);
    if (!m) return;
    if (m->compiled) api().LiteRtDestroyCompiledModel(m->compiled);
    if (m->model) api().LiteRtDestroyModel(m->model);
    delete m;
}

JNIEXPORT jobjectArray JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeSignatures(JNIEnv *env, jclass, jlong h) {
    auto *m = reinterpret_cast<Model *>(h);
    LiteRtParamIndex n = 0;
    api().LiteRtGetNumModelSignatures(m->model, &n);
    jobjectArray out = env->NewObjectArray((jsize) n, env->FindClass("java/lang/String"), nullptr);
    for (LiteRtParamIndex i = 0; i < n; i++) {
        LiteRtSignature s = signature(env, m, (jint) i);
        if (!s) return nullptr;
        const char *key = nullptr;
        api().LiteRtGetSignatureKey(s, &key);
        env->SetObjectArrayElement(out, (jsize) i, env->NewStringUTF(key ? key : ""));
    }
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeNames(JNIEnv *env, jclass, jlong h, jint sig, jboolean output) {
    return names(env, reinterpret_cast<Model *>(h), sig, output);
}

// [element type, dim 0, dim 1, ...] of one input or output.
JNIEXPORT jintArray JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeTensor(JNIEnv *env, jclass, jlong h, jint sig, jint index, jboolean output) {
    LiteRtRankedTensorType t{};
    if (!tensor_type(env, reinterpret_cast<Model *>(h), sig, index, output, &t)) return nullptr;
    std::vector<jint> v{t.element_type};
    for (unsigned i = 0; i < t.layout.rank; i++) v.push_back(t.layout.dimensions[i]);
    jintArray out = env->NewIntArray((jsize) v.size());
    env->SetIntArrayRegion(out, 0, (jsize) v.size(), v.data());
    return out;
}

// A host-memory buffer shaped as that input or output (zeroed, 64-byte aligned), for any signature's tensor of the
// same type and shape.
JNIEXPORT jlong JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeBuffer(JNIEnv *env, jclass, jlong h, jint sig, jint index, jboolean output) {
    LiteRtRankedTensorType t{};
    if (!tensor_type(env, reinterpret_cast<Model *>(h), sig, index, output, &t)) return 0;
    size_t n = element_bytes(t.element_type);
    for (unsigned i = 0; i < t.layout.rank; i++) n *= (size_t) t.layout.dimensions[i];
    auto *b = new Buffer();
    b->size = n;
    // XNNPACK may read a little past a tensor's end
    const size_t alloc = (n + 64 + 63) / 64 * 64;
    if (posix_memalign(&b->mem, 64, alloc) != 0) { delete b; throw_rt(env, "out of memory"); return 0; }
    memset(b->mem, 0, alloc);
    LiteRtStatus st = api().LiteRtCreateTensorBufferFromHostMemory(&t, b->mem, n, nullptr, &b->buffer);
    if (st != 0) {
        free(b->mem);
        delete b;
        throw_rt(env, "tensor buffer: " + status_text(st));
        return 0;
    }
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT jobject JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeBufferData(JNIEnv *env, jclass, jlong b) {
    auto *buf = reinterpret_cast<Buffer *>(b);
    return env->NewDirectByteBuffer(buf->mem, (jlong) buf->size);
}

JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeFreeBuffer(JNIEnv *, jclass, jlong b) {
    auto *buf = reinterpret_cast<Buffer *>(b);
    if (!buf) return;
    if (buf->buffer) api().LiteRtDestroyTensorBuffer(buf->buffer);
    free(buf->mem);
    delete buf;
}

// Runs signature `sig` with every input and output given, in the signature's order. A buffer may be both an input and
// an output (state updated in place).
JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_voice_OmniRt_nativeRun(JNIEnv *env, jclass, jlong h, jint sig, jlongArray inputs, jlongArray outputs) {
    auto *m = reinterpret_cast<Model *>(h);
    const jsize ni = env->GetArrayLength(inputs), no = env->GetArrayLength(outputs);
    std::vector<jlong> ih(ni), oh(no);
    env->GetLongArrayRegion(inputs, 0, ni, ih.data());
    env->GetLongArrayRegion(outputs, 0, no, oh.data());
    std::vector<LiteRtTensorBuffer> in(ni), out(no);
    for (jsize i = 0; i < ni; i++) in[i] = reinterpret_cast<Buffer *>(ih[i])->buffer;
    for (jsize i = 0; i < no; i++) out[i] = reinterpret_cast<Buffer *>(oh[i])->buffer;
    LiteRtStatus st = api().LiteRtRunCompiledModel(m->compiled, (LiteRtParamIndex) sig, in.size(), in.data(), out.size(), out.data());
    if (st != 0) throw_rt(env, "run signature " + std::to_string(sig) + ": " + status_text(st));
}

} // extern "C"
