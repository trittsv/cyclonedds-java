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

package org.eclipse.cyclonedds.sub;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.cyclonedds.core.Entity;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.internal.WireSample;
import org.eclipse.cyclonedds.topic.TypeSupport;

public final class DataReader<T> extends Entity {
    private final TypeSupport.Codec<T> codec;
    public DataReader(DomainParticipant participant, int handle, TypeSupport.Codec<T> codec) {
        super(participant, handle);
        this.codec = codec;
    }
    /** Removes up to maxSamples samples without blocking. */
    public List<Sample<T>> take(int maxSamples) {
        if (maxSamples < 1 || maxSamples > 65536)
            throw new IllegalArgumentException("maxSamples must be in [1, 65536]");
        synchronized (participant) {
            requireOpen();
            List<Sample<T>> result = new ArrayList<>();
            for (WireSample sample : Native.take(handle, maxSamples)) {
                T value = sample.validData() ? codec.read(new CdrReader(sample.cdr())) : null;
                result.add(new Sample<>(value, sample.validData(), sample.sourceTimestamp(),
                        sample.instanceHandle(), sample.instanceState()));
            }
            return List.copyOf(result);
        }
    }
    public int matchedPublications() {
        synchronized (participant) { requireOpen(); return Native.matched(handle, false); }
    }
}
