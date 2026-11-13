<!--
Copyright(c) 2026 Sven Trittler

This program and the accompanying materials are made available under the
terms of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
-->
# Upstream test comparison

Fresh official Python and C++ checkouts were downloaded on 2026-10-23. Their
exact revisions, source locations, parameterization markers and disabled C++
test markers are recorded in [inventory.json](inventory.json). The inventory
contains 212 Python `test_*` definitions, 571 C++ GoogleTest definitions and six
additional CMake generator probes. Definitions include nested Python helpers;
they are **not** the number of collected or parameter-expanded test cases.

[coverage.md](coverage.md) is the complete source-linked review ledger. It is
generated from [coverage.json](coverage.json), whose entries point to actual Java
sources and registered CTest names. Unreviewed definitions may already have
related Java coverage, but have not received an assertion-by-assertion review.
The audit does not establish equal line, branch or behavioral coverage.

The new test groups cover time/duration arithmetic, primitive wire boundaries,
both-endian reads and alignment, truncated payloads, nested/member lengths,
mutable member ordering and must-understand rules, all 128 read/query state
masks, sample ranks and generation counts, reader buffers and instance-table
growth, invalid-data key recovery, loan snapshots, entity/reference lifetimes,
default QoS propagation, discovery handle sets, bounds, and recursive types.

Full upstream parity is **not established**. Explicit feature gaps include XML
QoS providers, dynamic type discovery/introspection, bitmasks, statistics and
shared-memory loans. Built-in readers, matched endpoint metadata and end-to-end
optional/mutable XCDR1 now have dedicated native interoperability tests. The remaining unreviewed scenarios are retained individually
in the ledger. Python's CLI application and C++-specific operator/null-wrapper
tests have explicit applicability decisions. Unsupported-feature rejection tests
are never treated as evidence that the feature works.

Run the suite and validate the ledger:

```sh
cmake --build build --parallel
ctest --test-dir build --output-on-failure
python3 tests/upstream/audit.py --check --build build
```

To repeat the comparison using new downloads:

```sh
git clone --depth 1 https://github.com/eclipse-cyclonedds/cyclonedds-python /tmp/cyclonedds-python-review
git clone --depth 1 https://github.com/eclipse-cyclonedds/cyclonedds-cxx /tmp/cyclonedds-cxx-review
python3 tests/upstream/audit.py --refresh \
  --python /tmp/cyclonedds-python-review --cxx /tmp/cyclonedds-cxx-review --build build
```

Review new/changed assertions and parameter variants, add Java scenarios, update
`coverage.json`, and regenerate the Markdown with `python3 tests/upstream/audit.py`.
The audit rejects stale upstream identifiers, nonexistent Java sources, missing
CTest registrations and stale generated Markdown. It does not silently mark new
upstream definitions as covered, and CI does not access the network for this check.

The unmodified `idl/Recursive*.idl` fixtures come from
[cyclonedds-cxx/src/ddscxx/tests/data at 33d27ffff4d76021d88653f778cc001aa1a2bb7a](https://github.com/eclipse-cyclonedds/cyclonedds-cxx/tree/33d27ffff4d76021d88653f778cc001aa1a2bb7a/src/ddscxx/tests/data)
and retain the upstream EPL-2.0 OR BSD-3-Clause licensing. C++ rejection probes
are adapted to successful Java round trips where the Java mapping supports the
same IDL; the incomplete typedef-array case retains the core parser rejection.
