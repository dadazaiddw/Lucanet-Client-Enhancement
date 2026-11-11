using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows.Forms;

internal static class ScriptSettingsTests {
    internal static void Verify(string work,IEnhancementService service) {
        Directory.CreateDirectory(work);
        var store=new GuiSettingsStore(Path.Combine(work,"ui.json"));
        store.Save(new GuiPreferences {AutoRefresh=false,RefreshSeconds=8});
        string features=Path.Combine(work,"features.properties");
        File.WriteAllText(features,"linksInNewWindow=false\nfutureFeature=keep\n");
        Check(store.Features.LoadScript().HideStart && store.Features.LoadScript().Shortcut=="ALT+R","script defaults hide start");
        int messages=0;
        using(var form=new EnhancementForm(new string[0],service,store,d=>Task.FromResult(false),(m,i)=>messages++,false)) {
            var handle=form.Handle;form.Navigate("features");
            var hidden=(CheckBox)form.Controls.Find("hideScriptStart",true).Single();
            var doubleClick=(CheckBox)form.Controls.Find("scriptDoubleClick",true).Single();
            var shortcut=(ShortcutBox)form.Controls.Find("scriptRunShortcut",true).Single();
            Check(doubleClick.Checked && store.Features.LoadScriptDoubleClick(),"double-click default enabled");
            doubleClick.Checked=false;Check(!store.Features.LoadScriptDoubleClick(),"double-click disabled immediately");
            Check(hidden.Checked && shortcut.Shortcut=="ALT+R","GUI defaults hide start");
            hidden.Checked=false;Check(!store.Features.LoadScript().HideStart,"explicit display preference preserved");
            hidden.Checked=true;Check(store.Features.LoadScript().HideStart,"hide saved immediately");
            var command=typeof(ShortcutBox).GetMethod("ProcessCmdKey",BindingFlags.NonPublic|BindingFlags.Instance);
            command.Invoke(shortcut,new object[]{Message.Create(IntPtr.Zero,0x100,IntPtr.Zero,IntPtr.Zero),Keys.Control|Keys.Shift|Keys.K});
            Check(store.Features.LoadScript().Shortcut=="CTRL+SHIFT+K" && shortcut.Text=="Ctrl + Shift + K","key capture persists combination");
            command.Invoke(shortcut,new object[]{Message.Create(IntPtr.Zero,0x100,IntPtr.Zero,IntPtr.Zero),Keys.R});
            Check(shortcut.Shortcut=="CTRL+SHIFT+K" && store.Features.LoadScript().Shortcut=="CTRL+SHIFT+K","incomplete key leaves settings intact");
            command.Invoke(shortcut,new object[]{Message.Create(IntPtr.Zero,0x104,IntPtr.Zero,IntPtr.Zero),Keys.Alt|Keys.R});
            Check(shortcut.Shortcut=="ALT+R","Alt mnemonic captured by command route");
            store.Features.SaveLinksInNewWindow(true);
            Check(store.Features.LoadScript().HideStart && store.Features.LoadScript().Shortcut=="ALT+R","navigation save preserves script settings");
            hidden.Checked=false;
            Check(store.Features.LoadLinksInNewWindow() && File.ReadAllText(features).Contains("futureFeature=keep"),"script save preserves navigation and future settings");
            Check(!store.Features.LoadScriptDoubleClick(),"other settings preserve disabled double-click");
            var reset=(Button)form.Controls.Find("resetFeatureSettings",true).Single();
            typeof(Button).GetMethod("OnClick",BindingFlags.NonPublic|BindingFlags.Instance).Invoke(reset,new object[]{EventArgs.Empty});
            Check(hidden.Checked && store.Features.LoadScript().HideStart && store.Features.LoadScript().Shortcut=="ALT+R","restore defaults enables hiding");
            Check(doubleClick.Checked && store.Features.LoadScriptDoubleClick(),"restore defaults enables double-click");
            Check(messages==0,"normal edits do not show dialogs");form.Close();
        }
        foreach(string invalid in new[]{"R","SHIFT+R","CTRL+CTRL+R","ALT+F4","CTRL+ENTER","WIN+R"}) {
            try{ScriptShortcutSetting.Normalize(invalid);throw new Exception("Invalid shortcut accepted: "+invalid);}catch(ArgumentException){}
        }
        Check(ScriptShortcutSetting.Normalize("shift + ctrl + F12")=="CTRL+SHIFT+F12","normalized modifier order and function key");
        File.WriteAllText(features,"hideScriptStart=true\nscriptRunShortcut=bad\n");
        Check(!store.Features.LoadScript().HideStart && store.Features.LoadScript().Shortcut=="ALT+R","invalid config keeps button accessible");
        Console.WriteLine("PASS: script hide/defaults/key capture, live persistence, safe validation and preservation of other feature settings");
    }
    private static void Check(bool condition,string text){if(!condition)throw new Exception(text);}
}
