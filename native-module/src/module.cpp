#include <jni.h>
#include <string>
#include "common.h"
#include "zygisk.hpp"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

static Config g_cfg;

class SlimanModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    // Still running with zygote privileges: the only place where the config can be read.
    void preAppSpecialize(AppSpecializeArgs *args) override {
        std::string process;
        if (args->nice_name) {
            const char *n = env_->GetStringUTFChars(args->nice_name, nullptr);
            if (n) {
                process = n;
                env_->ReleaseStringUTFChars(args->nice_name, n);
            }
        }
        int dir = api_->getModuleDir();
        active_ = dir >= 0 && !process.empty() && loadConfig(dir, process, g_cfg);
        if (!active_) api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (!active_) return;
        LOGI("profile matched, installing native hooks");
        installHooks(g_cfg);
    }

    void preServerSpecialize(ServerSpecializeArgs *) override {
        api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool active_ = false;
};

REGISTER_ZYGISK_MODULE(SlimanModule)
