# 蓝科客户端增强 v1.0.1（client-enhancement）

安装一次后，客户继续双击原来的 **Lucanet Software Manager** 或原快捷方式。正常关闭 Financial Client 时，增强自动处理全部临时文件；正常退出、登录过期/会话超时及其他客户端状态触发“发现未知文件”时，都静默执行“保留”。客户无需运行脚本、配置文件名或编写 Java。

## 实现的功能

1. **全部临时文件归档**：在原退出检查前，递归移动当前客户端临时根目录内的全部普通文件，包括未知名称、不同扩展名、隐藏文件、空文件和各层子目录。
2. **未知临时文件提示静默保留**：覆盖正常退出、登录过期/会话超时，以及启动、运行等其他 Financial Client 状态触发的同一个“发现未知文件”对话框。统一执行“保留文件”，不弹出该提示、不自动删除文件，也不要求客户端已进入退出状态。
3. **外部 OID 链接新窗口导航**：`ln:goto/LNFC/数据库名称?oid=XXXXX` 默认调用客户端原生新窗口链接动作，先新建窗口，再在该窗口跳转；完整数据库名、查询参数和 fragment 原样保留。设置 → 功能中可随时开关，下次链接立即生效。
4. **兼容版本热更新**：更新下载校验后，可更新同一安装目录下运行中的增强模块，保留客户端登录会话。v1.0.0 首次升级仍需正常关闭后安装。
5. **原入口自动加载**：安装器把增强参数写入 Software Manager/KickStart 的原生启动配置，启动链会继续向 Financial Client 传递 Agent。日常无需额外启动器，也不依赖 `JAVA_TOOL_OPTIONS` 或系统 `JAVA_HOME`。

正常可移动的文件归档到用户目录下的 `LucaNet-exit-archive/日期时间-pid进程号-随机值/原相对路径`。每批使用独立目录，不覆盖已有归档。归档范围仅为当前客户端的 `ApplicationPathes.getTempDir()`，不扫描 Windows 系统 TEMP。符号链接、目录联接和特殊文件不跟随；不能移动的文件保留在原处并记日志。

**登录过期场景也已覆盖。** 旧版只有进入 `PREPARE_STOP`、`STOPPING`、`DISPOSING` 后才静默处理，登录过期时客户端可能仍是 `RUNNING`。新版不再以这些状态为前提，也不依赖状态查询成功。

非退出阶段只静默返回“保留文件”，文件留在原处；全量归档仍只在正常退出入口执行，避免登录过期或运行时移动仍可能使用的临时文件。登录过期通知、重新登录、账户锁定、保存确认等其他业务提示保持原样；本功能不会延长登录有效期或绕过身份验证。非 Financial Client 进程的弹窗行为保持原样。

从 v1.0.0 首次升级时，先正常关闭客户端与 Software Manager，再打开本版 `enhancement.exe`，在工作区点击“安装”。之后从原 Software Manager 启动客户端。v1.0.1 建立热更新通道后，兼容该协议的后续版本可保持登录应用更新；功能开关本身无需重启。

## 图形界面：工作区与设置

**完整解压交付 ZIP，然后双击 [enhancement.exe](enhancement.exe)。** 用户无需打开终端，也无需输入命令。请保留同目录的 `tools`、`dist` 和 PowerShell 文件，不能只拷贝一个 EXE。

客户交付 ZIP 仅包含保持全部图形界面功能所需的 **6 个运行文件**：

```text
enhancement.exe
install.ps1
resolve-lucanet-jdk.ps1
tools/
  gui-core.ps1
  gui-service.ps1
dist/
  client-enhancement.jar
```

README、维护源码、测试、构建脚本、命令行快捷入口、IDE 配置及历史产物不进入 ZIP。安装、卸载、检查、设置和更新功能均通过 GUI 使用；此精简不删改仓库中的维护文件。ASM 许可保留在 JAR 内的 `META-INF/ASM-LICENSE.txt`，不额外复制一份到 ZIP。

1. 在左侧选择“工作区”。程序自动识别 Lucanet 安装目录。存在多个安装时可从列表切换；未识别时点击“浏览…”选择包含 `LucaNetSoftwareManager.exe` 的文件夹，也可直接输入目录并点“检查状态”。
2. 确认“JDK”显示所选目录 `.jdk` 中的最高完整版本。
3. 正常关闭 Software Manager 和所有客户端，等待“Lucanet 运行状态”显示“未运行”。
4. 点击“安装”。完成后即可关闭管理器，继续使用原来的 Software Manager。

| 界面操作 | 作用 |
| --- | --- |
| 自动识别 | 查询当前进程、安装注册表、桌面/开始菜单快捷方式和常用安装位置，不扫描整盘。 |
| 浏览… | 手动选择安装目录，支持中文和空格路径。 |
| 检查状态 | 执行只读检查并更新安装状态、运行状态和 JDK；配置异常会给出一次提示。维护工程内也可通过 `check.cmd` 查看完整诊断。 |
| 安装 | 备份原生配置并安装当前交付版本；运行中按钮不可用。 |
| 卸载 | 确认后停用增强，保留归档、日志、原配置备份及不再加载的 JAR。 |
| 打开Lucanet.Software Manager | 打开所选目录的原生 Manager，方便验证实际加载。 |
| 以管理员身份重新打开 | 安装目录无写权限时使用。授权后等管理员新窗口就绪，原窗口自动关闭；取消授权时原窗口保留。 |
| 设置 → 功能 | 管理新窗口链接、自动刷新开关和间隔；默认 8 秒，范围 2–300 秒。 |
| 设置 → 应用 | 展示软件版本、GitHub 仓库、检查更新和更新包操作。 |

窗口标题为 **Lucanet Client Enhancement**，沿用 Lucanet 的深蓝侧栏、紫色主按钮、白色内容卡片。左侧“工作区”保留安装目录、安装状态、Lucanet 运行状态、JDK，以及安装、卸载、打开 Manager、管理员重启等操作。工作区不再放置任何刷新设置或顶部设置条。

点击“设置”进入“应用 / 功能”页面。“应用”分为软件信息、版本与更新、项目入口三张卡片；“功能”用独立卡片展示链接导航、自动刷新和间隔。GUI 的标题栏、任务栏、EXE 和应用信息页使用 Lucanet 客户端原始图标，资源内嵌。标题、说明和操作控件按内容高度排列，图标保持完整的正方形比例；小窗口下可滚动查看剩余内容。没有添加赞助商、问题反馈等当前未提供的入口。

设置开关使用灰色（关闭）和紫色（开启）的圆角轨道，支持鼠标与空格键切换；键盘焦点显示细圆角描边，禁用时降低对比度，不显示原生复选框的矩形背景。

### 功能设置

- **链接在新窗口中打开**：默认开启；匹配 `ln:goto/LNFC/数据库?oid=数字` 外部链接及 Lucanet 规范化的 `ln[s]://服务器/goto/LNFC/数据库?oid=数字` 形式。关闭时沿用原窗口跳转；设置原子写入 `%LOCALAPPDATA%/Lucanet/ClientEnhancement/features.properties`，每次接收链接读取，不依赖 GUI 自动刷新周期，也不需要 GUI 常驻。采用当前 Windows 用户配置，对该用户的已增强客户端生效。登录和权限仍由原客户端处理。
- **自动刷新**：默认开启，定时检查安装和运行状态；可随时关闭，手动“检查状态”始终可用。
- **刷新间隔**：默认 8 秒，范围 2–300 秒，数字框与“秒”保持紧凑对齐。
- **恢复功能默认设置**：恢复为开启新窗口链接、开启自动刷新、8 秒间隔，不重置应用更新设置。

刷新/更新界面偏好立即生效并保存在 `%LOCALAPPDATA%/Lucanet/ClientEnhancement/ui.json`，兼容此前只有刷新字段的配置文件。自动刷新保持静默，不显示进度条、不禁用输入、不弹窗；切换页面不会丢失工作区目录与状态。检测到 Lucanet 正在运行时，安装和卸载仍然禁止。**卸载后重装也要先正常关闭 Software Manager 和客户端。**

目录识别和状态检查各有 15 秒等待上限。超时后恢复光标及操作控件，可重试或手动选择目录；自动刷新超时不弹窗。该上限只用于只读后台检查，安装、卸载和热更新仍等待事务完成。日志在返回 GUI 前转换为纯文本，避免 Windows PowerShell 5.1 展开文件系统元数据造成启动后长期停留“待检查”。

### 应用信息与 GitHub 更新

“应用”页展示名称、Windows 平台、当前版本、稳定更新源、上次检查时间，以及 GitHub 仓库和发布内容入口。

更新源固定为 [dadazaiddw/Lucanet-Client-Enhancement](https://github.com/dadazaiddw/Lucanet-Client-Enhancement) 的 **稳定 GitHub Releases**。仓库链接末尾的中文句号不是地址的一部分，程序使用规范 URL。更新模块不会提交代码、推送分支、创建 Release 或读取 Git 凭据。

1. 点击“检查更新”。可选开启“自动检查更新”，以后打开软件时检查一次；该选项默认关闭，不会自动下载。
2. 存在更高版本且 Release 附带可校验的完整 ZIP 时显示“下载更新”。下载至当前用户 `%LOCALAPPDATA%/Lucanet/ClientEnhancement/updates` 的独立目录。
3. 校验 GitHub 返回的 SHA-256、大小和完整包结构，拒绝越界 ZIP 路径、符号链接、重复路径和不完整包。
4. 校验成功后点“应用热更新”。新包后台先处理所选安装，再打开新版 GUI；新版窗口就绪后关闭旧窗口，失败则保留旧窗口。
5. 已安装且没有 Lucanet 进程时执行正常更新；同一用户的全部相关进程均支持热更新协议 1 时执行在线更新。未安装增强时只切换 GUI。旧版/身份无法核实/协议不兼容时明确提示正常关闭后安装。

热更新使用本地文件握手、PID 与启动时间核对、SHA-256 校验、准备/提交/回滚流程。启动 JAR 保持原位，通过 `.client-enhancement/versions/<SHA256>.jar` 与 `active.properties` 切换版本，解决 Windows 正在使用的 JAR 文件锁。当前会话切换逻辑，下次原启动器启动也加载相同版本。准备或转换失败会恢复旧行为及版本指针，不杀死客户端、不注销用户、不修改数据库。进程意外退出等恢复失败会明确报错，不能将失败提示视为更新成功。更新期间请勿另行启动/退出 Manager 或执行安装、卸载。

GUI 从下载目录启动，不覆盖原解压目录或旧快捷方式；再次启动请使用新版本目录中的 `enhancement.exe`。增强模块已复制到安装目录，客户端日常使用不依赖下载缓存。每个增强 JVM 内的后台线程仅服务本机当前用户的更新请求，不创建系统服务、不监听网络、不启用 Attach；按内容摘要复用运行缓存。

热更新协议 1 允许替换内部行为实现及转换规则；稳定桥/更新协议不兼容的未来版本需要正常关闭后安装。首次从 v1.0.0 升级不能热注入，因为旧进程没有此通道。自动检查仅查新，下载与应用均由用户点击。软件版本按数字比较，禁止降级；当前 GUI、EXE、JAR manifest 和 Maven 版本统一为 **v1.0.1**。

网络不通、限流、没有 Release、没有兼容 ZIP 或缺少 SHA-256 时显示明确状态，可重试或打开发布页面。仓库源码推送本身不会成为更新，需发布包含完整 ZIP 的稳定 Release。

安装器将 JAR 放在安装目录的 `.client-enhancement/client-enhancement.jar`，并备份、修改已有版本的 Manager/KickStart 原生启动配置。其他 Agent 参数保持原样。备份位于 `.client-enhancement/backups`；发生错误会尝试恢复已修改配置。

GUI 使用 Windows 自带 .NET Framework 显示界面，后台脚本以隐藏窗口运行；无需系统 Java。Java 增强及维护构建仍使用 **Lucanet 安装目录下 `.jdk` 的最高完整 JDK**，不从系统 `JAVA_HOME` 或 PATH 选择。实际 Financial Client 的 JVM 仍由 Lucanet 原生启动器配置决定。

## 客户日常操作

安装后直接运行原来的 `LucaNetSoftwareManager.exe`，从中启动 Financial Client，像平时一样使用并关闭。原来的快捷方式继续有效，可以移走本项目/安装 ZIP，因为已安装的增强包存放在 Lucanet 目录中。

**Software Manager 升级到新版本目录后，需要重新运行一次安装器，并做版本兼容验收。** 升级可能重新生成原生启动配置；当前实现不安装后台常驻监控服务，也不自动改写未来未知版本。原生启动配置首次安装/修复仍需关闭客户端与 Manager；已接入协议 1 的运行会话可通过设置中的“应用热更新”升级兼容增强模块。

## 检查结果如何理解

主界面的“已安装”表示启动参数和增强 JAR 完整；“当前版本”表示当前有效模块（含热更新指针）与交付 JAR 一致，“可更新”表示有差异。“需要修复”表示配置或安装文件不完整。运行状态会显示 Software Manager/客户端是否正在使用所选安装目录，运行时禁止安装和卸载。

按本版精简设计，加载证据和历史退出记录不再展示在 GUI 中。维护人员可在源码工程使用 `check.cmd` 或 `install.cmd --check` 查看完整诊断，其中会区分当前进程的加载证据与历史记录，并核对路径、PID 和启动时间。精简运行包仍内含支持 `-Check` 的 `install.ps1`，GUI 的检查功能直接复用同一逻辑。**配置完整不等于已经完成每一次客户退出的实测。** 真实退出效果仍以对应客户端正常关闭和归档结果为准。

## 维护命令（客户可直接使用 GUI）

以下快捷命令保留在源码工程中，不放入最小运行 ZIP；客户使用 GUI 即可。命令入口统一为小写，不含 `Enhancement`：

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
| `TEMP_FILE_DIALOG_SUPPRESSED` | 客户端未知临时文件提示已静默返回“保留”，覆盖会话过期等非退出调用。 |
| `LINK_NEW_WINDOW` | 已执行原生新窗口链接动作，不记录具体业务 URI。 |
| `HOT_UPDATE_UNAVAILABLE` / `ACTIVE_MODULE_INVALID` | 热更新通道或版本指针异常，需要检查或离线重装。 |
| `EXIT_DIALOG_SUPPRESSED` | 旧版本的退出专用记录；状态检查仍兼容读取。 |
| `UNSUPPORTED_CLASS` / `TRANSFORM_FAILED` | 内部类不兼容或转换失败，需要重新适配，不应当作已经启用。 |

维护验收：从原 Software Manager 启动实际客户端，检查客户端日志有 `AGENT_ACTIVE` 和退出入口的 `TRANSFORMED`，然后正常退出，核对 `ARCHIVE_SUMMARY` 及归档结果。归档耗时取决于文件数量/体积；强制结束进程、崩溃或断电不保证执行。

会话过期场景可检查 `TRANSFORMED: com/lucanet/lisa/util/filehandling/UnknownFileDialogCreator` 与 `TEMP_FILE_DIALOG_SUPPRESSED`：前者说明提示入口已接入，后者说明一次提示已静默保留。该记录不表示重新登录已完成，也不表示进行了退出归档。当前测试覆盖真实对话框在运行/退出等状态的行为，尚未在客户生产会话中等待完整闲置超时。

## 维护、构建和测试

当前源码工程位于 `Lucanet-Client-Enhancement`，父目录保留 `AGENTS.md`。已经移除开发者回调接口和自定义代码示例；源码仅用于维护内置功能：

| 文件 | 内部职责 |
| --- | --- |
| [ExitAgent.java](src/main/java/cn/lucanet/enhancement/exit/ExitAgent.java) | JVM Agent 入口。 |
| [NativeTransformer.java](src/main/java/cn/lucanet/enhancement/exit/NativeTransformer.java) | 对已核实的方法签名实施内存转换，原专有 JAR 不被改写。 |
| [LinkNavigation.java](src/main/java/cn/lucanet/enhancement/exit/LinkNavigation.java) | 原生外部 OID 新窗口动作；FeatureSettings.java 读取即时开关。 |
| [AgentRuntime.java](src/main/java/cn/lucanet/enhancement/exit/AgentRuntime.java) | 稳定桥、独立模块加载与运行时转换；HotUpdateClient/Server 负责事务握手。 |
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

`test-package.ps1` 会先生成最终 ZIP，断言仅包含上述 6 个文件，再从中文/空格路径的独立解压目录验证 GUI 页面构造、更新器校验解压、目录发现、最高 Lucanet JDK 选择、安装、检查、卸载与重装。所有写操作都针对测试目录中的假安装，证明运行包不依赖仓库中的 README、源码或构建文件。

`build.cmd` 同时构建 Java Agent 和 `enhancement.exe`，仅重建 GUI 可执行 `build-gui.ps1`。GUI 的 Windows 外壳使用系统自带 .NET 编译器；状态与动作在 [gui/enhancement.cs](gui/enhancement.cs)，页面布局在 [gui/views.cs](gui/views.cs)，软件版本及仓库地址在 [gui/app-info.cs](gui/app-info.cs)，更新实现在 [gui/update-service.cs](gui/update-service.cs)，偏好与进程调用在 `gui/gui-settings.cs`、`gui/gui-service.cs`；只读检测和后台通信位于 [tools/gui-core.ps1](tools/gui-core.ps1)、[tools/gui-service.ps1](tools/gui-service.ps1)。它们复用 `install.ps1`，不实现第二套部署逻辑。

构建/测试始终从 Software Manager 安装目录的 `.jdk` 选择版本最高的完整 JDK，按数字比较版本及构建号，不从环境变量 `JAVA_HOME`/PATH 选择。可传 `-LucanetHome` 改安装根目录。默认离线构建使用安装目录 `.resource` 中的 ASM 9.7 与 ASM Commons 9.7，在交付 JAR 内将 ASM 重定位到私有包，避免与客户端模块冲突；许可见 [ASM-LICENSE.txt](licenses/ASM-LICENSE.txt)。本机使用 `LucaNetJDK-21.0.11+10`，产物为 Java 11 字节码。

也可用 `maven.ps1 -MavenArguments @('clean','package','-DskipTests')` 构建带私有 ASM 的 Maven 产物；Maven 依赖需已缓存或可下载，功能验收仍使用上述专用测试。`.ps1` 使用 UTF-8 BOM，`.cmd` 使用 ASCII，兼容 Windows PowerShell 5.1。

最小完整运行包：[dist/lucanet-client-enhancement.zip](dist/lucanet-client-enhancement.zip)。ZIP 仅包含上述 6 个运行文件，不捆绑源码、README、测试、构建文件、Lucanet 专有业务库或测试替身类；校验值 `dist/SHA256SUMS.txt` 单独保留在工程交付目录，不在 ZIP 中重复打包。

## 目录与清理规则

| 位置 | 用途 |
| --- | --- |
| `src/main` | 内置临时文件、链接导航及热更新实现，没有开发人员自定义回调入口。 |
| `src/test`、`src/integration`、`tools` | 有效回归测试、真实 Lucanet 库验证、GUI 后台服务及离线 JAR 组装工具。测试替身不进入 Agent JAR。 |
| `gui`、`enhancement.exe` | Windows GUI 源码/清单和可双击的管理程序。 |
| `docs`、`licenses` | 验证/审计说明与第三方许可。 |
| `.idea` | 本机 IDE 的必要工程、编码及 Lucanet JDK/Maven 配置；不进入发布 ZIP。 |
| `dist` | 当前 `client-enhancement.jar`、通用 ZIP、与其内容一致的版本 ZIP（当前为 `v1.0.1`）和 `SHA256SUMS.txt`。无需保留 ZIP 的重复解压副本。 |
| `build`、`target` | 测试/Maven 运行后产生的可再生成中间文件，不属于源码或交付包。测试结束并确认不需要排查后可清理。 |

`build.cmd` 会在本次编译结束后删除自己的临时 class 目录；`package.cmd` 按固定的 6 文件白名单直接生成 ZIP，不递归打包任何源码目录，也不保留 `release-*` 副本。审计结论、实际删除项和回归结果见 [项目审计记录](docs/项目审计记录.md)。该记录反映当时的工程结构；源码和文档的保留不代表它们会进入客户交付包。

## v1.0.1 验收补充

本次为 v1.0.1 的跳转修复包。旧包通过反射执行新窗口动作，触发 Lucanet `VirtualSocket` 的 `no reflection allowed!`，并错误地将失败的导航标记为已处理。修复改用 `javax.swing.Action.actionPerformed` 直接执行原生按钮动作，不修改客户端检查；执行失败时继续原有导航，避免吞掉链接。版本号保持 v1.0.1，已装旧包的用户需使用本次新包重新安装，同版本不会通过 GitHub 检查更新自动提示。

`test.ps1` 包含反射错误复现、直接 Swing 动作、失败回退、即时开关和双 JVM 热更新测试；`test-navigation-real.ps1` 使用本机真实 2311 / 2505 外部监听器、原生 API 契约以及未修改且保持启用的 `VirtualSocket` 检查方法验证，不连接业务数据库。额外使用 Lucanet 自带 Java 17 复测 2311 客户端；默认构建仍用 `.jdk` 中最高版本。GUI 布局覆盖 100%–200% 缩放和本机实际 DPI。交付前还运行安装/卸载、原生启动链、真实退出生命周期和六文件解压包回归。

真实登录会话中，从原 Software Manager 启动后，开启“链接在新窗口中打开”，执行原有 `Desktop.getDesktop().browse(URI.create("ln:goto/LNFC/lncn_xz?oid=2466470"))`；确认新增窗口到达目标，再关闭开关重试，确认恢复原窗口跳转。自动化测试未连接业务库，不能代替目标数据库的权限/OID 与真实登录会话验收。
