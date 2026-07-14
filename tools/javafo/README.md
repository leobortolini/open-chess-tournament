# JaVaFo comparison harness

[JaVaFo](http://www.rrweb.org/javafo/JaVaFo.htm) (by Roberto Ricca) is the
FIDE reference implementation of the Dutch Swiss pairing system: official
endorsement of pairing software is granted by matching its output.

`JaVaFoComparisonTest` simulates tournaments with this project's engine,
exports each pre-round state as a FIDE TRF (via `TrfExporter`) and asks
JaVaFo to pair the same position, then reports pairing and color
agreement. The test is skipped automatically when the JAR is missing.

To run the harness, download the JAR into this directory (it is not
committed — JaVaFo is distributed by its author, not under this project's
license):

```sh
curl -L -o tools/javafo/javafo.jar http://www.rrweb.org/javafo/current/javafo.jar
mvn test -Dtest=JaVaFoComparisonTest
```

The test prints every divergence. Divergences are expected in a fraction
of rounds: the engine implements the FIDE criteria hierarchy through
globally optimal weighted matching, which can select a different pairing
among equally-ranked alternatives than the Dutch rules' canonical
transposition order. Every generated round is still legal (no rematches,
no repeated byes, color constraints respected).
