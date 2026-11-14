# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

find_package(JNI REQUIRED)

add_library(cycloneddsjava SHARED bridge.c typesupport.c)
set_target_properties(cycloneddsjava PROPERTIES INSTALL_RPATH_USE_LINK_PATH TRUE)
target_compile_features(cycloneddsjava PRIVATE c_std_11)
target_include_directories(cycloneddsjava PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(cycloneddsjava PRIVATE CycloneDDS::ddsc)
if(MSVC)
  target_compile_options(cycloneddsjava PRIVATE /experimental:c11atomics)
endif()
if(CMAKE_C_COMPILER_ID MATCHES "Clang|GNU")
  target_compile_options(cycloneddsjava PRIVATE -Wall -Wextra -Werror)
endif()

add_library(CycloneDDSJava::cycloneddsjava ALIAS cycloneddsjava)
install(TARGETS cycloneddsjava EXPORT CycloneDDSJavaTargets LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
