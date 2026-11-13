<!--
Copyright(c) 2026 Sven Trittler

This program and the accompanying materials are made available under the
terms of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
-->

# Java DDS examples

These runnable Java examples cover the scenarios in the upstream
[C++ examples](https://github.com/eclipse-cyclonedds/cyclonedds-cxx/tree/master/examples)
and [Python examples](https://github.com/eclipse-cyclonedds/cyclonedds-python/tree/master/examples),
plus DDS listeners, waitsets and keyed-instance lifecycle. The Java implementations
use the standard OMG DDS API and types generated from IDL. They are new Java
implementations, with finite runs and bounded waits for easy experimentation.

The source references inspected were C++ revision
`33d27ffff4d76021d88653f778cc001aa1a2bb7a` and Python revision
`68b2acfd5ac56bd0f095e51914a9f6c494675327`.

| Upstream scenario | Java example | What it demonstrates |
| --- | --- | --- |
| C++ HelloWorld | [HelloWorld](helloworld/java/org/eclipse/cyclonedds/examples/HelloWorld.java) | Separate publisher/subscriber, reliable delivery, matching and read conditions |
| Python HelloWorld | [SimpleHelloWorld](helloworld/java/org/eclipse/cyclonedds/examples/SimpleHelloWorld.java) | Publish and receive in one process |
| C++ roundtrip | [RoundTrip](roundtrip/java/org/eclipse/cyclonedds/examples/RoundTrip.java) | Ping/pong, opposite partitions, payload validation and RTT percentiles |
| C++ throughput | [Throughput](throughput/java/org/eclipse/cyclonedds/examples/Throughput.java) | Reliable streaming, KeepAll history, sequence checks, samples/s and payload Mbit/s |
| Python vehicle | [VehicleExample](vehicle/java/org/eclipse/cyclonedds/examples/VehicleExample.java) | Generated vehicle type, moving coordinates, transient-local history |
| Python async | [AsyncSubscriber](async/java/org/eclipse/cyclonedds/examples/AsyncSubscriber.java) | Background reception, Future, main-thread heartbeat and cancellation |
| Python builtin_topics | [BuiltinTopics](builtin_topics/java/org/eclipse/cyclonedds/examples/BuiltinTopics.java) | All four discovery readers, metadata, QoS and deletion notifications |
| Additional | [Listeners](listeners/java/org/eclipse/cyclonedds/examples/Listeners.java) | Typed reader/writer adapters, event masks, data, matching, deadline and liveliness callbacks |
| Additional | [WaitSetExample](waitset/java/org/eclipse/cyclonedds/examples/WaitSetExample.java) | Blocking read conditions and an application shutdown guard |
| Additional | [InstanceLifecycle](lifecycle/java/org/eclipse/cyclonedds/examples/InstanceLifecycle.java) | Register, write, dispose, revive and unregister a keyed instance |

## Build and run

From the repository root, with JDK 17+, CMake and an installed Cyclone DDS:

```sh
cmake -S . -B build -DCMAKE_PREFIX_PATH=/path/to/cyclonedds-install -DBUILD_EXAMPLES=ON
cmake --build build --parallel
java @build/examples/java.args org.eclipse.cyclonedds.examples.SimpleHelloWorld
```

CMake generates `build/examples/java.args` with the provider setting and the
complete classpath for the bundled native runtime, example JARs and generated
types. Java reads this argument file directly. Choose the main class followed by
its arguments; no launcher script is needed. The same commands work on
macOS, Linux and Windows with JDK 17+.

For another build directory use its argument file, for example
`java @other-build/examples/java.args org.eclipse.cyclonedds.examples.Listeners`.
The argument file contains paths for the configured build location; regenerate it
with CMake after moving the build. `CYCLONEDDS_URI` configures DDS normally.
No `java.library.path` is needed.

## HelloWorld

Run in two terminals, subscriber first:

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.HelloWorld sub 0
java @build/examples/java.args org.eclipse.cyclonedds.examples.HelloWorld pub 0
```

The publisher sends ten messages. `SimpleHelloWorld [domain=0]` demonstrates the
single-process variant.

## Roundtrip

Run in two terminals with identical sample counts and payload sizes:

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.RoundTrip pong 100 1024 0
java @build/examples/java.args org.eclipse.cyclonedds.examples.RoundTrip ping 100 1024 0
```

Arguments: `ping|pong [samples=100] [bytes=1024] [domain=0]`. Pong echoes each
request. Ping validates each returned payload and reports full round-trip
min/median/p99 microseconds. Topic `RoundTrip`, type `RoundTripModule::DataType`
and partitions `ping`/`pong` follow the C++ example. Unlike the upstream CLI,
both Java roles exit after the chosen number of samples; there is no quit mode.
Use one ping and one pong per domain/partition pair.

## Throughput

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.Throughput sub 10000 1024 0
java @build/examples/java.args org.eclipse.cyclonedds.examples.Throughput pub 10000 1024 0
```

Arguments: `pub|sub [samples=10000] [bytes=1024] [domain=0]`. The subscriber verifies
sequence numbers and payload sizes. Topic `Throughput`, type
`ThroughputModule::DataType` and partition `Throughput example` follow the C++
example. The Java CLI uses a finite count rather than the upstream burst options;
it does not enable the C++ provider's batching extension.

Reported rates include Java serialization and application overhead. Subscriber
time starts on the first received batch; publisher time includes writing and
acknowledgment. Payload Mbit/s excludes DDS/network headers. These short examples
are educational measurements, not a calibrated benchmark. Use one publisher and
one subscriber; KeepAll is bounded to 16,384 cached samples and a slow receiver
can apply backpressure. Writes and receives have finite timeouts.

## Vehicles and asynchronous reception

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.VehicleExample sub 20 0
java @build/examples/java.args org.eclipse.cyclonedds.examples.VehicleExample pub 20 0
```

Arguments: `pub|sub [samples=20] [domain=0]`. Vehicle fields and topic `Vehicle`
follow Python's example. The writer moves coordinates every 100 ms. Reliable,
transient-local KeepLast(10) QoS illustrates retained history while the writer is
alive. Start the subscriber first to receive the full requested count; late
joiners can receive only the retained ten samples plus subsequent updates.
History disappears when the writer closes.

Replace the subscriber with the Java asynchronous variant:

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.AsyncSubscriber 20 0
java @build/examples/java.args org.eclipse.cyclonedds.examples.VehicleExample pub 20 0
```

Arguments: `[samples=20] [domain=0]`. A worker blocks on a DDS read condition while
the main thread prints a heartbeat. Future failures propagate to main. Shutdown
interrupts the worker, waits for it to release its waitset and then closes DDS
entities. Java executors/Futures take the place of Python's asyncio iterator.

## Built-in topics

Start the monitor, then run a Vehicle publisher/subscriber on the same domain:

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.BuiltinTopics 15 0
```

Arguments: `[seconds=10] [domain=0]`. The monitor observes `DCPSParticipant`,
`DCPSTopic`, `DCPSPublication` and `DCPSSubscription` through the built-in
subscriber. It prints participant keys, topic/type names, endpoint reliability,
instance state and handles. Disappearance events can have null data; the example
prints these as lifecycle notifications rather than dereferencing them.
A quiet domain can legitimately produce no remote events.

For `DCPSTopic`, Cyclone DDS must be built with topic discovery and have topic
discovery endpoints enabled. Set this **for all participating processes**:

```sh
export CYCLONEDDS_URI='<CycloneDDS><Domain><Discovery><EnableTopicDiscoveryEndpoints>true</EnableTopicDiscoveryEndpoints></Discovery></Domain></CycloneDDS>'
```

On PowerShell set `$env:CYCLONEDDS_URI` to the same XML. Merge it into an existing
configuration if needed. The other three discovery topics do not require this
option. Discovery sees remote participants/endpoints; do not rely on a
participant discovering its own application entities.

## DDS listeners

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.Listeners 0
```

Arguments: `[domain=0]`. The example runs a reader and writer together. It extends
`DataReaderAdapter<Message>` and `DataWriterAdapter<Message>` and installs explicit
status masks. It prints `onDataAvailable`, publication/subscription matches,
requested/offered deadline misses, liveliness changes and liveliness loss.

After one message the application deliberately stops writing and asserting the
manual-by-topic liveliness lease. The 200 ms deadline and one-second lease make
the status callbacks visible. Listener objects remain reachable until callbacks
are removed. Keep callbacks short, close sample iterators, hand expensive work
to an application queue, and propagate callback errors to the owning thread.

## Waitsets and instance lifecycle

```sh
java @build/examples/java.args org.eclipse.cyclonedds.examples.WaitSetExample 0
java @build/examples/java.args org.eclipse.cyclonedds.examples.InstanceLifecycle 0
```

Both accept `[domain=0]` and run in one process. The waitset example handles a read
condition and a guard used for shutdown. The lifecycle example disables automatic
dispose on unregister, then shows `ALIVE`, `NOT_ALIVE_DISPOSED`, revival to `ALIVE`
and `NOT_ALIVE_NO_WRITERS`. Invalid lifecycle samples have no data; inspect their
instance handle and state.

All examples close DDS entities deterministically. The OMG API has no close method
on WaitSet/GuardCondition; `ExampleSupport.close` uses this provider's
`AutoCloseable` extension for their native resources.

## Verification

```sh
ctest --test-dir build -R '^example-' --output-on-failure
```

The ten scenarios launch the real example entry points with the bundled runtime.
Pair tests use separate JVMs; discovery checks all four built-in topics and remote
deletion. Tests use loopback UDP, topic discovery and a dedicated domain, and
require local socket access. Peer discovery and sample waits are bounded.
