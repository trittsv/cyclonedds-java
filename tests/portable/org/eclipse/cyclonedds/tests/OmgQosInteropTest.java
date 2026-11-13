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
import org.omg.dds.core.policy.*;
import org.omg.dds.domain.*;

/** An independent C participant verifies actual discovery values and live updates. */
public final class OmgQosInteropTest {
    private OmgQosInteropTest() {}
    private static Message next(ServiceEnvironment env, org.omg.dds.sub.DataReader<Message> reader) throws Exception {
        var ws = WaitSet.newWaitSet(env);
        try (var condition = reader.select().getCondition()) {
            ws.attachCondition(condition);
            ws.waitForConditions(8, TimeUnit.SECONDS);
            var values = reader.select().take(null);
            assert values.size() == 1;
            return values.get(0).getData();
        } finally { ((AutoCloseable) ws).close(); }
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgQosInteropTest.class.getClassLoader());
        var factory = DomainParticipantFactory.getInstance(env); var policies = PolicyFactory.getPolicyFactory(env);
        var order = policies.DestinationOrder().withSourceTimestamp();
        var reliable = policies.Reliability().withReliable();
        byte[] initial = {1, 0, (byte) 255};
        var user = policies.UserData().withValue(initial, 0, 3);
        initial[0] = 42; user.getValue()[0] = 43;
        assert user.getValue()[0] == 1;
        assert user.equals(policies.UserData().withValue(new byte[]{1, 0, (byte) 255}, 0, 3));
        assert user.hashCode() == policies.UserData().withValue(new byte[]{1, 0, (byte) 255}, 0, 3).hashCode();
        Process peer = null;
        try (var p = factory.createParticipant(91, factory.getDefaultParticipantQos().withPolicy(
                policies.UserData().withValue(new byte[]{4, 0, (byte) 252}, 0, 3)), null, null);
             var topic = p.createTopic("QosMetadata", Message.class, p.getDefaultTopicQos().withPolicy(
                p.getDefaultTopicQos().getTopicData().withValue(new byte[]{3, 0, (byte) 253}, 0, 3)), null, null);
             var reply = p.createTopic("QosMetadataReply", Message.class);
             var pub = p.createPublisher(p.getDefaultPublisherQos().withPolicy(policies.GroupData().withValue(new byte[]{2, 0, (byte) 254}, 0, 3)), null, null);
             var sub = p.createSubscriber();
             var writer = pub.createDataWriter(topic, pub.getDefaultDataWriterQos().withPolicies(user, reliable, order));
             var reader = sub.createDataReader(reply, sub.getDefaultDataReaderQos().withPolicies(reliable, order))) {
            assert p.getQos().getUserData().getValue()[0] == 4;
            peer = new ProcessBuilder(args[0]).inheritIO().start();
            assert next(env, reader).get_text().equals("metadata verified");
            writer.setQos(writer.getQos().withPolicy(policies.UserData().withValue(new byte[]{8, 0, (byte) 255}, 0, 3)));
            pub.setQos(pub.getQos().withPolicy(policies.GroupData().withValue(new byte[]{9, 0, (byte) 254}, 0, 3)));
            assert next(env, reader).get_text().equals("updates verified");
            assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
            p.setQos(p.getQos().withPolicy(user));
            assert p.getQos().getUserData().equals(user);
        } finally { if (peer != null && peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); } }
        System.out.println("QoS discovery: binary user/group/topic data and enabled writer/publisher updates verified by C");
    }
}
