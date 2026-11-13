# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

# Like IDLCXX_GENERATE, invoke Cyclone's idlc with a language backend. Generated
# sources are compiled at build time so no hand-maintained list of IDL types is needed.
function(idljava_generate)
  cmake_parse_arguments(JAVA "" "TARGET" "FILES;INCLUDES;DEPENDS" ${ARGN})
  if(NOT JAVA_TARGET OR NOT JAVA_FILES)
    message(FATAL_ERROR "idljava_generate requires TARGET and FILES")
  endif()
  set(output_dir "${CMAKE_CURRENT_BINARY_DIR}/${JAVA_TARGET}-sources")
  set(jar "${CMAKE_CURRENT_BINARY_DIR}/${JAVA_TARGET}.jar")
  if(TARGET org.eclipse.cyclonedds)
    set(runtime_jar "$<TARGET_PROPERTY:org.eclipse.cyclonedds,JAR_FILE>")
    set(idl_api_jar "$<TARGET_PROPERTY:idl4-java-api,JAR_FILE>")
    set(runtime_dep org.eclipse.cyclonedds idl4-java-api "${runtime_jar}" "${idl_api_jar}")
  else()
    set(runtime_jar "${CycloneDDSJava_RUNTIME_JAR}")
    set(idl_api_jar "${CycloneDDSJava_IDL_API_JAR}")
    set(runtime_dep "${runtime_jar}" "${idl_api_jar}")
  endif()
  set(commands COMMAND ${CMAKE_COMMAND} -E rm -rf "${output_dir}"
               COMMAND ${CMAKE_COMMAND} -E make_directory "${output_dir}")
  set(include_args)
  foreach(include_dir IN LISTS JAVA_INCLUDES)
    list(APPEND include_args -I "${include_dir}")
  endforeach()
  set(input_files)
  foreach(input IN LISTS JAVA_FILES)
    get_filename_component(input "${input}" ABSOLUTE)
    list(APPEND input_files "${input}")
    list(APPEND commands COMMAND CycloneDDS::idlc -l "$<TARGET_FILE:CycloneDDSJava::idljava>"
      -o "${output_dir}" ${include_args} "${input}")
  endforeach()
  add_custom_command(OUTPUT "${jar}"
    ${commands}
    COMMAND ${CMAKE_COMMAND} "-DSOURCE_DIR=${output_dir}"
      "-DJAVAC=${Java_JAVAC_EXECUTABLE}" "-DJAR=${Java_JAR_EXECUTABLE}"
      "-DRUNTIME_JAR=${runtime_jar}" "-DIDL_API_JAR=${idl_api_jar}" "-DOUTPUT_JAR=${jar}"
      -P "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/CompileGeneratedJava.cmake"
    DEPENDS CycloneDDSJava::idljava ${runtime_dep} ${input_files} ${JAVA_DEPENDS}
      "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/CompileGeneratedJava.cmake"
    VERBATIM)
  add_custom_target(${JAVA_TARGET} ALL DEPENDS "${jar}")
  set_target_properties(${JAVA_TARGET} PROPERTIES JAR_FILE "${jar}")
  set(${JAVA_TARGET}_JAR "${jar}" PARENT_SCOPE)
endfunction()
