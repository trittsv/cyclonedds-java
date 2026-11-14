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
import org.omg.dds.core.*;
import org.omg.dds.core.policy.ResourceLimits;
import org.omg.dds.sub.*;

/** Reusable reader-bound selector; operations use one immutable settings snapshot. */
final class ReaderSelector<T> implements DataReader.Selector<T> {
    record Settings(DataStates state, InstanceHandle instance, int maximum, boolean next, String expression, List<String> parameters) {}
    private final ServiceEnvironment env;
    private final DataReader<T> reader;
    private final Object monitor;
    private final CycloneTypeSupport<?> type;
    private volatile Settings settings;
    private ReadCondition<T> condition;
    private Settings conditionSettings;

    ReaderSelector(ServiceEnvironment env, DataReader<T> reader, Object monitor, CycloneTypeSupport<?> type) {
        this.env = env; this.reader = reader; this.monitor = monitor; this.type = type;
        settings = new Settings(DataStates.any(env), new Values.Handle(env, 0).immutableCopy(), ResourceLimits.LENGTH_UNLIMITED, false, null, List.of());
    }
    Settings settings(Object owner) {
        if (owner != reader) throw new IllegalArgumentException("Selector belongs to another reader");
        return settings;
    }
    public ServiceEnvironment getEnvironment() { return env; }
    public ReaderSelector<T> instance(InstanceHandle handle) {
        long value = Values.handleValue(Objects.requireNonNull(handle, "Instance handle"), env);
        synchronized (monitor) {
            Settings old = settings;
            settings = new Settings(old.state(), new Values.Handle(env, value).immutableCopy(), old.maximum(), old.next(), old.expression(), old.parameters());
        }
        return this;
    }
    public ReaderSelector<T> dataState(Subscriber.DataState state) {
        DataStates copy = DataStates.snapshot(env, state);
        synchronized (monitor) {
            Settings old = settings;
            settings = new Settings(copy, old.instance(), old.maximum(), old.next(), old.expression(), old.parameters());
        }
        return this;
    }
    public ReaderSelector<T> maxSamples(int maximum) {
        if (maximum != ResourceLimits.LENGTH_UNLIMITED && (maximum < 0 || maximum > 65536))
            throw new IllegalArgumentException("Sample limit must be LENGTH_UNLIMITED or in [0, 65536]");
        synchronized (monitor) {
            Settings old = settings;
            settings = new Settings(old.state(), old.instance(), maximum, old.next(), old.expression(), old.parameters());
        }
        return this;
    }
    public ReaderSelector<T> nextInstance(boolean next) {
        synchronized (monitor) {
            Settings old = settings;
            settings = new Settings(old.state(), old.instance(), old.maximum(), next, old.expression(), old.parameters());
        }
        return this;
    }
    public ReaderSelector<T> Content(String expression, List<String> parameters) {
        ContentFilter filter = new ContentFilter(type, expression, parameters);
        synchronized (monitor) {
            Settings old = settings;
            settings = new Settings(old.state(), old.instance(), old.maximum(), old.next(), filter.expression, filter.parameters);
        }
        return this;
    }
    public InstanceHandle getInstance() { return settings.instance(); }
    public boolean retrieveNextInstance() { return settings.next(); }
    public Subscriber.DataState getDataState() { return settings.state(); }
    public String getQueryExpression() { return settings.expression(); }
    public List<String> getQueryParameters() { return settings.parameters(); }
    public int getMaxSamples() { return settings.maximum(); }
    public ReadCondition<T> getCondition() {
        synchronized (monitor) {
            Settings current = settings;
            if (!current.instance().isNil() || current.next()) throw Policies.unsupported("Instance-restricted read conditions");
            return condition(current);
        }
    }
    ReadCondition<T> condition(Settings current) {
        synchronized (monitor) {
            if (condition != null && conditionSettings.state().equals(current.state()) &&
                    Objects.equals(conditionSettings.expression(), current.expression()) && conditionSettings.parameters().equals(current.parameters())) {
                try { condition.getParent(); return condition; }
                catch (AlreadyClosedException closed) { condition = null; }
            }
            condition = current.expression() == null ? reader.createReadCondition(current.state())
                    : reader.createQueryCondition(current.state(), current.expression(), current.parameters());
            conditionSettings = current;
            return condition;
        }
    }
    public Sample.Iterator<T> read() { return reader.read(this); }
    public List<Sample<T>> read(List<Sample<T>> samples) { return reader.read(samples, this); }
    public Sample.Iterator<T> take() { return reader.take(this); }
    public List<Sample<T>> take(List<Sample<T>> samples) { return reader.take(samples, this); }
}
