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
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;
import org.eclipse.cyclonedds.internal.WireSample;
import org.eclipse.cyclonedds.topic.TypeSupport;
import org.omg.dds.core.*;
import org.omg.dds.sub.*;

final class Samples {
    private Samples() {}
    static final class Data<T> implements Sample<T> {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final transient TypeSupport.Codec<T> codec;
        @SuppressWarnings("serial") // Generated topic types implement Serializable.
        private T value;
        private WireSample info;
        Data(ServiceEnvironment env, TypeSupport.Codec<T> codec, T value, WireSample info) {
            this.env = env; this.codec = codec; this.value = value; this.info = info;
        }
        public ServiceEnvironment getEnvironment() { return env; }
        boolean ownedBy(ServiceEnvironment owner, TypeSupport.Codec<?> expected) { return env == owner && codec == expected; }
        void replace(Data<T> sample) { value = sample.value; info = sample.info; }
        public T getData() { return value; }
        public SampleState getSampleState() { return info.sampleState() == 1 ? SampleState.READ : SampleState.NOT_READ; }
        public ViewState getViewState() { return info.viewState() == 4 ? ViewState.NEW : ViewState.NOT_NEW; }
        public InstanceState getInstanceState() {
            return switch (info.instanceState()) {
                case 16 -> InstanceState.ALIVE;
                case 32 -> InstanceState.NOT_ALIVE_DISPOSED;
                case 64 -> InstanceState.NOT_ALIVE_NO_WRITERS;
                default -> throw new IllegalStateException("Unknown native instance state");
            };
        }
        public Time getSourceTimestamp() { return new Values.DdsTime(env, info.sourceTimestamp(), TimeUnit.NANOSECONDS).immutableCopy(); }
        public ModifiableInstanceHandle getInstanceHandle() { return new Values.Handle(env, info.instanceHandle()); }
        public ModifiableInstanceHandle getPublicationHandle() { return new Values.Handle(env, info.publicationHandle()); }
        public int getDisposedGenerationCount() { return info.disposedGenerationCount(); }
        public int getNoWritersGenerationCount() { return info.noWritersGenerationCount(); }
        public int getSampleRank() { return info.sampleRank(); }
        public int getGenerationRank() { return info.generationRank(); }
        public int getAbsoluteGenerationRank() { return info.absoluteGenerationRank(); }
        @SuppressWarnings("unchecked")
        @Override public Sample<T> clone() {
            T copy = null;
            if (Discovery.isData(value)) copy = (T)Discovery.copy(value);
            else if (value != null) {
                var writer = new CdrWriter(); codec.write(writer, value); copy = codec.read(new CdrReader(writer.toByteArray()));
            }
            return new Data<>(env, codec, copy, info);
        }
    }
    static final class Loan<T> implements Sample.Iterator<T> {
        private final ListIterator<Sample<T>> iterator;
        private final Runnable release;
        private boolean closed;
        Loan(List<Sample<T>> values, Runnable release) { iterator = values.listIterator(); this.release = release; }
        private void requireOpen() { if (closed) throw new IllegalStateException("Sample iterator is closed"); }
        public boolean hasNext() { requireOpen(); return iterator.hasNext(); }
        public boolean hasPrevious() { requireOpen(); return iterator.hasPrevious(); }
        public Sample<T> next() { requireOpen(); return iterator.next(); }
        public Sample<T> previous() { requireOpen(); return iterator.previous(); }
        public int nextIndex() { requireOpen(); return iterator.nextIndex(); }
        public int previousIndex() { requireOpen(); return iterator.previousIndex(); }
        public void remove() { throw new UnsupportedOperationException("Read-only sample iterator"); }
        public void set(Sample<T> value) { throw new UnsupportedOperationException("Read-only sample iterator"); }
        public void add(Sample<T> value) { throw new UnsupportedOperationException("Read-only sample iterator"); }
        public void close() { if (!closed) { closed = true; release.run(); } }
    }
}
