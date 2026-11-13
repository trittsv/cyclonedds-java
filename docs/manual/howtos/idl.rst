..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Richer IDL schemas
==================

Use the same ``idljava_generate`` command as in :doc:`exchange`. The backend
continues to generate Java source and XTypes metadata only; application types
need no C library.

Optional and external members
-----------------------------

.. code-block:: idl

   module Example {
     @mutable struct Packet {
       @id(1) @key long id;
       @id(2) @optional long count;
       @id(3) @optional string label;
       @id(4) @external long state;
       @id(5) wchar marker;
       @id(6) wstring<32> description;
       @id(7) sequence<long> batches[2];
     };
   };

Optional primitives use their boxed Java type: ``Packet.get_count()`` returns
``Integer``. Assign null to omit an optional member; absent optionals default to
null. Other optional types retain their normal Java reference type. External
primitives are boxed too, and external constructed values use Java references.
External members retain the ordinary wire representation; they do not add an
optional presence flag. A required external reference must have a value before
serialization. External constructed fields default to null to permit recursive
schema construction.

The example's batches map to ``org.omg.type.IntegerSeq[]``. Arrays of constructed
sequences use arrays of ``List<E>``; multidimensional arrays are supported too.
Defaults initialize each array element independently. Array dimensions and
sequence/string bounds are checked during serialization, as well as in setters.

Inheritance
-----------

.. code-block:: idl

   module Example {
     @mutable struct Base { @id(1) @key long id; };
     @mutable struct Derived : Base { @id(2) string text; };
   };

The mapped ``Derived`` extends ``Base``. Its all-values constructor takes the base
value first, followed by its own members:

.. code-block:: java

   var value = new Example.Derived(new Example.Base(42), "inherited key");
   assert value.get_id() == 42;
   var topic = participant.createTopic("DerivedValues", Example.Derived.class);

Codecs flatten inherited members in the enclosing wire representation, including
mutable member IDs. Key reconstruction includes inherited keys and preserves
non-key holder values. Final, appendable and mutable inheritance are tested
against C. Cyclone warns that evolving an appendable base is unsafe; use mutable
inheritance for schemas whose base members must evolve.

Codec/type-support static methods are vendor conveniences, outside the OMG
mapping. Every generated type has a class-specific ``$codec$TypeName()`` and
``$typeSupport$TypeName()`` helper, used internally by generated code and provider
discovery. Short ``codec()`` / ``typeSupport()`` helpers are omitted on classes
used as a base in the compilation unit, preventing inherited static methods from
silently selecting a base codec for a derived value. Applications using the OMG
API need none of these helpers: pass the generated class to ``createTopic`` or
the standard type-support factory.

Recursive values
----------------

.. code-block:: idl

   module Example {
     struct Node;
     @appendable struct Node {
       long value;
       sequence<Node> children;
       @optional Node next;
     };
   };

Forward declarations, self recursion and mutually recursive schemas resolve
through lazy codec references and the core's SCC type metadata. A default
sequence is empty and an optional reference is null, so default construction
terminates. Values on the wire must be finite: cyclic Java object graphs are
rejected, and encoding/decoding is limited to 128 nested constructed types.
Sharing the same object in separate completed branches is permitted.

The pinned core supports recursive type metadata. A core configuration that
explicitly disables ``Internal/AllowRecursiveTypes`` will reject recursive topic
registration. This binding does not override application core configuration.

XCDR and schema evolution
-------------------------

Writers emit little-endian XCDR1 or XCDR2, selected through representation QoS
(see :doc:`qos`). Readers support both byte orders and negotiate either or both
representations. XCDR2 remains the default. Final structs have plain framing;
XCDR2 appendable structs have a DHEADER, and mutable structs additionally have
member-ID parameter headers. Mutable unions encode discriminator
member zero and the selected branch's member ID. All XCDR2 length-code forms are
accepted, while writes use an explicit member length.

Mutable readers accept reordered known members, initialize missing members from
the generated defaults and skip unknown members. Unknown must-understand members,
duplicate IDs, truncated members, mismatched encapsulations and missing mutable
union branches fail explicitly. Appendable readers retain missing-member defaults
and skip trailing fields. Mutable unions, appendable unions, optional presence,
wide text and collection framing are verified by an independent native C echo
peer. Evolution vectors additionally exercise reordering, missing/unknown members,
must-understand rejection and big-endian mutable input.

Wide characters map to Java ``char`` and reject surrogate code units. Wide strings
map to ``String`` and use UTF-16 with a byte count, without a terminator. Bounds
count UTF-16 code units, matching the core; supplementary characters occupy two
units. Unpaired surrogates and embedded NULs are rejected.

XCDR1 works through native DDS endpoints, including final and appendable types,
optional parameter headers, mutable parameter lists and unions. Readers accept
compact and extended headers; writers use extended headers so large member IDs
and payloads are supported. Mutable lists terminate with the required sentinel.
Eight-byte alignment and parameter-relative alignment are preserved, and enums
occupy four bytes even when their XCDR2 representation is compact. Instance keys
remain consistent when XCDR1 and XCDR2 writers share a reader.

XCDR1 and the unmodified core
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Java uses the unmodified Cyclone DDS core. Native tests verify XCDR1 optional
members, mutable structs with scalar/string members, nested final/mutable types,
standalone appendable schema evolution and mixed XCDR1/XCDR2 instance identity.
For example:

.. code-block:: idl

   module Example {
     @mutable struct Sample {
       @key long id;
       @optional long value;
       string text;
     };
   };

.. code-block:: java

   var policies = org.omg.dds.core.policy.PolicyFactory.getPolicyFactory(participant.getEnvironment());
   var xcdr1 = policies.Representation().withValue((short) 0);
   try (var topic = participant.createTopic("Samples", Example.Sample.class);
        var writer = publisher.createDataWriter(topic,
            publisher.getDefaultDataWriterQos().withPolicy(xcdr1));
        var reader = subscriber.createDataReader(topic,
            subscriber.getDefaultDataReaderQos().withPolicy(xcdr1))) {
       writer.write(new Example.Sample(1, 42, "hello"));
   }

Generated type support derives representation restrictions from the IDL compiler,
following the C++ binding's approach. The pinned core additionally restricts
appendable inheritance, compact enums under XCDR1, and several nested appendable
layouts. Mutable unions and schemas marked as requiring XCDR2 by the compiler
use XCDR2. Unsupported configurations fail during endpoint creation; no core
modifications or alternate wire formats are introduced.

The standalone Java codecs retain broader wire-decoding tests, including bounded
appendable nesting. Those tests do not imply end-to-end support for layouts the
unmodified core rejects. Regenerate existing Java types after upgrading so their
type support includes the representation restrictions.

Annotations and names
---------------------

The backend supports the following annotations for its implemented type mappings:

* ``@id`` and ``@autoid`` determine mutable member IDs through Cyclone's AST.
* ``@final``, ``@appendable``, ``@mutable``, ``@optional`` and ``@external`` select
  framing and Java member representations.
* ``@key`` and ``@must_understand`` control keys and mutable member handling.
* ``@default``, ``@default_literal`` and ``@value`` determine defaults and enum values.
* ``@min``, ``@max`` and ``@range`` validate setters, including unsigned storage
  patterns. ``@unit`` has no Java representation change.
* Enum ``@bit_bound`` selects 8/16/32-bit XCDR2 storage while preserving a Java enum.
* User-defined scalar/string/enum annotations map to runtime Java annotations on
  classes, fields, accessors, enum constants and module packages. Each annotation
  has a public ``<Name>Group`` container for Java annotation compatibility.
  The core parser deduplicates identical applications and rejects conflicting
  repetitions; the backend uses only the annotations retained by that parser.
* ``@non_serialized`` retains a transient Java field and its default while omitting
  the field from both the Java wire codec and the generated XTypes schema.
  Applying it to a key or union branch is diagnosed.
* ``@try_construct(USE_DEFAULT)`` and ``TRIM`` recover bounded strings/sequences;
  ``USE_DEFAULT`` also handles unknown values of extensible enums. Final enums
  reject unknown values. Recovery consumes the full wire value, preserving the
  following field. Explicit member defaults are honored, and malformed wire
  lengths, invalid text and truncation still fail.
* ``@verbatim(language="java", ...)`` and ``language="*"`` insert Java text at
  file/declaration positions; struct/union bodies support beginning/end placement.
  The core parser still prints its generic unsupported-verbatim warning, even
  though this backend consumes the retained annotation.

The default naming convention preserves IDL names. ``@java_mapping`` is a built-in
annotation supplied by this backend; no separate IDL declaration is required:

.. code-block:: idl

   @java_mapping(apply_naming_convention=JAVA_NAMING_CONVENTION,
                 promote_integer_width=TRUE,
                 string_type="java.lang.StringBuilder")
   module EXAMPLE_TYPES {
     @final struct sensor_value {
       unsigned long sensor_id;
       unsigned long long counter;
       string<80> label;
     };
   };

This produces ``example_types.SensorValue`` with ``getSensorId()`` /
``setSensorId(long)``, ``getCounter()`` returning ``BigInteger`` and a
``StringBuilder`` label. Unsigned 8/16/32/64-bit integers promote to
``short`` / ``int`` / ``long`` / ``BigInteger`` without changing wire widths.
Promoted unsigned values are range checked. Primitive sequences use the promoted
``org.omg.type.*Seq`` interface; unsigned 64-bit sequences use
``List<BigInteger>``. ``string_type`` supports ``String``, ``StringBuilder`` and
``StringBuffer`` using their fully qualified names. Mutable string values compare
by text in generated equality and hash methods.

Options inherit from enclosing scopes; member annotations can override them.
``constants_container="Numbers"`` places constants in a public final ``Numbers``
class in their mapped package. Java naming lowercases module names, converts type
names to Pascal Case and enum literals/constants to uppercase, and supplies
JavaBeans accessors. IDL-style accessors remain available to the provider.
Filters continue to resolve original IDL field/enum names through generated
name metadata. Transformed-name collisions are diagnosed before code generation.

Java keywords and conflicting namespace prefixes are escaped. Generator storage
uses dollar-prefixed names, which IDL identifiers cannot contain. Convenience
getters are omitted when they would collide with mandatory accessors, Object
methods or static codec helpers. Regenerate type JARs after generator upgrades.

The unmodified core does not reliably support wchar union discriminators or
negative signed 64-bit union labels. The Java generator rejects these declarations
with explicit diagnostics. XTypes TypeObjects also restrict union labels to
signed 32-bit values. Wide scalar/string members remain supported. Wide annotation
members are rejected by the core parser, and conflicting repeated user annotations
are rejected rather than patched into the parser. Annotation members must use
supported scalar/string/enum types.
Long double, maps, bit sets/masks and other unimplemented IDL
building blocks remain outside this provider's supported profile.
