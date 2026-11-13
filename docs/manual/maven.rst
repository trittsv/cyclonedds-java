..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Maven: runtime and IDL compiler
==============================

The distribution has two JAR artifacts, both version ``11.0.1`` in Maven group
``org.eclipse.cyclonedds``:

* ``org.eclipse.cyclonedds``: Java API, provider, DDS core and JNI runtime libraries.
* ``org.eclipse.cyclonedds.idlc``: IDL compiler, Java backend, compiler platform
  libraries, CLI launcher and Maven source-generation goal.

The runtime contains no compiler or Maven integration. The compiler has no
runtime-Java dependency and is not put on an application's runtime classpath.
Its Maven integration is packaged inside the compiler JAR, not in a third JAR.
The two packages independently select the appropriate included OS/CPU payload.

Applications need JDK 17+ and Maven 3.9+, without CMake, a C compiler, a system
Cyclone DDS installation or a Git checkout. The artifacts are implemented and
tested locally but are not yet published to Maven Central. Until publication,
install them using the maintainer workflow below or use a Maven repository
containing them.

Application setup
-----------------

Put schemas in ``src/main/idl/Message.idl``:

.. code-block:: idl

   module Example {
     @final struct Message { @key long id; string text; };
   };

Declare the runtime as a dependency and the compiler as a build tool:

.. code-block:: xml

   <properties>
     <maven.compiler.release>17</maven.compiler.release>
     <cyclonedds.version>11.0.1</cyclonedds.version>
   </properties>
   <dependencies>
     <dependency>
       <groupId>org.eclipse.cyclonedds</groupId>
       <artifactId>org.eclipse.cyclonedds</artifactId>
       <version>${cyclonedds.version}</version>
     </dependency>
   </dependencies>
   <build>
     <plugins>
       <plugin>
         <groupId>org.eclipse.cyclonedds</groupId>
         <artifactId>org.eclipse.cyclonedds.idlc</artifactId>
         <version>${cyclonedds.version}</version>
         <executions>
           <execution><goals><goal>generate</goal></goals></execution>
         </executions>
       </plugin>
       <plugin>
         <groupId>org.apache.maven.plugins</groupId>
         <artifactId>maven-compiler-plugin</artifactId>
         <version>3.13.0</version>
       </plugin>
     </plugins>
   </build>

Maven uses its ``<plugin>`` mechanism to run the compiler JAR during
``generate-sources``. ``mvn package`` finds ``src/main/idl/*.idl`` recursively,
executes the packaged host compiler, writes ``target/generated-sources/cyclonedds``
and registers that directory for Java compilation. Application code imports
``Example.Message`` normally. No classifiers, OS properties or platform profiles
are needed in the POM. The complete example is in ``tests/maven-consumer``.

What is in each file
--------------------

``org.eclipse.cyclonedds-11.0.1.jar`` contains only the Java/runtime components
needed by applications. At runtime include this JAR and the application classes;
Maven and the compiler are not needed.

``org.eclipse.cyclonedds.idlc-11.0.1.jar`` contains the CLI, compilers and their
native dependencies. Its Maven APIs are declared ``provided``; they are supplied
by Maven and are not bundled. The CLI can run without Maven libraries:

.. code-block:: sh

   java -jar org.eclipse.cyclonedds.idlc-11.0.1.jar -o generated Message.idl

The launcher chooses the host's Java backend and forwards ``idlc`` options.
``--print-platform`` reports the detected host without extracting tools.

Platform selection and cross-platform builds
--------------------------------------------

The compiler uses ``META-INF/cyclonedds-idlc/<platform>/`` resources for its build
host. The runtime independently loads ``org/eclipse/cyclonedds/native/<platform>/``
for the running application. Each payload has a SHA-256 manifest.

A Linux build can generate Java classes for an application deployed on macOS:
it uses Linux ``idlc``; the deployed application uses macOS DDS/JNI. Packaging
native binaries does not cross-compile new C libraries. The maintainer must
build and test each native variant first.

The release matrix requires Linux on x86-64 and ARM64, macOS on ARM64, and
Windows on x86-64 for both JARs. macOS Intel is not a release target.
A missing host compiler/runtime is reported explicitly.
A local CMake build includes only its host by default; its platform indexes
list only actual supplied binaries. Release aggregation fails when any required
platform is missing.

Incremental builds and configuration
------------------------------------

After the first successful build, ``mvn -o package`` works offline. The compiler
runs as a child process; its temporary extraction directory is removed afterwards.
Source/include contents, preprocessor options and compiler JAR contents determine
whether generation is needed. Changed includes, edited/missing outputs and removed
schemas cause regeneration. A failed compiler fails Maven and retains the last
successful generated sources. Runtime-only changes do not change the compiler
package or its input fingerprint.

.. code-block:: xml

   <configuration>
     <sourceDirectory>${project.basedir}/schemas</sourceDirectory>
     <outputDirectory>${project.build.directory}/generated-sources/dds</outputDirectory>
     <includeDirectories>
       <includeDirectory>${project.basedir}/shared-idl</includeDirectory>
     </includeDirectories>
     <defines><define>FEATURE=1</define></defines>
   </configuration>

The compiler receives include directories with ``-I`` and definitions with
``-D``. Input/output trees must be separate. A nonempty output directory without
the generator's ownership marker is rejected.

``-Dcyclonedds.idlc.skip=true`` registers existing output without generation.
``-Dcyclonedds.idlc.compilerJar=/path/to/idlc.jar`` supplies a local compiler JAR.
``-Dcyclonedds.idlc.version=...`` selects another compiler package version;
normally runtime and compiler use the same Cyclone DDS version.

Maintainer packaging
--------------------

Compiler sources and the compiler Maven module live in ``src/idlc``. The
repository root contains the runtime Maven module; ``maven/pom.xml`` builds both.

A CMake build creates two distribution JARs:

* ``org.eclipse.cyclonedds-11.0.1.jar``: runtime.
* ``org.eclipse.cyclonedds.idlc-11.0.1.jar``: compiler toolchain.

The per-platform files with ``-<platform>`` suffixes are intermediate assembly
inputs. ``UNIVERSAL_BUNDLE_INPUTS`` accepts additional intermediate JARs;
``UNIVERSAL_REQUIRED_PLATFORMS`` lists mandatory platforms and defaults to the
local host. The assembler emits each kind separately:

.. code-block:: sh

   java -cp org.eclipse.cyclonedds.idlc-11.0.1-linux-x86_64.jar \
     org.eclipse.cyclonedds.idlc.UniversalJar --kind runtime \
     --output org.eclipse.cyclonedds-11.0.1.jar --version 11.0.1 \
     --platforms linux-x86_64,linux-aarch64,macos-aarch64,windows-x86_64 \
     --input-dir platform-builds

Use ``--kind compiler`` and output ``org.eclipse.cyclonedds.idlc-11.0.1.jar`` for
the compiler package. Inputs are checked for versions, checksums, conflicts and
required platforms. Compiler classes/resources in a runtime package, and runtime
classes/resources in a compiler package, are rejected. ZIP order/timestamps are
normalized; reversed input order produces identical output bytes.

With Maven available at CMake configuration time:

.. code-block:: sh

   cmake --build build --target maven-install

The equivalent explicit invocation is:

.. code-block:: sh

   mvn -f maven/pom.xml \
     -Dcyclonedds.runtime.jar="$PWD/build/org.eclipse.cyclonedds-11.0.1.jar" \
     -Dcyclonedds.idlc.jar="$PWD/build/org.eclipse.cyclonedds.idlc-11.0.1.jar" install

Maven packages the runtime as the main runtime artifact and adds compiler
platform resources to the ``org.eclipse.cyclonedds.idlc`` JAR alongside its Maven
goal/descriptor. This same final compiler JAR is usable through Maven or
``java -jar``. Without those properties the reactor builds Java-only development
artifacts. ``mvn package`` at repository root builds only the Java runtime.
No command publishes remotely.

``.github/workflows/universal.yml`` builds four native variants, assembles the two
JARs, adds the compiler's Maven integration and tests the identical pair on every
host. It prepares JARs and POMs as workflow artifacts; remote results must be
checked separately before release.

Validation
----------

``-DBUILD_MAVEN_TESTING=ON`` enables the independent Maven consumer test. It checks
a clean build, compiler repository resolution, offline rebuilding, includes,
defines, error handling and stale-source removal. It also checks that the runtime
JAR contains no compiler/Maven classes, the compiler has no runtime-Java classes,
and the Maven-packaged compiler is executable without Maven on its classpath.
``idlc-bundle`` tests native compiler execution and runtime loading separately.
``universal-assembly`` checks archive separation, completeness, checksums and
host selection. Synthetic foreign fixtures are never executed or distributed.
Local execution is verified on macOS ARM64; CI supplies other host checks.
