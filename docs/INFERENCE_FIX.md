# Inference stop investigation (2026-09-05)

## Findings in the repository

- The pinned llama.cpp commit `cc83d7b4824f73cfdda4dfbb47ee39804f71b328` supports Gemma 4 through its Jinja/common chat path, but **not** through `llama_chat_apply_template`'s built-in template detector. The app uses the latter and silently returns the unformatted user prompt on failure. This drops user/model turn markers and Gemma 4's non-thinking generation prefix.
- Generation stops at 384 tokens and reports `generation_complete` even when the root JSON has not closed. A multi-object scene can exceed this budget. The Kotlin parser also previously accepted unrelated objects with missing scene fields as empty maps.
- `MainActivity.onDestroy()` calls `AppController.close()` on the main thread. The engine close takes the same monitor held across the entire native inference, so activity destruction during inference can block Android's main thread.
- The latest Android CI run `33276488479` failed with `spirv/unified1/spirv.hpp file not found`. The isolated cross-compile include directory contained Vulkan headers but not SPIR-V headers. The corresponding release failed too; no latest release was available during this investigation.

These are source/build findings. No current device log, device connection or user GGUF was available, so the exact native stage of the reported device stall and hardware-specific GPU behavior have not been reproduced.

## Fix

- Format the app's single user/image turn using the pinned Gemma 4 template's `enable_thinking=false` format. Other supported templates continue through the existing API; unsupported or missing templates now produce an explicit error.
- Constrain sampling to the required scene JSON shape. Bound object/score counts, whitespace, labels and decimal precision to prevent endless reasoning or repetitive output.
- Allow up to 2048 output tokens, capped by the actual available context positions; stop immediately when the root object closes. Refuse to save incomplete output, and require all three scene fields when parsing.
- Use RAII for transient native bitmap/chunks/sampler allocations, including error paths. Return generated text as standard UTF-8 bytes so non-BMP labels are not passed to JNI's modified-UTF-8 string constructor.
- Release the engine on an IO dispatcher, including settings operations and activity destruction.
- Copy both Vulkan and SPIR-V headers into the isolated Android include directory. Replace CMake source-text substitution with an explicit compile definition so source changes rebuild reliably.

## Regression checks

`tests/native` links the pinned llama.cpp and verifies its template-detection failure, the corrected prompt, context budgeting, complete/truncated JSON, string escapes, Unicode, score precision and object-count bounds. The Android CI runs it before Kotlin unit tests and the APK build. `StructuredSceneTest` checks valid encoding and rejects incomplete/unrelated JSON.

```bash
cmake -S tests/native -B /tmp/svcam-native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/svcam-native-tests --target svcam_inference_test -j 2
ctest --test-dir /tmp/svcam-native-tests --output-on-failure
gradle :app:testDebugUnitTest :app:assembleDebug
```

On-device verification: install the new signed APK, import the matching Gemma 4 model and Q8_0 projector, process a gallery image and a camera frame, verify `generation_json_complete` → `semantic_memory_complete`, and verify activity rotation does not block the UI. If a GPU stage still stalls, export Settings → Diagnostics → Log ZIP so that the last native stage and model/device combination can be investigated.
