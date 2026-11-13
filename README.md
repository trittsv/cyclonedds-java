<!--
Copyright(c) 2026 Sven Trittler

This program and the accompanying materials are made available under the
terms of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
-->

# Cyclone DDS Java

Java binding for Cyclone DDS using the **official OMG DDS-Java API** and generated
IDL4-Java types. Two clearly separated artifacts are provided:

* **`org.eclipse.cyclonedds`**: Java API, provider, DDS core and JNI libraries for runtime use.
* **`org.eclipse.cyclonedds.idlc`**: IDL compilers, platform libraries, command-line launcher and Maven build integration.

Both use version **11.0.1** and select their platform automatically. The compiler
package is build-time only and is not an application runtime dependency.

**Status:** experimental, with a tested API subset. Full OMG profile conformance
and production maturity are not established. See [coverage](docs/manual/standards.rst)
and the [RTI/OpenSplice comparison](docs/manual/comparison.rst).

## 1. Generate a type

Save this as `Message.idl`:

```idl
module Example {
  @final struct Message { @key long id; string text; };
};
```

Put the file in `src/main/idl/Message.idl` and add the runtime dependency and
IDL generator to your Maven project's `pom.xml`:

```xml
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
```

`mvn package` now generates Java under `target/generated-sources/cyclonedds`
before compiling the application. The `org.eclipse.cyclonedds.idlc` build tool
uses its own bundled compiler. No CMake, C compiler, GitHub checkout or manually installed `idlc` is needed in an application project.
Use JDK 17+ and Maven 3.9+.

**Publication status:** these artifacts are implemented and tested locally but
are not yet published to Maven Central. Until publication, use a Maven repository
containing the artifacts or the [maintainer installation workflow](docs/manual/maven.rst).

No platform classifier or OS profiles are needed in the POM. The universal
release build requires Linux on x86-64 and ARM64, macOS on ARM64, and Windows on
x86-64. Its build matrix
combines the native outputs and tests the identical runtime/compiler pair on each host. A local
CMake build includes the local host by default; building that JAR alone does not
produce binaries for other systems. See
[assembly and cross-platform builds](docs/manual/maven.rst).

Generated classes have a default constructor, an all-values constructor, and
`get_id()` / `set_id(...)` accessors. No application-specific C library is needed.
See [Maven options, includes and offline builds](docs/manual/maven.rst), or the
[existing CMake/direct compiler integration](docs/manual/idljava.rst).
Optional/external fields, inheritance, recursive types, mutable XCDR1/XCDR2, wide
text and arrays of sequences are supported; see [richer schemas](docs/manual/howtos/idl.rst).

## 2. Use the type

Save as `src/main/java/Demo.java`. It publishes and receives one sample through the standard API:

```java
import Example.Message;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.core.WaitSet;
import org.omg.dds.core.status.PublicationMatchedStatus;
import org.omg.dds.domain.DomainParticipantFactory;

public final class Demo {
    public static void main(String[] args) throws Exception {
        System.setProperty("org.omg.dds.serviceClassName",
            "org.eclipse.cyclonedds.omg.CycloneServiceEnvironment");
        var env = ServiceEnvironment.createInstance(Demo.class.getClassLoader());
        var waitset = WaitSet.newWaitSet(env);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(0);
             var topic = participant.createTopic("ExampleMessage", Message.class);
             var publisher = participant.createPublisher();
             var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic);
             var reader = subscriber.createDataReader(topic);
             var ready = reader.select().getCondition()) {
            var matched = writer.getStatusCondition();
            matched.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
            waitset.attachCondition(matched);
            if (writer.getPublicationMatchedStatus().getCurrentCount() == 0)
                waitset.waitForConditions(5, TimeUnit.SECONDS);
            waitset.detachCondition(matched);
            waitset.attachCondition(ready);
            writer.write(new Message(42, "Hello from Java!"));
            waitset.waitForConditions(5, TimeUnit.SECONDS);
            for (var sample : reader.select().take(null)) {
                if (sample.getData() != null)
                    System.out.println(sample.getData().get_text());
            }
        } finally {
            if (waitset instanceof AutoCloseable resource) resource.close();
        }
    }
}
```

```sh
mvn package
mvn org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=Demo
```

Output: `Hello from Java!`. The runtime JAR loads its matching bundled native
libraries automatically; application code needs no native search-path setup.

For separate publisher/subscriber processes, see
[HelloWorld](examples/helloworld/java/org/eclipse/cyclonedds/examples/HelloWorld.java).
The [manual](docs/manual/index.rst) covers QoS, instance selectors, conditions,
type mappings and installation.

Readers and writers also support listener callbacks, standard status getters,
query conditions and content filters, next-instance traversal, key lookup and
timestamped registration overloads. See the short [operation examples and
restrictions](docs/manual/howtos/operations.rst), including callback threading and
the supported SQL subset.

## Build the binding

Requirements: JDK 17+, CMake 3.21+, a C11 compiler and a Cyclone DDS installation
with `idlc`, IDL/DDSI development headers and type-library support. The tested core
revision is the unmodified `3a5526315e64ac1d2a52e6ac1ae7f3e861c593d3` (11.0.1).
No Cyclone DDS core patches are required. Build that core first:

```sh
git clone https://github.com/eclipse-cyclonedds/cyclonedds.git core
git -C core checkout 3a5526315e64ac1d2a52e6ac1ae7f3e861c593d3
cmake -S core -B core-build -DCMAKE_INSTALL_PREFIX="$PWD/core-install" \
  -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF -DENABLE_SECURITY=OFF -DENABLE_SSL=OFF
cmake --build core-build --parallel
cmake --install core-build
```

Then build and install the binding:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH="$PWD/core-install"
cmake --build build --parallel
ctest --test-dir build --output-on-failure
cmake --install build --prefix /path/to/java-binding
cmake --build build --target package
```

The build creates separate runtime and compiler JARs for the configured platforms,
per-platform assembly inputs, thin development JARs, JNI runtime and IDL backend. CPack produces development `.tar.gz` / `.zip` archives and SHA-256
checksums. Nothing is published automatically. The tests cover wire formats,
standard API behavior, three independent C interoperability paths, a Java-only
installed consumer, and bundled-native loading with native search paths disabled.
Local validation is macOS ARM64; Linux, macOS ARM64 and Windows x86-64 CI is configured.

The [upstream test ledger](tests/upstream/README.md) compares pinned fresh Python
and C++ downloads with runnable Java scenarios. It records remaining gaps;
complete upstream test parity is not yet established.

To build and install the Maven artifacts locally, make Maven available when
configuring CMake, then run:

```sh
cmake --build build --target maven-install
```

This installs `org.eclipse.cyclonedds` (runtime) and `org.eclipse.cyclonedds.idlc`
(compiler plus Maven integration) into the local Maven repository. It does not
publish to a remote repository. See [Maven packaging and validation](docs/manual/maven.rst)
and [distribution details](docs/manual/distribution.rst).

## License

EPL 2.0 or EDL 1.0, using the exact [LICENSE](LICENSE) from the Cyclone DDS Python
binding. Bundled third-party components retain their notices; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

XCDR2 is the default. To use XCDR1, set the writer's representation policy to
`policyFactory.Representation().withValue((short) 0)`; readers can accept both
with `withValue((short) 0, (short) 2)`. Optional and mutable fields and top-level appendable evolution are supported
within the unmodified core's representation limits. Generated type support records
those limits and rejects unsupported XCDR1 endpoint configurations. Regenerate
existing types when upgrading.
See [IDL and wire formats](docs/manual/howtos/idl.rst) for nested appendable
layout restrictions.

Discovery metadata is available through `participant.getBuiltinSubscriber()`,
`reader.getMatchedPublicationData(handle)` and
`writer.getMatchedSubscriptionData(handle)`. The
[operations guide](docs/manual/howtos/operations.rst) includes a built-in reader
example and documents the available metadata.

## Runnable examples

See [the example catalog](examples/README.md) for HelloWorld, roundtrip, throughput,
vehicles, asynchronous reception, all four built-in topics, DDS listeners,
waitsets and instance lifecycle. Build with `-DBUILD_EXAMPLES=ON` and start with:

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.SimpleHelloWorld
java @build/examples/java.args org.eclipse.cyclonedds.examples.Listeners
```
