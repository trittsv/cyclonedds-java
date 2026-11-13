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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.omg.dds.core.event.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.pub.DataWriterAdapter;
import org.omg.dds.sub.DataReaderAdapter;

/** Typed DDS adapters and explicit masks for data, matching, deadline and liveliness. */
public final class Listeners {
    private Listeners() {}
    private static void await(CountDownLatch event, String name) throws InterruptedException {
        if (!event.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing listener event: " + name);
    }
    public static void main(String[] args) throws Exception {
        int domain = ExampleSupport.number(args, 0, 0, 0, 230);
        var env = ExampleSupport.environment();
        var policy = PolicyFactory.getPolicyFactory(env);
        var received = new CountDownLatch(1);
        var requested = new CountDownLatch(1);
        var offered = new CountDownLatch(1);
        var lost = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var readerListener = new DataReaderAdapter<Message>() {
            @Override public void onDataAvailable(DataAvailableEvent<Message> event) {
                // Keep callbacks short. Copy data to a queue for expensive application work.
                try (var samples = event.getSource().take()) {
                    while (samples.hasNext()) {
                        var data = samples.next().getData();
                        if (data != null) {
                            System.out.println("onDataAvailable: " + data.get_text());
                            received.countDown();
                        }
                    }
                } catch (Exception error) { failure.set(error); received.countDown(); }
            }
            @Override public void onSubscriptionMatched(SubscriptionMatchedEvent<Message> event) {
                System.out.println("onSubscriptionMatched: " + event.getStatus().getCurrentCount());
            }
            @Override public void onRequestedDeadlineMissed(RequestedDeadlineMissedEvent<Message> event) {
                System.out.println("onRequestedDeadlineMissed: " + event.getStatus().getTotalCount());
                requested.countDown();
            }
            @Override public void onLivelinessChanged(LivelinessChangedEvent<Message> event) {
                System.out.println("onLivelinessChanged: alive=" + event.getStatus().getAliveCount());
            }
        };
        var writerListener = new DataWriterAdapter<Message>() {
            @Override public void onPublicationMatched(PublicationMatchedEvent<Message> event) {
                System.out.println("onPublicationMatched: " + event.getStatus().getCurrentCount());
            }
            @Override public void onOfferedDeadlineMissed(OfferedDeadlineMissedEvent<Message> event) {
                System.out.println("onOfferedDeadlineMissed: " + event.getStatus().getTotalCount());
                offered.countDown();
            }
            @Override public void onLivelinessLost(LivelinessLostEvent<Message> event) {
                System.out.println("onLivelinessLost: " + event.getStatus().getTotalCount());
                lost.countDown();
            }
        };
        var reliable = policy.Reliability().withReliable();
        var deadline = policy.Deadline().withPeriod(200, TimeUnit.MILLISECONDS);
        var liveliness = policy.Liveliness().withManualByTopic().withLeaseDuration(1, TimeUnit.SECONDS);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain);
             var topic = participant.createTopic("JavaListeners", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(reliable, deadline, liveliness),
                 readerListener, Set.of(DataAvailableStatus.class, SubscriptionMatchedStatus.class, RequestedDeadlineMissedStatus.class, LivelinessChangedStatus.class));
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(reliable, deadline, liveliness),
                 writerListener, Set.of(PublicationMatchedStatus.class, OfferedDeadlineMissedStatus.class, LivelinessLostStatus.class))) {
            ExampleSupport.waitForReader(writer);
            writer.write(new Message(1, "Hello from a DDS listener"));
            await(received, "data");
            if (failure.get() != null) throw failure.get();
            // Deliberately stop writing and asserting liveliness to trigger these statuses.
            await(requested, "requested deadline");
            await(offered, "offered deadline");
            await(lost, "liveliness lost");
            // Remove listeners before closing their entities and application callback state.
            reader.setListener(null);
            writer.setListener(null);
            System.out.println("Listeners: data, deadlines and liveliness observed");
        }
    }
}
