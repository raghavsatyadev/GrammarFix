cmake_minimum_required(VERSION 3.22.1)
project(grammarfix_llama LANGUAGES C CXX)

# Build configuration mirrors kolama's Android setup (github.com/rajumark/kolama).
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)

# One CPU backend .so per ISA variant; the best one for the chip is loaded at runtime.
set(GGML_OPENMP           ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
unset(GGML_CPU_ARM_ARCH CACHE)
set(BUILD_SHARED_LIBS     ON CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(grammarfix_llama SHARED grammarfix_llama.cpp)
target_link_libraries(grammarfix_llama PRIVATE llama ggml android log)
set_target_properties(grammarfix_llama PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
