cmake_minimum_required(VERSION 3.22.1)
project(adguardian_yolo CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NCNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ncnn)
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
if (NOT EXISTS ${ncnn_DIR}/ncnnConfig.cmake)
    message(FATAL_ERROR
        "ncnn Android prebuilt is missing at ${NCNN_ROOT}/${ANDROID_ABI}\n"
        "Run tools/fetch_vision_deps.sh before Gradle")
endif()

find_package(ncnn REQUIRED)
find_library(log_lib log REQUIRED)
find_library(jnigraphics_lib jnigraphics REQUIRED)
find_library(android_lib android REQUIRED)

add_library(yolo_jni SHARED yolo_jni.cpp)
target_compile_features(yolo_jni PRIVATE cxx_std_17)
target_link_options(yolo_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(yolo_jni PRIVATE -fopenmp -static-openmp)
target_link_libraries(
    yolo_jni
    PRIVATE
    ncnn
    ${log_lib}
    ${jnigraphics_lib}
    ${android_lib}
)
