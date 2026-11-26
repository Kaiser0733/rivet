"""JiTTest for the production command JNI on Linux. Pass a Git ref to test its source."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time

NATIVE = Path("terminal-emulator/src/main/jni")


def alive(pid):
    try:
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] != "Z"
    except FileNotFoundError:
        return False


def wait_for(predicate, seconds=5):
    deadline = time.monotonic() + seconds
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError("Command tree did not reach the expected state")
        time.sleep(0.02)


def main():
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        command_source = (
            subprocess.check_output(["git", "show", f"{sys.argv[1]}:{NATIVE}/command.c"], text=True)
            if len(sys.argv) == 2 else (NATIVE / "command.c").read_text()
        )
        source = root / "command.c"
        # Use the Linux host's shell for the exact same JNI launch path.
        source.write_text(command_source.replace('"/system/bin/sh"', '"/bin/sh"'))
        javac = Path(shutil.which("javac")).resolve()
        include = javac.parent.parent / "include"
        library = root / "libcommand.so"
        subprocess.run([
            "cc", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror",
            "-I", str(include), "-I", str(include / "linux"), "-I", str(NATIVE.resolve()),
            str(source), "-o", str(library),
        ], check=True)
        java = root / "CommandNative.java"
        java.write_text("""
package com.kaiser.rivet.runtime;
public class CommandNative {
    native int[] start(String command, String cwd, String[] environment, long limit);
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        new CommandNative().start(
            "echo $$ > shell.pid; setsid /bin/sh -c 'echo $$ > detached.pid; exec sleep 45' & wait",
            args[1], new String[]{"PATH=/usr/bin:/bin"}, 0);
        Thread.sleep(60_000);
    }
}
""")
        subprocess.run([str(javac), "-d", temporary, str(java)], check=True)
        owner = subprocess.Popen(["java", "-cp", temporary, "com.kaiser.rivet.runtime.CommandNative",
                                  str(library), temporary])
        known_pids = []
        try:
            paths = [root / "shell.pid", root / "detached.pid"]
            wait_for(lambda: all(p.exists() and p.stat().st_size > 0 for p in paths))
            known_pids = [int(path.read_text()) for path in paths]
            assert all(alive(pid) for pid in known_pids), known_pids
            owner.kill()
            owner.wait(timeout=5)
            wait_for(lambda: not any(alive(pid) for pid in known_pids), seconds=3)
            print("PASS: shell and detached descendant died with their owner")
        finally:
            if owner.poll() is None:
                owner.kill()
                owner.wait(timeout=5)
            for pid in known_pids:
                if alive(pid):
                    os.kill(pid, signal.SIGKILL)


if __name__ == "__main__":
    main()
