cmake_minimum_required(VERSION 3.19)
project(xr-composite C CXX)

# xr-composite — render a SpatialScene (scene.json + panel PNGs) to a composite PNG, headless.
# Links against a prebuilt Filament desktop SDK; see build.sh and README.md.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- Locate the Filament desktop SDK (the unpacked release tarball: include/, lib/, bin/) ----
if(NOT FILAMENT_SDK)
  set(FILAMENT_SDK "$ENV{FILAMENT_SDK}")
endif()
if(NOT FILAMENT_SDK OR NOT EXISTS "${FILAMENT_SDK}/include/filament/Engine.h")
  message(FATAL_ERROR
    "FILAMENT_SDK is not set or does not point at an unpacked Filament SDK. "
    "Run ./build.sh (which downloads it) or pass -DFILAMENT_SDK=/path/to/filament.")
endif()
message(STATUS "Using Filament SDK: ${FILAMENT_SDK}")

# Filament ships per-arch static libs under lib/<arch>/.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  set(FILAMENT_ARCH "aarch64")
else()
  set(FILAMENT_ARCH "x86_64")
endif()
set(FILAMENT_LIB_DIR "${FILAMENT_SDK}/lib/${FILAMENT_ARCH}")
set(MATC "${FILAMENT_SDK}/bin/matc")

# Filament's Linux/macOS release libs are built against libc++; match the ABI.
if(NOT APPLE)
  add_compile_options(-stdlib=libc++)
  add_link_options(-stdlib=libc++)
endif()

# ---- Compile materials with matc (CPU tool; no GPU needed) ----
set(MATERIALS_OUT "${CMAKE_BINARY_DIR}/materials")
file(MAKE_DIRECTORY "${MATERIALS_OUT}")
set(MATERIAL_BLOBS "")
foreach(mat unlit_texture flat_color)
  set(src "${CMAKE_CURRENT_SOURCE_DIR}/materials/${mat}.mat")
  set(out "${MATERIALS_OUT}/${mat}.filamat")
  add_custom_command(
    OUTPUT "${out}"
    COMMAND "${MATC}" -a opengl -p desktop -o "${out}" "${src}"
    DEPENDS "${src}"
    COMMENT "matc ${mat}.mat")
  list(APPEND MATERIAL_BLOBS "${out}")
endforeach()
add_custom_target(materials ALL DEPENDS ${MATERIAL_BLOBS})

# ---- The compositor ----
add_executable(xr-composite src/main.cpp)
add_dependencies(xr-composite materials)
target_include_directories(xr-composite PRIVATE
  "${FILAMENT_SDK}/include"
  "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
target_link_directories(xr-composite PRIVATE "${FILAMENT_LIB_DIR}")

# Link list extends the SDK README's recipe: + zstd (material shader decompression).
target_link_libraries(xr-composite PRIVATE
  filament backend bluegl bluevk filabridge filaflat utils geometry smol-v ibl abseil zstd)
if(APPLE)
  target_link_libraries(xr-composite PRIVATE
    "-framework Cocoa" "-framework Metal" "-framework CoreVideo")
else()
  target_link_libraries(xr-composite PRIVATE pthread c++ c++abi dl)
endif()
