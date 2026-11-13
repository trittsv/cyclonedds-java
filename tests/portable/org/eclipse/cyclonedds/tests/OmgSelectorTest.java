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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

/** Exercises native filtering through the normative API, without provider imports. */
public final class OmgSelectorTest {
    private OmgSelectorTest() {}
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
        var env = ServiceEnvironment.createInstance(OmgSelectorTest.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(env);
        var policies = PolicyFactory.getPolicyFactory(env);
        var history = policies.History().withKeepAll();
        var reliable = policies.Reliability().withReliable();
        try (var participant = factory.createParticipant(78);
             var topic = participant.createTopic("OmgSelectors", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(history, reliable));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(history, reliable));
             var otherReader = subscriber.createDataReader(topic)) {
            var empty = subscriber.createDataState();
            assert empty.getSampleStates().isEmpty() && empty.getViewStates().isEmpty() && empty.getInstanceStates().isEmpty();
            var unread = subscriber.createDataState().with(SampleState.NOT_READ).withAnyViewState().withAnyInstanceState();
            assert unread.clone().equals(unread) && unread.clone().hashCode() == unread.hashCode();
            var states = unread.getSampleStates();
            rejects(UnsupportedOperationException.class, () -> states.add(SampleState.READ));
            var selector = reader.select().dataState(unread).maxSamples(1);
            unread.with(SampleState.READ); // Selector owns a snapshot, not this mutable input.
            assert selector.getDataState().getSampleStates().equals(Set.of(SampleState.NOT_READ));
            rejects(UnsupportedOperationException.class, () -> selector.getDataState().with(SampleState.READ));
            assert reader.select().getMaxSamples() == ResourceLimits.LENGTH_UNLIMITED;
            assert reader.select().getInstance().isNil();
            rejects(IllegalArgumentException.class, () -> selector.maxSamples(-2));
            rejects(IllegalArgumentException.class, () -> selector.maxSamples(65537));
            rejects(IllegalArgumentException.class, () -> otherReader.take(selector));
            rejects(IllegalArgumentException.class, () -> reader.read((DataReader.Selector<Message>) null));
            rejects(IllegalArgumentException.class, () -> reader.take(new ArrayList<>(), null));
            assert selector.nextInstance(true).retrieveNextInstance();
            selector.nextInstance(false);

            await(() -> writer.getMatchedSubscriptions().size() == 2);
            for (int key = 1; key <= 3; key++) writer.write(new Message(key, "message " + key));
            writer.waitForAcknowledgments(2, TimeUnit.SECONDS);
            await(() -> !reader.lookupInstance(new Message(3, "")).isNil());
            rejects(PreconditionNotMetException.class, () -> reader.select().instance(writer.getInstanceHandle()).take());

            // An empty or incomplete state must match nothing, even though the
            // C API interprets zero mask components as ANY.
            try (var samples = reader.select().dataState(empty).take()) { assert !samples.hasNext(); }
            try (var samples = reader.select().dataState(empty.clone().withAnySampleState()).take()) { assert !samples.hasNext(); }
            try (var samples = reader.select().maxSamples(0).take()) { assert !samples.hasNext(); }
            Set<Integer> keys = new HashSet<>();
            for (int i = 0; i < 3; i++) try (var samples = selector.read()) {
                assert samples.hasNext();
                var sample = samples.next();
                assert sample.getSampleState() == SampleState.NOT_READ && sample.getViewState() == ViewState.NEW;
                assert keys.add(sample.getData().get_id());
                assert !samples.hasNext();
            }
            assert keys.equals(Set.of(1, 2, 3));
            try (var samples = selector.read()) { assert !samples.hasNext(); }
            var read = subscriber.createDataState().with(SampleState.READ).with(ViewState.NOT_NEW).with(InstanceState.ALIVE);
            assert reader.select().dataState(read).read(null).size() == 3;
            var newViews = subscriber.createDataState().withAnySampleState().with(ViewState.NEW).withAnyInstanceState();
            assert reader.select().dataState(newViews).read(null).isEmpty();

            var instance = reader.lookupInstance(new Message(2, ""));
            var mutableHandle = instance.modifiableCopy();
            var onlyTwo = reader.select().instance(mutableHandle);
            mutableHandle.copyFrom(InstanceHandle.nilHandle(env));
            assert onlyTwo.getInstance().equals(instance);
            var two = onlyTwo.take(null);
            assert two.size() == 1 && two.get(0).getData().get_id() == 2;
            assert reader.select().read(null).size() == 2; // Other instances remain in DDS.

            // The list+selector overload uses the existing list length as its
            // upper bound; a null list allocates storage without that bound.
            List<Sample<Message>> destination = new ArrayList<>();
            assert reader.take(destination, reader.select()).isEmpty();
            destination.add(null);
            assert reader.take(destination, reader.select().maxSamples(10)) == destination;
            assert destination.size() == 1;
            assert reader.select().take(null).size() == 1;
            assert reader.select().take(null).isEmpty();
            var reusable = two.get(0);
            assert !reader.takeNextSample(reusable);
            writer.write(new Message(21, "next sample"));
            writer.waitForAcknowledgments(2, TimeUnit.SECONDS);
            await(() -> reader.readNextSample(reusable));
            assert reusable.getData().get_id() == 21 && reusable.getSampleState() == SampleState.NOT_READ;
            assert !reader.readNextSample(reusable); // READ samples are excluded.
            writer.write(new Message(22, "reuse list"));
            writer.waitForAcknowledgments(2, TimeUnit.SECONDS);
            await(() -> !reader.lookupInstance(new Message(22, "")).isNil());
            List<Sample<Message>> reusedList = new ArrayList<>(List.of(reusable));
            reader.select().instance(reader.lookupInstance(new Message(22, ""))).take(reusedList);
            assert reusedList.get(0) == reusable && reusable.getData().get_id() == 22;
            reader.select().take(null);

            // Native filtering includes invalid-data lifecycle notifications.
            writer.write(new Message(11, "dispose me"));
            writer.waitForAcknowledgments(2, TimeUnit.SECONDS);
            await(() -> !reader.lookupInstance(new Message(11, "")).isNil());
            reader.select().take(null);
            var disposed = subscriber.createDataState().withAnySampleState().withAnyViewState().withNotAliveInstanceStates();
            var notifications = reader.select().dataState(disposed);
            writer.dispose(writer.lookupInstance(new Message(11, "")));
            List<Sample<Message>> received = new ArrayList<>();
            await(() -> { received.clear(); received.addAll(notifications.take(null)); return !received.isEmpty(); });
            assert received.size() == 1 && received.get(0).getData() == null;
            assert received.get(0).getInstanceState() == InstanceState.NOT_ALIVE_DISPOSED;

            // Closing endpoints repeatedly must leave the participant usable.
            for (int i = 0; i < 32; i++) {
                var temporary = subscriber.createDataReader(topic);
                var stale = temporary.select();
                temporary.close();
                rejects(AlreadyClosedException.class, stale::read);
            }
        }
        System.out.println("OMG selectors: state masks, instance filtering, bounded lists, lifecycle and ownership passed");
    }
}
