..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


Development distributions
=========================

Build and validate the binding before producing archives:

.. code-block:: sh

   cmake --build build --parallel
   ctest --test-dir build --output-on-failure
   cmake --build build --target package

CPack creates platform-specific ``org.eclipse.cyclonedds-11.0.1-*.tar.gz`` and ``.zip``
files and accompanying SHA-256 checksum files in the build directory. These are
experimental development artifacts, not official Eclipse releases. Nothing is
published by this command. Archive bit-for-bit reproducibility is not established.

A distribution includes:

* ``share/java/org.eclipse.cyclonedds-11.0.1.jar``: API, provider, JNI and native runtimes.
* ``share/java/org.eclipse.cyclonedds.idlc-11.0.1.jar``: compiler launcher, compilers and their platform libraries.
* ``share/java/org.eclipse.cyclonedds-11.0.1-<platform>.jar``: per-platform runtime assembly input.
* ``share/java/org.eclipse.cyclonedds.idlc-11.0.1-<platform>.jar``: per-platform compiler assembly input.
* ``share/java/omg-dds-api.jar``\ : unmodified normative DDS-Java API and Apache notices.
* ``share/java/idl4-java-api.jar``\ : supported sequence interfaces and project license.
* ``share/java/org.eclipse.cyclonedds.jar``\ : provider, codec runtime and project notices.
* ``lib/libcycloneddsjava.*``\ : generic JNI runtime.
* ``lib/libcycloneddsidljava.*``\ : IDL compiler backend.
* ``lib/cmake/CycloneDDSJava``\ : downstream generator/package integration.
* ``share/doc/org.eclipse.cyclonedds``\ : license, README, coverage and usage documentation.
* ``share/org.eclipse.cyclonedds/consumer-example``\ : a standalone Java-only consumer project.

Each runtime/compiler JAR requires JDK 17+ and a platform present in its manifest.
It extracts only the build host's compiler or the running application's native
libraries, checks SHA-256 digests, and loads dependencies in order. The temporary
filesystem must allow native code to load/execute.

The ``org.eclipse.cyclonedds.idlc`` Maven goal generates application types using
its separate compiler package,
without a system Cyclone DDS installation, CMake or a C compiler. Building the
binding itself still requires the matching native core and development tools.
A local CMake build combines the local platform; :doc:`maven` describes the
multi-platform assembly and mandatory release platform checks. Release targets
are Linux x86-64/ARM64, macOS ARM64 and Windows x86-64.

Verify an extracted archive
---------------------------

Extract it into a fresh directory and configure the included consumer:

.. code-block:: sh

   cmake -S /path/to/distribution/share/org.eclipse.cyclonedds/consumer-example \
     -B consumer-build \
     -DCMAKE_PREFIX_PATH="/path/to/distribution;/path/to/cyclonedds-install"
   cmake --build consumer-build --parallel
   ctest --test-dir consumer-build --output-on-failure

The consumer project declares only ``LANGUAGES Java``. It generates an appendable
keyed type, compiles against the normative API and generated types without the
provider JAR on its compilation classpath, then loads the bundled provider/core/JNI
and exchanges data using a native waitset. The root ``installed-consumer`` test
performs this install/configure/build/run workflow automatically.

The provider is selected by:

.. code-block:: text

   -Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment

At runtime, include only the runtime JAR and generated application JAR
on the classpath. Application code does not load libraries or set native paths.
The installed CMake package exposes ``CycloneDDSJava_BUNDLED_RUNTIME_JAR`` for
this purpose, alongside the thin API/runtime paths used for compilation.

Maven and CI
------------

``mvn package`` at the repository root builds the Java runtime. The
``maven/pom.xml`` reactor also builds ``org.eclipse.cyclonedds.idlc``, a compiler
JAR that is both directly executable and usable as a Maven build plugin. There
is no separate ``*-maven-plugin`` artifact. Runtime and compiler packages have
no dependency on one another, and Maven APIs are provided by Maven itself.

The CMake ``maven-install`` target installs the assembled runtime JAR and the
compiler JAR with its Maven integration. Explicit ``cyclonedds.runtime.jar`` and
``cyclonedds.idlc.jar`` properties supply the packaged native resources. See
:doc:`maven` for commands. Nothing is published automatically.

``.github/workflows/universal.yml`` builds Linux on ARM64 and x86-64,
macOS on ARM64, and Windows on x86-64,
aggregates each kind into its own JAR, then verifies the same runtime/compiler
pair on all four hosts. Each build uses the pinned core below. Local validation
is macOS ARM64; remote workflow results must be checked separately.

Unmodified core and reproducibility
-----------------------------------

The binding builds against unmodified Cyclone DDS revision
``3a5526315e64ac1d2a52e6ac1ae7f3e861c593d3``. CI checks out and builds that revision
directly. No binding-specific core patches are applied, installed or bundled.
The standalone runtime JAR embeds the matching unmodified core and its original
license notices. Runtime behavior is verified against independent C peers built
with the same unmodified installation.

Core limitations remain explicit: unsupported enabled-reader time-based filter
updates are rejected, wchar/negative signed 64-bit union labels are diagnosed,
and generated representation restrictions keep unsupported schemas off XCDR1
endpoints. Java codec-only vectors do not establish native DDS interoperability.
