# Diagnostic history

- [Scrollable tab row diagnosis](blank-screen/scrollable-tab-row-diagnosis.md)
  — 2026-11-16 (0.1.13 investigation; date supplied by the release evidence,
  not embedded in the original record). Establishes the Material 3 1.4.0
  intrinsic-query ordering defect and documents CountPill's
  wrapContentWidth mitigation. Preserved verbatim, including its limits:
  source/bytecode review alone did not prove the entire device stall or
  verify the fix on a phone. Its proposed experiments are historical.

The supplied release evidence records user confirmation that 0.1.13 worked
on device on 2026-11-16; none of the seven local diagnostic records contains
that successful device verification. No verification record is invented here.
0.1.14 removed startup diagnostics; current baseline is 107 JVM tests and
lint 0 errors. See the [changelog](../../CHANGELOG.md).
