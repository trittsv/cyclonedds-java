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


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.omg.dds.core.WaitSet;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.DataReader;
import org.omg.dds.sub.ReadCondition;
import org.omg.dds.topic.ParticipantBuiltinTopicData;
import org.omg.dds.topic.PublicationBuiltinTopicData;
import org.omg.dds.topic.SubscriptionBuiltinTopicData;
import org.omg.dds.topic.TopicBuiltinTopicData;

/** Observe all four standard discovery topics, including invalid lifecycle samples. */
public final class BuiltinTopics {
    private BuiltinTopics() {}
    private static <T> int drain(String name, DataReader<T> reader, Function<T, String> describe) throws java.io.IOException {
        int count = 0;
        try (var samples = reader.take()) {
            while (samples.hasNext()) {
                var sample = samples.next();
                var data = sample.getData();
                System.out.printf("%s %s handle=%s %s%n", name, sample.getInstanceState(), sample.getInstanceHandle(),
                    data == null ? "lifecycle notification (no data)" : describe.apply(data));
                count++;
            }
        }
        return count;
    }
    public static void main(String[] args) throws Exception {
        int seconds = ExampleSupport.number(args, 0, 10, 1, 3600);
        int domain = ExampleSupport.number(args, 1, 0, 0, 230);
        var env = ExampleSupport.environment();
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(domain)) {
            var builtin = participant.getBuiltinSubscriber();
            try (DataReader<ParticipantBuiltinTopicData> participants = builtin.lookupDataReader("DCPSParticipant");
                 DataReader<TopicBuiltinTopicData> topics = builtin.lookupDataReader("DCPSTopic");
                 DataReader<PublicationBuiltinTopicData> publications = builtin.lookupDataReader("DCPSPublication");
                 DataReader<SubscriptionBuiltinTopicData> subscriptions = builtin.lookupDataReader("DCPSSubscription")) {
                var waitset = WaitSet.newWaitSet(env);
                List<ReadCondition<?>> conditions = new ArrayList<>();
                try {
                    for (var reader : List.of(participants, topics, publications, subscriptions)) {
                        var condition = reader.select().getCondition();
                        conditions.add(condition);
                        waitset.attachCondition(condition);
                    }
                    System.out.println("Observing discovery on domain " + domain + " for " + seconds + " seconds");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                    int events = 0;
                    while (System.nanoTime() < deadline) {
                        events += drain("DCPSParticipant", participants, data -> "key=" + data.getKey());
                        events += drain("DCPSTopic", topics, data -> "name=" + data.getName() + " type=" + data.getTypeName());
                        events += drain("DCPSPublication", publications, data -> "topic=" + data.getTopicName() + " type=" + data.getTypeName()
                            + " participant=" + data.getParticipantKey() + " reliability=" + data.getReliability().getKind());
                        events += drain("DCPSSubscription", subscriptions, data -> "topic=" + data.getTopicName() + " type=" + data.getTypeName()
                            + " participant=" + data.getParticipantKey() + " reliability=" + data.getReliability().getKind());
                        try { waitset.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                        catch (TimeoutException finished) { break; }
                    }
                    System.out.println("Discovery: " + events + " events");
                } finally {
                    ExampleSupport.close(waitset);
                    conditions.forEach(ReadCondition::close);
                }
            }
        }
    }
}
