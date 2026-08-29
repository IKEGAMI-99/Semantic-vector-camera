#include <jni.h>
#include <android/log.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#define SVCAM_TAG "SVCAM-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, SVCAM_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SVCAM_TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * vision = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_batch = 512;
    std::mutex mutex;

    ~Engine() {
        if (vision) mtmd_free(vision);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_backend_init();
        ggml_backend_load_all();
        LOGI("llama.cpp backend initialized");
    });
}

std::string jstring_to_string(JNIEnv * env, jstring input) {
    if (!input) return {};
    const char * chars = env->GetStringUTFChars(input, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(input, chars);
    return out;
}

void throw_runtime(JNIEnv * env, const std::string & message) {
    LOGE("%s", message.c_str());
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

using Clock = std::chrono::steady_clock;

long elapsed_ms(const Clock::time_point & start) {
    return static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count());
}

void report_progress(
    JNIEnv * env,
    jobject bridge,
    const char * event,
    int current,
    int total,
    long elapsed
) {
    if (!env || !bridge || !event || env->ExceptionCheck()) return;
    jclass cls = env->GetObjectClass(bridge);
    if (!cls) return;
    jmethodID method = env->GetMethodID(cls, "onNativeProgress", "(Ljava/lang/String;IIJ)V");
    if (!method) {
        env->DeleteLocalRef(cls);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jstring event_j = env->NewStringUTF(event);
    if (event_j) {
        env->CallVoidMethod(
            bridge,
            method,
            event_j,
            static_cast<jint>(current),
            static_cast<jint>(total),
            static_cast<jlong>(elapsed)
        );
        env->DeleteLocalRef(event_j);
    }
    env->DeleteLocalRef(cls);
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    char small[256];
    int n = llama_token_to_piece(vocab, token, small, sizeof(small), 0, true);
    if (n >= 0) return std::string(small, static_cast<size_t>(n));

    std::vector<char> buf(static_cast<size_t>(-n) + 8);
    n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    if (n < 0) return {};
    return std::string(buf.data(), static_cast<size_t>(n));
}

std::string format_user_prompt(Engine * engine, const std::string & user_content) {
    const char * tmpl = llama_model_chat_template(engine->model, nullptr);
    if (!tmpl) {
        return user_content;
    }

    llama_chat_message message {"user", user_content.c_str()};
    int32_t needed = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    if (needed < 0) return user_content;

    std::vector<char> buffer(static_cast<size_t>(needed) + 1);
    int32_t written = llama_chat_apply_template(
        tmpl,
        &message,
        1,
        true,
        buffer.data(),
        static_cast<int32_t>(buffer.size())
    );
    if (written < 0) return user_content;
    return std::string(buffer.data(), static_cast<size_t>(written));
}

bool has_complete_json_object(const std::string & text) {
    bool started = false;
    bool in_string = false;
    bool escaped = false;
    int depth = 0;

    for (char c : text) {
        if (!started) {
            if (c == '{') {
                started = true;
                depth = 1;
            }
            continue;
        }

        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }

        if (c == '"') {
            in_string = true;
        } else if (c == '{') {
            ++depth;
        } else if (c == '}') {
            --depth;
            if (depth == 0) return true;
        }
    }
    return false;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ikegami_svcam_inference_NativeGemmaBridge_nativeCreate(
    JNIEnv * env,
    jobject,
    jstring model_path_j,
    jstring mmproj_path_j,
    jint n_threads,
    jint n_ctx
) {
    try {
        ensure_backend();
        const std::string model_path = jstring_to_string(env, model_path_j);
        const std::string mmproj_path = jstring_to_string(env, mmproj_path_j);
        if (model_path.empty() || mmproj_path.empty()) {
            throw_runtime(env, "Model and mmproj paths are required");
            return 0;
        }

        auto * engine = new Engine();

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // CPU-safe baseline. Keep Vulkan as a separate build profile.
        engine->model = llama_model_load_from_file(model_path.c_str(), model_params);
        if (!engine->model) {
            delete engine;
            throw_runtime(env, "Failed to load GGUF model: " + model_path);
            return 0;
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = static_cast<uint32_t>(std::max(4096, static_cast<int>(n_ctx)));
        ctx_params.n_batch = std::min<uint32_t>(1024u, ctx_params.n_ctx);
        ctx_params.n_threads = std::max(1, static_cast<int>(n_threads));
        ctx_params.n_threads_batch = ctx_params.n_threads;
        ctx_params.no_perf = false;

        engine->context = llama_init_from_model(engine->model, ctx_params);
        if (!engine->context) {
            delete engine;
            throw_runtime(env, "Failed to create llama context");
            return 0;
        }
        engine->n_batch = static_cast<int>(ctx_params.n_batch);
        engine->vocab = llama_model_get_vocab(engine->model);

        mtmd_context_params vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.n_threads = ctx_params.n_threads;
        vision_params.print_timings = false;
        vision_params.warmup = false;

        engine->vision = mtmd_init_from_file(mmproj_path.c_str(), engine->model, vision_params);
        if (!engine->vision) {
            delete engine;
            throw_runtime(env, "Failed to load multimodal projector: " + mmproj_path);
            return 0;
        }
        if (!mtmd_support_vision(engine->vision)) {
            delete engine;
            throw_runtime(env, "The selected mmproj does not expose vision input");
            return 0;
        }
        if (!mtmd_helper_model_can_chat(engine->context, engine->vision)) {
            delete engine;
            throw_runtime(env, "The selected model/mmproj pair does not support multimodal chat");
            return 0;
        }

        LOGI("Gemma GGUF + mmproj loaded");
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception & e) {
        throw_runtime(env, std::string("Native model load failed: ") + e.what());
        return 0;
    } catch (...) {
        throw_runtime(env, "Native model load failed with an unknown error");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ikegami_svcam_inference_NativeGemmaBridge_nativeAnalyze(
    JNIEnv * env,
    jobject bridge,
    jlong handle,
    jint width,
    jint height,
    jbyteArray rgb_j,
    jstring prompt_j,
    jint n_predict
) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (!engine || !engine->model || !engine->context || !engine->vision) {
        throw_runtime(env, "Gemma engine is not loaded");
        return nullptr;
    }
    if (!rgb_j || width <= 0 || height <= 0) {
        throw_runtime(env, "Invalid image buffer");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    const auto inference_start = Clock::now();

    try {
        const jsize rgb_len = env->GetArrayLength(rgb_j);
        const size_t expected = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
        if (static_cast<size_t>(rgb_len) != expected) {
            throw_runtime(env, "RGB buffer size does not match width*height*3");
            return nullptr;
        }

        std::vector<unsigned char> rgb(expected);
        env->GetByteArrayRegion(rgb_j, 0, rgb_len, reinterpret_cast<jbyte *>(rgb.data()));

        llama_memory_clear(llama_get_memory(engine->context), true);

        mtmd_bitmap * bitmap = mtmd_bitmap_init(
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            rgb.data()
        );
        if (!bitmap) {
            throw_runtime(env, "Failed to create mtmd bitmap");
            return nullptr;
        }
        report_progress(env, bridge, "native_bitmap_ready", 1, 1, elapsed_ms(inference_start));

        const std::string prompt = jstring_to_string(env, prompt_j);
        const std::string marked = std::string(mtmd_default_marker()) + "\n" + prompt;
        const std::string formatted = format_user_prompt(engine, marked);

        mtmd_input_text text {};
        text.text = formatted.data();
        text.text_len = formatted.size();
        text.add_special = true;
        text.parse_special = true;

        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        const mtmd_bitmap * bitmap_array[1] = { bitmap };
        report_progress(env, bridge, "native_tokenize_start", 0, 1, elapsed_ms(inference_start));
        const int32_t tokenized = mtmd_tokenize(engine->vision, chunks, &text, bitmap_array, 1);
        if (tokenized != 0) {
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(bitmap);
            throw_runtime(env, "mtmd_tokenize failed: " + std::to_string(tokenized));
            return nullptr;
        }
        report_progress(env, bridge, "native_tokenize_complete", 1, 1, elapsed_ms(inference_start));

        llama_pos n_past = 0;
        report_progress(env, bridge, "vision_eval_start", 0, 1, elapsed_ms(inference_start));
        const int32_t eval = mtmd_helper_eval_chunks(
            engine->vision,
            engine->context,
            chunks,
            0,
            0,
            engine->n_batch,
            true,
            &n_past
        );
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);

        if (eval != 0) {
            throw_runtime(env, "mtmd/llama evaluation failed: " + std::to_string(eval));
            return nullptr;
        }
        report_progress(env, bridge, "vision_eval_complete", static_cast<int>(n_past), static_cast<int>(n_past), elapsed_ms(inference_start));

        llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        std::string output;
        const int max_tokens = std::clamp(static_cast<int>(n_predict), 32, 1024);
        report_progress(env, bridge, "generation_start", 0, max_tokens, elapsed_ms(inference_start));
        int generated = 0;
        for (int i = 0; i < max_tokens; ++i) {
            llama_token token = llama_sampler_sample(sampler, engine->context, -1);
            if (llama_vocab_is_eog(engine->vocab, token)) break;

            output += token_piece(engine->vocab, token);
            ++generated;

            if (generated == 1 || generated % 16 == 0) {
                report_progress(env, bridge, "generation_progress", generated, max_tokens, elapsed_ms(inference_start));
            }

            // The prompt requires exactly one JSON object. Once its top-level brace closes,
            // continuing to generate only wastes battery and often adds invalid commentary.
            if (has_complete_json_object(output)) {
                report_progress(env, bridge, "generation_json_complete", generated, max_tokens, elapsed_ms(inference_start));
                break;
            }

            llama_batch batch = llama_batch_get_one(&token, 1);
            const int decode = llama_decode(engine->context, batch);
            if (decode != 0) {
                llama_sampler_free(sampler);
                throw_runtime(env, "llama_decode failed while generating JSON: " + std::to_string(decode));
                return nullptr;
            }
        }
        llama_sampler_free(sampler);
        report_progress(env, bridge, "generation_complete", generated, max_tokens, elapsed_ms(inference_start));

        return env->NewStringUTF(output.c_str());
    } catch (const std::exception & e) {
        throw_runtime(env, std::string("Native inference failed: ") + e.what());
        return nullptr;
    } catch (...) {
        throw_runtime(env, "Native inference failed with an unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ikegami_svcam_inference_NativeGemmaBridge_nativeDestroy(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    delete engine;
}
