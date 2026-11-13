# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

idljava_generate(TARGET roundtrip-types FILES RoundTrip.idl)
add_jar(roundtrip-example SOURCES java/org/eclipse/cyclonedds/examples/RoundTrip.java
  INCLUDE_JARS example-common omg-dds-api idl4-java-api "$<TARGET_PROPERTY:roundtrip-types,JAR_FILE>")
add_dependencies(roundtrip-example roundtrip-types)
