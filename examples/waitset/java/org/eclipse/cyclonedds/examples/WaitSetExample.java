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
import org.omg.dds.core.GuardCondition;
import org.omg.dds.core.WaitSet;
import org.omg.dds.domain.DomainParticipantFactory;

/** Read conditions for data and a guard condition for application-controlled shutdown. */
public final class WaitSetExample {
    private WaitSetExample() {}
    public static void main(String[] args) throws Exception {
        int domain = ExampleSupport.number(args, 0, 0, 0, 230);
        var env = ExampleSupport.environment();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("JavaWaitSet", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var reader = subscriber.createDataReader(topic); var writer = publisher.createDataWriter(topic);
             var condition = reader.select().getCondition()) {
            var waitset = WaitSet.newWaitSet(env);
            var stop = GuardCondition.newGuardCondition(env);
            try {
                waitset.attachCondition(condition);
                waitset.attachCondition(stop);
                ExampleSupport.waitForReader(writer);
                writer.write(new Message(1, "WaitSet wakes on data"));
                waitset.waitForConditions(5, TimeUnit.SECONDS);
                try (var samples = reader.take()) {
                    if (!samples.hasNext()) throw new IllegalStateException("Missing data");
                    System.out.println(samples.next().getData().get_text());
                }
                // Another application thread can set this guard to stop a blocked event loop.
                stop.setTriggerValue(true);
                waitset.waitForConditions(0, TimeUnit.SECONDS);
                System.out.println("GuardCondition: shutdown requested");
            } finally { ExampleSupport.close(waitset); ExampleSupport.close(stop); }
        }
    }
}
