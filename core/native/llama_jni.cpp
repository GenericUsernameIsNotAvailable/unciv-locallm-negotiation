#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"

struct UncivLlamaHolder {
    llama_model* model = nullptr;
    int contextSize = 2048;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_unciv_logic_commitments_llm_JniLlamaCppBridge_nativeLoadModel(
    JNIEnv* env, jclass, jstring modelPathJ, jint contextSize, jint gpuLayers
) {
    llama_backend_init();
    const char* path = env->GetStringUTFChars(modelPathJ, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpuLayers;
    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPathJ, path);
    if (!model) return 0;

    auto* holder = new UncivLlamaHolder{model, contextSize > 0 ? contextSize : 2048};
    return reinterpret_cast<jlong>(holder);
}

JNIEXPORT void JNICALL
Java_com_unciv_logic_commitments_llm_JniLlamaCppBridge_nativeFreeModel(
    JNIEnv*, jclass, jlong handle
) {
    auto* holder = reinterpret_cast<UncivLlamaHolder*>(handle);
    if (!holder) return;
    if (holder->model) llama_model_free(holder->model);
    delete holder;
}

JNIEXPORT jstring JNICALL
Java_com_unciv_logic_commitments_llm_JniLlamaCppBridge_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring promptJ, jint maxTokens, jfloat temperature
) {
    auto* holder = reinterpret_cast<UncivLlamaHolder*>(handle);
    if (!holder || !holder->model) return env->NewStringUTF("");

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

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = holder->contextSize > (n_prompt + maxTokens) ? holder->contextSize : (n_prompt + maxTokens + 64);
    cparams.n_batch = n_prompt > 512 ? n_prompt : 512;
    llama_context* ctx = llama_init_from_model(holder->model, cparams);
    if (!ctx) return env->NewStringUTF("");

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        llama_free(ctx);
        return env->NewStringUTF("");
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }
    llama_sampler_free(smpl);
    llama_free(ctx);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
