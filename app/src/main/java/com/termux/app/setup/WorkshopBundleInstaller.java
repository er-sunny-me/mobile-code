package com.termux.app.setup;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Properties;

/**
 * Installs the APK-bundled workshop files (Ubuntu rootfs + CLI agent binaries).
 *
 * <p>The Gradle task {@code downloadWorkshopBundle} fetches version-pinned upstream files into
 * {@code app/src/main/assets/workshop/} plus a {@code bundle.properties} manifest. Everything
 * here degrades gracefully: when an asset is missing (lean builds, other CPU archs) callers
 * fall back to the normal network install — nothing ever hard-fails because of the bundle.</p>
 *
 * <p>Asset layout (compressed files use the neutral {@code .tar.bin} suffix because AAPT2
 * gunzips anything ending in {@code .gz} and strips the suffix):</p>
 * <pre>
 * workshop/bundle.properties
 * workshop/rootfs/ubuntu-arm64.tar.bin
 * workshop/rootfs/ubuntu-x86_64.tar.bin
 * workshop/agents/arm64/agy.tar.bin      (inner file "antigravity" -> ~/.local/bin/agy)
 * workshop/agents/arm64/opencode.tar.bin (inner file "opencode"    -> ~/.opencode/bin/opencode)
 * workshop/agents/arm64/grok.bin        (raw binary -> ~/.grok/downloads + symlinks)
 * workshop/agents/arm64/kiro.zip        (inner kirocli/install.sh)
 * </pre>
 */
public final class WorkshopBundleInstaller {

    private static final String BUNDLE_DIR = "workshop";
    private static final String MANIFEST = "workshop/bundle.properties";

    private WorkshopBundleInstaller() {
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

    /** Load the bundle manifest, or null when this APK has no bundle (lean build). */
    public static Properties loadManifest(Context context) {
        try {
            AssetManager assets = context.getAssets();
            try (InputStream in = assets.open(MANIFEST)) {
                Properties props = new Properties();
                props.load(in);
                return props;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean hasBundle(Context context) {
        return loadManifest(context) != null;
    }

    // ------------------------------------------------------------------
    // Pure helpers (unit-tested)
    // ------------------------------------------------------------------

    /**
     * Map an Android ABI to a bundle arch, or null when nothing is bundled for it.
     * Agents ship arm64-only (test phones); the rootfs ships arm64 + x86_64 (emulator).
     */
    public static String bundleArchForAbi(String abi) {
        if ("arm64-v8a".equals(abi)) return "arm64";
        if ("x86_64".equals(abi)) return "x86_64";
        return null;
    }

    /** This device's bundle arch, or null when nothing is bundled for it. */
    public static String deviceBundleArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                String arch = bundleArchForAbi(abi);
                if (arch != null) return arch;
            }
        }
        return null;
    }

    /** Manifest key for an agent's bundled binary, or null when it has none. */
    public static String bundledAgentKey(String agentId, String arch) {
        if (!"arm64".equals(arch)) return null;
        switch (agentId) {
            case "antigravity":
                return "agent.agy.arm64";
            case "opencode":
                return "agent.opencode.arm64";
            case "grok":
                return "agent.grok.arm64";
            case "kiro":
                return "agent.kiro.arm64";
            default:
                return null;
        }
    }

    /** Manifest key for a rootfs tarball, or null when not bundled. */
    public static String bundledRootfsKey(String distro, String arch) {
        if (!"ubuntu".equals(distro)) return null;
        if ("arm64".equals(arch) || "x86_64".equals(arch)) {
            return "rootfs.ubuntu." + arch;
        }
        return null;
    }

    /** Shell snippet extracting a rootfs tarball into a proot-distro legacy slot. */
    public static String buildRootfsExtractCommand(String tarballPath, String destDir) {
        return "mkdir -p " + shellQuote(destDir)
                + " && tar -xzpf " + shellQuote(tarballPath) + " -C " + shellQuote(destDir);
    }

    /**
     * Directory for staged bundle files. Deliberately under {@code $PREFIX/tmp}: proot-distro
     * binds the whole {@code /data/data/<pkg>} tree into the guest, but the spelling must be
     * {@code /data/data/...} — {@code Context.getCacheDir()} spells it {@code /data/user/0/...}
     * which is NOT bound, so the guest sees "No such file or directory".
     */
    public static File stagingDir() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp/workshop-bundle");
    }

    static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------
    // Asset I/O (needs a Context)
    // ------------------------------------------------------------------

    /** Copy an asset to a file, streaming so 160MB binaries never sit fully in RAM. */
    public static boolean copyAssetToFile(Context context, String assetPath, File dest) {
        try {
            dest.getParentFile().mkdirs();
            AssetManager assets = context.getAssets();
            try (InputStream in = new BufferedInputStream(assets.open(assetPath));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buffer = new byte[1048576];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Exception e) {
            try {
                dest.delete();
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    /** Verify a file against the manifest's sha256, or false when unknown. */
    public static boolean verifyManifestSha(Properties manifest, String key, File file) {
        if (manifest == null || key == null || file == null || !file.isFile()) return false;
        String expected = manifest.getProperty(key + ".sha256");
        if (expected == null || expected.isEmpty()) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[1048576];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(expected.trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Stage a bundled rootfs tarball into the app cache (Termux home is bound inside the
     * proot guest, so the workshop shell can read it from there).
     *
     * @return the staged file, or null when not bundled / copy failed.
     */
    public static File stageBundledRootfs(Context context, String distro) {
        String arch = deviceBundleArch();
        String key = arch == null ? null : bundledRootfsKey(distro, arch);
        if (key == null) return null;
        Properties manifest = loadManifest(context);
        if (manifest == null) return null;
        String asset = manifest.getProperty(key + ".file");
        if (asset == null) return null;
        File dest = new File(stagingDir(), new File(asset).getName());
        if (dest.isFile() && verifyManifestSha(manifest, key, dest)) return dest;
        if (!copyAssetToFile(context, BUNDLE_DIR + "/" + asset, dest)) return null;
        if (!verifyManifestSha(manifest, key, dest)) {
            dest.delete();
            return null;
        }
        return dest;
    }

    /**
     * Offline install snippet for a bundled agent binary. The tarball/zip/bin must already be
     * staged under the Termux home (visible inside the proot guest at the same path).
     *
     * @return shell command, or null when this agent has no bundle (use network install).
     */
    public static String bundledAgentInstallSnippet(String agentId, String stagedPath) {
        String q = shellQuote(stagedPath);
        switch (agentId) {
            case "antigravity":
                return "mkdir -p ~/.local/bin && rm -rf ~/.cache/agy-bundle-tmp && mkdir -p ~/.cache/agy-bundle-tmp"
                        + " && tar -xzf " + q + " -C ~/.cache/agy-bundle-tmp antigravity"
                        + " && cp ~/.cache/agy-bundle-tmp/antigravity ~/.local/bin/agy"
                        + " && chmod +x ~/.local/bin/agy && rm -rf ~/.cache/agy-bundle-tmp"
                        + " && { agy install || true; } && command -v agy && agy --version";
            case "opencode":
                return "mkdir -p ~/.opencode/bin && rm -rf ~/.cache/opencode-bundle-tmp && mkdir -p ~/.cache/opencode-bundle-tmp"
                        + " && tar -xzf " + q + " -C ~/.cache/opencode-bundle-tmp opencode"
                        + " && cp ~/.cache/opencode-bundle-tmp/opencode ~/.opencode/bin/opencode"
                        + " && chmod +x ~/.opencode/bin/opencode && rm -rf ~/.cache/opencode-bundle-tmp"
                        + " && command -v opencode || [ -x ~/.opencode/bin/opencode ]";
            case "grok":
                return "mkdir -p ~/.grok/downloads ~/.grok/bin"
                        + " && cp " + q + " ~/.grok/downloads/grok-linux-aarch64"
                        + " && chmod +x ~/.grok/downloads/grok-linux-aarch64"
                        + " && ln -sf ~/.grok/downloads/grok-linux-aarch64 ~/.grok/bin/grok"
                        + " && ln -sf ~/.grok/downloads/grok-linux-aarch64 ~/.grok/bin/agent"
                        + " && command -v grok && grok --version";
            case "kiro":
                // stagedPath = Java-extracted dir (see unzipToDir): run its inner installer.
                return "INST=$(find " + q + " -maxdepth 3 -path '*kirocli/install.sh' | head -1);"
                        + " [ -n \"$INST\" ] && chmod +x \"$INST\""
                        + " && KIRO_CLI_SKIP_SETUP=1 bash \"$INST\";"
                        + " command -v kiro-cli";
            default:
                return null;
        }
    }

    /**
     * Stage a bundled agent file into the app cache.
     *
     * @return the staged file, or null when not bundled / copy or checksum failed.
     */
    public static File stageBundledAgent(Context context, String agentId) {
        String arch = deviceBundleArch();
        String key = arch == null ? null : bundledAgentKey(agentId, arch);
        if (key == null) return null;
        Properties manifest = loadManifest(context);
        if (manifest == null) return null;
        String asset = manifest.getProperty(key + ".file");
        if (asset == null) return null;
        File dest = new File(stagingDir(), new File(asset).getName());
        if (dest.isFile() && verifyManifestSha(manifest, key, dest)) return dest;
        if (!copyAssetToFile(context, BUNDLE_DIR + "/" + asset, dest)) return null;
        if (!verifyManifestSha(manifest, key, dest)) {
            dest.delete();
            return null;
        }
        return dest;
    }

    /**
     * Unzip a file with plain Java (the minimal Ubuntu rootfs has no {@code unzip} binary).
     * Skips directory traversal attacks.
     *
     * @return true when at least one entry was extracted.
     */
    public static boolean unzipToDir(File zip, File destDir) {
        boolean extracted = false;
        try {
            destDir.mkdirs();
            String canonicalDest = destDir.getCanonicalPath();
            try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    new BufferedInputStream(new FileInputStream(zip)))) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[262144];
                while ((entry = zin.getNextEntry()) != null) {
                    File out = new File(destDir, entry.getName());
                    if (!out.getCanonicalPath().startsWith(canonicalDest + File.separator)
                            && !out.getCanonicalPath().equals(canonicalDest)) {
                        zin.closeEntry();
                        continue;
                    }
                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        out.getParentFile().mkdirs();
                        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(out))) {
                            int read;
                            while ((read = zin.read(buffer)) != -1) {
                                outStream.write(buffer, 0, read);
                            }
                        }
                        extracted = true;
                    }
                    zin.closeEntry();
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return extracted;
    }
}
