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


import RoundTripModule.DataType;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;

/** Ping/pong using the C++ example's topic, type and opposite partitions. */
public final class RoundTrip {
    private RoundTrip() {}
    public static void main(String[] args) throws Exception {
        boolean ping = ExampleSupport.role(args, "ping", "pong", "RoundTrip ping|pong [samples=100] [bytes=1024] [domain=0]");
        int count = ExampleSupport.number(args, 1, 100, 1, 1_000_000);
        int size = ExampleSupport.number(args, 2, 1024, 1, 1_048_576);
        int domain = ExampleSupport.number(args, 3, 0, 0, 230);
        var env = ExampleSupport.environment();
        var policy = PolicyFactory.getPolicyFactory(env);
        var reliable = policy.Reliability().withReliable();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("RoundTrip", DataType.class);
             var publisher = participant.createPublisher(participant.getDefaultPublisherQos().withPolicy(policy.Partition().withName(java.util.List.of(ping ? "ping" : "pong"))));
             var subscriber = participant.createSubscriber(participant.getDefaultSubscriberQos().withPolicy(policy.Partition().withName(java.util.List.of(ping ? "pong" : "ping"))));
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(reliable));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicy(reliable));
             var incoming = new ExampleSupport.ReadWait<>(reader)) {
            ExampleSupport.waitForReader(writer);
            var message = new DataType();
            for (int i = 0; i < size; i++) message.get_payload().add((byte) (i % 251));
            long[] elapsed = new long[count];
            int received = 0;
            while (received < count) {
                long start = System.nanoTime();
                if (ping) writer.write(message);
                for (var sample : incoming.take(ExampleSupport.deadline())) {
                    var data = sample.getData();
                    if (data == null) continue;
                    if (ping) {
                        if (!data.get_payload().equals(message.get_payload())) throw new IllegalStateException("Echo payload differs");
                        elapsed[received] = System.nanoTime() - start;
                    } else writer.write(data);
                    received++;
                }
            }
            writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            if (ping) {
                Arrays.sort(elapsed);
                System.out.printf("RoundTrip: %d echoes, %d bytes; RTT min/median/p99: %.3f / %.3f / %.3f us%n",
                    count, size, elapsed[0] / 1000.0, elapsed[count / 2] / 1000.0,
                    elapsed[Math.min(count - 1, (int) (count * 0.99))] / 1000.0);
            } else System.out.println("Pong: " + received + " echoes");
        }
    }
}
