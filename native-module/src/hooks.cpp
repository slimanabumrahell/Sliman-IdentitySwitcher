// Native hooks: GOT/PLT patching of libc (and GL) entry points inside the target app process.
//
// How it works: for every loaded library we scan its writable data / RELRO segments for pointers that are
// equal to the real address of a function we want to replace (that is exactly what a GOT slot holds) and
// overwrite them with our wrapper. Libraries loaded later are handled by hooking dlopen / android_dlopen_ext.
// Because a slot is only written when it already holds the real function's address, a wrong guess can never
// corrupt anything: the worst case is a hook that does not take effect.

#include "common.h"

#include <algorithm>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <dlfcn.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <link.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <linux/sockios.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/sysinfo.h>
#include <sys/utsname.h>
#include <unistd.h>

#ifndef O_TMPFILE
#define O_TMPFILE 020200000
#endif

namespace {

// ============================================================ spoofed state (filled from Config)

std::unordered_map<std::string, std::string> g_props;   // ro.* -> value
std::string g_kernel, g_bootId, g_soc, g_glVendor, g_glRenderer;
long g_cores = 0;
unsigned long long g_ramBytes = 0;
double g_storageRatio = 0;
unsigned long long g_realDataTotal = 0;
unsigned char g_mac[6];
bool g_hasMac = false;

std::string readFileRaw(const char *path) {
    std::string s;
    int fd = open(path, O_RDONLY | O_CLOEXEC);   // our own GOT is never patched -> real libc
    if (fd < 0) return s;
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) s.append(buf, (size_t) n);
    close(fd);
    return s;
}

void randomBytes(unsigned char *out, size_t n) {
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (read(fd, out, n) != (ssize_t) n) memset(out, 0x5a, n);
        close(fd);
    } else {
        memset(out, 0x5a, n);
    }
}

std::string freshUuid() {
    std::string u = readFileRaw("/proc/sys/kernel/random/uuid");
    while (!u.empty() && (u.back() == '\n' || u.back() == '\r')) u.pop_back();
    return u;
}

// ============================================================ synthesized files

std::vector<std::string> splitLines(const std::string &s) {
    std::vector<std::string> v;
    size_t pos = 0;
    while (pos <= s.size()) {
        size_t nl = s.find('\n', pos);
        if (nl == std::string::npos) { if (pos < s.size()) v.push_back(s.substr(pos)); break; }
        v.push_back(s.substr(pos, nl - pos));
        pos = nl + 1;
    }
    return v;
}

std::string fixMeminfo(const std::string &real) {
    unsigned long long realKb = 0;
    auto lines = splitLines(real);
    for (auto &l : lines)
        if (l.compare(0, 9, "MemTotal:") == 0) realKb = strtoull(l.c_str() + 9, nullptr, 10);
    if (realKb == 0) return real;
    double ratio = (double) (g_ramBytes / 1024) / (double) realKb;
    std::string out;
    char buf[128];
    for (auto &l : lines) {
        if (l.compare(0, 9, "MemTotal:") == 0) {
            snprintf(buf, sizeof(buf), "%-16s%8llu kB\n", "MemTotal:", (unsigned long long) (g_ramBytes / 1024));
            out += buf;
        } else if (l.compare(0, 8, "MemFree:") == 0) {
            snprintf(buf, sizeof(buf), "%-16s%8llu kB\n", "MemFree:", (unsigned long long) (strtoull(l.c_str() + 8, nullptr, 10) * ratio));
            out += buf;
        } else if (l.compare(0, 13, "MemAvailable:") == 0) {
            snprintf(buf, sizeof(buf), "%-16s%8llu kB\n", "MemAvailable:", (unsigned long long) (strtoull(l.c_str() + 13, nullptr, 10) * ratio));
            out += buf;
        } else {
            out += l + "\n";
        }
    }
    return out;
}

std::string fixCpuinfo(const std::string &real) {
    // Split in blocks separated by an empty line; "processor" blocks are resized to g_cores.
    std::vector<std::string> procs, tail;
    size_t pos = 0;
    while (pos < real.size()) {
        size_t end = real.find("\n\n", pos);
        std::string block = real.substr(pos, end == std::string::npos ? std::string::npos : end - pos);
        pos = end == std::string::npos ? real.size() : end + 2;
        if (block.empty()) continue;
        (block.compare(0, 9, "processor") == 0 ? procs : tail).push_back(block);
    }
    if (procs.empty()) return real;
    long n = g_cores > 0 ? g_cores : (long) procs.size();
    std::string out;
    for (long i = 0; i < n; i++) {
        std::string b = procs[std::min<size_t>((size_t) i, procs.size() - 1)];
        size_t nl = b.find('\n');
        std::string rest = nl == std::string::npos ? "" : b.substr(nl);
        out += "processor\t: " + std::to_string(i) + rest + "\n\n";
    }
    for (auto &t : tail) {
        if (!g_soc.empty() && t.compare(0, 8, "Hardware") == 0) out += "Hardware\t: " + g_soc + "\n\n";
        else out += t + "\n\n";
    }
    return out;
}

std::string cpuRange() { return g_cores <= 1 ? "0\n" : "0-" + std::to_string(g_cores - 1) + "\n"; }

/** Returns true (and the fake content) when [path] must be served from memory instead of the real file. */
bool synth(const char *path, std::string &out) {
    if (!path || path[0] != '/') return false;
    if (g_ramBytes && !strcmp(path, "/proc/meminfo")) { out = fixMeminfo(readFileRaw(path)); return true; }
    if ((g_cores || !g_soc.empty()) && !strcmp(path, "/proc/cpuinfo")) { out = fixCpuinfo(readFileRaw(path)); return true; }
    if (!g_kernel.empty() && !strcmp(path, "/proc/version")) {
        std::string v = readFileRaw(path);
        const std::string key = "Linux version ";
        if (v.compare(0, key.size(), key) == 0) {
            size_t sp = v.find(' ', key.size());
            out = key + g_kernel + (sp == std::string::npos ? "\n" : v.substr(sp));
            return true;
        }
        return false;
    }
    if (!g_bootId.empty() && !strcmp(path, "/proc/sys/kernel/random/boot_id")) { out = g_bootId + "\n"; return true; }
    if (g_cores && (!strcmp(path, "/sys/devices/system/cpu/possible") || !strcmp(path, "/sys/devices/system/cpu/present") ||
                    !strcmp(path, "/sys/devices/system/cpu/online"))) { out = cpuRange(); return true; }
    if (g_hasMac && !strcmp(path, "/sys/class/net/wlan0/address")) {
        char b[32];
        snprintf(b, sizeof(b), "%02x:%02x:%02x:%02x:%02x:%02x\n", g_mac[0], g_mac[1], g_mac[2], g_mac[3], g_mac[4], g_mac[5]);
        out = b;
        return true;
    }
    return false;
}

int memfdFrom(const std::string &s) {
    int fd = (int) syscall(__NR_memfd_create, "sliman", 1u /* MFD_CLOEXEC */);
    if (fd < 0) return -1;
    size_t off = 0;
    while (off < s.size()) {
        ssize_t n = write(fd, s.data() + off, s.size() - off);
        if (n <= 0) { close(fd); return -1; }
        off += (size_t) n;
    }
    lseek(fd, 0, SEEK_SET);
    return fd;
}

// ============================================================ hooked functions

// ---- system properties
int (*orig_prop_get)(const char *, char *);
int my_prop_get(const char *name, char *value) {
    if (name && value) {
        auto it = g_props.find(name);
        if (it != g_props.end()) {
            size_t n = std::min<size_t>(it->second.size(), 91);
            memcpy(value, it->second.data(), n);
            value[n] = 0;
            return (int) n;
        }
    }
    return orig_prop_get(name, value);
}

struct FakeProp { std::string name, value; };
std::mutex g_fakeMu;
std::unordered_map<std::string, FakeProp *> g_fakeByName;
std::unordered_map<const void *, FakeProp *> g_fakeByPtr;

FakeProp *fakeFor(const void *pi) {
    std::lock_guard<std::mutex> lk(g_fakeMu);
    auto it = g_fakeByPtr.find(pi);
    return it == g_fakeByPtr.end() ? nullptr : it->second;
}

const void *(*orig_prop_find)(const char *);
const void *my_prop_find(const char *name) {
    if (name) {
        auto it = g_props.find(name);
        if (it != g_props.end()) {
            std::lock_guard<std::mutex> lk(g_fakeMu);
            FakeProp *&fp = g_fakeByName[name];
            if (!fp) { fp = new FakeProp{name, it->second}; g_fakeByPtr[fp] = fp; }
            return fp;
        }
    }
    return orig_prop_find(name);
}

void (*orig_prop_read_cb)(const void *, void (*)(void *, const char *, const char *, uint32_t), void *);
void my_prop_read_cb(const void *pi, void (*cb)(void *, const char *, const char *, uint32_t), void *cookie) {
    if (FakeProp *fp = fakeFor(pi)) { cb(cookie, fp->name.c_str(), fp->value.c_str(), 1); return; }
    orig_prop_read_cb(pi, cb, cookie);
}

int (*orig_prop_read)(const void *, char *, char *);
int my_prop_read(const void *pi, char *name, char *value) {
    if (FakeProp *fp = fakeFor(pi)) {
        if (name) { strncpy(name, fp->name.c_str(), 31); name[31] = 0; }
        size_t n = std::min<size_t>(fp->value.size(), 91);
        if (value) { memcpy(value, fp->value.data(), n); value[n] = 0; }
        return (int) n;
    }
    return orig_prop_read(pi, name, value);
}

uint32_t (*orig_prop_serial)(const void *);
uint32_t my_prop_serial(const void *pi) { return fakeFor(pi) ? 1u : orig_prop_serial(pi); }

// ---- uname / cpu / memory
int (*orig_uname)(struct utsname *);
int my_uname(struct utsname *u) {
    int r = orig_uname(u);
    if (r == 0 && u && !g_kernel.empty()) { strncpy(u->release, g_kernel.c_str(), sizeof(u->release) - 1); u->release[sizeof(u->release) - 1] = 0; }
    return r;
}

long (*orig_sysconf)(int);
long my_sysconf(int n) {
    if (g_cores > 0 && (n == _SC_NPROCESSORS_CONF || n == _SC_NPROCESSORS_ONLN)) return g_cores;
    if (g_ramBytes && n == _SC_PHYS_PAGES) return (long) (g_ramBytes / (unsigned long long) orig_sysconf(_SC_PAGESIZE));
    if (g_ramBytes && n == _SC_AVPHYS_PAGES) {
        long total = orig_sysconf(_SC_PHYS_PAGES), avail = orig_sysconf(_SC_AVPHYS_PAGES);
        long fakeTotal = (long) (g_ramBytes / (unsigned long long) orig_sysconf(_SC_PAGESIZE));
        return total > 0 ? (long) ((double) avail * fakeTotal / total) : avail;
    }
    return orig_sysconf(n);
}

int (*orig_get_nprocs)();
int my_get_nprocs() { return g_cores > 0 ? (int) g_cores : orig_get_nprocs(); }

int (*orig_sysinfo)(struct sysinfo *);
int my_sysinfo(struct sysinfo *si) {
    int r = orig_sysinfo(si);
    if (r == 0 && si && g_ramBytes && si->totalram) {
        double unit = si->mem_unit ? si->mem_unit : 1;
        double ratio = (double) g_ramBytes / ((double) si->totalram * unit);
        si->totalram = (unsigned long) ((double) si->totalram * ratio);
        si->freeram = (unsigned long) ((double) si->freeram * ratio);
        si->bufferram = (unsigned long) ((double) si->bufferram * ratio);
    }
    return r;
}

// ---- file redirects
int (*orig_open)(const char *, int, ...);
int my_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = (mode_t) va_arg(ap, int); va_end(ap); }
    if ((flags & O_ACCMODE) == O_RDONLY) {
        std::string c;
        if (synth(path, c)) { int fd = memfdFrom(c); if (fd >= 0) return fd; }
    }
    return orig_open(path, flags, mode);
}

int (*orig_openat)(int, const char *, int, ...);
int my_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = (mode_t) va_arg(ap, int); va_end(ap); }
    if ((flags & O_ACCMODE) == O_RDONLY) {
        std::string c;
        if (synth(path, c)) { int fd = memfdFrom(c); if (fd >= 0) return fd; }
    }
    return orig_openat(dirfd, path, flags, mode);
}

int (*orig_open_2)(const char *, int);
int my_open_2(const char *path, int flags) {
    if ((flags & O_ACCMODE) == O_RDONLY) {
        std::string c;
        if (synth(path, c)) { int fd = memfdFrom(c); if (fd >= 0) return fd; }
    }
    return orig_open_2(path, flags);
}

int (*orig_openat_2)(int, const char *, int);
int my_openat_2(int dirfd, const char *path, int flags) {
    if ((flags & O_ACCMODE) == O_RDONLY) {
        std::string c;
        if (synth(path, c)) { int fd = memfdFrom(c); if (fd >= 0) return fd; }
    }
    return orig_openat_2(dirfd, path, flags);
}

FILE *(*orig_fopen)(const char *, const char *);
FILE *my_fopen(const char *path, const char *mode) {
    if (mode && mode[0] == 'r' && !strchr(mode, '+')) {
        std::string c;
        if (synth(path, c)) {
            int fd = memfdFrom(c);
            if (fd >= 0) { if (FILE *f = fdopen(fd, "r")) return f; close(fd); }
        }
    }
    return orig_fopen(path, mode);
}

// ---- storage
template <class S>
void scaleFs(S *s) {
    if (!s || g_storageRatio <= 0 || g_realDataTotal == 0) return;
    double bs = (double) s->f_bsize;
    double total = (double) s->f_blocks * bs;
    // Only the data partition (same size as the real /data): leave /system, /vendor... alone.
    if (total < g_realDataTotal * 0.98 || total > g_realDataTotal * 1.02) return;
    s->f_blocks = (decltype(s->f_blocks)) ((double) s->f_blocks * g_storageRatio);
    s->f_bfree = (decltype(s->f_bfree)) ((double) s->f_bfree * g_storageRatio);
    s->f_bavail = (decltype(s->f_bavail)) ((double) s->f_bavail * g_storageRatio);
}

int (*orig_statfs)(const char *, struct statfs *);
int my_statfs(const char *p, struct statfs *s) { int r = orig_statfs(p, s); if (r == 0) scaleFs(s); return r; }
int (*orig_fstatfs)(int, struct statfs *);
int my_fstatfs(int fd, struct statfs *s) { int r = orig_fstatfs(fd, s); if (r == 0) scaleFs(s); return r; }
int (*orig_statfs64)(const char *, struct statfs64 *);
int my_statfs64(const char *p, struct statfs64 *s) { int r = orig_statfs64(p, s); if (r == 0) scaleFs(s); return r; }
int (*orig_fstatfs64)(int, struct statfs64 *);
int my_fstatfs64(int fd, struct statfs64 *s) { int r = orig_fstatfs64(fd, s); if (r == 0) scaleFs(s); return r; }

template <class S>
void scaleVfs(S *s) {
    if (!s || g_storageRatio <= 0 || g_realDataTotal == 0) return;
    double bs = (double) s->f_frsize;
    double total = (double) s->f_blocks * bs;
    if (total < g_realDataTotal * 0.98 || total > g_realDataTotal * 1.02) return;
    s->f_blocks = (decltype(s->f_blocks)) ((double) s->f_blocks * g_storageRatio);
    s->f_bfree = (decltype(s->f_bfree)) ((double) s->f_bfree * g_storageRatio);
    s->f_bavail = (decltype(s->f_bavail)) ((double) s->f_bavail * g_storageRatio);
}

int (*orig_statvfs)(const char *, struct statvfs *);
int my_statvfs(const char *p, struct statvfs *s) { int r = orig_statvfs(p, s); if (r == 0) scaleVfs(s); return r; }
int (*orig_fstatvfs)(int, struct statvfs *);
int my_fstatvfs(int fd, struct statvfs *s) { int r = orig_fstatvfs(fd, s); if (r == 0) scaleVfs(s); return r; }
int (*orig_statvfs64)(const char *, struct statvfs64 *);
int my_statvfs64(const char *p, struct statvfs64 *s) { int r = orig_statvfs64(p, s); if (r == 0) scaleVfs(s); return r; }
int (*orig_fstatvfs64)(int, struct statvfs64 *);
int my_fstatvfs64(int fd, struct statvfs64 *s) { int r = orig_fstatvfs64(fd, s); if (r == 0) scaleVfs(s); return r; }

// ---- MAC address
int (*orig_ioctl)(int, int, ...);
int my_ioctl(int fd, int req, ...) {
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    int r = orig_ioctl(fd, req, arg);
    if (r == 0 && req == SIOCGIFHWADDR && g_hasMac && arg) {
        auto *ifr = (struct ifreq *) arg;
        if (!strncmp(ifr->ifr_name, "wlan", 4)) memcpy(ifr->ifr_hwaddr.sa_data, g_mac, 6);
    }
    return r;
}

int (*orig_getifaddrs)(struct ifaddrs **);
int my_getifaddrs(struct ifaddrs **out) {
    int r = orig_getifaddrs(out);
    if (r == 0 && g_hasMac && out) {
        for (struct ifaddrs *i = *out; i; i = i->ifa_next) {
            if (i->ifa_name && i->ifa_addr && i->ifa_addr->sa_family == AF_PACKET && !strncmp(i->ifa_name, "wlan", 4))
                memcpy(((struct sockaddr_ll *) i->ifa_addr)->sll_addr, g_mac, 6);
        }
    }
    return r;
}

// ---- GPU strings (WebGL renderer / vendor)
typedef const unsigned char *(*GlGetString)(unsigned int);
GlGetString g_orig_glGetString = nullptr;
void *(*real_dlsym)(void *, const char *) = nullptr;
bool g_glOn = false;

const unsigned char *my_glGetString(unsigned int name) {
    if (!g_orig_glGetString && real_dlsym) g_orig_glGetString = (GlGetString) real_dlsym(RTLD_DEFAULT, "glGetString");
    if (name == 0x1F00 && !g_glVendor.empty()) return (const unsigned char *) g_glVendor.c_str();     // GL_VENDOR
    if (name == 0x1F01 && !g_glRenderer.empty()) return (const unsigned char *) g_glRenderer.c_str(); // GL_RENDERER
    return g_orig_glGetString ? g_orig_glGetString(name) : nullptr;
}

void *(*orig_eglGetProcAddress)(const char *);
void *my_eglGetProcAddress(const char *n) {
    void *p = orig_eglGetProcAddress(n);
    if (p && n && g_glOn && !strcmp(n, "glGetString")) { g_orig_glGetString = (GlGetString) p; return (void *) &my_glGetString; }
    return p;
}

void *my_dlsym(void *h, const char *s) {
    void *p = real_dlsym(h, s);
    if (p && s && g_glOn && !strcmp(s, "glGetString")) { g_orig_glGetString = (GlGetString) p; return (void *) &my_glGetString; }
    return p;
}

// ============================================================ GOT patching engine

struct HookDef { const char *name; void *repl; void **orig; uintptr_t addr; };
std::vector<HookDef> g_defs;
std::unordered_map<uintptr_t, size_t> g_byAddr;
uintptr_t g_lo = 0, g_hi = 0;

std::mutex g_scanMu;
std::unordered_set<const void *> g_done;
thread_local bool t_scanning = false;

void addHook(const char *name, void *repl, void **orig) { g_defs.push_back({name, repl, orig, 0}); }

/** Resolves hooks whose target exists now (GL libraries may only appear later). Returns true if anything new resolved. */
bool resolvePending() {
    bool changed = false;
    for (auto &d : g_defs) {
        if (d.addr) continue;
        void *a = dlsym(RTLD_DEFAULT, d.name);
        if (!a) continue;
        d.addr = (uintptr_t) a;
        *d.orig = a;
        changed = true;
    }
    if (changed) {
        g_byAddr.clear();
        g_lo = UINTPTR_MAX; g_hi = 0;
        for (size_t i = 0; i < g_defs.size(); i++) {
            if (!g_defs[i].addr) continue;
            g_byAddr.emplace(g_defs[i].addr, i);   // first definition wins for aliased symbols
            g_lo = std::min(g_lo, g_defs[i].addr);
            g_hi = std::max(g_hi, g_defs[i].addr);
        }
    }
    return changed;
}

struct MapRange { uintptr_t start, end; };
struct ScanCtx { bool loaded = false; std::vector<MapRange> readable; uintptr_t self = 0; };

void loadMaps(ScanCtx &c) {
    c.loaded = true;
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        unsigned long s, e;
        char perms[8];
        if (sscanf(line, "%lx-%lx %7s", &s, &e, perms) == 3 && perms[0] == 'r') c.readable.push_back({s, e});
    }
    fclose(f);
}

int g_failed = 0;

void writeSlot(uintptr_t slot, void *repl, bool relro) {
    const uintptr_t page = (uintptr_t) sysconf(_SC_PAGESIZE);
    uintptr_t pg = slot & ~(page - 1);
    if (mprotect((void *) pg, page, PROT_READ | PROT_WRITE) != 0) { g_failed++; return; }
    *(volatile uintptr_t *) slot = (uintptr_t) repl;
    if (relro) mprotect((void *) pg, page, PROT_READ);
}

int patchRange(uintptr_t lo, uintptr_t hi, uintptr_t rs, uintptr_t re) {
    int n = 0;
    const size_t w = sizeof(uintptr_t);
    lo = (lo + w - 1) & ~(w - 1);
    for (uintptr_t p = lo; p + w <= hi; p += w) {
        uintptr_t v = *(volatile uintptr_t *) p;
        if (v < g_lo || v > g_hi) continue;
        auto it = g_byAddr.find(v);
        if (it == g_byAddr.end()) continue;
        writeSlot(p, g_defs[it->second].repl, p >= rs && p < re);
        n++;
    }
    return n;
}

bool skipLib(const char *name) {
    static const char *skip[] = {"/libc.so", "/libdl.so", "/libm.so", "/linker", "/linker64", "ld-android.so"};
    std::string n = name;
    for (auto *s : skip) {
        size_t l = strlen(s);
        if (n.size() >= l && n.compare(n.size() - l, l, s) == 0) return true;
    }
    return false;
}

int phdrCb(struct dl_phdr_info *info, size_t, void *data) {
    auto *ctx = (ScanCtx *) data;
    const void *key = (const void *) info->dlpi_phdr;
    if (g_done.count(key)) return 0;
    g_done.insert(key);
    const char *name = info->dlpi_name ? info->dlpi_name : "";
    if (!*name || skipLib(name)) return 0;

    uintptr_t bias = info->dlpi_addr, rs = 0, re = 0;
    bool self = false;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        if (ph.p_type == PT_GNU_RELRO) { rs = bias + ph.p_vaddr; re = rs + ph.p_memsz; }
        if (ph.p_type == PT_LOAD && ctx->self >= bias + ph.p_vaddr && ctx->self < bias + ph.p_vaddr + ph.p_memsz) self = true;
    }
    if (self) return 0;   // never patch our own module

    int patched = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_W)) continue;
        if (!ctx->loaded) loadMaps(*ctx);
        uintptr_t s = bias + ph.p_vaddr, e = s + ph.p_memsz;
        for (auto &m : ctx->readable) {
            uintptr_t lo = std::max(m.start, s), hi = std::min(m.end, e);
            if (lo < hi) patched += patchRange(lo, hi, rs, re);
        }
    }
    if (patched) LOGI("patched %d slot(s) in %s", patched, name);
    return 0;
}

void scanAll() {
    if (t_scanning) return;
    // try_lock: another thread may be inside dl_iterate_phdr holding the loader lock while we run from a dlopen
    // constructor, so waiting here could deadlock. A skipped scan is simply repeated at the next library load.
    std::unique_lock<std::mutex> lk(g_scanMu, std::try_to_lock);
    if (!lk.owns_lock()) return;
    t_scanning = true;
    if (resolvePending()) g_done.clear();
    ScanCtx ctx;
    ctx.self = (uintptr_t) &scanAll;
    dl_iterate_phdr(phdrCb, &ctx);
    t_scanning = false;
}

void *(*orig_dlopen)(const char *, int);
void *my_dlopen(const char *f, int fl) {
    void *h = orig_dlopen(f, fl);
    if (h) scanAll();
    return h;
}

void *(*orig_dlopen_ext)(const char *, int, const void *);
void *my_dlopen_ext(const char *f, int fl, const void *ext) {
    void *h = orig_dlopen_ext(f, fl, ext);
    if (h) scanAll();
    return h;
}

void parseMac(const std::string &s) {
    if (s == "@random") {
        randomBytes(g_mac, 6);
        g_mac[0] = (unsigned char) ((g_mac[0] & 0xFE) | 0x02);
        g_hasMac = true;
        return;
    }
    unsigned v[6];
    if (sscanf(s.c_str(), "%x:%x:%x:%x:%x:%x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) == 6) {
        for (int i = 0; i < 6; i++) g_mac[i] = (unsigned char) v[i];
        g_hasMac = true;
    }
}

}  // namespace

void installHooks(const Config &c) {
    // ---------------- state
    auto prop = [&](const char *key, std::initializer_list<const char *> names) {
        const std::string &v = c.get(key);
        if (v.empty()) return;
        for (auto *n : names) g_props[n] = v;
    };
    prop("manufacturer", {"ro.product.manufacturer", "ro.product.system.manufacturer", "ro.product.vendor.manufacturer"});
    prop("brand", {"ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand"});
    prop("model", {"ro.product.model", "ro.product.system.model", "ro.product.vendor.model"});
    prop("device", {"ro.product.device", "ro.product.system.device", "ro.product.vendor.device"});
    prop("product", {"ro.product.name", "ro.product.system.name", "ro.product.vendor.name"});
    prop("board", {"ro.product.board"});
    // NOT spoofed natively on purpose: ro.hardware (selects GPU/HAL drivers) and ro.build.version.sdk
    // (android_get_device_api_level() is used by native libraries for compatibility decisions).
    prop("fingerprint", {"ro.build.fingerprint", "ro.system.build.fingerprint", "ro.vendor.build.fingerprint"});
    prop("buildId", {"ro.build.id"});
    prop("buildDisplay", {"ro.build.display.id"});
    prop("buildIncremental", {"ro.build.version.incremental"});
    prop("buildType", {"ro.build.type"});
    prop("buildTags", {"ro.build.tags"});
    prop("bootloader", {"ro.bootloader", "ro.boot.bootloader"});
    prop("radio", {"gsm.version.baseband"});
    prop("serial", {"ro.serialno", "ro.boot.serialno"});
    prop("socManufacturer", {"ro.soc.manufacturer"});
    prop("socModel", {"ro.soc.model"});
    prop("androidRelease", {"ro.build.version.release"});
    prop("securityPatch", {"ro.build.version.security_patch"});
    prop("hostname", {"net.hostname"});

    g_kernel = c.get("kernel");
    g_soc = c.get("socModel");
    g_cores = std::max(0L, strtol(c.get("cpuCores").c_str(), nullptr, 10));
    g_ramBytes = strtoull(c.get("ramMb").c_str(), nullptr, 10) * 1024ULL * 1024ULL;
    g_glVendor = c.get("glVendor");
    g_glRenderer = c.get("glRenderer");
    g_glOn = !g_glRenderer.empty() || !g_glVendor.empty();
    if (c.has("bootId")) g_bootId = c.get("bootId") == "@random" ? freshUuid() : c.get("bootId");
    if (c.has("wifiMac")) parseMac(c.get("wifiMac"));

    unsigned long long gb = strtoull(c.get("storageGb").c_str(), nullptr, 10);
    if (gb) {
        struct statfs st;
        if (statfs("/data", &st) == 0 && st.f_blocks) {        // measured before any hook exists
            g_realDataTotal = (unsigned long long) st.f_blocks * (unsigned long long) st.f_bsize;
            g_storageRatio = (double) gb * 1e9 * 0.93 / (double) g_realDataTotal;
        }
    }

    real_dlsym = (void *(*)(void *, const char *)) dlsym(RTLD_DEFAULT, "dlsym");

    // ---------------- hook table (only what the profile needs)
    auto H = [](const char *n, void *repl, void *orig) { addHook(n, repl, (void **) orig); };
    if (!g_props.empty()) {
        H("__system_property_get", (void *) &my_prop_get, &orig_prop_get);
        H("__system_property_find", (void *) &my_prop_find, &orig_prop_find);
        H("__system_property_read_callback", (void *) &my_prop_read_cb, &orig_prop_read_cb);
        H("__system_property_read", (void *) &my_prop_read, &orig_prop_read);
        H("__system_property_serial", (void *) &my_prop_serial, &orig_prop_serial);
    }
    if (!g_kernel.empty()) H("uname", (void *) &my_uname, &orig_uname);
    if (g_cores > 0 || g_ramBytes) {
        H("sysconf", (void *) &my_sysconf, &orig_sysconf);
        if (g_cores > 0) H("get_nprocs", (void *) &my_get_nprocs, &orig_get_nprocs);
        if (g_ramBytes) H("sysinfo", (void *) &my_sysinfo, &orig_sysinfo);
    }
    if (g_ramBytes || g_cores > 0 || !g_soc.empty() || !g_kernel.empty() || !g_bootId.empty() || g_hasMac) {
        H("open", (void *) &my_open, &orig_open);
        H("openat", (void *) &my_openat, &orig_openat);
        H("__open_2", (void *) &my_open_2, &orig_open_2);
        H("__openat_2", (void *) &my_openat_2, &orig_openat_2);
        H("fopen", (void *) &my_fopen, &orig_fopen);
    }
    if (g_storageRatio > 0) {
        H("statfs", (void *) &my_statfs, &orig_statfs);
        H("fstatfs", (void *) &my_fstatfs, &orig_fstatfs);
        H("statfs64", (void *) &my_statfs64, &orig_statfs64);
        H("fstatfs64", (void *) &my_fstatfs64, &orig_fstatfs64);
        H("statvfs", (void *) &my_statvfs, &orig_statvfs);
        H("fstatvfs", (void *) &my_fstatvfs, &orig_fstatvfs);
        H("statvfs64", (void *) &my_statvfs64, &orig_statvfs64);
        H("fstatvfs64", (void *) &my_fstatvfs64, &orig_fstatvfs64);
    }
    if (g_hasMac) {
        H("ioctl", (void *) &my_ioctl, &orig_ioctl);
        H("getifaddrs", (void *) &my_getifaddrs, &orig_getifaddrs);
    }
    if (g_glOn) {
        H("glGetString", (void *) &my_glGetString, &g_orig_glGetString);
        H("eglGetProcAddress", (void *) &my_eglGetProcAddress, &orig_eglGetProcAddress);
        H("dlsym", (void *) &my_dlsym, &real_dlsym);
    }
    if (g_defs.empty()) { LOGI("nothing to hook for this profile"); return; }
    // Keep new libraries covered (Chromium / WebView load their engines lazily).
    H("dlopen", (void *) &my_dlopen, &orig_dlopen);
    H("android_dlopen_ext", (void *) &my_dlopen_ext, &orig_dlopen_ext);

    scanAll();
    LOGI("hooks installed: %zu defs, %d write failure(s)", g_defs.size(), g_failed);
}
