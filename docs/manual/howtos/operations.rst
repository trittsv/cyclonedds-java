..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Everyday DDS operations
=======================

The examples below use the generated ``Message`` type and the ``participant``,
``publisher``, ``subscriber``, ``writer`` and ``reader`` from :doc:`exchange`.
They use the normative OMG API. Import ``org.omg.dds.core.*``,
``org.omg.dds.core.event.*``, ``org.omg.dds.core.status.*``,
``org.omg.dds.sub.*``, ``java.util.*`` and ``java.util.concurrent.TimeUnit``
as needed.

Content selection and query conditions
---------------------------------------

A selector applies its query in the native reader cache. A filtered take removes
only matching samples; other samples remain available to other selections.
The capital ``Content`` spelling is part of the normative API.

.. code-block:: java

   var selected = reader.select().Content("id >= %0 AND text LIKE %1",
                                          List.of("10", "'Hello%'"));
   for (var sample : selected.take(null)) {
       if (sample.getData() != null) System.out.println(sample.getData().get_text());
   }

Use the selector's condition to wait for matching data. Query parameters are
immutable snapshots. Changing parameters on a ``QueryCondition`` re-evaluates
samples already in the reader and preserves its waitset attachments.

.. code-block:: java

   var selector = reader.select().Content("id = %0", List.of("10"));
   var waitset = WaitSet.newWaitSet(reader.getEnvironment());
   try (var condition = selector.getCondition()) {
       waitset.attachCondition(condition);
       waitset.waitForConditions(5, TimeUnit.SECONDS);
       var samples = selector.take(null);
   } finally {
       ((AutoCloseable) waitset).close();
   }

A condition created with ``reader.createQueryCondition("id = %0", List.of("10"))``
also supports ``setQueryParameters(List.of("20"))``. Such a change updates that
condition; it does not change a separately configured selector's expression.
A condition only signals availability; use a selector with the intended query to
read or take the matching data.

The implemented SQL WHERE subset supports scalar/nested field paths,
``= != <> < <= > >=``, parentheses, ``AND``, ``OR``, ``NOT``, ``BETWEEN``,
``IN`` and ``LIKE`` (``%`` and ``_`` wildcards), quoted strings with doubled-quote
escaping, numeric/boolean/enumeration literals and ``%n`` parameters.
Unknown fields, unsupported syntax, missing parameters and incompatible operand
types fail before installing a filter. Arithmetic, SQL functions and collection
indexing are not implemented. Unsigned fields currently use the signed numeric
value of their Java storage; full unsigned SQL comparison semantics remain work.

The C query API has no callback argument. The JNI bridge supplies 64 reusable
query callback slots per process; exhausting them or a core reader condition limit
raises ``OutOfResourcesException``. Parameter replacement briefly needs an
additional slot. Close unused conditions (or their reader) to return slots.

Content-filtered topics
------------------------

A content-filtered topic admits matching samples before they enter its readers'
history caches. It has its own topic handle and does not filter other readers or
writers using the related topic.

.. code-block:: java

   try (var filtered = participant.createContentFilteredTopic(
            "ImportantMessages", topic, "id >= %0", List.of("10"));
        var filteredReader = subscriber.createDataReader(filtered)) {
       // Later incoming samples use the updated threshold.
       filtered.setExpressionParameters(List.of("20"));
   }

Parameter changes affect subsequent admission, not samples already admitted to
reader histories. This implementation filters at the receiving side; it does not
request remote writer-side filtering. Key-only dispose/unregister notifications
are retained even when non-key content cannot be evaluated. Keep the related
topic alive until its filtered topics close, and close readers before their topic
description.

Instance traversal and keys
----------------------------

``nextInstance(true)`` selects one non-empty matching instance after the supplied
handle in the core's traversal order. Start from the nil handle and advance with
the handle in the returned sample. Handles are opaque; do not compare or sort
them as application keys. A batch limit can return only part of an instance.

.. code-block:: java

   var selection = reader.select().nextInstance(true);
   for (;;) {
       var samples = selection.read(null);
       if (samples.isEmpty()) break;
       for (var sample : samples) {
           if (sample.getData() != null) System.out.println(sample.getData());
       }
       selection.instance(samples.get(0).getInstanceHandle());
   }

``take`` supports the same traversal and can be combined with state masks and
content queries. The native collector performs selection without consuming or
marking unrelated samples as read.

.. code-block:: java

   var instance = writer.registerInstance(new Message(10, ""), 123,
                                          TimeUnit.NANOSECONDS);
   Message keys = writer.getKeyValue(instance);
   Message holder = new Message(0, "keep this non-key field");
   writer.getKeyValue(holder, instance); // Updates key fields, preserves other values.

Readers expose the holder overload of ``getKeyValue``. Both use the native
instance table and CDR descriptor, including composite/nested keys, appendable
structures, keyed union discriminators and keys after non-key fields. Generated
key-copy methods preserve non-key holder values; regenerate types built with an
older backend before using this operation. They do not retain an application-side sample cache. Nil or
unknown handles fail explicitly.

Both timestamped registration overloads validate their timestamp and register the
instance locally. The core exposes only ``dds_register_instance``: registration
sends no sample and stores no source timestamp. Use a timestamped write,
dispose or unregister operation when a wire-visible source time is required.

Listeners and status values
---------------------------

Listeners implement the standard interfaces. An application must implement every
method of the chosen interface, even when its status mask enables only one.
For example:

.. code-block:: java

   DataReaderListener<Message> listener = new DataReaderListener<>() {
       public void onDataAvailable(DataAvailableEvent<Message> event) {
           for (var sample : event.getSource().select().take(null)) {
               if (sample.getData() != null) System.out.println(sample.getData());
           }
       }
       public void onRequestedDeadlineMissed(RequestedDeadlineMissedEvent<Message> e) {}
       public void onRequestedIncompatibleQos(RequestedIncompatibleQosEvent<Message> e) {}
       public void onSampleRejected(SampleRejectedEvent<Message> e) {}
       public void onLivelinessChanged(LivelinessChangedEvent<Message> e) {}
       public void onSubscriptionMatched(SubscriptionMatchedEvent<Message> e) {}
       public void onSampleLost(SampleLostEvent<Message> e) {}
   };
   reader.setListener(listener, Set.of(DataAvailableStatus.class));

Callbacks run serially on one daemon dispatcher per participant. Native callbacks
only queue event data; user code runs outside native callbacks and participant
monitors. A callback may read, take, write or close an entity, including itself.
Return promptly and do not wait for another callback on the same participant.
Callback exceptions go to the dispatch thread's uncaught-exception handler;
subsequent callbacks continue. Install a default handler if your application needs
to collect these failures.

Masks determine which events a listener handles. A missing or disabled handler
falls back to the enclosing subscriber/publisher and participant. Event sources
remain the entity whose status changed. ``DataOnReaders`` takes precedence over
``DataAvailable``; its listener may call ``subscriber.notifyDataReaders()`` to
delegate to installed reader listeners. The provider implements this delegation
using native status flags because the core's notification function is a stub.

Listener replacement and closure cancel queued events for the affected dispatch
targets. A callback already running may finish after another thread closes its
entity; subsequent DDS calls then raise ``AlreadyClosedException``. Closing the
participant shuts down its dispatcher without waiting on application callbacks.
Data-available events are notifications to drain available data, not a promise
of one callback per sample.

All topic/writer/reader status getters return immutable snapshots, including
inconsistent topics, deadlines, incompatible QoS, lost/rejected samples,
liveliness and publication/subscription matches. Native reads/listener delivery
reset change counters according to the core's status behavior. The core exposes
the last incompatible policy ID, but not the full per-policy counter sequence:
``getLastPolicyClass()`` works, while incompatible-QoS ``getPolicies()`` explicitly
throws ``UnsupportedOperationException``.

Discovery and matched endpoint information
-------------------------------------------

The participant owns a built-in subscriber with readers named
``DCPSParticipant``, ``DCPSTopic``, ``DCPSPublication`` and ``DCPSSubscription``.
These expose the standard OMG built-in data interfaces and owned QoS snapshots.

.. code-block:: java

   import org.omg.dds.topic.PublicationBuiltinTopicData;

   var builtin = participant.getBuiltinSubscriber();
   org.omg.dds.sub.DataReader<PublicationBuiltinTopicData> publications =
       builtin.lookupDataReader("DCPSPublication");
   try (var samples = publications.read()) {
       while (samples.hasNext()) {
           var sample = samples.next();
           if (sample.getData() != null)
               System.out.println(sample.getData().getTopicName());
       }
   }
   for (var handle : reader.getMatchedPublications()) {
       var publication = reader.getMatchedPublicationData(handle);
       System.out.println(publication.getTypeName());
   }

Writers provide ``getMatchedSubscriptionData(handle)``. Participants provide
``getDiscoveredParticipants()``, ``getDiscoveredTopics()``,
``getDiscoveredParticipantData(handle)`` and ``getDiscoveredTopicData(handle)``.
These discovery inspection operations do not mark built-in reader samples read.
Topic discovery requires core topic-discovery support and the
``Discovery/EnableTopicDiscoveryEndpoints`` configuration setting.

Built-in readers support read/take, state selectors, read conditions, waitsets,
listeners, instance traversal, ``lookupInstance`` and ``getKeyValue(holder,
handle)``. Their keys preserve Cyclone's entire 128-bit GUID as four big-endian
32-bit words; applications should treat keys as opaque. Key lookup remains
available for instances previously read or taken by that reader. Snapshot clones
and policy values remain valid after native endpoints are removed. Invalid-data
samples carry instance state and handles with null data, as application readers do.

SQL content predicates over built-in data and application-created endpoints on
built-in topics are unsupported. TypeObject-based ``getType()`` introspection
returns null; equivalent/base type-name lists are empty because the public core
built-in structures do not expose them. Matched data is a point-in-time snapshot:
a handle can disappear between listing it and retrieving its data, producing
``PreconditionNotMetException``.
