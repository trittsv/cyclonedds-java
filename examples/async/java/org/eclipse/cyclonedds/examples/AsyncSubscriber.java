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


import vehicles.Vehicle;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;

/** Java counterpart to Python's asyncio receiver: worker + Future + heartbeat. */
public final class AsyncSubscriber {
    private AsyncSubscriber() {}
    public static void main(String[] args) throws Exception {
        int count = ExampleSupport.number(args, 0, 20, 1, 100000);
        int domain = ExampleSupport.number(args, 1, 0, 0, 230);
        var env = ExampleSupport.environment();
        var policy = PolicyFactory.getPolicyFactory(env);
        var executor = Executors.newSingleThreadExecutor();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("Vehicle", Vehicle.class);
             var subscriber = participant.createSubscriber();
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(
                 policy.Reliability().withReliable(), policy.Durability().withTransientLocal(), policy.History().withKeepLast(10)))) {
            try {
                var result = executor.submit(() -> {
                    try (var incoming = new ExampleSupport.ReadWait<>(reader)) {
                        int received = 0;
                        long deadline = ExampleSupport.deadline();
                        while (received < count && !Thread.currentThread().isInterrupted()) {
                            try {
                                for (var sample : incoming.take(Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(1)))) {
                                    var vehicle = sample.getData();
                                    if (vehicle != null) {
                                        System.out.printf("Async vehicle: %s (%d, %d)%n", vehicle.get_name(), vehicle.get_x(), vehicle.get_y());
                                        received++;
                                    }
                                }
                            } catch (TimeoutException timeout) {
                                if (System.nanoTime() >= deadline) throw timeout;
                            }
                        }
                        return received;
                    }
                });
                for (;;) {
                    try {
                        int received = result.get(250, TimeUnit.MILLISECONDS);
                        if (received != count) throw new IllegalStateException("Reception cancelled");
                        System.out.println("Async: " + received + " samples");
                        break;
                    } catch (TimeoutException waiting) { System.out.println("Alive! Main thread remains available."); }
                }
            } finally {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Receiver did not stop");
            }
        } finally { executor.shutdownNow(); }
    }
}
