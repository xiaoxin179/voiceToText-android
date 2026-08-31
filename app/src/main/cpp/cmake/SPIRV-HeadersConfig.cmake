set(SPIRV-Headers_FOUND TRUE)

if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(
        SPIRV-Headers::SPIRV-Headers
        PROPERTIES INTERFACE_INCLUDE_DIRECTORIES
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/SPIRV-Headers-v1.3.296/include"
    )
endif()
