# bbpPairings comparison harness

[bbpPairings](https://github.com/BieremaBoyzProgramming/bbpPairings) (by
Bierema Boyz Programming) is a FIDE-endorsed Swiss pairing engine
implementing the Dutch and Burstein systems. Unlike JaVaFo's canonical
transposition walk, bbpPairings uses weighted perfect matching (Blossom),
the same architecture as `SwissPairingEngine`.

`BbpPairingsComparisonTest` simulates tournaments with this project's
engines, exports each pre-round state as a FIDE TRF (via `TrfExporter`)
and asks bbpPairings to pair the same position, then reports pairing and
color agreement. The test is skipped automatically when the executable
is missing.

The test prints every divergence. Divergences are expected: the engine
implements the FIDE criteria hierarchy through globally optimal weighted
matching, which can select a different pairing among equally-ranked
alternatives than bbpPairings' implementation details.

## Obtaining bbpPairings

### Option A: pre-built binary (Linux / Windows)

Download the latest release from
<https://github.com/BieremaBoyzProgramming/bbpPairings/releases> and
extract `bbpPairings.exe` into this directory.

### Option B: build from source (macOS / Linux)

macOS users must compile from source (no macOS binary is published):
edit makefiles with these changes (https://github.com/BieremaBoyzProgramming/bbpPairings/pull/29)

    git clone https://github.com/BieremaBoyzProgramming/bbpPairings.git
    cd bbpPairings
    make static=no
    cp bbpPairings.exe ../tools/bbppairings/

Compilation requires a C++20 compiler (Clang or GCC) and GLPK.

### Option C: Docker (any platform)

Prepend `tools/bbppairings/bbpPairings.sh` to `/usr/local/bin` with:

    #!/bin/sh
    docker run --rm -v "$(pwd):/work" -w /work \
      bieremaboyz/bbppairings --dutch "$@"

Then symlink `tools/bbppairings/bbpPairings.exe` to that script.
