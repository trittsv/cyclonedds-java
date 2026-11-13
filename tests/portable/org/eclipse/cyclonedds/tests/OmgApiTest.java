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
import BindingTest.DelayedKey;
import BindingTest.Telemetry;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.*;
import org.omg.dds.sub.*;
import org.omg.dds.type.TypeSupport;

/** Compiled with the normative API and generated data types, without the provider JAR. */
public final class OmgApiTest {
    private OmgApiTest() {}
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("DDS operation timed out");
            Thread.sleep(10);
        }
    }
    private static void rejects(Class<? extends Throwable> expected, Runnable operation) {
        try { operation.run(); }
        catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Unexpected exception", error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgApiTest.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(env);
        assert factory == DomainParticipantFactory.getInstance(env);
        assert env.getEnvironment() == env;
        var duration = Duration.newDuration(1500, TimeUnit.MILLISECONDS, env);
        assert duration.getDuration(TimeUnit.SECONDS) == 1;
        assert duration.getRemainder(TimeUnit.SECONDS, TimeUnit.MILLISECONDS) == 500;
        assert duration.add(500, TimeUnit.MILLISECONDS).getDuration(TimeUnit.SECONDS) == 2;
        assert Duration.infiniteDuration(env).isInfinite();
        assert Duration.zeroDuration(env).isZero();
        var time = Time.newTime(1234, TimeUnit.MILLISECONDS, env);
        var frozen = time.immutableCopy();
        time.add(1, TimeUnit.SECONDS);
        assert frozen.getTime(TimeUnit.MILLISECONDS) == 1234;
        assert time.getTime(TimeUnit.MILLISECONDS) == 2234;
        assert !Time.invalidTime(env).isValid();
        var nil = InstanceHandle.nilHandle(env);
        assert nil.isNil() && nil.modifiableCopy().isNil();
        // The normative archive's convenience overload passes type.getClass().getName().
        // Use the explicit registered-name overload for an interoperable IDL type name.
        var support = TypeSupport.newTypeSupport(Message.class, "HelloWorld::Message", env);
        assert support.getType() == Message.class && support.getTypeName().equals("HelloWorld::Message");
        var empty = support.newData();
        assert empty.get_id() == 0 && empty.get_text().isEmpty();
        empty.set_id(42); empty.set_text("OMG API → Java 🌍");
        var policies = PolicyFactory.getPolicyFactory(env);
        var history = policies.History().withKeepLast(16);
        var reliability = policies.Reliability().withReliable().withMaxBlockingTime(1, TimeUnit.SECONDS);
        try (var participant = factory.createParticipant(75);
             var topic = participant.createTopic("OmgApiTest", support);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(history, reliability));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(history, reliability))) {
            assert participant.getEnvironment() == env && reader.getEnvironment() == env;
            assert publisher.getParent() == participant && reader.getParent() == subscriber;
            assert writer.getTopic() == topic && reader.getTopicDescription() == topic;
            assert !participant.getInstanceHandle().isNil();
            var lookup = factory.lookupParticipant(75);
            assert lookup == participant;
            lookup.close(); // Releases the reference obtained by lookup.
            assert participant.getDomainId() == 75;
            assert topic.getTypeSupport().getType() == Message.class;
            var originalQos = writer.getQos();
            var changedQos = originalQos.withPolicy(history.withKeepLast(8));
            assert originalQos.getHistory().getDepth() == 16 && changedQos.getHistory().getDepth() == 8;
            rejects(UnsupportedOperationException.class, () -> originalQos.clear());
            rejects(UnsupportedOperationException.class, () -> participant.setQos("library", "profile"));
            rejects(UnsupportedOperationException.class, () -> policies.Durability().withPersitent());
            rejects(PreconditionNotMetException.class, topic::close);
            await(() -> !writer.getMatchedSubscriptions().isEmpty() && !reader.getMatchedPublications().isEmpty());
            var instance = writer.registerInstance(empty);
            assert !instance.isNil() && instance.equals(writer.lookupInstance(empty));
            long timestamp = 1_234_567_890_000L;
            writer.write(empty, instance, timestamp, TimeUnit.NANOSECONDS);
            writer.waitForAcknowledgments(2, TimeUnit.SECONDS);
            List<Sample<Message>> read = new ArrayList<>();
            await(() -> { reader.read(read); return !read.isEmpty(); });
            var received = read.get(0);
            assert received.getData().get_text().equals(empty.get_text());
            assert received.getSampleState() == SampleState.NOT_READ;
            assert received.getViewState() == ViewState.NEW;
            assert received.getInstanceState() == InstanceState.ALIVE;
            assert received.getSourceTimestamp().getTime(TimeUnit.NANOSECONDS) == timestamp;
            assert !received.getInstanceHandle().isNil() && !received.getPublicationHandle().isNil();
            var clone = received.clone();
            clone.getData().set_text("mutated clone");
            assert received.getData().get_text().equals(empty.get_text());
            try (var samples = reader.take()) {
                assert samples.hasNext();
                assert samples.next().getSampleState() == SampleState.READ;
                rejects(PreconditionNotMetException.class, reader::close);
                rejects(PreconditionNotMetException.class, participant::close);
            }
            writer.dispose(instance);
            List<Sample<Message>> notifications = new ArrayList<>();
            await(() -> { reader.take(notifications); return !notifications.isEmpty(); });
            assert notifications.get(0).getData() == null;
            assert notifications.get(0).getInstanceState() == InstanceState.NOT_ALIVE_DISPOSED;
            writer.unregisterInstance(instance);
        }
        // Registration must extract keys from the whole serialized sample, even
        // when a non-key field precedes the key in IDL declaration order.
        try (var participant = factory.createParticipant(76);
             var topic = participant.createTopic("OmgDelayedKey", DelayedKey.class);
             var publisher = participant.createPublisher();
             var writer = publisher.createDataWriter(topic)) {
            var one = writer.registerInstance(new DelayedKey("first text", 1));
            var same = writer.registerInstance(new DelayedKey("different text", 1));
            var other = writer.registerInstance(new DelayedKey("first text", 2));
            assert one.equals(same) && !one.equals(other);
            writer.dispose(one);
        }
        // Primitive sequences are visible through the IDL4 API, without importing
        // or compiling against a vendor sequence implementation.
        try (var participant = factory.createParticipant(76);
             var topic = participant.createTopic("OmgTelemetry", Telemetry.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic); var reader = subscriber.createDataReader(topic)) {
            var telemetry = new Telemetry();
            telemetry.set_id(101);
            telemetry.get_values().add(7);
            telemetry.get_values().add(11);
            await(() -> !writer.getMatchedSubscriptions().isEmpty());
            writer.write(telemetry);
            List<Sample<Telemetry>> received = new ArrayList<>();
            await(() -> { reader.take(received); return !received.isEmpty(); });
            assert received.get(0).getData().get_values().equals(List.of(7, 11));
        }
        var closed = factory.createParticipant(77);
        closed.close();
        rejects(AlreadyClosedException.class, closed::getDomainId);
        System.out.println("Normative OMG API: bootstrap, typed DDS pub/sub, QoS, read/take, metadata, loans, timestamps and instances passed");
    }
}
