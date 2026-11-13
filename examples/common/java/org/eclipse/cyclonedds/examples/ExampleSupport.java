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

package org.eclipse.cyclonedds.examples;


import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.core.WaitSet;
import org.omg.dds.core.status.PublicationMatchedStatus;
import org.omg.dds.pub.DataWriter;
import org.omg.dds.sub.DataReader;
import org.omg.dds.sub.ReadCondition;
import org.omg.dds.sub.Sample;

/** Shared argument parsing and bounded waits; applications use the OMG DDS API. */
public final class ExampleSupport {
    private ExampleSupport() {}
    public static ServiceEnvironment environment() {
        return ServiceEnvironment.createInstance(ExampleSupport.class.getClassLoader());
    }
    public static int number(String[] args, int index, int fallback, int min, int max) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value < min || value > max)
            throw new IllegalArgumentException("Argument " + (index + 1) + " must be in [" + min + ", " + max + "]");
        return value;
    }
    public static boolean role(String[] args, String first, String second, String usage) {
        if (args.length == 0 || !(args[0].equals(first) || args[0].equals(second)))
            throw new IllegalArgumentException("Usage: " + usage);
        return args[0].equals(first);
    }
    public static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(30); }
    public static void close(Object object) {
        // WaitSet and GuardCondition have no close method in the normative API.
        // This provider implements AutoCloseable for deterministic native cleanup.
        if (object instanceof AutoCloseable resource) {
            try { resource.close(); }
            catch (Exception failure) { throw new IllegalStateException("DDS cleanup failed", failure); }
        }
    }
    public static <T> void waitForReader(DataWriter<T> writer) throws TimeoutException {
        var waitset = WaitSet.newWaitSet(writer.getEnvironment());
        var matched = writer.getStatusCondition();
        matched.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
        waitset.attachCondition(matched);
        long deadline = deadline();
        try {
            while (writer.getPublicationMatchedStatus().getCurrentCount() == 0)
                waitset.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally { close(waitset); }
    }
    /** A read condition wakes the application without polling or consuming callback data. */
    public static final class ReadWait<T> implements AutoCloseable {
        private final DataReader<T> reader;
        private final WaitSet waitset;
        private final ReadCondition<T> condition;
        public ReadWait(DataReader<T> reader) {
            this.reader = reader;
            waitset = WaitSet.newWaitSet(reader.getEnvironment());
            condition = reader.select().getCondition();
            waitset.attachCondition(condition);
        }
        public List<Sample<T>> take(long deadline) throws TimeoutException {
            for (;;) {
                List<Sample<T>> samples = reader.select().take(null);
                if (!samples.isEmpty()) return samples;
                waitset.waitForConditions(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        }
        @Override public void close() {
            ExampleSupport.close(waitset);
            condition.close();
        }
    }
}
