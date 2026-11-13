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
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.InstanceState;

/** A keyed instance transitions through ALIVE, DISPOSED and NO_WRITERS. */
public final class InstanceLifecycle {
    private InstanceLifecycle() {}
    public static void main(String[] args) throws Exception {
        int domain = ExampleSupport.number(args, 0, 0, 0, 230);
        var env = ExampleSupport.environment();
        var policy = PolicyFactory.getPolicyFactory(env);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("JavaLifecycle", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var reader = subscriber.createDataReader(topic);
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(
                 policy.WriterDataLifecycle().withAutDisposeUnregisteredInstances(false)));
             var incoming = new ExampleSupport.ReadWait<>(reader)) {
            ExampleSupport.waitForReader(writer);
            var message = new Message(7, "one keyed instance");
            var handle = writer.registerInstance(message);
            writer.write(message);
            show(incoming, InstanceState.ALIVE);
            writer.dispose(handle);
            show(incoming, InstanceState.NOT_ALIVE_DISPOSED);
            writer.write(message); // Revive the disposed instance.
            show(incoming, InstanceState.ALIVE);
            writer.unregisterInstance(handle);
            show(incoming, InstanceState.NOT_ALIVE_NO_WRITERS);
        }
    }
    private static void show(ExampleSupport.ReadWait<Message> incoming, InstanceState expected) throws Exception {
        boolean found = false;
        for (var sample : incoming.take(ExampleSupport.deadline())) {
            System.out.printf("%s handle=%s data=%s%n", sample.getInstanceState(), sample.getInstanceHandle(),
                sample.getData() == null ? "<invalid lifecycle notification>" : sample.getData().get_text());
            found |= sample.getInstanceState() == expected;
        }
        if (!found) throw new IllegalStateException("Missing state " + expected);
    }
}
