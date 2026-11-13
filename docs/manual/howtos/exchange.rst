..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


Reading data
============

Readers use the official ``org.omg.dds.sub.DataReader`` API. Reads and takes are
nonblocking: an empty result means no matching samples are currently available.
Discovery and delivery are asynchronous. The HelloWorld example uses native status/read conditions and waitsets with
deadlines for discovery and data receipt.

State selection
---------------

Every sample has a sample state, view state and instance state. Configure all
three components explicitly:

.. code-block:: java

   var unread = subscriber.createDataState()
       .with(org.omg.dds.sub.SampleState.NOT_READ)
       .withAnyViewState()
       .with(org.omg.dds.sub.InstanceState.ALIVE);
   var selection = reader.select().dataState(unread).maxSamples(64);
   try (var samples = selection.read()) {
       while (samples.hasNext()) {
           var sample = samples.next();
           if (sample.getData() != null) process(sample.getData());
       }
   }

``subscriber.createDataState()`` starts with empty sets. If any component remains
empty, the selection matches nothing. ``with(...)`` adds a state to its component;
it does not replace previously added states. ``clone()`` gives an independent
modifiable copy, and the set getters return unmodifiable snapshots.

``reader.select()`` starts with all states, a nil instance handle and
``ResourceLimits.LENGTH_UNLIMITED``. Each call creates a separate selector bound to
that reader. Configuration methods return the same selector for chaining; reuse
it across operations. A selector copies supplied state sets and handles. Its
``getDataState()`` result is immutable; clone that result to modify it.

``read`` retains data in DDS and changes returned samples from NOT_READ to READ.
It also changes the view state of the accessed instances from NEW to NOT_NEW.
Returned metadata describes the states at access time. ``take`` removes the
matching samples. Neither operation consumes nonmatching samples. Filtering runs
inside Cyclone DDS before Java copying and deserialization.

Instance selection
------------------

For keyed types, resolve a key using the reader's instance table:

.. code-block:: java

   var key = new HelloWorld.Message(42, "");
   var handle = reader.lookupInstance(key);
   if (!handle.isNil()) {
       try (var samples = reader.select().instance(handle).take()) {
           while (samples.hasNext()) processSample(samples.next());
       }
   }

A nil handle means no instance restriction. Check lookup results before applying
them. A non-nil handle unknown to the reader raises
``PreconditionNotMetException``. Using a selector with another reader raises
``IllegalArgumentException``. Applying a selector after its reader closes raises
``AlreadyClosedException``.

Lists, limits and lifecycle
---------------------------

Iterators implement ``AutoCloseable``. Use try-with-resources, including for empty
batches. Reader and parent closure is rejected while an iterator remains open.
The implementation copies native samples into Java before returning, but retains
the standard iterator lifetime checks.

For copied samples without an iterator, use the list overload. Passing null
allocates a list sized to the result:

.. code-block:: java

   java.util.List<org.omg.dds.sub.Sample<HelloWorld.Message>> batch = selection.take(null);
   for (var sample : batch) processSample(sample);

With a non-null list, the selector overload uses the list's current length as
another limit. An empty list therefore requests zero samples. To provide space
for up to 16 samples:

.. code-block:: java

   var batch = new java.util.ArrayList<org.omg.dds.sub.Sample<HelloWorld.Message>>(
       java.util.Collections.nCopies(16, null));
   selection.take(batch);

The list is trimmed to the result size. Refill it to the desired length before
reusing it as bounded storage. The implementation reuses sample objects of the same type returned by this
provider, preserving their element identities. It replaces null or foreign objects. The unselected ``read(List)``
and ``take(List)`` overloads replace the list contents without using its old length
as a limit.

Explicit batch limits range from zero to 65,536. LENGTH_UNLIMITED also has a
65,536-sample implementation cap per operation. Drain larger histories with
repeated ``take`` or with repeated NOT_READ selections. Repeating an unrestricted
``read`` can return the same retained samples.

``getData()`` can be null for dispose and unregister notifications. Inspect the
instance state and instance handle instead. For lifecycle notifications, select
all instance states or use ``withNotAliveInstanceStates()``.

Native entity operations are serialized per participant. Configure a selector
from one thread at a time, or synchronize configuration in the application.
Next-instance traversal, listener dispatch and native waitsets are supported.
See :doc:`operations` for instance traversal and listener behavior. Content queries
and query conditions remain unsupported and fail explicitly.


See :doc:`waitset` for blocking reads and condition lifetimes.
