..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


Comparison with RTI Connext and OpenSplice
==========================================

This is a source/API comparison, not an interoperability or performance benchmark.
RTI's linked current Java API reference identifies version 7.7.0i. OpenSplice is
compared at commit ``a4818af5f5931834501055cd7ce2339de4f0af13``.

Does the standard use ServiceEnvironment?
-----------------------------------------

Yes. OMG DDS-Java 1.0 defines ``ServiceEnvironment`` and provider selection through
``org.omg.dds.serviceClassName``. OpenSplice's Java 5 API implements this model in
`OsplServiceEnvironment.java <https://github.com/ADLINK-IST/opensplice/blob/a4818af5f5931834501055cd7ce2339de4f0af13/src/api/dcps/java5/common/java/code/org/opensplice/dds/core/OsplServiceEnvironment.java>`_.
Our ``CycloneServiceEnvironment`` follows the same standard bootstrap mechanism.
The normative sources are retained unchanged; the provider property is required
by their ``ServiceEnvironment.createInstance(...)`` implementation.

The `RTI Java reference <https://community.rti.com/static/documentation/connext-dds/current/doc/api/connext_dds/api_java/RTI_ConnextDDS_ApiReferenceJava.pdf>`_
documents the classic ``com.rti.dds`` API, including
``DomainParticipantFactory.get_instance()`` and snake-case entity methods. It does
not describe the OMG DDS-Java 1.0 ServiceEnvironment bootstrap. Its examples use
generated type support, typed readers/writers, waitsets and explicit loan returns.
It also documents listeners, XML QoS, built-in discovery and dynamic data, which
this provider does not yet implement completely. These API styles differ;
sharing DDS concepts does not make their Java application sources interchangeable.

OpenSplice's separate classic
`java binding <https://github.com/ADLINK-IST/opensplice/tree/a4818af5f5931834501055cd7ce2339de4f0af13/src/api/dcps/java>`_
uses ``DDS.*`` and ``DDS.DomainParticipantFactory.get_instance()``. Its
`DomainParticipantFactoryImpl <https://github.com/ADLINK-IST/opensplice/blob/a4818af5f5931834501055cd7ce2339de4f0af13/src/api/dcps/java/common/java/code/org/opensplice/dds/dcps/DomainParticipantFactoryImpl.java>`_
loads the JNI library ``dcpssaj`` internally with ``System.loadLibrary``.

How the layers compare
----------------------

OpenSplice's `Java 5 factory <https://github.com/ADLINK-IST/opensplice/blob/a4818af5f5931834501055cd7ce2339de4f0af13/src/api/dcps/java5/common/java/code/org/opensplice/dds/domain/DomainParticipantFactoryImpl.java>`_
delegates to the classic ``DDS.DomainParticipantFactory``. Its
`Java 5 type support <https://github.com/ADLINK-IST/opensplice/blob/a4818af5f5931834501055cd7ce2339de4f0af13/src/api/dcps/java5/common/java/code/org/opensplice/dds/type/TypeSupportImpl.java>`_
finds the generated classic type-support class reflectively. Its
`reader implementation <https://github.com/ADLINK-IST/opensplice/blob/a4818af5f5931834501055cd7ce2339de4f0af13/src/api/dcps/java5/common/java/code/org/opensplice/dds/sub/DataReaderImpl.java>`_
uses a reflection reader over the classic binding.

Our provider instead calls Cyclone DDS through one generic JNI/type-support layer.
``idljava`` uses Cyclone's AST and XTypes generator to emit Java types and codecs;
Java performs serialization, while the native core validates CDR, extracts keys,
and handles discovery, history, reliability and transport. Application types need
no per-type C library. This follows the Cyclone Python/C++ architectural approach;
it is not an implementation of OpenSplice's classic ``DDS.*`` API.

Current maturity
----------------

The architecture is usable for the supported subset, but this project is **not at
production maturity or API coverage parity** with the compared implementations.
The current evidence is local macOS integration testing, independent C peers,
a standalone runtime JAR test, and an installed Java-only consumer. This does not
establish performance, long-running stability, security-plugin coverage or a
complete standard profile.

Listeners, standard status getters, query/content filters, key-value reconstruction
and next-instance traversal are now implemented and integration-tested, with
restrictions documented in :doc:`howtos/operations`. Built-in discovery and matched
endpoint metadata are implemented. Concrete gaps include dynamic types, coherent
access, XML QoS and complete mapping annotations.
Mutable/optional/external/recursive IDL, inheritance and wide text now have
independent C interoperability tests; :doc:`howtos/idl` records their restrictions. Java entity dispatch currently uses dynamic proxies; typed
implementation classes and broader lifecycle/failure audits remain maintainability
work. Native platform coverage, stress/sanitizer tests and a published release
workflow also remain incomplete.

The runtime JAR embeds matching native Cyclone cores and JNI libraries for the included platforms, extracts them into a private temporary directory, verifies their
SHA-256 digests and loads dependencies in order. Application code need not call
``System.loadLibrary`` or configure ``java.library.path``. This is a packaging feature;
it does not remove the coverage and validation gaps above.
