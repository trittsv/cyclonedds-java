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

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.cyclonedds.core.DdsException;
import org.eclipse.cyclonedds.internal.Native;
import org.omg.dds.core.*;
import org.omg.dds.core.status.*;
import org.omg.dds.sub.*;

final class Conditions {
    private Conditions() {}
    static final Cleaner CLEANER = Cleaner.create();
    interface Backed extends Condition { Resource resource(); }
    static final class Cleanup implements Runnable {
        private int handle;
        Cleanup(int handle) { this.handle = handle; }
        public synchronized void run() {
            if (handle != 0) {
                try { Native.delete(handle); } catch (DdsException ignored) { /* Already deleted by an owner. */ }
                handle = 0;
            }
        }
    }
    static final class Resource {
        final ServiceEnvironment env;
        volatile int handle;
        final boolean owned;
        final CopyOnWriteArrayList<WeakReference<BlockingWaitSet>> waitsets = new CopyOnWriteArrayList<>();
        Resource(ServiceEnvironment env, int handle, boolean owned) { this.env = env; this.handle = handle; this.owned = owned; }
        int handle() { int value = handle; if (value == 0) throw new Errors.Closed(env); return value; }
        boolean triggered() {
            try { return Native.triggered(handle()); } catch (DdsException e) { throw Errors.unchecked(env, e); }
        }
        void watch(BlockingWaitSet ws) {
            waitsets.removeIf(ref -> ref.get() == null);
            if (waitsets.stream().noneMatch(ref -> ref.get() == ws)) waitsets.add(new WeakReference<>(ws));
        }
        void unwatch(BlockingWaitSet ws) { waitsets.removeIf(ref -> ref.get() == null || ref.get() == ws); }
        void replace(Condition condition, int replacement) {
            int previous = handle(); handle = replacement;
            for (var reference : waitsets) {
                var ws = reference.get();
                if (ws != null) ws.replaced(condition, previous, replacement);
            }
        }
        void invalidate(Condition condition) {
            handle = 0;
            for (var reference : waitsets) {
                var ws = reference.get();
                if (ws != null) ws.deleted(condition);
            }
            waitsets.clear();
        }
    }
    static final class Guard extends GuardCondition implements Backed, AutoCloseable {
        private final Resource resource;
        private final Cleaner.Cleanable cleanup;
        Guard(ServiceEnvironment env) {
            try { resource = new Resource(env, Native.guard(), true); }
            catch (DdsException e) { throw Errors.unchecked(env, e); }
            cleanup = CLEANER.register(this, new Cleanup(resource.handle));
        }
        public Resource resource() { return resource; }
        public ServiceEnvironment getEnvironment() { return resource.env; }
        public boolean getTriggerValue() { return resource.triggered(); }
        public void setTriggerValue(boolean value) {
            try { Native.guardTrigger(resource.handle(), value); } catch (DdsException e) { throw Errors.unchecked(resource.env, e); }
            Reference.reachabilityFence(this);
        }
        public void close() { resource.invalidate(this); cleanup.clean(); }
    }
    static class Read<T> implements ReadCondition<T>, Backed {
        private final Resource resource;
        private final DataStates state;
        private final DataReader<T> reader;
        private final java.util.function.Consumer<Read<?>> onClose;
        Read(ServiceEnvironment env, DataReader<T> reader, int handle, DataStates state, java.util.function.Consumer<Read<?>> onClose) {
            resource = new Resource(env, handle, true); this.reader = reader; this.state = state; this.onClose = onClose;
        }
        public Resource resource() { return resource; }
        public ServiceEnvironment getEnvironment() { return resource.env; }
        public boolean getTriggerValue() { resource.handle(); return state.mask() != 0 && resource.triggered(); }
        public Set<SampleState> getSampleStates() { resource.handle(); return state.getSampleStates(); }
        public Set<ViewState> getViewStates() { resource.handle(); return state.getViewStates(); }
        public Set<InstanceState> getInstanceStates() { resource.handle(); return state.getInstanceStates(); }
        public DataReader<T> getParent() { resource.handle(); return reader; }
        public void close() { onClose.accept(this); }
        void delete() {
            if (resource.handle == 0) return;
            try { Native.delete(resource.handle); }
            catch (DdsException e) { if (e.code() != -9) throw Errors.unchecked(resource.env, e); }
            resource.invalidate(this);
        }
    }
    static final List<Class<? extends Status>> STATUS_TYPES = List.of(
        InconsistentTopicStatus.class, OfferedDeadlineMissedStatus.class, RequestedDeadlineMissedStatus.class,
        OfferedIncompatibleQosStatus.class, RequestedIncompatibleQosStatus.class, SampleLostStatus.class,
        SampleRejectedStatus.class, DataOnReadersStatus.class, DataAvailableStatus.class, LivelinessLostStatus.class,
        LivelinessChangedStatus.class, PublicationMatchedStatus.class, SubscriptionMatchedStatus.class);
    static Set<Class<? extends Status>> statuses(int mask) {
        Set<Class<? extends Status>> result = new LinkedHashSet<>();
        for (int i = 0; i < STATUS_TYPES.size(); i++) if ((mask & (1 << i)) != 0) result.add(STATUS_TYPES.get(i));
        return Collections.unmodifiableSet(result);
    }
    static final class EntityStatus<E extends Entity<?, ?>> implements StatusCondition<E>, Backed {
        private final Resource resource;
        private final E parent;
        private final int legalMask;
        EntityStatus(ServiceEnvironment env, int handle, E parent, int legalMask) {
            resource = new Resource(env, handle, false); this.parent = parent; this.legalMask = legalMask;
            Native.statusMask(handle, legalMask, true);
        }
        public Resource resource() { return resource; }
        public ServiceEnvironment getEnvironment() { return resource.env; }
        public boolean getTriggerValue() { return resource.triggered(); }
        public E getParent() { resource.handle(); return parent; }
        public Set<Class<? extends Status>> getEnabledStatuses() {
            try { return statuses(Native.statusMask(resource.handle(), 0, false)); }
            catch (DdsException e) { throw Errors.unchecked(resource.env, e); }
        }
        public void setEnabledStatuses(Collection<Class<? extends Status>> statuses) {
            int mask = statuses == null ? legalMask : 0;
            if (statuses != null) for (var status : statuses) {
                int index = STATUS_TYPES.indexOf(Objects.requireNonNull(status));
                if (index < 0 || (legalMask & (1 << index)) == 0) throw new IllegalArgumentException("Status is not applicable to this entity");
                mask |= 1 << index;
            }
            try { Native.statusMask(resource.handle(), mask, true); }
            catch (DdsException e) { throw Errors.unchecked(resource.env, e); }
        }
        void invalidate() { resource.invalidate(this); }
    }
    static final class Publication extends PublicationMatchedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] values;
        Publication(ServiceEnvironment env, long[] values) { this.env = env; this.values = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) values[0]; }
        public int getTotalCountChange() { return (int) values[1]; }
        public int getCurrentCount() { return (int) values[2]; }
        public int getCurrentCountChange() { return (int) values[3]; }
        public InstanceHandle getLastSubscriptionHandle() { return new Values.Handle(env, values[4]).immutableCopy(); }
    }
    static final class Subscription extends SubscriptionMatchedStatus {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long[] values;
        Subscription(ServiceEnvironment env, long[] values) { this.env = env; this.values = values.clone(); }
        public ServiceEnvironment getEnvironment() { return env; }
        public int getTotalCount() { return (int) values[0]; }
        public int getTotalCountChange() { return (int) values[1]; }
        public int getCurrentCount() { return (int) values[2]; }
        public int getCurrentCountChange() { return (int) values[3]; }
        public InstanceHandle getLastPublicationHandle() { return new Values.Handle(env, values[4]).immutableCopy(); }
    }
}
