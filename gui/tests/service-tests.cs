using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

// 实际启动隐藏 PowerShell 来覆盖等待/管道/超时，避免只靠可立即返回的 service 替身。
internal static class ServiceTests {
    internal static async Task Verify(string work) {
        string package = Path.Combine(work, "service fixture 中文 空格");
        string tools = Path.Combine(package, "tools"); Directory.CreateDirectory(tools);
        string mode = Path.Combine(tools, "mode.txt"), pid = Path.Combine(tools, "helper.pid");
        string script = Path.Combine(tools, "gui-service.ps1");
        File.WriteAllText(script, @"
param([string]$Action,[string]$LucanetHome)
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'helper.pid'),$PID.ToString())
$mode=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'mode.txt'))
if($mode -eq 'hang'){Start-Sleep -Seconds 30}
if($mode -eq 'write'){Start-Sleep -Seconds 2}
if($Action -eq 'discover'){$data=@(@{Root='D:\Fixture'})}
else{$data=@{Root=$LucanetHome;State='installed';Current=$true;EnabledCount=2;ConfigCount=2;CanModify=$true;SourceAvailable=$true;JdkVersion='21.0.11+10';JdkError='';Problems=@();Processes=@()}}
[Console]::WriteLine((@{Ok=$true;Data=$data}|ConvertTo-Json -Depth 4 -Compress))
", new System.Text.UTF8Encoding(true));
        var service = new PowerShellEnhancementService(package, 2500);
        File.WriteAllText(mode, "hang");
        var watch = Stopwatch.StartNew();
        try { await service.InvokeAsync("discover", ""); throw new Exception("hung discovery succeeded"); }
        catch (TimeoutException e) { Check(e.Message.Contains("自动识别超时"), "discovery timeout actionable"); }
        Check(watch.ElapsedMilliseconds < 6500, "discovery wait bounded");
        await CheckHelperExited(pid);

        var store = new GuiSettingsStore(Path.Combine(package, "ui.json"));
        store.Save(new GuiPreferences { AutoRefresh = false, RefreshSeconds = 8 });
        EnhancementForm form = null; int alerts = 0;
        try {
            form = new EnhancementForm(new string[0], service, store, d => Task.FromResult(false), (message, icon) => {
                alerts++;
                Check(message.Contains("状态检查超时"), "manual timeout visible");
                Check(!form.UseWaitCursor && Find(form, "installationDirectory").Enabled,
                    "cursor and controls restored before timeout notification");
                Check(!Find(form, "install").Enabled, "unknown status cannot enable installation");
            }, false);
            var handle = form.Handle;
            Find(form, "installationDirectory").Text = @"D:\Fixture";
            var request = form.RefreshStatusAsync(false);
            Check(form.UseWaitCursor, "foreground request initially busy");
            await request; Check(alerts == 1 && !form.UseWaitCursor, "manual timeout completes and releases UI");
            await CheckHelperExited(pid);

            File.WriteAllText(mode, "ok");
            await form.RefreshStatusAsync(false);
            Check(Find(form, "install").Enabled, "retry recovers after timeout");
            ((CheckBox)Find(form, "autoRefresh")).Checked = true;
            File.WriteAllText(mode, "hang");
            request = form.RefreshStatusAsync(true);
            Check(!form.UseWaitCursor && Find(form, "installationDirectory").Enabled, "background wait keeps UI usable");
            await request; Check(alerts == 1 && !form.UseWaitCursor, "automatic timeout stays silent");
            await CheckHelperExited(pid);
            File.WriteAllText(mode, "ok");
            await form.RefreshStatusAsync(true);
            Check(Find(form, "install").Enabled, "automatic refresh recovers on next successful read");
            form.Close();
        } finally { if (form != null) form.Dispose(); }

        // 写操作不套用只读截止时间，不中断事务。测试脚本仅 sleep/JSON，不写安装目录。
        File.WriteAllText(mode, "write");
        var shortReadDeadline = new PowerShellEnhancementService(package, 100);
        var written = await shortReadDeadline.InvokeAsync("install", @"D:\Fixture");
        Check(GuiData.BoolOf(written,"Ok"), "deployment not terminated by read-only timeout");
        Console.WriteLine("PASS: real helper discovery/status timeout, helper cleanup, UI/cursor recovery, silent background retry and uninterrupted write transaction");
    }
    private static async Task CheckHelperExited(string path) {
        Check(File.Exists(path), "fixture helper started");
        int id = Int32.Parse(File.ReadAllText(path));
        for (int i = 0; i < 20; i++) {
            try { using (var process = Process.GetProcessById(id)) { if (process.HasExited) return; } }
            catch (ArgumentException) { return; }
            await Task.Delay(50);
        }
        throw new Exception("timed-out read-only helper was left running");
    }
    private static Control Find(Control parent, string name) { return parent.Controls.Find(name, true).Single(); }
    private static void Check(bool value,string description) { if (!value) throw new Exception(description); }
}
