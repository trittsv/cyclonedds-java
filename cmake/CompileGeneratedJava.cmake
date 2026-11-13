# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

file(GLOB_RECURSE sources "${SOURCE_DIR}/*.java")
if(NOT sources)
  message(FATAL_ERROR "idljava emitted no Java sources")
endif()
set(class_dir "${SOURCE_DIR}/classes")
file(MAKE_DIRECTORY "${class_dir}")
set(arguments "")
foreach(source IN LISTS sources)
  string(APPEND arguments "\"${source}\"\n")
endforeach()
file(WRITE "${SOURCE_DIR}/javac.args" "${arguments}")
if(WIN32)
  set(classpath "${RUNTIME_JAR}\;${IDL_API_JAR}")
else()
  set(classpath "${RUNTIME_JAR}:${IDL_API_JAR}")
endif()
execute_process(COMMAND "${JAVAC}" --release 17 -encoding UTF-8 -Xlint:all -Werror
  -cp "${classpath}" -d "${class_dir}" "@${SOURCE_DIR}/javac.args" RESULT_VARIABLE status)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "Compilation of generated Java failed")
endif()
execute_process(COMMAND "${JAR}" --create --file "${OUTPUT_JAR}" -C "${class_dir}" . RESULT_VARIABLE status)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "Packaging of generated Java failed")
endif()
