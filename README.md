# 蓝科客户端增强（client-enhancement）

安装一次后，客户继续双击原来的 **Lucanet Software Manager** 或原快捷方式。正常关闭 Financial Client 时，增强自动处理全部临时文件，并静默处理“发现未知文件”提示。客户无需运行脚本、配置文件名或编写 Java。

## 实现的功能

1. **全部临时文件归档**：在原退出检查前，递归移动当前客户端临时根目录内的全部普通文件，包括未知名称、不同扩展名、隐藏文件、空文件和各层子目录。
2. **退出时不显示“发现未知文件”**：归档失败、文件占用或后续组件重新生成的剩余文件，在退出阶段自动执行“保留文件”结果，不弹出该提示，不自动删除它们。
3. **原入口自动加载**：安装器把增强参数写入 Software Manager/KickStart 的原生启动配置，启动链会继续向 Financial Client 传递 Agent。日常无需额外启动器，也不依赖 `JAVA_TOOL_OPTIONS` 或系统 `JAVA_HOME`。

正常可移动的文件归档到用户目录下的 `LucaNet-exit-archive/日期时间-pid进程号-随机值/原相对路径`。每批使用独立目录，不覆盖已有归档。归档范围仅为当前客户端的 `ApplicationPathes.getTempDir()`，不扫描 Windows 系统 TEMP。符号链接、目录联接和特殊文件不跟随；不能移动的文件保留在原处并记日志。

本功能只处理退出阶段的“发现未知文件”对话框。账户锁定、保存确认等其他业务提示继续由蓝科原流程处理。运行或启动阶段的未知文件提示也保持原行为。

## 图形界面：工作区与设置

**完整解压交付 ZIP，然后双击 [enhancement.exe](enhancement.exe)。** 用户无需打开终端，也无需输入命令。请保留同目录的 `tools`、`dist` 和 PowerShell 文件，不能只拷贝一个 EXE。

1. 在左侧选择“工作区”。程序自动识别 Lucanet 安装目录。存在多个安装时可从列表切换；未识别时点击“浏览…”选择包含 `LucaNetSoftwareManager.exe` 的文件夹，也可直接输入目录并点“检查状态”。
2. 确认“JDK”显示所选目录 `.jdk` 中的最高完整版本。
3. 正常关闭 Software Manager 和所有客户端，等待“Lucanet 运行状态”显示“未运行”。
4. 点击“安装”。完成后即可关闭管理器，继续使用原来的 Software Manager。

| 界面操作 | 作用 |
| --- | --- |
| 自动识别 | 查询当前进程、安装注册表、桌面/开始菜单快捷方式和常用安装位置，不扫描整盘。 |
| 浏览… | 手动选择安装目录，支持中文和空格路径。 |
| 检查状态 | 执行只读检查并更新安装状态、运行状态和 JDK；配置异常会给出一次提示。完整诊断仍可通过 `check.cmd` 查看。 |
| 安装 | 备份原生配置并安装当前交付版本；运行中按钮不可用。 |
| 卸载 | 确认后停用增强，保留归档、日志、原配置备份及不再加载的 JAR。 |
| 打开Lucanet.Software Manager | 打开所选目录的原生 Manager，方便验证实际加载。 |
| 以管理员身份重新打开 | 安装目录无写权限时使用。授权后等管理员新窗口就绪，原窗口自动关闭；取消授权时原窗口保留。 |
| 设置 → 功能 | 管理自动刷新开关和间隔；默认 8 秒，范围 2–300 秒。 |
| 设置 → 应用 | 展示软件版本、GitHub 仓库、检查更新和更新包操作。 |

窗口标题为 **Lucanet Client Enhancement**，沿用 Lucanet 的深蓝侧栏、紫色主按钮、白色内容卡片。左侧“工作区”保留安装目录、安装状态、Lucanet 运行状态、JDK，以及安装、卸载、打开 Manager、管理员重启等操作。工作区不再放置任何刷新设置或顶部设置条。

点击“设置”进入“应用 / 功能”页面。“应用”分为软件信息、版本与更新、项目入口三张卡片；“功能”用独立卡片展示自动刷新和间隔。标题、说明和操作控件按内容高度排列，图标保持完整的正方形比例；小窗口下可滚动查看剩余内容。没有添加赞助商、问题反馈等当前未提供的入口。

设置开关使用灰色（关闭）和紫色（开启）的圆角轨道，支持鼠标与空格键切换；键盘焦点显示细圆角描边，禁用时降低对比度，不显示原生复选框的矩形背景。

### 功能设置

- **自动刷新**：默认开启，定时检查安装和运行状态；可随时关闭，手动“检查状态”始终可用。
- **刷新间隔**：默认 8 秒，范围 2–300 秒，数字框与“秒”保持紧凑对齐。
- **恢复功能默认设置**：恢复为开启自动刷新、8 秒间隔，不重置应用更新设置。

设置立即生效并保存在 `%LOCALAPPDATA%/Lucanet/ClientEnhancement/ui.json`，兼容此前只有刷新字段的配置文件。自动刷新保持静默，不显示进度条、不禁用输入、不弹窗；切换页面不会丢失工作区目录与状态。检测到 Lucanet 正在运行时，安装和卸载仍然禁止。**卸载后重装也要先正常关闭 Software Manager 和客户端。**

### 应用信息与 GitHub 更新

“应用”页展示名称、Windows 平台、当前版本、稳定更新源、上次检查时间，以及 GitHub 仓库和发布内容入口。

更新源固定为 [dadazaiddw/Lucanet-Client-Enhancement](https://github.com/dadazaiddw/Lucanet-Client-Enhancement) 的 **稳定 GitHub Releases**。仓库链接末尾的中文句号不是地址的一部分，程序使用规范 URL。更新模块不会提交代码、推送分支、创建 Release 或读取 Git 凭据。

1. 点击“检查更新”。可选开启“自动检查更新”，以后打开软件时检查一次；该选项默认关闭，不会自动下载。
2. 存在更高版本且 Release 附带可校验的完整 ZIP 时显示“下载更新”。下载至当前用户 `%LOCALAPPDATA%/Lucanet/ClientEnhancement/updates` 的独立目录。
3. 校验 GitHub 返回的 SHA-256、大小和完整包结构，拒绝越界 ZIP 路径、符号链接、重复路径和不完整包。
4. 校验成功后可点“打开新版本”。经用户确认后启动解压目录中的新 GUI，等新窗口就绪再关闭旧窗口。失败时当前窗口保留。
5. 若要更新客户安装目录中的 Java 增强，关闭 Lucanet 后，在新 GUI 的“工作区”点击“安装”。

更新采用独立目录启动，**不会覆盖原交付目录或原快捷方式**，也不自动改写 Lucanet 安装文件。关闭新版本后，可以再次运行下载目录的 `enhancement.exe`；若仍打开旧路径，则运行的仍是旧版本。自动更新检查只负责查新，安装/应用操作始终由用户触发。

网络不通、限流、没有 Release、没有兼容 ZIP 或缺少 SHA-256 时显示明确状态，可重试或打开发布页面。版本按数字比较，阻止降级。**当前界面、EXE 元数据及项目版本统一保持为 `v1.0.0`**；GitHub 返回 `v1.0.0` 时按同版本处理，不会提示本地版本更高或强制下载。

安装器将 JAR 放在安装目录的 `.client-enhancement/client-enhancement.jar`，并备份、修改已有版本的 Manager/KickStart 原生启动配置。其他 Agent 参数保持原样。备份位于 `.client-enhancement/backups`；发生错误会尝试恢复已修改配置。

GUI 使用 Windows 自带 .NET Framework 显示界面，后台脚本以隐藏窗口运行；无需系统 Java。Java 增强及维护构建仍使用 **Lucanet 安装目录下 `.jdk` 的最高完整 JDK**，不从系统 `JAVA_HOME` 或 PATH 选择。实际 Financial Client 的 JVM 仍由 Lucanet 原生启动器配置决定。

## 客户日常操作

安装后直接运行原来的 `LucaNetSoftwareManager.exe`，从中启动 Financial Client，像平时一样使用并关闭。原来的快捷方式继续有效，可以移走本项目/安装 ZIP，因为已安装的增强包存放在 Lucanet 目录中。

**Software Manager 升级到新版本目录后，需要重新运行一次安装器，并做版本兼容验收。** 升级可能重新生成原生启动配置；当前实现不安装后台常驻监控服务，也不自动改写未来未知版本。已经运行的 JVM 不会热更新，安装或升级增强后应重新启动客户端与 Manager。

## 检查结果如何理解

主界面的“已安装”表示启动参数和增强 JAR 完整；“当前版本”表示与当前交付 JAR 一致，“可更新”表示有差异。“需要修复”表示配置或安装文件不完整。运行状态会显示 Software Manager/客户端是否正在使用所选安装目录，运行时禁止安装和卸载。

按本版精简设计，加载证据和历史退出记录不再展示在 GUI 中。维护人员可用 `check.cmd` 或 `install.cmd --check` 查看完整诊断，其中会区分当前进程的加载证据与历史记录，并核对路径、PID 和启动时间。**配置完整不等于已经完成每一次客户退出的实测。** 真实退出效果仍以对应客户端正常关闭和归档结果为准。

## 维护命令（客户可直接使用 GUI）

命令入口统一为小写，不含 `Enhancement`：

```powershell
.\install.cmd
.\uninstall.cmd
.\check.cmd
.\install.cmd --check
```

其他安装位置可添加 `-LucanetHome "C:\Program Files\LucaNet"`。`--check` 和 `-Check` 均为只读检查；不修改配置、不关闭进程。

`.cmd` 或 GUI 仅对本次 PowerShell 子进程使用 `ExecutionPolicy Bypass`，不修改用户或系统的持久执行策略。组织强制的组策略仍然有效。已有 `.ps1`、UTF-8 BOM 编码和 Windows PowerShell 5.1 兼容性保留。

## 日志与验收

本地日志默认位于用户目录 `.lucanet-exit-enhancement/logs/client-exit-<PID>.log`。常见记录：

| 记录 | 含义 |
| --- | --- |
| `AGENT_ACTIVE` | 该 JVM 已从安装目录加载增强。 |
| `TRANSFORMED: ...ProgramLaunchConfiguration` | 已接入原生配置生成方法。 |
| `PROPAGATED: ...SummaClientStart` | 已将增强传递给 Financial Client。 |
| `TRANSFORMED: ln/application/Application` | 客户端的退出入口已接入。 |
| `ARCHIVE_SUMMARY` | 文件归档成功、失败及跳过数量。 |
| `EXIT_ARCHIVE_FINISHED` | 退出前归档结束；有失败时仍检查 `ARCHIVE_FAILED`。 |
| `EXIT_DIALOG_SUPPRESSED` | 剩余文件的退出提示已静默执行“保留”。 |
| `UNSUPPORTED_CLASS` / `TRANSFORM_FAILED` | 内部类不兼容或转换失败，需要重新适配，不应当作已经启用。 |

维护验收：从原 Software Manager 启动实际客户端，检查客户端日志有 `AGENT_ACTIVE` 和退出入口的 `TRANSFORMED`，然后正常退出，核对 `ARCHIVE_SUMMARY` 及归档结果。归档耗时取决于文件数量/体积；强制结束进程、崩溃或断电不保证执行。

## 维护、构建和测试

项目全部位于 `ClientEnhancement`，父目录只保留 `AGENTS.md`。已经移除开发者回调接口和自定义代码示例；源码仅用于维护内置功能：

| 文件 | 内部职责 |
| --- | --- |
| [ExitAgent.java](src/main/java/cn/lucanet/enhancement/exit/ExitAgent.java) | JVM Agent 入口。 |
| [NativeTransformer.java](src/main/java/cn/lucanet/enhancement/exit/NativeTransformer.java) | 对已核实的方法签名实施内存转换，原专有 JAR 不被改写。 |
| [NativeBridge.java](src/main/java/cn/lucanet/enhancement/exit/NativeBridge.java) | 固定的启动参数传递、全量归档和退出静默保留行为。 |
| [TempFileArchiver.java](src/main/java/cn/lucanet/enhancement/exit/TempFileArchiver.java) | 文件递归移动、路径边界和归档记录。 |
| [install.ps1](install.ps1) | 安装、检查、备份和停用。 |

维护命令：

```powershell
.\build.cmd
.\test.cmd
.\test-real-lifecycle.cmd
.\test-native-integration.cmd
.\package.cmd
```

`test-gui-window.ps1` 在隔离窗体中验证工作区/应用/功能切换、设置兼容与保存、100%–200% 缩放模拟，并使用 EXE 相同的 DPI 清单测试本机真实缩放下首次显示隐藏设置页、图标/文本边界、最小窗口和滚动可达性；同时覆盖静默刷新、管理员交接和更新版本比较、下载校验等。`test-install.ps1` 提供工作区内的安装、卸载和重装测试；`test-gui.ps1` 验证目录发现、运行保护、JDK 选择和证据分离。测试使用工作区内假安装或 HTTP 替身，不修改真实客户配置、不运行远端更新。安装和验证详情见 [验证记录](docs/验证记录.md)。

`build.cmd` 同时构建 Java Agent 和 `enhancement.exe`，仅重建 GUI 可执行 `build-gui.ps1`。GUI 的 Windows 外壳使用系统自带 .NET 编译器；状态与动作在 [gui/enhancement.cs](gui/enhancement.cs)，页面布局在 [gui/views.cs](gui/views.cs)，软件版本及仓库地址在 [gui/app-info.cs](gui/app-info.cs)，更新实现在 [gui/update-service.cs](gui/update-service.cs)，偏好与进程调用在 `gui/gui-settings.cs`、`gui/gui-service.cs`；只读检测和后台通信位于 [tools/gui-core.ps1](tools/gui-core.ps1)、[tools/gui-service.ps1](tools/gui-service.ps1)。它们复用 `install.ps1`，不实现第二套部署逻辑。

构建/测试始终从 Software Manager 安装目录的 `.jdk` 选择版本最高的完整 JDK，按数字比较版本及构建号，不从环境变量 `JAVA_HOME`/PATH 选择。可传 `-LucanetHome` 改安装根目录。默认离线构建使用安装目录 `.resource` 中的 ASM 9.7 与 ASM Commons 9.7，在交付 JAR 内将 ASM 重定位到私有包，避免与客户端模块冲突；许可见 [ASM-LICENSE.txt](licenses/ASM-LICENSE.txt)。本机使用 `LucaNetJDK-21.0.11+10`，产物为 Java 11 字节码。

也可用 `maven.ps1 -MavenArguments @('clean','package','-DskipTests')` 构建带私有 ASM 的 Maven 产物；Maven 依赖需已缓存或可下载，功能验收仍使用上述专用测试。`.ps1` 使用 UTF-8 BOM，`.cmd` 使用 ASCII，兼容 Windows PowerShell 5.1。

完整发布包：[dist/lucanet-client-enhancement.zip](dist/lucanet-client-enhancement.zip)。包中包含可双击的 GUI、维护源码、安装器、卸载器和已构建 JAR，不捆绑 Lucanet 专有库或测试替身类。

## 目录与清理规则

| 位置 | 用途 |
| --- | --- |
| `src/main` | 6 个内置增强实现类，没有自定义 Java 回调入口。 |
| `src/test`、`src/integration`、`tools` | 有效回归测试、真实 Lucanet 库验证、GUI 后台服务及离线 JAR 组装工具。测试替身不进入 Agent JAR。 |
| `gui`、`enhancement.exe` | Windows GUI 源码/清单和可双击的管理程序。 |
| `docs`、`licenses` | 验证/审计说明与第三方许可。 |
| `.idea` | 本机 IDE 的必要工程、编码及 Lucanet JDK/Maven 配置；不进入发布 ZIP。 |
| `dist` | 当前 `client-enhancement.jar`、通用 ZIP、与其内容一致的版本 ZIP（当前为 `v1.0.0`）和 `SHA256SUMS.txt`。无需保留 ZIP 的重复解压副本。 |
| `build`、`target` | 测试/Maven 运行后产生的可再生成中间文件，不属于源码或交付包。测试结束并确认不需要排查后可清理。 |

`build.cmd` 会在本次编译结束后删除自己的临时 class 目录；`package.cmd` 按明确清单直接生成 ZIP，不再保留 `release-*` 源码副本。审计结论、实际删除项和回归结果见 [项目审计记录](docs/项目审计记录.md)。该记录反映清理时的项目结构；本轮新增的 GUI 和后台检测是当前功能所需文件。


## 发布更新包约定（维护人员）

- 维护 `pom.xml` 与 `gui/app-info.cs` 的版本保持一致，稳定标签使用 `v主版本.次版本.补丁`，如 `v2.1.0`。
- 执行本地构建/测试/打包后，在 GitHub Release 附加完整 ZIP，文件名使用 `lucanet-client-enhancement.zip` 或 `lucanet-client-enhancement-v2.1.0.zip`；不要只上传源码压缩包或单独 EXE。
- 更新模块使用 GitHub Release asset 的 `digest: sha256:...`。未提供该摘要时不在应用内下载/执行更新，用户可查看发布页面。
- 本次仅修改本地工作区和交付包；未执行 Git 推送，也未创建 GitHub 发布。
