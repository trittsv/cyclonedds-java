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

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;

/** Value implementations used by the normative OMG API. */
final class Values {
    private Values() {}
    static long nanos(long value, TimeUnit unit) { return Objects.requireNonNull(unit).toNanos(value); }
    static long remainder(long nanos, TimeUnit primary, TimeUnit remainder) {
        if (primary.compareTo(remainder) < 0) throw new IllegalArgumentException("Remainder unit must be finer than primary unit");
        return remainder.convert(nanos - primary.toNanos(primary.convert(nanos, TimeUnit.NANOSECONDS)), TimeUnit.NANOSECONDS);
    }
    static final class DdsDuration extends Duration {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long value;
        DdsDuration(ServiceEnvironment env, long value, TimeUnit unit) {
            if (value < 0) throw new IllegalArgumentException("Duration must be nonnegative");
            this.env = env; this.value = value == Long.MAX_VALUE ? Long.MAX_VALUE : nanos(value, unit);
        }
        public ServiceEnvironment getEnvironment() { return env; }
        public long getDuration(TimeUnit unit) { return isInfinite() ? Long.MAX_VALUE : unit.convert(value, TimeUnit.NANOSECONDS); }
        public long getRemainder(TimeUnit primary, TimeUnit unit) { return isInfinite() ? 0 : remainder(value, primary, unit); }
        public boolean isZero() { return value == 0; }
        public boolean isInfinite() { return value == Long.MAX_VALUE; }
        public Duration add(Duration other) { return add(other.getDuration(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS); }
        public Duration add(long amount, TimeUnit unit) {
            if (amount < 0) throw new IllegalArgumentException("Negative duration");
            long delta = nanos(amount, unit);
            long result = isInfinite() || amount == Long.MAX_VALUE || delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
            return new DdsDuration(env, result, TimeUnit.NANOSECONDS);
        }
        public Duration subtract(Duration other) { return subtract(other.getDuration(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS); }
        public Duration subtract(long amount, TimeUnit unit) {
            if (amount < 0) throw new IllegalArgumentException("Negative duration");
            long delta = amount == Long.MAX_VALUE ? Long.MAX_VALUE : nanos(amount, unit);
            if (delta > value) throw new IllegalArgumentException("Duration underflow");
            return new DdsDuration(env, isInfinite() && delta != Long.MAX_VALUE ? Long.MAX_VALUE : value - delta, TimeUnit.NANOSECONDS);
        }
        public int compareTo(Duration other) { return Long.compare(value, other.getDuration(TimeUnit.NANOSECONDS)); }
        @Override public boolean equals(Object other) { return other instanceof Duration d && compareTo(d) == 0; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }
    static final class DdsTime extends ModifiableTime {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private long value;
        DdsTime(ServiceEnvironment env, long value, TimeUnit unit) { this.env = env; setTime(value, unit); }
        public ServiceEnvironment getEnvironment() { return env; }
        public long getTime(TimeUnit unit) { return isValid() ? unit.convert(value, TimeUnit.NANOSECONDS) : -1; }
        public long getRemainder(TimeUnit primary, TimeUnit unit) { return isValid() ? remainder(value, primary, unit) : 0; }
        public boolean isValid() { return value >= 0; }
        public ModifiableTime modifiableCopy() { return new DdsTime(env, value, TimeUnit.NANOSECONDS); }
        public void copyFrom(Time source) { value = source.getTime(TimeUnit.NANOSECONDS); }
        public Time immutableCopy() { return new FrozenTime(env, value); }
        public void setTime(long time, TimeUnit unit) { value = time < 0 ? -1 : nanos(time, unit); }
        public void add(Duration duration) { add(duration.getDuration(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS); }
        public void add(long amount, TimeUnit unit) { if (!isValid() || amount < 0) throw new IllegalArgumentException("Invalid time or duration"); value = Math.addExact(value, nanos(amount, unit)); }
        public void subtract(Duration duration) { subtract(duration.getDuration(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS); }
        public void subtract(long amount, TimeUnit unit) {
            long delta = nanos(amount, unit);
            if (!isValid() || amount < 0 || delta > value) throw new IllegalArgumentException("Time underflow");
            value -= delta;
        }
        public int compareTo(Time other) { return Long.compare(value, other.getTime(TimeUnit.NANOSECONDS)); }
        @Override public boolean equals(Object other) { return other instanceof Time t && compareTo(t) == 0; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }
    private static final class FrozenTime extends Time {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long value;
        FrozenTime(ServiceEnvironment env, long value) { this.env = env; this.value = value; }
        public ServiceEnvironment getEnvironment() { return env; }
        public boolean isValid() { return value >= 0; }
        public long getTime(TimeUnit unit) { return isValid() ? unit.convert(value, TimeUnit.NANOSECONDS) : -1; }
        public long getRemainder(TimeUnit primary, TimeUnit unit) { return isValid() ? remainder(value, primary, unit) : 0; }
        public ModifiableTime modifiableCopy() { return new DdsTime(env, value, TimeUnit.NANOSECONDS); }
        public int compareTo(Time other) { return Long.compare(value, other.getTime(TimeUnit.NANOSECONDS)); }
        @Override public boolean equals(Object other) { return other instanceof Time t && compareTo(t) == 0; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }
    static final class Handle extends ModifiableInstanceHandle {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private long value;
        Handle(ServiceEnvironment env, long value) { this.env = env; this.value = value; }
        long value() { return value; }
        public ServiceEnvironment getEnvironment() { return env; }
        public boolean isNil() { return value == 0; }
        public ModifiableInstanceHandle modifiableCopy() { return new Handle(env, value); }
        public void copyFrom(InstanceHandle source) { value = handleValue(source, env); }
        public InstanceHandle immutableCopy() { return new FrozenHandle(env, value); }
        public int compareTo(InstanceHandle other) { return Long.compareUnsigned(value, handleValue(other, env)); }
        @Override public boolean equals(Object other) { return other instanceof InstanceHandle h && h.getEnvironment() == env && compareTo(h) == 0; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }
    private static final class FrozenHandle extends InstanceHandle {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        private final long value;
        FrozenHandle(ServiceEnvironment env, long value) { this.env = env; this.value = value; }
        public ServiceEnvironment getEnvironment() { return env; }
        public boolean isNil() { return value == 0; }
        public ModifiableInstanceHandle modifiableCopy() { return new Handle(env, value); }
        public int compareTo(InstanceHandle other) { return Long.compareUnsigned(value, handleValue(other, env)); }
        @Override public boolean equals(Object other) { return other instanceof InstanceHandle h && h.getEnvironment() == env && compareTo(h) == 0; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }
    static long handleValue(InstanceHandle handle, ServiceEnvironment env) {
        Objects.requireNonNull(handle);
        if (handle.getEnvironment() != env) throw new IllegalArgumentException("Instance handle belongs to another environment");
        if (handle instanceof Handle h) return h.value;
        if (handle instanceof FrozenHandle h) return h.value;
        throw new IllegalArgumentException("Foreign instance handle implementation");
    }
}
