cmake_minimum_required(VERSION 3.18)
project(unciv_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)

# Point LLAMA_CPP_DIR to a local checkout of https://github.com/ggml-org/llama.cpp
if(DEFINED LLAMA_CPP_DIR AND EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    add_subdirectory(${LLAMA_CPP_DIR} llama.cpp EXCLUDE_FROM_ALL)
    add_library(unciv_llama SHARED llama_jni.cpp)
    target_link_libraries(unciv_llama PRIVATE llama)
endif()
