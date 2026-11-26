#ifndef RIVET_COMMAND_OWNER_H
#define RIVET_COMMAND_OWNER_H

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define RIVET_MONITOR_NAME "RivetCmdGuard"

static int rivet_close_descriptors(int keep_first, int keep_second) {
    DIR* directory = opendir("/proc/self/fd");
    if (!directory) return -1;
    int directory_fd = dirfd(directory);
    struct dirent* entry;
    while ((entry = readdir(directory))) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != directory_fd && fd != keep_first && fd != keep_second) close(fd);
    }
    closedir(directory);
    return 0;
}

// The monitor is an immediate child of the app, with no dependence on the
// lifetime of the particular Java thread that called fork().
static int rivet_prepare_monitor(pid_t owner, sigset_t* events) {
    sigemptyset(events);
    sigaddset(events, SIGCHLD);
    sigaddset(events, SIGTERM);
    sigaddset(events, SIGUSR1);
    if (sigprocmask(SIG_BLOCK, events, NULL) < 0 ||
        prctl(PR_SET_NAME, RIVET_MONITOR_NAME, 0, 0, 0) < 0 ||
        prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0 || getppid() != owner) return -1;
    struct sigaction action = { .sa_handler = SIG_DFL };
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGCHLD, &action, NULL) < 0) return -1;
    DIR* proc = opendir("/proc");
    if (!proc) return -1;
    closedir(proc);
    return 0;
}

static void rivet_prepare_shell(void) {
    struct sigaction action = { .sa_handler = SIG_DFL };
    sigemptyset(&action.sa_mask);
    sigaction(SIGCHLD, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGUSR1, &action, NULL);
    sigset_t mask;
    sigemptyset(&mask);
    sigprocmask(SIG_SETMASK, &mask, NULL);
}

static void rivet_kill_adopted_children(void) {
    DIR* proc = opendir("/proc");
    if (!proc) return;
    const pid_t monitor = getpid();
    struct dirent* entry;
    while ((entry = readdir(proc)) != NULL) {
        char* end;
        long pid = strtol(entry->d_name, &end, 10);
        if (*end != '\0' || pid <= 0 || pid == monitor) continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        char stat[512];
        ssize_t bytes = read(fd, stat, sizeof(stat) - 1);
        close(fd);
        if (bytes <= 0) continue;
        stat[bytes] = '\0';
        char* fields = strrchr(stat, ')');
        // A direct child cannot have its pid reused while this monitor owns
        // its wait status. Only this thread ever reaps those children.
        if (fields && strlen(fields) > 4 && strtol(fields + 4, NULL, 10) == monitor) {
            kill((pid_t) pid, SIGKILL);
        }
    }
    closedir(proc);
}

static void rivet_monitor(pid_t owner, pid_t shell, const sigset_t* events) {
    struct timespec interval = { .tv_sec = 0, .tv_nsec = 100000000L };
    int forced = 0;
    for (;;) {
        siginfo_t child = {0};
        if (waitid(P_PID, (id_t) shell, &child, WEXITED | WNOHANG | WNOWAIT) == 0 && child.si_pid) break;
        if (getppid() != owner) { forced = SIGKILL; break; }
        int event = sigtimedwait(events, NULL, &interval);
        if (event == SIGUSR1) { forced = SIGKILL; break; }
        if (event == SIGTERM) {
            kill(-shell, SIGTERM);
            kill(shell, SIGTERM);
        }
    }

    // Keep the leader's PID reserved until the group signal has been sent.
    // A child that called setsid() is reparented to the subreaper as its
    // ancestors die, so it is swept even if it escaped the shell's group.
    kill(-shell, SIGKILL);
    kill(shell, SIGKILL);
    int status;
    pid_t reaped;
    do { reaped = waitpid(shell, &status, 0); } while (reaped < 0 && errno == EINTR);
    for (;;) {
        rivet_kill_adopted_children();
        int next = waitpid(-1, NULL, WNOHANG);
        if (next < 0 && errno == ECHILD) break;
        if (next == 0) sigtimedwait(events, NULL, &interval);
    }
    if (reaped < 0) _exit(126);
    if (!forced && WIFEXITED(status)) _exit(WEXITSTATUS(status));
    int signal = forced ? forced : WTERMSIG(status);
    rivet_prepare_shell();
    kill(getpid(), signal);
    _exit(128 + signal);
}

#endif
