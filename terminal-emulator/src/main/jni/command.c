#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static jint fail(JNIEnv* env, const char* message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
    return -1;
}

static void close_child_descriptors(void) {
    DIR* directory = opendir("/proc/self/fd");
    if (!directory) return;
    int directory_fd = dirfd(directory);
    struct dirent* entry;
    while ((entry = readdir(directory))) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != directory_fd) close(fd);
    }
    closedir(directory);
}

JNIEXPORT jintArray JNICALL Java_com_kaiser_rivet_runtime_CommandNative_start(
    JNIEnv* env, jobject instance, jstring command, jstring cwd, jobjectArray environment) {
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
    pid = fork();
    if (pid == 0) {
        close(out[0]); close(err[0]);
        if (setsid() < 0) _exit(126);
        int input = open("/dev/null", O_RDONLY);
        if (input < 0 || dup2(input, 0) < 0 || dup2(out[1], 1) < 0 || dup2(err[1], 2) < 0) _exit(126);
        close_child_descriptors();
        if (chdir(cwd_utf8) != 0) _exit(126);
        char* const argv[] = {"/system/bin/sh", "-lc", (char*) command_utf8, NULL};
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
        kill(pid, SIGKILL);
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

JNIEXPORT void JNICALL Java_com_kaiser_rivet_runtime_CommandNative_signalGroup(
    JNIEnv* env, jobject instance, jint pid, jint signal) {
    (void) env; (void) instance;
    if (pid > 0) kill(-pid, signal);
}

JNIEXPORT void JNICALL Java_com_kaiser_rivet_runtime_CommandNative_signalLeader(
    JNIEnv* env, jobject instance, jint pid, jint signal) {
    (void) env; (void) instance;
    if (pid > 0) kill(pid, signal);
}

JNIEXPORT void JNICALL Java_com_kaiser_rivet_runtime_CommandNative_closeFd(
    JNIEnv* env, jobject instance, jint fd) {
    (void) env; (void) instance;
    if (fd >= 0) close(fd);
}
