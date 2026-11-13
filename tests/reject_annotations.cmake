# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

foreach(fixture IN ITEMS UnsupportedNaming UnsupportedLabel CoreWcharUnion CoreWideAnnotation CoreRepeatedAnnotation CoreNegativeUnion)
  file(REMOVE_RECURSE "${OUTPUT}/${fixture}")
  file(MAKE_DIRECTORY "${OUTPUT}/${fixture}")
  execute_process(COMMAND "${IDLC}" -l "${BACKEND}" -o "${OUTPUT}/${fixture}" "${INPUT_DIR}/${fixture}.idl"
    RESULT_VARIABLE result ERROR_VARIABLE error)
  if(result EQUAL 0 OR NOT error MATCHES "naming collision|Case label value must be in range INT32_MIN|unmodified Cyclone DDS core|syntax error|Incompatible reapplication")
    message(FATAL_ERROR "Missing precise annotation/schema diagnostic: ${fixture}: ${error}")
  endif()
  if(EXISTS "${OUTPUT}/${fixture}/Collision/MyType.java" OR EXISTS "${OUTPUT}/${fixture}/Unsupported/Changeable.java")
    message(FATAL_ERROR "Backend left an invalid generated Java class")
  endif()
endforeach()
