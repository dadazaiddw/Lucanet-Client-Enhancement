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
            var sceneFrame=(CheckBox)form.Controls.Find("nativeSceneDialogFrame",true).Single();
            var editor=(CheckBox)form.Controls.Find("javaEditorAssist",true).Single();
            var types=(CheckBox)form.Controls.Find("customJavaTypes",true).Single();
            var code=form.Controls.Find("codeSettings",true).Single();
            var ui=form.Controls.Find("featureSettings",true).Single();
            Check(code.Contains(editor)&&code.Contains(types)&&!ui.Contains(editor),"code features live only on Code page");
            Check(types.Checked&&store.Features.LoadCustomJavaTypes(),"custom types default enabled");
            types.Checked=false;Check(!store.Features.LoadCustomJavaTypes(),"custom types persist immediately");
            var font=(NumericUpDown)form.Controls.Find("javaCompletionFontSize",true).Single();
            Check(font.Value==20 && store.Features.LoadJavaCompletionFontSize()==20&&font.AccessibleName=="字号","code font label and default 20");
            Check(form.Controls.Find("javaEditorShortcutHint",true).Length==0,"shortcut description removed");
            font.Value=24;Check(new FeatureSettingsStore(features).LoadJavaCompletionFontSize()==24,"lookup font saved and reloaded immediately");
            Check(editor.Checked && store.Features.LoadJavaEditorAssist(),"Java editor default enabled");
            editor.Checked=false;Check(!store.Features.LoadJavaEditorAssist(),"Java editor persists immediately");
            Check(sceneFrame.Checked && store.Features.LoadNativeSceneDialogFrame(),"scene frame default enabled");
            sceneFrame.Checked=false;Check(!store.Features.LoadNativeSceneDialogFrame(),"scene frame persists immediately");
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
            Check(!store.Features.LoadNativeSceneDialogFrame(),"other settings preserve disabled scene frame");
            Check(!store.Features.LoadJavaEditorAssist(),"other settings preserve disabled Java editor");
            Check(store.Features.LoadJavaCompletionFontSize()==24,"other settings preserve lookup font");
            var reset=(Button)form.Controls.Find("resetFeatureSettings",true).Single();
            typeof(Button).GetMethod("OnClick",BindingFlags.NonPublic|BindingFlags.Instance).Invoke(reset,new object[]{EventArgs.Empty});
            Check(hidden.Checked && store.Features.LoadScript().HideStart && store.Features.LoadScript().Shortcut=="ALT+R","restore defaults enables hiding");
            Check(doubleClick.Checked && store.Features.LoadScriptDoubleClick(),"restore defaults enables double-click");
            Check(sceneFrame.Checked && store.Features.LoadNativeSceneDialogFrame(),"restore defaults enables scene frame");
            Check(editor.Checked && store.Features.LoadJavaEditorAssist(),"restore defaults enables Java editor");
            Check(font.Value==20&&store.Features.LoadJavaCompletionFontSize()==20,"restore default font");
            Check(types.Checked&&store.Features.LoadCustomJavaTypes(),"restore defaults enables custom types");
            using(var locked=File.Open(features,FileMode.Open,FileAccess.ReadWrite,FileShare.None)) {
                types.Checked=false;Check(types.Checked,"failed custom types save restores toggle");
            }
            Check(messages==1&&store.Features.LoadCustomJavaTypes(),"failed types save preserves configuration");messages=0;
            using(var locked=File.Open(features,FileMode.Open,FileAccess.ReadWrite,FileShare.None)) {
                font.Value=26;Check(font.Value==20,"failed font save restores displayed value");
            }
            Check(messages==1&&store.Features.LoadJavaCompletionFontSize()==20,"failed font save preserves configuration");messages=0;
            using(var locked=File.Open(features,FileMode.Open,FileAccess.ReadWrite,FileShare.None)) {
                sceneFrame.Checked=false;Check(sceneFrame.Checked,"failed save restores the displayed scene setting");
            }
            Check(messages==1 && store.Features.LoadNativeSceneDialogFrame(),"failed save reports once and preserves configuration");messages=0;
            using(var locked=File.Open(features,FileMode.Open,FileAccess.ReadWrite,FileShare.None)) {
                editor.Checked=false;Check(editor.Checked,"failed Java editor save restores displayed value");
            }
            Check(messages==1 && store.Features.LoadJavaEditorAssist(),"failed Java editor save preserves configuration");messages=0;
            Check(messages==0,"normal edits do not show dialogs");form.Close();
        }
        foreach(string invalid in new[]{"R","SHIFT+R","CTRL+CTRL+R","ALT+F4","CTRL+ENTER","WIN+R"}) {
            try{ScriptShortcutSetting.Normalize(invalid);throw new Exception("Invalid shortcut accepted: "+invalid);}catch(ArgumentException){}
        }
        Check(ScriptShortcutSetting.Normalize("shift + ctrl + F12")=="CTRL+SHIFT+F12","normalized modifier order and function key");
        File.WriteAllText(features,"hideScriptStart=true\nscriptRunShortcut=bad\n");
        Check(!store.Features.LoadScript().HideStart && store.Features.LoadScript().Shortcut=="ALT+R","invalid config keeps button accessible");
        foreach(string invalid in new[]{"0","31","bad"}) {
            File.WriteAllText(features,"javaCompletionFontSize="+invalid+"\n");Check(store.Features.LoadJavaCompletionFontSize()==20,"invalid font falls back to 20");
        }
        Console.WriteLine("PASS: script hide/defaults/key capture, live persistence, safe validation and preservation of other feature settings");
    }
    private static void Check(bool condition,string text){if(!condition)throw new Exception(text);}
}
