// JNI bridge: stateless raw-prompt completion over llama.cpp.
//
// Model loading and backend setup follow kolama (github.com/rajumark/kolama, Apache-2.0,
// native/kolama/kolama.cpp). It differs in three ways, because the app runs many
// independent single-turn corrections against one loaded model:
//
//  1. The caller passes a fully formatted ChatML prompt; no chat template or history here.
//  2. Every completion is independent. The KV cache is cleared before each prefill and positions
//     restart at 0. Skipping this is the "second inference" bug: llama_batch_get_one appends after
//     whatever the cache already holds, positions drift, and the next answer is garbage or ends
//     immediately. Reusing the cached prompt prefix is not safe either: decoding the prefix as one
//     batch and the tail as another changes float accumulation order, so a greedy run of the same
//     input can pick a different token than it did from a cold cache (seen on device).
//  3. Sampling is greedy with a sampler created per call, so no sampler state survives either.
//
// One deliberate exception to point 2, and only one: nativeCompleteWithPrefix (#32). The tone read
// sends the same ~240-token opening (system turn and worked examples) before every message, and
// prefilling it cold was ~4 s of every ~4.2 s check. That entry point prefills the opening once,
// keeps a copy of its KV state (llama_state_seq_get_data), and on every later call clears the
// cache, restores that copy and decodes only the tail. It still pays the accumulation-order
// difference described above, which is why it is a separate entry point that only the tone read
// calls: nativeComplete (proofreading and every rewrite) keeps its cold single-pass prefill and
// never reads anything cached here. The tone read is consistent with itself: a call that builds the
// copy then restores it before decoding the tail, exactly like a call that finds it, so its answer
// never depends on whether the cache was warm. free_model() drops the copy, so it cannot outlive
// the model and context it was computed with.
//
// nativeScoreChoices shares that cache (up to two openings are kept, see PrefixEntry). It generates
// nothing: it reads the log-probability of each of a fixed set of answers after the prompt, which
// is how the tone read picks its labels.

#include <jni.h>
#include <android/log.h>

#include <llama.h>
#include <ggml-backend.h>

#include <algorithm>
#include <cmath>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define TAG "GrammarFixLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

llama_model *       g_model = nullptr;
llama_context *     g_ctx   = nullptr;
const llama_vocab * g_vocab = nullptr;
std::string         g_model_path;

std::mutex g_mtx;
bool       g_backend_ready = false;

// A saved opening for the prefix-cached entry points: the text it was made from, the tokens it
// covers, and seq 0's KV state right after they were decoded.
struct PrefixEntry {
    std::string              text;
    std::vector<llama_token> tokens;
    std::vector<uint8_t>     state;
};

// Most recently used first. Two slots, so that a second scored or cached opening (the tone read is
// the only one today) would not evict the first and make every alternating call pay its full cold
// prefill again. A slot is only filled when used; ~34 MB each.
constexpr size_t         kMaxPrefixEntries = 2;
std::vector<PrefixEntry> g_prefixes;

void drop_prefix_cache() {
    g_prefixes.clear();
    g_prefixes.shrink_to_fit();   // tens of MB per opening; give it back
}

// The cached entry for `prefix`, moved to the front, or null.
PrefixEntry * find_prefix(const std::string & prefix) {
    for (size_t i = 0; i < g_prefixes.size(); ++i) {
        if (g_prefixes[i].text == prefix && !g_prefixes[i].state.empty()) {
            std::rotate(g_prefixes.begin(), g_prefixes.begin() + (std::ptrdiff_t) i,
                        g_prefixes.begin() + (std::ptrdiff_t) i + 1);
            return &g_prefixes[0];
        }
    }
    return nullptr;
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void throw_java(JNIEnv * env, const std::string & msg) {
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(ex, msg.c_str());
}

void free_model() {
    // The saved KV state belongs to this model and context; it must never survive either.
    drop_prefix_cache();
    if (g_ctx)  { llama_free(g_ctx);         g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_model_path.clear();
}

std::vector<llama_token> tokenize(const std::string & text) {
    std::vector<llama_token> tokens(text.size() + 16);
    // add_special=false: Qwen has no BOS. parse_special=true: <|im_start|> etc. are control tokens.
    int n = llama_tokenize(g_vocab, text.data(), (int32_t) text.size(), tokens.data(),
                           (int32_t) tokens.size(), false, true);
    if (n < 0) {
        tokens.resize((size_t) -n);
        n = llama_tokenize(g_vocab, text.data(), (int32_t) text.size(), tokens.data(),
                           (int32_t) tokens.size(), false, true);
    }
    tokens.resize((size_t) std::max(n, 0));
    return tokens;
}

void append_piece(std::string & out, llama_token token) {
    char buf[128];
    int32_t len = llama_token_to_piece(g_vocab, token, buf, (int32_t) sizeof(buf), 0, false);
    if (len >= 0) {
        out.append(buf, (size_t) len);
        return;
    }
    std::string big((size_t) -len, '\0');
    len = llama_token_to_piece(g_vocab, token, big.data(), (int32_t) big.size(), 0, false);
    if (len > 0) out.append(big.data(), (size_t) len);
}

// The request the caller has given up on (#31). Each completion carries its own id, so an abort that
// arrives late -- after its request finished and the next one began -- never cuts the wrong answer
// short. Read between decode steps, which is as often as generation can usefully stop.
std::atomic<int64_t> g_abort_request{0};

bool aborted(int64_t request) {
    return request != 0 && g_abort_request.load(std::memory_order_relaxed) == request;
}

// Decodes tokens[from..to) at their true positions; only the last one needs logits.
bool decode_range(const std::vector<llama_token> & tokens, size_t from, size_t to, int64_t request) {
    const int32_t n_batch = (int32_t) llama_n_batch(g_ctx);
    for (size_t start = from; start < to; start += (size_t) n_batch) {
        if (aborted(request)) return false;
        const size_t end = std::min(to, start + (size_t) n_batch);
        llama_batch batch = llama_batch_init((int32_t) (end - start), 0, 1);
        for (size_t i = start; i < end; ++i) {
            const int32_t k = (int32_t) (i - start);
            batch.token[k]     = tokens[i];
            batch.pos[k]       = (llama_pos) i;
            batch.n_seq_id[k]  = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k]    = (i == to - 1);
        }
        batch.n_tokens = (int32_t) (end - start);
        const int rc = llama_decode(g_ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) return false;
    }
    return true;
}

// Greedy generation after a prompt of n_prompt tokens has been decoded and the last one's logits
// are ready. Shared by both completions so they sample in exactly the same way.
std::string generate(size_t n_prompt, int32_t max_tokens, int64_t request) {
    const int32_t n_ctx = (int32_t) llama_n_ctx(g_ctx);
    llama_sampler * smpl = llama_sampler_init_greedy();
    llama_batch one = llama_batch_init(1, 0, 1);
    std::string out;
    llama_pos pos = (llama_pos) n_prompt;

    for (int32_t i = 0; i < max_tokens && pos < n_ctx - 1; ++i) {
        if (aborted(request)) {
            LOGI("completion %lld aborted after %d tokens", (long long) request, i);
            break;
        }
        const llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, tok)) break;
        append_piece(out, tok);

        one.n_tokens     = 1;
        one.token[0]     = tok;
        one.pos[0]       = pos;
        one.n_seq_id[0]  = 1;
        one.seq_id[0][0] = 0;
        one.logits[0]    = true;
        if (llama_decode(g_ctx, one) != 0) break;
        pos++;
    }

    llama_batch_free(one);
    llama_sampler_free(smpl);
    return out;
}

// Decodes the part of `tokens` that `prefix` covers and saves seq 0's KV state as a new cache
// entry, evicting the least recently used one when the cache is full. Returns the entry, or null
// on failure. The caller then restores it like any cache hit.
PrefixEntry * build_prefix_cache(const std::string & prefix, const std::vector<llama_token> & tokens,
                                 int64_t request) {
    // The prefix is tokenised on its own only to find where it ends inside the full prompt: BPE
    // could merge across the join, so the reusable part is the run both tokenisations agree on.
    // At least one token is always left over, so the tail has logits to sample from.
    const std::vector<llama_token> alone = tokenize(prefix);
    size_t n = 0;
    while (n < alone.size() && n + 1 < tokens.size() && alone[n] == tokens[n]) ++n;
    if (n == 0) return nullptr;

    llama_memory_clear(llama_get_memory(g_ctx), true);
    if (!decode_range(tokens, 0, n, request)) return nullptr;
    std::vector<uint8_t> state(llama_state_seq_get_size(g_ctx, 0));
    if (state.empty() || llama_state_seq_get_data(g_ctx, state.data(), state.size(), 0) == 0) {
        return nullptr;
    }
    PrefixEntry entry;
    entry.text = prefix;
    entry.tokens.assign(tokens.begin(), tokens.begin() + (std::ptrdiff_t) n);
    entry.state = std::move(state);
    g_prefixes.erase(std::remove_if(g_prefixes.begin(), g_prefixes.end(),
                                    [&](const PrefixEntry & e) { return e.text == prefix; }),
                     g_prefixes.end());
    if (g_prefixes.size() >= kMaxPrefixEntries) g_prefixes.pop_back();
    g_prefixes.insert(g_prefixes.begin(), std::move(entry));
    LOGI("prefix cache built: %zu tokens, %zu bytes (%zu cached)", n, g_prefixes[0].state.size(),
         g_prefixes.size());
    return &g_prefixes[0];
}

// Loads `entry` into an emptied cache when it covers the start of `tokens`. Returns how many tokens
// it covers, or 0 when the caller must prefill from scratch.
size_t restore_prefix_cache(const PrefixEntry * entry, const std::vector<llama_token> & tokens) {
    if (!entry) return 0;
    const size_t n = entry->tokens.size();
    if (n == 0 || n >= tokens.size()) return 0;
    if (!std::equal(entry->tokens.begin(), entry->tokens.end(), tokens.begin())) return 0;
    llama_memory_clear(llama_get_memory(g_ctx), true);
    if (llama_state_seq_set_data(g_ctx, entry->state.data(), entry->state.size(), 0) == 0) {
        drop_prefix_cache();
        return 0;
    }
    return n;
}

// Brings seq 0 to the end of `tokens`: the cached `prefix` restored (built first when it is not
// cached yet) and only the rest decoded, or everything decoded cold when `prefix` is empty or its
// tokens do not start `tokens`. The last token's logits are then ready. False on failure or abort.
bool prefill(const std::string & prefix, const std::vector<llama_token> & tokens, int64_t request) {
    size_t reused = 0;
    if (!prefix.empty()) {
        const PrefixEntry * entry = find_prefix(prefix);
        if (!entry) {
            entry = build_prefix_cache(prefix, tokens, request);
            if (!entry && aborted(request)) return false;
        }
        // A prompt whose tokens do not start with the cached ones is prefilled cold rather than
        // answered from the wrong state.
        reused = restore_prefix_cache(entry, tokens);
    }
    if (reused == 0) llama_memory_clear(llama_get_memory(g_ctx), true);
    return decode_range(tokens, reused, tokens.size(), request);
}

// log(sum(exp(logits))) over the whole vocabulary, computed stably.
float log_sum_exp(const float * logits, int32_t n_vocab) {
    float max = logits[0];
    for (int32_t i = 1; i < n_vocab; ++i) max = std::max(max, logits[i]);
    double sum = 0.0;
    for (int32_t i = 0; i < n_vocab; ++i) sum += std::exp((double) (logits[i] - max));
    return max + (float) std::log(sum);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring backend_dir) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (g_backend_ready) return;   // registering the CPU backends twice is not supported
    llama_backend_init();
    const std::string dir = jstr(env, backend_dir);
    if (!dir.empty()) {
        // The CPU backend ships as one .so per ISA variant; pick the best for this chip.
        ggml_backend_load_all_from_path(dir.c_str());
    }
    g_backend_ready = true;
}

JNIEXPORT void JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jpath,
                                                            jint n_ctx, jint n_threads) {
    std::lock_guard<std::mutex> lock(g_mtx);
    const std::string path = jstr(env, jpath);
    if (g_model && g_ctx && path == g_model_path) return;

    free_model();

    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) {
        throw_java(env, "Failed to load model: " + path);
        return;
    }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) n_ctx;
    cp.n_batch         = 512;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        free_model();
        throw_java(env, "Failed to create llama context");
        return;
    }
    llama_set_n_threads(g_ctx, n_threads, n_threads);
    g_model_path = path;
    LOGI("loaded %s (n_ctx=%d, threads=%d)", path.c_str(), n_ctx, n_threads);
}

JNIEXPORT jboolean JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeIsLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeContextTokens(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    return g_ctx ? (jint) llama_n_ctx(g_ctx) : 0;
}

// Measures a string against the loaded model's own vocabulary. Budgeting a request by a
// characters-per-token guess is not safe: guess a few percent low and nativeComplete rejects the
// prompt outright, which is the failure the caller is splitting the text to avoid.
JNIEXPORT jint JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeTokenCount(JNIEnv * env, jobject, jstring jtext) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_vocab) {
        throw_java(env, "Model is not loaded");
        return 0;
    }
    return (jint) tokenize(jstr(env, jtext)).size();
}

JNIEXPORT jstring JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeComplete(JNIEnv * env, jobject, jstring jprompt,
                                                                jint max_tokens, jlong request) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_model || !g_ctx) {
        throw_java(env, "Model is not loaded");
        return nullptr;
    }

    const std::vector<llama_token> tokens = tokenize(jstr(env, jprompt));
    const int32_t n_ctx = (int32_t) llama_n_ctx(g_ctx);
    if (tokens.empty()) {
        throw_java(env, "Prompt produced no tokens");
        return nullptr;
    }
    if ((int32_t) tokens.size() + max_tokens >= n_ctx) {
        throw_java(env, "Prompt is too long for the context window");
        return nullptr;
    }

    // Cold cache for every call, so the output depends on this prompt alone.
    llama_memory_clear(llama_get_memory(g_ctx), true);
    if (!decode_range(tokens, 0, tokens.size(), (int64_t) request)) {
        if (aborted((int64_t) request)) return env->NewStringUTF("");
        throw_java(env, "Failed to decode prompt");
        return nullptr;
    }

    const std::string out = generate(tokens.size(), max_tokens, (int64_t) request);
    return env->NewStringUTF(out.c_str());
}

// The tone read's completion (#32). `prompt` begins with `prefix`, and the KV state of that
// opening is decoded once and reused by every later call with the same prefix. See the header for
// why nothing else may call this.
JNIEXPORT jstring JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeCompleteWithPrefix(
        JNIEnv * env, jobject, jstring jprefix, jstring jprompt, jint max_tokens, jlong request) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_model || !g_ctx) {
        throw_java(env, "Model is not loaded");
        return nullptr;
    }

    const std::string prefix = jstr(env, jprefix);
    const std::string prompt = jstr(env, jprompt);
    if (prompt.compare(0, prefix.size(), prefix) != 0) {
        throw_java(env, "Prompt does not start with its prefix");
        return nullptr;
    }
    const std::vector<llama_token> tokens = tokenize(prompt);
    const int32_t n_ctx = (int32_t) llama_n_ctx(g_ctx);
    if (tokens.empty()) {
        throw_java(env, "Prompt produced no tokens");
        return nullptr;
    }
    if ((int32_t) tokens.size() + max_tokens >= n_ctx) {
        throw_java(env, "Prompt is too long for the context window");
        return nullptr;
    }

    const int64_t req = (int64_t) request;
    if (!prefill(prefix, tokens, req)) {
        if (aborted(req)) return env->NewStringUTF("");
        throw_java(env, "Failed to decode prompt");
        return nullptr;
    }

    const std::string out = generate(tokens.size(), max_tokens, req);
    return env->NewStringUTF(out.c_str());
}

// Scores fixed candidate answers instead of generating one ("System One"). `prompt` is decoded once
// -- reusing the cached KV state of `prefix` when it is non-empty, exactly as
// nativeCompleteWithPrefix does -- and then, for each candidate, its tokens are appended, their
// log-probabilities read from the logits, and the cache is rolled back to the end of the prompt
// (llama_memory_seq_rm) before the next one. A single-token candidate costs no decode at all: its
// probability is already in the prompt's last logits.
//
// Candidates are tokenised on their own, so each should start with the space that would follow the
// prompt (" formal", " No"). Returns two floats per candidate: the summed natural-log probability
// of its tokens and how many tokens that was, so the caller can normalise by length. An aborted
// call returns an empty array.
JNIEXPORT jfloatArray JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeScoreChoices(
        JNIEnv * env, jobject, jstring jprefix, jstring jprompt, jobjectArray jchoices, jlong request) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_model || !g_ctx) {
        throw_java(env, "Model is not loaded");
        return nullptr;
    }

    const std::string prefix = jstr(env, jprefix);
    const std::string prompt = jstr(env, jprompt);
    if (prompt.compare(0, prefix.size(), prefix) != 0) {
        throw_java(env, "Prompt does not start with its prefix");
        return nullptr;
    }
    const jsize n_choices = jchoices ? env->GetArrayLength(jchoices) : 0;
    std::vector<std::vector<llama_token>> choices((size_t) n_choices);
    size_t longest = 0;
    for (jsize i = 0; i < n_choices; ++i) {
        auto js = (jstring) env->GetObjectArrayElement(jchoices, i);
        choices[(size_t) i] = tokenize(jstr(env, js));
        env->DeleteLocalRef(js);
        longest = std::max(longest, choices[(size_t) i].size());
    }

    const std::vector<llama_token> tokens = tokenize(prompt);
    const int32_t n_ctx = (int32_t) llama_n_ctx(g_ctx);
    if (tokens.empty()) {
        throw_java(env, "Prompt produced no tokens");
        return nullptr;
    }
    if ((int32_t) (tokens.size() + longest) >= n_ctx) {
        throw_java(env, "Prompt is too long for the context window");
        return nullptr;
    }

    const int64_t req = (int64_t) request;
    if (!prefill(prefix, tokens, req)) {
        if (aborted(req)) return env->NewFloatArray(0);
        throw_java(env, "Failed to decode prompt");
        return nullptr;
    }

    // Decoding the first multi-token candidate overwrites the prompt's last logits, so keep the
    // log-probability they give every possible first token.
    const int32_t n_vocab = llama_vocab_n_tokens(g_vocab);
    std::vector<float> first((size_t) n_vocab);
    {
        const float * logits = llama_get_logits_ith(g_ctx, -1);
        const float   lse    = log_sum_exp(logits, n_vocab);
        for (int32_t v = 0; v < n_vocab; ++v) first[(size_t) v] = logits[v] - lse;
    }

    const llama_pos    n_prompt = (llama_pos) tokens.size();
    llama_memory_t     mem      = llama_get_memory(g_ctx);
    std::vector<float> out((size_t) n_choices * 2, 0.0f);
    for (jsize c = 0; c < n_choices; ++c) {
        if (aborted(req)) return env->NewFloatArray(0);
        const std::vector<llama_token> & toks = choices[(size_t) c];
        if (toks.empty()) {
            out[(size_t) c * 2] = -INFINITY;
            continue;
        }
        double sum = first[(size_t) toks[0]];
        if (toks.size() > 1) {
            // Every token but the last is decoded, each with logits: each predicts the next one.
            const int32_t n = (int32_t) toks.size() - 1;
            llama_batch batch = llama_batch_init(n, 0, 1);
            for (int32_t k = 0; k < n; ++k) {
                batch.token[k]     = toks[(size_t) k];
                batch.pos[k]       = n_prompt + k;
                batch.n_seq_id[k]  = 1;
                batch.seq_id[k][0] = 0;
                batch.logits[k]    = true;
            }
            batch.n_tokens = n;
            const int rc = llama_decode(g_ctx, batch);
            llama_batch_free(batch);
            if (rc != 0) {
                throw_java(env, "Failed to decode a choice");
                return nullptr;
            }
            for (int32_t k = 0; k < n; ++k) {
                const float * logits = llama_get_logits_ith(g_ctx, k);
                sum += logits[toks[(size_t) k + 1]] - log_sum_exp(logits, n_vocab);
            }
            // Back to the end of the prompt, so the next candidate follows the prompt alone.
            if (!llama_memory_seq_rm(mem, 0, n_prompt, -1)) {
                throw_java(env, "Failed to roll the KV cache back");
                return nullptr;
            }
        }
        out[(size_t) c * 2]     = (float) sum;
        out[(size_t) c * 2 + 1] = (float) toks.size();
    }

    jfloatArray result = env->NewFloatArray((jsize) out.size());
    env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return result;
}

// Drops every saved opening, e.g. when the app is trimming memory.
JNIEXPORT void JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeDropPrefixCache(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    drop_prefix_cache();
}

// Deliberately takes no lock: the completion it stops is holding it.
JNIEXPORT void JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeAbort(JNIEnv *, jobject, jlong request) {
    g_abort_request.store((int64_t) request, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_raghavsatyadev_grammarfix_llama_LlamaNative_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    free_model();
}

} // extern "C"
