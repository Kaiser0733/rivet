#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>
#include "command_owner.h"

static jint fail(JNIEnv* env, const char* message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
    return -1;
}

JNIEXPORT jintArray JNICALL Java_com_kaiser_rivet_runtime_CommandNative_start(
    JNIEnv* env, jobject instance, jstring command, jstring cwd, jobjectArray environment,
    jlong address_space_limit) {
    (void) instance;
    if (!command || !cwd || !environment) {
        fail(env, "Invalid command arguments");
        return NULL;
    }
    const char* command_utf8 = NULL;
    const char* cwd_utf8 = NULL;
    jsize count = (*env)->GetArrayLength(env, environment);
    char** envp = calloc((size_t) count + 1, sizeof(char*));
    int out[2] = {-1, -1};
    int err[2] = {-1, -1};
    pid_t pid = -1;
    jintArray result = NULL;
    if (!envp) goto cleanup;
    for (jsize i = 0; i < count; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, environment, i);
        if (!value) goto cleanup;
        const char* utf8 = (*env)->GetStringUTFChars(env, value, NULL);
        if (!utf8) { (*env)->DeleteLocalRef(env, value); goto cleanup; }
        envp[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, value, utf8);
        (*env)->DeleteLocalRef(env, value);
        if (!envp[i]) goto cleanup;
    }
    command_utf8 = (*env)->GetStringUTFChars(env, command, NULL);
    if (!command_utf8) goto cleanup;
    cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (!cwd_utf8) goto cleanup;
    if (pipe2(out, O_CLOEXEC) != 0 || pipe2(err, O_CLOEXEC) != 0) goto cleanup;
    pid_t owner = getpid();
    pid = fork();
    if (pid == 0) {
        close(out[0]); close(err[0]);
        if (rivet_close_descriptors(out[1], err[1]) < 0) _exit(126);
        sigset_t events;
        if (rivet_prepare_monitor(owner, &events) < 0) _exit(126);
        pid_t shell = fork();
        if (shell < 0) _exit(126);
        if (shell > 0) {
            close(out[1]); close(err[1]);
            rivet_monitor(owner, shell, &events);
        }
        rivet_prepare_shell();
        if (setsid() < 0) _exit(126);
        int input = open("/dev/null", O_RDONLY);
        if (input < 0 || dup2(input, 0) < 0 || dup2(out[1], 1) < 0 || dup2(err[1], 2) < 0) _exit(126);
        if (rivet_close_descriptors(-1, -1) < 0) _exit(126);
        if (address_space_limit > 0) {
            struct rlimit limit;
            limit.rlim_cur = (rlim_t) address_space_limit;
            limit.rlim_max = (rlim_t) address_space_limit;
            // Best effort: kernels that do not enforce RLIMIT_AS still have the
            // PSS watchdog in the parent.
            (void) setrlimit(RLIMIT_AS, &limit);
        }
        if (chdir(cwd_utf8) != 0) _exit(126);
        // No login shell: the launcher passes the complete environment, and a device
        // profile must not replace PATH/HOME with developer or vendor defaults.
        char* const argv[] = {"/system/bin/sh", "-c", (char*) command_utf8, NULL};
        execve("/system/bin/sh", argv, envp);
        _exit(127);
    }
    if (pid < 0) goto cleanup;
    close(out[1]); out[1] = -1;
    close(err[1]); err[1] = -1;
    result = (*env)->NewIntArray(env, 3);
    if (result) {
        jint values[] = {(jint) pid, out[0], err[0]};
        (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    }
    if (!result || (*env)->ExceptionCheck(env)) {
        kill(pid, SIGUSR1);
        waitpid(pid, NULL, 0);
        result = NULL;
    } else {
        out[0] = -1;
        err[0] = -1;
    }
cleanup:
    if (out[0] >= 0) close(out[0]);
    if (out[1] >= 0) close(out[1]);
    if (err[0] >= 0) close(err[0]);
    if (err[1] >= 0) close(err[1]);
    if (cwd_utf8) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);
    if (command_utf8) (*env)->ReleaseStringUTFChars(env, command, command_utf8);
    if (envp) { for (jsize i = 0; i < count; ++i) free(envp[i]); free(envp); }
    if (!result && !(*env)->ExceptionCheck(env)) fail(env, "Cannot start command");
    return result;
}

JNIEXPORT jint JNICALL Java_com_kaiser_rivet_runtime_CommandNative_waitFor(
    JNIEnv* env, jobject instance, jint pid) {
    (void) instance;
    if (pid <= 0) return fail(env, "Invalid command process");
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        return fail(env, "Cannot wait for command process");
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL Java_com_kaiser_rivet_runtime_CommandNative_stop(
    JNIEnv* env, jobject instance, jint pid, jint signal) {
    (void) env; (void) instance;
    if (pid > 0) kill(pid, signal == SIGKILL ? SIGUSR1 : SIGTERM);
}

JNIEXPORT void JNICALL Java_com_kaiser_rivet_runtime_CommandNative_reapStaleMonitors(
    JNIEnv* env, jobject instance) {
    (void) env; (void) instance;
#if defined(__NR_pidfd_open) && defined(__NR_pidfd_send_signal)
    // Some Android seccomp profiles may reject pidfd syscalls. Probe in a child
    // so a profile that delivers SIGSYS cannot take the app down at launch.
    pid_t sweep = fork();
    if (sweep < 0) return;
    if (sweep > 0) {
        while (waitpid(sweep, NULL, 0) < 0 && errno == EINTR) { }
        return;
    }
    DIR* proc = opendir("/proc");
    if (!proc) _exit(0);
    struct dirent* entry;
    while ((entry = readdir(proc)) != NULL) {
        char* end;
        long pid = strtol(entry->d_name, &end, 10);
        if (*end != '\0' || pid <= 1) continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
        struct stat owner;
        if (stat(path, &owner) < 0 || owner.st_uid != getuid()) continue;
        int identity = (int) syscall(__NR_pidfd_open, pid, 0);
        if (identity < 0) continue;
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            char state[512];
            ssize_t size = read(fd, state, sizeof(state) - 1);
            close(fd);
            if (size > 0) {
                state[size] = '\0';
                char* comm = strchr(state, '(');
                char* fields = strrchr(state, ')');
                if (comm && fields && fields - comm == (ptrdiff_t) sizeof(RIVET_MONITOR_NAME) &&
                    !memcmp(comm + 1, RIVET_MONITOR_NAME, sizeof(RIVET_MONITOR_NAME) - 1) &&
                    strlen(fields) > 4 && strtol(fields + 4, NULL, 10) == 1) {
                    syscall(__NR_pidfd_send_signal, identity, SIGUSR1, NULL, 0);
                }
            }
        }
        close(identity);
    }
    closedir(proc);
    _exit(0);
#endif
}

JNIEXPORT void JNICALL Java_com_kaiser_rivet_runtime_CommandNative_closeFd(
    JNIEnv* env, jobject instance, jint fd) {
    (void) env; (void) instance;
    if (fd >= 0) close(fd);
}
