cmake_minimum_required(VERSION 3.22.1)

project(nayti_runtime LANGUAGES CXX)

add_library(nayti_runtime_core STATIC runtime_contract.cpp)
target_compile_features(nayti_runtime_core PUBLIC cxx_std_20)
target_compile_options(nayti_runtime_core PRIVATE -Wall -Wextra -Werror)
set_target_properties(nayti_runtime_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(nayti_runtime SHARED native_bridge.cpp)
    target_link_libraries(nayti_runtime PRIVATE nayti_runtime_core)
    target_link_options(nayti_runtime PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
    target_compile_features(nayti_runtime PRIVATE cxx_std_20)
    target_compile_options(nayti_runtime PRIVATE -Wall -Wextra -Werror)
else()
    enable_testing()
    add_executable(nayti_runtime_contract_test runtime_contract_test.cpp)
    target_link_libraries(nayti_runtime_contract_test PRIVATE nayti_runtime_core)
    add_test(NAME runtime_contract COMMAND nayti_runtime_contract_test)
endif()
