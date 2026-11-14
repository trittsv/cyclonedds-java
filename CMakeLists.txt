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
project(CycloneDDSJava VERSION 11.0.1 LANGUAGES C Java)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
if(MSVC)
  # Keep Unicode interoperability fixtures identical to the Java UTF-8 sources.
  add_compile_options(/utf-8)
endif()

find_package(CycloneDDS ${PROJECT_VERSION} EXACT REQUIRED)
find_package(Java 17 REQUIRED COMPONENTS Development)
include(UseJava)
include(CTest)
include(CMakePackageConfigHelpers)

add_subdirectory(src/jni)

file(GLOB_RECURSE OMG_API_SOURCES CONFIGURE_DEPENDS third_party/omg-dds/src/*.java)
# Compile the normative API unchanged and independently of provider code.
set(CMAKE_JAVA_COMPILE_FLAGS --release 8 -encoding UTF-8 -Xlint:none)
add_jar(omg-dds-api SOURCES ${OMG_API_SOURCES}
  RESOURCES NAMESPACE META-INF third_party/omg-dds/LICENSE third_party/omg-dds/NOTICE
  OUTPUT_NAME omg-dds-api)
install_jar(omg-dds-api DESTINATION share/java)
set(CMAKE_JAVA_COMPILE_FLAGS --release 17 -encoding UTF-8 -Xlint:all -Werror)
file(GLOB IDL_JAVA_API_SOURCES CONFIGURE_DEPENDS src/ddsjava/org/omg/type/*.java)
add_jar(idl4-java-api SOURCES ${IDL_JAVA_API_SOURCES}
  RESOURCES NAMESPACE META-INF LICENSE OUTPUT_NAME idl4-java-api)
install_jar(idl4-java-api DESTINATION share/java)
file(GLOB_RECURSE JAVA_SOURCES CONFIGURE_DEPENDS src/ddsjava/*.java)
list(FILTER JAVA_SOURCES EXCLUDE REGEX "/org/omg/type/")
add_jar(org.eclipse.cyclonedds SOURCES ${JAVA_SOURCES} INCLUDE_JARS omg-dds-api idl4-java-api
  RESOURCES NAMESPACE META-INF LICENSE THIRD_PARTY_NOTICES.md
  OUTPUT_NAME org.eclipse.cyclonedds)
install_jar(org.eclipse.cyclonedds DESTINATION share/java)
string(TOLOWER "${CMAKE_SYSTEM_NAME}" JAVA_NATIVE_OS)
if(APPLE)
  set(JAVA_NATIVE_OS macos)
endif()
string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" JAVA_NATIVE_ARCH)
if(JAVA_NATIVE_ARCH MATCHES "^(arm64|aarch64)$")
  set(JAVA_NATIVE_ARCH aarch64)
elseif(JAVA_NATIVE_ARCH MATCHES "^(amd64|x86_64)$")
  set(JAVA_NATIVE_ARCH x86_64)
endif()
set(JAVA_NATIVE_PLATFORM "${JAVA_NATIVE_OS}-${JAVA_NATIVE_ARCH}")
set(BUNDLED_RUNTIME_NAME "org.eclipse.cyclonedds-${PROJECT_VERSION}-${JAVA_NATIVE_PLATFORM}.jar")
set(BUNDLED_RUNTIME_JAR "${CMAKE_BINARY_DIR}/${BUNDLED_RUNTIME_NAME}")
get_target_property(CORE_LIBRARY_TYPE CycloneDDS::ddsc TYPE)
if(CORE_LIBRARY_TYPE STREQUAL "STATIC_LIBRARY")
  set(BUNDLE_CORE_LIBRARY "")
else()
  set(BUNDLE_CORE_LIBRARY "$<TARGET_FILE:CycloneDDS::ddsc>")
endif()
add_custom_command(OUTPUT "${BUNDLED_RUNTIME_JAR}"
  COMMAND ${CMAKE_COMMAND} "-DSTAGING=${CMAKE_BINARY_DIR}/runtime-bundle"
    "-DOMG_JAR=$<TARGET_PROPERTY:omg-dds-api,JAR_FILE>"
    "-DIDL_JAR=$<TARGET_PROPERTY:idl4-java-api,JAR_FILE>"
    "-DRUNTIME_JAR=$<TARGET_PROPERTY:org.eclipse.cyclonedds,JAR_FILE>"
    "-DCORE_LIBRARY=${BUNDLE_CORE_LIBRARY}" "-DJNI_LIBRARY=$<TARGET_FILE:cycloneddsjava>"
    "-DVERSION=${PROJECT_VERSION}" "-DPLATFORM=${JAVA_NATIVE_PLATFORM}" "-DJAR=${Java_JAR_EXECUTABLE}"
    "-DOUTPUT=${BUNDLED_RUNTIME_JAR}" "-DSOURCE_DIR=${PROJECT_SOURCE_DIR}"
    -P "${PROJECT_SOURCE_DIR}/cmake/BundleRuntime.cmake"
  DEPENDS omg-dds-api idl4-java-api org.eclipse.cyclonedds cycloneddsjava CycloneDDS::ddsc
    "$<TARGET_PROPERTY:omg-dds-api,JAR_FILE>" "$<TARGET_PROPERTY:idl4-java-api,JAR_FILE>"
    "$<TARGET_PROPERTY:org.eclipse.cyclonedds,JAR_FILE>" "$<TARGET_FILE:cycloneddsjava>"
    cmake/BundleRuntime.cmake third_party/cyclonedds/LICENSE third_party/cyclonedds/NOTICE.md
  VERBATIM)
add_custom_target(bundled-runtime ALL DEPENDS "${BUNDLED_RUNTIME_JAR}")
install(FILES "${BUNDLED_RUNTIME_JAR}" DESTINATION share/java)
install(FILES LICENSE THIRD_PARTY_NOTICES.md DESTINATION share/doc/org.eclipse.cyclonedds)
install(FILES README.md DESTINATION share/doc/org.eclipse.cyclonedds)
install(DIRECTORY docs/ DESTINATION share/doc/org.eclipse.cyclonedds/docs)
install(FILES third_party/omg-dds/LICENSE third_party/omg-dds/NOTICE DESTINATION share/doc/org.eclipse.cyclonedds/omg-dds)

add_subdirectory(src/idljava)
add_subdirectory(src/buildtools)
set(UNIVERSAL_BUNDLE_INPUTS "" CACHE STRING "Additional platform runtime/compiler JARs to merge")
set(UNIVERSAL_REQUIRED_PLATFORMS "${JAVA_NATIVE_PLATFORM}" CACHE STRING "Required platform pairs; CI release assembly requires all supported platforms")
string(REPLACE ";" "," UNIVERSAL_PLATFORM_ARGUMENT "${UNIVERSAL_REQUIRED_PLATFORMS}")
set(UNIVERSAL_RUNTIME_NAME "org.eclipse.cyclonedds-${PROJECT_VERSION}.jar")
set(UNIVERSAL_RUNTIME_JAR "${CMAKE_BINARY_DIR}/${UNIVERSAL_RUNTIME_NAME}")
add_custom_command(OUTPUT "${UNIVERSAL_RUNTIME_JAR}"
  COMMAND "${Java_JAVA_EXECUTABLE}" -cp "$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>"
    org.eclipse.cyclonedds.idlc.UniversalJar --kind runtime --output "${UNIVERSAL_RUNTIME_JAR}"
    --version "${PROJECT_VERSION}" --platforms "${UNIVERSAL_PLATFORM_ARGUMENT}"
    "${BUNDLED_RUNTIME_JAR}" "${BUNDLED_IDLC_JAR}" ${UNIVERSAL_BUNDLE_INPUTS}
  DEPENDS bundled-runtime bundled-idlc idlc-launcher
    "${BUNDLED_RUNTIME_JAR}" "${BUNDLED_IDLC_JAR}"
    "$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>" ${UNIVERSAL_BUNDLE_INPUTS}
  VERBATIM)
add_custom_target(universal-runtime ALL DEPENDS "${UNIVERSAL_RUNTIME_JAR}")
install(FILES "${UNIVERSAL_RUNTIME_JAR}" DESTINATION share/java)
set(UNIVERSAL_IDLC_NAME "org.eclipse.cyclonedds.idlc-${PROJECT_VERSION}.jar")
set(UNIVERSAL_IDLC_JAR "${CMAKE_BINARY_DIR}/${UNIVERSAL_IDLC_NAME}")
add_custom_command(OUTPUT "${UNIVERSAL_IDLC_JAR}"
  COMMAND "${Java_JAVA_EXECUTABLE}" -cp "$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>"
    org.eclipse.cyclonedds.idlc.UniversalJar --kind compiler --output "${UNIVERSAL_IDLC_JAR}"
    --version "${PROJECT_VERSION}" --platforms "${UNIVERSAL_PLATFORM_ARGUMENT}"
    "${BUNDLED_RUNTIME_JAR}" "${BUNDLED_IDLC_JAR}" ${UNIVERSAL_BUNDLE_INPUTS}
  DEPENDS bundled-runtime bundled-idlc idlc-launcher
    "${BUNDLED_RUNTIME_JAR}" "${BUNDLED_IDLC_JAR}"
    "$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>" ${UNIVERSAL_BUNDLE_INPUTS}
  VERBATIM)
add_custom_target(universal-idlc ALL DEPENDS "${UNIVERSAL_IDLC_JAR}")
install(FILES "${UNIVERSAL_IDLC_JAR}" DESTINATION share/java)
if(WIN32)
  find_program(MAVEN_EXECUTABLE NAMES mvn.cmd HINTS "$ENV{MAVEN_HOME}/bin")
else()
  find_program(MAVEN_EXECUTABLE NAMES mvn HINTS "$ENV{MAVEN_HOME}/bin")
endif()
if(MAVEN_EXECUTABLE)
  add_custom_target(maven-install
    COMMAND "${MAVEN_EXECUTABLE}" -B -f "${PROJECT_SOURCE_DIR}/maven/pom.xml"
      "-Dcyclonedds.runtime.jar=${UNIVERSAL_RUNTIME_JAR}"
      "-Dcyclonedds.idlc.jar=${UNIVERSAL_IDLC_JAR}" install
    DEPENDS universal-runtime universal-idlc USES_TERMINAL VERBATIM)
endif()
configure_package_config_file(cmake/CycloneDDSJavaConfig.cmake.in
  "${CMAKE_CURRENT_BINARY_DIR}/CycloneDDSJavaConfig.cmake" INSTALL_DESTINATION lib/cmake/CycloneDDSJava)
write_basic_package_version_file("${CMAKE_CURRENT_BINARY_DIR}/CycloneDDSJavaConfigVersion.cmake"
  VERSION ${PROJECT_VERSION} COMPATIBILITY SameMinorVersion)
install(EXPORT CycloneDDSJavaTargets NAMESPACE CycloneDDSJava:: DESTINATION lib/cmake/CycloneDDSJava)
install(FILES "${CMAKE_CURRENT_BINARY_DIR}/CycloneDDSJavaConfig.cmake"
  "${CMAKE_CURRENT_BINARY_DIR}/CycloneDDSJavaConfigVersion.cmake"
  cmake/IdlJavaGenerate.cmake cmake/CompileGeneratedJava.cmake DESTINATION lib/cmake/CycloneDDSJava)
include(cmake/IdlJavaGenerate.cmake)
option(BUILD_EXAMPLES "Build Java DDS examples" ON)
if(BUILD_EXAMPLES OR BUILD_TESTING)
  add_subdirectory(examples/common)
  add_subdirectory(examples/helloworld)
endif()
if(BUILD_EXAMPLES)
  add_subdirectory(examples)
endif()
if(BUILD_TESTING)
  add_subdirectory(tests)
  find_package(Python3 COMPONENTS Interpreter QUIET)
  if(Python3_Interpreter_FOUND)
    add_test(NAME upstream-coverage-audit COMMAND "${Python3_EXECUTABLE}"
      "${CMAKE_CURRENT_SOURCE_DIR}/tests/upstream/audit.py" --check --build "${CMAKE_CURRENT_BINARY_DIR}")
  endif()
  if(Python3_Interpreter_FOUND)
    add_test(NAME idlc-bundle COMMAND "${Python3_EXECUTABLE}"
      "${PROJECT_SOURCE_DIR}/tests/idlc_bundle.py" "${Java_JAVA_EXECUTABLE}" "${Java_JAVAC_EXECUTABLE}"
      "${UNIVERSAL_IDLC_JAR}" "${UNIVERSAL_RUNTIME_JAR}" "${PROJECT_SOURCE_DIR}")
    set_tests_properties(idlc-bundle PROPERTIES TIMEOUT 60)
    add_test(NAME universal-assembly COMMAND "${Python3_EXECUTABLE}"
      "${PROJECT_SOURCE_DIR}/tests/universal_assembly.py" "${Java_JAVA_EXECUTABLE}"
      "$<TARGET_PROPERTY:idlc-launcher,JAR_FILE>" "${BUNDLED_RUNTIME_JAR}" "${BUNDLED_IDLC_JAR}"
      "${UNIVERSAL_RUNTIME_JAR}" "${PROJECT_VERSION}")
    set_tests_properties(universal-assembly PROPERTIES TIMEOUT 60)
    option(BUILD_MAVEN_TESTING "Run Maven compiler/plugin consumer tests (downloads Maven dependencies)" OFF)
    if(BUILD_MAVEN_TESTING)
      if(NOT MAVEN_EXECUTABLE)
        message(FATAL_ERROR "BUILD_MAVEN_TESTING requires Maven 3.9+")
      endif()
      set(MAVEN_TEST_REPOSITORY "${CMAKE_BINARY_DIR}/maven-test-repository" CACHE PATH "Isolated Maven integration-test repository")
      add_test(NAME maven-consumer COMMAND "${Python3_EXECUTABLE}"
        "${PROJECT_SOURCE_DIR}/tests/maven_consumer.py" --maven "${MAVEN_EXECUTABLE}"
        --build "${CMAKE_BINARY_DIR}" --platform "${JAVA_NATIVE_PLATFORM}" --version "${PROJECT_VERSION}"
        --repository "${MAVEN_TEST_REPOSITORY}")
      set_tests_properties(maven-consumer PROPERTIES TIMEOUT 300 RUN_SERIAL TRUE)
    endif()
  endif()
  idljava_generate(TARGET test-types FILES tests/idl/Types.idl tests/idl/Naming.idl tests/idl/Annotations.idl
    tests/upstream/idl/RecursiveExternalUnionCase.idl
    tests/upstream/idl/RecursiveMutualSequence.idl
    tests/upstream/idl/RecursiveOptionalArraySelf.idl
    tests/upstream/idl/RecursiveOptionalExternal.idl
    tests/upstream/idl/RecursiveOptionalSelf.idl
    tests/upstream/idl/RecursiveOptionalSequenceSelf.idl
    tests/upstream/idl/RecursiveOptionalTypedefSequenceSelf.idl)
  file(GLOB_RECURSE TEST_SOURCES CONFIGURE_DEPENDS tests/java/*.java)
  add_jar(java-tests SOURCES ${TEST_SOURCES} INCLUDE_JARS omg-dds-api idl4-java-api org.eclipse.cyclonedds helloworld-java "${hello-types_JAR}" "${test-types_JAR}")
  add_dependencies(java-tests hello-types test-types)
  file(GLOB_RECURSE PORTABLE_TEST_SOURCES CONFIGURE_DEPENDS tests/portable/*.java)
  add_jar(omg-api-tests SOURCES ${PORTABLE_TEST_SOURCES}
    INCLUDE_JARS omg-dds-api idl4-java-api "${hello-types_JAR}" "${test-types_JAR}")
  add_dependencies(omg-api-tests hello-types test-types)
  if(WIN32)
    set(CP_SEPARATOR "\;")
  else()
    set(CP_SEPARATOR ":")
  endif()
  set(TEST_CP "${CMAKE_CURRENT_BINARY_DIR}/java-tests.jar${CP_SEPARATOR}${CMAKE_CURRENT_BINARY_DIR}/omg-dds-api.jar${CP_SEPARATOR}${CMAKE_CURRENT_BINARY_DIR}/idl4-java-api.jar${CP_SEPARATOR}${CMAKE_CURRENT_BINARY_DIR}/org.eclipse.cyclonedds.jar${CP_SEPARATOR}${CMAKE_CURRENT_BINARY_DIR}/examples/helloworld/hello-types.jar${CP_SEPARATOR}${test-types_JAR}")
  foreach(value_case duration-add duration-subtract duration-units duration-compare duration-special
      time-add time-subtract time-units time-compare time-copies time-invalid nil-handle)
    add_test(NAME "upstream-${value_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea
      "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
      -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}"
      org.eclipse.cyclonedds.tests.UpstreamValuesTest "${value_case}")
  endforeach()
  foreach(state_case read-masks query-masks selector-read selector-take)
    add_test(NAME "upstream-${state_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
      "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
      "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
      -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}"
      org.eclipse.cyclonedds.tests.UpstreamStateTest "${state_case}")
    set_tests_properties("upstream-${state_case}" PROPERTIES TIMEOUT 30 RESOURCE_LOCK upstream-state-domain)
  endforeach()
  foreach(entity_case parent-links default-endpoint-qos default-container-qos lookup-retain
      retain-close close-cascade matched-endpoint-sets registration-keys)
    add_test(NAME "upstream-${entity_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
      "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
      "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
      -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}"
      org.eclipse.cyclonedds.tests.UpstreamEntityTest "${entity_case}")
    set_tests_properties("upstream-${entity_case}" PROPERTIES TIMEOUT 30 RESOURCE_LOCK upstream-entity-domain)
  endforeach()
  foreach(reader_case generations-auto-unregister-only generations-auto-dispose-only generations-auto-both
      generations-manual-unregister-only generations-manual-dispose-only generations-manual-both
      buffer-resize many-instances loan-snapshot empty-buffer-resize invalid-read-count
      keyless-mutable-dispose read-next-many-instances communication-read communication-take
      communication-order communication-read-preserves communication-take-consumes)
    add_test(NAME "upstream-${reader_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
      "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
      "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
      -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}"
      org.eclipse.cyclonedds.tests.UpstreamReaderTest "${reader_case}")
    set_tests_properties("upstream-${reader_case}" PROPERTIES TIMEOUT 30 RESOURCE_LOCK upstream-reader-domain)
  endforeach()
  add_test(NAME cdr COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.CdrTest)
  foreach(recursive_case optional-self optional-array optional-external external-union mutual-sequence
      optional-sequence optional-typedef-sequence)
    add_test(NAME "upstream-recursive-${recursive_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea
      -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.UpstreamRecursiveTest "${recursive_case}")
  endforeach()
  add_test(NAME upstream-recursive-incomplete-array COMMAND ${CMAKE_COMMAND}
    "-DIDLC=$<TARGET_FILE:CycloneDDS::idlc>" "-DBACKEND=$<TARGET_FILE:idljava>"
    "-DINPUT=${CMAKE_CURRENT_SOURCE_DIR}/tests/upstream/idl/RecursiveOptionalTypedefArraySelf.idl"
    "-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/recursive-incomplete-array"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/upstream/reject_incomplete_array.cmake")
  foreach(cdr_case primitive-boundaries float-boundaries alignment-endianness truncated-payload
      nested-read-limits mutable-member-order mutable-must-understand sequence-lengths string-boundaries)
    add_test(NAME "upstream-cdr-${cdr_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea
      -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.UpstreamCdrTest "${cdr_case}")
  endforeach()
  add_test(NAME idl-java-mapping COMMAND ${Java_JAVA_EXECUTABLE} -ea -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.IdlMappingTest)
  foreach(bound_case strings sequence boolean)
    add_test(NAME "upstream-bounds-${bound_case}" COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
      "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
      "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
      -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.UpstreamBoundsTest "${bound_case}")
    set_tests_properties("upstream-bounds-${bound_case}" PROPERTIES TIMEOUT 30 RESOURCE_LOCK upstream-bounds-domain)
  endforeach()
  add_test(NAME omg-api COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
    -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}" org.eclipse.cyclonedds.tests.OmgApiTest)
  add_test(NAME omg-selectors COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
    -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}" org.eclipse.cyclonedds.tests.OmgSelectorTest)
  add_test(NAME omg-waitsets COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
    -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}" org.eclipse.cyclonedds.tests.OmgWaitSetTest)
  add_test(NAME omg-qos COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
    -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}" org.eclipse.cyclonedds.tests.OmgQosTest)
  add_test(NAME qos-interop COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
    -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}"
    org.eclipse.cyclonedds.tests.OmgQosInteropTest "$<TARGET_FILE:qos-peer>")
  add_test(NAME omg-discovery COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
    -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}"
    org.eclipse.cyclonedds.tests.OmgDiscoveryTest "$<TARGET_FILE:discovery-peer>")
  set_tests_properties(omg-discovery PROPERTIES TIMEOUT 40
    ENVIRONMENT "CYCLONEDDS_URI=<CycloneDDS><Domain><General><Interfaces><NetworkInterface address=\"127.0.0.1\"/></Interfaces><AllowMulticast>false</AllowMulticast></General><Discovery><ParticipantIndex>auto</ParticipantIndex><EnableTopicDiscoveryEndpoints>true</EnableTopicDiscoveryEndpoints><Peers><Peer Address=\"127.0.0.1\"/></Peers></Discovery></Domain></CycloneDDS>")
  set_tests_properties(qos-interop PROPERTIES TIMEOUT 30
    ENVIRONMENT "CYCLONEDDS_URI=<CycloneDDS><Domain><General><Interfaces><NetworkInterface address=\"127.0.0.1\"/></Interfaces><AllowMulticast>false</AllowMulticast></General><Discovery><ParticipantIndex>auto</ParticipantIndex><Peers><Peer Address=\"127.0.0.1\"/></Peers></Discovery></Domain></CycloneDDS>")
  foreach(operation_test Operations Listener QosBehavior)
    string(TOLOWER "${operation_test}" operation_name)
    add_test(NAME "omg-${operation_name}" COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
      "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
      "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>"
      -cp "${CMAKE_CURRENT_BINARY_DIR}/omg-api-tests.jar${CP_SEPARATOR}${TEST_CP}" "org.eclipse.cyclonedds.tests.Omg${operation_test}Test")
    set_tests_properties("omg-${operation_name}" PROPERTIES TIMEOUT 40)
  endforeach()
  add_test(NAME generated-types COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.GeneratedTypesTest)
  add_test(NAME annotations COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.AnnotationTest)
  set_tests_properties(annotations PROPERTIES TIMEOUT 30)
  add_test(NAME reject-annotation-schemas COMMAND ${CMAKE_COMMAND}
    "-DIDLC=$<TARGET_FILE:CycloneDDS::idlc>" "-DBACKEND=$<TARGET_FILE:idljava>"
    "-DINPUT_DIR=${PROJECT_SOURCE_DIR}/tests/idl" "-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/rejected-annotations"
    -P "${PROJECT_SOURCE_DIR}/tests/reject_annotations.cmake")
  add_test(NAME reject-unsupported-idl COMMAND ${CMAKE_COMMAND}
    "-DIDLC=$<TARGET_FILE:CycloneDDS::idlc>" "-DBACKEND=$<TARGET_FILE:idljava>"
    "-DINPUT=${PROJECT_SOURCE_DIR}/tests/idl/Unsupported.idl" "-DOUTPUT=${CMAKE_CURRENT_BINARY_DIR}/rejected-idl"
    -P "${PROJECT_SOURCE_DIR}/tests/reject_unsupported.cmake")
  add_test(NAME pubsub COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}" org.eclipse.cyclonedds.tests.PubSubTest)
  add_test(NAME c-interop COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.InteropTest "$<TARGET_FILE:interop-peer>")
  add_test(NAME collections-interop COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.CollectionsTest "$<TARGET_FILE:collections-peer>")
  add_test(NAME structured-interop COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.StructuredTest "$<TARGET_FILE:structured-peer>")
  add_test(NAME broader-idl-interop COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.BroaderTest "$<TARGET_FILE:broader-peer>")
  add_test(NAME xcdr1-broader-interop COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.BroaderTest "$<TARGET_FILE:broader-peer>" 1)
  add_test(NAME xcdr1 COMMAND ${Java_JAVA_EXECUTABLE} -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=$<TARGET_FILE_DIR:cycloneddsjava>" -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.Xcdr1Test "$<TARGET_FILE:broader-peer>")
  set_tests_properties(broader-idl-interop xcdr1-broader-interop xcdr1 PROPERTIES TIMEOUT 40 RESOURCE_LOCK broader-domain
    ENVIRONMENT "CYCLONEDDS_URI=<CycloneDDS><Domain><General><Interfaces><NetworkInterface address=\"127.0.0.1\"/></Interfaces><AllowMulticast>false</AllowMulticast></General><Discovery><ParticipantIndex>auto</ParticipantIndex><Peers><Peer Address=\"127.0.0.1\"/></Peers></Discovery></Domain></CycloneDDS>")
  set_tests_properties(pubsub c-interop collections-interop structured-interop generated-types omg-api omg-selectors omg-waitsets omg-qos PROPERTIES TIMEOUT 30)
  # These tests launch a separate C process. Use explicit loopback discovery so
  # they do not depend on multicast between processes on hosted runners.
  # The C peer inherits CYCLONEDDS_URI from the Java process.
  set_tests_properties(c-interop collections-interop structured-interop PROPERTIES
    ENVIRONMENT "CYCLONEDDS_URI=<CycloneDDS><Domain><General><Interfaces><NetworkInterface address=\"127.0.0.1\"/></Interfaces><AllowMulticast>false</AllowMulticast></General><Discovery><ParticipantIndex>auto</ParticipantIndex><Peers><Peer Address=\"127.0.0.1\"/></Peers></Discovery></Domain></CycloneDDS>")
  add_test(NAME installed-consumer COMMAND ${CMAKE_COMMAND}
    "-DBUILD_DIR=${CMAKE_BINARY_DIR}" "-DSOURCE_DIR=${PROJECT_SOURCE_DIR}"
    "-DCORE_DIR=${CycloneDDS_DIR}" "-DJAVA=${Java_JAVA_EXECUTABLE}"
    -P "${PROJECT_SOURCE_DIR}/tests/installed_consumer.cmake")
  set_tests_properties(installed-consumer PROPERTIES TIMEOUT 90 RUN_SERIAL TRUE)
  add_test(NAME bundled-runtime COMMAND "${Java_JAVA_EXECUTABLE}" -ea -Xcheck:jni
    "-Dorg.omg.dds.serviceClassName=org.eclipse.cyclonedds.omg.CycloneServiceEnvironment"
    "-Djava.library.path=${CMAKE_BINARY_DIR}/no-system-natives"
    -cp "${UNIVERSAL_RUNTIME_JAR}${CP_SEPARATOR}${CMAKE_BINARY_DIR}/consumer-build/consumer.jar${CP_SEPARATOR}${CMAKE_BINARY_DIR}/consumer-build/consumer-types.jar" Consumer)
  set_tests_properties(bundled-runtime PROPERTIES TIMEOUT 30 DEPENDS installed-consumer
    ENVIRONMENT "LD_LIBRARY_PATH=;DYLD_LIBRARY_PATH=;DYLD_FALLBACK_LIBRARY_PATH=")
  add_test(NAME native-bundle-errors COMMAND "${Java_JAVA_EXECUTABLE}" -ea -cp "${TEST_CP}"
    org.eclipse.cyclonedds.tests.NativeBundleTest "${UNIVERSAL_RUNTIME_JAR}")
  set_tests_properties(native-bundle-errors PROPERTIES TIMEOUT 30)
endif()

install(DIRECTORY tests/consumer/ DESTINATION share/org.eclipse.cyclonedds/consumer-example)
set(CPACK_PACKAGE_NAME org.eclipse.cyclonedds)
set(CPACK_PACKAGE_VERSION "${PROJECT_VERSION}")
set(CPACK_PACKAGE_VENDOR "Cyclone DDS Java contributors")
set(CPACK_PACKAGE_DESCRIPTION_SUMMARY "Experimental OMG Java provider and IDL compiler for Cyclone DDS")
set(CPACK_RESOURCE_FILE_LICENSE "${PROJECT_SOURCE_DIR}/LICENSE")
set(CPACK_GENERATOR TGZ ZIP)
set(CPACK_PACKAGE_CHECKSUM SHA256)
set(CPACK_SYSTEM_NAME "${CMAKE_SYSTEM_NAME}-${CMAKE_SYSTEM_PROCESSOR}")
include(CPack)
