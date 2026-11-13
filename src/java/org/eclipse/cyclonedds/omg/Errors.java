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

import org.eclipse.cyclonedds.core.DdsException;
import org.omg.dds.core.*;

final class Errors {
    private Errors() {}
    static RuntimeException unchecked(ServiceEnvironment env, DdsException error) {
        Throwable mapped = map(env, error);
        return mapped instanceof RuntimeException runtime ? runtime : new Failure(env, mapped);
    }
    static final class Closed extends AlreadyClosedException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        Closed(ServiceEnvironment env) { super("DDS entity is closed"); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class Precondition extends PreconditionNotMetException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        Precondition(ServiceEnvironment env, String message) { super(message); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class Failure extends DDSException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        Failure(ServiceEnvironment env, Throwable cause) { super(cause); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class Resources extends OutOfResourcesException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        Resources(ServiceEnvironment env, Throwable cause) { super(cause); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class NotEnabled extends NotEnabledException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        NotEnabled(ServiceEnvironment env, Throwable cause) { super(cause); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class Immutable extends ImmutablePolicyException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        Immutable(ServiceEnvironment env, Throwable cause) { super(cause); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class Inconsistent extends InconsistentPolicyException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        Inconsistent(ServiceEnvironment env, Throwable cause) { super(cause); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static final class IllegalOperation extends IllegalOperationException {
        private static final long serialVersionUID = 1L;
        private final transient ServiceEnvironment env;
        IllegalOperation(ServiceEnvironment env, Throwable cause) { super(cause); this.env = env; }
        public ServiceEnvironment getEnvironment() { return env; }
    }
    static Throwable map(ServiceEnvironment env, DdsException error) {
        return switch (error.code()) {
            case -2 -> new UnsupportedOperationException(error.getMessage(), error);
            case -3 -> new IllegalArgumentException(error.getMessage(), error);
            case -4 -> new Precondition(env, error.getMessage());
            case -5 -> new Resources(env, error);
            case -6 -> new NotEnabled(env, error);
            case -7 -> new Immutable(env, error);
            case -8 -> new Inconsistent(env, error);
            case -12 -> new IllegalOperation(env, error);
            case -9 -> new Closed(env);
            case -10 -> new java.util.concurrent.TimeoutException(error.getMessage());
            default -> new Failure(env, error);
        };
    }
}
