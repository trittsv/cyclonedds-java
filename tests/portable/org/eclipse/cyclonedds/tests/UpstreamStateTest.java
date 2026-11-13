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
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

/** Exhaustive mask oracle for the upstream Condition/DataReaderSelector scenarios. */
public final class UpstreamStateTest {
    private record Expected(int key, SampleState sample, ViewState view, InstanceState instance) {}
    @SuppressWarnings("try") // Close the unused fixture writer before creating fresh selector caches.
    public static void main(String[] args) throws Exception {
        if (!Set.of("read-masks", "query-masks", "selector-read", "selector-take").contains(args[0]))
            throw new AssertionError("Unknown case: " + args[0]);
        var env = ServiceEnvironment.createInstance(UpstreamStateTest.class.getClassLoader());
        var pf = PolicyFactory.getPolicyFactory(env);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(95);
             var topic = participant.createTopic("UpstreamStates", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(
                 pf.History().withKeepAll(), pf.Reliability().withReliable(), pf.WriterDataLifecycle().withAutDisposeUnregisteredInstances(false)));
             var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(
                 pf.History().withKeepAll(), pf.Reliability().withReliable()))) {
            if (args[0].startsWith("selector-")) {
                writer.close();
                int checks = 0;
                for (int samples = 0; samples < 4; samples++) for (int views = 0; views < 4; views++) for (int instances = 0; instances < 8; instances++) {
                    var state = subscriber.createDataState();
                    for (var value : SampleState.values()) if ((samples & (1 << value.ordinal())) != 0) state.with(value);
                    for (var value : ViewState.values()) if ((views & (1 << value.ordinal())) != 0) state.with(value);
                    for (var value : InstanceState.values()) if ((instances & (1 << value.ordinal())) != 0) state.with(value);
                    // Each operation gets a fresh cache: read changes state and take consumes it.
                    try (var dw = publisher.createDataWriter(topic, writerQos(publisher, pf));
                         var dr = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(
                             pf.History().withKeepAll(), pf.Reliability().withReliable()))) {
                        for (int key = 0; key < 4; key++) dw.write(new Message(key, "key-" + key));
                        dw.waitForAcknowledgments(5, TimeUnit.SECONDS);
                        assert dr.select().instance(dr.lookupInstance(new Message(1, ""))).read(null).size() == 1;
                        dw.dispose(dw.lookupInstance(new Message(2, "")));
                        dw.unregisterInstance(dw.lookupInstance(new Message(3, "")));
                        dw.waitForAcknowledgments(5, TimeUnit.SECONDS);
                        var wanted = new HashSet<Integer>();
                        for (var e : expectedStates()) if (state.getSampleStates().contains(e.sample)
                                && state.getViewStates().contains(e.view) && state.getInstanceStates().contains(e.instance)) wanted.add(e.key);
                        var selection = dr.select().dataState(state);
                        var found = args[0].equals("selector-read") ? selection.read(null) : selection.take(null);
                        var keys = new HashSet<Integer>();
                        for (var sample : found) {
                            assert sample.getData() != null && keys.add(sample.getData().get_id());
                            assert state.getSampleStates().contains(sample.getSampleState());
                            assert state.getViewStates().contains(sample.getViewState());
                            assert state.getInstanceStates().contains(sample.getInstanceState());
                        }
                        assert keys.equals(wanted) : "Incorrect selector mask " + samples + "/" + views + "/" + instances + ": " + keys + " versus " + wanted;
                        assert dr.select().take(null).size() == (args[0].equals("selector-read") ? 4 : 4 - wanted.size());
                        checks++;
                    }
                }
                assert checks == 128;
                System.out.println(args[0] + ": all 128 selector masks passed");
                return;
            }
            for (int key = 0; key < 4; key++) writer.write(new Message(key, "key-" + key));
            writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            assert reader.select().instance(reader.lookupInstance(new Message(1, ""))).read(null).size() == 1;
            writer.dispose(writer.lookupInstance(new Message(2, "")));
            writer.unregisterInstance(writer.lookupInstance(new Message(3, "")));
            writer.waitForAcknowledgments(5, TimeUnit.SECONDS);
            var expected = expectedStates();
            int checks = 0;
            for (int samples = 0; samples < 4; samples++) for (int views = 0; views < 4; views++) for (int instances = 0; instances < 8; instances++) {
                var state = subscriber.createDataState();
                for (var value : SampleState.values()) if ((samples & (1 << value.ordinal())) != 0) state.with(value);
                for (var value : ViewState.values()) if ((views & (1 << value.ordinal())) != 0) state.with(value);
                for (var value : InstanceState.values()) if ((instances & (1 << value.ordinal())) != 0) state.with(value);
                // Conditions inspect the cache without marking samples READ or views NOT_NEW.
                // Test every mask, including incomplete masks that the native C API treats as ANY.
                int minimum = args[0].equals("query-masks") ? 2 : 0;
                boolean matches = expected.stream().anyMatch(e -> e.key >= minimum
                    && state.getSampleStates().contains(e.sample) && state.getViewStates().contains(e.view)
                    && state.getInstanceStates().contains(e.instance));
                try (var condition = args[0].equals("query-masks")
                        ? reader.createQueryCondition(state, "id >= %0", List.of("2"))
                        : reader.createReadCondition(state)) {
                    assert condition.getParent() == reader;
                    assert condition.getSampleStates().equals(state.getSampleStates());
                    assert condition.getViewStates().equals(state.getViewStates());
                    assert condition.getInstanceStates().equals(state.getInstanceStates());
                    assert condition.getTriggerValue() == matches : "Incorrect mask " + samples + "/" + views + "/" + instances;
                }
                checks++;
            }
            assert checks == 128;
            assert reader.select().take(null).size() == 4; // Neither condition variant consumed or read the cache.
        }
        System.out.println(args[0] + ": all 128 sample/view/instance masks passed");
    }
    private static org.omg.dds.pub.DataWriterQos writerQos(org.omg.dds.pub.Publisher publisher, PolicyFactory pf) {
        return publisher.getDefaultDataWriterQos().withPolicies(pf.History().withKeepAll(), pf.Reliability().withReliable(),
            pf.WriterDataLifecycle().withAutDisposeUnregisteredInstances(false));
    }
    private static List<Expected> expectedStates() {
        return List.of(new Expected(0, SampleState.NOT_READ, ViewState.NEW, InstanceState.ALIVE),
            new Expected(1, SampleState.READ, ViewState.NOT_NEW, InstanceState.ALIVE),
            new Expected(2, SampleState.NOT_READ, ViewState.NEW, InstanceState.NOT_ALIVE_DISPOSED),
            new Expected(3, SampleState.NOT_READ, ViewState.NEW, InstanceState.NOT_ALIVE_NO_WRITERS));
    }
}
