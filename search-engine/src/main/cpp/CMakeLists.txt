cmake_minimum_required(VERSION 3.22.1)

project(nayti_search LANGUAGES CXX)

add_library(nayti_search_core STATIC
    segment_format.cpp
    exact_search.cpp
    mapped_segment.cpp
    sha256.cpp
)
target_compile_features(nayti_search_core PUBLIC cxx_std_20)
target_compile_options(nayti_search_core PRIVATE -Wall -Wextra -Werror)
set_target_properties(nayti_search_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

if(ANDROID)
    add_library(nayti_search SHARED native_bridge.cpp)
    target_link_libraries(nayti_search PRIVATE nayti_search_core)
    target_compile_features(nayti_search PRIVATE cxx_std_20)
    target_compile_options(nayti_search PRIVATE -Wall -Wextra -Werror)
    target_link_options(nayti_search PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
else()
    enable_testing()
    add_executable(nayti_vector_proof_test vector_proof_test.cpp)
    target_link_libraries(nayti_vector_proof_test PRIVATE nayti_search_core)
    target_compile_options(nayti_vector_proof_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME vector_proof COMMAND nayti_vector_proof_test)
    add_executable(nayti_vector_benchmark vector_benchmark.cpp)
    target_link_libraries(nayti_vector_benchmark PRIVATE nayti_search_core)
    target_compile_options(nayti_vector_benchmark PRIVATE -Wall -Wextra -Werror)
    add_test(NAME vector_scale_rehearsal COMMAND nayti_vector_benchmark)
endif()
