/* Modified by Rivet from termux/termux-app; see THIRD_PARTY_NOTICES.md. */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
# define LACKS_PTSNAME_R
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(ptm) || unlockpt(ptm) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(ptm)) == NULL
#else
            ptsname_r(ptm, devname, sizeof(devname))
#endif
       ) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns, .ws_xpixel = (unsigned short) (columns * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height)};
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        *pProcessId = (int) pid;
        return ptm;
    } else {
        // Clear signals which the Android java process may have blocked:
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        if (setsid() < 0) _exit(1);

        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(1);

        if (dup2(pts, 0) < 0 || dup2(pts, 1) < 0 || dup2(pts, 2) < 0) _exit(1);
        if (pts > 2) close(pts);

        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        if (chdir(cwd) != 0) {
            char* error_message;
            // No need to free asprintf()-allocated memory since doing execvp() or exit() below.
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
            _exit(1);
        }
        execvp(cmd, argv);
        // Show terminal output about failing exec() call:
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    if (!cmd || !cwd || !processIdArray || (*env)->GetArrayLength(env, processIdArray) != 1)
        return throw_runtime_exception(env, "Invalid terminal subprocess arguments");
    jsize arg_count = args ? (*env)->GetArrayLength(env, args) : 0;
    jsize env_count = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    if (arg_count < 1) return throw_runtime_exception(env, "Missing terminal argv");
    char** argv = calloc((size_t) arg_count + 1, sizeof(char*));
    char** envp = calloc((size_t) env_count + 1, sizeof(char*));
    char const* cmd_utf8 = NULL;
    char const* cwd_utf8 = NULL;
    int ptm = -1;
    int proc_id = 0;
    if (!argv || !envp) goto cleanup;
    for (jsize i = 0; i < arg_count; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!value) goto cleanup;
        char const* utf8 = (*env)->GetStringUTFChars(env, value, NULL);
        if (!utf8) { (*env)->DeleteLocalRef(env, value); goto cleanup; }
        argv[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, value, utf8);
        (*env)->DeleteLocalRef(env, value);
        if (!argv[i]) goto cleanup;
    }
    for (jsize i = 0; i < env_count; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
        if (!value) goto cleanup;
        char const* utf8 = (*env)->GetStringUTFChars(env, value, NULL);
        if (!utf8) { (*env)->DeleteLocalRef(env, value); goto cleanup; }
        envp[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, value, utf8);
        (*env)->DeleteLocalRef(env, value);
        if (!envp[i]) goto cleanup;
    }
    cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (!cwd_utf8) goto cleanup;
    cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (!cmd_utf8) goto cleanup;
    ptm = create_subprocess(env, cmd_utf8, cwd_utf8, argv, envp, &proc_id,
                            rows, columns, cell_width, cell_height);
    if (ptm >= 0) {
        jint pid_value = proc_id;
        (*env)->SetIntArrayRegion(env, processIdArray, 0, 1, &pid_value);
        if ((*env)->ExceptionCheck(env)) {
            kill(proc_id, SIGKILL);
            waitpid(proc_id, NULL, 0);
            close(ptm);
            ptm = -1;
        }
    }
cleanup:
    if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    if (cwd_utf8) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);
    if (argv) { for (jsize i = 0; i < arg_count; ++i) free(argv[i]); free(argv); }
    if (envp) { for (jsize i = 0; i < env_count; ++i) free(envp[i]); free(envp); }
    if (ptm < 0 && !(*env)->ExceptionCheck(env)) return throw_runtime_exception(env, "Cannot start terminal subprocess");
    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols, .ws_xpixel = (unsigned short) (cols * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height) };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    if (fd >= 0) close(fd);
}
