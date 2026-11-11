// pip entry point for Android: a native PIE that runs the bundled interpreter.
// Android 10+ refuses execve() from app-writable storage regardless of mode bits.
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char self[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (length <= 0 || length >= (ssize_t)sizeof(self)) {
        fprintf(stderr, "pip: cannot locate the launcher: %s\n", strerror(errno));
        return 127;
    }
    self[length] = '\0';
    char *slash = strrchr(self, '/');
    if (slash == NULL) {
        fprintf(stderr, "pip: cannot locate the native directory\n");
        return 127;
    }
    *slash = '\0';

    char python[PATH_MAX];
    int written = snprintf(python, sizeof(python), "%s/libpython3.so", self);
    if (written <= 0 || written >= (int)sizeof(python)) {
        fprintf(stderr, "pip: native path is too long\n");
        return 127;
    }

    char **args = calloc((size_t)argc + 3, sizeof(char *));
    if (args == NULL) {
        fprintf(stderr, "pip: out of memory\n");
        return 127;
    }
    args[0] = (char *)"python3";
    args[1] = (char *)"-m";
    args[2] = (char *)"pip";
    for (int i = 1; i < argc; i++) args[i + 2] = argv[i];

    execv(python, args);
    fprintf(stderr, "pip: cannot execute %s: %s\n", python, strerror(errno));
    free(args);
    return 127;
}
