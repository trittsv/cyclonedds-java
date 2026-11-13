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
import BindingTest.CompositeKey;
import BindingTest.DelayedKey;
import BindingTest.KeyedEvolving;
import BindingTest.KeyedChoice;
import BindingTest.NestedKeys;
import BindingTest.Position;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

public final class OmgOperationsTest {
    private OmgOperationsTest() {}
    interface Operation { void run() throws Exception; }
    static void rejects(Class<? extends Throwable> type, Operation op) throws Exception {
        try { op.run(); } catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError(e); }
        throw new AssertionError("Expected " + type.getName());
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgOperationsTest.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(env);
        var policy = PolicyFactory.getPolicyFactory(env);
        var ws = WaitSet.newWaitSet(env);
        try (var participant = factory.createParticipant(85);
             var topic = participant.createTopic("EverydayOperations", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicy(policy.History().withKeepAll()));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(policy.History().withKeepAll(), policy.Reliability().withReliable()));
             var available = reader.select().getCondition()) {
            ws.attachCondition(available);
            var registered = writer.registerInstance(new Message(7, "not published"), 123, TimeUnit.NANOSECONDS);
            assert registered.equals(writer.registerInstance(new Message(7, "same key"), Time.newTime(456, TimeUnit.NANOSECONDS, env)));
            assert reader.select().take(null).isEmpty();
            rejects(IllegalArgumentException.class, () -> writer.registerInstance(new Message(8, "invalid"), -1, TimeUnit.SECONDS));
            Message holder = new Message(0, "preserve non-key");
            assert writer.getKeyValue(holder, registered) == holder;
            assert holder.get_id() == 7 && holder.get_text().equals("preserve non-key");
            assert writer.getKeyValue(registered).get_id() == 7;
            rejects(IllegalArgumentException.class, () -> writer.getKeyValue(InstanceHandle.nilHandle(env)));
            for (int key = 1; key <= 3; key++) writer.write(new Message(key, "value " + key));
            writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            ws.waitForConditions(5, TimeUnit.SECONDS);
            Set<Integer> seen = new HashSet<>();
            var traversal = reader.select().nextInstance(true);
            assert traversal.retrieveNextInstance();
            for (;;) {
                var samples = traversal.read(null);
                if (samples.isEmpty()) break;
                var sample = samples.get(0);
                assert seen.add(sample.getData().get_id());
                for (var value : samples) assert value.getInstanceHandle().equals(sample.getInstanceHandle());
                traversal.instance(sample.getInstanceHandle());
            }
            assert seen.equals(Set.of(1, 2, 3)) : seen;
            var handle = reader.lookupInstance(new Message(2, ""));
            holder.set_id(0);
            assert reader.getKeyValue(holder, handle) == holder && holder.get_id() == 2 && holder.get_text().equals("preserve non-key");
            rejects(IllegalArgumentException.class, () -> reader.select().Content("missing = 1", List.of()));
            rejects(IllegalArgumentException.class, () -> reader.createQueryCondition("id = %1", List.of("1")));
            rejects(IllegalArgumentException.class, () -> reader.createQueryCondition("id =", List.of()));
            rejects(IllegalArgumentException.class, () -> reader.createQueryCondition("id = 'text'", List.of()));
            rejects(IllegalArgumentException.class, () -> reader.createQueryCondition("id LIKE '%'", List.of()));
            try (var query = reader.createQueryCondition("id = %0", List.of("99"))) {
                ws.detachCondition(available); ws.attachCondition(query);
                rejects(TimeoutException.class, () -> ws.waitForConditions(0, TimeUnit.NANOSECONDS));
                var waitFailure = new AtomicReference<Throwable>();
                Thread waiting = new Thread(() -> {
                    try { ws.waitForConditions(5, TimeUnit.SECONDS); } catch (Throwable e) { waitFailure.set(e); }
                });
                waiting.start();
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Arrays.stream(waiting.getStackTrace()).noneMatch(f -> f.getMethodName().equals("await") && f.isNativeMethod())) {
                    if (!waiting.isAlive() || System.nanoTime() > waitDeadline) throw new AssertionError("Wait did not block");
                    Thread.sleep(1);
                }
                query.setQueryParameters(List.of("2")); // Re-evaluate cached data and wake an already blocked waiter.
                waiting.join(2000); assert !waiting.isAlive() && waitFailure.get() == null : waitFailure.get();
                // Attach/detach can race with replacement without losing the current native attachment.
                Thread attaching = new Thread(() -> {
                    try { for (int i = 0; i < 100; i++) { ws.detachCondition(query); ws.attachCondition(query); } }
                    catch (Throwable e) { waitFailure.set(e); }
                });
                attaching.start();
                for (int i = 0; i < 100; i++) query.setQueryParameters(List.of(i % 2 == 0 ? "99" : "2"));
                attaching.join(2000); assert !attaching.isAlive() && waitFailure.get() == null : waitFailure.get();
                assert query.getQueryExpression().equals("id = %0") && query.getQueryParameters().equals(List.of("2"));
                rejects(UnsupportedOperationException.class, () -> query.getQueryParameters().add("3"));
                ws.waitForConditions(5, TimeUnit.SECONDS);
                rejects(IllegalArgumentException.class, () -> query.setQueryParameters(List.of()));
                assert query.getQueryParameters().equals(List.of("2"));
                var selected = reader.select().Content("id = %0", List.of("2")).take(null);
                assert selected.size() == 1 && selected.get(0).getData().get_id() == 2;
                assert !query.getTriggerValue();
                var rest = reader.select().Content("(id BETWEEN 1 AND 3) AND text LIKE 'value %' AND id NOT IN (2, 4)", List.of()).take(null);
                assert rest.size() == 2;
                assert reader.select().take(null).isEmpty(); // Filtered take consumed exactly the selected instances.
                ws.detachCondition(query);
            }
            writer.write(new Message(41, "first")); writer.write(new Message(41, "second")); writer.write(new Message(42, "third"));
            writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            Set<Integer> taken = new HashSet<>();
            var nextTake = reader.select().nextInstance(true).Content("id >= 40", List.of());
            int count = 0;
            for (;;) {
                var samples = nextTake.take(null);
                if (samples.isEmpty()) break;
                int id = samples.get(0).getData().get_id(); assert taken.add(id);
                for (var sample : samples) assert sample.getData().get_id() == id;
                count += samples.size(); nextTake.instance(samples.get(0).getInstanceHandle());
            }
            assert count == 3 && taken.equals(Set.of(41, 42));
            var finalQuery = reader.createQueryCondition("id = 100", List.of());
            reader.closeContainedEntities();
            rejects(AlreadyClosedException.class, finalQuery::getQueryExpression);
            for (int i = 0; i < 100; i++) try (var query = reader.createQueryCondition("id = 1", List.of())) { assert !query.getTriggerValue(); }
            try (var filtered = participant.createContentFilteredTopic("OnlyLarge", topic, "id >= %0", List.of("20"));
                 var filteredOther = participant.createContentFilteredTopic("OnlySmall", topic, "id < 20", List.of());
                 var filteredReader = subscriber.createDataReader(filtered);
                 var smallReader = subscriber.createDataReader(filteredOther)) {
                assert filtered.getRelatedTopic() == topic && filteredReader.getTopicDescription() == filtered;
                writer.write(new Message(10, "small")); writer.write(new Message(30, "large"));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert filteredReader.select().take(null).stream().map(s -> s.getData().get_id()).toList().equals(List.of(30));
                assert smallReader.select().take(null).stream().map(s -> s.getData().get_id()).toList().equals(List.of(10));
                assert reader.select().take(null).size() == 2;
                filtered.setExpressionParameters(List.of("5"));
                writer.write(new Message(11, "now admitted")); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert filteredReader.select().take(null).size() == 1;
            }
            try (var unionTopic = participant.createTopic("UnionKeys", KeyedChoice.class);
                 var dw = publisher.createDataWriter(unionTopic)) {
                var data = new KeyedChoice(); data.set_number(99);
                var key = dw.registerInstance(data);
                var holderUnion = new KeyedChoice(); holderUnion.set_number(123);
                assert dw.getKeyValue(holderUnion, key) == holderUnion;
                assert holderUnion.get_discriminator() == 1 && holderUnion.get_number() == 123;
                assert dw.getKeyValue(key).get_discriminator() == 1;
            }
            try (var evolving = participant.createTopic("AppendableKeys", KeyedEvolving.class);
                 var dw = publisher.createDataWriter(evolving)) {
                var key = dw.registerInstance(new KeyedEvolving(17, "original"));
                var value = dw.getKeyValue(new KeyedEvolving(0, "retained"), key);
                assert value.get_id() == 17 && value.get_text().equals("retained");
            }
            try (var nested = participant.createTopic("NestedKeys", NestedKeys.class);
                 var dw = publisher.createDataWriter(nested)) {
                var key = dw.registerInstance(new NestedKeys(new Position(1.25, 2.5), "nested region", "original"));
                var value = dw.getKeyValue(new NestedKeys(new Position(), "", "retained"), key);
                assert value.get_position().equals(new Position(1.25, 2.5)) && value.get_region().equals("nested region") && value.get_text().equals("retained");
                // Handles of incompatible key layouts are rejected before native key deserialization.
                rejects(IllegalArgumentException.class, () -> dw.getKeyValue(registered));
            }
            // Key fields need not precede non-key fields; composite strings use native key decoding.
            try (var delayed = participant.createTopic("KeyAfterText", DelayedKey.class);
                 var dw = publisher.createDataWriter(delayed)) {
                var key = dw.registerInstance(new DelayedKey("text", 42));
                var value = dw.getKeyValue(new DelayedKey("retained", 0), key);
                assert value.get_id() == 42 && value.get_text().equals("retained");
            }
            try (var composite = participant.createTopic("CompositeGetKey", CompositeKey.class);
                 var dw = publisher.createDataWriter(composite)) {
                var key = dw.registerInstance(new CompositeKey("Europe 🌍", 73, "payload"));
                var value = dw.getKeyValue(new CompositeKey("", 0, "retained"), key);
                assert value.get_region().equals("Europe 🌍") && value.get_id() == 73 && value.get_text().equals("retained");
            }
        } finally { ((AutoCloseable) ws).close(); }
        System.out.println("Everyday operations: next instances, keys, timestamp registration, SQL queries and content-filtered topics passed");
    }
}
