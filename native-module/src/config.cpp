#include "common.h"
#include <fcntl.h>
#include <unistd.h>

static std::string readAll(int fd) {
    std::string s;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) s.append(buf, (size_t) n);
    return s;
}

static bool matches(const std::string &section, const std::string &process) {
    if (section.empty() || process.empty()) return false;
    if (process == section) return true;
    // isolated / auxiliary processes: "com.pkg:sandboxed_process0:..." and "com.pkg:service"
    return process.size() > section.size() && process.compare(0, section.size(), section) == 0 &&
           process[section.size()] == ':';
}

bool loadConfig(int dirfd, const std::string &process, Config &out) {
    int fd = openat(dirfd, "profiles.conf", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    std::string text = readAll(fd);
    close(fd);

    bool inSection = false;
    size_t pos = 0;
    while (pos < text.size()) {
        size_t nl = text.find('\n', pos);
        if (nl == std::string::npos) nl = text.size();
        std::string line = text.substr(pos, nl - pos);
        pos = nl + 1;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) continue;
        if (line.front() == '[' && line.back() == ']') {
            inSection = matches(line.substr(1, line.size() - 2), process);
            continue;
        }
        if (!inSection) continue;
        size_t eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        out.kv[line.substr(0, eq)] = line.substr(eq + 1);
    }
    if (out.kv.empty()) return false;
    if (out.get("enabled") == "0") return false;
    return true;
}
