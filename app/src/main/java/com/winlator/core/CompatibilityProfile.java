package com.winlator.core;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.container.DXWrappers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Compatibility profile: picks a realistic graphics card for each container (once, then it stays
 * the same so shader caches and games see a stable device) and writes matching system information
 * to the Wine registry. Cards the user chose by hand in the DXVK/WineD3D settings are never overridden.
 */
public abstract class CompatibilityProfile {
    private static final String EXTRA_GPU = "compatGpu";
    private static final String EXTRA_PC = "compatPc";
    private static final String EXTRA_REGISTRY = "compatRegistry";

    // Newer cards only: very old ones (RIVA, GeForce 256...) make modern games pick wrong code paths.
    private static final String[] PREFERRED = {"GTX 9", "GTX 10", "GTX 16", "RTX", "Radeon RX", "Radeon R9"};

    // {manufacturer, product name, bios vendor, bios version, bios date}
    private static final String[][] PCS = {
        {"ASUSTeK COMPUTER INC.", "ROG STRIX B550-F GAMING", "American Megatrends Inc.", "2803", "03/15/2022"},
        {"Micro-Star International Co., Ltd.", "MS-7C95", "American Megatrends International, LLC.", "1.D0", "08/19/2021"},
        {"Gigabyte Technology Co., Ltd.", "B550 AORUS ELITE", "American Megatrends Inc.", "F15c", "11/08/2021"},
        {"Dell Inc.", "XPS 8940", "Dell Inc.", "2.9.0", "06/27/2022"},
        {"HP", "OMEN 25L Desktop GT12-0xxx", "AMI", "F.22", "04/12/2021"}
    };

    public static class Gpu {
        public final String name;
        public final int deviceId;
        public final int vendorId;

        Gpu(String name, int deviceId, int vendorId) {
            this.name = name.replace(",", " ").replace(":", " ").trim();
            this.deviceId = deviceId;
            this.vendorId = vendorId;
        }
    }

    private static boolean isPreferred(String name) {
        for (String keyword : PREFERRED) if (name.contains(keyword)) return true;
        return false;
    }

    private static Gpu parseSaved(String saved) {
        try {
            String[] parts = saved.split("\\|");
            if (parts.length != 3) return null;
            return new Gpu(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns this container's GPU, choosing a random one the first time. Returns null if the card list can't be read. */
    public static synchronized Gpu getOrPickGpu(Context context, Container container) {
        Gpu saved = parseSaved(container.getExtra(EXTRA_GPU));
        if (saved != null) return saved;

        try {
            JSONArray cards = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            List<Gpu> preferred = new ArrayList<>();
            List<Gpu> all = new ArrayList<>();
            for (int i = 0; i < cards.length(); i++) {
                JSONObject item = cards.getJSONObject(i);
                Gpu gpu = new Gpu(item.getString("name"), item.getInt("deviceID"), item.getInt("vendorID"));
                all.add(gpu);
                if (isPreferred(gpu.name)) preferred.add(gpu);
            }

            List<Gpu> pool = !preferred.isEmpty() ? preferred : all;
            if (pool.isEmpty()) return null;

            Gpu gpu = pool.get(new Random().nextInt(pool.size()));
            container.putExtra(EXTRA_GPU, gpu.name+"|"+gpu.deviceId+"|"+gpu.vendorId);
            container.saveData();
            return gpu;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Fills in the graphics card for DXVK / WineD3D when the user hasn't picked one. Changes only the in-memory config. */
    public static void applyGpu(Context context, Container container, String dxwrapper, KeyValueSet config) {
        if (dxwrapper == null) return;

        boolean isDXVK = dxwrapper.equals(DXWrappers.DXVK);
        boolean isWineD3D = dxwrapper.equals(DXWrappers.WINED3D);
        if (!isDXVK && !isWineD3D) return;

        if (isDXVK && config.get("customDevice").contains(":")) return;
        if (isWineD3D && !config.get("VideoPciDeviceID").isEmpty()) return;

        Gpu gpu = getOrPickGpu(context, container);
        if (gpu == null) return;

        if (isDXVK) {
            config.put("customDevice", String.format("%04x", gpu.deviceId)+":"+String.format("%04x", gpu.vendorId)+":"+gpu.name);
        }
        else {
            config.put("VideoPciDeviceID", String.valueOf(gpu.deviceId));
            config.put("VideoPciVendorID", String.valueOf(gpu.vendorId));
        }
    }

    /**
     * Writes PC manufacturer/BIOS info to the registry once per container (and again whenever
     * the GPU changes). Registry writes must never stop a game from starting, so every error is swallowed.
     */
    public static void applyRegistry(Context context, Container container, File systemRegFile) {
        try {
            Gpu gpu = getOrPickGpu(context, container);
            String gpuName = gpu != null ? gpu.name : "";

            String pcIndex = container.getExtra(EXTRA_PC);
            if (pcIndex.isEmpty()) {
                pcIndex = String.valueOf(new Random().nextInt(PCS.length));
                container.putExtra(EXTRA_PC, pcIndex);
            }

            String stamp = pcIndex+"-"+gpuName;
            if (stamp.equals(container.getExtra(EXTRA_REGISTRY)) || !systemRegFile.isFile()) return;

            String[] pc = PCS[Integer.parseInt(pcIndex) % PCS.length];
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                final String key = "System\\ControlSet001\\Control\\SystemInformation";
                registryEditor.setStringValue(key, "SystemManufacturer", pc[0]);
                registryEditor.setStringValue(key, "SystemProductName", pc[1]);
                registryEditor.setStringValue(key, "BIOSVendor", pc[2]);
                registryEditor.setStringValue(key, "BIOSVersion", pc[3]);
                registryEditor.setStringValue(key, "BIOSReleaseDate", pc[4]);
            }

            container.putExtra(EXTRA_REGISTRY, stamp);
            container.saveData();
        }
        catch (Exception e) {
            // best effort
        }
    }
}
