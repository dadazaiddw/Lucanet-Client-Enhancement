using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

internal sealed class StoragePreferences {
    internal readonly bool Enabled;
    internal readonly string Directory;
    internal readonly int Days;
    internal StoragePreferences(bool enabled,string directory,int days){Enabled=enabled;Directory=directory;Days=days;}
}

internal sealed partial class FeatureSettingsStore {
    internal static string DefaultArchiveDirectory { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".lucanet-exit-enhancement","archive"); } }
    internal static string DefaultLogDirectory { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".lucanet-cilent-enhancement","log"); } }
    internal const int DefaultRetentionDays=7, MaximumRetentionDays=36500;
    internal StoragePreferences LoadStorage(bool logs){
        string prefix=logs?"enhancementLog":"archive",directory=logs?DefaultLogDirectory:DefaultArchiveDirectory;
        bool enabled=true;int days=DefaultRetentionDays;
        if(File.Exists(path))foreach(string source in File.ReadAllLines(path,Encoding.UTF8)){
            string line=source.Trim();int value;
            if(line.StartsWith(prefix+"Enabled=",StringComparison.Ordinal))enabled=line.Substring((prefix+"Enabled=").Length).Trim().Equals("true",StringComparison.OrdinalIgnoreCase);
            if(line.StartsWith(prefix+"Directory=",StringComparison.Ordinal)){
                string candidate=line.Substring((prefix+"Directory=").Length).Trim();
                try{directory=ValidateDirectory(candidate);}catch(ArgumentException){}catch(NotSupportedException){}catch(PathTooLongException){}
            }
            if(line.StartsWith(prefix+"RetentionDays=",StringComparison.Ordinal)&&int.TryParse(line.Substring((prefix+"RetentionDays=").Length),out value))
                days=value>=1&&value<=MaximumRetentionDays?value:DefaultRetentionDays;
        }
        return new StoragePreferences(enabled,directory,days);
    }
    internal static string ValidateDirectory(string value){
        string directory=(value??"").Trim();
        if(directory.Length==0||directory.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new ArgumentException("请输入完整目录，或以 ~/ 开头的用户目录。");
        string expanded=directory;
        if(directory=="~")expanded=Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        else if(directory.StartsWith("~/")||directory.StartsWith("~\\"))expanded=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),directory.Substring(2));
        expanded=expanded.Replace('/','\\');
        if(!Path.IsPathRooted(expanded)||(!expanded.StartsWith(@"\\")&&(expanded.Length<3||expanded[1]!=':'||(expanded[2]!='\\'&&expanded[2]!='/'))))
            throw new ArgumentException("请输入完整目录，或以 ~/ 开头的用户目录。");
        if(expanded.IndexOfAny(Path.GetInvalidPathChars())>=0||expanded.IndexOfAny(new[]{'*','?'})>=0)throw new ArgumentException("目录包含无效字符。");
        return Path.GetFullPath(expanded);
    }
    internal void SaveStorage(bool logs,StoragePreferences settings){
        string directory=ValidateDirectory(settings.Directory);
        if(settings.Days<1||settings.Days>MaximumRetentionDays)throw new ArgumentOutOfRangeException("Days","保留天数必须为 1–36500。");
        string prefix=logs?"enhancementLog":"archive";
        // Forward slashes survive java.util.Properties parsing, including Chinese/space/UNC paths.
        SaveValues(new Dictionary<string,string>{{prefix+"Enabled",settings.Enabled?"true":"false"},
            {prefix+"Directory",directory.Replace('\\','/')},{prefix+"RetentionDays",settings.Days.ToString(CultureInfo.InvariantCulture)}});
    }
}
