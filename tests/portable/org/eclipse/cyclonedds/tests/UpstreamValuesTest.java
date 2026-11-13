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

import java.util.concurrent.TimeUnit;
import org.omg.dds.core.*;
import static java.util.concurrent.TimeUnit.*;

/** Java equivalents of ddscxx Duration.cpp and Time.cpp value semantics. */
public final class UpstreamValuesTest {
    private static final ServiceEnvironment ENV = ServiceEnvironment.createInstance(UpstreamValuesTest.class.getClassLoader());
    private static Duration duration(long nanos) { return Duration.newDuration(nanos, NANOSECONDS, ENV); }
    private static ModifiableTime time(long nanos) { return Time.newTime(nanos, NANOSECONDS, ENV).modifiableCopy(); }
    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
    public static void main(String[] args) {
        switch (args[0]) {
        case "duration-add": {
            var original = duration(10_000_000_010L);
            var sum = original.add(original);
            assert sum.equals(duration(20_000_000_020L));
            assert sum.add(1_999_999_999L, NANOSECONDS).equals(duration(22_000_000_019L));
            assert original.equals(duration(10_000_000_010L));
            assert original.add(0, SECONDS).equals(original);
            assert original.add(Duration.infiniteDuration(ENV)).isInfinite();
            rejects(() -> original.add(-1, NANOSECONDS));
            break;
        }
        case "duration-subtract": {
            var value = duration(11_000_000_009L);
            assert value.subtract(duration(10_000_000_010L)).equals(duration(999_999_999L));
            assert value.subtract(value).isZero();
            assert value.subtract(1, SECONDS).equals(duration(10_000_000_009L));
            assert duration(12_000_000_012L).subtract(duration(10_000_000_010L)).equals(duration(2_000_000_002L));
            for (long invalid : new long[]{10_000_000_009L, 9_000_000_009L, 9_000_000_012L})
                rejects(() -> duration(invalid).subtract(duration(10_000_000_010L)));
            rejects(() -> duration(0).subtract(1, NANOSECONDS));
            rejects(() -> value.subtract(-1, SECONDS));
            break;
        }
        case "duration-units": {
            long nanos = 123_456_789_123L;
            for (var unit : TimeUnit.values()) {
                assert duration(nanos).getDuration(unit) == unit.convert(nanos, NANOSECONDS);
                assert Duration.newDuration(2, unit, ENV).getDuration(NANOSECONDS) == unit.toNanos(2);
                for (var finer : TimeUnit.values()) if (finer.compareTo(unit) <= 0)
                    assert duration(nanos).getRemainder(unit, finer) == finer.convert(nanos - unit.toNanos(unit.convert(nanos, NANOSECONDS)), NANOSECONDS);
            }
            rejects(() -> duration(nanos).getRemainder(MILLISECONDS, SECONDS));
            break;
        }
        case "duration-compare": {
            var base = duration(3_000_000_005L);
            for (int seconds = 2; seconds <= 4; seconds++) for (int nanos = 4; nanos <= 6; nanos++) {
                var other = duration(seconds * 1_000_000_000L + nanos);
                int expected = Long.compare(3_000_000_005L, seconds * 1_000_000_000L + nanos);
                assert Integer.signum(base.compareTo(other)) == expected;
                assert Integer.signum(other.compareTo(base)) == -expected;
                assert base.equals(other) == (expected == 0);
                if (expected == 0) assert base.hashCode() == other.hashCode();
            }
            assert !base.equals(null) && !base.equals("duration");
            break;
        }
        case "duration-special": {
            var infinity = Duration.infiniteDuration(ENV);
            assert infinity.isInfinite() && !infinity.isZero();
            assert infinity.add(1, SECONDS).isInfinite();
            assert infinity.subtract(1, SECONDS).isInfinite();
            assert infinity.compareTo(duration(Long.MAX_VALUE - 1)) > 0;
            for (var unit : TimeUnit.values()) assert infinity.getDuration(unit) == Long.MAX_VALUE;
            assert duration(Long.MAX_VALUE - 1).add(2, NANOSECONDS).isInfinite();
            assert Duration.zeroDuration(ENV).isZero();
            rejects(() -> duration(-1));
            break;
        }
        case "time-add": {
            var value = time(10_000_000_010L);
            value.add(duration(10_000_000_010L));
            assert value.equals(time(20_000_000_020L));
            value.add(1_999_999_999L, NANOSECONDS);
            assert value.equals(time(22_000_000_019L));
            rejects(() -> value.add(-1, NANOSECONDS));
            break;
        }
        case "time-subtract": {
            var value = time(11_000_000_009L);
            value.subtract(duration(10_000_000_010L));
            assert value.equals(time(999_999_999L));
            value.subtract(999_999_999L, NANOSECONDS);
            assert value.equals(time(0));
            var difference = time(12_000_000_012L); difference.subtract(duration(10_000_000_010L));
            assert difference.equals(time(2_000_000_002L));
            for (long invalid : new long[]{10_000_000_009L, 9_000_000_009L, 9_000_000_012L})
                rejects(() -> time(invalid).subtract(duration(10_000_000_010L)));
            rejects(() -> value.subtract(1, NANOSECONDS));
            assert value.getTime(NANOSECONDS) == 0;
            rejects(() -> value.subtract(-1, SECONDS));
            break;
        }
        case "time-units": {
            long nanos = 123_456_789_123L;
            for (var unit : TimeUnit.values()) {
                assert time(nanos).getTime(unit) == unit.convert(nanos, NANOSECONDS);
                var value = time(0); value.setTime(2, unit);
                assert value.getTime(NANOSECONDS) == unit.toNanos(2);
                for (var finer : TimeUnit.values()) if (finer.compareTo(unit) <= 0)
                    assert time(nanos).getRemainder(unit, finer) == finer.convert(nanos - unit.toNanos(unit.convert(nanos, NANOSECONDS)), NANOSECONDS);
            }
            rejects(() -> time(nanos).getRemainder(MILLISECONDS, SECONDS));
            break;
        }
        case "time-compare": {
            var base = time(3_000_000_005L);
            for (int seconds = 2; seconds <= 4; seconds++) for (int nanos = 4; nanos <= 6; nanos++) {
                var other = time(seconds * 1_000_000_000L + nanos);
                int expected = Long.compare(3_000_000_005L, seconds * 1_000_000_000L + nanos);
                assert Integer.signum(base.compareTo(other)) == expected;
                assert Integer.signum(other.compareTo(base)) == -expected;
                assert base.equals(other) == (expected == 0);
                if (expected == 0) assert base.hashCode() == other.hashCode();
            }
            assert !base.equals(null) && !base.equals("time");
            break;
        }
        case "time-copies": {
            var source = time(123);
            var frozen = source.immutableCopy();
            var mutable = frozen.modifiableCopy();
            assert source.equals(frozen) && frozen.equals(source);
            source.add(1, NANOSECONDS); mutable.subtract(1, NANOSECONDS);
            assert frozen.getTime(NANOSECONDS) == 123;
            assert source.getTime(NANOSECONDS) == 124 && mutable.getTime(NANOSECONDS) == 122;
            mutable.copyFrom(frozen);
            assert mutable.equals(frozen) && mutable.hashCode() == frozen.hashCode();
            break;
        }
        case "time-invalid": {
            var value = Time.invalidTime(ENV).modifiableCopy();
            assert !value.isValid();
            for (var unit : TimeUnit.values()) assert value.getTime(unit) == -1;
            rejects(() -> value.add(1, SECONDS));
            rejects(() -> value.subtract(1, SECONDS));
            assert !value.immutableCopy().isValid() && !value.modifiableCopy().isValid();
            value.setTime(0, SECONDS); assert value.isValid();
            value.setTime(-42, SECONDS); assert !value.isValid();
            break;
        }
        case "nil-handle": {
            var nil = InstanceHandle.nilHandle(ENV);
            var mutable = nil.modifiableCopy();
            assert nil.isNil() && mutable.isNil() && mutable.immutableCopy().isNil();
            assert nil.equals(mutable) && mutable.equals(nil) && nil.compareTo(mutable) == 0;
            assert nil.hashCode() == mutable.hashCode();
            mutable.copyFrom(nil);
            assert nil.equals(mutable);
            var otherEnv = ServiceEnvironment.createInstance(UpstreamValuesTest.class.getClassLoader());
            assert !nil.equals(InstanceHandle.nilHandle(otherEnv));
            rejects(() -> mutable.copyFrom(InstanceHandle.nilHandle(otherEnv)));
            break;
        }
        default: throw new AssertionError("Unknown case: " + args[0]);
        }
        System.out.println(args[0] + " passed");
    }
}
