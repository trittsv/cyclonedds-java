# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

cmake_minimum_required(VERSION 3.21)
project(CycloneJavaConsumer LANGUAGES Java)
find_package(CycloneDDSJava CONFIG REQUIRED)
include(UseJava)
include(CTest)
idljava_generate(TARGET consumer-types FILES Consumer.idl)
set(CMAKE_JAVA_COMPILE_FLAGS --release 17 -encoding UTF-8 -Xlint:all -Werror)
add_jar(consumer SOURCES Consumer.java INCLUDE_JARS
  "${CycloneDDSJava_API_JAR}" "${CycloneDDSJava_IDL_API_JAR}" "${consumer-types_JAR}")
add_dependencies(consumer consumer-types)
if(WIN32)
  set(separator "\;")
else()
  set(separator ":")
endif()
set(classpath "${CMAKE_CURRENT_BINARY_DIR}/consumer.jar${separator}${consumer-types_JAR}${separator}${CycloneDDSJava_BUNDLED_RUNTIME_JAR}")
add_test(NAME consumer COMMAND "${Java_JAVA_EXECUTABLE}" -ea -Xcheck:jni
  "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
  -cp "${classpath}" Consumer)
set_tests_properties(consumer PROPERTIES TIMEOUT 15)
