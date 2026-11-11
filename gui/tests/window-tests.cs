using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

// 只实例化自身窗体，不操作桌面/UAC/真实安装目录。
internal static class WindowTests {
    [STAThread]
    private static int Main(string[] args) {
        Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
        SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
        try {
            if (args.Length > 1 && args[1] == "--native-dpi") {
                var native = VerifyNativeDpi(args[0]);
                while (!native.IsCompleted) { Application.DoEvents(); Thread.Sleep(5); }
                native.GetAwaiter().GetResult();
                ToggleTests.Verify(Path.Combine(args[0],"toggles"));
                Console.WriteLine("PASS: real system DPI manifest, first-hidden settings pages, text/icon bounds and resize recovery");
                return 0;
            }
            var task = Verify(args[0]);
            while (!task.IsCompleted) { Application.DoEvents(); Thread.Sleep(5); }
            task.GetAwaiter().GetResult();
            Console.WriteLine("PASS: compact layout, refresh persistence, quiet/background recovery, stale-result guard, successful relaunch closure and canceled/failed relaunch recovery");
            Console.WriteLine("PASS: workspace/application/features navigation and settings layout at simulated 100/125/150/175/200 percent scaling");
            return 0;
        } catch (Exception e) { Console.Error.WriteLine(e); return 1; }
    }

    private static async Task Verify(string work) {
        Directory.CreateDirectory(work);
        var path = Path.Combine(work, "ui.json"); var store = new GuiSettingsStore(path);
        Check(store.Load().RefreshSeconds == 8, "default interval");
        File.WriteAllText(path, "not json"); Check(store.Load().RefreshSeconds == 8, "corrupt settings fallback");
        File.WriteAllText(path, "{\"AutoRefresh\":true,\"RefreshSeconds\":0}"); Check(store.Load().RefreshSeconds == 8, "out of range fallback");
        store.Save(new GuiPreferences { AutoRefresh = true, RefreshSeconds = 8 });
        var service = new StubService(); int alerts = 0;
        var elevated = new TaskCompletionSource<bool>();
        using (var form = new EnhancementForm(new string[0], service, store, d => elevated.Task, (m, i) => alerts++, false)) {
            var handle = form.Handle;
            var controls = All(form).ToArray();
            Check(form.Text == "Lucanet Client Enhancement", "title");
            Check(!controls.Any(c => c is ProgressBar || (c is TextBox && ((TextBox)c).Multiline)), "no progress or report area");
            Check(!controls.Any(c => c.Text.Contains("实际加载证据") || c.Text.Contains("仅使用所选")), "removed labels");
            Check(controls.Any(c => c.Text == "Lucanet.Software Manager 安装目录"), "directory label");
            Check(controls.Any(c => c.Text == "安装状态") && controls.Any(c => c.Text == "JDK"), "card names");
            var directory = (ComboBox)controls.Single(c => c.Name == "installationDirectory");
            var interval = (NumericUpDown)controls.Single(c => c.Name == "refreshSeconds");
            var automatic = (CheckBox)controls.Single(c => c.Name == "autoRefresh");
            var links = (CheckBox)controls.Single(c => c.Name == "linksInNewWindow");
            Check(links.Checked, "links default enabled");
            links.Checked = false; Check(!store.Features.LoadLinksInNewWindow(), "new-window preference saved immediately");
            links.Checked = true; Check(store.Features.LoadLinksInNewWindow(), "new-window preference re-enabled immediately");
            using (var icon = ClientIcon.Load()) Check(icon.Width == 32, "embedded native client icon");
            var install = (Button)controls.Single(c => c.Name == "install");
            var workspace = controls.Single(c => c.Name == "workspacePage");
            Check(!All(workspace).Any(c => c.Name == "autoRefresh" || c.Name == "refreshSeconds" || c.Name == "refreshToolbar"), "workspace has no settings toolbar");
            form.Navigate("features");
            Check(All(controls.Single(c => c.Name == "featureSettings")).Contains(interval), "refresh moved to features");
            form.Navigate("application");
            Check(controls.Any(c => c.Name == "appVersion" && c.Text == "v"+AppInfo.Version), "application metadata");
            Check(!controls.Any(c => c.Text.Contains("赞助") || c.Text.Contains("反馈问题")), "no unsupported settings links");
            form.Navigate("workspace");
            directory.Text = @"D:\Test\Lucanet";
            await form.RefreshStatusAsync(false);
            Check(install.Enabled, "ready to install");
            // 在屏幕可见区域之外渲染自身控件；不操作其他窗口或触发 UAC。
            form.StartPosition = FormStartPosition.Manual; form.Location = new Point(-20000, -20000);
            form.ShowInTaskbar = false; form.Show(); Application.DoEvents(); form.PerformLayout();
            using (var image = new Bitmap(form.Width, form.Height)) {
                form.DrawToBitmap(image, new Rectangle(Point.Empty, image.Size));
                image.Save(Path.Combine(work, "layout-100.png"));
            }
            form.Hide();
            interval.Value = 15; Check(store.Load().RefreshSeconds == 15, "interval persisted");
            automatic.Checked = false; Check(!store.Load().AutoRefresh && !interval.Enabled, "pause persisted");
            int calls = service.Calls; await form.RefreshStatusAsync(true); Check(service.Calls == calls, "disabled timer does not refresh");
            automatic.Checked = true;

            var delayed = new TaskCompletionSource<Dictionary<string, object>>(); service.Next = delayed.Task;
            var refresh = form.RefreshStatusAsync(true);
            Check(directory.Enabled && install.Enabled && !form.UseWaitCursor, "background refresh does not disable or flash UI");
            await form.RefreshStatusAsync(true); Check(service.Calls == calls + 1, "no overlapping timer requests");
            directory.Text = @"E:\Other\Lucanet";
            delayed.SetResult(service.Response(@"D:\Test\Lucanet")); await refresh;
            Check(All(form).Single(c => c.Name == "installationState").Text == "待检查", "stale directory result ignored");
            await form.RefreshStatusAsync(false);

            service.Next = Task.FromException<Dictionary<string, object>>(new IOException("transient read failure"));
            await form.RefreshStatusAsync(true); Check(alerts == 0 && !install.Enabled, "background error silent and disables unsafe actions");
            await form.RefreshStatusAsync(true); Check(install.Enabled, "later refresh recovers after transient failure");

            var pendingRefresh = new TaskCompletionSource<Dictionary<string, object>>(); service.Next = pendingRefresh.Task;
            var concurrent = form.RefreshStatusAsync(true);
            var relaunch = form.RelaunchElevatedAsync();
            Check(!form.IsDisposed && !directory.Enabled, "keep old window while elevated child starts");
            elevated.SetResult(true); await relaunch;
            Check(form.IsDisposed, "successful elevated child closes old window despite pending refresh");
            pendingRefresh.SetResult(service.Response(@"E:\Other\Lucanet")); await concurrent;
        }

        using (var form = new EnhancementForm(new string[0], new StubService(), store,
            d => Task.FromException<bool>(new Win32Exception(1223)), (m, i) => alerts++, false)) {
            var handle = form.Handle;
            await form.RelaunchElevatedAsync();
            Check(!form.IsDisposed && All(form).Single(c => c.Name == "installationDirectory").Enabled, "UAC cancellation keeps old window usable");
            Check(alerts == 0, "UAC cancellation is not an error popup");
            form.Close();
        }
        using (var form = new EnhancementForm(new string[0], new StubService(), store,
            d => Task.FromResult(false), (m, i) => alerts++, false)) {
            var handle = form.Handle; await form.RelaunchElevatedAsync();
            Check(!form.IsDisposed && alerts == 1, "failed elevated launch keeps window and reports failure"); form.Close();
        }
        await VerifyRefreshLayout(work);
        await UpdateTests.Verify(work);
        await VerifyUpdatePage(work);
        await ServiceTests.Verify(work);
        ToggleTests.Verify(Path.Combine(work,"toggles"));
    }

    private static async Task VerifyRefreshLayout(string work) {
        // 测试进程保持 96 DPI，以控件尺寸和字体同步缩放模拟 Windows 100%～200%。
        // 不是缩放一张位图：每个比例重新创建真实 WinForms 控件并重新执行布局。
        foreach (float scale in new[] {1F, 1.25F, 1.5F, 1.75F, 2F}) {
            string tag = ((int)(scale * 100)).ToString();
            var store = new GuiSettingsStore(Path.Combine(work, "layout-" + tag + ".json"));
            store.Save(new GuiPreferences { AutoRefresh = false, RefreshSeconds = 8 });
            using (var form = new EnhancementForm(new string[0], new StubService(), store, d => Task.FromResult(false), (m, i) => { }, false)) {
                var handle = form.Handle;
                var directory = (ComboBox)All(form).Single(c => c.Name == "installationDirectory");
                directory.Text = @"D:\Soft\LucaNet"; await form.RefreshStatusAsync(false);
                form.SuspendLayout(); form.AutoScaleMode = AutoScaleMode.None;
                form.Scale(new SizeF(scale, scale));
                form.Font = new Font(form.Font.FontFamily, 9F * scale);
                form.StartPosition = FormStartPosition.Manual; form.Location = new Point(-20000, -20000); form.ShowInTaskbar = false;
                form.ResumeLayout(true); form.Show(); Application.DoEvents();
                foreach(string page in new[]{"workspace","application","features"}) {
                    form.Navigate(page);form.PerformLayout();Application.DoEvents();
                    Check(form.Controls.Count==1,"one navigation shell");
                    Check(directory.Text==@"D:\Soft\LucaNet","navigation preserves selected installation");
                    Check(All(form).Single(c=>c.Name=="workspacePage").Visible==(page=="workspace"),"workspace visibility");
                    Check(All(form).Single(c=>c.Name=="applicationSettings").Visible==(page=="application"),"application visibility");
                    Check(All(form).Single(c=>c.Name=="featureSettings").Visible==(page=="features"),"features visibility");
                    if(page=="features")CheckRefreshBounds(form, scale, tag + " enabled");
                    if(page!="workspace")CheckSettingsContent(form,page,tag);
                    using (var image = new Bitmap(form.Width, form.Height)) {
                        form.DrawToBitmap(image, new Rectangle(Point.Empty, image.Size));
                        image.Save(Path.Combine(work,page+"-"+tag+".png"));
                    }
                }
                var checkbox = (CheckBox)All(form).Single(c => c.Name == "autoRefresh");
                var seconds = (NumericUpDown)All(form).Single(c => c.Name == "refreshSeconds");
                checkbox.Checked = true; seconds.Value = 300; Application.DoEvents();
                CheckRefreshBounds(form, scale, tag + " maximum interval");
                using (var image = new Bitmap(form.Width, form.Height)) {
                    form.DrawToBitmap(image, new Rectangle(Point.Empty, image.Size));
                    image.Save(Path.Combine(work, "layout-" + tag + ".png"));
                }
                form.Width = Math.Max(form.MinimumSize.Width, form.Width - (int)(40 * scale));
                form.PerformLayout(); Application.DoEvents();
                CheckRefreshBounds(form, scale, tag + " narrow window");
                checkbox.Checked = false; Application.DoEvents();
                CheckRefreshBounds(form, scale, tag + " disabled");
                form.Close();
            }
        }
    }

    private static void CheckRefreshBounds(Form form, float scale, string scenario) {
        var toolbar = (RefreshIntervalControl)All(form).Single(c => c.Name == "refreshInterval");
        var seconds = (NumericUpDown)All(toolbar).Single(c => c.Name == "refreshSeconds");
        var unit = All(toolbar).Single(c => c.Name == "refreshUnit");
        var content = Rectangle.FromLTRB(toolbar.Padding.Left, toolbar.Padding.Top,
            toolbar.ClientSize.Width - toolbar.Padding.Right, toolbar.ClientSize.Height - toolbar.Padding.Bottom);
        foreach (Control c in new Control[] {seconds, unit}) {
            Check(c.Parent == toolbar, scenario + ": one explicit row");
            Check(content.Contains(c.Bounds), scenario + ": control fully inside toolbar: " + c.Name + " " + c.Bounds + " in " + content);
            Control ancestor = c.Parent;
            Rectangle visibleBounds = c.Bounds;
            while (ancestor != null) {
                Check(ancestor.ClientRectangle.Contains(visibleBounds), scenario + ": visible through " + ancestor.GetType().Name + ": " + c.Name + " " + visibleBounds + " in " + ancestor.ClientRectangle);
                visibleBounds.Offset(ancestor.Left, ancestor.Top);
                ancestor = ancestor.Parent;
            }
            Check(Math.Abs(c.Top + c.Height / 2.0 - (seconds.Top + seconds.Height / 2.0)) <= 1,
                scenario + ": controls share vertical center");
        }
        Check(seconds.Height >= seconds.PreferredHeight, scenario + ": complete numeric input height");
        Check(unit.Left >= seconds.Right, scenario + ": no horizontal overlap");
        Check(unit.Left - seconds.Right <= 4 * scale + 2,
            scenario + ": label, numeric input and unit stay together");
        Check(seconds.Width <= 80 * scale, scenario + ": numeric width cannot grow through layout feedback");
        Check(content.Right - unit.Right <= 2, scenario + ": group stays aligned to right padding");
    }

    // 使用和 enhancement.exe 相同的 manifest 启动独立测试进程，读取真实系统 DPI。
    // 重点覆盖先创建隐藏设置页、显示工作区、之后才切换设置的路径。
    private static async Task VerifyNativeDpi(string work) {
        Directory.CreateDirectory(work);
        var store = new GuiSettingsStore(Path.Combine(work, "native-ui.json"));
        store.Save(new GuiPreferences { AutoRefresh = false, RefreshSeconds = 8 });
        var updater = new StubUpdates();
        using (var form = new EnhancementForm(new string[0], new StubService(), store, d => Task.FromResult(false), (m,i) => {}, false, updater)) {
            form.StartPosition = FormStartPosition.Manual; form.Location = new Point(-20000,-20000); form.ShowInTaskbar = false;
            form.Show(); Application.DoEvents();
            float dpi; using (var g = form.CreateGraphics()) { dpi = g.DpiX; }
            Console.WriteLine("Native DPI: " + dpi + "; client: " + form.ClientSize);
            foreach (string page in new[] {"application","features","workspace","features","application"}) {
                form.Navigate(page); form.PerformLayout(); Application.DoEvents();
                if (page == "workspace") continue;
                CheckSettingsContent(form,page,"native " + dpi);
                using(var image=new Bitmap(form.Width,form.Height)) {
                    form.DrawToBitmap(image,new Rectangle(Point.Empty,image.Size));
                    image.Save(Path.Combine(work,"native-"+page+"-"+dpi+".png"));
                }
            }
            form.Size = form.MinimumSize; Application.DoEvents();
            foreach(string page in new[]{"features","application"}) {
                form.Navigate(page);form.PerformLayout();Application.DoEvents();
                CheckSettingsContent(form,page,"native minimum " + dpi);
            }
            updater.Result = new ReleaseUpdate { State = UpdateState.Available, Version = "1.1.0",
                PageUrl = AppInfo.Releases, AssetUrl = AppInfo.Repository + "/releases/download/v1.1.0/lucanet-client-enhancement.zip", Sha256 = new string('a',64) };
            await form.CheckForUpdatesAsync(true); form.PerformLayout(); Application.DoEvents();
            CheckSettingsContent(form,"application","native visible download action");
            Check(All(form).Single(c => c.Name == "downloadUpdate").Visible, "native download action visible for new release");
            var updateStatus = (Label)All(form).Single(c => c.Name == "updateStatus");
            updateStatus.Text = "无法连接更新服务。请检查网络后重试，或通过 GitHub 发布页面查看版本与更新内容。";
            form.PerformLayout(); Application.DoEvents();
            CheckSettingsContent(form,"application","native wrapped update message");
            var panel=(Panel)All(form).Single(c=>c.Name=="applicationSettings");
            var projectLink=All(panel).Single(c=>c.Name=="releaseLink");
            panel.ScrollControlIntoView(projectLink);Application.DoEvents();
            var location=panel.PointToClient(projectLink.PointToScreen(Point.Empty));
            Check(panel.ClientRectangle.Contains(new Rectangle(location,projectLink.Size)),"project link is reachable by scrolling");
            form.Close();
        }
    }

    private static void CheckSettingsContent(Form form, string page, string scenario) {
        var panel=(Panel)All(form).Single(c=>c.Name==(page=="application"?"applicationSettings":"featureSettings"));
        var content=panel.Controls.Cast<Control>().Single();
        // 允许内容超出滚动视口，但不能被中间卡片/单行父容器剪掉。
        foreach (Control c in All(panel).Where(c=>c.Visible && (c is Label || c is Button || c is AppMark || c is SettingToggle || c is NumericUpDown))) {
            if(c is Label && String.IsNullOrEmpty(c.Text))continue;
            Rectangle bounds=c.Bounds;Control parent=c.Parent;
            while(parent!=null && parent!=panel) {
                Check(parent.ClientRectangle.Contains(bounds),scenario+": clipped "+c.GetType().Name+" "+c.Name+" "+c.Text+" in "+parent.Name+" "+bounds+" / "+parent.ClientRectangle);
                bounds.Offset(parent.Left,parent.Top);parent=parent.Parent;
            }
            var label=c as Label;
            if(label!=null) {
                Size preferred=label.GetPreferredSize(new Size(label.Width,0));
                Check(label.Height>=preferred.Height,scenario+": insufficient text height "+label.Text+" "+label.Size+" expected "+preferred);
            }
            if(c is AppMark)Check(Math.Abs(c.Width-c.Height)<=1,scenario+": icon square aspect");
            if(c is NumericUpDown)Check(c.Height>=c.PreferredSize.Height,scenario+": numeric edit height");
        }
        var blocks=content.Controls.Cast<Control>().Where(c=>c.Visible).OrderBy(c=>c.Top).ToArray();
        for(int i=1;i<blocks.Length;i++)Check(blocks[i].Top>=blocks[i-1].Bottom,scenario+": overlapping settings cards");
        if(page=="application") {
            var version=All(panel).Single(c=>c.Name=="appVersion");Check(version.Text=="v1.0.1",scenario+": application version fixed at 1.0.1");
            var icon=All(panel).Single(c=>c.Name=="applicationIcon");
            var identity=All(panel).Single(c=>c.Name=="applicationIdentity");
            Check(icon.Bottom<=identity.Height,scenario+": icon fully contained in identity card");
        }
    }
    private static async Task VerifyUpdatePage(string work) {
        var updater=new StubUpdates();var store=new GuiSettingsStore(Path.Combine(work,"app-settings.json"));
        // 兼容上一版本只包含刷新设置的文件。
        File.WriteAllText(Path.Combine(work,"app-settings.json"),"{\"AutoRefresh\":false,\"RefreshSeconds\":25}");
        Check(!store.Load().AutoCheckUpdates && store.Load().RefreshSeconds==25,"existing settings migrate without enabling network");
        using(var form=new EnhancementForm(new string[0],new StubService(),store,d=>Task.FromResult(false),(m,i)=>{},false,updater)) {
            var handle=form.Handle;form.Navigate("application");
            var updateStatus=All(form).Single(c=>c.Name=="updateStatus");
            foreach(var state in new[]{UpdateState.Available,UpdateState.Current,UpdateState.Ahead,UpdateState.NoRelease}) {
                updater.Result=new ReleaseUpdate { State=state, Version="2.1.0",PageUrl=AppInfo.Releases };
                await form.CheckForUpdatesAsync(true);Check(!String.IsNullOrEmpty(store.Load().LastUpdateCheck),"last check persisted");
                Check(updateStatus.Text.Contains(state==UpdateState.Available?"新版本":state==UpdateState.Current?"最新":state==UpdateState.Ahead?"高于":"尚无"),"update state visible: "+state);
            }
            updater.Failure=true;await form.CheckForUpdatesAsync(true);Check(updateStatus.Text.Contains("test-network"),"network error displayed and retry possible");
            var automatic=(CheckBox)All(form).Single(c=>c.Name=="autoCheckUpdates");automatic.Checked=true;
            Check(store.Load().AutoCheckUpdates,"auto update preference persisted");form.Close();
        }
    }
    private sealed class StubUpdates : IUpdateService {
        internal ReleaseUpdate Result;internal bool Failure;
        public Task<ReleaseUpdate> CheckAsync(){return Failure?Task.FromException<ReleaseUpdate>(new IOException("test-network")):Task.FromResult(Result);}
        public Task<string> DownloadAsync(ReleaseUpdate release,IProgress<int> progress){throw new NotSupportedException();}
    }
    private static IEnumerable<Control> All(Control parent) {
        foreach (Control c in parent.Controls) { yield return c; foreach (var child in All(c)) yield return child; }
    }
    private static void Check(bool result, string message) { if (!result) throw new Exception(message); }
    private sealed class StubService : IEnhancementService {
        internal Task<Dictionary<string, object>> Next; internal int Calls;
        public Task<Dictionary<string, object>> InvokeAsync(string action, string directory) {
            Calls++; var result = Next; Next = null; return result ?? Task.FromResult(Response(directory));
        }
        internal Dictionary<string, object> Response(string directory) {
            return new Dictionary<string, object> { {"Ok", true}, {"Data", new Dictionary<string, object> {
                {"Root", directory}, {"State", "installed"}, {"Current", true}, {"EnabledCount", 4}, {"ConfigCount", 4},
                {"ManagerRunning", false}, {"Processes", new object[0]}, {"JdkVersion", "21.0.11+10-LTS"}, {"JdkError", ""}, {"JdkHome", directory+@"\.jdk\LucaNetJDK-21.0.11+10"},
                {"Problems", new object[0]}, {"CanModify", true}, {"SourceAvailable", true}
            } } };
        }
    }
}
