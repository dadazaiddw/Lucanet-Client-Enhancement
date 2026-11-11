using System;
using System.Collections;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

internal interface IEnhancementService {
    Task<Dictionary<string, object>> InvokeAsync(string action, string directory);
}

// 后台服务始终隐藏控制台；只读刷新与部署复用已验证的 PowerShell 实现。
internal sealed class PowerShellEnhancementService : IEnhancementService {
    private readonly string packageRoot;
    private readonly int readOnlyTimeoutMilliseconds;
    internal PowerShellEnhancementService(string packageRoot) : this(packageRoot, 15000) { }
    internal PowerShellEnhancementService(string packageRoot, int readOnlyTimeoutMilliseconds) {
        if (readOnlyTimeoutMilliseconds <= 0) throw new ArgumentOutOfRangeException("readOnlyTimeoutMilliseconds");
        this.packageRoot = packageRoot; this.readOnlyTimeoutMilliseconds = readOnlyTimeoutMilliseconds;
    }
    public Task<Dictionary<string, object>> InvokeAsync(string action, string directory) {
        string powershell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe");
        string script = Path.Combine(packageRoot, @"tools\gui-service.ps1");
        if (!File.Exists(script)) throw new IOException("交付文件不完整，请完整解压 ZIP 后再打开 enhancement.exe。");
        var start = new ProcessStartInfo(powershell) {
            UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8, WorkingDirectory = packageRoot
        };
        start.Arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File " + GuiData.Quote(script)
            + " -Action " + action + (String.IsNullOrWhiteSpace(directory) ? "" : " -LucanetHome " + GuiData.Quote(directory));
        return Task.Run(async delegate {
            using (var process = Process.Start(start)) {
                if (process == null) throw new IOException("无法启动状态检查程序。");
                var elapsed = Stopwatch.StartNew();
                Task<string> outputTask = process.StandardOutput.ReadToEndAsync();
                Task<string> errorTask = process.StandardError.ReadToEndAsync();
                Task drained = Task.WhenAll(outputTask, errorTask);
                // 只读发现/检查有总等待上限，含进程退出后的管道读取；安装、卸载及热更新
                // 仍等待事务完成，绝不能为了恢复 UI 而强杀正在写配置的进程。
                if (action == "discover" || action == "status") {
                    bool exited = process.WaitForExit(readOnlyTimeoutMilliseconds);
                    int remaining = Math.Max(1, readOnlyTimeoutMilliseconds - (int)elapsed.ElapsedMilliseconds);
                    bool complete = exited;
                    if (exited && !drained.IsCompleted) {
                        using (var deadline = new CancellationTokenSource()) {
                            complete = await Task.WhenAny(drained, Task.Delay(remaining, deadline.Token)) == drained;
                            deadline.Cancel();
                        }
                    }
                    if (!complete) {
                        StopReadOnlyHelper(process, drained);
                        throw new TimeoutException(action == "discover"
                            ? "自动识别超时，请重试或点击“浏览…”手动选择安装目录。"
                            : "状态检查超时，请点击“检查状态”重试或重新选择安装目录。");
                    }
                } else { process.WaitForExit(); }
                await drained;
                string output = await outputTask;
                string error = await errorTask;
                Dictionary<string, object> result;
                try { result = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(output.Trim().TrimStart('\uFEFF')); }
                catch (ArgumentException) { throw new InvalidOperationException("检查程序未返回有效结果，请确认交付文件完整。\r\n" + error); }
                if (!GuiData.BoolOf(result, "Ok")) throw new InvalidOperationException(GuiData.TextOf(result, "Message"));
                if (process.ExitCode != 0) throw new InvalidOperationException("操作未成功，请检查安装目录的访问权限。");
                return result;
            }
        });
    }

    private static void StopReadOnlyHelper(Process process, Task drained) {
        // 仅停止本次创建的只读 PowerShell，绝不结束 Lucanet/Manager 或其他用户进程。
        try { if (!process.HasExited) process.Kill(); }
        catch (InvalidOperationException) { } catch (Win32Exception) { }
        try { process.StandardOutput.Close(); } catch (IOException) { }
        try { process.StandardError.Close(); } catch (IOException) { }
        // 关闭管道可能使未完成的异步读取报错，观察异常，避免后台遗留未处理任务。
        drained.ContinueWith(t => { GC.KeepAlive(t.Exception); }, TaskContinuationOptions.OnlyOnFaulted);
    }
}

internal static class ElevatedRelaunch {
    internal static Task<bool> StartAsync(string directory) {
        // 必须先停掉旧窗口的定时刷新；只有新进程已经显示主窗口时才交接。
        return Task.Run(delegate {
            using (var process = Process.Start(new ProcessStartInfo(Application.ExecutablePath) {
                UseShellExecute = true, Verb = "runas",
                Arguments = String.IsNullOrWhiteSpace(directory) ? "" : "--directory " + GuiData.Quote(directory)
            })) {
                if (process == null) return false;
                var timeout = Stopwatch.StartNew();
                while (timeout.ElapsedMilliseconds < 15000) {
                    if (process.HasExited) return false;
                    process.Refresh();
                    if (process.MainWindowHandle != IntPtr.Zero) return true;
                    Thread.Sleep(100);
                }
                return false;
            }
        });
    }
}

internal static class GuiData {
    internal static string Quote(string value) {
        if (value.IndexOfAny(new[] {'"', '\r', '\n'}) >= 0) throw new ArgumentException("路径不能包含引号或换行。");
        return "\"" + value.TrimEnd('\\') + "\"";
    }
    internal static Dictionary<string, object> Map(object value) { return value as Dictionary<string, object> ?? new Dictionary<string, object>(); }
    internal static object[] Items(object value) {
        if (value == null) return new object[0];
        var array = value as object[]; if (array != null) return array;
        var list = value as ArrayList; return list != null ? list.ToArray() : new[] {value};
    }
    internal static string TextOf(Dictionary<string, object> map, string key) { return map != null && map.ContainsKey(key) && map[key] != null ? Convert.ToString(map[key]) : ""; }
    internal static bool BoolOf(Dictionary<string, object> map, string key) { return map != null && map.ContainsKey(key) && map[key] is bool && (bool)map[key]; }
    internal static object ValueOf(Dictionary<string, object> map, string key) { return map != null && map.ContainsKey(key) ? map[key] : null; }
}
