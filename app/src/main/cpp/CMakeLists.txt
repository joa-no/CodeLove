#
# Copyright (c) 2006-2024 LOVE Development Team
#
# This software is provided 'as-is', without any express or implied
# warranty.  In no event will the authors be held liable for any damages
# arising from the use of this software.
#
# Permission is granted to anyone to use this software for any purpose,
# including commercial applications, and to alter it and redistribute it
# freely, subject to the following restrictions:
#
# 1. The origin of this software must not be misrepresented; you must not
#    claim that you wrote the original software. If you use this software
#    in a product, an acknowledgment in the product documentation would be
#    appreciated but is not required.
# 2. Altered source versions must be plainly marked as such, and must not be
#    misrepresented as being the original software.
# 3. This notice may not be removed or altered from any source distribution.
#

cmake_minimum_required(VERSION 3.21)

project(love-android LANGUAGES C CXX)

add_custom_target(love_android)
set(LOVE_ANDROID 1)

# Include Megasource and LOVE
set(MEGA_LOVE "${CMAKE_CURRENT_SOURCE_DIR}/love")
add_subdirectory(megasource)

# Preview-only instrumentation: generate an explicitly marked wrapper from
# the pinned LÖVE source, leaving the upstream submodule and embed flavor intact.
if(CODELOVE_PREVIEW_METRICS)
    set(metrics_original "${MEGA_LOVE}/src/modules/graphics/wrap_Graphics.cpp")
    set(metrics_header "${CMAKE_CURRENT_SOURCE_DIR}/codelove/PreviewMetrics.h")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${metrics_original}" "${metrics_header}")
    file(READ "${metrics_original}" metrics_source)
    set(metrics_anchor "luax_catchexcept(L, [&]() { instance()->present(L); });")
    string(FIND "${metrics_source}" "${metrics_anchor}" metrics_index)
    if(metrics_index EQUAL -1)
        message(FATAL_ERROR "CodeLove preview metrics: upstream present wrapper changed")
    endif()
    string(REPLACE "${metrics_anchor}"
        "luax_catchexcept(L, [&]() {\n        const bool active = instance()->isActive();\n        instance()->present(L);\n        if (active) codelove_preview::record(instance()->getRenderer() == RENDERER_VULKAN ? 2u : instance()->getRenderer() == RENDERER_OPENGL ? 1u : 0u);\n    });"
        metrics_source "${metrics_source}")
    set(metrics_generated "${CMAKE_CURRENT_BINARY_DIR}/codelove_wrap_Graphics.cpp")
    file(WRITE "${metrics_generated}"
        "// CodeLove altered preview wrapper; original LÖVE notice retained below.\n#include \"${metrics_header}\"\n${metrics_source}")
    get_target_property(metrics_sources love_graphics_root SOURCES)
    list(FIND metrics_sources "src/modules/graphics/wrap_Graphics.cpp" metrics_source_index)
    if(metrics_source_index EQUAL -1)
        message(FATAL_ERROR "CodeLove preview metrics: original graphics source not found")
    endif()
    list(REMOVE_ITEM metrics_sources "src/modules/graphics/wrap_Graphics.cpp")
    list(APPEND metrics_sources "${metrics_generated}")
    set_property(TARGET love_graphics_root PROPERTY SOURCES "${metrics_sources}")
    target_include_directories(love_graphics_root PRIVATE "${MEGA_LOVE}/src/modules/graphics")
endif()


add_dependencies(love_android love OpenAL)

find_package(Python 3.9 COMPONENTS Interpreter)
if(NOT Python_Interpreter_FOUND)
	message(WARNING "No Python detected, Android.mk won't be converted to CMakeLists.txt!")
endif()

# List lua-modules

file(GLOB MODULE_LIST
	LIST_DIRECTORIES TRUE
	RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}"
	CONFIGURE_DEPENDS
	"lua-modules/*"
)
foreach(dir ${MODULE_LIST})
	if(IS_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/${dir}")
		set(has_run_cmake 0)
		# Load either CMakeLists.txt or convert Android.mk to CMakeLists.txt
		if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${dir}/CMakeLists.txt")
			add_subdirectory(${dir})
			set(has_run_cmake 1)
		elseif(Python_Interpreter_FOUND AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${dir}/Android.mk")
			get_filename_component(item_sanitized0 "${dir}" NAME)
			string(REGEX REPLACE "[^\\w-]" "-" item_sanitized1 "${item_sanitized0}")
			string(REGEX REPLACE "-+" "-" item "${item_sanitized0}")
			message(STATUS "Converting ${dir} Android.mk to CMakeLists.txt")
			execute_process(RESULT_VARIABLE status
				COMMAND "${Python_EXECUTABLE}" amkparse.py convert "${item}" "${dir}/Android.mk"
				WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
				OUTPUT_FILE "${dir}/CMakeLists.txt"
			)
			if("${status}" STREQUAL "0")
				add_subdirectory(${dir})
				set(has_run_cmake 1)
			else()
				message(FATAL_ERROR "amkparse.py returned ${status}")
			endif()
		endif()

		# If CMake has been run, retrieve targets and add it as dependency
		if(has_run_cmake)
			get_property(defined_build_targets DIRECTORY "${dir}" PROPERTY BUILDSYSTEM_TARGETS)
			get_property(defined_import_targets DIRECTORY "${dir}" PROPERTY IMPORTED_TARGETS)
			add_dependencies(love_android ${defined_build_targets} ${defined_import_targets})
		endif()
	endif()
endforeach()
