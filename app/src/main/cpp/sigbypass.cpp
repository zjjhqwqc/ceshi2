//
// sigbypass.cpp - Native层签名Hook
// 通过GOT表Hook拦截libsgmiddletier.so中的open/openat/fopen调用
// 当钉钉尝试读取签名文件时，重定向到原始签名文件
//

#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <elf.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "SigBypass"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef ELF64_R_SYM
#define ELF64_R_SYM(i) ((i) >> 32)
#endif
#ifndef ELF32_R_SYM
#define ELF32_R_SYM(i) ((i) >> 8)
#endif

// 原始函数指针
static int (*orig_openat)(int, const char*, int, ...) = nullptr;
static int (*orig_open)(const char*, int, ...) = nullptr;
static FILE* (*orig_fopen)(const char*, const char*) = nullptr;

// 原始签名文件路径（由Java层在初始化时写入）
static char original_rsa_path[256] = "/data/local/tmp/original_signature.rsa";
static char original_sf_path[256] = "/data/local/tmp/original_signature.sf";
static char original_mf_path[256] = "/data/local/tmp/original_manifest.mf";

// 检查路径是否为签名相关文件
static bool is_signature_file(const char* pathname) {
    if (!pathname) return false;
    return strstr(pathname, "RIMET.RSA") != nullptr
        || strstr(pathname, "CERT.RSA") != nullptr
        || strstr(pathname, "RIMET.SF") != nullptr
        || strstr(pathname, "CERT.SF") != nullptr
        || strstr(pathname, "MANIFEST.MF") != nullptr;
}

// 根据路径后缀返回对应的原始文件路径
static const char* get_original_path(const char* pathname) {
    if (!pathname) return nullptr;
    if (strstr(pathname, ".RSA")) return original_rsa_path;
    if (strstr(pathname, ".SF")) return original_sf_path;
    if (strstr(pathname, "MANIFEST.MF")) return original_mf_path;
    return nullptr;
}

// ==================== Hook函数 ====================

static int hooked_openat(int dirfd, const char* pathname, int flags, ...) {
    const char* redirect = get_original_path(pathname);
    if (redirect && orig_openat) {
        LOGI("[openat] redirect %s -> %s", pathname, redirect);
        return orig_openat(dirfd, redirect, O_RDONLY);
    }
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_openat(dirfd, pathname, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags);
}

static int hooked_open(const char* pathname, int flags, ...) {
    const char* redirect = get_original_path(pathname);
    if (redirect && orig_open) {
        LOGI("[open] redirect %s -> %s", pathname, redirect);
        return orig_open(redirect, O_RDONLY);
    }
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = va_arg(args, mode_t);
        va_end(args);
        return orig_open(pathname, flags, mode);
    }
    return orig_open(pathname, flags);
}

static FILE* hooked_fopen(const char* pathname, const char* mode) {
    const char* redirect = get_original_path(pathname);
    if (redirect && orig_fopen) {
        LOGI("[fopen] redirect %s -> %s", pathname, redirect);
        return orig_fopen(redirect, "rb");
    }
    return orig_fopen(pathname, mode);
}

// ==================== ELF GOT Hook ====================

struct hook_info {
    const char* target_so_substr;  // 目标SO名称子串（如 "libsgmiddletier"）
    const char* symbol;            // 要Hook的符号名
    void* new_func;                // 新函数地址
    void** old_func;               // 原始函数地址存储位置
};

static int hook_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    hook_info* hi = (hook_info*)data;

    if (!info->dlpi_name) return 0;
    if (!strstr(info->dlpi_name, hi->target_so_substr)) return 0;

    ElfW(Addr) base = info->dlpi_addr;
    const ElfW(Phdr)* phdr = info->dlpi_phdr;
    int phnum = info->dlpi_phnum;

    ElfW(Dyn)* dyn = nullptr;
    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = (ElfW(Dyn)*)(base + phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    // 解析.dynamic段
    const char* strtab = nullptr;
    ElfW(Sym)* symtab = nullptr;
    ElfW(Rel)* rel = nullptr;
    ElfW(Rela)* rela = nullptr;
    size_t relsz = 0, relasz = 0;

    for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_STRTAB:  strtab = (const char*)(base + d->d_un.d_ptr); break;
            case DT_SYMTAB:  symtab = (ElfW(Sym)*)(base + d->d_un.d_ptr); break;
            case DT_REL:     rel = (ElfW(Rel)*)(base + d->d_un.d_ptr); break;
            case DT_RELSZ:   relsz = d->d_un.d_val; break;
            case DT_RELA:    rela = (ElfW(Rela)*)(base + d->d_un.d_ptr); break;
            case DT_RELASZ:  relasz = d->d_un.d_val; break;
        }
    }

    size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
    bool hooked = false;

    // 处理RELA（arm64主要使用RELA）
    if (rela && relasz > 0 && symtab && strtab) {
        size_t count = relasz / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            int sym_idx = (int)ELF64_R_SYM(rela[i].r_info);
            const char* name = strtab + symtab[sym_idx].st_name;
            if (strcmp(name, hi->symbol) == 0) {
                void** got_entry = (void**)(base + rela[i].r_offset);
                void* page_start = (void*)(((uintptr_t)got_entry) & ~(page_size - 1));
                if (mprotect(page_start, page_size, PROT_READ | PROT_WRITE) == 0) {
                    *hi->old_func = *got_entry;
                    *got_entry = hi->new_func;
                    LOGI("Hooked %s in %s (RELA) GOT=%p orig=%p new=%p",
                         hi->symbol, info->dlpi_name, got_entry, *hi->old_func, hi->new_func);
                    hooked = true;
                } else {
                    LOGE("mprotect failed for %s", hi->symbol);
                }
                break;
            }
        }
    }

    // 处理REL（部分32位SO使用REL）
    if (!hooked && rel && relsz > 0 && symtab && strtab) {
        size_t count = relsz / sizeof(ElfW(Rel));
        for (size_t i = 0; i < count; i++) {
            int sym_idx = (int)ELF32_R_SYM(rel[i].r_info);
            const char* name = strtab + symtab[sym_idx].st_name;
            if (strcmp(name, hi->symbol) == 0) {
                void** got_entry = (void**)(base + rel[i].r_offset);
                void* page_start = (void*)(((uintptr_t)got_entry) & ~(page_size - 1));
                if (mprotect(page_start, page_size, PROT_READ | PROT_WRITE) == 0) {
                    *hi->old_func = *got_entry;
                    *got_entry = hi->new_func;
                    LOGI("Hooked %s in %s (REL) GOT=%p", hi->symbol, info->dlpi_name, got_entry);
                    hooked = true;
                }
                break;
            }
        }
    }

    return hooked ? 1 : 0;
}

static void hook_plt(const char* target_so, const char* symbol, void* new_func, void** old_func) {
    hook_info hi = { target_so, symbol, new_func, old_func };
    dl_iterate_phdr(hook_callback, &hi);
}

// ==================== 初始化 ====================

static void init_hooks() {
    LOGI("Initializing signature bypass hooks...");

    // Hook libsgmiddletier.so 和 libsgmiddletierso 的 open/openat/fopen
    const char* targets[] = {
        "libsgmiddletier",
        "libsgmainso",
        "libsgsecuritybodyso",
        nullptr
    };

    for (int i = 0; targets[i]; i++) {
        hook_plt(targets[i], "__openat", (void*)hooked_openat, (void**)&orig_openat);
        hook_plt(targets[i], "openat",   (void*)hooked_openat, (void**)&orig_openat);
        hook_plt(targets[i], "__open",   (void*)hooked_open,   (void**)&orig_open);
        hook_plt(targets[i], "open",     (void*)hooked_open,   (void**)&orig_open);
        hook_plt(targets[i], "fopen",    (void*)hooked_fopen,  (void**)&orig_fopen);
    }

    LOGI("Signature bypass hooks initialized.");
}

// ==================== JNI接口 ====================

extern "C" JNIEXPORT void JNICALL
Java_com_HookTest_Hook_nativeHookSgMiddleTier(JNIEnv* env, jobject /*thiz*/) {
    LOGI("nativeHookSgMiddleTier called from Java");
    init_hooks();
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    LOGI("JNI_OnLoad: sigbypass library loaded");
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    // 可选：在SO加载时自动初始化Hook
    // init_hooks();
    return JNI_VERSION_1_6;
}
