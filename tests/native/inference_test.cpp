#ifdef NDEBUG
#undef NDEBUG
#endif
#include "inference_format.h"
#include "llama-chat.h"
#include "llama-grammar.h"
#include "unicode.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>

static bool accepts(const std::string & text) {
    auto grammar = std::unique_ptr<llama_grammar, decltype(&llama_grammar_free_impl)>(
        llama_grammar_init_impl(nullptr, svcam::SCENE_GRAMMAR, "root", false, nullptr, 0, nullptr, 0),
        llama_grammar_free_impl);
    assert(grammar);
    for (size_t pos = 0; pos < text.size();) {
        const auto cp = unicode_cpt_from_utf8(text, pos);
        llama_grammar_accept(grammar.get(), cp);
        if (llama_grammar_get_stacks(grammar.get()).empty()) return false;
    }
    for (const auto & stack : llama_grammar_get_stacks(grammar.get())) if (stack.empty()) return true;
    return false;
}

int main(int argc, char ** argv) {
    assert(argc == 2);
    std::ifstream source(argv[1]);
    assert(source.good());
    const std::string tmpl((std::istreambuf_iterator<char>(source)), {});
    // Reproduce the original bug against the actual pinned runtime, without a GGUF.
    assert(llm_chat_detect_template(tmpl) == LLM_CHAT_TEMPLATE_UNKNOWN);
    assert(svcam::is_gemma4_template(tmpl));
    assert(!svcam::is_gemma4_template("<start_of_turn>user\n"));
    const auto prompt = svcam::gemma4_prompt("<__media__>\nDescribe image");
    assert(prompt == "<|turn>user\n<__media__>\nDescribe image<turn|>\n<|turn>model\n<|channel>thought\n<channel|>");
    assert(prompt.find("<bos>") == std::string::npos);

    assert(svcam::generation_budget(2048, 4096, 2000) == 2048);
    assert(svcam::generation_budget(2048, 4096, 3000) == 1096);
    assert(svcam::generation_budget(2048, 4096, 4064) == 32);
    bool rejected = false;
    try { svcam::generation_budget(2048, 4096, 4080); } catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);

    const std::string scene = R"({"global":{"indoor":0.9},"objects":[{"label":"camera {\"test\"}","bbox":[0.5,0.5,0.2,0.2],"scores":{"device":1}}],"relations":{}})";
    assert(accepts(scene));
    for (size_t size = 0; size < scene.size(); ++size) {
        assert(!svcam::has_complete_json_object(scene.substr(0, size)));
    }
    assert(svcam::has_complete_json_object(scene));
    assert(!svcam::has_complete_json_object("<|channel>thought\n{\"example\":1}"));
    assert(!accepts("<|channel>thought\n" + scene));
    assert(!accepts("{}"));
    assert(!accepts(scene.substr(0, scene.size() - 1)));
    assert(!accepts(scene + " commentary"));
    assert(!accepts(R"({"global":{"indoor":2},"objects":[],"relations":{}})"));
    assert(!accepts(R"({"global":{"indoor":0.1234},"objects":[],"relations":{}})"));
    assert(accepts(R"({"global":{},"objects":[{"label":"カメラ📷","bbox":[0,0,1,1],"scores":{}}],"relations":{}})"));

    std::string objects;
    const std::string object = R"({"label":"camera","bbox":[0.5,0.5,0.2,0.2],"scores":{"device":0.99,"importance":0.95,"confidence":1,"foreground":0.5}})";
    for (int i = 0; i < 8; ++i) { if (i) objects += ','; objects += object; }
    const std::string large = "{\"global\":{\"indoor\":1},\"objects\":[" + objects + "],\"relations\":{}}";
    // Large but valid responses must complete; the former fixed cap cut them mid-object.
    assert(accepts(large));
    assert(svcam::has_complete_json_object(large));
    assert(!accepts("{\"global\":{},\"objects\":[" + objects + "," + object + "],\"relations\":{}}"));
    std::cout << "PASS: pinned Gemma 4 template regression, context budgets, constrained JSON, truncated output, Unicode and object bounds\n";
}
