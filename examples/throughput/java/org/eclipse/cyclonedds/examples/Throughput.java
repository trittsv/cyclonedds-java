/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

package org.eclipse.cyclonedds.examples;


import ThroughputModule.DataType;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;

/** Finite reliable stream with sequence checks and application payload throughput. */
public final class Throughput {
    private Throughput() {}
    public static void main(String[] args) throws Exception {
        boolean publish = ExampleSupport.role(args, "pub", "sub", "Throughput pub|sub [samples=10000] [bytes=1024] [domain=0]");
        int count = ExampleSupport.number(args, 1, 10000, 1, 1_000_000);
        int size = ExampleSupport.number(args, 2, 1024, 1, 1_048_576);
        int domain = ExampleSupport.number(args, 3, 0, 0, 230);
        var env = ExampleSupport.environment();
        var policy = PolicyFactory.getPolicyFactory(env);
        var reliable = policy.Reliability().withReliable();
        var history = policy.History().withKeepAll();
        var limits = policy.ResourceLimits().withMaxSamples(16384);
        var partition = policy.Partition().withName(java.util.List.of("Throughput example"));
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("Throughput", DataType.class);
             var publisher = participant.createPublisher(participant.getDefaultPublisherQos().withPolicy(partition));
             var subscriber = participant.createSubscriber(participant.getDefaultSubscriberQos().withPolicy(partition))) {
            if (publish) {
                try (var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(reliable, history, limits))) {
                    ExampleSupport.waitForReader(writer);
                    var message = new DataType();
                    for (int i = 0; i < size; i++) message.get_payload().add((byte) (i % 251));
                    long start = System.nanoTime();
                    for (int i = 0; i < count; i++) { message.set_count(i); writer.write(message); }
                    writer.waitForAcknowledgments(10, TimeUnit.SECONDS);
                    print("Sent", count, size, start);
                }
            } else {
                try (var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(reliable, history, limits));
                     var incoming = new ExampleSupport.ReadWait<>(reader)) {
                    int received = 0;
                    long start = 0;
                    long deadline = ExampleSupport.deadline();
                    while (received < count) {
                        for (var sample : incoming.take(deadline)) {
                            var data = sample.getData();
                            if (data == null) continue;
                            if (start == 0) start = System.nanoTime();
                            if (data.get_count() != received || data.get_payload().size() != size)
                                throw new IllegalStateException("Unexpected sequence number or payload size at " + received);
                            received++;
                        }
                    }
                    print("Received", received, size, start);
                }
            }
        }
    }
    private static void print(String role, int count, int size, long start) {
        double seconds = Math.max(1, System.nanoTime() - start) / 1e9;
        System.out.printf("%s %d samples, %d bytes/sample: %.0f samples/s, %.2f payload Mbit/s%n",
            role, count, size, count / seconds, count * (double) size * 8 / seconds / 1e6);
    }
}
