package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Unit tests for bootstrap prefix byte-patching and repo-compat script repair. */
public class BootstrapAndCompatTest {

    @Test
    public void termuxInstallerPatchesShebangsAndSymlinksInPlace() {
        byte[] raw = ("#!/data/data/com.termux/files/usr/bin/bash\n"
                + "source \"/data/data/com.termux/files/usr/bin/termux-setup-package-manager\"\n")
                .getBytes(StandardCharsets.UTF_8);
        int count = TermuxInstaller.patchPackageBytesInPlace(raw, raw.length);
        String patched = new String(raw, StandardCharsets.UTF_8);

        assertEquals(2, count);
        assertEquals("#!/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/bash\n"
                + "source \"/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/termux-setup-package-manager\"\n",
                patched);

        String symlink = TermuxInstaller.patchPackageString("/data/data/com.termux/files/usr/bin/sh");
        assertEquals("/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/sh", symlink);
    }

    @Test
    public void repoCompatRepairsLegacyPkgShebangAndPatchesElfBinaries() throws Exception {
        File tmpPrefix = Files.createTempDirectory("xyzide-prefix-test").toFile();
        try {
            File binDir = new File(tmpPrefix, "bin");
            assertTrue(binDir.mkdirs());

            File pkgFile = new File(binDir, "pkg");
            Files.write(pkgFile.toPath(),
                    ("#!/data/data/com.termux/files/usr/bin/bash\necho /data/data/com.termux/files/usr\n")
                            .getBytes(StandardCharsets.UTF_8));

            byte[] fakeElf = new byte[] {
                    0x7f, 'E', 'L', 'F', 0x00, 0x01, '/', 'd', 'a', 't', 'a', '/', 'd', 'a', 't', 'a', '/', 'c', 'o', 'm', '.', 't', 'e', 'r', 'm', 'u', 'x'
            };
            File elfFile = new File(binDir, "bash");
            Files.write(elfFile.toPath(), fakeElf);

            int repaired = RepoCompatInstaller.repairPrefixTextReferences(tmpPrefix);
            assertEquals(2, repaired); // Text script + ELF binary

            String pkgContent = new String(Files.readAllBytes(pkgFile.toPath()), StandardCharsets.UTF_8);
            assertTrue(pkgContent.startsWith("#!/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/bash"));
            assertTrue(!pkgContent.contains("/data/data/com.termux"));

            // ELF binary must be patched by replacing com.termux with targetPkg
            byte[] expectedElf = new byte[] {
                    0x7f, 'E', 'L', 'F', 0x00, 0x01, '/', 'd', 'a', 't', 'a', '/', 'd', 'a', 't', 'a', '/', 'c', 'o', 'm', '.', 'x', 'y', 'z', 'i', 'd', 'e'
            };
            assertArrayEquals(expectedElf, Files.readAllBytes(elfFile.toPath()));
        } finally {
            deleteDir(tmpPrefix);
        }
    }

    private static void deleteDir(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) deleteDir(c);
                else c.delete();
            }
        }
        dir.delete();
    }
}
