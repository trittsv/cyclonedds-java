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

package org.eclipse.cyclonedds.pub;

import java.util.Objects;
import org.eclipse.cyclonedds.core.Entity;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.topic.TypeSupport;

public final class DataWriter<T> extends Entity {
    private final TypeSupport.Codec<T> codec;
    public DataWriter(DomainParticipant participant, int handle, TypeSupport.Codec<T> codec) {
        super(participant, handle);
        this.codec = codec;
    }
    public void write(T value) {
        synchronized (participant) {
            requireOpen();
            CdrWriter cdr = new CdrWriter();
            codec.write(cdr, Objects.requireNonNull(value));
            Native.write(handle, cdr.toByteArray());
        }
    }
    public int matchedSubscriptions() {
        synchronized (participant) { requireOpen(); return Native.matched(handle, true); }
    }
}
