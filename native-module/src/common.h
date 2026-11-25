#pragma once
#include <android/log.h>
#include <string>
#include <unordered_map>

#define LOG_TAG "SlimanNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/** key=value pairs of the profile that matched the process being started. */
struct Config {
    std::unordered_map<std::string, std::string> kv;

    const std::string &get(const char *key) const {
        static const std::string empty;
        auto it = kv.find(key);
        return it == kv.end() ? empty : it->second;
    }
    bool has(const char *key) const { return !get(key).empty(); }
};

/** Reads <moduledir>/profiles.conf (through [dirfd]) and fills [out] with the profile for [process]. */
bool loadConfig(int dirfd, const std::string &process, Config &out);

/** Installs all native hooks described by [cfg] in the current process. */
void installHooks(const Config &cfg);
