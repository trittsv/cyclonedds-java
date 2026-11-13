..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Java binding
============

|var-project-short| provides a tested subset of the official OMG DDS-Java 1.0
API over the native |var-core-project-short| runtime. The provider remains
experimental; it does not claim full standard-profile conformance.

Applications use generated Java types, the ``org.omg.dds`` interfaces and a
runtime JAR ``org.eclipse.cyclonedds``. It contains the API, provider, JNI and
matching core libraries for its included platforms. The separate build tool
``org.eclipse.cyclonedds.idlc`` contains the compiler and its platform libraries. It extracts and loads the native libraries automatically.
No application-specific C type library or manual native-library loading is needed.

See :doc:`maven` for automatic generation during a Java build, :doc:`idljava` for
compiler options, :doc:`howtos/exchange` to read and write
samples, :doc:`howtos/waitset` to wait for data, and :doc:`distribution` for
build/install instructions. :doc:`comparison` explains the API relationship to
RTI Connext and OpenSplice, and :doc:`standards` lists current limitations.

Requirements
------------

Running the runtime JAR requires JDK 17+ and an included operating system
and CPU architecture. Java extracts the native libraries into the JVM temporary
directory; that filesystem must permit loading native code.

Application type generation uses the packaged host compiler through Maven or
``java -jar``; no native build tools are needed. Building the binding itself
requires CMake 3.21+, JDK 17+, a C11 compiler and the matching Cyclone DDS installation with IDL/DDSI development
headers and the ``idlc`` compiler. The tested core revision is
``3a5526315e64ac1d2a52e6ac1ae7f3e861c593d3`` (11.0.1).
