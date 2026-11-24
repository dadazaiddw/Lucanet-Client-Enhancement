using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

internal sealed partial class EnhancementForm {
    private readonly List<Action> resetStorage=new List<Action>();
    private void BuildStorageSettings(TableLayoutPanel stack,bool logs){
        string prefix=logs?"enhancementLog":"archive",fallback=logs?FeatureSettingsStore.DefaultLogDirectory:FeatureSettingsStore.DefaultArchiveDirectory;
        StoragePreferences saved;
        try{saved=settingsStore.Features.LoadStorage(logs);}catch(IOException){saved=new StoragePreferences(false,fallback,7);}catch(UnauthorizedAccessException){saved=new StoragePreferences(false,fallback,7);}
        var card=SettingsCard(prefix+"Card");
        AddSectionHeading(card,logs?"增强插件日志":"临时文件归档",logs?"运行记录持续保存，退出后可在所选目录查看。":"正常退出时归档客户端临时文件，保留原目录结构。");
        var enabled=new SettingToggle{Name=prefix+"Enabled",Checked=saved.Enabled,AccessibleName=logs?"保存增强插件日志":"启用临时文件归档"};
        var directory=new TextBox{Name=prefix+"Directory",Text=saved.Directory,AccessibleName=logs?"日志保存目录":"归档保存目录",Dock=DockStyle.Fill};
        var days=new NumericUpDown{Name=prefix+"RetentionDays",Minimum=1,Maximum=FeatureSettingsStore.MaximumRetentionDays,Value=saved.Days,Width=100,AccessibleName=logs?"日志保留天数":"归档保留天数"};
        var hint=SettingsLabel("修改后自动保存并实时生效；保留时间默认 7 天。",9,false,Muted,prefix+"Hint");
        bool restoring=false;
        Action restore=delegate{restoring=true;enabled.Checked=saved.Enabled;directory.Text=saved.Directory;days.Value=saved.Days;restoring=false;};
        Func<bool> save=delegate{
            if(restoring)return true;
            try{
                var next=new StoragePreferences(enabled.Checked,FeatureSettingsStore.ValidateDirectory(directory.Text),(int)days.Value);settingsStore.Features.SaveStorage(logs,next);saved=next;
                restoring=true;directory.Text=saved.Directory;restoring=false;
                hint.Text="已保存，实时生效。已有文件保留在原目录。";hint.ForeColor=Muted;return true;
            }catch(Exception error){restore();hint.Text="设置未保存："+error.Message;hint.ForeColor=Color.Firebrick;return false;}
        };
        enabled.CheckedChanged+=delegate{save();};days.ValueChanged+=delegate{save();};
        directory.Leave+=delegate{if(directory.Text!=saved.Directory)save();};
        directory.KeyDown+=delegate(object sender,KeyEventArgs e){if(e.KeyCode==Keys.Enter){e.SuppressKeyPress=true;save();}};
        var browse=MakeButton("浏览…",false,84);browse.Name=prefix+"Browse";
        browse.Click+=delegate{
            using(var picker=new FolderBrowserDialog{Description=logs?"选择日志保存目录":"选择归档保存目录",ShowNewFolderButton=true}){
                try{string resolved=FeatureSettingsStore.ValidateDirectory(directory.Text);if(System.IO.Directory.Exists(resolved))picker.SelectedPath=resolved;}catch(Exception){}
                if(picker.ShowDialog(this)==DialogResult.OK){directory.Text=picker.SelectedPath;save();}
            }
        };
        AddAutoRow(card,SettingRow(logs?"保存日志":"启用归档",logs?"关闭后停止新增日志及过期清理。":"关闭后恢复原生临时文件弹窗，并停止归档及过期清理。",enabled));
        AddAutoRow(card,SettingsDivider());
        var label=SettingsLabel("默认保存目录",10,false,Ink);label.Margin=new Padding(0,12,0,8);AddAutoRow(card,label);
        var pathRow=new TableLayoutPanel{ColumnCount=2,RowCount=1,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Margin=new Padding(0)};
        pathRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));pathRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        directory.Anchor=AnchorStyles.Left|AnchorStyles.Right;directory.Margin=new Padding(0,0,12,0);browse.Margin=new Padding(0);
        pathRow.Controls.Add(directory,0,0);pathRow.Controls.Add(browse,1,0);AddAutoRow(card,pathRow);
        AddAutoRow(card,SettingRow("保留时间（天）",logs?"仅清理本目录内已过期且所属进程已退出的日志文件。":"仅清理本目录内已过期且所属进程已退出的归档文件。",days));
        AddAutoRow(card,hint);AddAutoRow(stack,card);
        resetStorage.Add(delegate{restoring=true;enabled.Checked=true;directory.Text=fallback;days.Value=FeatureSettingsStore.DefaultRetentionDays;restoring=false;save();});
    }
}
