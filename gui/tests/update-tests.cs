using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

internal static class UpdateTests {
    internal static async Task Verify(string work) {
        byte[] bytes=Package(null);string hash=Hash(bytes);
        Assert(AppInfo.Version == "1.0.0", "user-specified application version");
        Assert(GitHubUpdateService.ParseRelease(Release("v1.0.0",bytes.Length,hash),AppInfo.Version).State==UpdateState.Current,"v1.0.0 release matches application version");
        var info=GitHubUpdateService.ParseRelease(Release("v2.1.0",bytes.Length,hash),"2.0.0");
        Assert(info.CanDownload&&info.State==UpdateState.Available,"new stable release");
        Assert(GitHubUpdateService.ParseRelease(Release("v2.0.0",bytes.Length,hash),"2.0.0").State==UpdateState.Current,"same version");
        Assert(GitHubUpdateService.ParseRelease(Release("v1.0.0",bytes.Length,hash),"2.0.0").State==UpdateState.Ahead,"no downgrade");
        Assert(GitHubUpdateService.ParseVersion("2.10.0")>GitHubUpdateService.ParseVersion("2.9.0"),"numeric versions");
        Assert(!GitHubUpdateService.ParseRelease(Release("v2.1.0",bytes.Length,""),"2.0.0").CanDownload,"missing digest disables direct update");
        Reject(()=>GitHubUpdateService.ParseRelease(Release("v2.1.0",bytes.Length,hash).Replace("github.com/dadazaiddw","github.com/other"),"2.0.0"),"different repository");
        var network=new FakeHandler();network.Json=Release("v2.1.0",bytes.Length,hash);network.Bytes=bytes;
        var service=new GitHubUpdateService(network,Path.Combine(work,"updates"));
        Assert((await service.CheckAsync()).State==UpdateState.Available,"actual HTTP parsing path");
        int progress=0;string extracted=await service.DownloadAsync(info,new ImmediateProgress(p=>progress=p));
        Assert(File.Exists(Path.Combine(extracted,"enhancement.exe"))&&progress==100,"verified download and full extraction");
        network.Status=HttpStatusCode.NotFound;Assert((await service.CheckAsync()).State==UpdateState.NoRelease,"404 is no release");
        network.Status=(HttpStatusCode)429;
        try{await service.CheckAsync();throw new Exception("rate limit accepted");}catch(InvalidOperationException){ }
        string zip=Path.Combine(work,"test-update.zip");File.WriteAllBytes(zip,bytes);
        Reject(()=>GitHubUpdateService.VerifyAndExtract(zip,new string('0',64),Path.Combine(work,"wrong-hash")),"digest mismatch");
        foreach(string name in new[]{"../escape.txt","/rooted.txt","C:/escape.txt","folder/file:stream","folder/../escape","NUL.txt","folder./bad"}){
            byte[] bad=Package(name);File.WriteAllBytes(zip,bad);
            Reject(()=>GitHubUpdateService.VerifyAndExtract(zip,Hash(bad),Path.Combine(work,"invalid-"+Guid.NewGuid().ToString("N"))),"unsafe ZIP path: "+name);
        }
        File.WriteAllBytes(zip,Package(null,true));
        Reject(()=>GitHubUpdateService.VerifyAndExtract(zip,Hash(File.ReadAllBytes(zip)),Path.Combine(work,"incomplete")),"missing package dependencies");
        network.Status=HttpStatusCode.OK;network.Redirect="https://example.com/untrusted.zip";
        try{await service.DownloadAsync(info,null);throw new Exception("foreign redirect accepted");}catch(InvalidDataException){ }
        Assert(!File.Exists(Path.Combine(work,"escape.txt")),"ZIP extraction never escaped cache");
        Console.WriteLine("PASS: GitHub stable releases, numeric version comparison, no downgrade, 404/rate limit, checksum download, full package, redirect allowlist and ZIP traversal rejection");
    }
    private static byte[] Package(string extra,bool incomplete=false){
        using(var stream=new MemoryStream()){
            using(var zip=new ZipArchive(stream,ZipArchiveMode.Create,true)){
                string[] names=incomplete?new[]{"enhancement.exe"}:new[]{"enhancement.exe","install.ps1","resolve-lucanet-jdk.ps1","tools/gui-service.ps1","tools/gui-core.ps1","dist/client-enhancement.jar"};
                foreach(string n in names){var e=zip.CreateEntry(n);using(var writer=new StreamWriter(e.Open()))writer.Write("fixture");}
                if(extra!=null){var e=zip.CreateEntry(extra);using(var writer=new StreamWriter(e.Open()))writer.Write("reject");}
            }return stream.ToArray();
        }
    }
    private static string Release(string version,int size,string hash){
        return new JavaScriptSerializer().Serialize(new {tag_name=version,draft=false,prerelease=false,html_url=AppInfo.Repository+"/releases/tag/"+version,assets=new[]{new{name="lucanet-client-enhancement-"+version+".zip",size=size,browser_download_url=AppInfo.Repository+"/releases/download/"+version+"/lucanet-client-enhancement-"+version+".zip",digest=String.IsNullOrEmpty(hash)?null:"sha256:"+hash}}});
    }
    private static string Hash(byte[] data){using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(data)).Replace("-","").ToLowerInvariant();}
    private static void Reject(Action action,string description){try{action();throw new Exception("Accepted "+description);}catch(InvalidDataException){ }}
    private static void Assert(bool condition,string text){if(!condition)throw new Exception(text);}
    private sealed class ImmediateProgress:IProgress<int>{private readonly Action<int> action;internal ImmediateProgress(Action<int> action){this.action=action;}public void Report(int value){action(value);}}
    private sealed class FakeHandler:HttpMessageHandler {
        internal string Json,Redirect;internal byte[] Bytes;internal HttpStatusCode Status=HttpStatusCode.OK;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken cancellationToken){
            if(request.RequestUri.Host=="api.github.com")return Task.FromResult(new HttpResponseMessage(Status){Content=new StringContent(Json??"{}",Encoding.UTF8,"application/json")});
            if(Redirect!=null){var response=new HttpResponseMessage(HttpStatusCode.Redirect);response.Headers.Location=new Uri(Redirect);return Task.FromResult(response);}
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK){Content=new ByteArrayContent(Bytes)});
        }
    }
}
