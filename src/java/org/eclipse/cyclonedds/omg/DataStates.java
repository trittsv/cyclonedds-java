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
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.sub.*;

/** Java state sets translated to Cyclone's native sample/view/instance mask. */
final class DataStates implements Subscriber.DataState {
    private final ServiceEnvironment env;
    private final EnumSet<SampleState> samples = EnumSet.noneOf(SampleState.class);
    private final EnumSet<ViewState> views = EnumSet.noneOf(ViewState.class);
    private final EnumSet<InstanceState> instances = EnumSet.noneOf(InstanceState.class);
    private final boolean mutable;

    DataStates(ServiceEnvironment env) { this.env = env; mutable = true; }
    private DataStates(Subscriber.DataState source, boolean mutable) {
        env = source.getEnvironment(); this.mutable = mutable;
        samples.addAll(source.getSampleStates());
        views.addAll(source.getViewStates());
        instances.addAll(source.getInstanceStates());
    }
    static DataStates snapshot(ServiceEnvironment env, Subscriber.DataState source) {
        Objects.requireNonNull(source, "Data state");
        if (source.getEnvironment() != env) throw new IllegalArgumentException("Data state belongs to another environment");
        return new DataStates(source, false);
    }
    static DataStates any(ServiceEnvironment env) {
        return snapshot(env, new DataStates(env).withAnySampleState().withAnyViewState().withAnyInstanceState());
    }
    public ServiceEnvironment getEnvironment() { return env; }
    public Set<SampleState> getSampleStates() { return Collections.unmodifiableSet(samples.clone()); }
    public Set<ViewState> getViewStates() { return Collections.unmodifiableSet(views.clone()); }
    public Set<InstanceState> getInstanceStates() { return Collections.unmodifiableSet(instances.clone()); }
    private void requireMutable() { if (!mutable) throw new UnsupportedOperationException("Immutable data state"); }
    public DataStates with(SampleState state) { requireMutable(); samples.add(Objects.requireNonNull(state)); return this; }
    public DataStates with(ViewState state) { requireMutable(); views.add(Objects.requireNonNull(state)); return this; }
    public DataStates with(InstanceState state) { requireMutable(); instances.add(Objects.requireNonNull(state)); return this; }
    public DataStates withAnySampleState() { requireMutable(); samples.addAll(EnumSet.allOf(SampleState.class)); return this; }
    public DataStates withAnyViewState() { requireMutable(); views.addAll(EnumSet.allOf(ViewState.class)); return this; }
    public DataStates withAnyInstanceState() { requireMutable(); instances.addAll(EnumSet.allOf(InstanceState.class)); return this; }
    public DataStates withNotAliveInstanceStates() {
        return with(InstanceState.NOT_ALIVE_DISPOSED).with(InstanceState.NOT_ALIVE_NO_WRITERS);
    }
    public DataStates clone() { return new DataStates(this, true); }
    public boolean equals(Object other) {
        return other instanceof Subscriber.DataState state && samples.equals(state.getSampleStates())
                && views.equals(state.getViewStates()) && instances.equals(state.getInstanceStates());
    }
    public int hashCode() { return Objects.hash(samples, views, instances); }
    int mask() {
        // The C API interprets a missing component as ANY. An empty Java state
        // set must instead match nothing, so callers skip the native read.
        if (samples.isEmpty() || views.isEmpty() || instances.isEmpty()) return 0;
        int mask = 0;
        if (samples.contains(SampleState.READ)) mask |= 1;
        if (samples.contains(SampleState.NOT_READ)) mask |= 2;
        if (views.contains(ViewState.NEW)) mask |= 4;
        if (views.contains(ViewState.NOT_NEW)) mask |= 8;
        if (instances.contains(InstanceState.ALIVE)) mask |= 16;
        if (instances.contains(InstanceState.NOT_ALIVE_DISPOSED)) mask |= 32;
        if (instances.contains(InstanceState.NOT_ALIVE_NO_WRITERS)) mask |= 64;
        return mask;
    }
}
