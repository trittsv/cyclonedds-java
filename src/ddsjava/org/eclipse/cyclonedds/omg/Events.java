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

import org.omg.dds.core.*;
import org.omg.dds.core.event.*;
import org.omg.dds.core.status.*;
import org.omg.dds.pub.DataWriter;
import org.omg.dds.sub.*;
import org.omg.dds.topic.Topic;

@SuppressWarnings({"unchecked", "rawtypes"})
final class Events {
    private Events() {}
    static StatusChangedEvent<?> create(int kind, Object source, Status status) {
        return switch (kind) {
            case 0 -> new InconsistentTopicEvent<Object>((Topic) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public Topic<Object> getSource() { return (Topic<Object>) super.source; }
                public InconsistentTopicStatus getStatus() { return (InconsistentTopicStatus) status; }
                public InconsistentTopicEvent<Object> clone() { return (InconsistentTopicEvent<Object>) create(0, source, status); }
            };
            case 1 -> new OfferedDeadlineMissedEvent<Object>((DataWriter) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataWriter<Object> getSource() { return (DataWriter<Object>) super.source; }
                public OfferedDeadlineMissedStatus getStatus() { return (OfferedDeadlineMissedStatus) status; }
                public OfferedDeadlineMissedEvent<Object> clone() { return (OfferedDeadlineMissedEvent<Object>) create(1, source, status); }
            };
            case 2 -> new RequestedDeadlineMissedEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public RequestedDeadlineMissedStatus getStatus() { return (RequestedDeadlineMissedStatus) status; }
                public RequestedDeadlineMissedEvent<Object> clone() { return (RequestedDeadlineMissedEvent<Object>) create(2, source, status); }
            };
            case 3 -> new OfferedIncompatibleQosEvent<Object>((DataWriter) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataWriter<Object> getSource() { return (DataWriter<Object>) super.source; }
                public OfferedIncompatibleQosStatus getStatus() { return (OfferedIncompatibleQosStatus) status; }
                public OfferedIncompatibleQosEvent<Object> clone() { return (OfferedIncompatibleQosEvent<Object>) create(3, source, status); }
            };
            case 4 -> new RequestedIncompatibleQosEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public RequestedIncompatibleQosStatus getStatus() { return (RequestedIncompatibleQosStatus) status; }
                public RequestedIncompatibleQosEvent<Object> clone() { return (RequestedIncompatibleQosEvent<Object>) create(4, source, status); }
            };
            case 5 -> new SampleLostEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public SampleLostStatus getStatus() { return (SampleLostStatus) status; }
                public SampleLostEvent<Object> clone() { return (SampleLostEvent<Object>) create(5, source, status); }
            };
            case 6 -> new SampleRejectedEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public SampleRejectedStatus getStatus() { return (SampleRejectedStatus) status; }
                public SampleRejectedEvent<Object> clone() { return (SampleRejectedEvent<Object>) create(6, source, status); }
            };
            case 7 -> new DataOnReadersEvent((Subscriber) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public Subscriber getSource() { return (Subscriber) super.source; }
                public DataOnReadersStatus getStatus() { return (DataOnReadersStatus) status; }
                public DataOnReadersEvent clone() { return (DataOnReadersEvent) create(7, source, status); }
            };
            case 8 -> new DataAvailableEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public DataAvailableStatus getStatus() { return (DataAvailableStatus) status; }
                public DataAvailableEvent<Object> clone() { return (DataAvailableEvent<Object>) create(8, source, status); }
            };
            case 9 -> new LivelinessLostEvent<Object>((DataWriter) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataWriter<Object> getSource() { return (DataWriter<Object>) super.source; }
                public LivelinessLostStatus getStatus() { return (LivelinessLostStatus) status; }
                public LivelinessLostEvent<Object> clone() { return (LivelinessLostEvent<Object>) create(9, source, status); }
            };
            case 10 -> new LivelinessChangedEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public LivelinessChangedStatus getStatus() { return (LivelinessChangedStatus) status; }
                public LivelinessChangedEvent<Object> clone() { return (LivelinessChangedEvent<Object>) create(10, source, status); }
            };
            case 11 -> new PublicationMatchedEvent<Object>((DataWriter) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataWriter<Object> getSource() { return (DataWriter<Object>) super.source; }
                public PublicationMatchedStatus getStatus() { return (PublicationMatchedStatus) status; }
                public PublicationMatchedEvent<Object> clone() { return (PublicationMatchedEvent<Object>) create(11, source, status); }
            };
            case 12 -> new SubscriptionMatchedEvent<Object>((DataReader) source) {
                private static final long serialVersionUID = 1L;
                public ServiceEnvironment getEnvironment() { return getSource().getEnvironment(); }
                public DataReader<Object> getSource() { return (DataReader<Object>) super.source; }
                public SubscriptionMatchedStatus getStatus() { return (SubscriptionMatchedStatus) status; }
                public SubscriptionMatchedEvent<Object> clone() { return (SubscriptionMatchedEvent<Object>) create(12, source, status); }
            };
            default -> throw new IllegalArgumentException("Unknown status");
        };
    }
}
