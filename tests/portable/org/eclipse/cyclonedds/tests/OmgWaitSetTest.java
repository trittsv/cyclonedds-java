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

package org.eclipse.cyclonedds.tests;

import HelloWorld.Message;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.omg.dds.core.*;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

public final class OmgWaitSetTest {
    private OmgWaitSetTest() {}
    interface Operation { void run() throws Exception; }
    private static void rejects(Class<? extends Throwable> expected, Operation operation) throws Exception {
        try { operation.run(); }
        catch (Throwable error) { if (expected.isInstance(error)) return; throw new AssertionError(error); }
        throw new AssertionError("Expected " + expected.getName());
    }
    private static void blocked(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Arrays.stream(thread.getStackTrace()).noneMatch(frame -> frame.getMethodName().equals("await") && frame.isNativeMethod())) {
            if (!thread.isAlive() || System.nanoTime() > deadline) throw new AssertionError("Thread did not reach native wait");
            Thread.sleep(1);
        }
    }
    private static Thread waiter(WaitSet guardWs, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> { try { guardWs.waitForConditions(5, TimeUnit.SECONDS); } catch (Throwable e) { failure.set(e); } });
        thread.start(); return thread;
    }
    @SuppressWarnings("try") // Explicit close verifies wakeup while the reader is still in its lexical scope.
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(OmgWaitSetTest.class.getClassLoader());
        var guardWs = WaitSet.newWaitSet(env);
        var guard = GuardCondition.newGuardCondition(env);
        var second = GuardCondition.newGuardCondition(env);
        try {
            assert !guard.getTriggerValue();
            guardWs.attachCondition(guard); guardWs.attachCondition(guard);
            assert guardWs.getConditions().size() == 1;
            rejects(TimeoutException.class, () -> guardWs.waitForConditions(0, TimeUnit.NANOSECONDS));
            guard.setTriggerValue(true);
            List<Condition> active = new ArrayList<>();
            guardWs.waitForConditions(active, 0, TimeUnit.NANOSECONDS);
            assert active.equals(List.of(guard));
            guard.setTriggerValue(false);
            var failure = new AtomicReference<Throwable>();
            var thread = waiter(guardWs, failure);
            blocked(thread);
            rejects(PreconditionNotMetException.class, () -> guardWs.waitForConditions(0, TimeUnit.NANOSECONDS));
            second.setTriggerValue(true); guardWs.attachCondition(second);
            thread.join(2000); assert !thread.isAlive() && failure.get() == null;
            guardWs.detachCondition(second);
            rejects(PreconditionNotMetException.class, () -> guardWs.detachCondition(second));
            thread = waiter(guardWs, failure); blocked(thread);
            ((AutoCloseable) guard).close();
            thread.join(2000); assert !thread.isAlive() && failure.get() instanceof AlreadyClosedException;
            assert guardWs.getConditions().isEmpty();
            failure.set(null); thread = waiter(guardWs, failure); blocked(thread);
            ((AutoCloseable) guardWs).close();
            thread.join(2000); assert !thread.isAlive() && failure.get() instanceof AlreadyClosedException;
        } finally {
            ((AutoCloseable) guardWs).close(); ((AutoCloseable) guard).close(); ((AutoCloseable) second).close();
        }
        var factory = DomainParticipantFactory.getInstance(env);
        try (var participant = factory.createParticipant(80);
             var topic = participant.createTopic("OmgWaits", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic); var reader = subscriber.createDataReader(topic)) {
            var ws = WaitSet.newWaitSet(env);
            try {
                var matched = writer.getStatusCondition();
                matched.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
                ws.attachCondition(matched);
                if (writer.getPublicationMatchedStatus().getCurrentCount() == 0) ws.waitForConditions(5, TimeUnit.SECONDS);
                assert writer.getPublicationMatchedStatus().getCurrentCount() == 1;
                assert writer.getPublicationMatchedStatus().getCurrentCountChange() == 0;
                assert !matched.getTriggerValue();
                ws.detachCondition(matched);
                var unread = subscriber.createDataState().with(SampleState.NOT_READ).withAnyViewState().withAnyInstanceState();
                try (var condition = reader.createReadCondition(unread)) {
                    ws.attachCondition(condition);
                    var failure = new AtomicReference<Throwable>();
                    var thread = waiter(ws, failure); blocked(thread);
                    writer.write(new Message(42, "Wake without polling"));
                    thread.join(2000); assert !thread.isAlive() && failure.get() == null;
                    assert condition.getTriggerValue();
                    assert reader.select().dataState(unread).read(null).size() == 1;
                    assert !condition.getTriggerValue();
                    ws.detachCondition(condition);
                }
                try (var empty = reader.createReadCondition(subscriber.createDataState())) {
                    ws.attachCondition(empty);
                    assert !empty.getTriggerValue();
                    final var emptyWs = ws;
                    rejects(TimeoutException.class, () -> emptyWs.waitForConditions(10, TimeUnit.MILLISECONDS));
                    ws.detachCondition(empty);
                }
                var condition = reader.createReadCondition(unread);
                ws.attachCondition(condition);
                var failure = new AtomicReference<Throwable>();
                var thread = waiter(ws, failure); blocked(thread);
                reader.close();
                thread.join(2000); assert !thread.isAlive() && failure.get() instanceof AlreadyClosedException;
                assert ws.getConditions().isEmpty();
                rejects(AlreadyClosedException.class, condition::getTriggerValue);
            } finally { ((AutoCloseable) ws).close(); }
        }
        System.out.println("OMG waitsets: native wakeup, guards, read/status conditions, timeouts, concurrent waits and shutdown passed");
    }
}
