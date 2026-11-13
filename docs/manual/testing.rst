..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Testing and upstream comparison
===============================

Build and run the tests with:

.. code-block:: sh

   cmake --build build --parallel
   ctest --test-dir build --output-on-failure
   ctest --test-dir build -R '^upstream-' --output-on-failure

The portable tests compile against the unmodified OMG API archive and generated
data types, without the provider archive. Runtime tests use the provider and JNI
with assertions and JNI checking enabled. Independent C peers verify wire and
discovery interoperability. The installed-consumer and bundled-runtime tests
exercise installation and automatic native loading.

The separate-process C interoperability tests use explicit loopback peers with
multicast disabled. CTest supplies the same ``CYCLONEDDS_URI`` configuration to
the Java process and its C peer, avoiding host-network discovery dependencies.

The ``xcdr1`` test covers independent wire vectors, both byte orders, optional
alignment, native mutable/standalone appendable schema evolution, large members,
C interoperability and mixed-representation instance identity on the unmodified
core. Unsupported representation selections are tested as rejections, not counted
as successful DDS support. Broader Java codec-only nesting vectors are kept
separate from the native exchange assertions.

``xcdr1-broader-interop`` repeats the broader IDL cases whose generated metadata
permits XCDR1; the C peer exchanges exactly those supported cases. Unsupported
cases must reject endpoint creation. ``reject-annotation-schemas`` verifies core
parser/generator diagnostics for wchar unions, negative 64-bit union labels,
wide annotation members and conflicting repeated annotations.
``omg-discovery`` tests all four built-in readers and matched endpoint metadata
against an independent C process, including QoS, keys and removal events.

The upstream comparison uses fresh downloads from 2026-10-23:

* Python: ``68b2acfd5ac56bd0f095e51914a9f6c494675327``.
* C++: ``33d27ffff4d76021d88653f778cc001aa1a2bb7a``.

The inventory contains 212 Python test definitions, 571 C++ GoogleTest
definitions, and six CMake generator probes. Parameter expansion, nested Python
helpers, disabled tests and C++ feature guards affect the number of runnable
upstream cases. These counts cannot be compared directly with CTest entries or
used as a coverage percentage.

The repository's ``tests/upstream/coverage.md`` links each inventoried definition
to its pinned upstream source. Reviewed entries record the Java source, CTest
name and any remaining differences. ``tests/upstream/README.md`` explains how to
refresh the inventory and review new upstream tests. Validate the recorded
evidence without downloading anything:

.. code-block:: sh

   python3 tests/upstream/audit.py --check --build build

The new upstream tests include exhaustive state-mask oracles, sample ranks and
lifecycle generation counts, large buffers and many-instance traversal,
independent wire vectors, malformed nested lengths, bounds, default QoS,
reference lifetimes and the original C++ recursive IDL fixtures. Several C++
recursive rejection probes become successful Java round trips because the Java
mapping supports those forms.

Full test parity is not established. Unreviewed definitions remain visible;
related Java tests are not automatically counted as equivalent. Runtime dynamic
types, XML QoS providers, bitmasks, statistics and shared-memory loans still have feature
gaps. Rejection tests verify diagnostics and do not count as successful feature
coverage. See :doc:`roadmap` for implementation limitations.
