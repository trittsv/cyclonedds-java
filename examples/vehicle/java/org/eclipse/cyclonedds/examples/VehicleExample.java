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
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;

/** Generated vehicle data, reliable delivery and transient-local history. */
public final class VehicleExample {
    private VehicleExample() {}
    public static void main(String[] args) throws Exception {
        boolean publish = ExampleSupport.role(args, "pub", "sub", "VehicleExample pub|sub [samples=20] [domain=0]");
        int count = ExampleSupport.number(args, 1, 20, 1, 100000);
        int domain = ExampleSupport.number(args, 2, 0, 0, 230);
        var env = ExampleSupport.environment();
        var policy = PolicyFactory.getPolicyFactory(env);
        var reliable = policy.Reliability().withReliable();
        var durable = policy.Durability().withTransientLocal();
        var history = policy.History().withKeepLast(10);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("Vehicle", Vehicle.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber()) {
            if (publish) {
                try (var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(reliable, durable, history))) {
                    ExampleSupport.waitForReader(writer);
                    var random = new Random(42);
                    var vehicle = new Vehicle("Dallara IL-15", 200, 200);
                    for (int i = 0; i < count; i++) {
                        vehicle.set_x(vehicle.get_x() + random.nextInt(3) - 1);
                        vehicle.set_y(vehicle.get_y() + random.nextInt(3) - 1);
                        writer.write(vehicle);
                        System.out.printf("Vehicle sent: %s (%d, %d)%n", vehicle.get_name(), vehicle.get_x(), vehicle.get_y());
                        Thread.sleep(100);
                    }
                    writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                }
            } else {
                try (var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(reliable, durable, history));
                     var incoming = new ExampleSupport.ReadWait<>(reader)) {
                    int received = 0;
                    while (received < count) {
                        for (var sample : incoming.take(ExampleSupport.deadline())) {
                            var vehicle = sample.getData();
                            if (vehicle != null) {
                                System.out.printf("Vehicle received: %s (%d, %d)%n", vehicle.get_name(), vehicle.get_x(), vehicle.get_y());
                                received++;
                            }
                        }
                    }
                    System.out.println("Vehicle: " + received + " samples");
                }
            }
        }
    }
}
