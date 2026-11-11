using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Security.Principal;
using System.Threading.Tasks;
using System.Windows.Forms;

internal static class Program {
    [STAThread]
    private static void Main(string[] args) {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try { Application.Run(new EnhancementForm(args)); }
        catch (Exception e) { MessageBox.Show(e.Message, "Lucanet Client Enhancement", MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }
}

internal sealed partial class EnhancementForm : Form {
    private static readonly Color Navy = Color.FromArgb(0, 43, 61);
    private static readonly Color Purple = Color.FromArgb(149, 71, 211);
    private static readonly Color Ink = Color.FromArgb(21, 43, 57);
    private static readonly Color Muted = Color.FromArgb(103, 120, 132);
    private static readonly Color Green = Color.FromArgb(24, 120, 94);
    private static readonly Color Amber = Color.FromArgb(162, 102, 22);
    private readonly IEnhancementService service;
    private readonly GuiSettingsStore settingsStore;
    private readonly Func<string, Task<bool>> elevatedLauncher;
    private readonly Action<string, MessageBoxIcon> notify;
    private readonly Timer poll = new Timer();
    private readonly ToolTip tips = new ToolTip { AutoPopDelay = 10000 };
    private readonly bool autoDiscover;
    private GuiPreferences preferences;
    private ComboBox directories;
    private CheckBox autoRefresh;
    private NumericUpDown refreshSeconds;
    private Button discoverButton, browseButton, checkButton, installButton, uninstallButton, startButton, elevateButton;
    private Label installationValue, runningValue, jdkValue, installationDetail, runningDetail, selectionSource;
    private Dictionary<string, object> status;
    private string initialRoot;
    private string lastCheckedRoot = "";
    private bool foregroundBusy, refreshInProgress, deploymentInProgress, relaunching, closing, allowRelaunchClose;
    private int requestGeneration;

    internal EnhancementForm(string[] args) : this(args,
        new PowerShellEnhancementService(AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar)),
        GuiSettingsStore.ForCurrentUser(), ElevatedRelaunch.StartAsync, null, true) { }

    // 注入后台与重启操作用于隔离测试，不提供对外的 Java 自定义代码入口。
    internal EnhancementForm(string[] args, IEnhancementService service, GuiSettingsStore settingsStore,
        Func<string, Task<bool>> elevatedLauncher, Action<string, MessageBoxIcon> notify, bool autoDiscover, IUpdateService updates = null) {
        this.service = service; this.settingsStore = settingsStore; this.elevatedLauncher = elevatedLauncher;
        this.notify = notify; this.autoDiscover = autoDiscover;
        updateService = updates ?? new GitHubUpdateService();
        preferences = settingsStore.Load();
        initialRoot = args.Length >= 2 && args[0] == "--directory" ? args[1] : "";
        SuspendLayout(); AutoScaleDimensions = new SizeF(96F, 96F); AutoScaleMode = AutoScaleMode.Dpi;
        Text = "Lucanet Client Enhancement";
        Icon = ClientIcon.Load();
        ClientSize = new Size(1080, 720); MinimumSize = new Size(1000, 620);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(246, 248, 250); ForeColor = Ink;
        Font = new Font("Microsoft YaHei UI", 9F);
        BuildView(); ResumeLayout(true);
        Shown += async delegate { if (this.autoDiscover) { await DiscoverAsync(); if (preferences.AutoCheckUpdates && !closing) await CheckForUpdatesAsync(false); } };
        FormClosing += delegate(object sender, FormClosingEventArgs e) {
            // 只阻止正在写安装配置时关闭。只读刷新和已确认的管理员交接绝不阻止旧窗口关闭。
            if ((deploymentInProgress || updateInstalling) && !allowRelaunchClose) { e.Cancel = true; return; }
            closing = true; poll.Stop(); requestGeneration++;
        };
        FormClosed += delegate { poll.Dispose(); tips.Dispose(); };
        poll.Tick += async delegate { await RefreshStatusAsync(true); };
        ConfigurePolling();
    }

    private void ConfigurePolling() {
        poll.Stop(); poll.Interval = preferences.RefreshSeconds * 1000;
        refreshSeconds.Enabled = autoRefresh.Checked && !foregroundBusy && !relaunching;
        if (preferences.AutoRefresh && !foregroundBusy && !relaunching && !updateInstalling && !closing) poll.Start();
    }

    private void SaveRefreshPreferences() {
        preferences.AutoRefresh = autoRefresh.Checked; preferences.RefreshSeconds = (int)refreshSeconds.Value;
        ConfigurePolling();
        try { settingsStore.Save(preferences); }
        catch (Exception e) { ShowMessage("本次刷新设置已生效，但无法保存到用户目录：" + e.Message, MessageBoxIcon.Warning); }
    }

    private void SetForeground(bool value) {
        foregroundBusy = value; UseWaitCursor = value && !relaunching;
        ConfigurePolling(); UpdateButtons();
    }

    private async Task DiscoverAsync() {
        if (foregroundBusy || relaunching || closing) return;
        requestGeneration++; SetForeground(true);
        string selected = String.IsNullOrWhiteSpace(initialRoot) ? directories.Text : initialRoot; initialRoot = "";
        Exception failure = null;
        try {
            var response = await service.InvokeAsync("discover", ""); if (closing) return;
            var candidates = GuiData.Items(GuiData.ValueOf(response, "Data"));
            directories.Items.Clear();
            foreach (var candidate in candidates) directories.Items.Add(GuiData.TextOf(GuiData.Map(candidate), "Root"));
            if (!String.IsNullOrWhiteSpace(selected)) directories.Text = selected;
            else if (directories.Items.Count > 0) directories.SelectedIndex = 0;
            selectionSource.Text = candidates.Length > 0 ? "找到 " + candidates.Length + " 个安装目录，可从列表切换或手动选择。" : "未自动找到安装目录，请点击“浏览…”选择。";
        } catch (Exception e) { failure = e; }
        finally { if (!closing) SetForeground(false); }
        if (failure != null) { ShowMessage(failure.Message, MessageBoxIcon.Error); return; }
        if (!closing && !String.IsNullOrWhiteSpace(directories.Text)) await RefreshStatusAsync(false);
    }

    private async Task BrowseAsync() {
        if (foregroundBusy || relaunching || closing) return;
        requestGeneration++; SetForeground(true); string selected = null;
        try {
            using (var dialog = new FolderBrowserDialog { Description = "选择包含 LucaNetSoftwareManager.exe 的目录", ShowNewFolderButton = false }) {
                if (Directory.Exists(directories.Text)) dialog.SelectedPath = directories.Text;
                if (dialog.ShowDialog(this) == DialogResult.OK) { selected = dialog.SelectedPath; directories.Text = selected; }
            }
        } finally { if (!closing) SetForeground(false); }
        if (selected != null && !closing) await RefreshStatusAsync(false);
    }

    // 自动刷新不显示进度、不切换光标、不禁用输入、不弹窗；只更新检查完成后的状态。
    // 请求代数使切换目录/提权后到达的旧结果失效，防止覆盖新的界面。
    internal async Task RefreshStatusAsync(bool automatic) {
        if (closing || relaunching || updateInstalling || foregroundBusy || String.IsNullOrWhiteSpace(directories.Text)) return;
        if (automatic && (refreshInProgress || !preferences.AutoRefresh || String.IsNullOrEmpty(lastCheckedRoot) || !SameRoot(directories.Text, lastCheckedRoot))) return;
        string directory = directories.Text.Trim();
        int generation = automatic ? requestGeneration : ++requestGeneration;
        string message = null; MessageBoxIcon messageIcon = MessageBoxIcon.Warning;
        if (automatic) refreshInProgress = true; else SetForeground(true);
        try {
            var response = await service.InvokeAsync("status", directory);
            if (closing || relaunching || generation != requestGeneration || !SameRoot(directory, directories.Text)) return;
            status = GuiData.Map(GuiData.ValueOf(response, "Data")); lastCheckedRoot = directory; RenderStatus(); UpdateButtons();
            if (!automatic) {
                string[] problems = GuiData.Items(GuiData.ValueOf(status, "Problems")).Select(Convert.ToString).ToArray();
                if (problems.Length > 0) message = String.Join(Environment.NewLine, problems);
            }
        } catch (Exception e) {
            if (!closing && !relaunching && generation == requestGeneration) {
                status = null; ResetCards(); UpdateButtons();
                if (!automatic) { message = e.Message; messageIcon = MessageBoxIcon.Error; }
            }
        } finally {
            if (automatic) refreshInProgress = false;
            else if (!closing && !relaunching) SetForeground(false);
        }
        // 先恢复光标/控件，再显示提示。用户阅读错误信息时界面不再残留忙碌状态。
        if (message != null && !closing && !relaunching && generation == requestGeneration)
            ShowMessage(message, messageIcon);
    }

    private async Task DeployAsync(bool uninstall) {
        if (foregroundBusy || closing || relaunching || updateInstalling) return;
        requestGeneration++; SetForeground(true);
        try {
            var before = await service.InvokeAsync("status", directories.Text.Trim()); if (closing) return;
            status = GuiData.Map(GuiData.ValueOf(before, "Data")); RenderStatus();
            if (!GuiData.BoolOf(status, "CanModify")) { ShowMessage("请先正常关闭 Lucanet.Software Manager 和客户端。", MessageBoxIcon.Information); return; }
            if (uninstall && MessageBox.Show(this, "卸载将停用客户端增强，已归档文件和恢复备份会保留。", "卸载", MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            deploymentInProgress = true;
            var response = await service.InvokeAsync(uninstall ? "uninstall" : "install", GuiData.TextOf(status, "Root"));
            var data = GuiData.Map(GuiData.ValueOf(response, "Data")); status = GuiData.Map(GuiData.ValueOf(data, "Status")); RenderStatus();
            ShowMessage(uninstall ? "卸载完成。下次启动将不再加载增强。" : "安装完成，请直接打开 Lucanet.Software Manager。", MessageBoxIcon.Information);
        } catch (Exception e) { ShowMessage(e.Message, MessageBoxIcon.Error); }
        finally { deploymentInProgress = false; if (!closing) SetForeground(false); }
    }

    private void RenderStatus() {
        string state = GuiData.TextOf(status, "State"); bool installed = state == "installed";
        installationValue.Text = installed ? "已安装" : state == "partial" ? "需要修复" : "未安装";
        installationValue.ForeColor = installed ? Green : state == "partial" ? Amber : Muted;
        installationDetail.Text = GuiData.TextOf(status, "EnabledCount") + " / " + GuiData.TextOf(status, "ConfigCount") + " 项启动配置"
            + (installed ? (GuiData.BoolOf(status, "Current") ? " · 当前版本" : " · 可更新") : "");
        var processes = GuiData.Items(GuiData.ValueOf(status, "Processes"));
        runningValue.Text = GuiData.BoolOf(status, "ManagerRunning") ? "Manager 运行中" : processes.Length > 0 ? "客户端 / 待确认" : "未运行";
        runningValue.ForeColor = processes.Length > 0 ? Amber : Green;
        runningDetail.Text = processes.Length > 0 ? "检测到 " + processes.Length + " 个进程，请先正常关闭" : "可以安装或卸载";
        string version = GuiData.TextOf(status, "JdkVersion");
        jdkValue.Text = String.IsNullOrEmpty(version) ? "未找到 JDK" : version.Replace("-LTS", "");
        jdkValue.ForeColor = String.IsNullOrEmpty(GuiData.TextOf(status, "JdkError")) ? Ink : Amber;
        tips.SetToolTip(jdkValue, GuiData.TextOf(status, "JdkHome"));
    }

    private void UpdateButtons() {
        if (directories == null || installButton == null) return;
        bool available = !foregroundBusy && !relaunching && !updateInstalling && !closing;
        directories.Enabled = available; browseButton.Enabled = available; discoverButton.Enabled = available; checkButton.Enabled = available;
        autoRefresh.Enabled = available; refreshSeconds.Enabled = available && autoRefresh.Checked;
        bool canModify = status != null && GuiData.BoolOf(status, "CanModify");
        installButton.Enabled = available && canModify && GuiData.BoolOf(status, "SourceAvailable") && String.IsNullOrEmpty(GuiData.TextOf(status, "JdkError"));
        uninstallButton.Enabled = available && canModify && GuiData.TextOf(status, "State") != "not-installed";
        startButton.Enabled = available && status != null && !GuiData.BoolOf(status, "ManagerRunning");
        elevateButton.Enabled = available && !IsAdmin();
        UpdateUpdateButtons();
    }

    private void ResetCards() {
        foreach (var label in new[] {installationValue, runningValue, jdkValue}) { label.Text = "待检查"; label.ForeColor = Muted; }
        installationDetail.Text = ""; runningDetail.Text = ""; tips.SetToolTip(jdkValue, "");
    }

    private async Task OpenManagerAsync() {
        try {
            string root = GuiData.TextOf(status, "Root");
            Process.Start(new ProcessStartInfo(Path.Combine(root, "LucaNetSoftwareManager.exe")) { UseShellExecute = true, WorkingDirectory = root });
            await RefreshStatusAsync(false);
        } catch (Exception e) { ShowMessage(e.Message, MessageBoxIcon.Error); }
    }

    internal async Task RelaunchElevatedAsync() {
        if (foregroundBusy || relaunching || closing || updateDownloading || updateInstalling) return;
        relaunching = true; requestGeneration++; poll.Stop(); UpdateButtons();
        try {
            bool ready = await elevatedLauncher(directories.Text.Trim());
            if (closing) return;
            if (!ready) { ShowMessage("管理员窗口未能正常打开，当前窗口仍保留，请重试。", MessageBoxIcon.Warning); return; }
            // 不再经过“busy 禁止关闭”的旧逻辑，迟到的只读刷新结果也会被忽略。
            allowRelaunchClose = true; Close();
        } catch (Win32Exception e) {
            if (e.NativeErrorCode != 1223) ShowMessage(e.Message, MessageBoxIcon.Error);
            // 用户取消 UAC：原窗口保留，finally 恢复定时器。
        } catch (Exception e) { ShowMessage(e.Message, MessageBoxIcon.Error); }
        finally {
            if (!closing && !IsDisposed) { relaunching = false; ConfigurePolling(); UpdateButtons(); }
        }
    }

    private void ShowMessage(string message, MessageBoxIcon icon) {
        if (closing || IsDisposed) return;
        if (notify != null) notify(message, icon);
        else MessageBox.Show(this, message, Text, MessageBoxButtons.OK, icon);
    }
    private static bool SameRoot(string a, string b) { return String.Equals(a.Trim().TrimEnd('\\'), b.Trim().TrimEnd('\\'), StringComparison.OrdinalIgnoreCase); }
    private static bool IsAdmin() { using (var identity = WindowsIdentity.GetCurrent()) return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator); }
}
