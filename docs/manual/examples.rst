..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Runnable Java examples
======================

The ``examples`` directory provides Java counterparts of the Cyclone DDS C++
HelloWorld, roundtrip and throughput examples and the Python HelloWorld, vehicle,
async and built-in-topic examples. Additional programs demonstrate DDS listeners,
read conditions, guard conditions and keyed-instance lifecycle.

Build from the repository root:

.. code-block:: console

   cmake -S . -B build -DCMAKE_PREFIX_PATH=/path/to/cyclonedds-install -DBUILD_EXAMPLES=ON
   cmake --build build --parallel
   java @build/examples/java.args org.eclipse.cyclonedds.examples.SimpleHelloWorld

CMake generates ``build/examples/java.args`` with the provider setting and the
classpath for the bundled platform runtime, example JARs and generated IDL types.
Java reads this file directly; no launcher script is required. For another build
directory use its ``examples/java.args`` file. Regenerate with CMake after moving
the build, since the argument file contains paths for its configured location.

.. list-table:: Programs
   :header-rows: 1
   :widths: 30 35 35

   * - Program
     - Arguments (defaults)
     - Demonstrates
   * - HelloWorld
     - pub|sub [domain=0]
     - Reliable delivery, ten messages
   * - SimpleHelloWorld
     - [domain=0]
     - Publish/receive in one process
   * - RoundTrip
     - ping|pong [samples=100] [bytes=1024] [domain=0]
     - Echo payload checks and RTT percentiles
   * - Throughput
     - pub|sub [samples=10000] [bytes=1024] [domain=0]
     - Sequence checks and payload throughput
   * - VehicleExample
     - pub|sub [samples=20] [domain=0]
     - Generated coordinates, transient-local history
   * - AsyncSubscriber
     - [samples=20] [domain=0]
     - Worker/Future reception and main-thread heartbeat
   * - BuiltinTopics
     - [seconds=10] [domain=0]
     - All four standard discovery topics
   * - Listeners
     - [domain=0]
     - Data, matches, deadlines and liveliness callbacks
   * - WaitSetExample
     - [domain=0]
     - Read and shutdown guard conditions
   * - InstanceLifecycle
     - [domain=0]
     - Write, dispose, revive and unregister

For paired programs start the subscriber or pong first, then its peer in another
terminal. Both must use the same count, payload size and domain. The async receiver
pairs with ``VehicleExample pub``. Start ``BuiltinTopics`` before the vehicle pair
to observe their discovery and deletion events.

All commands, source links, upstream revisions and differences are documented in
``examples/README.md``. Roundtrip uses the C++ topic/type and opposite ping/pong
partitions; throughput uses its topic/type and partition. Java runs use bounded
counts rather than upstream benchmark control options. No cross-language
benchmark equivalence is claimed.

Discovery and listener behavior
-------------------------------

The built-in monitor prints participant keys, topic/type names, endpoint QoS and
invalid lifecycle notifications through ``DCPSParticipant``, ``DCPSTopic``,
``DCPSPublication`` and ``DCPSSubscription``. Topic discovery requires a core built
with that feature and this setting on all peers:

.. code-block:: console

   export CYCLONEDDS_URI='<CycloneDDS><Domain><Discovery><EnableTopicDiscoveryEndpoints>true</EnableTopicDiscoveryEndpoints></Discovery></Domain></CycloneDDS>'
   java @build/examples/java.args org.eclipse.cyclonedds.examples.BuiltinTopics 15 0

The listener example installs typed reader/writer adapters with explicit masks.
It writes one message, then deliberately misses a 200 ms deadline and a one-second
manual liveliness lease. Callbacks consume data promptly and communicate failures
to the main thread. See :doc:`howtos/operations` for listener inheritance and
callback semantics and :doc:`howtos/waitset` for condition lifetimes.

The vehicle's transient-local history exists while its writer is alive and retains
only ten updates. Start receivers first when requesting the full finite stream.
Throughput numbers include Java/application overhead and exclude network headers.

Run the real entry points as integration tests:

.. code-block:: console

   ctest --test-dir build -R '^example-' --output-on-failure

The ten tests use separate JVMs for pairs, a bundled native runtime, loopback UDP
and finite timeouts. Local socket access is required.
