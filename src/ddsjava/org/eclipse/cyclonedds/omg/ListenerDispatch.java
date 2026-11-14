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

import java.lang.reflect.*;
import java.util.concurrent.*;
import org.omg.dds.core.*;
import org.omg.dds.core.status.Status;
import org.omg.dds.domain.DomainParticipantListener;

/** Native ingress never acquires an entity monitor or runs application code. */
final class ListenerDispatch {
    private final ExecutorService executor;
    ListenerDispatch(ListenerDispatch parent) {
        executor = parent == null ? Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CycloneDDS-Java-listeners"); thread.setDaemon(true); return thread;
        }) : parent.executor;
    }
    void shutdown() { executor.shutdown(); }
    record Target(Object listener, Object source, ServiceEnvironment env, int handle) {}
    private volatile Target[] targets = new Target[13];
    void configure(Target[] value) {
        Target[] replacement = value.clone(), previous = targets;
        for (int i = 0; i < replacement.length; i++) {
            Target old = previous[i], next = replacement[i];
            if (old != null && next != null && old.listener() == next.listener() && old.source() == next.source()
                    && old.env() == next.env() && old.handle() == next.handle()) replacement[i] = old;
        }
        targets = replacement;
    }
    public void onEvent(int kind, long[] values) {
        Target target = targets[kind];
        if (target == null) return;
        executor.execute(() -> {
            if (targets[kind] != target) return; // Listener replacement or closure cancels queued delivery.
            try {
                Status status = StatusValues.create(target.env(), kind, values);
                var event = Events.create(kind, target.source(), status);
                if (kind == 7) {
                    try { org.eclipse.cyclonedds.internal.Native.takeStatus(target.handle(), 1 << 7); }
                    catch (org.eclipse.cyclonedds.core.DdsException closed) { if (closed.code() == -9) return; throw closed; }
                }
                String name = Conditions.STATUS_TYPES.get(kind).getSimpleName().replace("Status", "Event");
                Class<?> eventType = Class.forName("org.omg.dds.core.event." + name);
                Method method = null;
                for (Class<?> api : new Class<?>[]{DomainParticipantListener.class, org.omg.dds.pub.PublisherListener.class,
                        org.omg.dds.sub.SubscriberListener.class, org.omg.dds.topic.TopicListener.class,
                        org.omg.dds.pub.DataWriterListener.class, org.omg.dds.sub.DataReaderListener.class})
                    if (api.isInstance(target.listener())) {
                        method = api.getMethod("on" + name.replace("Event", ""), eventType); break;
                    }
                if (method == null) throw new IllegalArgumentException("Invalid DDS listener");
                method.invoke(target.listener(), event);
            } catch (InvocationTargetException e) { report(e.getCause()); }
            catch (ReflectiveOperationException | RuntimeException e) { report(e); }
        });
    }
    private static void report(Throwable error) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, error);
    }
}
