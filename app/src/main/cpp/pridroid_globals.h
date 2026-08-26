#ifndef PRIDROID_GLOBALS_H
#define PRIDROID_GLOBALS_H

#include <android/native_window.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdbool.h>

typedef enum {
    RD_GL4ES,
    RD_ZINK_ZFA,
    RD_ZINK_OSMESA,
    RD_SOFTPIPE      // CPU software renderer: OSMesa + Mesa softpipe, no GPU/Vulkan
} PriDroidRenderer;

extern PriDroidRenderer g_pridroid_renderer;
extern const char*      g_pridroid_vulkan_driver_name;

typedef struct {
    ANativeWindow* native_window;
    int width;
    int height;
    bool is_dirty;
    bool is_used;
    pthread_mutex_t mutex;
    pthread_cond_t ready_for_destroy_cond;
} PriDroidSurface;

extern PriDroidSurface g_pridroid_surface;

// Namespace used to load x86_64 libs via linkernsbypass
struct android_namespace_t;
extern struct android_namespace_t* pridroid_ns;

#endif // PRIDROID_GLOBALS_H
