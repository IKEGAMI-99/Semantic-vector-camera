#pragma once

#include <algorithm>
#include <stdexcept>
#include <string>

namespace svcam {

inline bool is_gemma4_template(const std::string & tmpl) {
    return tmpl.find("<|turn>") != std::string::npos &&
           tmpl.find("<turn|>") != std::string::npos;
}

// Single user/image turn, matching the pinned Gemma 4 Jinja template with
// enable_thinking=false. mtmd adds BOS via add_special; do not duplicate it here.
inline std::string gemma4_prompt(const std::string & content) {
    return "<|turn>user\n" + content +
           "<turn|>\n<|turn>model\n<|channel>thought\n<channel|>";
}

inline int generation_budget(int requested, int context_size, int prompt_positions) {
    const int available = context_size - prompt_positions;
    if (available < 32) {
        throw std::runtime_error("Image and prompt fill the model context; no room for semantic JSON");
    }
    return std::min(std::clamp(requested, 32, 2048), available);
}

// Input is constrained by SCENE_GRAMMAR, so only a complete root object counts.
inline bool has_complete_json_object(const std::string & text) {
    bool started = false;
    bool in_string = false;
    bool escaped = false;
    int depth = 0;
    for (char c : text) {
        if (!started) {
            if (c == '{') { started = true; depth = 1; }
            else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return false;
            continue;
        }
        if (in_string) {
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') in_string = false;
        } else if (c == '"') in_string = true;
        else if (c == '{') ++depth;
        else if (c == '}' && --depth == 0) return true;
    }
    return false;
}

// Bound lists, strings, decimal precision and whitespace so greedy decoding
// cannot spend the whole context in a thought, repeated objects or whitespace.
inline constexpr const char * SCENE_GRAMMAR = R"gbnf(
root ::= "{" ws "\"global\"" ws ":" ws global ws "," ws "\"objects\"" ws ":" ws objects ws "," ws "\"relations\"" ws ":" ws scores ws "}"
global ::= "{" ws (pair (ws "," ws pair){0,15})? ws "}"
scores ::= "{" ws (pair (ws "," ws pair){0,7})? ws "}"
pair ::= key ws ":" ws number
key ::= "\"" [a-z_] [a-z_0-9]{0,63} "\""
objects ::= "[" ws (object (ws "," ws object){0,7})? ws "]"
object ::= "{" ws "\"label\"" ws ":" ws label ws "," ws "\"bbox\"" ws ":" ws bbox ws "," ws "\"scores\"" ws ":" ws scores ws "}"
label ::= "\"" char{1,48} "\""
char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
bbox ::= "[" ws number ws "," ws number ws "," ws number ws "," ws number ws "]"
number ::= "0" ("." [0-9]{1,3})? | "1" ("." "0"{1,3})?
ws ::= [ \t\n\r]?
)gbnf";

} // namespace svcam
