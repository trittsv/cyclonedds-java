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

package org.eclipse.cyclonedds.omg;

import java.util.*;
import org.omg.dds.core.*;
import org.omg.dds.core.status.Status;
import org.omg.dds.domain.*;

final class ParticipantFactory extends DomainParticipantFactory {
    private final ServiceEnvironment env;
    private final List<DomainParticipant> participants = new ArrayList<>();
    private DomainParticipantQos defaults;
    private DomainParticipantFactoryQos qos;
    ParticipantFactory(ServiceEnvironment env) {
        this.env = env;
        defaults = Policies.defaults(env, DomainParticipantQos.class, false);
        qos = Policies.defaults(env, DomainParticipantFactoryQos.class, false);
    }
    public ServiceEnvironment getEnvironment() { return env; }
    public DomainParticipant createParticipant() { return createParticipant(0); }
    public synchronized DomainParticipant createParticipant(int domain) { return createParticipant(domain, defaults, null, null); }
    public synchronized DomainParticipant createParticipant(int domain, DomainParticipantQos value,
            DomainParticipantListener listener, Collection<Class<? extends Status>> statuses) {
        if (domain < 0) throw new IllegalArgumentException("Domain must be nonnegative");
        Entities.validateListenerArguments(listener, statuses);
        Policies.requireOwned(env, value);
        participants.removeIf(participant -> !Entities.isOpen(participant));
        DomainParticipant participant = Entities.participant(env, domain, value, listener, statuses);
        participants.add(participant);
        return participant;
    }
    public synchronized DomainParticipant lookupParticipant(int domain) {
        for (DomainParticipant participant : participants)
            if (Entities.isOpen(participant) && participant.getDomainId() == domain) { participant.retain(); return participant; }
        return null;
    }
    public synchronized DomainParticipantFactoryQos getQos() { return qos; }
    public synchronized void setQos(DomainParticipantFactoryQos value) { Policies.requireOwned(env, value); Policies.validate(value); qos = value; }
    public synchronized DomainParticipantQos getDefaultParticipantQos() { return defaults; }
    public synchronized void setDefaultParticipantQos(DomainParticipantQos value) { Policies.requireOwned(env, value); defaults = value; }
}
