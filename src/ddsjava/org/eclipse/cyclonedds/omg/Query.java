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
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.core.DdsException;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.sub.*;

/** Parameter changes rebuild the native condition, including already cached samples. */
final class Query<T> extends Conditions.Read<T> implements QueryCondition<T> {
    private final Object monitor;
    private final CycloneTypeSupport<?> type;
    private final DataStates states;
    private final int readerHandle;
    private volatile ContentFilter filter;
    private int slot;
    Query(ServiceEnvironment env, DataReader<T> reader, int readerHandle, DataStates state,
          CycloneTypeSupport<?> type, ContentFilter filter, Object monitor,
          java.util.function.Consumer<Conditions.Read<?>> onClose) {
        this(env, reader, readerHandle, state, type, filter, monitor, onClose, Native.queryCreate(readerHandle, state.mask(), filter));
    }
    private Query(ServiceEnvironment env, DataReader<T> reader, int readerHandle, DataStates state,
          CycloneTypeSupport<?> type, ContentFilter filter, Object monitor,
          java.util.function.Consumer<Conditions.Read<?>> onClose, long[] nativeQuery) {
        super(env, reader, (int) nativeQuery[0], state, onClose);
        this.readerHandle = readerHandle; this.type = type; this.states = state;
        this.filter = filter; this.monitor = monitor; this.slot = (int) nativeQuery[1];
    }
    void checkFailure() { filter.checkFailure(); }
    @Override public boolean getTriggerValue() { boolean value = super.getTriggerValue(); checkFailure(); return value; }
    public String getQueryExpression() { synchronized (monitor) { resource().handle(); return filter.expression; } }
    public List<String> getQueryParameters() { synchronized (monitor) { resource().handle(); return filter.parameters; } }
    public void setQueryParameters(List<String> parameters) {
        try { synchronized (monitor) {
            int old = resource().handle();
            ContentFilter replacement = new ContentFilter(type, filter.expression, parameters);
            long[] nativeQuery = Native.queryCreate(readerHandle, states.mask(), replacement);
            int oldSlot = slot;
            resource().replace(this, (int) nativeQuery[0]);
            filter = replacement; slot = (int) nativeQuery[1];
            Native.queryDelete(old, oldSlot);
        } } catch (DdsException error) { throw Errors.unchecked(getEnvironment(), error); }
    }
    @Override void delete() {
        if (resource().handle == 0) return;
        try { Native.queryDelete(resource().handle, slot); }
        catch (DdsException error) { throw Errors.unchecked(getEnvironment(), error); }
        resource().invalidate(this);
    }
}
