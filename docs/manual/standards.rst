..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


OMG standards coverage
======================

The implementation targets two distinct standards:

* `DDS-Java 1.0: Java 5 Language PSM for DDS <https://www.omg.org/spec/DDS-Java/1.0/PDF>`_\ , which defines the application API and its behavior.
* `IDL4-Java 1.0: IDL4 to Java Language Mapping <https://www.omg.org/spec/IDL4-Java/1.0/PDF>`_\ , which defines generated Java type mappings.

**Current status: based on these specifications, with a tested subset of the
normative DDS-Java API. Full conformance is not established.**

DDS-Java clause 2 ties conformance to complete DDS profiles, including all
interfaces required by the selected profile and a supported type representation.
IDL4-Java clause 2 requires the applicable clause 7 mappings. Implementing a few
interfaces or using the standard package names does not establish either result.
The README therefore does not make an unqualified compliance claim.

Normative API provenance
------------------------

The unmodified Java sources from the OMG DDS-Java
`normative source archive <https://www.omg.org/spec/DDS-Java/20121201/omgdds_src.zip>`_
are stored in ``third_party/omg-dds/src``. Its license and notices are retained;
``ORIGIN.md`` records the archive checksum. The build produces an independent
``omg-dds-api.jar``. Vendor implementations are under ``org.eclipse.cyclonedds.omg``\ ,
with no vendor additions to ``org.omg.dds``.

``helloworld-java`` and ``omg-api-tests`` compile against the normative DDS API JAR,
the separate supported IDL4 API interfaces, and generated types, without
``org.eclipse.cyclonedds.jar`` on their compilation classpath.
They run with the Cyclone provider selected through ``ServiceEnvironment``.
This demonstrates source compatibility for those applications and behavior for
the tested paths. It does not demonstrate every profile requirement.

The normative archive contains a known inconsistency in
``TypeSupport.newTypeSupport(Class, ServiceEnvironment)``\ : its implementation passes
``type.getClass().getName()`` as the registered name, which evaluates to
``java.lang.Class``. The archive is retained unchanged. Use the standard explicit
registered-name overload for an interoperable IDL name, or create the topic from
the generated class directly, as the example does. Alternate explicit registered
names are supported separately from the schema's original XTypes name.

DDS-Java implementation coverage
--------------------------------

.. list-table::
   :header-rows: 1

   * - Area
     - Implemented and tested
     - Missing or restricted
   * - Bootstrap
     - ``ServiceEnvironment``\ , per-environment participant factory, provider selection
     - Other provider-specific configuration
   * - Type support
     - Standard ``TypeSupport``\ , generated class discovery, default data construction, registered names
     - Arbitrary Java type discovery and dynamic types
   * - Entities
     - Participants, topics, native publishers/subscribers, typed readers/writers, parents, lookups, retain/close
     - Disabled entity creation and full optional-profile behavior
   * - Lifecycle
     - Topic dependency checks, outstanding sample iterator checks, contained entity closure, closed-object exceptions
     - Full profile lifecycle validation across every optional entity kind
   * - QoS
     - Immutable views; reliability/history, durability/retention, resource limits, partition, deadline, lifespan, liveliness, ownership/strength, filtering, writer lifecycle, destination order, binary user/group/topic data, latency/priority, reader lifecycle API, exact/assignable consistency, representation/entity-factory/presentation values; native updates
     - Finite reader auto-purge timers, legacy exact-name/declared consistency, coherent/ordered presentation, disabled entities, external durability service, core-restricted enabled updates, XML profiles
   * - Writer
     - Ordinary/timestamped writes, matched handles, acknowledgment waits, ordinary/timestamped registration overloads, lookup, key-value reconstruction, handle-based dispose/unregister
     - Registration has no wire-visible timestamp; full per-policy incompatibility counts are unavailable
   * - Reader
     - Read/take, bounded batches, list and iterator forms, reusable selectors, sample/view/instance state filters, per-instance selection, next-sample mutation of provider samples, read/query conditions, next-instance traversal, SQL content selection, key-value reconstruction, historical-data waits, matched handles, sample metadata
     - Complete DDS SQL grammar and unsigned SQL comparisons
   * - Samples
     - Data, sample/view/instance states, source time, instance/publication handles, generation counters and ranks, clone
     - Complete cross-implementation serialization behavior
   * - Values
     - Durations, times, immutable copies, mutable/immutable instance handles
     - Full edge-case and cross-provider conformance testing
   * - Errors
     - Standard argument/unsupported errors, closed/precondition errors and native error translation
     - Exhaustive failure injection across all standard operations
   * - Other DDS facilities
     - Native environment-wide waitsets, guard/read/query/status conditions, standard listeners with ancestor fallback, content-filtered topics, all topic/writer/reader status getters, status changes, liveliness assertion, built-in discovery readers and matched endpoint metadata
     - Per-policy incompatible-QoS count sequences, coherent access and dynamic type APIs


Reader batches currently have a limit of 65,536 samples. Native operations are
serialized per participant, except blocking acknowledgment/historical-data waits
and waitset operations, which rely on native entity lifetime pinning. Unsupported features throw exceptions; the presence
of their declarations in the API JAR does not imply implementation support.

Selectors default to ``ResourceLimits.LENGTH_UNLIMITED``\ , subject to that batch
limit. Explicit limits from zero to 65,536 are accepted. A subscriber's new
``DataState`` starts with three empty sets, as required by the normative API; a
selection with any empty component matches nothing. Selector setters copy state
sets and handles, so later changes to their input objects do not change a
selection. Each read/take uses one settings snapshot. Concurrent selector
configuration requires application synchronization.

The list+selector overload limits a batch to the supplied list's current size;
pass null to allocate a new list without that additional limit. List overloads
return copied samples and reuse preexisting sample objects of the same type
returned by this provider. Foreign/custom sample objects are replaced. Iterators must be closed even
when their batch is empty. State and instance filters run in Cyclone DDS before
samples are copied or decoded in Java.

See :doc:`howtos/operations` for callback threading/shutdown, SQL subset, query
resource limits, filter updates and timestamped registration semantics.

IDL4-Java mapping coverage
--------------------------

.. list-table::
   :header-rows: 1

   * - Clause or mapping
     - Current implementation
     - Remaining work
   * - 7.1 names
     - IDL and Java naming schemes, collision diagnostics; reserved-name escaping
     - Complete nested declaration/profile coverage
   * - 7.2 scalar types
     - Boolean, char, octet, ordinary signed/unsigned integer bit patterns, float/double
     - Long double
   * - 7.2.4.2 strings
     - String/wide-string fields, StringBuilder/StringBuffer mappings, UTF-8/UTF-16 validation, bounded recovery
     - Complete character-range and annotation options
   * - 7.2.4.2 primitive sequences
     - Typed ``org.omg.type.*Seq`` interfaces, mutable list implementations, bounds, primitive bulk operations
     - Complete sequence API edge-case validation
   * - 7.2.4.3.1 structures
     - Serializable classes, private/transient fields, default/all-values constructors, IDL/JavaBeans accessors, optional/external boxed primitives, Java inheritance, recursive values, final/appendable/mutable XCDR1 and XCDR2 codecs
     - Core-restricted nested appendable, compact enum and mutable union XCDR1 layouts; broader aggregate recovery validation
   * - 7.2.4.3.3 enumerations
     - Java enums preserving numeric values, ``getValue()`` and ``valueOf(int)``\
     - Complete annotation/profile coverage beyond supported widths
   * - Arrays/collections
     - Fixed multidimensional arrays, array aliases, arrays of sequences, constructed and nested sequences; non-basic sequences map to ``List<E>``\ ; XCDR2 collection delimiters
     - Empty constructed sequence elements and broader size/annotation coverage
   * - 7.2.4.3.2 unions
     - Serializable final classes, discriminator/branch accessors, validated modifiers, explicit/implicit defaults, final/appendable/mutable XCDR1 and XCDR2 codecs, external members
     - Wchar discriminators, negative signed 64-bit labels, labels outside the signed 32-bit TypeObject range; broader profile coverage
   * - Aliases/modules
     - Scalar/array aliases; modules form packages with runtime annotations; constants containers
     - Complete nested declarations and non-scalar user annotations
   * - Other building blocks
     - Unsupported input is rejected
     - Bit sets/masks, maps, fixed-point, interface/value/component mappings


The primitive-sequence pseudocode in IDL4-Java contains Java-incompatible forms,
including constructors in interfaces and a primitive-returning ``get(int)`` while
extending ``List<T>``. The implementation uses inherited boxed ``List.get(int)``
(which Java unboxes), overloaded primitive writes and primitive-array bulk
operations. This interpretation and the remaining omissions are another reason
not to assert full mapping conformance.

The generated ``codec()``\ , ``typeSupport()``\ , short member accessors and legacy enum
helpers are vendor conveniences. Portable application code uses the mapped
getters/setters, ``getValue()`` / ``valueOf(int)``\ , and the OMG type-support factories.

See :doc:`howtos/idl` for the tested broader mappings, supported annotations and
explicit limits. Annotation and Java-naming support are not complete.

Evidence required before claiming conformance
---------------------------------------------

A future claim must name a complete DDS profile, account for every required API
and behavioral rule, complete the applicable IDL building blocks, and verify those
requirements. The regression suite provides regression and interoperability
evidence for the implemented subset; they are not an OMG certification suite.

Waitset and packaging behavior
------------------------------

A waitset supports one blocked thread, environment-owned guard/read/status
conditions across participants, bounded/infinite waits and concurrent attachment.
Timeouts raise ``TimeoutException``. Deleting an attached reader/condition removes
its attachment and wakes an existing waiter with ``AlreadyClosedException``.
Waitsets and guards additionally implement ``AutoCloseable``\ , since the normative
API has no close method; GC cleanup is also provided. Read conditions belong to
their reader and close with it. A selector caches its read condition until its
state/query configuration changes or the condition closes; instance-restricted
conditions remain unsupported.

The CMake distribution installs thin API/runtime JARs, separate multi-platform
runtime and compiler JARs, native libraries and generator backend,
CMake package, licenses/docs and a consumer example. CPack produces development
archives and SHA-256 checksums. An integration test builds and runs a Java-only
consumer from the installation. Linux, macOS ARM64 and Windows x86-64 CI is configured; only macOS ARM64
has been executed locally. Maven builds the runtime and the separate compiler
package with its source-generation goal.
Byte-for-byte archive reproducibility and a complete published Maven/native
release workflow are not established.

The runtime JAR combines the normative API, supported IDL API,
provider and matching core/JNI libraries. A loader verifies bundled library
SHA-256 digests, extracts them into a private temporary directory and loads the
core before JNI. The bundled-runtime test runs with an unusable native search
path and empty loader environment variables. Applications need no manual native
loading or external core installation when running that artifact.

The native bundle error test rejects altered resource checksums and unsupported
platforms in isolated JVMs and verifies temporary extraction cleanup. The Maven
consumer and standalone compiler tests generate and compile Java types using the
separate compiler and runtime JARs, without a platform classifier or external native tools.
The installed consumer exchanges DDS data with only the runtime JAR on its classpath.
