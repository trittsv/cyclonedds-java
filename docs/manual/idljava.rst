..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

IDL compiler
============

``idljava`` is an ``idlc`` backend using Cyclone's parser, AST and XTypes
metadata generator, following ``idlcxx`` and ``idlpy``. It emits Java source only.

.. code-block:: idl

   module Example {
     @final struct Message {
       @key long id;
       string text;
     };
   };

Maven integration
-----------------

The :doc:`maven` plugin generates and compiles application types as part of
``mvn package``. The separate ``org.eclipse.cyclonedds.idlc`` JAR bundles ``idlc``, the Java backend
and native dependencies, so application builds need no CMake or native tools.
The same compiler JAR can be invoked directly with ``java -jar ...``.

CMake integration
-----------------

.. code-block:: cmake

   cmake_minimum_required(VERSION 3.21)
   project(MyTypes LANGUAGES Java)
   find_package(CycloneDDSJava CONFIG REQUIRED)
   idljava_generate(TARGET message-types FILES Message.idl)

Configure with both binding/core installation prefixes, then build. The target
produces ``message-types.jar`` and generated sources under
``message-types-sources``. ``INCLUDES`` supplies IDL include paths; ``DEPENDS``
adds included IDL dependencies that trigger regeneration.

Direct invocation
-----------------

.. code-block:: sh

   idlc -l /path/to/binding/lib/libcycloneddsidljava.so -o generated Message.idl
   javac -cp org.eclipse.cyclonedds-11.0.1.jar -d classes generated/Example/Message.java
   jar --create --file message-types.jar -C classes .

Use ``.dylib`` on macOS. The backend is needed only while generating source.
A generated type JAR plus the runtime JAR is sufficient to run an
application; ``idlc`` and a C compiler are not runtime requirements.

Mappings
--------

Structs have default/all-values constructors and ``get_<member>`` /
``set_<member>`` accessors. Unions have discriminator and active-branch accessors
with validated modifiers. IDL modules form Java packages. Basic sequences use
``org.omg.type.*Seq``; constructed sequences use ``List<E>``. Fixed arrays use
Java arrays. Codecs enforce declared bounds and carry embedded XTypes metadata.
``@java_mapping`` selects JavaBeans naming, unsigned-width promotion, string
classes and constants containers. Recovery annotations, transient fields and
runtime annotation groups/packages are described in :doc:`howtos/idl`.
The build uses the unmodified pinned core; see the source README for reproducible
build commands. No core patches are needed.
See :doc:`standards` for supported mappings and rejected declarations.

See :doc:`howtos/idl` for optional/external fields, inheritance, recursive values,
mutable schemas, wide text, annotation behavior and remaining restrictions.

Generated source header
-----------------------

Every generated Java source (including annotation groups, constants containers and
package-info files) carries the Cyclone translator banner. It identifies the IDL
input, the Java output path and the core IDL compiler version, using the same layout
as the C++ wrapper and naming the translator ``IDL to Java``. No generation timestamp
is added.
