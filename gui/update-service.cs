using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

internal enum UpdateState { Available, Current, Ahead, NoRelease }
internal sealed class ReleaseUpdate {
    internal UpdateState State;
    internal string Version, PageUrl, AssetUrl, AssetName, Sha256;
    internal long Size;
    internal bool CanDownload { get { return State == UpdateState.Available && !String.IsNullOrEmpty(AssetUrl) && !String.IsNullOrEmpty(Sha256); } }
}
internal interface IUpdateService {
    Task<ReleaseUpdate> CheckAsync();
    Task<string> DownloadAsync(ReleaseUpdate release, IProgress<int> progress);
}

// 只读取指定仓库的稳定 Release，不读取分支源码，不写 GitHub，也不后台执行下载的程序。
internal sealed class GitHubUpdateService : IUpdateService {
    private readonly HttpClient client;
    private readonly string cacheRoot;
    internal GitHubUpdateService() : this(new HttpClientHandler { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate }) { }
    internal GitHubUpdateService(HttpMessageHandler handler, string cacheRoot = null) {
        // Windows .NET Framework 的旧默认协议可能不包含 GitHub 要求的 TLS 1.2。
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        this.cacheRoot = cacheRoot ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Lucanet","ClientEnhancement","updates");
        client = new HttpClient(handler); client.Timeout = TimeSpan.FromSeconds(40);
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Lucanet-Client-Enhancement/" + AppInfo.Version);
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
    }
    public async Task<ReleaseUpdate> CheckAsync() {
        try {
            using (var response = await client.GetAsync(AppInfo.LatestReleaseApi, HttpCompletionOption.ResponseHeadersRead)) {
                if (response.StatusCode == HttpStatusCode.NotFound) return new ReleaseUpdate { State = UpdateState.NoRelease, PageUrl = AppInfo.Releases };
                if ((int)response.StatusCode == 403 || (int)response.StatusCode == 429) throw new InvalidOperationException("GitHub 暂时限制请求，请稍后再试，或打开发布页面。");
                response.EnsureSuccessStatusCode();
                string json = System.Text.Encoding.UTF8.GetString(await ReadLimitedAsync(response, 2 * 1024 * 1024));
                return ParseRelease(json, AppInfo.Version);
            }
        } catch (TaskCanceledException) { throw new IOException("检查更新超时，请检查网络后重试。"); }
        catch (HttpRequestException) { throw new IOException("无法连接 GitHub，请检查网络或稍后重试。"); }
    }

    internal static Version ParseVersion(string text) {
        var match = Regex.Match(text ?? "", @"^[vV]?(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?$");
        if (!match.Success) throw new InvalidDataException("发布版本号格式不受支持，请在 GitHub 发布页面确认。");
        return new Version(Int32.Parse(match.Groups[1].Value), Int32.Parse(match.Groups[2].Value), Int32.Parse(match.Groups[3].Value), match.Groups[4].Success ? Int32.Parse(match.Groups[4].Value) : 0);
    }
    internal static ReleaseUpdate ParseRelease(string json, string installedVersion) {
        var data = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
        if (data == null || GuiData.BoolOf(data,"draft") || GuiData.BoolOf(data,"prerelease")) throw new InvalidDataException("没有可用的稳定发布版本。");
        string version = GuiData.TextOf(data,"tag_name"); int comparison = ParseVersion(version).CompareTo(ParseVersion(installedVersion));
        string page = GuiData.TextOf(data,"html_url");
        if (!IsRepositoryUrl(page, "/releases/")) throw new InvalidDataException("发布页面不属于本项目仓库。");
        var result = new ReleaseUpdate { Version = version.TrimStart('v','V'), PageUrl = page,
            State = comparison > 0 ? UpdateState.Available : comparison == 0 ? UpdateState.Current : UpdateState.Ahead };
        foreach (object value in GuiData.Items(GuiData.ValueOf(data,"assets"))) {
            var asset = GuiData.Map(value); string name = GuiData.TextOf(asset,"name");
            if (!Regex.IsMatch(name, @"^lucanet-client-enhancement(?:-v?\d+\.\d+\.\d+(?:\.\d+)?)?\.zip$", RegexOptions.IgnoreCase)) continue;
            string url = GuiData.TextOf(asset,"browser_download_url"); string digest = GuiData.TextOf(asset,"digest"); long size;
            if (!IsRepositoryUrl(url, "/releases/download/") || !Int64.TryParse(GuiData.TextOf(asset,"size"), out size) || size <= 0 || size > 128 * 1024 * 1024) continue;
            result.AssetName = name; result.AssetUrl = url; result.Size = size;
            if (Regex.IsMatch(digest, "^sha256:[0-9a-fA-F]{64}$")) result.Sha256 = digest.Substring(7).ToLowerInvariant();
            break;
        }
        return result;
    }
    internal static bool IsRepositoryUrl(string text, string suffix) {
        Uri uri;
        return Uri.TryCreate(text,UriKind.Absolute,out uri) && uri.Scheme == "https" && uri.Host.Equals("github.com",StringComparison.OrdinalIgnoreCase)
            && uri.IsDefaultPort && String.IsNullOrEmpty(uri.UserInfo)
            && uri.AbsolutePath.StartsWith("/dadazaiddw/Lucanet-Client-Enhancement"+suffix,StringComparison.OrdinalIgnoreCase);
    }
    private static bool IsDownloadHost(Uri uri) {
        return uri.Scheme == "https" && uri.IsDefaultPort && String.IsNullOrEmpty(uri.UserInfo)
            && (IsRepositoryUrl(uri.AbsoluteUri,"/releases/download/") || uri.Host == "release-assets.githubusercontent.com" || uri.Host == "objects.githubusercontent.com");
    }

    public async Task<string> DownloadAsync(ReleaseUpdate release, IProgress<int> progress) {
        if (release == null || !release.CanDownload || !IsRepositoryUrl(release.AssetUrl,"/releases/download/")) throw new InvalidOperationException("此发布没有可校验的完整更新包，请打开 GitHub 发布页面。");
        string cache = Path.Combine(cacheRoot,Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(cache); string zip = Path.Combine(cache,"release.zip");
        bool ready = false;
        try {
            Uri uri = new Uri(release.AssetUrl);
            using (var deadline = new CancellationTokenSource(TimeSpan.FromMinutes(3))) {
                for (int redirect = 0; redirect < 5; redirect++) {
                    if (!IsDownloadHost(uri)) throw new InvalidDataException("更新下载地址不受信任。");
                    using (var response = await client.GetAsync(uri,HttpCompletionOption.ResponseHeadersRead,deadline.Token)) {
                        int code = (int)response.StatusCode;
                        if (code >= 300 && code < 400) {
                            if (response.Headers.Location == null) throw new InvalidDataException("更新下载重定向无效。");
                            uri = new Uri(uri,response.Headers.Location); continue;
                        }
                        response.EnsureSuccessStatusCode();
                        using (var stream = await response.Content.ReadAsStreamAsync())
                        using (var output = File.Create(zip)) {
                            var buffer = new byte[65536]; long total = 0; int read;
                            while ((read = await stream.ReadAsync(buffer,0,buffer.Length,deadline.Token)) > 0) {
                                total += read; if (total > release.Size) throw new InvalidDataException("更新包大小与发布信息不符。");
                                await output.WriteAsync(buffer,0,read,deadline.Token);
                                if (progress != null) progress.Report((int)(total * 100 / release.Size));
                            }
                            if (total != release.Size) throw new InvalidDataException("更新包下载不完整，请重试。");
                        }
                        string extracted = await Task.Run(() => VerifyAndExtract(zip,release.Sha256,Path.Combine(cache,"app")));
                        ready = true; return extracted;
                    }
                }
                throw new InvalidDataException("更新下载重定向次数过多。");
            }
        } catch (TaskCanceledException) { throw new IOException("更新下载超时，请重试。"); }
        catch (HttpRequestException) { throw new IOException("更新包下载失败，请检查网络后重试。"); }
        finally {
            // 仅移除本次新建的独立缓存，失败不会改动当前程序、Lucanet 配置或用户偏好。
            if (!ready && Directory.Exists(cache)) { try { Directory.Delete(cache,true); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
        }
    }

    internal static string VerifyAndExtract(string zipPath, string digest, string destination) {
        using (var sha = SHA256.Create()) using (var file = File.OpenRead(zipPath)) {
            string actual = BitConverter.ToString(sha.ComputeHash(file)).Replace("-","");
            if (!String.Equals(actual,digest,StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("更新包 SHA-256 校验失败，已停止更新。");
        }
        if (Directory.Exists(destination) || File.Exists(destination)) throw new IOException("更新目标必须是新目录。");
        string root = Path.GetFullPath(destination).TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar;
        using (var zip = ZipFile.OpenRead(zipPath)) {
            if (zip.Entries.Count > 3000) throw new InvalidDataException("更新包文件数量异常。");
            long total = 0; var files = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var nonemptyFiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var entry in zip.Entries) {
                string name = entry.FullName.Replace('\\','/');
                if (String.IsNullOrEmpty(name) || name.StartsWith("/") || name.Contains(":")) throw new InvalidDataException("更新包路径无效。");
                foreach (string part in name.TrimEnd('/').Split('/')) {
                    if (String.IsNullOrEmpty(part) || part == "." || part == ".." || part.EndsWith(".") || part.EndsWith(" ") || part.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
                        throw new InvalidDataException("更新包含非法路径。");
                    if (Regex.IsMatch(part,@"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)",RegexOptions.IgnoreCase)) throw new InvalidDataException("更新包含系统保留路径。");
                }
                string target = Path.GetFullPath(Path.Combine(destination,name.Replace('/',Path.DirectorySeparatorChar)));
                if (!target.StartsWith(root,StringComparison.OrdinalIgnoreCase) || (entry.ExternalAttributes & (int)FileAttributes.ReparsePoint) != 0 || ((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000)
                    throw new InvalidDataException("更新包含越界路径或链接。");
                total += entry.Length; if (total > 256 * 1024 * 1024) throw new InvalidDataException("更新包解压体积异常。");
                if (!files.Add(name.TrimEnd('/'))) throw new InvalidDataException("更新包含重复路径。");
                if (!name.EndsWith("/") && entry.Length > 0) nonemptyFiles.Add(name);
            }
            foreach (string required in new[] {"enhancement.exe","install.ps1","resolve-lucanet-jdk.ps1","tools/gui-service.ps1","tools/gui-core.ps1","dist/client-enhancement.jar"}) {
                if (!nonemptyFiles.Contains(required)) throw new InvalidDataException("该发布未提供兼容的完整更新包，请在 GitHub 发布页下载。缺少："+required);
            }
            Directory.CreateDirectory(destination);
            foreach (var entry in zip.Entries) {
                string target = Path.Combine(destination,entry.FullName.Replace('/',Path.DirectorySeparatorChar).Replace('\\',Path.DirectorySeparatorChar));
                if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\")) Directory.CreateDirectory(target);
                else { Directory.CreateDirectory(Path.GetDirectoryName(target)); entry.ExtractToFile(target,false); }
            }
        }
        return destination;
    }

    private static async Task<byte[]> ReadLimitedAsync(HttpResponseMessage response,int maximum) {
        using (var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30))) {
        using (var input = await response.Content.ReadAsStreamAsync()) using (var output = new MemoryStream()) {
            var buffer = new byte[16384]; int count;
            while ((count = await input.ReadAsync(buffer,0,buffer.Length,deadline.Token)) > 0) {
                if (output.Length + count > maximum) throw new InvalidDataException("GitHub 返回的数据过大。");
                output.Write(buffer,0,count);
            }
            return output.ToArray();
        }
        }
    }
}
