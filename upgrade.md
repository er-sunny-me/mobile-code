# Mobile Code — GUI Upgrade (implemented)

Yeh doc batata hai ki **kaam kya hua**, **kaise kaam karta hai**, aur **aage kya karna hai**.
Backend (agent install / launch / monitoring) waisa hi hai — sirf uske upar ek GUI layer add hui hai.

---

## 1. Idea (ek line mein)

Pehle: agent terminal ke andar chalta tha, output terminal mein hi dikhta tha.
Ab: **agent wahi chalega (same Termux environment), par output GUI chat bubbles mein dikhega.**

```
User prompt
   ↓
AgentChatActivity  ──►  CLIAgentManager.buildGuiPromptCommand()   (backend, unchanged logic)
   ↓
AgentRunner  ──►  $PREFIX/bin/bash -lc "<agent> -p \"<prompt>\""   (background process)
   ↓
stdout  ──►  AgentOutputParser (ANSI strip + error detect)
   ↓
ChatMessageAdapter  ──►  chat bubbles
```

Terminal wala puraana raasta bhi bilkul chalu hai — chat screen se "open in terminal" button
aur agent ko interactive mode mein bhi chala sakte ho.

---

## 2. Naya code

### Naya package: `app/src/main/java/com/termux/app/gui/`

| File | Kaam |
|---|---|
| `ChatMessage.java` | Ek chat entry ka model (USER / AGENT / ERROR / STATUS) |
| `AgentOutputParser.java` | Raw agent output se ANSI codes hatata hai, spinner-noise filter karta hai, error detect karta hai, aur 4000-char chunks mein todta hai |
| `AgentRunner.java` | Agent ko background process ki tarah chalata hai, Termux env (`PREFIX`, `PATH`, `LD_LIBRARY_PATH`, `TMPDIR`) set karta hai, line-by-line output main thread pe bhejta hai |
| `ChatMessageAdapter.java` | RecyclerView adapter — 4 tarah ke bubbles, streaming output ko ek hi bubble mein append karta hai |
| `AgentChatActivity.java` | **Main GUI screen** — agent picker + chat + prompt input + stop button |
| `AgentListAdapter.java` | Agent dashboard ka card adapter |
| `AgentDashboardActivity.java` | **Agent manage screen** — install / enable / default / GUI command edit |

### Naye layouts

| File | Kaam |
|---|---|
| `activity_agent_chat.xml` | Chat screen (top bar + transcript + running strip + input) |
| `item_chat_message.xml` | Agent/error bubble (left, avatar ke saath) — pehle dead tha, ab use ho raha hai |
| `item_chat_message_user.xml` | User bubble (right, brand red) |
| `item_chat_status.xml` | Centered chhota status line |
| `activity_agent_dashboard.xml` | Agent dashboard screen |
| `item_cli_agent.xml` | Agent card — pehle dead tha, ab use ho raha hai |
| `dialog_agent_gui_command.xml` | GUI command edit karne ka dialog |

### Naye drawables

`bg_bubble_agent.xml`, `bg_bubble_user.xml`, `bg_bubble_error.xml`,
`bg_badge_installed.xml`, `bg_badge_missing.xml`

`colors.xml` mein naye chat/badge colors add hue (`zc_chat_*`, `zc_badge_*`).

---

## 3. Backend mein kya change hua (kam se kam)

`CLIAgentManager` — logic same, sirf 3 additions:

1. **`guiPromptTemplate`** — har agent ke liye non-interactive command
   (`claude -p "{prompt}"`, `codex exec "{prompt}"`, etc.).
   `buildGuiPromptCommand()` isse poora command line banata hai.
2. **`getGuiCommand()` / `setGuiCommand()` / `hasCustomGuiCommand()`** — user built-in
   command ko override kar sakta hai (dashboard ke "Command" button se). Galat flag ho to
   app rebuild karne ki zarurat nahi.
3. **`shellPrelude()`** — pehle ek hi jagah string thi, ab ek method hai.

`AIChatManager.installAgent()` ab `buildInstallCommand()` use karta hai (CA prelude ke saath).

**Agent detection better hui:** `isAgentInstalled()` ab `$PREFIX/bin`, `~/.local/bin`,
`/usr/bin`, `/usr/local/bin` check karta hai, aur phir `bash -lc "command -v <bin>"`
se confirm karta hai. Pehle `which` par depend karta tha jo app ke PATH mein nahi hota.

---

## 4. Bug fixes

| # | Bug | Kya tha | Fix |
|---|---|---|---|
| 1 | **`gradle.properties` corrupt** | File ke end mein UTF-16LE bytes ghuse the (`a\0n\0d\0r\0o\0i\0d\0...`). `file` ise "data" (binary) batata tha, `grep` kuch nahi dhoondh paata tha | Poori file UTF-8 mein rewrite; `android.javaCompile.suppressSourceTargetDeprecationWarning=true` sahi tarah se add hua |
| 2 | **`settings.gradle` duplicate includes** | `include ':app'` teen baar tha | Sirf ek baar |
| 3 | **SSL globally disabled** ⚠️ | `NODE_TLS_REJECT_UNAUTHORIZED=0` + `npm config set strict-ssl false` — yani **saare** HTTPS connections ka certificate verification off. Agents apni API keys inhi connections se bhejte hain | Hataya. Ab `SSL_CERT_FILE` + `NODE_EXTRA_CA_CERTS` Termux ke CA bundle par point karte hain — SSL chalta hai, verification band nahi hoti |
| 4 | **`local.properties` path escape** | `sdk.dir=C\:\\...` Windows par AGP mein `IOException: The filename, directory name, or volume label syntax is incorrect` deta tha | Forward slashes (`sdk.dir=C:/Users/...`) |
| 5 | **Branding 4 jagah 4 naam** | `OpenCode` (app strings.xml), `ZeroCode-Studio` (build.gradle), `Novus IDE` (manifest + UI), `Mobile Code` (README) | Sab **Mobile Code** par unify. Manifest/layouts ab `@string/app_name` use karte hain — ek jagah badlo, sab badal jayega |
| 6 | **`ZeroCodeHomeActivity.closeTab()` galat tab kholta tha** | `openFiles.keySet()` par loop chala kar **hamesha last** file select karta tha | Ab closed tab ke index wala neighbour select hota hai |
| 7 | **`UriToPathUtils` SD card support nahi** | Sirf `/tree/primary:` aur `/tree/raw:` string-match karta tha; removable volume par `null` return karta tha | `DocumentsContract.getTreeDocumentId()` use karta hai → `/storage/<VOLUME_ID>/...` bhi resolve hota hai |
| 8 | **`ErrorExplanationSheet` crash** | AI response aane tak activity finish ho jaye to `runOnUiThread` destroyed activity par chalta tha | `isFinishing()/isDestroyed()` guard (dono jagah) |
| 9 | **Hardcoded `$PREFIX` paths** | `TermuxActivity`, `FileExplorerAdapter` mein literal `"/data/data/com.termux/files/home"` | `TermuxConstants.TERMUX_HOME_DIR_PATH` |
| 10 | **Dead layouts** | `item_chat_message.xml`, `item_cli_agent.xml` kisi Java se reference nahi the | Dono ko naye GUI ke liye use kiya (dead code zinda ho gaya) |
| 11 | **Agent install par CA setup nahi** | Install command raw chalti thi, isliye `strict-ssl false` "fix" ki zarurat padi thi | `buildInstallCommand()` CA prelude add karta hai |
| 12 | **Jacoco Java 21 support nahi** | `jacoco { toolVersion = "0.8.8" }` — har test run par `IllegalArgumentException: Unsupported class file major version 65` stack trace aata tha (coverage instrumentation JDK 21 class files padh nahi paata) | `0.8.12` |
| 13 | **CI workflows kabhi chalte hi nahi** | `run_tests.yml`, `gradle-wrapper-validation.yml`, `debug_build.yml`, `dependency-submission.yml` — chaaron `master` (aur `android-10`) branch par trigger the, par repo ki ekmatra branch `main` hai. Yani tests aur wrapper validation CI mein **kabhi run nahi hue** | Sab `main` par |
| 14 | **Gradle wrapper 9.6.0 par chala gaya tha** | Android Studio ne sync ke waqt wrapper `9.5.0 → 9.6.0` kar diya. AGP 8.13.2 Gradle 9.6 ke saath **incompatible** hai (Gradle 9.6 ne `org.gradle.problems.internal.InternalProblems` hata diya) → `CONFIGURE FAILED` | Wrapper wapas `9.5.0` par |
| 15 | **App launch par crash** ⚠️ | Manifest mein `<activity android:name=".MainActivity">` LAUNCHER ke saath add ho gaya tha (Android Studio ne 10:18 par), par `com.termux.MainActivity` class **exist hi nahi karti** (asli template class `com.example.myapplication.MainActivity` thi). APK mein do launcher activities thi, aur ek tap par `ClassNotFoundException` → crash | Entry hataya + poori dead template scaffolding delete |
| 16 | **Download complete par crash** ⚠️ | Manifest `<receiver android:name=".utils.UpdateReceiver">` → resolves `com.termux.utils.UpdateReceiver`, par asli class `com.termux.app.utils.UpdateReceiver` hai. Koi bhi download complete hone par system receiver instantiate karta → `ClassNotFoundException` | `.app.utils.UpdateReceiver` |
| 17 | **Dead template scaffolding** | `com.example.myapplication` ke 6 Kotlin files (MainActivity + Compose theme + 2 template tests), aur **3 dead `.kts` build files** (`app/build.gradle.kts` mein `namespace/applicationId = "com.example.myapplication"`, `settings.gradle.kts` mein `rootProject.name = "My Application"` aur koi module include nahi). Gradle sirf Groovy files use karta hai (verified via `buildFiles`), to `.kts` files pure landmines thi — aur `MainActivity` wale crash ko invite kar rahi thi | Sab delete. `ExampleInstrumentedTest` galat package assert karta tha (`com.example.myapplication`) — wo bhi gaya |
| 18 | **3 agents ka PATH galat tha** ⚠️ | Installer binary ek jagah rakhta hai, app doosri jagah dhoondhta hai → `agy: command not found`. **Antigravity**: installer `$HOME/.local/bin/agy` par, app `/root/.local/bin` par (wo sirf proot ke andar exist karta hai). **OpenCode**: installer `$HOME/.opencode/bin`, app `$HOME/.local/bin`. **Grok**: installer `$HOME/.grok/bin`, app `$HOME/.local/bin` | Teenon ke install + run commands apne asli dir par; `shellPrelude()` ab dono environments ke dir PATH mein daalta hai (`$HOME/...` + `/root/...` + Termux home) aur `hash -r` karta hai; install ke baad `~/.bashrc` mein bhi persist |
| 19 | **GUI aur terminal alag environment mein chal rahe the** ⚠️ | App ka terminal `proot-distro login ubuntu` karta hai, to uske andar install `/root/.local/bin` mein jata hai. Par naya GUI chat (`AgentRunner`) native Termux mein chal raha tha aur dashboard native mein install kar raha tha → GUI se install kiya agent terminal mein nahi milta, aur ulta bhi | `AgentRunner` ab workshop rootfs detect karke `proot-distro login ubuntu -- bash -lc '<cmd>'` se **wahi environment** use karta hai; install-detection bhi wahin probe karta hai (ek batched call, 5s cache); UI batata hai kaunsa environment chal raha hai |

### Environment model — yeh sabse important baat hai

```
App ka terminal session
   └─ bootScript: pkg install proot-distro → proot-distro install ubuntu
      → proot-distro login ubuntu          ← yahan user land karta hai
         HOME=/root, filesystem = Ubuntu rootfs

Native Termux (sirf failsafe session)
   HOME=/data/data/com.termux/files/home
```

**Yeh do alag filesystems hain.** Ek mein install kiya binary doosre mein dikhta hi nahi.
Isliye:

- app ka Install button aur terminal — dono workshop mein (consistent)
- GUI chat bhi ab workshop mein (bug 19 ka fix)
- agar tum **khud** proot ke andar `agy` type karo, to wo wahin install hona chahiye:

```bash
# proot ke andar (prompt: root@localhost:...#)
curl -k -fsSL https://antigravity.google/cli/install.sh | bash
export PATH="$HOME/.local/bin:$PATH"   # $HOME = /root yahan
agy --version
```

`proot-distro login` ka command form official README se verify kiya:
`proot-distro login [OPTIONS] CONTAINER [-- COMMAND ...]` — e.g.
`proot-distro login ubuntu -- bash -c "echo hello"`. Isi ko `CLIAgentManager.wrapForWorkshop()`
use karta hai, aur payload ko single-quote karta hai taaki andar ke quotes/`$` literal rahein.

### Bug 18 kaise pakda gaya (method)

Andaaza lagane ke bajaye har installer script khud fetch karke usme install dir dhoondhi:

```bash
curl -sSL https://antigravity.google/cli/install.sh | grep -nE "TARGET_DIR|INSTALL_DIR|BIN_DIR|mkdir -p"
```

Result:

| Agent | Installer ka dir | Binary name | App ka purana PATH |
|---|---|---|---|
| Antigravity | `$HOME/.local/bin` | `agy` | `/root/.local/bin` ✗ |
| Kiro | `$HOME/.local/bin` | `kiro-cli` | `$HOME/.local/bin` ✓ |
| OpenCode | `$HOME/.opencode/bin` | `opencode` | `$HOME/.local/bin` ✗ |
| Grok | `$HOME/.grok/bin` | `grok` | `$HOME/.local/bin` ✗ |
| Claude / Codex / Kilo | npm global → `$PREFIX/bin` | `claude` / `codex` / `kilo` | ✓ |

npm wale agents ke liye asli bin name registry se confirm kiya:
`curl -sS https://registry.npmjs.org/@kilocode%2Fcli/latest | grep -o '"bin":{[^}]*}'`

### Sabak: manifest component bina class ke compile ho jaata hai

`<activity>`/`<receiver>` jo aisi class ko point kare jo exist nahi karti, **build mein koi error nahi deta** — sirf runtime par crash hota hai. Isliye build ke baad yeh check chalao:

```bash
APK=app/build/outputs/apk/debug/*universal*.apk
AAPT="$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe"
"$AAPT" dump badging "$APK" | grep launchable-activity     # exactly ek launcher hona chahiye
"$AAPT" dump xmltree --file AndroidManifest.xml "$APK" > /tmp/apkm.txt
unzip -p "$APK" 'classes*.dex' | grep -a -o 'Lcom/termux/[A-Za-z0-9_/$]*;' | sort -u > /tmp/dex.txt
# har declared activity/service/receiver/provider ka class dex mein hona chahiye
```

Ab yeh check **pass** karta hai — 0 MISSING, ek launcher (`com.termux.app.ZeroCodeHomeActivity`, label `Mobile Code`).

### Mila, par fix nahi kiya (decision chahiye)

**APK naming teen jagah teen tarah ka hai:**

| Jagah | Kya expect karta hai |
|---|---|
| `app/build.gradle` variant hook | `MobileCode-v<version>-debug_<abi>.apk` (asli output) |
| `debug_build.yml`, `attach_debug_apks_to_release.yml` | `termux-app_<tag>_<abi>.apk` → validation **fail** hogi |
| `README.md` download buttons | `app-debug.apk` / `app-release.apk` (aisi koi file banti hi nahi) |
| `release_apk.yml` release asset | `Novus-IDE-Release-APK` |

Yeh pre-existing hai (variant hook pehle `Novus-IDE-v...` deta tha), aur ise theek karne ka matlab hai ek naming scheme chunna — jo tumhare release assets aur README ke download links badal dega. Isliye yeh change maine nahi kiya.

Recommendation: Gradle ko source of truth maano (`MobileCode-v<version>-<type>_<abi>.apk`), CI ke `APK_BASENAME_PREFIX` ko us se match karao, aur README ke links update karo.

---

## 5. UI kaisa dikhta hai

### Agent Chat (`AgentChatActivity`)

```
┌──────────────────────────────────────┐
│ ←  🟠 Claude Code          ⚙  ⌨     │  ← agent picker, dashboard, terminal
├──────────────────────────────────────┤
│         Pick an agent and describe   │  ← status line (centered, muted)
│                                      │
│  ┌────────────────────────────┐      │
│  │ login page pe 500 error aata hai  │  ← user bubble (right, red)
│  └────────────────────────────┘      │
│                                      │
│ 🟠  Claude Code · 12:04              │  ← agent meta line
│  ┌────────────────────────────┐      │
│  │ main.py:42 mein None check  │      │  ← agent bubble (left, dark)
│  │ missing hai...              │      │
│  └────────────────────────────┘      │
│                                      │
│  ┌────────────────────────────┐      │
│  │ Traceback (most recent...)  │      │  ← error bubble (red border)
│  └────────────────────────────┘      │
├──────────────────────────────────────┤
│ ◌ Running Claude Code…        [Stop] │  ← sirf jab chal raha ho
├──────────────────────────────────────┤
│  Ask the agent to change your…  (➤)  │  ← prompt input + send
└──────────────────────────────────────┘
```

- User bubble right + brand red, agent bubble left + dark + avatar, error bubble red border
- `250ms` throttle se output stream hota hai (per-line redraw nahi)
- Ek hi run ka output **ek hi bubble** mein append hota hai (line-per-bubble nahi)
- Back dabane par agent chal raha ho to confirm dialog

### Agent Dashboard (`AgentDashboardActivity`)

```
┌──────────────────────────────────────┐
│ ←  Coding Agents                  ⟳  │
├──────────────────────────────────────┤
│ 3 of 9 agents ready. Install one...  │  ← live count
├──────────────────────────────────────┤
│ 🟠 Claude Code      [DEFAULT] [READY]│
│    Anthropic's agentic coding CLI... │
│        Command  Disable  Use in Chat │
│    $ claude -p "{prompt}"            │  ← exact command dikhta hai
├──────────────────────────────────────┤
│ ⚪ Codex CLI          [NOT INSTALLED] │
│    OpenAI's lightweight agent...      │
│        Command  Disable  [Install]   │
│    $ codex exec "{prompt}"           │
└──────────────────────────────────────┘
```

- **Install** → install command background mein chalti hai, live log hint bar mein dikhta hai,
  phir status auto-refresh
- **Command** → GUI command edit (built-in default par "Reset" bhi milega)
- **Disable/Enable** → agent picker se hide/show
- **Use in Chat** → default agent set
- Status check background thread par hota hai (binary detection shell out karta hai — main thread par nahi)

---

## 6. Kaise use karein

1. App kholo → top bar mein **✎ (red pencil icon)** = Agent Chat, **⚙-manage icon** = Coding Agents
2. Pehli baar: **Coding Agents** → jise chahiye uspe **Install** dabao
3. **Agent Chat** → agent chuno → prompt likho → send
4. Agar koi agent chat mein sahi se na chale → **⌨ button** se terminal mein kholo
   (puraana interactive flow bilkul waise hi hai)

---

## 7. Honest caveats

### GUI commands: 6 of 8 official docs se verified

Flags maine vendor docs se confirm kiye hain (aur install dirs installer scripts se — bug 18 dekho):

| Agent | Command | Status |
|---|---|---|
| Claude Code | `claude -p "{prompt}"` | ✅ docs: `-p, --print` = non-interactive |
| Codex | `codex exec "{prompt}"` | ✅ OpenAI docs: "`codex exec` — Run Codex non-interactively" |
| Grok | `grok -p "{prompt}"` | ✅ xAI docs: `-p, --single <PROMPT>` |
| OpenCode | `opencode run "{prompt}"` | ✅ opencode.ai docs: `run` = non-interactive mode |
| Kilo | `kilo run "{prompt}"` | ✅ kilo.ai docs: `kilo run` headless by default |
| GitHub Copilot | `npx -y @github/copilot -p "{prompt}"` | ✅ copilot-cli: `-p` = headless |
| **Antigravity** | `agy -p "{prompt}"` | ✅ verified on device: prompts read from `-p/--print` |
| **Kiro** | `kiro-cli chat "{prompt}"` | ⚠️ unverified — installer sirf `kiro-cli` batata hai (ek alag `kiro-cli-chat` binary bhi hai) |

Kilo ke liye `--auto` jaan-boojh kar **nahi** lagaya — docs usse "dangerous" batate hain
(permissions auto-approve). One-shot ke liye plain `kilo run` kaafi hai.

Isliye safety net:
- dashboard har card par **exact command dikhata hai** (`$ ...` line)
- command **editable** hai — galat ho to wahi theek kar lo, app rebuild ki zarurat nahi
- command blank chhodo → wo agent hamesha terminal mein khulega (silent fail nahi hoga)
- exit code != 0 par chat screen khud batati hai "use the terminal button"
- `CLIAgentManagerTest` mein regression tests hain jo galat PATH pakad lete hain

### Agent process TTY ke bina chalta hai
`AgentRunner` ek normal process chalata hai (PTY nahi). One-shot prompt execution ke liye
ye theek hai, par jo agent bina TTY ke behave nahi karta uske liye terminal button hai.
Agar baad mein zarurat pade to `TerminalSession` + `TerminalSessionClient.onTextChanged()`
se PTY-backed chat banaya ja sakta hai.

### Build verification

`gradlew :app:assembleDebug test` **BUILD SUCCESSFUL** — yeh actually chalaya gaya hai:

```
BUILD SUCCESSFUL
226 actionable tasks: 35 executed, 191 up-to-date
```

- Chaaron ABI ke debug APK bane: `app/build/outputs/apk/debug/MobileCode-v0.0.2-debug_*.apk`
  (arm64-v8a, armeabi-v7a, x86, x86_64, universal)
- Saare unit tests pass — **0 failures, 0 errors**. Naye `AgentOutputParserTest` (13) aur
  `CLIAgentManagerTest` (21) ke saath, kul 36 tests.
- Merged manifest mein dono nayi activities confirm hui, aur label `@string/app_name` →
  `Mobile Code` resolve hota hai.

Build ke liye is machine par do environment fixes chahiye the (code ke nahi, repo ke bahar):
- `~/.gradle/gradle.properties` mein JDK toolchain pin karna pada, kyunki Gradle ek adhoora JRE
  (`~/.antigravity-ide/extensions/redhat.java-.../jre/21...`, jismein `jlink.exe` nahi hai)
  auto-detect kar raha tha. Ek `.bak` bhi bana diya hai.
- NDK `29.0.14206865` (`gradle.properties` mein) install nahi hai; installed
  `28.2.13676358` use kiya via `JITPACK_NDK_VERSION`.
- `local.properties` banani padi (`sdk.dir` forward slashes ke saath) — yeh gitignored hai.

**Device par test karna abhi baaki hai** — yeh kaam is machine par sirf compile + unit-test
level tak verify hua hai, actual Android device par nahi.

---

## 8. Aage kya kar sakte ho

1. **Device par test karo** — har agent ka GUI command verify karo, jo galat ho use
   dashboard se theek karo (ya `CLIAgentManager.AGENTS` mein default update karo)
2. **Chat history persist karo** — abhi process ke saath chali jaati hai (ObjectBox already
   dependency hai, `app/src/main/java/com/termux/app/database/`)
3. **PTY-backed chat** — interactive agents ko bhi chat mein laane ke liye
4. **File change cards** — agent ne kaunsi file badli, uske liye ek naya `ChatMessage.Type`
5. **Streaming diff view** — agent ke edits ko side-by-side dikhana
6. **Multi-agent tabs** — ek saath do agents, do chat tabs
7. **Tests badhao** — `AgentOutputParser` aur `CLIAgentManager` ke basic tests ho gaye
   (`app/src/test/java/com/termux/app/gui/`, `.../app/terminal/`). Aage:
   `AgentRunner` ka output streaming, `AgentChatActivity` ka streaming-merge behaviour,
   aur `buildGuiPromptCommand()` ka end-to-end command string (Robolectric ke saath)

---

## 9. Changed files ki poori list

**Naye (19):**
```
app/src/main/java/com/termux/app/gui/ChatMessage.java
app/src/main/java/com/termux/app/gui/AgentOutputParser.java
app/src/main/java/com/termux/app/gui/AgentRunner.java
app/src/main/java/com/termux/app/gui/ChatMessageAdapter.java
app/src/main/java/com/termux/app/gui/AgentChatActivity.java
app/src/main/java/com/termux/app/gui/AgentListAdapter.java
app/src/main/java/com/termux/app/gui/AgentDashboardActivity.java
app/src/test/java/com/termux/app/gui/AgentOutputParserTest.java
app/src/test/java/com/termux/app/terminal/CLIAgentManagerTest.java
app/src/main/res/layout/activity_agent_chat.xml
app/src/main/res/layout/item_chat_message_user.xml
app/src/main/res/layout/item_chat_status.xml
app/src/main/res/layout/activity_agent_dashboard.xml
app/src/main/res/layout/dialog_agent_gui_command.xml
app/src/main/res/drawable/bg_bubble_agent.xml
app/src/main/res/drawable/bg_bubble_user.xml
app/src/main/res/drawable/bg_bubble_error.xml
app/src/main/res/drawable/bg_badge_installed.xml
app/src/main/res/drawable/bg_badge_missing.xml
upgrade.md
```

**Badle (25):**
```
gradle.properties                          (UTF-16 corruption fix)
settings.gradle                            (duplicate includes)
app/build.gradle                           (branding + APK naam + jacoco 0.8.12)
app/src/main/AndroidManifest.xml           (2 activities + @string/app_name)
app/src/main/res/values/strings.xml        (branding)
app/src/main/res/values/colors.xml         (chat colors)
app/src/main/res/layout/activity_home.xml  (2 naye toolbar buttons)
app/src/main/res/layout/item_chat_message.xml   (dead → agent bubble)
app/src/main/res/layout/item_cli_agent.xml      (dead → agent card)
app/src/main/java/com/termux/app/terminal/CLIAgentManager.java  (GUI commands + SSL fix)
app/src/main/java/com/termux/app/terminal/AIChatManager.java    (install prelude)
app/src/main/java/com/termux/app/terminal/ErrorExplanationSheet.java (lifecycle + branding)
app/src/main/java/com/termux/app/terminal/FileContextBuilder.java    (branding)
app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java (branding)
app/src/main/java/com/termux/app/ZeroCodeHomeActivity.java  (wiring + closeTab + branding)
app/src/main/java/com/termux/app/UriToPathUtils.java        (SD card support)
app/src/main/java/com/termux/app/TermuxActivity.java        (TermuxConstants)
app/src/main/java/com/termux/app/FileExplorerAdapter.java   (TermuxConstants)
termux-shared/src/main/res/values/strings.xml               (branding)
app/src/main/res/values/themes.xml                          (unused Theme.MyApplication hataya)
.gitignore                                                  (objectbox windows dll)
.github/workflows/run_tests.yml                             (master → main)
.github/workflows/gradle-wrapper-validation.yml             (master → main)
.github/workflows/debug_build.yml                           (master → main)
.github/workflows/dependency-submission.yml                 (master → main)
```

**Delete (9) — dead project-template scaffolding:**

```
build.gradle.kts
settings.gradle.kts
app/build.gradle.kts
app/src/main/java/com/example/myapplication/MainActivity.kt
app/src/main/java/com/example/myapplication/ui/theme/Color.kt
app/src/main/java/com/example/myapplication/ui/theme/Theme.kt
app/src/main/java/com/example/myapplication/ui/theme/Type.kt
app/src/test/java/com/example/myapplication/ExampleUnitTest.kt
app/src/androidTest/java/com/example/myapplication/ExampleInstrumentedTest.kt
```

### Note: Compose/Kotlin toolchain ab dead hai (nahi hataya)

6 Kotlin files delete hone ke baad app mein **0 `.kt` files** bachi hain, phir bhi `app/build.gradle`
abhi bhi Kotlin Compose plugin, `buildFeatures { compose true }`, aur Compose/`activity-compose`/
`room-runtime` dependencies apply karta hai. Isi wajah se merged manifest mein yeh extra entries
aati hain: `androidx.compose.ui.tooling.PreviewActivity`, `androidx.activity.ComponentActivity`,
`androidx.room.MultiInstanceInvalidationService`, `androidx.startup.InitializationProvider`,
`androidx.profileinstaller.ProfileInstallReceiver`.

Yeh harmless hai par APK size aur build time dono badhata hai. Hatana safe hai (build verify ho
chuki hai) par yeh build-config change hai jo CI/release ko touch karta hai, isliye alag se kar
lena — bolo to kar dunga.
