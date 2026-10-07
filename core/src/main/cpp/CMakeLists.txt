cmake_minimum_required(VERSION 3.22.1)
project(kiwix_epub_vulkan)

add_library(kiwix_epub_vulkan SHARED epub_vulkan_scale.cpp)
find_library(android-lib android REQUIRED)
find_library(jnigraphics-lib jnigraphics REQUIRED)
find_library(log-lib log REQUIRED)
find_library(vulkan-lib vulkan REQUIRED)
find_library(mediandk-lib mediandk REQUIRED)
find_library(dl-lib dl REQUIRED)
target_compile_features(kiwix_epub_vulkan PRIVATE cxx_std_17)
target_compile_options(kiwix_epub_vulkan PRIVATE -Wall -Wextra -Werror=return-type
  -Wno-missing-field-initializers)
target_link_libraries(kiwix_epub_vulkan PRIVATE ${android-lib} ${jnigraphics-lib} ${log-lib}
  ${vulkan-lib} ${mediandk-lib} ${dl-lib})
