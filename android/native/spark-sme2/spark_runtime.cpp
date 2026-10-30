#include <jni.h>
#include <atomic>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <cstdlib>
#include <dlfcn.h>
#include <sched.h>
#include <cmath>
#include <limits>

namespace {
std::mutex mutex;
std::atomic<bool> cancelled{false};
llama_model* model = nullptr;
llama_context* context = nullptr;
std::string loadedPath;
std::string metrics = "{}";
int decodeThreads = 4;
int prefillThreads = 4;
int smeThreads = 2;
int affinityMask = 0;
#if defined(__aarch64__)
int contextOptions = 33; // CPU FlashAttention AUTO and buffered weight loading.
#else
int contextOptions = 0;
#endif
bool prefixCacheEnabled = true;
std::vector<llama_token> cachedPrompt;
const char* jsonGrammar = R"grammar(
root ::= ws (object | array)
value ::= object | array | string | number | ("true" | "false" | "null") ws
object ::= "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
array ::= "[" ws (value ("," ws value)*)? "]" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
number ::= "-"? ("0" | [1-9] [0-9]{0,15}) ("." [0-9]+)? ([eE] [-+]? [0-9]{1,16})? ws
ws ::= [ \t\n\r]*
)grammar";
using Clock = std::chrono::steady_clock;
long elapsed(Clock::time_point t) { return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-t).count(); }

// JNI's modified UTF-8 cannot represent supplementary characters in model output.
std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return "";
    jclass cls = env->FindClass("java/lang/String");
    jmethodID method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray)env->CallObjectMethod(value, method, charset);
    std::string out(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, out.size(), reinterpret_cast<jbyte*>(out.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return out;
}
jstring javaString(JNIEnv* env, const std::string& value) {
    jbyteArray bytes = env->NewByteArray(value.size());
    env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte*>(value.data()));
    jclass cls = env->FindClass("java/lang/String");
    jmethodID init = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = (jstring)env->NewObject(cls, init, bytes, charset);
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return out;
}
void release() {
    if (context) llama_free(context);
    if (model) llama_model_free(model);
    context = nullptr; model = nullptr; loadedPath.clear(); cachedPrompt.clear();
}
void log(ggml_log_level level, const char* text, void*) {
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "SparkNative", text);
}
std::string generate(const std::string& path, const std::string& input, int limit) {
    const auto start = Clock::now();
    cancelled.store(false);
    if (affinityMask) {
        cpu_set_t cpus; CPU_ZERO(&cpus);
        for (int i = 0; i < 8; ++i) if (affinityMask & (1 << i)) CPU_SET(i, &cpus);
        if (sched_setaffinity(0, sizeof(cpus), &cpus) != 0) throw std::runtime_error("CPU affinity request failed.");
    }
    long loadMs = 0;
    if (loadedPath != path || !context) {
        release();
        setenv("GGML_KLEIDIAI_SME", std::to_string(smeThreads).c_str(), 1);
        setenv("GGML_TOTAL_THREADS", std::to_string(std::max(decodeThreads, prefillThreads)).c_str(), 1);
        if (contextOptions & 16) {
            setenv("GGML_VK_DISABLE_GRAPH_OPTIMIZE", "1", 1);
            setenv("GGML_VK_DISABLE_ASYNC", "1", 1);
            setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
            setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
            setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);
        }
        llama_log_set(log, nullptr);
        ggml_log_set(log, nullptr);
#if defined(__aarch64__)
        Dl_info info = {};
        if (dladdr(reinterpret_cast<void*>(&generate), &info) && info.dli_fname) {
            std::string library(info.dli_fname);
            const auto directory = library.substr(0, library.find_last_of('/'));
            if (contextOptions & 4) ggml_backend_load_all_from_path(directory.c_str());
            else ggml_backend_load_cpu_from_path(directory.c_str());
        }
#endif
        llama_backend_init();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = (contextOptions & 4) ? 999 : 0;
        mp.load_mode = (contextOptions & 64) ? LLAMA_LOAD_MODE_DIRECT_IO : (contextOptions & 32) ? LLAMA_LOAD_MODE_NONE : LLAMA_LOAD_MODE_MMAP;
        model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) throw std::runtime_error("Spark-X2.5 GGUF load failed. See SparkNative log.");
        char architecture[64] = {};
        llama_model_meta_val_str(model, "general.architecture", architecture, sizeof(architecture));
        if (std::string(architecture) != "spark2_5") {
            release(); throw std::runtime_error("Expected the official Spark spark2_5 model architecture.");
        }
        auto cp = llama_context_default_params();
        cp.abort_callback = [](void*) { return cancelled.load(); };
        cp.n_ctx = 4096; cp.n_batch = (contextOptions & 2) ? 128 : 256; cp.n_ubatch = 128;
        cp.n_threads = decodeThreads; cp.n_threads_batch = prefillThreads;
        cp.flash_attn_type = (contextOptions & 1) ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.offload_kqv = (contextOptions & 4) != 0;
        cp.op_offload = (contextOptions & 4) != 0;
        context = llama_init_from_model(model, cp);
        if (!context) { release(); throw std::runtime_error("Spark context initialization failed."); }
        loadedPath = path;
        loadMs = elapsed(start);
    }
    // Exact single-turn expansion of the official tokenizer chat_template with enable_thinking=false.
    // Keep task history in the application and reuse only an identical token prefix.
    const std::string bos = u8"<｜start▁of▁sentence｜>";
    const std::string eos = u8"<｜end▁of▁sentence｜>";
    const std::string prompt = bos + "<|System|>\nyou are a helpful assistant." + eos
        + bos + "<|User|>" + input + eos + bos + "<|Bot|></think>";
    const llama_vocab* vocab = llama_model_get_vocab(model);
    int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    limit = std::max(16, std::min(limit, 1024));
    if (count <= 0 || count > 2944 || count + limit >= 4096) throw std::runtime_error("INPUT_TOKEN_LIMIT");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0)
        throw std::runtime_error("Spark tokenization failed.");
    int shared = 0;
    if (prefixCacheEnabled) {
        while (shared < count - 1 && shared < (int)cachedPrompt.size() && tokens[shared] == cachedPrompt[shared]) ++shared;
    }
    auto memory = llama_get_memory(context);
    if (shared == 0 || !llama_memory_seq_rm(memory, 0, shared, -1)) {
        llama_memory_clear(memory, true); shared = 0;
    }
    cachedPrompt.clear();
    llama_perf_context_reset(context);
    const auto prefill = Clock::now();
    const int batchSize = (contextOptions & 2) ? 128 : 256;
    for (int offset = shared; offset < count; offset += batchSize) {
        int n = std::min(batchSize, count - offset);
        if (llama_decode(context, llama_batch_get_one(tokens.data()+offset, n)) != 0)
            throw std::runtime_error("Spark prompt evaluation failed.");
    }
    cachedPrompt = tokens;
    llama_synchronize(context);
    long prefillMs = elapsed(prefill);
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> grammar(llama_sampler_init_grammar(vocab, jsonGrammar, "root"), llama_sampler_free);
    if (!grammar) throw std::runtime_error("Cannot initialize the JSON output constraint.");
    const int vocabularySize = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> candidates(vocabularySize);
    const auto decodeStart = Clock::now();
    std::string output;
    int generated = 0;
    bool ended = false;
    long samplingMs = 0;
    int grammarFallbacks = 0;
    int jsonDepth = 0;
    bool jsonQuoted = false, jsonEscaped = false;
    for (; generated < limit; ++generated) {
        if (cancelled.load()) throw std::runtime_error("GENERATION_CANCELLED");
        llama_synchronize(context);
        const auto samplingStart = Clock::now();
        const float* logits = llama_get_logits_ith(context, -1);
        llama_token token = std::max_element(logits, logits + vocabularySize) - logits;
        llama_token_data single = {token, logits[token], 0.0f};
        llama_token_data_array one = {&single, 1, -1, false};
        llama_sampler_apply(grammar.get(), &one);
        if (!std::isfinite(single.logit)) {
            ++grammarFallbacks;
            for (int i = 0; i < vocabularySize; ++i) candidates[i] = {i, logits[i], 0.0f};
            llama_token_data_array all = {candidates.data(), candidates.size(), -1, false};
            llama_sampler_apply(grammar.get(), &all);
            auto best = std::max_element(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) { return a.logit < b.logit; });
            if (!std::isfinite(best->logit)) throw std::runtime_error("No token satisfies the JSON grammar.");
            token = best->id;
        }
        llama_sampler_accept(grammar.get(), token);
        samplingMs += elapsed(samplingStart);
        if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
        char piece[512];
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (n < 0) throw std::runtime_error("Spark output token is too large.");
        output.append(piece, n);
        for (int i = 0; i < n; ++i) {
            const char c = piece[i];
            if (jsonQuoted) {
                if (jsonEscaped) jsonEscaped = false;
                else if (c == '\\') jsonEscaped = true;
                else if (c == '"') jsonQuoted = false;
            } else if (c == '"') jsonQuoted = true;
            else if (c == '{' || c == '[') ++jsonDepth;
            else if (c == '}' || c == ']') {
                --jsonDepth;
                if (jsonDepth == 0) ended = true;
            }
        }
        if (ended) { ++generated; break; }
        if (generated + 1 < limit && llama_decode(context, llama_batch_get_one(&token, 1)) != 0)
            throw std::runtime_error("Spark generation failed.");
    }
    std::ostringstream report;
    report << "{\"model\":\"Spark-X2.5-4B-Q4_K_M\",\"runtime\":\"llama.cpp-b10828+29219\","
           << "\"threads\":" << decodeThreads << ",\"threads_batch\":" << prefillThreads << ",\"sme_threads\":" << smeThreads << ","
           << "\"affinity_mask\":" << affinityMask << ","
           << "\"context_options\":" << contextOptions << ","
           << "\"load_ms\":" << loadMs << ",\"prefill_ms\":" << prefillMs
           << ",\"decode_ms\":" << elapsed(decodeStart) << ",\"total_ms\":" << elapsed(start)
           << ",\"prompt_tokens\":" << count << ",\"generated_tokens\":" << generated
           << ",\"cached_prompt_tokens\":" << shared << ",\"evaluated_prompt_tokens\":" << count - shared
           << ",\"sampling_ms\":" << samplingMs << ",\"grammar_fallbacks\":" << grammarFallbacks
           << ",\"finished\":" << (ended ? "true" : "false") << "}";
    metrics = report.str();
    __android_log_print(ANDROID_LOG_INFO, "SparkNative", "inference_metrics %s", metrics.c_str());
    if (!ended) {
        // Private diary/model text must never enter Android logs.
        throw std::runtime_error("Spark output reached its token limit; incomplete response rejected.");
    }
    return output;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeGenerate(
    JNIEnv* env, jclass, jstring path, jstring prompt, jint maxTokens) {
    std::lock_guard<std::mutex> lock(mutex);
    try { return javaString(env, generate(utf8(env,path), utf8(env,prompt), maxTokens)); }
    catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what()); return nullptr;
    }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeMetrics(JNIEnv* env,jclass) {
    std::lock_guard<std::mutex> lock(mutex); return env->NewStringUTF(metrics.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeRelease(JNIEnv*,jclass) {
    std::lock_guard<std::mutex> lock(mutex); release();
}
extern "C" JNIEXPORT void JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeConfigure(JNIEnv* env,jclass,jint decode,jint prefill,jint sme,jboolean cache,jint affinity,jint options) {
    std::lock_guard<std::mutex> lock(mutex);
    if (model || decode < 1 || decode > 8 || prefill < 1 || prefill > 8 || sme < 0 || sme > 8) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Configure once before loading the model."); return;
    }
    decodeThreads = decode; prefillThreads = prefill; smeThreads = sme;
    prefixCacheEnabled = cache;
    affinityMask = affinity;
    contextOptions = options;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeSystemInfo(JNIEnv* env,jclass) {
    return env->NewStringUTF(llama_print_system_info());
}
extern "C" JNIEXPORT jstring JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeConfiguration(JNIEnv* env,jclass) {
    std::ostringstream out;
    out << "{\"decode_threads\":" << decodeThreads << ",\"prefill_threads\":" << prefillThreads
        << ",\"sme_threads\":" << smeThreads << ",\"affinity_mask\":" << affinityMask
        << ",\"context_options\":" << contextOptions
        << ",\"prefix_cache\":" << (prefixCacheEnabled ? "true" : "false") << "}";
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeCancel(JNIEnv*,jclass) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL Java_com_blackbox_diary_inference_SparkSmeBridge_nativeSetThreads(JNIEnv*,jclass,jint count) {
    std::lock_guard<std::mutex> lock(mutex);
    decodeThreads = prefillThreads = count == 2 ? 2 : 4;
    if (context) llama_set_n_threads(context, decodeThreads, prefillThreads);
}
