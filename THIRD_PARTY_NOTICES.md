# Third-party notices

Rivet is Apache-2.0 (see `LICENSE`). It also ships other people's work.
Each part keeps its own license. This file says what, whose, and under
what terms. License texts that must be reproduced word for word are
quoted as-is — everything else is kept short on purpose.

## The terminal toolchain

Rivet's built-in terminal ships three ported tools. We built them from
source for Android; the build recipes and our patches are in
[`toolchain/`](toolchain/) in this repo.

**BusyBox 1.36.1** — shell and core utilities.
Copyright the BusyBox contributors. License: GPL-2.0
(`licenses/GPL-2.0.txt`). Source: https://busybox.net/downloads/busybox-1.36.1.tar.bz2 —
our config is `toolchain/busybox.config`, recipe `toolchain/busybox-recipe.md`.

**Git 2.56.0** — the `git` command (binary + HTTPS remote helper).
Copyright the Git contributors. License: GPL-2.0
(`licenses/GPL-2.0.txt`). Source: https://mirrors.edge.kernel.org/pub/software/scm/git/git-2.56.0.tar.xz —
our patches are in `toolchain/git/patches/`, build script
`toolchain/git/build.sh`, recipe `toolchain/git/git-recipe.md`. Git links the system zlib (zlib license,
Copyright (C) 1995–2024 Jean-loup Gailly and Mark Adler).

**CPython 3.12.3** — the `python3` command, its standard library, and pip 24.0.
Copyright the Python Software Foundation. License: PSF License
(`licenses/PSF.txt`). Source: https://www.python.org/ftp/python/3.12.3/Python-3.12.3.tgz —
our patches are in `toolchain/python-patches/`, recipe
`toolchain/python-recipe.md`.

Python's extension modules statically include: **OpenSSL 3.x**
(Apache-2.0, Copyright The OpenSSL Project), **SQLite** (public domain),
**libffi** (MIT, Copyright (C) 1996–2023 Anthony Green and others),
**expat** (MIT, Copyright (C) 1998–2000 Thai Open Source Software Center
and Clark Cooper; (C) 2001–2022 the Expat maintainers), **zlib** (above),
**bzip2** (BSD-style, Copyright (C) 1996–2010 Julian R Seward), and
**liblzma** from XZ Utils (public domain). None of these change the PSF
terms above; their full texts ship with Python's own license file.

**GPL source offer.** BusyBox and Git above are GPL-2.0. Their complete
corresponding source — upstream tarball plus our patches and build
recipes — is in this repository under `toolchain/`, which is the source
distribution for the binaries in the APK. If any link or file is missing,
open an issue and we will provide the source.

## Terminal emulator (from Termux)

Rivet includes `terminal-emulator` source from
[`termux/termux-app`](https://github.com/termux/termux-app), pinned to
`084d709fbf23ea83b5cb85fd3d795c775be06676`. Upstream's `LICENSE.md`
makes this library an Apache-2.0 exception to the app's GPLv3 license.
The Apache-2.0 text is in Rivet's `LICENSE`. Rivet keeps the
`com.termux.terminal` package name, and changed: Gradle packaging,
native error cleanup, `ParcelFileDescriptor` instead of reflective
file-descriptor access, and process-group cleanup. Changed files carry a
notice at the top. Rivet's `command.c` in the same library is original
code. Rivet is not part of Termux, and no other Termux code or binaries
are included.

`terminal-emulator/AndroidUtils.java` contains code from upstream
`termux-shared`, MIT-licensed at the pinned revision:

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
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

`WcWidth.java` includes code and Unicode tables derived from
[`jquast/wcwidth`](https://github.com/jquast/wcwidth), revision
`1b9b6585b0080ea5cb88dc9815796505724793fe`, MIT:

    The MIT License (MIT)

    Copyright (c) 2014 Jeff Quast <contact@jeffquast.com>

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

    Markus Kuhn -- 2007-05-26 (Unicode 5.0)

    Permission to use, copy, modify, and distribute this software
    for any purpose and without fee is hereby granted. The author
    disclaims all warranties with regard to this software.

## Libraries

**AOSP apksig 8.9.2** — verifies update signatures.
Apache-2.0, copyright The Android Open Source Project. Used as the
published artifact, not copied or modified.

**Eclipse JGit core 6.10.1.202505221210-r** — local Git repository work
(checkpoints). Used unmodified, as a library. License: Eclipse
Distribution License 1.0:

    This program and the accompanying materials are made available
    under the terms of the Eclipse Distribution License v1.0 which
    accompanies this distribution, is reproduced below, and is
    available at http://www.eclipse.org/org/documents/edl-v10.php

    Copyright (c) 2007, EclipseSource and others.

    All rights reserved.

    Redistribution and use in source and binary forms, with or
    without modification, are permitted provided that the following
    conditions are met:

    - Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    - Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    - Neither the name of the Eclipse Foundation, Inc. nor the
      names of its contributors may be used to endorse or promote
      products derived from this software without specific prior
      written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
    CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
    INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
    MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
    ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
    CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
    SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
    NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
    LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
    CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
    STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
    ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

JGit's runtime dependencies: **JavaEWAH 1.2.3** (Apache-2.0),
**Apache Commons Codec 1.17.0** (Apache-2.0 — "Apache Commons Codec,
Copyright 2002-2024 The Apache Software Foundation; this product
includes software developed at The Apache Software Foundation
(https://www.apache.org/)"), and **SLF4J API 1.7.36** (MIT):

    Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
    All rights reserved.

    Permission is hereby granted, free  of charge, to any person obtaining
    a  copy  of this  software  and  associated  documentation files  (the
    "Software"), to  deal in  the Software without  restriction, including
    without limitation  the rights to  use, copy,  modify,  merge, publish,
    distribute,  sublicense, and/or sell  copies of  the Software,  and to
    permit persons to whom the Software  is furnished to do so, subject to
    the following conditions:

    The  above  copyright  notice  and  this permission  notice  shall  be
    included in all copies or substantial portions of the Software.

    THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
    EXPRESS OR  IMPLIED, INCLUDING BUT NOT  LIMITED  TO  THE  WARRANTIES OF
    MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
    NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
    LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
    OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
    WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## CA certificates

Rivet ships the curl.se Mozilla CA extract
(`https://curl.se/ca/cacert.pem`) as
`toolchain/src/main/assets/ca-certificates.pem`, so Python and Git trust
the same roots on every phone. The bundle is MPL-2.0; each root keeps
its own notice inside the PEM. Pinned hash and refresh steps:
`toolchain/CA-BUNDLE.md`.

## llama.cpp

Rivet embeds [llama.cpp](https://github.com/ggml-org/llama.cpp), pinned to
**v0.9.0** (commit `cd959bae99ff4bed22bef2604f45241c09bbb8c8`), built from source
for `arm64-v8a` as `librivet_llama.so`. Copyright (c) 2023-2026 the llama.cpp
contributors. License: MIT.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
