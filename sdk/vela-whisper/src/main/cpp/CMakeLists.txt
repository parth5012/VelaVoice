cmake_minimum_required(VERSION 3.13)

project("whisper")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# Compile out assert() in whisper.cpp / ggml.c so that violated internal
# invariants (e.g. hparams.n_text_state != n_audio_state on a corrupt model)
# hit the guarding if-return or throw path instead of calling abort().
# The JNI layer wraps every call in try/catch so recoverable errors are
# surfaced as Java exceptions rather than killing the process.
add_definitions(-DNDEBUG)

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

target_compile_options(whisper PRIVATE
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    -fvisibility=hidden
)

target_link_options(whisper PRIVATE
    -Wl,-z,relro
    -Wl,-z,now
)

find_library(log-lib log)
target_link_libraries(whisper ${log-lib})
