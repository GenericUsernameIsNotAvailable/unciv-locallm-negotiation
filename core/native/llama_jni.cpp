// High-speed JNI bridge connecting JniLlamaCppBridge.kt to llama.cpp on Android (arm64-v8a), iOS (arm64), and Desktop.
// Optimised for sub-3-second mobile inference via:
// 1. Persistent llama_context across turns (no 150 MB KV-cache reallocation per message)
// 2. Prefix KV-cache reuse via llama_memory_seq_rm (skips re-evaluating the system prompt on follow-up turns)
// 3. 6-thread big-core execution pool + large batch prefill
// 4. Early stopping on turn-end markers and 2-3 sentence completion
#include <jni.h>
#include <algorithm>
#include <string>
#include <thread>
#include <vector>
#include "llama.h"

struct UncivLlamaHolder {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    int contextSize = 2048;
    int nThreads = 6;
    std::vector<llama_token> cachedTokens;
};

static int computeOptimalThreads() {
    unsigned int hw = std::thread::hardware_concurrency();
    if (hw >= 8) return 6;
    if (hw >= 4) return static_cast<int>(hw);
    return 4;
}

static bool hasStopMarker(const std::string& out, size_t& cutPos) {
    static const char* markers[] = {
        "<|im_end|>", "<end_of_turn>", "<|eot_id|>", "<|turn|>", "<turn|>",
        "\nUser:", "\nPlayer:", "\nHuman:", nullptr
    };
    for (int i = 0; markers[i] != nullptr; ++i) {
        size_t pos = out.find(markers[i]);
        if (pos != std::string::npos) {
            cutPos = pos;
            return true;
        }
    }
    return false;
}

static int countCompletedSentences(const std::string& out) {
    int count = 0;
    for (size_t i = 0; i < out.size(); ++i) {
        char c = out[i];
        if (c == '.' || c == '!' || c == '?') {
            if (i + 1 == out.size() || out[i + 1] == ' ' || out[i + 1] == '\n' || out[i + 1] == '"') {
                ++count;
            }
        }
    }
    return count;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_unciv_logic_commitments_llm_JniLlamaCppBridge_nativeLoadModel(
    JNIEnv* env, jclass, jstring modelPathJ, jint contextSize, jint gpuLayers
) {
    llama_backend_init();
    const char* path = env->GetStringUTFChars(modelPathJ, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpuLayers;
    // mmap enabled by default
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPathJ, path);
    if (!model) return 0;

    int ctxLen = contextSize > 0 ? contextSize : 2048;
    int threads = computeOptimalThreads();

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = ctxLen;
    cparams.n_batch = ctxLen;
    cparams.n_ubatch = 512;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    return reinterpret_cast<jlong>(new UncivLlamaHolder{model, ctx, ctxLen, threads, {}});
}

JNIEXPORT void JNICALL
Java_com_unciv_logic_commitments_llm_JniLlamaCppBridge_nativeFreeModel(
    JNIEnv*, jclass, jlong handle
) {
    auto* holder = reinterpret_cast<UncivLlamaHolder*>(handle);
    if (!holder) return;
    if (holder->ctx) llama_free(holder->ctx);
    if (holder->model) llama_model_free(holder->model);
    delete holder;
}

JNIEXPORT jstring JNICALL
Java_com_unciv_logic_commitments_llm_JniLlamaCppBridge_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring promptJ, jint maxTokens, jfloat temperature
) {
    auto* holder = reinterpret_cast<UncivLlamaHolder*>(handle);
    if (!holder || !holder->model || !holder->ctx) return env->NewStringUTF("");

    const char* promptChars = env->GetStringUTFChars(promptJ, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(promptJ, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(holder->model);
    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) return env->NewStringUTF("");

    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), tokens.data(), tokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }

    llama_context* ctx = holder->ctx;
    llama_memory_t mem = llama_get_memory(ctx);

    if (n_prompt + maxTokens + 32 > holder->contextSize) {
        llama_memory_clear(mem, false);
        holder->cachedTokens.clear();
    }

    size_t commonPrefix = 0;
    const size_t maxPrefix = std::min(holder->cachedTokens.size(), tokens.size());
    while (commonPrefix < maxPrefix && holder->cachedTokens[commonPrefix] == tokens[commonPrefix]) {
        ++commonPrefix;
    }
    if (commonPrefix >= tokens.size()) {
        commonPrefix = tokens.size() > 0 ? tokens.size() - 1 : 0;
    }

    bool decodedOk = false;
    if (commonPrefix > 16) {
        if (llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(commonPrefix), -1)) {
            llama_batch deltaBatch = llama_batch_get_one(
                tokens.data() + commonPrefix,
                static_cast<int32_t>(tokens.size() - commonPrefix)
            );
            if (llama_decode(ctx, deltaBatch) == 0) {
                decodedOk = true;
            }
        }
    }

    if (!decodedOk) {
        llama_memory_clear(mem, false);
        llama_batch fullBatch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
        if (llama_decode(ctx, fullBatch) != 0) {
            holder->cachedTokens.clear();
            return env->NewStringUTF("");
        }
    }

    holder->cachedTokens = tokens;

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    const int tokenCap = (maxTokens > 0 && maxTokens <= 140) ? maxTokens : 88;
    std::string out;
    out.reserve(320);

    for (int i = 0; i < tokenCap; ++i) {
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);

        size_t cutPos = std::string::npos;
        if (hasStopMarker(out, cutPos)) {
            out.erase(cutPos);
            break;
        }

        llama_batch nextBatch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, nextBatch) != 0) break;
        holder->cachedTokens.push_back(id);

        if (i >= 24 && out.size() >= 85 && out.find('{') == std::string::npos) {
            char lastChar = out.back();
            if ((lastChar == '.' || lastChar == '!' || lastChar == '?') && countCompletedSentences(out) >= 2) {
                break;
            }
        }
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"

