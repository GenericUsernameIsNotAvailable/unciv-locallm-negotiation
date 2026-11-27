// High-speed JNI bridge connecting JniLlamaCppBridge.kt to llama.cpp on Android (arm64-v8a), iOS (arm64), and Desktop.
// v1.2.0:
// 1. Persistent llama_context across turns (zero 150 MB reallocation per message) + 6-thread big-core pool
// 2. Clean KV memory reset per call (prevents ISWA sliding-window position corruption on Gemma 4 & cross-leader context bleed)
// 3. Built-in Gemma 4 thought-channel stripping (<|channel>thought...<channel|>) and accurate stop markers
// 4. Repeat penalty to prevent generic/looping phrasing on 1.5B-2B models
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
};

static int computeOptimalThreads() {
    unsigned int hw = std::thread::hardware_concurrency();
    if (hw >= 8) return 6;
    if (hw >= 4) return static_cast<int>(hw);
    return 4;
}

static bool hasStopMarker(const std::string& out, size_t& cutPos) {
    static const char* markers[] = {
        "<turn|>",
        "<|turn>",
        "<|im_end|>",
        "<end_of_turn>",
        "<|eot_id|>",
        "\nUser:",
        "\nPlayer:",
        "\nHuman:",
        nullptr
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

static std::string stripChannelBlocks(const std::string& raw) {
    std::string s = raw;
    // Strip complete <|channel>...<channel|> blocks
    while (true) {
        size_t start = s.find("<|channel>");
        if (start == std::string::npos) break;
        size_t end = s.find("<channel|>", start);
        if (end != std::string::npos) {
            s.erase(start, (end + 10) - start);
        } else {
            // Unclosed channel block
            s.erase(start);
            break;
        }
    }
    // Strip stray <channel|> if present at start
    size_t strayEnd = s.find("<channel|>");
    if (strayEnd != std::string::npos) {
        s.erase(0, strayEnd + 10);
    }
    return s;
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
    return reinterpret_cast<jlong>(new UncivLlamaHolder{model, ctx, ctxLen, threads});
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

    // Truncate from the middle/front if prompt ever approaches context limit
    const int tokenCap = (maxTokens > 0 && maxTokens <= 200) ? maxTokens : 140;
    if (static_cast<int>(tokens.size()) + tokenCap + 16 > holder->contextSize) {
        int maxPromptTokens = std::max(128, holder->contextSize - tokenCap - 16);
        if (static_cast<int>(tokens.size()) > maxPromptTokens) {
            tokens.resize(maxPromptTokens);
        }
    }

    llama_context* ctx = holder->ctx;
    llama_memory_t mem = llama_get_memory(ctx);
    llama_memory_clear(mem, true);

    llama_batch fullBatch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(ctx, fullBatch) != 0) {
        return env->NewStringUTF("");
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.12f, 0.0f, 0.0f));
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    out.reserve(512);

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
    }

    llama_sampler_free(smpl);
    std::string cleaned = stripChannelBlocks(out);
    return env->NewStringUTF(cleaned.c_str());
}

} // extern "C"
