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

package org.eclipse.cyclonedds.tests;

import HelloWorld.Message;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import org.omg.dds.core.*;
import org.omg.dds.core.event.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.pub.DataWriterListener;
import org.omg.dds.sub.*;

public final class OmgListenerTest {
    private OmgListenerTest() {}
    @SuppressWarnings("unchecked")
    private static <L> L listener(Class<L> api, BiConsumer<String, StatusChangedEvent<?>> callback) {
        return (L) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (p, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> p == args[0]; case "hashCode" -> System.identityHashCode(p); default -> "test listener";
            };
            callback.accept(method.getName(), (StatusChangedEvent<?>) args[0]); return null;
        });
    }
    private static void await(CountDownLatch latch) throws InterruptedException { assert latch.await(5, TimeUnit.SECONDS) : "Listener timed out"; }
    @SuppressWarnings({"unchecked", "try"})
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgListenerTest.class.getClassLoader());
        var policy = PolicyFactory.getPolicyFactory(env);
        var failure = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { failure.compareAndSet(null, e); e.printStackTrace(); });
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(86);
             var topic = participant.createTopic("ListenerOperations", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber()) {
            var matched = new CountDownLatch(1);
            DataWriterListener<Message> wl = listener(DataWriterListener.class, (name, event) -> {
                if (!name.equals("onPublicationMatched")) throw new AssertionError(name);
                var status = ((PublicationMatchedEvent<?>) event).getStatus();
                if (status.getCurrentCount() > 0) { assert status.getEnvironment() == env; assert event.clone().getSource() == event.getSource(); matched.countDown(); }
            });
            var received = new CountDownLatch(1);
            var readerMatched = new CountDownLatch(1);
            DataReaderListener<Message> rl = listener(DataReaderListener.class, (name, event) -> {
                if (name.equals("onSubscriptionMatched")) { readerMatched.countDown(); return; }
                if (!name.equals("onDataAvailable")) throw new AssertionError(name);
                var source = (DataReader<Message>) event.getSource();
                var samples = source.select().take(null); // A listener may call DDS: no native callback/root-lock deadlock.
                if (!samples.isEmpty()) {
                    assert samples.get(0).getData().get_text().equals("callback data");
                    assert event.getEnvironment() == env;
                    received.countDown();
                }
            });
            try (var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos(), wl, Set.of(PublicationMatchedStatus.class));
                 var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos(), rl, Set.of(DataAvailableStatus.class, SubscriptionMatchedStatus.class))) {
                assert writer.getListener() == wl && reader.getListener() == rl;
                await(matched); await(readerMatched);
                writer.write(new Message(1, "callback data")); await(received);
                assert writer.getPublicationMatchedStatus().getCurrentCount() == 1;
                assert reader.getSubscriptionMatchedStatus().getCurrentCount() == 1;
                assert reader.getSampleLostStatus().getTotalCount() == 0;
                assert reader.getSampleRejectedStatus().getLastReason() == SampleRejectedStatus.Kind.NOT_REJECTED;
                assert reader.getRequestedDeadlineMissedStatus().getTotalCount() == 0;
                assert reader.getRequestedIncompatibleQosStatus().getTotalCount() == 0;
                assert writer.getOfferedDeadlineMissedStatus().getTotalCount() == 0;
                assert writer.getLivelinessLostStatus().getTotalCount() == 0;
                assert topic.getInconsistentTopicStatus().getTotalCount() == 0;
                assert reader.getLivelinessChangedStatus().getAliveCount() == 1;
                var inherited = new CountDownLatch(1);
                SubscriberListener sl = listener(SubscriberListener.class, (name, event) -> {
                    if (name.equals("onDataAvailable")) {
                        assert event.getSource() == reader;
                        ((DataReader<?>) event.getSource()).select().take(null); inherited.countDown();
                    }
                });
                subscriber.setListener(sl, Set.of(DataAvailableStatus.class));
                reader.setListener(null);
                assert reader.getListener() == null;
                writer.write(new Message(2, "inherited")); await(inherited);
                var onReaders = new CountDownLatch(1);
                subscriber.setListener(listener(SubscriberListener.class, (name, event) -> {
                    if (name.equals("onDataOnReaders")) { assert event.getSource() == subscriber; subscriber.notifyDataReaders(); onReaders.countDown(); }
                }), Set.of(DataOnReadersStatus.class));
                // notifyDataReaders calls the reader listeners explicitly, without recursively raising DataOnReaders.
                var notified = new CountDownLatch(1);
                reader.setListener(listener(DataReaderListener.class, (name, event) -> {
                    if (name.equals("onDataAvailable")) { reader.select().take(null); notified.countDown(); }
                }), Set.of(DataAvailableStatus.class));
                writer.write(new Message(3, "notify readers")); await(onReaders); await(notified);
                subscriber.setListener(null);
                // Closing an entity inside its callback must be safe.
                var closed = new CountDownLatch(1);
                reader.setListener(listener(DataReaderListener.class, (name, event) -> {
                    if (name.equals("onDataAvailable")) { reader.close(); closed.countDown(); }
                }), Set.of(DataAvailableStatus.class));
                writer.write(new Message(4, "close from callback")); await(closed);
            }
            // A real incompatible reliability event exercises status snapshots and policy ID mapping.
            var incompatible = new CountDownLatch(1);
            var incompatibleStatus = new AtomicReference<RequestedIncompatibleQosStatus>();
            try (var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(policy.Reliability().withBestEffort()));
                 var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicy(policy.Reliability().withReliable()),
                     listener(DataReaderListener.class, (name, event) -> {
                         if (name.equals("onRequestedIncompatibleQos")) { incompatibleStatus.set(((RequestedIncompatibleQosEvent<?>) event).getStatus()); incompatible.countDown(); }
                     }), Set.of(RequestedIncompatibleQosStatus.class))) {
                await(incompatible);
                assert incompatibleStatus.get().getTotalCount() >= 1;
                assert incompatibleStatus.get().getLastPolicyClass() == Reliability.class;
            }
            var offeredDeadline = new CountDownLatch(1);
            var requestedDeadline = new CountDownLatch(1);
            var deadline = policy.Deadline().withPeriod(100, TimeUnit.MILLISECONDS);
            try (var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(deadline),
                     listener(DataWriterListener.class, (name, event) -> {
                         if (name.equals("onOfferedDeadlineMissed")) {
                             assert ((OfferedDeadlineMissedEvent<?>) event).getStatus().getTotalCount() > 0;
                             assert !((OfferedDeadlineMissedEvent<?>) event).getStatus().getLastInstanceHandle().isNil(); offeredDeadline.countDown();
                         }
                     }), Set.of(OfferedDeadlineMissedStatus.class));
                 var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicy(deadline),
                     listener(DataReaderListener.class, (name, event) -> {
                         if (name.equals("onRequestedDeadlineMissed")) {
                             assert ((RequestedDeadlineMissedEvent<?>) event).getStatus().getTotalCount() > 0; requestedDeadline.countDown();
                         }
                     }), Set.of(RequestedDeadlineMissedStatus.class))) {
                writer.write(new Message(20, "deadline")); await(offeredDeadline); await(requestedDeadline);
            }
            var rejected = new CountDownLatch(1);
            try (var writer = publisher.createDataWriter(topic);
                 var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicy(policy.ResourceLimits().withMaxInstances(1)),
                     listener(DataReaderListener.class, (name, event) -> {
                         if (name.equals("onSampleRejected")) {
                             var status = ((SampleRejectedEvent<?>) event).getStatus();
                             assert status.getTotalCount() > 0 && status.getLastReason() == SampleRejectedStatus.Kind.REJECTED_BY_INSTANCES_LIMIT;
                             rejected.countDown();
                         }
                     }), Set.of(SampleRejectedStatus.class))) {
                writer.write(new Message(21, "first instance")); writer.write(new Message(22, "instance limit")); await(rejected);
            }
            var lost = new CountDownLatch(1);
            try (var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(
                     policy.Liveliness().withManualByTopic().withLeaseDuration(100, TimeUnit.MILLISECONDS)),
                     listener(DataWriterListener.class, (name, event) -> {
                         if (name.equals("onLivelinessLost")) { assert ((LivelinessLostEvent<?>) event).getStatus().getTotalCount() > 0; lost.countDown(); }
                     }), Set.of(LivelinessLostStatus.class));
                 var reader = subscriber.createDataReader(topic)) {
                writer.write(new Message(23, "liveliness lease")); await(lost);
            }
            assert failure.get() == null : failure.get();
        }
        System.out.println("Listeners: snapshots, status getters, masks, inheritance, reentrant DDS calls and callback close passed");
    }
}
