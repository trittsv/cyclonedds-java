..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


Implementation progress and remaining work
==========================================

The provider now has working implementations in all four usability areas from
the initial roadmap. This is still an experimental subset of DDS-Java, not a
complete conformance profile. See the :doc:`coverage matrix <standards>`.

Implemented
-----------


* Native environment-wide waitsets and guard/read/status conditions; bounded and
  infinite waits, one-waiter validation, attachment changes and shutdown wakeup.
  HelloWorld uses conditions instead of polling for matches/data.
* Volatile/transient-local durability and explicit durability-service retention,
  resource limits, partition, deadline, lifespan, liveliness, ownership/strength,
  time-based filtering and writer lifecycle; topic/default propagation, historical
  data waits and native QoS update/error handling.
* Fixed multidimensional arrays and array aliases, constructed/nested sequences,
  union classes with validated branch/discriminator access, and appendable XCDR2
  framing. Independent C peers test collection, union and appendable wire formats.
* Next-sample operations using provider-owned samples, copied-list sample reuse,
  all topic/writer/reader status getters, status changes and liveliness assertion.
* Standard listener callbacks with ancestor fallback, per-participant dispatch,
  immutable status snapshots and reader notification; query conditions with
  waitset-preserving parameter updates, SQL content selectors, content-filtered
  topics, native next-instance traversal, key-value reconstruction and validated
  timestamped registration overloads.
* CPack development archives with checksums, license notices inside JARs,
  installable compiler/runtime/CMake package, documented Java-only consumer, and
  an installation integration test. CI is configured to retain archive artifacts.

Remaining API work
------------------

Complete the SQL grammar and unsigned comparisons, remove the bridge's 64-query
callback limit when the core supplies a context-bearing API, and expose full
per-policy incompatibility counts if the core makes them available. Timestamped
registration is a local operation with no stored/source timestamp. Complete the
remaining standard writer/reader operations. Dynamic types, coherent access and XML QoS
profiles remain unsupported. Built-in discovery and matched endpoint metadata are
implemented; their introspection restrictions are documented in :doc:`howtos/operations`. Next-sample storage currently requires a sample
returned by this provider; arbitrary user sample implementations are rejected.

Remaining QoS work
------------------

The standard policy factories and entity QoS views now include destination order,
binary user/group/topic data, latency budget, transport priority, reader lifecycle,
representation, entity factory, presentation and exact/assignable type consistency.
Behavioral tests cover deadline/liveliness, ownership transfer, filtering and live
updates; an independent C peer verifies discovery metadata. The core time-based
filter is tested at reader creation; enabled interval changes are rejected because
the unmodified cache does not apply them.

Remaining restrictions include finite reader auto-purge timers, legacy
exact-name/declared consistency modes, disabled entities, coherent/ordered access,
external persistent/transient durability services.
Enabled changes to matching policies remain restricted by the core and fail
explicitly. More lifecycle combinations and distributed stress tests remain work.

Remaining IDL work
------------------

Optional/external members, inheritance, recursive types, mutable XCDR2,
wide characters/strings and arrays of sequences are implemented. Independent C
interoperability covers these mappings, additional union discriminators and
mutable/appendable evolution vectors. Runtime Java annotations, defaults/ranges,
compact enums and name-collision handling have also been expanded.

Java mapping naming/width/string options, constants containers, package annotations,
annotation groups, transient fields and bounded string/sequence/extensible-enum
try-construct recovery are implemented within the core parser's restrictions.
Wchar unions and negative signed 64-bit union labels are rejected. Wide annotation
members and conflicting repeated annotations remain core parser limitations.
TypeObject labels remain signed 32-bit by the XTypes specification.

Remaining work includes non-scalar user annotations, broader aggregate recovery
validation and unbounded non-tail appendable and direct appendable collection XCDR1 layouts. Bit sets/masks, maps and other IDL building blocks remain outside the
current profile. Empty constructed sequence elements need additional bounds
handling.
Application types must continue to require no application-specific native library.

Remaining release and validation work
-------------------------------------

Verify the configured Linux x86-64/ARM64, macOS ARM64 and Windows x86-64 CI. Provide a
published Maven/Gradle generator/native workflow, reproducible archives and
sanitizer/stress runs. The standalone runtime JAR bundles the matching core/JNI libraries; code
generation uses the separate ``org.eclipse.cyclonedds.idlc`` package, including
its platform libraries and Maven goal. The runtime artifact is
``org.eclipse.cyclonedds``; both use the core version.

A standards claim still requires choosing a complete DDS profile, mapping every
applicable requirement to implementation/tests, and closing all required gaps.
The included normative API sources do not establish full conformance.
