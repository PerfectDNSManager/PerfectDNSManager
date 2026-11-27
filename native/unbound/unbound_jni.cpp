#include <jni.h>
#include <poll.h>
#include <unbound.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
struct Request {
    std::mutex mutex;
    std::condition_variable ready;
    bool done = false;
    std::vector<unsigned char> packet;
};
void answer(void* arg, int error, ub_result* result) {
    auto* request = static_cast<Request*>(arg);
    {
        std::lock_guard<std::mutex> lock(request->mutex);
        if (!error && result && !result->bogus && result->answer_packet &&
            result->answer_len >= 12 && result->answer_len <= 8192) {
            auto* bytes = static_cast<unsigned char*>(result->answer_packet);
            try { request->packet.assign(bytes, bytes + result->answer_len); }
            catch (...) { request->packet.clear(); }
            if (!request->packet.empty()) {
            if (result->secure) request->packet[3] |= 0x20;
            else request->packet[3] &= ~0x20;
            }
        }
        request->done = true;
    }
    if (result) ub_resolve_free(result);
    request->ready.notify_all();
}
struct Engine {
    ub_ctx* ctx = ub_ctx_create();
    std::mutex mutex;
    std::atomic<bool> closed{false};
    std::thread worker;
    std::unordered_map<int, std::shared_ptr<Request>> pending;
    bool option(const char* name, const char* value) { return ub_ctx_set_option(ctx, name, value) == 0; }
    void start() {
        worker = std::thread([this] {
            // Wait for actual answers rather than waking 100 times/second at idle.
            pollfd descriptor{ub_fd(ctx), POLLIN, 0};
            while (!closed.load()) {
                int events = ::poll(&descriptor, 1, 100);
                if (events > 0 && !closed.load()) {
                    std::lock_guard<std::mutex> lock(mutex);
                    if (ctx && ub_poll(ctx)) ub_process(ctx);
                } else if (events < 0) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                }
            }
        });
    }
    void close() {
        if (closed.exchange(true)) return;
        if (worker.joinable()) worker.join();
        std::lock_guard<std::mutex> lock(mutex);
        // Stop Unbound's internal worker before releasing any callback arguments.
        if (ctx) { ub_ctx_delete(ctx); ctx = nullptr; }
        for (auto& entry : pending) {
            auto& request = entry.second;
            { std::lock_guard<std::mutex> rlock(request->mutex); request->done = true; }
            request->ready.notify_all();
        }
        pending.clear();
    }
    ~Engine() { close(); }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Engine>> registry;
jlong nextId = 1;
std::string text(JNIEnv* env, jstring input) {
    if (!input) return {};
    const char* utf = env->GetStringUTFChars(input, nullptr);
    if (!utf) return {};
    std::string result(utf); env->ReleaseStringUTFChars(input, utf); return result;
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_perfectdnsmanager_service_UnboundNative_create(JNIEnv* env, jobject, jobjectArray addresses, jstring ca, jstring anchors) {
    try {
        if (!addresses || env->GetArrayLength(addresses) < 1 || env->GetArrayLength(addresses) > 8) return 0;
        auto engine = std::make_shared<Engine>();
        if (!engine->ctx) return 0;
        if (!engine->option("verbosity:", "0") || !engine->option("use-syslog:", "no") ||
            !engine->option("log-queries:", "no") || !engine->option("log-replies:", "no") ||
            !engine->option("log-servfail:", "no") || !engine->option("msg-cache-size:", "2m") ||
            !engine->option("rrset-cache-size:", "4m") || !engine->option("key-cache-size:", "1m") ||
            !engine->option("outgoing-num-tcp:", "8") || !engine->option("do-udp:", "no") ||
            !engine->option("tls-use-sni:", "yes") ||
            !engine->option("tls-cert-bundle:", text(env, ca).c_str()) ||
            ub_ctx_add_ta_file(engine->ctx, text(env, anchors).c_str()) != 0 ||
            ub_ctx_async(engine->ctx, 1) != 0) return 0;
        for (jsize i = 0; i < env->GetArrayLength(addresses); ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(addresses, i));
            std::string address = text(env, value); env->DeleteLocalRef(value);
            // Every address must carry a TLS authentication name, never anonymous TLS.
            if (address.empty() || address.size() > 512 || address.find('#') == std::string::npos ||
                address.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-:@#") != std::string::npos ||
                ub_ctx_set_fwd(engine->ctx, address.c_str()) != 0) return 0;
        }
        if (ub_ctx_set_tls(engine->ctx, 1) != 0) return 0;
        engine->start();
        std::lock_guard<std::mutex> lock(registryMutex);
        jlong id = nextId++; registry.emplace(id, engine); return id;
    } catch (...) { return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_perfectdnsmanager_service_UnboundNative_resolve(JNIEnv* env, jobject, jlong id, jstring domain, jint type) {
    try {
        std::shared_ptr<Engine> engine;
        { std::lock_guard<std::mutex> lock(registryMutex); auto it=registry.find(id); if(it==registry.end()) return nullptr; engine=it->second; }
        std::string name = text(env, domain);
        if (name.empty() || name.size() > 254 || type < 1 || type > 65535) return nullptr;
        auto request = std::make_shared<Request>();
        int requestId = 0;
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            if (engine->closed.load() || !engine->ctx || engine->pending.size() >= 64) return nullptr;
            if (ub_resolve_async(engine->ctx, name.c_str(), type, 1, request.get(), answer, &requestId) != 0) return nullptr;
            try { engine->pending.emplace(requestId, request); }
            catch (...) { ub_cancel(engine->ctx, requestId); return nullptr; }
        }
        {
            std::unique_lock<std::mutex> lock(request->mutex);
            request->ready.wait_for(lock, std::chrono::seconds(8), [&] { return request->done; });
        }
        std::vector<unsigned char> packet;
        {
            std::lock_guard<std::mutex> lock(engine->mutex);
            std::lock_guard<std::mutex> rlock(request->mutex);
            if (!request->done && engine->ctx) ub_cancel(engine->ctx, requestId);
            if (!engine->closed.load()) packet = request->packet;
            engine->pending.erase(requestId);
        }
        if (packet.empty()) return nullptr;
        jbyteArray result = env->NewByteArray(static_cast<jsize>(packet.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(packet.size()), reinterpret_cast<jbyte*>(packet.data()));
        return result;
    } catch (...) { return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_app_perfectdnsmanager_service_UnboundNative_destroy(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<Engine> engine;
    { std::lock_guard<std::mutex> lock(registryMutex); auto it=registry.find(id); if(it==registry.end()) return; engine=it->second; registry.erase(it); }
    engine->close();
}
