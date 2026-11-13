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

package org.eclipse.cyclonedds.topic;

import java.util.Objects;
import org.eclipse.cyclonedds.core.Entity;
import org.eclipse.cyclonedds.core.Qos;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.pub.DataWriter;
import org.eclipse.cyclonedds.sub.DataReader;

public final class Topic<T> extends Entity {
    private final TypeSupport<T> type;
    public Topic(DomainParticipant participant, int handle, TypeSupport<T> type) {
        super(participant, handle);
        this.type = type;
    }
    public DataWriter<T> createWriter() { return createWriter(Qos.DEFAULT); }
    public DataReader<T> createReader() { return createReader(Qos.DEFAULT); }
    public DataWriter<T> createWriter(Qos qos) {
        synchronized (participant) {
            requireOpen();
            return new DataWriter<>(participant, participant.createEndpoint(handle, true, Objects.requireNonNull(qos)), type.codec());
        }
    }
    public DataReader<T> createReader(Qos qos) {
        synchronized (participant) {
            requireOpen();
            return new DataReader<>(participant, participant.createEndpoint(handle, false, Objects.requireNonNull(qos)), type.codec());
        }
    }
}
