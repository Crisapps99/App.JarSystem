cmake_minimum_required(VERSION 3.22.1)
project("whisper_android")

# ─────────────────────────────────────────────────────────────────────────────
# CMakeLists.txt para whisper.cpp v1.4.0
# Estructura plana — todos los archivos en la raíz:
#   cpp/whisper.cpp/
#   ├── ggml.c       ← único archivo ggml
#   ├── ggml.h
#   ├── whisper.cpp  ← fuente principal
#   ├── whisper.h
#   └── ...
# ─────────────────────────────────────────────────────────────────────────────

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -ffast-math -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -DNDEBUG")

# ─── Librería ggml ────────────────────────────────────────────────────────────
add_library(ggml STATIC
        ${WHISPER_DIR}/ggml.c
)
target_include_directories(ggml PUBLIC ${WHISPER_DIR})

# ─── Librería whisper + JNI ───────────────────────────────────────────────────
add_library(whisper SHARED
        ${WHISPER_DIR}/whisper.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp
)
target_include_directories(whisper PRIVATE ${WHISPER_DIR})

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(whisper ggml ${log-lib} ${android-lib})