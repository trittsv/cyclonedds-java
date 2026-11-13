<!--
Copyright(c) 2026 Sven Trittler

This program and the accompanying materials are made available under the
terms of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
-->

`src/jni/typesupport.c` adapts the byte-oriented DDSI type-support approach
and CDR key extraction from
[cyclonedds-python/clayer/pysertype.c](https://github.com/eclipse-cyclonedds/cyclonedds-python/blob/68b2acfd5ac56bd0f095e51914a9f6c494675327/clayer/pysertype.c).

Copyright(c) 2021 to 2022 ZettaScale Technology and others.

That source is offered under `EPL-2.0 OR BSD-3-Clause`, matching Cyclone DDS's
Eclipse Public License 2.0 or Eclipse Distribution License 1.0 licensing.
Both license texts are reproduced in [LICENSE](LICENSE), copied unchanged from
the Python binding. The original copyright and license identifier are retained
in the adapted file.

The unmodified normative OMG DDS-Java 1.0 API sources are included under
`third_party/omg-dds`. They are licensed under Apache-2.0, with the original
[LICENSE](third_party/omg-dds/LICENSE), [NOTICE](third_party/omg-dds/NOTICE) and
[source provenance](third_party/omg-dds/ORIGIN.md). These files retain their own
license; the project's EPL-2.0 / EDL-1.0 licensing does not replace it.
# Bundled Cyclone DDS runtime

The `org.eclipse.cyclonedds` runtime JAR embeds the Cyclone DDS native cores for its included platforms.
Its unmodified EPL 2.0 / EDL 1.0 license and component notices are preserved in
`third_party/cyclonedds` and in `META-INF/cyclonedds` inside the runtime JAR.
The tested source revision is recorded in `third_party/cyclonedds/ORIGIN.md`.


# Bundled IDL compiler

The separate `org.eclipse.cyclonedds.idlc` compiler JAR embeds each platform's configured
Cyclone DDS `idlc` executable, IDL library, Java backend and required native
libraries. The compiler statically includes the MCPP preprocessor, copyright
1998, 2002-2008 Kiyoshi Matsui, under its BSD license. That license is retained
in [third_party/idlc/LICENSE-mcpp](third_party/idlc/LICENSE-mcpp) and in
`META-INF/cyclonedds/LICENSE-mcpp` inside the compiler JAR. Both artifacts preserve the core's original license and notices.

# Documentation theme helpers

The manual's `_static/css/helpers.css` and `_static/js/helpers.js` are copied from
the Cyclone DDS C++ documentation at revision
`33d27ffff4d76021d88653f778cc001aa1a2bb7a`, under EPL-2.0 OR BSD-3-Clause.
The Sphinx layout/metadata conventions follow the C++ and Python manuals.
Piccolo and Sphinx are documentation build dependencies and retain their licenses.
