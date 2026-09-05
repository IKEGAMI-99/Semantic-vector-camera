#include <jni.h>
#include <android/log.h>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "inference_format.h"

#include <algorithm>
#include <chrono>
#include <mutex>
#include <memory>
#include <stdexcept>
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
    if (!tmpl) throw std::runtime_error("Model has no chat template; import an instruction-tuned vision GGUF");
    if (svcam::is_gemma4_template(tmpl)) return svcam::gemma4_prompt(user_content);

    llama_chat_message message {"user", user_content.c_str()};
    int32_t needed = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    if (needed < 0) throw std::runtime_error("Unsupported model chat template");

    std::vector<char> buffer(static_cast<size_t>(needed) + 1);
    int32_t written = llama_chat_apply_template(
        tmpl,
        &message,
        1,
        true,
        buffer.data(),
        static_cast<int32_t>(buffer.size())
    );
    if (written < 0 || written > needed) throw std::runtime_error("Failed to format model chat template");
    return std::string(buffer.data(), static_cast<size_t>(written));
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
#if SVCAM_ENABLE_VULKAN
        model_params.n_gpu_layers = 999;
#else
        model_params.n_gpu_layers = 0;
#endif
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
        vision_params.use_gpu = SVCAM_ENABLE_VULKAN;
        vision_params.n_threads = ctx_params.n_threads;
        vision_params.print_timings = false;
        vision_params.warmup = false;

        // Gemma 4 supports dynamic visual token budgets (70/140/280/560/1120).
        // SVCAM only needs coarse scene semantics, so keep the smallest supported budget.
        vision_params.image_min_tokens = 70;
        vision_params.image_max_tokens = 70;
        vision_params.batch_max_tokens = 256;

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

        LOGI("Gemma GGUF + mmproj loaded (image token budget: 70)");
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception & e) {
        throw_runtime(env, std::string("Native model load failed: ") + e.what());
        return 0;
    } catch (...) {
        throw_runtime(env, "Native model load failed with an unknown error");
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
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
        report_progress(env, bridge, "kv_cache_cleared", 0, 0, elapsed_ms(inference_start));

        auto bitmap = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>(mtmd_bitmap_init(
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            rgb.data()
        ), mtmd_bitmap_free);
        if (!bitmap) {
            throw_runtime(env, "Failed to create mtmd bitmap");
            return nullptr;
        }
        report_progress(env, bridge, "native_bitmap_ready", 1, 1, elapsed_ms(inference_start));

        const std::string prompt = jstring_to_string(env, prompt_j);
        const std::string marked = std::string(mtmd_default_marker()) + "\n" + prompt;
        const std::string formatted = format_user_prompt(engine, marked);
        report_progress(env, bridge, "chat_template_ready", static_cast<int>(formatted.size()), static_cast<int>(prompt.size()), elapsed_ms(inference_start));

        mtmd_input_text text {};
        text.text = formatted.data();
        text.text_len = formatted.size();
        text.add_special = true;
        text.parse_special = true;

        auto chunks = std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)>(
            mtmd_input_chunks_init(), mtmd_input_chunks_free);
        const mtmd_bitmap * bitmap_array[1] = { bitmap.get() };
        report_progress(env, bridge, "native_tokenize_start", 0, 1, elapsed_ms(inference_start));
        const int32_t tokenized = mtmd_tokenize(engine->vision, chunks.get(), &text, bitmap_array, 1);
        if (tokenized != 0) {
            throw_runtime(env, "mtmd_tokenize failed: " + std::to_string(tokenized));
            return nullptr;
        }

        const int total_tokens = static_cast<int>(mtmd_helper_get_n_tokens(chunks.get()));
        const int total_positions = static_cast<int>(mtmd_helper_get_n_pos(chunks.get()));
        const int chunk_count = static_cast<int>(mtmd_input_chunks_size(chunks.get()));
        report_progress(env, bridge, "native_tokenize_complete", total_tokens, total_positions, elapsed_ms(inference_start));
        report_progress(env, bridge, "vision_plan_ready", chunk_count, total_tokens, elapsed_ms(inference_start));

        const int max_tokens = svcam::generation_budget(
            n_predict, static_cast<int>(llama_n_ctx(engine->context)), total_positions);
        report_progress(env, bridge, "generation_budget_ready", max_tokens, total_positions, elapsed_ms(inference_start));

        llama_pos n_past = 0;
        int32_t eval = 0;
        report_progress(env, bridge, "vision_eval_start", 0, total_tokens, elapsed_ms(inference_start));

        // Do not hide the expensive multimodal work behind mtmd_helper_eval_chunks().
        // Evaluate each chunk explicitly so the terminal can show whether we are stuck in
        // text prefill, the ViT/mmproj encoder, or image-embedding decode into the LLM.
        for (int i = 0; i < chunk_count; ++i) {
            const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks.get(), static_cast<size_t>(i));
            if (!chunk) {
                eval = -1;
                break;
            }

            const auto type = mtmd_input_chunk_get_type(chunk);
            const int chunk_tokens = static_cast<int>(mtmd_input_chunk_get_n_tokens(chunk));
            const int chunk_positions = static_cast<int>(mtmd_input_chunk_get_n_pos(chunk));
            report_progress(env, bridge, "vision_chunk_start", i + 1, chunk_count, elapsed_ms(inference_start));
            report_progress(env, bridge, "vision_chunk_shape", chunk_tokens, chunk_positions, elapsed_ms(inference_start));

            if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                const llama_pos before = n_past;
                report_progress(env, bridge, "vision_text_prefill_start", chunk_tokens, chunk_positions, elapsed_ms(inference_start));
                eval = mtmd_helper_eval_chunk_single(
                    engine->vision,
                    engine->context,
                    chunk,
                    n_past,
                    0,
                    engine->n_batch,
                    i == chunk_count - 1,
                    &n_past
                );
                if (eval == 0) {
                    report_progress(
                        env,
                        bridge,
                        "vision_text_prefill_complete",
                        static_cast<int>(n_past - before),
                        chunk_tokens,
                        elapsed_ms(inference_start)
                    );
                }
            } else if (type == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
                report_progress(env, bridge, "vision_image_encode_start", chunk_tokens, chunk_positions, elapsed_ms(inference_start));
                eval = mtmd_encode_chunk(engine->vision, chunk);
                if (eval == 0) {
                    report_progress(env, bridge, "vision_image_encode_complete", chunk_tokens, chunk_positions, elapsed_ms(inference_start));

                    float * embd = mtmd_get_output_embd(engine->vision);
                    if (!embd) {
                        eval = -2;
                    } else {
                        const llama_pos before = n_past;
                        llama_pos after = n_past;
                        report_progress(env, bridge, "vision_image_llm_prefill_start", chunk_tokens, chunk_positions, elapsed_ms(inference_start));
                        eval = mtmd_helper_decode_image_chunk(
                            engine->vision,
                            engine->context,
                            chunk,
                            embd,
                            n_past,
                            0,
                            engine->n_batch,
                            &after,
                            nullptr,
                            nullptr
                        );
                        if (eval == 0) {
                            n_past = after;
                            report_progress(
                                env,
                                bridge,
                                "vision_image_llm_prefill_complete",
                                static_cast<int>(n_past - before),
                                chunk_positions,
                                elapsed_ms(inference_start)
                            );
                        }
                    }
                }
            } else {
                // SVCAM currently supplies one image only, but keep a safe fallback for
                // future audio/media chunks without losing diagnostics.
                report_progress(env, bridge, "vision_other_chunk_start", chunk_tokens, chunk_positions, elapsed_ms(inference_start));
                eval = mtmd_helper_eval_chunk_single(
                    engine->vision,
                    engine->context,
                    chunk,
                    n_past,
                    0,
                    engine->n_batch,
                    i == chunk_count - 1,
                    &n_past
                );
            }

            if (eval != 0) break;
            report_progress(env, bridge, "vision_chunk_complete", i + 1, chunk_count, elapsed_ms(inference_start));
        }

        chunks.reset();
        bitmap.reset();

        if (eval != 0) {
            throw_runtime(env, "mtmd/llama evaluation failed: " + std::to_string(eval));
            return nullptr;
        }
        report_progress(env, bridge, "vision_eval_complete", static_cast<int>(n_past), total_positions, elapsed_ms(inference_start));

        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
            llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto * grammar = llama_sampler_init_grammar(engine->vocab, svcam::SCENE_GRAMMAR, "root");
        if (!grammar) throw std::runtime_error("Failed to initialize semantic JSON grammar");
        llama_sampler_chain_add(sampler.get(), grammar);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());

        std::string output;
        bool complete = false;
        report_progress(env, bridge, "generation_start", 0, max_tokens, elapsed_ms(inference_start));
        int generated = 0;
        for (int i = 0; i < max_tokens; ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), engine->context, -1);
            if (llama_vocab_is_eog(engine->vocab, token)) break;

            output += token_piece(engine->vocab, token);
            ++generated;

            if (generated == 1 || generated % 8 == 0) {
                report_progress(env, bridge, "generation_progress", generated, max_tokens, elapsed_ms(inference_start));
            }

            // The prompt requires exactly one JSON object. Once its top-level brace closes,
            // continuing to generate only wastes battery and often adds invalid commentary.
            if (svcam::has_complete_json_object(output)) {
                complete = true;
                report_progress(env, bridge, "generation_json_complete", generated, max_tokens, elapsed_ms(inference_start));
                break;
            }

            // Do not decode a final token that will never be sampled from.
            if (i + 1 == max_tokens) break;
            llama_batch batch = llama_batch_get_one(&token, 1);
            const int decode = llama_decode(engine->context, batch);
            if (decode != 0) {
                throw_runtime(env, "llama_decode failed while generating JSON: " + std::to_string(decode));
                return nullptr;
            }
        }
        if (!complete) {
            report_progress(env, bridge, "generation_incomplete", generated, max_tokens, elapsed_ms(inference_start));
            throw std::runtime_error("Semantic JSON was not completed within the generation/context budget (" +
                std::to_string(generated) + "/" + std::to_string(max_tokens) + " tokens); capture was not saved");
        }
        report_progress(env, bridge, "generation_complete", generated, max_tokens, elapsed_ms(inference_start));

        // Model output is standard UTF-8, not JNI modified UTF-8 (e.g. emoji).
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
            reinterpret_cast<const jbyte *>(output.data()));
        return result;
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
