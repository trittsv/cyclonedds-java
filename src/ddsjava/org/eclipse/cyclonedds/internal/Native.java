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

package org.eclipse.cyclonedds.internal;

/** Internal JNI boundary. Entity mutation uses the participant monitor; blocking
 * operations and waitables rely on native DDS entity pinning for their lifetime. */
public final class Native {
    static { NativeLoader.load(); }
    private Native() {}
    public record Qos(long[] values, byte[][] data, String[] partitions) {}
    public static native int participant(int domain);
    public static native int participantConfigured(int domain, Qos qos);
    public static int topic(int participant, String name, String typeName, boolean hasKey, byte[] typeInformation, byte[] typeMapping) {
        return topicConfigured(participant, name, typeName, hasKey, 5, typeInformation, typeMapping, null);
    }
    public static native int topicConfigured(int participant, String name, String typeName, boolean hasKey, int representations,
                                   byte[] typeInformation, byte[] typeMapping, Qos qos);
    public static native int endpointConfigured(int parent, int topic, boolean writer, Qos qos);
    public static native int containerConfigured(int participant, boolean publisher, Qos qos);
    public static native void setQos(int entity, Qos qos);
    public static native int takeStatus(int entity, int mask);
    public static native void assertLiveliness(int entity);
    public static native int endpoint(int participant, int topic, boolean writer, boolean reliable, int depth, long maxBlockingNanos);
    public static native int container(int participant, boolean publisher);
    public static native long instanceHandle(int entity);
    public static native long[] matchedHandles(int endpoint, boolean writer);
    public static native DiscoveryData matchedData(int endpoint, boolean writer, long instance);
    public static native int builtinReader(int subscriber, int kind);
    public static native int readerTopic(int reader);
    public static native Qos getQos(int entity);
    public static native DiscoveryData[] builtinRead(int reader, int kind, int maximum, int operation, int mask, long instance, boolean next);
    public static native DiscoveryData builtinKey(int reader, int kind, long instance);
    public static native long builtinLookup(int reader, int[] key);
    public static native long instanceOperation(int writer, byte[] cdr, boolean register);
    public static native void dispose(int writer, long instance, boolean unregister, long timestamp);
    public static native void waitForAcks(int entity, long timeoutNanos);
    public static native void delete(int entity);
    public static native void write(int writer, byte[] cdr);
    public static native void writeAt(int writer, byte[] cdr, long timestamp);
    public static native WireSample[] take(int reader, int maxSamples);
    public static native WireSample[] read(int reader, int maxSamples);
    public static native WireSample[] select(int reader, int maxSamples, boolean take, int stateMask, long instance);
    public static native WireSample[] selectNext(int reader, int maxSamples, boolean take, int stateMask, long instance);
    public static native byte[] keyValue(int entity, long instance, byte[] holder);
    public static native long[] status(int entity, int kind);
    public static native long listenerCreate(Object receiver);
    public static native void listenerSet(int entity, long context, int mask);
    public static native void listenerFree(long context);
    public static native long[] queryCreate(int reader, int mask, Object filter);
    public static native void queryDelete(int condition, int slot);
    public static native long topicFilter(int topic, Object filter);
    public static native void filterFree(long context);
    public static native int matched(int endpoint, boolean writer);
    public static native int waitset();
    public static native int guard();
    public static native int readCondition(int reader, int mask);
    public static native boolean triggered(int condition);
    public static native void guardTrigger(int condition, boolean value);
    public static native void waitsetTrigger(int waitset, boolean value);
    public static native void attach(int waitset, int condition, boolean attach);
    public static native int await(int waitset, long timeout);
    public static native int statusMask(int entity, int mask, boolean set);
    public static native int statusChanges(int entity);
    public static native long[] matchedStatus(int entity, boolean writer);
    public static native void historicalData(int reader, long timeout);
}
