cmake_minimum_required(VERSION 3.10.2)

project("whisper")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

add_library(whisper SHARED
    whisper.h
    whisper.cpp
    ggml.h
    ggml.c
    ggml-alloc.h
    ggml-alloc.c
    ggml-backend.h
    ggml-backend.c
    ggml-quants.h
    ggml-quants.c
    whisper-jni.cpp
)

find_library(log-lib log)
target_link_libraries(whisper ${log-lib})
