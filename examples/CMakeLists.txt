# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

foreach(example roundtrip throughput vehicle async builtin_topics listeners waitset lifecycle)
  add_subdirectory(${example})
endforeach()
# Java reads the provider setting and complete classpath directly from this file.
# Keep the platform separator and quoted paths out of application start commands.
set(example_jars "${BUNDLED_RUNTIME_JAR}")
foreach(target example-common helloworld-java hello-types roundtrip-types throughput-types vehicle-types
    roundtrip-example throughput-example vehicle-example async-example builtin_topics-example
    listeners-example waitset-example lifecycle-example)
  list(APPEND example_jars "$<TARGET_PROPERTY:${target},JAR_FILE>")
endforeach()
if(WIN32)
  set(example_separator ";")
else()
  set(example_separator ":")
endif()
list(JOIN example_jars "${example_separator}" example_classpath)
configure_file(java.args.in "${CMAKE_CURRENT_BINARY_DIR}/java.args.in" @ONLY)
file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/java.args"
  INPUT "${CMAKE_CURRENT_BINARY_DIR}/java.args.in")
if(BUILD_TESTING)
  find_package(Python3 COMPONENTS Interpreter QUIET)
  if(Python3_Interpreter_FOUND)
    foreach(scenario hello simple roundtrip throughput vehicle async builtin listeners waitset lifecycle)
      add_test(NAME "example-${scenario}" COMMAND "${Python3_EXECUTABLE}"
        "${PROJECT_SOURCE_DIR}/tests/examples/run.py" --java "${Java_JAVA_EXECUTABLE}"
        --build "${CMAKE_BINARY_DIR}" --scenario "${scenario}")
      set_tests_properties("example-${scenario}" PROPERTIES TIMEOUT 50 RESOURCE_LOCK example-domain)
    endforeach()
  endif()
endif()
install(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/" DESTINATION share/org.eclipse.cyclonedds/examples)
