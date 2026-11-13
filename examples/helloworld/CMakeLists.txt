# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

idljava_generate(TARGET hello-types FILES HelloWorld.idl)
file(GLOB_RECURSE EXAMPLE_JAVA CONFIGURE_DEPENDS java/*.java)
add_jar(helloworld-java SOURCES ${EXAMPLE_JAVA} INCLUDE_JARS example-common omg-dds-api idl4-java-api "${hello-types_JAR}")
add_dependencies(helloworld-java hello-types)
set(hello-types_JAR "${hello-types_JAR}" PARENT_SCOPE)
