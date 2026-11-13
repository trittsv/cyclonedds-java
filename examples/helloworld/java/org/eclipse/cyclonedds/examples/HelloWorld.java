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

import HelloWorld.Message;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.core.WaitSet;
import org.omg.dds.core.status.PublicationMatchedStatus;
import java.util.Set;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;

/** Application source compiled exclusively against the OMG API and generated types. */
public final class HelloWorld {
    private HelloWorld() {}
    private static void release(WaitSet waitset) throws Exception {
        // The OMG API has no close operation. The provider additionally permits
        // deterministic cleanup through AutoCloseable; it also supplies GC cleanup.
        if (waitset instanceof AutoCloseable resource) resource.close();
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !(args[0].equals("pub") || args[0].equals("sub"))) {
            System.err.println("Usage: HelloWorld pub|sub [domain]");
            System.exit(2);
        }
        var environment = ServiceEnvironment.createInstance(HelloWorld.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(environment);
        var reliable = PolicyFactory.getPolicyFactory(environment).Reliability().withReliable();
        try (var participant = factory.createParticipant(args.length > 1 ? Integer.parseInt(args[1]) : 0);
             var topic = participant.createTopic("HelloWorldJava", Message.class)) {
            long deadline = System.nanoTime() + 30_000_000_000L;
            if (args[0].equals("pub")) {
                try (var publisher = participant.createPublisher();
                     var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(reliable))) {
                    var waitset = WaitSet.newWaitSet(environment);
                    var matched = writer.getStatusCondition();
                    matched.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
                    waitset.attachCondition(matched);
                    try {
                        while (writer.getPublicationMatchedStatus().getCurrentCount() == 0)
                            waitset.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } finally {
                        waitset.detachCondition(matched);
                        release(waitset);
                    }
                    for (int i = 0; i < 10; i++) {
                        var message = new Message(i, "Hello from Java!");
                        writer.write(message);
                        System.out.println("Sent " + message.get_id() + ": " + message.get_text());
                        Thread.sleep(100);
                    }
                    writer.waitForAcknowledgments(2, TimeUnit.SECONDS);
                }
            } else {
                try (var subscriber = participant.createSubscriber();
                     var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicy(reliable))) {
                    var selection = reader.select().maxSamples(32);
                    var waitset = WaitSet.newWaitSet(environment);
                    try (var condition = selection.getCondition()) {
                        waitset.attachCondition(condition);
                        int received = 0;
                        while (received < 10) {
                            waitset.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                            try (var samples = selection.take()) {
                                while (samples.hasNext()) {
                                    var message = samples.next().getData();
                                    if (message != null) {
                                        System.out.println("Received " + message.get_id() + ": " + message.get_text());
                                        received++;
                                    }
                                }
                            }
                        }
                    } finally { release(waitset); }
                }
            }
        }
    }
}
