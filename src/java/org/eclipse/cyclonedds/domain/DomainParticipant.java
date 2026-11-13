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

package org.eclipse.cyclonedds.domain;

import java.util.Objects;
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.topic.Topic;
import org.eclipse.cyclonedds.topic.TypeSupport;

/** Owns a native participant and its entities. Type support is supplied by generated Java. */
public final class DomainParticipant implements AutoCloseable {
    private int handle;

    public DomainParticipant() { this(0); }
    public DomainParticipant(int domain) {
        if (domain < 0) throw new IllegalArgumentException("domain must be nonnegative");
        handle = Native.participant(domain);
    }

    public synchronized boolean isOpen() { return handle != 0; }
    public synchronized void requireParticipantOpen() {
        if (!isOpen()) throw new IllegalStateException("Participant is closed");
    }

    public synchronized <T> Topic<T> createTopic(String name, TypeSupport<T> type) {
        requireParticipantOpen();
        Objects.requireNonNull(type);
        if (Objects.requireNonNull(name).isBlank() || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid topic name");
        int topic = Native.topicConfigured(handle, name, type.typeName(), type.hasKey(), type.allowedDataRepresentations(), type.typeInformation(), type.typeMapping(), null);
        return new Topic<>(this, topic, type);
    }

    /** Internal endpoint creation; caller holds this participant's monitor. */
    public synchronized int createEndpoint(int topic, boolean writer, org.eclipse.cyclonedds.core.Qos qos) {
        requireParticipantOpen();
        return Native.endpoint(handle, topic, writer, qos.reliable(), qos.historyDepth(), 2_000_000_000L);
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        Native.delete(handle);
        handle = 0;
    }
}
