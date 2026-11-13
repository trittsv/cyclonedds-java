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
import org.omg.dds.domain.DomainParticipantFactory;

/** Single-process HelloWorld corresponding to the Python example. */
public final class SimpleHelloWorld {
    private SimpleHelloWorld() {}
    public static void main(String[] args) throws Exception {
        int domain = ExampleSupport.number(args, 0, 0, 0, 230);
        var env = ExampleSupport.environment();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("JavaSimpleHello", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var reader = subscriber.createDataReader(topic); var writer = publisher.createDataWriter(topic);
             var incoming = new ExampleSupport.ReadWait<>(reader)) {
            ExampleSupport.waitForReader(writer);
            writer.write(new Message(1, "Hello, World!"));
            for (var sample : incoming.take(ExampleSupport.deadline()))
                if (sample.getData() != null) System.out.println(sample.getData().get_text());
        }
    }
}
