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
import java.util.concurrent.*;
import org.eclipse.cyclonedds.core.DdsException;
import org.eclipse.cyclonedds.internal.Native;
import org.omg.dds.core.*;

/** Environment-wide native waitset; no participant lock is held during a wait. */
final class BlockingWaitSet extends WaitSet implements AutoCloseable {
    private final ServiceEnvironment env;
    private final int handle;
    private final Cleaner.Cleanable cleanup;
    private static final class Attachment {
        final Conditions.Resource resource;
        int handle;
        Attachment(Conditions.Resource resource, int handle) { this.resource = resource; this.handle = handle; }
    }
    private final Map<Condition, Attachment> conditions = new LinkedHashMap<>();
    private boolean closed, waiting, deleted;
    BlockingWaitSet(ServiceEnvironment env) {
        this.env = env;
        try { handle = Native.waitset(); } catch (DdsException e) { throw Errors.unchecked(env, e); }
        cleanup = Conditions.CLEANER.register(this, new Conditions.Cleanup(handle));
    }
    public ServiceEnvironment getEnvironment() { return env; }
    private void requireOpen() { if (closed) throw new Errors.Closed(env); }
    public synchronized void attachCondition(Condition condition) {
        requireOpen();
        if (!(condition instanceof Conditions.Backed backed) || condition.getEnvironment() != env)
            throw new IllegalArgumentException("Foreign condition");
        if (conditions.containsKey(condition)) return;
        var resource = backed.resource();
        try {
            int nativeCondition = resource.handle();
            Native.attach(handle, nativeCondition, true);
            conditions.put(condition, new Attachment(resource, nativeCondition)); resource.watch(this);
            if (resource.handle != 0 && resource.handle != nativeCondition) replaced(condition, nativeCondition, resource.handle);
            // Close may race between native attachment and registration.
            if (resource.handle == 0) { deleted(condition); throw new Errors.Closed(env); }
            Native.waitsetTrigger(handle, true);
        } catch (DdsException e) { throw Errors.unchecked(env, e); }
    }
    public synchronized void detachCondition(Condition condition) {
        requireOpen();
        var attachment = conditions.get(condition);
        if (attachment == null) throw new Errors.Precondition(env, "Condition is not attached");
        try { Native.attach(handle, attachment.handle, false); }
        catch (DdsException e) { if (attachment.resource.handle != 0 && e.code() != -3 && e.code() != -4 && e.code() != -9) throw Errors.unchecked(env, e); }
        conditions.remove(condition); attachment.resource.unwatch(this);
        Native.waitsetTrigger(handle, true);
    }
    synchronized void replaced(Condition condition, int previous, int replacement) {
        var attachment = conditions.get(condition);
        if (closed || attachment == null || attachment.handle == replacement) return;
        try { Native.attach(handle, attachment.handle, false); }
        catch (DdsException e) { if (e.code() != -4 && e.code() != -3 && e.code() != -9) throw Errors.unchecked(env, e); }
        Native.attach(handle, replacement, true); attachment.handle = replacement;
        Native.waitsetTrigger(handle, true);
    }
    synchronized void deleted(Condition condition) {
        if (conditions.remove(condition) != null && !closed) {
            if (waiting) deleted = true;
            Native.waitsetTrigger(handle, true);
        }
    }
    public synchronized Collection<Condition> getConditions() { requireOpen(); return List.copyOf(conditions.keySet()); }
    public void waitForConditions() { waitForConditions((Collection<Condition>) null); }
    public void waitForConditions(Collection<Condition> active) {
        try { waitForConditions(active, Long.MAX_VALUE, TimeUnit.NANOSECONDS); }
        catch (TimeoutException impossible) { throw new AssertionError(impossible); }
    }
    public void waitForConditions(Duration timeout) throws TimeoutException { waitForConditions(null, timeout); }
    public void waitForConditions(long timeout, TimeUnit unit) throws TimeoutException { waitForConditions(null, timeout, unit); }
    public void waitForConditions(Collection<Condition> active, Duration timeout) throws TimeoutException {
        Objects.requireNonNull(timeout, "Timeout");
        waitForConditions(active, timeout.getDuration(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
    }
    public void waitForConditions(Collection<Condition> active, long timeout, TimeUnit unit) throws TimeoutException {
        if (timeout < 0) throw new IllegalArgumentException("Negative timeout");
        long nanos = Values.nanos(timeout, Objects.requireNonNull(unit));
        long start = System.nanoTime();
        synchronized (this) {
            requireOpen();
            if (waiting) throw new Errors.Precondition(env, "Only one thread may wait on a waitset");
            if (active != null) active.clear();
            waiting = true; deleted = false;
        }
        try {
            while (true) {
                long remaining;
                synchronized (this) {
                    requireOpen();
                    if (deleted) throw new Errors.Closed(env);
                    Native.waitsetTrigger(handle, false);
                    List<Condition> triggered = new ArrayList<>();
                    for (Condition condition : conditions.keySet()) if (condition.getTriggerValue()) triggered.add(condition);
                    if (!triggered.isEmpty()) { if (active != null) active.addAll(triggered); return; }
                    remaining = nanos == Long.MAX_VALUE ? nanos : nanos - (System.nanoTime() - start);
                    if (remaining <= 0) throw new TimeoutException("No attached condition triggered before the timeout");
                }
                Native.await(handle, remaining);
            }
        } catch (DdsException e) { throw Errors.unchecked(env, e); }
        finally { synchronized (this) { waiting = false; } Reference.reachabilityFence(this); }
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var attachment : conditions.values()) attachment.resource.unwatch(this);
        conditions.clear(); cleanup.clean();
    }
}
