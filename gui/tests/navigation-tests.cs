using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

// 独立设置文件和空后台；计时包含布局及待处理的绘制消息，不只测 Visible 赋值。
internal static class NavigationTests {
    [STAThread]
    private static int Main(string[] args) {
        Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
        try { Verify(args[0], args.Contains("--baseline")); return 0; }
        catch(Exception e) { Console.Error.WriteLine(e); return 1; }
    }
    internal static void Verify(string work, bool baseline = false) {
        Directory.CreateDirectory(work);
        var path=Path.Combine(work,"ui.json"); var store=new GuiSettingsStore(path);
        store.Save(new GuiPreferences { AutoRefresh=false, RefreshSeconds=25, AutoCheckUpdates=true });
        store.Features.SaveLinksInNewWindow(false);
        store.Features.SaveScriptDoubleClick(false);
        store.Features.SaveScript(new ScriptFeatureSettings(false,"CTRL+SHIFT+K"));
        string uiBefore=File.ReadAllText(path), featureBefore=File.ReadAllText(Path.Combine(work,"features.properties"));
        var service=new NoService();
        using(var form=new EnhancementForm(new string[0],service,store,d=>Task.FromResult(false),(m,i)=>{throw new Exception(m);},false)) {
            form.StartPosition=FormStartPosition.Manual;form.Location=new Point(-20000,-20000);form.ShowInTaskbar=false;
            form.Show();Application.DoEvents();
            var controls=All(form).ToArray();
            var directory=(ComboBox)controls.Single(c=>c.Name=="installationDirectory");directory.Text=@"D:\Test\Lucanet";
            int layouts=0;
            foreach(var c in controls)c.Layout+=delegate{layouts++;};
            var times=new List<double>();var lines=new List<string>();
            // 首次进入、返回、设置页之间以及反复点击当前页签。
            for(int cycle=0;cycle<5;cycle++) {
                if(cycle==3){form.Size=form.MinimumSize;Application.DoEvents();}
                if(cycle==4){form.Width+=160;Application.DoEvents();}
                foreach(string target in new[]{"application","features","code","workspace","application","application","workspace"}) {
                    string button=target=="workspace"?"workspaceNavigation":target=="features"?"featureNavigation":target=="code"?"codeNavigation":
                        controls.Single(c=>c.Name=="workspacePage").Visible?"settingsNavigation":"applicationNavigation";
                    layouts=0;var watch=Stopwatch.StartNew();
                    ((Button)controls.Single(c=>c.Name==button)).PerformClick();
                    double layoutTime=watch.Elapsed.TotalMilliseconds;
                    form.Update();double paintTime=watch.Elapsed.TotalMilliseconds-layoutTime;
                    Application.DoEvents();watch.Stop();times.Add(watch.Elapsed.TotalMilliseconds);
                    lines.Add(String.Format("{0},{1},{2:F2},{3},{4:F2},{5:F2}",cycle,target,watch.Elapsed.TotalMilliseconds,layouts,layoutTime,paintTime));
                    if(!baseline) {
                        string title=target=="workspace"?"工作区":target=="application"?"应用":target=="code"?"代码":"界面";
                        Check(controls.Single(c=>c.Name=="pageTitle").Text==title,"title follows selected tab");
                    }
                    Check(directory.Text==@"D:\Test\Lucanet","navigation preserves selected installation");
                }
            }
            File.WriteAllLines(Path.Combine(work,"navigation.csv"),new[]{"cycle,page,milliseconds,layouts,layoutMs,paintMs"}.Concat(lines));
            Console.WriteLine("Navigation: first="+times[0].ToString("F1")+" ms; median="+times.OrderBy(x=>x).ElementAt(times.Count/2).ToString("F1")+" ms; max="+times.Max().ToString("F1")+" ms");
            Check(service.Calls==0,"navigation must not call discovery/status/install backend");
            Check(File.ReadAllText(path)==uiBefore && File.ReadAllText(Path.Combine(work,"features.properties"))==featureBefore,"navigation must not rewrite preferences");
            if(!baseline)Check(times.Max()<250,"navigation must not block UI for 250 ms: "+times.Max().ToString("F1"));
            form.Close();
        }
    }
    private static IEnumerable<Control> All(Control parent) {
        foreach(Control c in parent.Controls) {yield return c;foreach(var child in All(c))yield return child;}
    }
    private static void Check(bool condition,string message) {if(!condition)throw new Exception(message);}
    private sealed class NoService : IEnhancementService {
        internal int Calls;
        public Task<Dictionary<string,object>> InvokeAsync(string action,string directory) {Calls++;throw new InvalidOperationException("Unexpected backend: "+action);}
    }
}
