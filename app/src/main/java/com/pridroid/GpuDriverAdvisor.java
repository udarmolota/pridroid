package com.pridroid;

/**
 * Detects the device GPU once and applies the matching recommended launch profile to a freshly
 * created instance: the Vulkan driver, plus conservative performance defaults for GPU families
 * where field testing proved the general defaults too aggressive.
 */
public final class GpuDriverAdvisor {

    private static final int POWERVR_RENDER_SCALE_PERCENT = 50;
    private static final int POWERVR_FPS_CAP = 30;

    private GpuDriverAdvisor() {}

    public static final class Result {
        public final String gpuName;     // e.g. "Adreno (TM) 610"
        public final String driverLabel; // e.g. "Turnip Adreno 6xx (legacy)"
        public final String driverSo;    // soName written to the instance
        public final boolean applied;    // false if the instance already had an explicit driver
        public final boolean performanceProfileApplied;
        public final int recommendedRenderScalePercent; // 0 when no family-specific override
        public final int recommendedFpsCap;              // 0 when no family-specific override
        Result(String gpuName, String driverLabel, String driverSo, boolean applied,
               boolean performanceProfileApplied, int recommendedRenderScalePercent,
               int recommendedFpsCap) {
            this.gpuName = gpuName;
            this.driverLabel = driverLabel;
            this.driverSo = driverSo;
            this.applied = applied;
            this.performanceProfileApplied = performanceProfileApplied;
            this.recommendedRenderScalePercent = recommendedRenderScalePercent;
            this.recommendedFpsCap = recommendedFpsCap;
        }
    }

    /**
     * Query the GPU and store the recommended driver on {@code instanceName} (unless it already has
     * an explicit driver). Runs a small EGL context — call OFF the UI thread.
     */
    public static Result applyRecommendedDriver(String instanceName) {
        GpuInfo gpu = GpuInfo.query();
        String so = gpu.recommendedDriverSo();
        int idx = 0;
        for (int i = 0; i < LauncherPreferences.VULKAN_DRIVERS.size(); i++) {
            if (LauncherPreferences.VULKAN_DRIVERS.get(i).soName.equals(so)) { idx = i; break; }
        }
        String label = LauncherPreferences.VULKAN_DRIVERS.get(idx).label;
        InstanceSettings s = new InstanceSettings(instanceName);
        boolean applied = false;
        if (!s.hasExplicitDriver()) {
            s.setVulkanDriverSo(so);
            applied = true;
        }

        // Moto G56 field test (PowerVR BXM-8-256): Zink is playable on the system Vulkan driver,
        // but its first 1800x810 / 60 FPS runs showed a black screen / visual artifacts. 1200x540
        // (50% of its 2400x1080 panel) / 30 FPS ran smoothly and also reduces framebuffer pixels
        // by 56%. Keep this PowerVR-only and never overwrite an explicit per-instance choice.
        int renderScale = 0;
        int fpsCap = 0;
        boolean performanceApplied = false;
        if (gpu.isPowerVr()) {
            renderScale = POWERVR_RENDER_SCALE_PERCENT;
            fpsCap = POWERVR_FPS_CAP;
            performanceApplied = s.applyPerformanceDefaultsIfUnset(renderScale, fpsCap);
        }

        return new Result(gpu.displayName(), label, so, applied, performanceApplied,
                renderScale, fpsCap);
    }
}
