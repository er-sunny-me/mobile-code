package com.termux.app;

import android.system.Os;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Installs the repo-compat layer that makes upstream Termux repo packages
 * installable in this app at all.
 *
 * <p>Root problem: this app's package is {@code com.xyzide}, but every .deb
 * in the upstream Termux repo carries a file tree that starts with the
 * directory {@code ./data/data/com.termux/}. Stock dpkg unpacks relative to
 * {@code /}, so installing any repo package tries to create
 * {@code /data/data/com.termux} — a directory Android only lets the
 * (nonexistent) com.termux app own — and dies with
 * {@code error creating directory './data/data/com.termux': Permission denied}.
 * The bootstrap never hit this because its zip is byte-patched at build time
 * ({@code scripts/patch-bootstrap.py}, com.termux -&gt; com.xyzide, same
 * length); repo .debs downloaded at runtime were never translated.
 *
 * <p>The fix installed here is a dpkg wrapper: apt is pointed at it via
 * {@code Dir::Bin::dpkg} in {@code etc/apt/apt.conf.d/}, and before dpkg
 * unpacks a .deb the wrapper translates it exactly like the bootstrap
 * patcher does — it renames the tree's {@code data/data/com.termux}
 * directory to {@code data/data/com.xyzide} and byte-patches the old prefix
 * string inside file contents and symlink targets (same length, so ELF
 * offsets are untouched). All other dpkg invocations pass through
 * unchanged. The mechanism was verified off-device against the real repo
 * .debs: the original ncurses .deb reproduces the exact phone error under
 * dpkg, and the translated ncurses/proot/proot-distro/python .debs unpack
 * and register cleanly into the com.xyzide prefix.
 *
 * <p>Idempotent: files are only rewritten when their content differs, so
 * calling this on every setup path is cheap and safe. It must run before
 * any {@code pkg}/{@code apt-get} install in the wizard, the dashboard,
 * the terminal boot script and the setup-all command, because existing
 * installs (whose bootstrap predates this layer) need it too.
 */
public class RepoCompatInstaller {

    private static final String LOG_TAG = "RepoCompatInstaller";

    private static final String WRAPPER_VERSION = "1";

    /** Write/refresh the dpkg wrapper and the apt config that points at it. */
    public static void ensureInstalled() {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File prefixDir = new File(prefix);
        if (!prefixDir.isDirectory()) return; // bootstrap not installed yet

        File wrapper = new File(prefix + "/libexec/xyzide/dpkg-wrapper");
        File conf = new File(prefix + "/etc/apt/apt.conf.d/99-xyzide-dpkg-wrapper.conf");
        try {
            writeIfChanged(wrapper, wrapperScript(prefix));
            makeExecutable(wrapper);
            writeIfChanged(conf, "Dir::Bin::dpkg \"" + wrapper.getAbsolutePath() + "\";\n");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to install repo-compat layer: " + e.getMessage());
        }
    }

    private static void writeIfChanged(File file, String content) throws IOException {
        if (file.isFile()) {
            String existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (existing.equals(content)) return;
        }
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static void makeExecutable(File file) {
        try {
            Os.chmod(file.getAbsolutePath(), 0700);
        } catch (Exception ignored) {
            file.setExecutable(true, false);
        }
    }

    private static String wrapperScript(String prefix) {
        String[] lines = {
            "#!" + prefix + "/bin/bash",
            "# Xyz.IDE repo-compat layer v" + WRAPPER_VERSION + " - installed by RepoCompatInstaller.",
            "# apt calls this instead of dpkg (Dir::Bin::dpkg). On --unpack/--install it",
            "# translates each .deb from the upstream Termux repo (whose file tree is",
            "# rooted at ./data/data/com.termux/) into a com.xyzide tree before the real",
            "# dpkg sees it; everything else passes through untouched. See",
            "# RepoCompatInstaller.java for the full explanation.",
            "XYZIDE_PREFIX=\"" + prefix + "\"",
            "REAL_DPKG=\"$XYZIDE_PREFIX/bin/dpkg\"",
            "XYZIDE_WORKDIRS=()",
            "xyzide_cleanup() {",
            "  local d",
            "  for d in \"${XYZIDE_WORKDIRS[@]}\"; do rm -rf \"$d\"; done",
            "}",
            "trap xyzide_cleanup EXIT",
            "",
            "XYZIDE_PATCHED=\"\"",
            "xyzide_patch_one() {",
            "  local deb=\"$1\" work tree f t s",
            "  XYZIDE_PATCHED=\"$deb\"",
            "  work=$(mktemp -d \"$XYZIDE_PREFIX/tmp/xyzide-dpkg.XXXXXXXX\") || return 0",
            "  XYZIDE_WORKDIRS+=(\"$work\")",
            "  tree=\"$work/tree\"",
            "  mkdir -p \"$tree\" || return 0",
            "  dpkg-deb -x \"$deb\" \"$tree\" 2>/dev/null || return 0",
            "  dpkg-deb -e \"$deb\" \"$tree/DEBIAN\" 2>/dev/null || return 0",
            "  [ -d \"$tree/data/data/com.termux\" ] || return 0",
            "  mv \"$tree/data/data/com.termux\" \"$tree/data/data/com.xyzide\" || return 0",
            "  grep -rl 'com\\.termux' \"$tree\" 2>/dev/null | while IFS= read -r f; do",
            "    chmod u+w \"$f\" 2>/dev/null",
            "    sed -i 's/com\\.termux/com.xyzide/g' \"$f\" 2>/dev/null",
            "  done",
            "  find \"$tree\" -type l 2>/dev/null | while IFS= read -r f; do",
            "    t=$(readlink \"$f\")",
            "    case \"$t\" in",
            "      *com.termux*) ln -sf \"${t//com.termux/com.xyzide}\" \"$f\" 2>/dev/null ;;",
            "    esac",
            "  done",
            "  rm -f \"$tree/DEBIAN/md5sums\"",
            "  for s in postinst preinst postrm prerm config; do",
            "    if [ -f \"$tree/DEBIAN/$s\" ]; then chmod 755 \"$tree/DEBIAN/$s\"; fi",
            "  done",
            "  if dpkg-deb -Zgzip --build \"$tree\" \"$work/patched.deb\" >/dev/null 2>&1; then",
            "    XYZIDE_PATCHED=\"$work/patched.deb\"",
            "  fi",
            "}",
            "",
            "xyzide_needs_patch=0",
            "for xyzide_a in \"$@\"; do",
            "  case \"$xyzide_a\" in",
            "    --unpack|--install) xyzide_needs_patch=1 ;;",
            "  esac",
            "done",
            "if [ \"$xyzide_needs_patch\" != \"1\" ]; then",
            "  exec \"$REAL_DPKG\" \"$@\"",
            "fi",
            "",
            "xyzide_args=()",
            "xyzide_prev=\"\"",
            "for xyzide_a in \"$@\"; do",
            "  if [ \"$xyzide_prev\" = \"--recursive\" ] && [ -d \"$xyzide_a\" ]; then",
            "    xyzide_newdir=$(mktemp -d \"$XYZIDE_PREFIX/tmp/xyzide-dpkg-rec.XXXXXXXX\")",
            "    XYZIDE_WORKDIRS+=(\"$xyzide_newdir\")",
            "    for xyzide_d in \"$xyzide_a\"/*; do",
            "      [ -f \"$xyzide_d\" ] || continue",
            "      case \"$xyzide_d\" in",
            "        *.deb) xyzide_patch_one \"$xyzide_d\"; cp \"$XYZIDE_PATCHED\" \"$xyzide_newdir/$(basename \"$xyzide_d\")\" ;;",
            "        *) cp \"$xyzide_d\" \"$xyzide_newdir/\" ;;",
            "      esac",
            "    done",
            "    xyzide_args+=(\"$xyzide_newdir\")",
            "  else",
            "    case \"$xyzide_a\" in",
            "      *.deb)",
            "        if [ -f \"$xyzide_a\" ]; then",
            "          xyzide_patch_one \"$xyzide_a\"",
            "          xyzide_args+=(\"$XYZIDE_PATCHED\")",
            "        else",
            "          xyzide_args+=(\"$xyzide_a\")",
            "        fi",
            "        ;;",
            "      *) xyzide_args+=(\"$xyzide_a\") ;;",
            "    esac",
            "  fi",
            "  xyzide_prev=\"$xyzide_a\"",
            "done",
            "",
            "\"$REAL_DPKG\" \"${xyzide_args[@]}\"",
            "exit $?",
            ""
        };
        return String.join("\n", lines);
    }
}
