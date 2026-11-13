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
import BindingTest.KeylessMutable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

/** Ports of ddscxx SampleInfo and Python reader buffer/instance regressions. */
public final class UpstreamReaderTest {
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(UpstreamReaderTest.class.getClassLoader());
        var pf = PolicyFactory.getPolicyFactory(env);
        boolean generations = args[0].startsWith("generations-");
        boolean autodispose = !args[0].contains("manual");
        boolean dispose = args[0].contains("dispose-only") || args[0].endsWith("both");
        boolean unregister = args[0].contains("unregister-only") || args[0].endsWith("both");
        var history = generations ? pf.History().withKeepLast(2) : pf.History().withKeepAll();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(94);
             var topic = participant.createTopic("UpstreamReader", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(
                 history, pf.Reliability().withReliable(), pf.WriterDataLifecycle().withAutDisposeUnregisteredInstances(autodispose)));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(history, pf.Reliability().withReliable()))) {
            if (generations) {
                for (int key = 0; key < 2; key++) for (int seq = 0; seq < 2; seq++) writer.write(new Message(key, "" + seq));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                var initial = reader.select().take(null);
                verifyRanks(initial, 0, 0);
                for (var sample : initial) {
                    if (dispose) writer.dispose(InstanceHandle.nilHandle(env), sample.getData());
                    if (unregister) writer.unregisterInstance(InstanceHandle.nilHandle(env), sample.getData());
                }
                for (int key = 0; key < 2; key++) for (int seq = 0; seq < 2; seq++) writer.write(new Message(key, "" + seq));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                // Same expectations as SampleInfo.cpp; also exercise the genuinely
                // manual-dispose+unregister variant (upstream accidentally enables autodispose).
                verifyRanks(reader.select().take(null), dispose || autodispose ? 1 : 0,
                    unregister ? (autodispose || dispose ? 2 : 1) : 0);
            } else switch (args[0]) {
            case "empty-buffer-resize":
                for (int size : new int[]{100, 200, 100}) {
                    assert reader.select().maxSamples(size).read(null).isEmpty();
                    assert reader.select().maxSamples(size).take(null).isEmpty();
                }
                break;
            case "invalid-read-count":
                for (int size : new int[]{-2, Integer.MIN_VALUE}) {
                    try { reader.select().maxSamples(size).read(); throw new AssertionError("Negative read count"); }
                    catch (IllegalArgumentException expected) { }
                    try { reader.select().maxSamples(size).take(); throw new AssertionError("Negative take count"); }
                    catch (IllegalArgumentException expected) { }
                }
                // Java uses the OMG sentinel -1 to request an unlimited result.
                assert reader.select().maxSamples(-1).read(null).isEmpty();
                break;
            case "keyless-mutable-dispose":
                try (var mutableTopic = participant.createTopic("KeylessMutableDispose", KeylessMutable.class);
                     var dw = publisher.createDataWriter(mutableTopic);
                     var dr = subscriber.createDataReader(mutableTopic)) {
                    var value = new KeylessMutable(42);
                    dw.write(value); dw.waitForAcknowledgments(5, TimeUnit.SECONDS);
                    var first = dr.select().take(null);
                    assert first.size() == 1 && first.get(0).getData().equals(value);
                    var key = dw.lookupInstance(value);
                    dw.dispose(key); dw.waitForAcknowledgments(5, TimeUnit.SECONDS);
                    var invalid = dr.select().take(null);
                    assert invalid.size() == 1 && invalid.get(0).getData() == null;
                    assert invalid.get(0).getInstanceState() == InstanceState.NOT_ALIVE_DISPOSED;
                    assert invalid.get(0).getInstanceHandle().equals(first.get(0).getInstanceHandle());
                    assert dr.getKeyValue(new KeylessMutable(), invalid.get(0).getInstanceHandle()).equals(new KeylessMutable());
                }
                break;
            case "read-next-many-instances": {
                // Python's original hopscotch regression reads each sample before
                // adding the next key; traversal below also tests the bulk case.
                Sample<Message> holder = null;
                for (int key = 0; key < 100; key++) {
                    writer.write(new Message(key, "next-" + key));
                    writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                    if (holder == null) {
                        var first = reader.select().maxSamples(1).read(null);
                        assert first.size() == 1; holder = first.get(0);
                    } else assert reader.readNextSample(holder);
                    assert holder.getData().get_id() == key && holder.getSampleState() == SampleState.NOT_READ;
                    assert !reader.readNextSample(holder);
                }
                break;
            }
            case "communication-read":
            case "communication-take":
            case "communication-order":
            case "communication-read-preserves":
            case "communication-take-consumes": {
                writer.write(new Message(1, "Hi1!"));
                boolean order = args[0].equals("communication-order");
                if (order) writer.write(new Message(1, "Hi2!"));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                var samples = args[0].equals("communication-take") ? reader.select().take(null) : reader.select().read(null);
                assert samples.size() == (order ? 2 : 1);
                assert samples.get(0).getData().equals(new Message(1, "Hi1!"));
                if (order) assert samples.get(1).getData().equals(new Message(1, "Hi2!"));
                if (args[0].equals("communication-read-preserves")) {
                    var again = reader.select().read(null);
                    assert again.size() == 1 && again.get(0).getData().equals(samples.get(0).getData());
                    assert again.get(0).getSampleState() == SampleState.READ;
                }
                if (args[0].equals("communication-take-consumes")) {
                    var taken = reader.select().take(null);
                    assert taken.size() == 1 && taken.get(0).getData().equals(samples.get(0).getData());
                    assert reader.select().read(null).isEmpty();
                }
                if (args[0].equals("communication-take")) assert reader.select().read(null).isEmpty();
                break;
            }
            case "buffer-resize":
                for (int batch : new int[]{100, 200, 100}) {
                    for (int i = 0; i < batch; i++) writer.write(new Message(i, "batch-" + batch + "-" + i));
                    writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                    var samples = reader.select().take(null);
                    assert samples.size() == batch : samples.size();
                    var keys = new HashSet<Integer>();
                    for (var sample : samples) {
                        assert keys.add(sample.getData().get_id());
                        assert sample.getData().get_text().equals("batch-" + batch + "-" + sample.getData().get_id());
                    }
                    assert reader.select().take(null).isEmpty();
                }
                break;
            case "many-instances": {
                for (int i = 0; i < 100; i++) writer.write(new Message(i, "instance-" + i));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                var keys = new HashSet<Integer>();
                var traversal = reader.select().nextInstance(true);
                for (;;) {
                    var samples = traversal.take(null);
                    if (samples.isEmpty()) break;
                    assert samples.size() == 1;
                    var sample = samples.get(0);
                    int key = sample.getData().get_id();
                    assert keys.add(key) && key >= 0 && key < 100;
                    assert reader.getKeyValue(new Message(), sample.getInstanceHandle()).get_id() == key;
                    traversal.instance(sample.getInstanceHandle());
                }
                assert keys.size() == 100 : keys.size();
                assert reader.select().take(null).isEmpty();
                break;
            }
            case "loan-snapshot": {
                writer.write(new Message(17, "original"));
                writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
                Sample<Message> snapshot;
                try (var loan = reader.read()) {
                    assert loan.hasNext();
                    var sample = loan.next(); snapshot = sample.clone();
                    assert snapshot != sample && snapshot.getData() != sample.getData();
                    sample.getData().set_text("changed in Java");
                    assert snapshot.getData().get_text().equals("original");
                    assert loan.hasPrevious() && loan.previous() == sample;
                    try { loan.remove(); throw new AssertionError("Mutable loan"); }
                    catch (UnsupportedOperationException expected) { /* read-only iterator */ }
                }
                assert snapshot.getData().get_text().equals("original");
                assert snapshot.getInstanceState() == InstanceState.ALIVE;
                try (var loan = reader.take()) {
                    assert loan.next().getData().get_text().equals("original");
                }
                break;
            }
            default: throw new AssertionError("Unknown case: " + args[0]);
            }
        }
        System.out.println(args[0] + " passed");
    }
    private static void verifyRanks(List<Sample<Message>> samples, int disposed, int noWriters) {
        assert samples.size() == 4 : samples.size();
        var counts = new HashMap<Integer, Integer>();
        for (var sample : samples) {
            assert sample.getData() != null;
            counts.merge(sample.getData().get_id(), 1, Integer::sum);
            assert sample.getDisposedGenerationCount() == disposed : "disposed=" + sample.getDisposedGenerationCount() + ", expected=" + disposed;
            assert sample.getNoWritersGenerationCount() == noWriters : "noWriters=" + sample.getNoWritersGenerationCount() + ", expected=" + noWriters;
            assert sample.getSampleRank() + Integer.parseInt(sample.getData().get_text()) == 1;
            assert sample.getGenerationRank() == 0 && sample.getAbsoluteGenerationRank() == 0;
            assert !sample.getInstanceHandle().isNil() && !sample.getPublicationHandle().isNil();
        }
        assert counts.equals(Map.of(0, 2, 1, 2)) : counts;
    }
}
