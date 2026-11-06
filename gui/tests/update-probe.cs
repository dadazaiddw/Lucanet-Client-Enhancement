using System;
internal static class UpdateProbe {
    private static int Main() {
        try {
            var result = new GitHubUpdateService().CheckAsync().GetAwaiter().GetResult();
            Console.WriteLine("Repository: " + AppInfo.Repository);
            Console.WriteLine("Local: " + AppInfo.Version + "; GitHub: " + result.Version + "; state: " + result.State);
            Console.WriteLine("Release page: " + result.PageUrl);
            Console.WriteLine("Asset: " + result.AssetName + "; SHA256 available: " + !String.IsNullOrEmpty(result.Sha256));
            return 0;
        } catch (Exception e) { Console.Error.WriteLine(e.Message); return 1; }
    }
}
