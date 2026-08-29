package com.pridroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GpuInfoTest {

    @Test
    public void detectsPowerVrFromRenderer() {
        GpuInfo gpu = new GpuInfo("PowerVR B-Series BXM-8-256", "Imagination Technologies", 0, 0);
        assertTrue(gpu.isPowerVr());
    }

    @Test
    public void detectsImgRendererFromVendor() {
        GpuInfo gpu = new GpuInfo("IMG BXM-8-256", "Imagination Technologies", 0, 0);
        assertTrue(gpu.isPowerVr());
    }

    @Test
    public void doesNotClassifyAdrenoOrMaliAsPowerVr() {
        assertFalse(new GpuInfo("Adreno (TM) 750", "Qualcomm", 7, 750).isPowerVr());
        assertFalse(new GpuInfo("Mali-G57 MC2", "ARM", 0, 0).isPowerVr());
    }
}
