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

import ConsumerTypes.Event;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import org.omg.dds.core.status.PublicationMatchedStatus;
import org.omg.dds.domain.DomainParticipantFactory;

/** A downstream application compiled without the provider JAR or a C compiler. */
public final class Consumer {
    private Consumer() {}
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(Consumer.class.getClassLoader());
        var ws = WaitSet.newWaitSet(env);
        try (var participant = DomainParticipantFactory.getInstance(env).createParticipant(84);
             var topic = participant.createTopic("InstalledJavaConsumer", Event.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic); var reader = subscriber.createDataReader(topic);
             var data = reader.select().getCondition()) {
            var matched = writer.getStatusCondition(); matched.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
            ws.attachCondition(matched);
            if (writer.getPublicationMatchedStatus().getCurrentCount() == 0) ws.waitForConditions(5, TimeUnit.SECONDS);
            ws.detachCondition(matched); ws.attachCondition(data);
            writer.write(new Event(42, "Installed Java-only consumer"));
            ws.waitForConditions(5, TimeUnit.SECONDS);
            var samples = reader.select().take(null);
            assert samples.size() == 1 && samples.get(0).getData().get_id() == 42;
            assert samples.get(0).getData().get_payload().equals("Installed Java-only consumer");
        } finally { if (ws instanceof AutoCloseable resource) resource.close(); }
        System.out.println("Installed Java consumer passed");
    }
}
