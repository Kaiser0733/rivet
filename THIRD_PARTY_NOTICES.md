# Third-party runtime components

Rivet includes `terminal-emulator` and `terminal-view` source from
[`termux/termux-app`](https://github.com/termux/termux-app), pinned to
`084d709fbf23ea83b5cb85fd3d795c775be06676`. The upstream repository's
`LICENSE.md` identifies these two terminal libraries as Apache-2.0 exceptions
to its GPLv3 app license. The Apache-2.0 license text is in Rivet's `LICENSE`.
Rivet keeps the `com.termux.terminal` and `com.termux.view` package names.
Rivet changes Gradle packaging, fixes native error cleanup, replaces reflective
file-descriptor access with `ParcelFileDescriptor`, and adds process-group
cleanup. Rivet's `command.c` in the same native library is original code.

`terminal-emulator/AndroidUtils.java` says it contains code sourced from the
upstream `termux-shared` Android utility. That source directory is MIT-licensed
at the pinned revision. The MIT notice for that portion follows.

Copyright (c) the Termux contributors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

The modern [`termux-play-store/termux-exec`](https://github.com/termux-play-store/termux-exec)
implementation was reviewed at `47633d5df2db3dfea34cef568c6c82a7ba8dcc2e`
(Apache-2.0). No `termux-exec` code or binaries are included in this APK.
