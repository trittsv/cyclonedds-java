# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

"""Run the documented examples as separate JVMs using the bundled runtime."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--scenario", required=True)
    args = parser.parse_args()
    build = args.build.resolve()
    command = [args.java, "-ea", "-Xcheck:jni",
               "-Djava.library.path=" + str(build / "no-system-natives"),
               "@" + str(build / "examples/java.args")]
    environment = os.environ.copy()
    environment["CYCLONEDDS_URI"] = (
        '<CycloneDDS><Domain><General><Interfaces>'
        '<NetworkInterface address="127.0.0.1"/></Interfaces><AllowMulticast>false</AllowMulticast>'
        '</General><Discovery><ParticipantIndex>auto</ParticipantIndex>'
        '<EnableTopicDiscoveryEndpoints>true</EnableTopicDiscoveryEndpoints>'
        '<Peers><Peer Address="127.0.0.1"/></Peers></Discovery></Domain></CycloneDDS>')
    processes = []
    with tempfile.TemporaryDirectory(prefix="org.eclipse.cyclonedds-examples-") as directory:
        def start(name, *arguments):
            path = Path(directory) / (str(len(processes)) + ".log")
            with path.open("w") as output:
                process = subprocess.Popen(command + ["org.eclipse.cyclonedds.examples." + name, *map(str, arguments)],
                                           env=environment, stdout=output, stderr=subprocess.STDOUT)
            processes.append((process, path))
            return process, path

        def expect(pair, *messages):
            process, path = pair
            process.wait(timeout=38)
            output = path.read_text()
            print(output, end="")
            if process.returncode != 0:
                raise AssertionError(f"Example exited with {process.returncode}")
            for message in messages:
                if message not in output:
                    raise AssertionError(f"Missing output: {message}")

        domain = 180
        try:
            scenario = args.scenario
            if scenario == "hello":
                sub = start("HelloWorld", "sub", domain)
                pub = start("HelloWorld", "pub", domain)
                expect(pub, "Sent 9:")
                expect(sub, "Received 9:")
            elif scenario == "simple":
                expect(start("SimpleHelloWorld", domain), "Hello, World!")
            elif scenario == "roundtrip":
                pong = start("RoundTrip", "pong", 20, 128, domain)
                ping = start("RoundTrip", "ping", 20, 128, domain)
                expect(ping, "RoundTrip: 20 echoes, 128 bytes")
                expect(pong, "Pong: 20 echoes")
            elif scenario == "throughput":
                sub = start("Throughput", "sub", 300, 256, domain)
                pub = start("Throughput", "pub", 300, 256, domain)
                expect(pub, "Sent 300 samples")
                expect(sub, "Received 300 samples", "payload Mbit/s")
            elif scenario in ("vehicle", "async", "builtin"):
                monitor = None
                if scenario == "builtin":
                    monitor = start("BuiltinTopics", 4, domain)
                    deadline = time.monotonic() + 10
                    while "Observing discovery" not in monitor[1].read_text():
                        if monitor[0].poll() is not None or time.monotonic() > deadline:
                            raise AssertionError("Discovery monitor did not start: " + monitor[1].read_text())
                        time.sleep(0.02)
                sub = start("AsyncSubscriber", 8, domain) if scenario == "async" else start("VehicleExample", "sub", 8, domain)
                pub = start("VehicleExample", "pub", 8, domain)
                expect(pub, "Vehicle sent:")
                expect(sub, "Async: 8 samples", "Alive!") if scenario == "async" else expect(sub, "Vehicle: 8 samples")
                if monitor:
                    expect(monitor, "DCPSParticipant ALIVE", "DCPSTopic ALIVE", "DCPSPublication ALIVE",
                           "DCPSSubscription ALIVE", "topic=Vehicle", "lifecycle notification")
            elif scenario == "listeners":
                expect(start("Listeners", domain), "onDataAvailable:", "onPublicationMatched:",
                       "onSubscriptionMatched:", "onRequestedDeadlineMissed:", "onOfferedDeadlineMissed:",
                       "onLivelinessChanged:", "onLivelinessLost:", "Listeners: data, deadlines and liveliness observed")
            elif scenario == "waitset":
                expect(start("WaitSetExample", domain), "WaitSet wakes on data", "GuardCondition: shutdown requested")
            elif scenario == "lifecycle":
                expect(start("InstanceLifecycle", domain), "ALIVE", "NOT_ALIVE_DISPOSED", "NOT_ALIVE_NO_WRITERS", "<invalid lifecycle notification>")
            else:
                raise ValueError(scenario)
        finally:
            for process, path in processes:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
                    print(path.read_text(), end="")


if __name__ == "__main__":
    main()
