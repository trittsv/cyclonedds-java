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

import XcdrTest.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.topic.TypeSupport.Codec;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.*;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;

public final class Xcdr1Test {
    private static <T> void echo(DomainParticipant p, Publisher pub, Subscriber sub, Class<T> type, List<T> values) throws Exception {
        echo(p, pub, sub, type, values, values);
    }
    private static <T> void echo(DomainParticipant p, Publisher pub, Subscriber sub, Class<T> type, List<T> values, List<T> expected) throws Exception {
        exchange(p, pub, sub, type, type.getSimpleName(), values, expected);
    }
    private static <T> void exchange(DomainParticipant p, Publisher pub, Subscriber sub, Class<T> type, String peerType, List<T> values, List<T> expected) throws Exception {
        var ws = WaitSet.newWaitSet(p.getEnvironment());
        try (var toC = p.createTopic("Broad" + peerType + "ToC", type);
             var fromC = p.createTopic("Broad" + peerType + "FromC", type);
             var writer = pub.createDataWriter(toC); var reader = sub.createDataReader(fromC);
             var available = reader.select().getCondition()) {
            Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty() && !reader.getMatchedPublications().isEmpty());
            ws.attachCondition(available);
            for (int index = 0; index < values.size(); index++) {
                writer.write(values.get(index)); ws.waitForConditions(5, TimeUnit.SECONDS);
                var samples = reader.select().take(null);
                assert samples.size() == 1 && expected.get(index).equals(samples.get(0).getData()) : type.getSimpleName() + ": " + samples;
            }
        } finally { ((AutoCloseable)ws).close(); }
    }
    private static <T> void roundtrip(Codec<T> codec, T value) {
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            var writer = new CdrWriter(1, order); codec.write(writer, value);
            var reader = new CdrReader(writer.toByteArray());
            assert value.equals(codec.read(reader)); assert !reader.hasRemaining();
        }
    }
    private static void vectors() {
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            var writer = new CdrWriter(1, order);
            OptionalBeforeKey.codec().write(writer, new OptionalBeforeKey(0x0102030405060708L, 9));
            var expected = ByteBuffer.allocate(28).order(order);
            expected.put(new byte[]{0, (byte)(order == ByteOrder.LITTLE_ENDIAN ? 1 : 0), 0, 0})
                .putShort((short)0x7f01).putShort((short)8).putInt(7).putInt(8).putLong(0x0102030405060708L).putInt(9);
            assert Arrays.equals(writer.toByteArray(), expected.array());
            // Independent compact-header vector: the long long starts at a
            // parameter-relative origin, not at the encapsulation's origin.
            var compact = ByteBuffer.allocate(20).order(order);
            compact.put(new byte[]{0, (byte)(order == ByteOrder.LITTLE_ENDIAN ? 1 : 0), 0, 0})
                .putShort((short)7).putShort((short)8).putLong(0x0102030405060708L).putInt(9);
            assert OptionalBeforeKey.codec().read(new CdrReader(compact.array())).equals(new OptionalBeforeKey(0x0102030405060708L, 9));
        }
        roundtrip(OptionalBeforeKey.codec(), new OptionalBeforeKey(null, 1));
        roundtrip(Aligned.codec(), new Aligned(2, Long.MIN_VALUE, "a".repeat(70000), 3));
        roundtrip(Nested.codec(), new Nested(1, new Aligned(2, Long.MAX_VALUE, null, 3), new OptionalBeforeKey(5L, 4), 6));
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            var future = new CdrWriter(1, order); int end = future.beginType(2);
            // An extended member ID equal to PID_LIST_END is ordinary data;
            // only the short-form control header terminates a parameter list.
            int m = future.beginMember(0x3f02, false); future.writeInt(12); future.endMember(m);
            m = future.beginMember(30000, false); future.writeString("ignored"); future.endMember(m);
            m = future.beginMember(19, false); future.writeLong(77); future.endMember(m);
            m = future.beginMember(7, true); future.writeInt(8); future.endMember(m); future.endType(end);
            assert OldMutable.codec().read(new CdrReader(future.toByteArray())).equals(new OldMutable(8, 77));
            byte[] complete = future.toByteArray();
            for (int size = 0; size < complete.length; size++) {
                byte[] truncated = Arrays.copyOf(complete, size);
                Checks.rejects(IllegalArgumentException.class, () -> OldMutable.codec().read(new CdrReader(truncated)));
            }
            var unknown = new CdrWriter(1, order); end = unknown.beginType(2);
            m = unknown.beginMember(30000, true); unknown.writeInt(1); unknown.endMember(m); unknown.endType(end);
            Checks.rejects(IllegalArgumentException.class, () -> OldMutable.codec().read(new CdrReader(unknown.toByteArray())));
            var appendable = new CdrWriter(1, order); NewAppendable.codec().write(appendable, new NewAppendable(7, "future"));
            assert OldAppendable.codec().read(new CdrReader(appendable.toByteArray())).get_id() == 7;
        }
    }
    private static void nestedVectors() {
        for (int version : List.of(1, 2)) for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            var writer = new CdrWriter(version, order);
            WrappedNew.codec().write(writer, new WrappedNew(3, new TailNew(11, new NewAppendable(7, "future")), 99));
            var reader = new CdrReader(writer.toByteArray());
            assert WrappedOld.codec().read(reader).equals(new WrappedOld(3, new TailOld(11, new OldAppendable(7)), 99));
            assert !reader.hasRemaining();
            writer = new CdrWriter(version, order);
            OptionalNew.codec().write(writer, new OptionalNew(11, new NewAppendable(7, "future"), 99));
            reader = new CdrReader(writer.toByteArray());
            assert OptionalOld.codec().read(reader).equals(new OptionalOld(11, new OldAppendable(7), 99));
            assert !reader.hasRemaining();
            var branch = new BranchNew(); branch.set_payload(new NewAppendable(7, "future"));
            writer = new CdrWriter(version, order); UnionNew.codec().write(writer, new UnionNew(11, branch, 99));
            var expected = new BranchOld(); expected.set_payload(new OldAppendable(7));
            reader = new CdrReader(writer.toByteArray());
            assert UnionOld.codec().read(reader).equals(new UnionOld(11, expected, 99)); assert !reader.hasRemaining();
        }
    }
    private static void boundedMalformed() {
        for (var order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            var writer = new CdrWriter(1, order);
            OptionalNew.codec().write(writer, new OptionalNew(11, new NewAppendable(7, "not truncated"), 99));
            byte[] complete = writer.toByteArray();
            for (int size = 0; size < complete.length; size++) {
                byte[] prefix = Arrays.copyOf(complete, size);
                Checks.rejects(IllegalArgumentException.class, () -> OptionalNew.codec().read(new CdrReader(prefix)));
            }
        }
    }
    private static void evolution(DomainParticipant p, Publisher pub, Subscriber sub) throws Exception {
        try (var newer = p.createTopic("X1MutableEvolution", NewMutable.class);
             var older = p.createTopic("X1MutableEvolution", OldMutable.class);
             var writer = pub.createDataWriter(newer); var reader = sub.createDataReader(older)) {
            Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty());
            writer.write(new NewMutable(77, 8, "unknown optional"));
            writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            assert reader.select().take(null).get(0).getData().equals(new OldMutable(8, 77));
        }
        try (var older = p.createTopic("X1MutableDefaults", OldMutable.class);
             var newer = p.createTopic("X1MutableDefaults", NewMutable.class);
             var writer = pub.createDataWriter(older); var reader = sub.createDataReader(newer)) {
            Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty());
            writer.write(new OldMutable(8, 77)); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            assert reader.select().take(null).get(0).getData().equals(new NewMutable(77, 8, null));
        }
        try (var newer = p.createTopic("X1AppendableEvolution", StockXcdr1.Extended.class);
             var older = p.createTopic("X1AppendableEvolution", StockXcdr1.Original.class);
             var writer = pub.createDataWriter(newer); var reader = sub.createDataReader(older)) {
            Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty());
            writer.write(new StockXcdr1.Extended(8, "trailing field")); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            assert reader.select().take(null).get(0).getData().equals(new StockXcdr1.Original(8));
        }
        try (var older = p.createTopic("X1AppendableDefaults", StockXcdr1.Original.class);
             var newer = p.createTopic("X1AppendableDefaults", StockXcdr1.Extended.class);
             var writer = pub.createDataWriter(older); var reader = sub.createDataReader(newer)) {
            Checks.await(() -> !writer.getMatchedSubscriptions().isEmpty());
            writer.write(new StockXcdr1.Original(8)); writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            assert reader.select().take(null).get(0).getData().equals(new StockXcdr1.Extended(8, ""));
        }
    }
    private static <T> void rejectType(DomainParticipant p, Publisher pub, Subscriber sub, Class<T> type) {
        try (var topic = p.createTopic("X1Unsupported" + type.getSimpleName(), type)) {
            Checks.rejects(IllegalArgumentException.class, () -> pub.createDataWriter(topic));
            Checks.rejects(IllegalArgumentException.class, () -> sub.createDataReader(topic));
        }
    }
    private static void rejectAmbiguous(DomainParticipant p, Publisher pub, Subscriber sub) {
        rejectType(p, pub, sub, UnsafeMiddle.class);
        rejectType(p, pub, sub, UnsafeSequence.class);
        rejectType(p, pub, sub, UnsafeArray.class);
    }
    public static void main(String[] args) throws Exception {
        vectors(); nestedVectors(); boundedMalformed();
        var env = ServiceEnvironment.createInstance(Xcdr1Test.class.getClassLoader());
        var pf = PolicyFactory.getPolicyFactory(env);
        var xcdr1 = pf.Representation().withValue((short)0);
        Process peer = null;
        try (var p = DomainParticipantFactory.getInstance(env).createParticipant(87);
             var pub = p.createPublisher(); var sub = p.createSubscriber()) {
            pub.setDefaultDataWriterQos(pub.getDefaultDataWriterQos().withPolicies(xcdr1, pf.Reliability().withReliable(), pf.History().withKeepAll()));
            sub.setDefaultDataReaderQos(sub.getDefaultDataReaderQos().withPolicies(xcdr1, pf.Reliability().withReliable(), pf.History().withKeepAll()));
            evolution(p, pub, sub);
            peer = new ProcessBuilder(args[0], "7", "1", ",OptionalBeforeKey,Aligned,Nested,").inheritIO().start();
            echo(p, pub, sub, OptionalBeforeKey.class, List.of(new OptionalBeforeKey(null, 1), new OptionalBeforeKey(Long.MIN_VALUE, 2), new OptionalBeforeKey(Long.MAX_VALUE, 3)));
            echo(p, pub, sub, Aligned.class, List.of(new Aligned(1, 17, null, 2), new Aligned(2, Long.MIN_VALUE, "🌍".repeat(18000), 3)));
            echo(p, pub, sub, Nested.class, List.of(new Nested(1, new Aligned(2, 3, null, 4), new OptionalBeforeKey(null, 5), 6),
                new Nested(7, new Aligned(8, Long.MAX_VALUE, "nested", 9), new OptionalBeforeKey(10L, 11), 12)));
            assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
            rejectAmbiguous(p, pub, sub);
            rejectType(p, pub, sub, EnumKeys.class);
            rejectType(p, pub, sub, WrappedNew.class);
            rejectType(p, pub, sub, UnionNew.class);
            rejectType(p, pub, sub, CollectionNew.class);
            // A reader admitting both representations receives XCDR1 and XCDR2
            // writers using the same key, without splitting the native instance.
            try (var t = p.createTopic("MixedRepresentation", Aligned.class);
                 var one = pub.createDataWriter(t);
                 var two = pub.createDataWriter(t, pub.getDefaultDataWriterQos().withPolicy(pf.Representation().withValue((short)2)));
                 var reader = sub.createDataReader(t, sub.getDefaultDataReaderQos().withPolicy(pf.Representation().withValue((short)0, (short)2)))) {
                var a = new Aligned(9, 1, null, 0); var b = new Aligned(9, 2, "two", 0);
                one.write(a); two.write(b); one.waitForAcknowledgments(5, TimeUnit.SECONDS); two.waitForAcknowledgments(5, TimeUnit.SECONDS);
                var samples = reader.select().take(null);
                assert samples.size() == 2 && samples.get(0).getInstanceHandle().equals(samples.get(1).getInstanceHandle());
                var key = one.lookupInstance(a);
                assert one.getKeyValue(key).get_id() == 9;
                var holder = new Aligned(0, 123, "retained", 4);
                one.getKeyValue(holder, key); assert holder.get_id() == 9 && holder.get_value() == 123 && holder.get_note().equals("retained");
                one.dispose(key); one.waitForAcknowledgments(5, TimeUnit.SECONDS);
                assert reader.select().take(null).stream().anyMatch(s -> s.getData() == null && s.getInstanceState() == InstanceState.NOT_ALIVE_DISPOSED);
            }
        } finally { if (peer != null && peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); } }
        System.out.println("XCDR1: unmodified-core optional/mutable interoperability, top-level appendable evolution, malformed vectors, representation restrictions and mixed-representation keys passed");
    }
}
