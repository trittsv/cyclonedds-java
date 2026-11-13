..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


.. |m2r-link-1ae90e8129cb| replace:: \ ``clayer/pysertype.c``
.. _m2r-link-1ae90e8129cb: https://github.com/eclipse-cyclonedds/cyclonedds-python/blob/68b2acfd5ac56bd0f095e51914a9f6c494675327/clayer/pysertype.c


Binding architecture
====================

The design separates the standard application API, IDL generation, Java CDR
serialization and the native Cyclone DDS runtime. Application code uses the
unmodified OMG DDS-Java interfaces. Vendor implementations live under
``org.eclipse.cyclonedds.omg``.

Reference bindings
------------------

These source revisions were inspected while implementing the generator and type
support. The links are pinned so the comparison remains reproducible.

.. list-table::
   :header-rows: 1

   * - Repository
     - Revision
     - Relevant components
   * - `C++ <https://github.com/eclipse-cyclonedds/cyclonedds-cxx/tree/33d27ffff4d76021d88653f778cc001aa1a2bb7a>`_\
     - ``33d27ffff4d7``\
     - ``src/ddscxx``\ , ``src/idlcxx``\
   * - `Python <https://github.com/eclipse-cyclonedds/cyclonedds-python/tree/68b2acfd5ac56bd0f095e51914a9f6c494675327>`_\
     - ``68b2acfd5ac5``\
     - ``cyclonedds/idl``\ , ``clayer``\ , ``idlpy``\
   * - `Rust <https://github.com/eclipse-cyclonedds/cyclonedds-rust/tree/ef0dd5cc1477b29449aba1f717d9496db5953b2a>`_\
     - ``ef0dd5cc1477``\
     - ``cyclonedds``\ , ``cyclonedds-sys``\ , ``cyclonedds-macros``\
   * - `Core <https://github.com/eclipse-cyclonedds/cyclonedds/tree/3a5526315e64ac1d2a52e6ac1ae7f3e861c593d3>`_\
     - ``3a5526315e64``\
     - ``ddsc``\ , ``ddsi``\ , ``cdr``\ , ``idlc``\


All three bindings leave discovery, transport, reliability, history and instance
management in the C core. Their language-specific layers provide entities, type
metadata and serialization.

C++ generator and runtime
^^^^^^^^^^^^^^^^^^^^^^^^^

The C++ public API lives under ``dds``\ ; implementations and delegates live under
``org/eclipse/cyclonedds``. Its ``idlcxx`` backend uses Cyclone's IDL AST to generate
C++ types, traits and streamers. The C++ runtime implements its own Basic CDR,
XCDR1 and XCDR2 streams. In ``src/idlcxx/src/traits.c``\ , the compiler configuration's
``generate_typeinfo_typemap`` callback supplies language-neutral XTypes metadata.

Java uses that same compiler callback. ``src/idljava`` is a real ``idlc`` backend and
uses the existing parser and preprocessor. It generates Java source files only;
applications do not compile a C descriptor or load a native library for each type.

Python serialization and type support
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Python combines a language-level entity API, ``ctypes`` and a C extension.
``cyclonedds/idl`` builds serialization and XTypes information from Python types;
``idlpy`` provides an IDL backend. Writers serialize application objects before
crossing the native boundary.

The byte-oriented type support in
|m2r-link-1ae90e8129cb|_
implements DDSI ``sertype`` and ``serdata`` callbacks. After registration, its
``init_cdrstream_descriptor`` reconstructs a CDR descriptor from XTypes for validation
and key extraction. Java follows this runtime approach in ``typesupport.c``\ ; the
attribution is recorded in :download:`the third-party notices <../../THIRD_PARTY_NOTICES.md>`.

Rust layering
^^^^^^^^^^^^^

Rust separates generated C bindings (\ ``cyclonedds-sys``\ ), the typed API
(\ ``cyclonedds``\ ) and derive macros (\ ``cyclonedds-macros``\ ). Its ``Topicable`` trait ties
together Serde, type names and keys; DDSI operations use ``cdr-encoding``. In the
inspected revision, some XTypes callbacks in ``internal/sertype.rs`` are commented
out. The reference bindings therefore provide architectural guidance without
implying identical coverage or implementation details.

Java application and generation path
------------------------------------

.. code-block:: text

   IDL -> Cyclone idlc + idljava
             -> Serializable Java classes and enums
             -> Java CDR codecs
             -> XTypes TypeInformation and TypeMapping

   Application compiled against OMG DDS-Java
             -> ServiceEnvironment / participant / topic / publisher / subscriber
             -> typed reader and writer
             -> generated Java codec
             -> JNI
             -> binding-owned DDSI sertype / serdata
             -> Cyclone DDS core -> RTPS

The normative API is vendored unchanged under ``third_party/omg-dds``\ , with its
upstream Apache-2.0 license, notices and archive checksum. It builds into
``omg-dds-api.jar``. ``CycloneServiceEnvironment`` is selected with the standard
``org.omg.dds.serviceClassName`` property. Factories and type support are concrete
provider classes; entities are exposed through Java proxies implementing the
actual OMG interfaces. Dispatch checks supported overloads and rejects unsupported
operations explicitly.

The older compact API remains available for internal regression tests. It is a
vendor API, has different lifecycle behavior in some cases, and is not the
standards-facing application API. The example and portable API test compile
without the provider JAR on their compilation classpath. The supported IDL4
sequence interfaces are packaged separately as ``idl4-java-api.jar``.

Generated structs are serializable classes with private fields, no-argument and
all-values constructors, and IDL-style ``get_<member>`` / ``set_<member>`` accessors. Unions expose validated
discriminator/branch modifiers and active-member getters.
Enums expose ``getValue()`` and ``valueOf(int)``. Primitive sequences use typed
``org.omg.type.*Seq`` list interfaces with implementations in the vendor package.
Generated static codec/type-support methods and short convenience accessors are
vendor extensions; portable application code uses the standard entry points and
mapped accessors. :doc:`Standards coverage <standards>` explains the remaining gaps.

.. list-table::
   :header-rows: 1

   * - Directory
     - Responsibility
   * - ``third_party/omg-dds``\
     - Unmodified normative DDS-Java API
   * - ``src/java/.../omg``\
     - Service provider, entities, supported QoS, values and samples
   * - ``src/idljava``\
     - IDL compiler backend
   * - ``src/java/org/omg/type``\
     - IDL4-Java primitive sequence interfaces
   * - ``src/java/.../type/seq``\
     - Primitive sequence implementations
   * - ``src/java/.../core/cdr``\
     - Java CDR codec runtime
   * - ``src/jni``\
     - JNI calls into Cyclone DDS and generic DDSI type/sample support
   * - ``cmake``\
     - Generator integration and installed CMake package
   * - ``examples/helloworld``\
     - IDL and application source using the OMG API
   * - ``tests/portable``\
     - Application source compiled against OMG interfaces only
   * - ``tests/c``\
     - Independent C interoperability peer

The runtime sources (``src/java`` and ``src/jni``) sit alongside the compiler
backend (``src/idljava``), following the sibling-component layout of the reference
bindings. CMake uses these paths directly; Maven explicitly configures
``src/java`` as its source directory.

``src/jni`` is the Java binding's native integration layer. ``bridge.c``
implements JNI entry points, native entity operations, listeners and filters;
``typesupport.c`` / ``typesupport.h`` implement DDSI type/sample callbacks,
XTypes metadata integration and key extraction. Java codecs serialize application
values. This corresponds to Python's ``clayer`` and the native type-support
integration within C++'s ``src/ddscxx``; JNI identifies the Java-specific boundary.


Native memory and resource ownership
------------------------------------

Type support owns copies of the generated XTypes blobs. DDSI serialization callbacks
do not call into the JVM. Separate listener/filter callbacks retain scoped JNI global
references: listeners enqueue Java events, while filters execute compiled predicates
using generated codecs/accessors without calling DDS. DDS reference counts
control native type lifetime. Equivalent types are interned by metadata; a native
lock protects initialization of the reconstructed CDR descriptor.

Serialized samples own padded byte buffers. DDSI callbacks accept fragmented RTPS
payloads in either XCDR1 or XCDR2, validate a native-order copy and extract keys. The original payload
is retained for Java try-construct recovery, since core normalization can patch
strings and enum values in place. Keys are compared in
native XCDR2 representation. RTPS key hashes use big-endian key serialization and
MD5 when required. Full samples are distinguished from key-only samples when
registering or disposing instances, including keys that follow non-key fields.

Reader operations copy CDR and all native sample-info fields into Java before
releasing native references. The OMG provider returns standard samples and sample
iterators. It tracks outstanding iterators and rejects reader/parent closure until
they close. Topic closure is rejected while a reader or writer still uses that
topic. Publisher and subscriber objects own real native DDS containers.

The provider synchronizes native operations with resource closure under the
participant's monitor. QoS objects and supported policies are immutable. Unsupported
QoS policies and dynamic APIs fail explicitly.
No native capability is presented as implemented merely because its interface is
available in the normative API JAR.

Reusable standard reader selectors snapshot their state sets, instance handle and
batch limit for each operation. JNI forwards state masks and optional instance
handles to Cyclone's read/take collector API, including its next-instance variants;
Java does not fetch and discard nonmatching samples. This follows the native
selection approach of Python's ``DataReader.read/take`` and C++'s reader selectors.
Empty Java state components are handled before calling C, where a zero component
would otherwise mean ANY. Closed children are removed from their parent lists so
repeated endpoint creation does not retain every closed endpoint.

Validation and limits
---------------------

The suite checks generated mappings, Java object serialization, wire-format
vectors, bounds, keyed/keyless samples, native validation and resource lifetime.
The portable OMG API test exercises bootstrap, factories, supported QoS,
publish/subscribe, read versus take, metadata, sample iterators, instance operations
and timestamps. The independent C peer checks Unicode, fragmented 100-kB samples,
composite keys and disposal notifications.
The separate portable selector test covers unread/read and new/not-new transitions,
per-instance removal, empty state components, bounded lists, selector ownership,
closed readers and invalid-data lifecycle filtering.

The generator handles final/appendable/mutable structs and unions, optional and
external members, real Java inheritance, forward/recursive types, compact enums,
scalar/array aliases, fixed arrays including sequence elements, wide text and
primitive/constructed/nested sequences. Collection codecs add XCDR2 delimiter headers for non-basic elements.
Mutable types use member-ID parameter lists: XCDR1 has compact/extended
parameter headers and a sentinel, while XCDR2 uses a DHEADER and EMHEADERs.
XCDR1 optional values also use parameter headers. Each XCDR1 member body has
its own alignment origin, and 64-bit values retain eight-byte alignment.
Appendable types use plain XCDR1 or delimited XCDR2; nested type framing
preserves the outer encapsulation. Generated readers propagate known value
boundaries into tail fields. XCDR1 optional and mutable parameter bodies establish
independent boundaries; collection elements clear inherited boundary hints.
Appendable readers consume unknown trailing bytes only within those known
bounds, so they cannot consume a following parent field. The native validator checks framing against top-level extensibility.
Generated representation flags combine the core IDL compiler's restrictions with
conservative guards for layouts the unmodified native validator cannot process.
Codec-only decoding support does not override those native endpoint restrictions. It does not yet implement all IDL
building blocks or all DDS profiles. Neither complete DDS-Java conformance nor
complete IDL4-Java conformance is claimed. DDSI and compiler plugin interfaces are
version dependent; use the same Cyclone DDS installation at build time and runtime.

Listener dispatch and filtering
-------------------------------

Native listeners copy status values into a Java event queue. One daemon executor
per participant invokes application listeners outside participant monitors and
native callbacks. Effective masks choose the nearest enabled ancestor listener;
each event retains its actual source and an immutable status snapshot. Target
replacement/closure cancels queued delivery, and participant closure shuts down
its executor. In-flight callbacks may finish afterward. Native entity deletion
quiesces callbacks before JNI global references are freed.

SQL predicates decode a sample with its generated Java codec and use accessors
validated when the expression is compiled. Query predicates run in the reader
cache, so unmatched samples are neither marked read nor removed. The argument-free
C query callback API is bridged with 64 reusable trampolines. Changing query
parameters creates a replacement native condition, transfers waitset attachments,
then deletes the old condition and releases its callback reference. Each waitset
tracks its actual attached handle to handle concurrent replacement/attachment.
Filtered topics use separate native topic handles with receiver-side admission
filters. Changing topic parameters atomically replaces the compiled predicate.
Key-only lifecycle notifications are retained. See :doc:`howtos/operations` for
SQL and resource restrictions.

``getKeyValue`` asks the core instance table for serialized keys. The native CDR
descriptor reconstructs serialized key values. Generated key-copy methods apply
only the selected IDL key paths to the Java holder, preserving non-key values even
when appendable key decoding in the core defaults trailing fields. No per-instance Java object cache
or application-specific C code is needed.

Waitables and distributions
---------------------------

Waitsets and guards are native DDS runtime-owned objects, matching the OMG
ServiceEnvironment-wide factories without a hidden participant. Waitsets attach
themselves internally for configuration/shutdown wakeup, and block in
``dds_waitset_wait`` outside Java participant monitors. Conditions retain weak
references to attached waitsets for deletion notifications. Read conditions belong
to their reader. Waitset/guard native cleanup uses Cleaner actions that retain
only native handles; optional AutoCloseable permits deterministic cleanup.

Policies encode QoS into a single internal JNI configuration containing numeric
settings, byte arrays and partitions. Participant, topic, container and endpoint
creation and updates use the same conversion. Byte-data values are copied on
input and output. Unsupported core profiles are validated before native changes;
Java QoS views are committed only after native success.

Creation supplies all applicable settings before the entity is enabled; updates
call ``dds_set_qos``. Core restrictions remain visible as standard exceptions,
with changed-policy names included for unsupported enabled-entity updates.

CPack development archives contain the three Java artifacts, generic native
runtime, compiler backend, installed CMake package, documentation and consumer
source. An installation integration test runs a downstream Java-only build and
DDS exchange. JAR resources carry the appropriate project or upstream notices.

The standalone runtime JAR merges those Java artifacts and embeds the matching
core/JNI libraries under ``org/eclipse/cyclonedds/native/<os>-<arch>``. A generated
manifest orders native dependencies and records SHA-256 digests. NativeLoader
validates every extracted file before loading any of them from a private JVM
temporary directory. Thin JARs retain development-system library loading when no
bundled platform resources are present.

The documentation follows the Cyclone C++/Python ``docs/manual`` Sphinx layout,
reStructuredText pages, JSON metadata, ``var-*`` substitutions, Piccolo theme and
helper assets. Java-specific API overview pages replace their language-specific
Breathe/autodoc integration. ``sphinx-build -W`` validates the manual in CI.

Broader IDL validation
----------------------

Generated codec dependencies are lazy, allowing recursive SCC schemas without
static-initialization cycles. Optional/external primitives are boxed Java values;
optional absence maps to null. Derived codecs flatten inherited members into the
enclosing representation while their classes extend the mapped base class.
Class-specific factories prevent Java static method hiding from selecting the
wrong type support. Runtime framing limits nesting to 128 constructed types and
rejects heap cycles before they exhaust the Java stack.

The broader interoperability test exchanges values with an independent C process
using loopback unicast discovery. It covers mutable/optional/external data,
inheritance, self/mutual recursion, UTF-16, sequence arrays and several union
forms. Additional vectors cover endian handling, unknown/missing/reordered
members, duplicate IDs and must-understand errors. See :doc:`howtos/idl` for
annotation, XCDR1 and core compiler restrictions.

Annotation generation and recovery
----------------------------------

The backend registers the OMG ``java_mapping`` annotation with ``idlc`` and
resolves options through enclosing scopes. Mapped names are separate from wire
names. Repeated user annotations use public Group containers; annotated modules
produce package-info sources. Transient members are removed from the metadata
view only while generating TypeObjects, with all AST links restored on return.
Their Java fields remain available, with defaults, and their codecs omit them.

Generated recovery scopes distinguish construction failures from malformed CDR.
Bounded sequence readers consume every element while retaining the allowed
prefix; string readers validate the entire text before trimming. Defaults use
fresh values and explicit member defaults where supplied. The native validation
copy allows these rules to see the original bounds and unknown enum values.
See :doc:`howtos/idl` for the supported scope and the TypeObject union-label limit.

Built-in discovery data
------------------------

The participant lazily creates a native built-in subscriber and its four readers.
The JNI collector retains native samples while collecting, then copies names,
GUIDs, advertised QoS and sample metadata into Java-owned snapshots after the
cache callback returns. It releases every native reference and QoS allocation.
Built-in data therefore uses its native discovery structures rather than an
application CDR codec. State selectors, conditions, listeners and sample loans
reuse the normal reader infrastructure; SQL content predicates are rejected.

Matched endpoint data is copied through the public native discovery APIs.
Participant/topic discovery inspection uses peek operations so it does not consume
or mark public built-in reader samples read. Built-in key lookup validates the
handle against that reader's cache and retains observed keys until reader closure;
it never casts arbitrary application instance data to a discovery structure.
The full 128-bit Cyclone key is preserved in the standard key's integer array.
