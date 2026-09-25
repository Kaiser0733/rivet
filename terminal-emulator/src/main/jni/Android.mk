# Modified by Rivet from termux/termux-app; see THIRD_PARTY_NOTICES.md.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c command.c
include $(BUILD_SHARED_LIBRARY)
