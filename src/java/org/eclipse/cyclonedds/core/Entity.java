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

package org.eclipse.cyclonedds.core;

import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.internal.Native;

/** Explicit native ownership. Closing a participant also invalidates its children. */
public abstract class Entity implements AutoCloseable {
    protected final DomainParticipant participant;
    protected int handle;

    protected Entity(DomainParticipant participant, int handle) {
        this.participant = participant;
        this.handle = handle;
    }

    protected final void requireOpen() {
        participant.requireParticipantOpen();
        if (handle == 0) throw new IllegalStateException("Entity is closed");
    }

    @Override public final void close() {
        synchronized (participant) {
            if (handle == 0) return;
            if (participant.isOpen()) Native.delete(handle);
            handle = 0;
        }
    }
}
