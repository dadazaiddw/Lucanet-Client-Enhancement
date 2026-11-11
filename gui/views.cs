using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;

internal sealed partial class EnhancementForm {
    private Panel pageHost, workspacePage, settingsPage, applicationPage, featurePage;
    private Button workspaceNav, settingsNav, applicationNav, featureNav;
    private Label pageTitle, updateStatus, lastUpdateLabel;
    private Button checkUpdatesButton, downloadUpdateButton, openUpdateButton;
    private TableLayoutPanel updateActionRow;
    private CheckBox autoCheckUpdates;
    private CheckBox linksInNewWindow;
    private bool restoringLinkSetting;
    private CheckBox hideScriptStart;
    private ShortcutBox scriptShortcut;
    private ScriptFeatureSettings scriptPreferences;
    private bool restoringScriptSettings;
    private IUpdateService updateService;
    private ReleaseUpdate release;
    private bool updateChecking, updateDownloading, updateInstalling;
    private string downloadedDirectory;

    private void BuildView() {
        var shell = new TableLayoutPanel { Dock=DockStyle.Fill, ColumnCount=2, RowCount=1, Margin=new Padding(0) };
        shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,176)); shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
        shell.RowStyles.Add(new RowStyle(SizeType.Percent,100)); Controls.Add(shell);
        var sidebar = new TableLayoutPanel { Dock=DockStyle.Fill, BackColor=Navy, ColumnCount=1, RowCount=7, Padding=new Padding(14,22,14,14), Margin=new Padding(0) };
        sidebar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
        foreach(int height in new[]{68,44,8,44,44}) sidebar.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        sidebar.RowStyles.Add(new RowStyle(SizeType.Percent,100)); sidebar.RowStyles.Add(new RowStyle(SizeType.Absolute,30));
        sidebar.Controls.Add(new Label { Text="Lucanet\nClient Enhancement", Dock=DockStyle.Fill, ForeColor=Color.White, Font=new Font(Font.FontFamily,11,FontStyle.Bold) },0,0);
        workspaceNav=NavigationButton("工作区","workspaceNavigation");workspaceNav.Click+=delegate{Navigate("workspace");};
        settingsNav=NavigationButton("设置","settingsNavigation");settingsNav.Click+=delegate{Navigate("application");};
        sidebar.Controls.Add(workspaceNav,0,1);sidebar.Controls.Add(settingsNav,0,3);
        sidebar.Controls.Add(new Label { Text="v"+AppInfo.Version,ForeColor=Color.FromArgb(172,194,207),Dock=DockStyle.Fill,TextAlign=ContentAlignment.BottomLeft },0,6);
        shell.Controls.Add(sidebar,0,0);

        var main=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=1,RowCount=2,Margin=new Padding(0) };
        main.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));main.RowStyles.Add(new RowStyle(SizeType.Absolute,62));main.RowStyles.Add(new RowStyle(SizeType.Percent,100));shell.Controls.Add(main,1,0);
        pageTitle=new Label { Text="工作区",Dock=DockStyle.Fill,Padding=new Padding(24,18,0,0),Font=new Font(Font.FontFamily,16,FontStyle.Bold),BackColor=Color.White,ForeColor=Navy,Margin=new Padding(0) };
        main.Controls.Add(pageTitle,0,0);
        pageHost=new Panel { Dock=DockStyle.Fill,Margin=new Padding(0) };main.Controls.Add(pageHost,0,1);
        workspacePage=new Panel { Name="workspacePage",Dock=DockStyle.Fill,Padding=new Padding(24,24,24,20),AutoScroll=true };
        settingsPage=new Panel { Name="settingsPage",Dock=DockStyle.Fill,Padding=new Padding(24,18,24,20),Visible=false };
        pageHost.Controls.Add(settingsPage);pageHost.Controls.Add(workspacePage);
        BuildWorkspace();BuildSettings();Navigate("workspace");UpdateButtons();
    }

    private Button NavigationButton(string text,string name) {
        var b=new Button { Name=name,Text=text,TextAlign=ContentAlignment.MiddleLeft,Dock=DockStyle.Fill,FlatStyle=FlatStyle.Flat,ForeColor=Color.FromArgb(215,227,234),BackColor=Navy,Cursor=Cursors.Hand,Padding=new Padding(13,0,0,0),Margin=new Padding(0),Font=new Font(Font.FontFamily,10) };
        b.FlatAppearance.BorderSize=0;b.FlatAppearance.MouseOverBackColor=Color.FromArgb(22,65,82);return b;
    }

    internal void Navigate(string page) {
        bool workspace=page=="workspace";
        workspacePage.Visible=workspace;settingsPage.Visible=!workspace;
        workspaceNav.BackColor=workspace?Purple:Navy;workspaceNav.ForeColor=Color.White;
        settingsNav.BackColor=workspace?Navy:Purple;settingsNav.ForeColor=Color.White;
        pageTitle.Text=workspace?"工作区":"设置";
        if(!workspace){applicationPage.Visible=page=="application";featurePage.Visible=page=="features";}
        StyleTab(applicationNav,page=="application");StyleTab(featureNav,page=="features");
        if(workspace)workspacePage.BringToFront();else settingsPage.BringToFront();
    }

    private void BuildWorkspace() {
        var content=new TableLayoutPanel { Dock=DockStyle.Top,AutoSize=true,ColumnCount=1,RowCount=5,Margin=new Padding(0) };
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));
        foreach(int height in new[]{114,152,56,56,44})content.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        workspacePage.Controls.Add(content);
        var pathBox=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=4,RowCount=3,BackColor=Color.White,Padding=new Padding(16,12,16,10),Margin=new Padding(0,0,0,14) };
        pathBox.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));foreach(int width in new[]{82,92,92})pathBox.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,width));
        foreach(int height in new[]{24,34,20})pathBox.RowStyles.Add(new RowStyle(SizeType.Absolute,height));
        var title=new Label { Text="Lucanet.Software Manager 安装目录",ForeColor=Navy,Font=new Font(Font,FontStyle.Bold),AutoSize=true };
        pathBox.Controls.Add(title,0,0);pathBox.SetColumnSpan(title,4);
        directories=new ComboBox { Name="installationDirectory",Dock=DockStyle.Fill,DropDownStyle=ComboBoxStyle.DropDown,Font=new Font(Font.FontFamily,10),Margin=new Padding(0,3,10,0),AccessibleName="Lucanet 安装目录" };
        directories.TextChanged+=delegate{if(foregroundBusy||closing)return;requestGeneration++;status=null;lastCheckedRoot="";ResetCards();UpdateButtons();};
        directories.SelectionChangeCommitted+=async delegate{if(!foregroundBusy)await RefreshStatusAsync(false);};
        directories.KeyDown+=async delegate(object sender,KeyEventArgs e){if(e.KeyCode==Keys.Enter){e.SuppressKeyPress=true;await RefreshStatusAsync(false);}};
        browseButton=MakeButton("浏览…",false,76);browseButton.Click+=async delegate{await BrowseAsync();};
        discoverButton=MakeButton("自动识别",false,86);discoverButton.Click+=async delegate{await DiscoverAsync();};
        checkButton=MakeButton("检查状态",false,86);checkButton.Click+=async delegate{await RefreshStatusAsync(false);};
        pathBox.Controls.Add(directories,0,1);pathBox.Controls.Add(browseButton,1,1);pathBox.Controls.Add(discoverButton,2,1);pathBox.Controls.Add(checkButton,3,1);
        selectionSource=new Label { Text="选择安装目录，或点击“自动识别”。",AutoSize=true,ForeColor=Muted,Margin=new Padding(0,3,0,0) };
        pathBox.Controls.Add(selectionSource,0,2);pathBox.SetColumnSpan(selectionSource,4);content.Controls.Add(pathBox,0,0);

        var cards=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=3,RowCount=1,Margin=new Padding(0,0,0,16) };
        for(int i=0;i<3;i++)cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100F/3));cards.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        cards.Controls.Add(MakeCard("安装状态","installationState",out installationValue,out installationDetail),0,0);
        cards.Controls.Add(MakeCard("Lucanet 运行状态","runningState",out runningValue,out runningDetail),1,0);
        Label empty;var jdk=MakeCard("JDK","jdkVersion",out jdkValue,out empty);jdk.Controls.Remove(empty);empty.Dispose();jdk.Margin=new Padding(0);cards.Controls.Add(jdk,2,0);content.Controls.Add(cards,0,1);
        var primary=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false,Margin=new Padding(0),Padding=new Padding(0,5,0,0) };
        installButton=MakeButton("安装",true,126);installButton.Name="install";installButton.Click+=async delegate{await DeployAsync(false);};
        uninstallButton=MakeButton("卸载",false,126);uninstallButton.Name="uninstall";uninstallButton.Click+=async delegate{await DeployAsync(true);};
        primary.Controls.AddRange(new Control[]{installButton,uninstallButton});content.Controls.Add(primary,0,2);
        var secondary=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false,Margin=new Padding(0),Padding=new Padding(0,4,0,0) };
        startButton=MakeButton("打开Lucanet.Software Manager",false,272);startButton.Click+=async delegate{await OpenManagerAsync();};
        elevateButton=MakeButton("以管理员身份重新打开",false,214);elevateButton.Click+=async delegate{await RelaunchElevatedAsync();};
        secondary.Controls.AddRange(new Control[]{startButton,elevateButton});content.Controls.Add(secondary,0,3);
        content.Controls.Add(new Label { Text="安装完成后，日常继续使用原来的 Lucanet.Software Manager。",AutoSize=true,ForeColor=Muted,Margin=new Padding(0,8,0,0) },0,4);
    }

    private void BuildSettings() {
        var layout=new TableLayoutPanel { Dock=DockStyle.Fill,ColumnCount=1,RowCount=2,Margin=new Padding(0) };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,50));layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));settingsPage.Controls.Add(layout);
        var tabs=new FlowLayoutPanel { Dock=DockStyle.Fill,WrapContents=false,Margin=new Padding(0) };
        applicationNav=MakeButton("应用",false,100);applicationNav.Name="applicationNavigation";applicationNav.Click+=delegate{Navigate("application");};
        featureNav=MakeButton("功能",false,100);featureNav.Name="featureNavigation";featureNav.Click+=delegate{Navigate("features");};tabs.Controls.AddRange(new Control[]{applicationNav,featureNav});layout.Controls.Add(tabs,0,0);
        var host=new Panel { Dock=DockStyle.Fill,Margin=new Padding(0) };layout.Controls.Add(host,0,1);
        applicationPage=new Panel { Name="applicationSettings",Dock=DockStyle.Fill,AutoScroll=true,BackColor=BackColor,Padding=new Padding(0,0,0,4) };
        featurePage=new Panel { Name="featureSettings",Dock=DockStyle.Fill,AutoScroll=true,BackColor=BackColor,Padding=new Padding(0,0,0,4),Visible=false };
        host.Controls.Add(featurePage);host.Controls.Add(applicationPage);
        BuildApplicationSettings();BuildFeatureSettings();
    }

    // 设置页使用 AutoSize 行，不把像素行高与字体 DPI 缩放混在一起。
    // 即使页首次隐藏，显示时也按真实文本/输入框/图标的 PreferredSize 重新排版。
    private TableLayoutPanel SettingsStack(Panel page) {
        var stack = VerticalStack();
        stack.Name = page.Name + "Content"; stack.Dock = DockStyle.Top;
        page.Controls.Add(stack); return stack;
    }
    private TableLayoutPanel VerticalStack() {
        var stack = new TableLayoutPanel { AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = 1, RowCount = 0, Margin = new Padding(0), Padding = new Padding(0) };
        stack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); return stack;
    }
    private void AddAutoRow(TableLayoutPanel parent, Control control) {
        int row = parent.RowCount++; parent.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        control.Dock = DockStyle.Top; parent.Controls.Add(control, 0, row);
    }
    private Label SettingsLabel(string text, float size, bool bold, Color color, string name = "") {
        return new Label { Name = name, Text = text, AutoSize = true,
            Font = new Font(Font.FontFamily, size, bold ? FontStyle.Bold : FontStyle.Regular),
            ForeColor = color, Margin = new Padding(0), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right,
            UseMnemonic = false };
    }
    private TableLayoutPanel SettingsCard(string name) {
        var card = VerticalStack(); card.Name = name; card.BackColor = Color.White;
        card.Padding = new Padding(22, 20, 22, 20); card.Margin = new Padding(0, 0, 0, 16); return card;
    }
    private void AddSectionHeading(TableLayoutPanel card, string title, string subtitle) {
        var heading = VerticalStack(); heading.Margin = new Padding(0, 0, 0, 16);
        AddAutoRow(heading, SettingsLabel(title, 12, true, Navy));
        if (!String.IsNullOrEmpty(subtitle)) {
            var description = SettingsLabel(subtitle, 9, false, Muted);
            description.Margin = new Padding(0, 6, 0, 0); AddAutoRow(heading, description);
        }
        AddAutoRow(card, heading);
    }
    private Control SettingsDivider() {
        return new Panel { Height = 1, BackColor = Color.FromArgb(232, 237, 242), Margin = new Padding(0, 4, 0, 4) };
    }
    private TableLayoutPanel ButtonRow(params Button[] buttons) {
        var row = new TableLayoutPanel { Name = "settingsButtonRow", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = buttons.Length + 1, RowCount = 1, Margin = new Padding(0), Padding = new Padding(0) };
        row.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        for (int i = 0; i < buttons.Length; i++) {
            row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            buttons[i].Anchor = AnchorStyles.Left; row.Controls.Add(buttons[i], i, 0);
        }
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); return row;
    }

    private void BuildApplicationSettings() {
        var stack = SettingsStack(applicationPage);
        var identityCard = SettingsCard("applicationIdentityCard");
        var hero = new TableLayoutPanel { Name = "applicationIdentity", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = 2, RowCount = 1, Margin = new Padding(0) };
        hero.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize)); hero.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        hero.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        hero.Controls.Add(new AppMark { Name = "applicationIcon", Anchor = AnchorStyles.Top | AnchorStyles.Left,
            Margin = new Padding(0, 0, 20, 0) }, 0, 0);
        var heading = VerticalStack(); heading.Dock = DockStyle.Top;
        AddAutoRow(heading, SettingsLabel(AppInfo.Name, 17, true, Ink, "applicationName"));
        var subtitle = SettingsLabel("Lucanet 客户端增强 · Windows", 9, false, Muted, "applicationSubtitle");
        subtitle.Margin = new Padding(0, 7, 0, 0); AddAutoRow(heading, subtitle);
        var version = SettingsLabel("v" + AppInfo.Version, 10, true, Purple, "appVersion");
        version.Margin = new Padding(0, 9, 0, 0); AddAutoRow(heading, version);
        hero.Controls.Add(heading, 1, 0); AddAutoRow(identityCard, hero); AddAutoRow(stack, identityCard);

        var updateCard = SettingsCard("applicationUpdateCard");
        AddSectionHeading(updateCard, "版本与更新", "从 GitHub Releases 检查稳定版本，下载校验后可应用热更新。" );
        checkUpdatesButton = MakeButton("检查更新", true, 112); checkUpdatesButton.Name = "checkUpdates";
        checkUpdatesButton.Click += async delegate { await CheckForUpdatesAsync(true); };
        updateStatus = SettingsLabel("尚未检查更新", 10, false, Muted, "updateStatus");
        var checkRow = new TableLayoutPanel { Name = "updateCheckRow", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = 2, RowCount = 1, Margin = new Padding(0, 0, 0, 12) };
        checkRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); checkRow.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        checkRow.RowStyles.Add(new RowStyle(SizeType.AutoSize)); updateStatus.Anchor = AnchorStyles.Left | AnchorStyles.Right;
        updateStatus.Margin = new Padding(0, 4, 16, 4); checkUpdatesButton.Margin = new Padding(0); checkUpdatesButton.Anchor = AnchorStyles.Right;
        checkRow.Controls.Add(updateStatus, 0, 0); checkRow.Controls.Add(checkUpdatesButton, 1, 0); AddAutoRow(updateCard, checkRow);
        downloadUpdateButton = MakeButton("下载更新", false, 112); downloadUpdateButton.Name = "downloadUpdate";
        downloadUpdateButton.Click += async delegate { await DownloadUpdateAsync(); };
        openUpdateButton = MakeButton("应用热更新", false, 126); openUpdateButton.Name = "openUpdate";
        openUpdateButton.Click += async delegate { await OpenDownloadedVersionAsync(); };
        updateActionRow = ButtonRow(downloadUpdateButton, openUpdateButton); updateActionRow.Name = "updateActions";
        updateActionRow.Margin = new Padding(0, 0, 0, 12); AddAutoRow(updateCard, updateActionRow);
        AddAutoRow(updateCard, SettingsDivider());
        autoCheckUpdates = new SettingToggle { Name = "autoCheckUpdates", Checked = preferences.AutoCheckUpdates,
            AccessibleName = "自动检查更新", Anchor = AnchorStyles.Right };
        autoCheckUpdates.CheckedChanged += delegate { preferences.AutoCheckUpdates = autoCheckUpdates.Checked; SavePreferences(); };
        AddAutoRow(updateCard, SettingRow("自动检查更新", "打开软件时检查，不自动下载或安装。", autoCheckUpdates));
        AddAutoRow(updateCard, SettingsDivider());
        lastUpdateLabel = SettingsLabel(FormatLastCheck(), 9, false, Muted, "lastUpdateCheck"); lastUpdateLabel.Anchor = AnchorStyles.Right;
        AddAutoRow(updateCard, SettingRow("上次检查", "更新渠道：Stable", lastUpdateLabel)); AddAutoRow(stack, updateCard);

        var projectCard = SettingsCard("applicationProjectCard");
        AddSectionHeading(projectCard, "项目", "查看源码仓库与已发布的版本内容。" );
        var repository = MakeButton("GitHub 仓库 ↗", false, 142); repository.Name = "repositoryLink";
        repository.Click += delegate { OpenProjectLink(AppInfo.Repository); };
        var releases = MakeButton("发布与更新内容 ↗", false, 174); releases.Name = "releaseLink";
        releases.Click += delegate { OpenProjectLink(release == null ? AppInfo.Releases : release.PageUrl); };
        AddAutoRow(projectCard, ButtonRow(repository, releases)); AddAutoRow(stack, projectCard);
    }

    private void BuildFeatureSettings() {
        var stack = SettingsStack(featurePage);
        var navigationCard = SettingsCard("featureNavigationCard");
        AddSectionHeading(navigationCard, "链接导航", "控制外部链接在 Lucanet 客户端中的打开方式。" );
        bool enabled = true;
        try { enabled = settingsStore.Features.LoadLinksInNewWindow(); } catch (IOException) { enabled = false; } catch (UnauthorizedAccessException) { enabled = false; }
        linksInNewWindow = new SettingToggle { Name = "linksInNewWindow", Checked = enabled,
            AccessibleName = "链接在新窗口中打开", Anchor = AnchorStyles.Right };
        linksInNewWindow.CheckedChanged += delegate {
            if (restoringLinkSetting) return;
            try { settingsStore.Features.SaveLinksInNewWindow(linksInNewWindow.Checked); }
            catch (Exception e) {
                restoringLinkSetting = true; linksInNewWindow.Checked = !linksInNewWindow.Checked; restoringLinkSetting = false;
                ShowMessage("功能设置未保存，已保留原开关状态：" + e.Message, MessageBoxIcon.Warning);
            }
        };
        AddAutoRow(navigationCard, SettingRow("链接在新窗口中打开", "ln:goto/LNFC 的 OID 链接先新建窗口再跳转。切换后下一次链接立即生效。", linksInNewWindow));
        AddAutoRow(stack, navigationCard);
        var scriptCard = SettingsCard("featureScriptCard");
        AddSectionHeading(scriptCard, "脚本运行", "控制脚本运行弹窗中的开始按钮和快捷键，已打开的弹窗也会实时更新。");
        try { scriptPreferences = settingsStore.Features.LoadScript(); }
        catch (IOException) { scriptPreferences = new ScriptFeatureSettings(false,"ALT+R"); }
        catch (UnauthorizedAccessException) { scriptPreferences = new ScriptFeatureSettings(false,"ALT+R"); }
        hideScriptStart = new SettingToggle { Name="hideScriptStart", Checked=scriptPreferences.HideStart,
            AccessibleName="隐藏开始按钮", Anchor=AnchorStyles.Right };
        hideScriptStart.CheckedChanged += delegate { SaveScriptSettings(); };
        AddAutoRow(scriptCard,SettingRow("隐藏“开始”按钮", "仅改变按钮显示，不自动运行；关闭后恢复原按钮。",hideScriptStart));
        AddAutoRow(scriptCard,SettingsDivider());
        scriptShortcut = new ShortcutBox { Shortcut=scriptPreferences.Shortcut, Anchor=AnchorStyles.Right };
        var shortcutHint = SettingsLabel("点击输入框后按组合键。默认 Alt + R，仅对当前脚本弹窗生效。",9,false,Muted,"scriptShortcutHint");
        scriptShortcut.ShortcutRejected += message => { shortcutHint.Text=message; shortcutHint.ForeColor=Amber; };
        scriptShortcut.ShortcutChanged += delegate { if(SaveScriptSettings()) { shortcutHint.Text="快捷键已保存，打开的脚本弹窗立即使用新设置。";shortcutHint.ForeColor=Muted; } };
        AddAutoRow(scriptCard,SettingRow("开始快捷键", "支持 Ctrl / Alt + 字母、数字或 F1–F12，也可组合 Shift。",scriptShortcut));
        shortcutHint.Margin=new Padding(0,8,0,0);AddAutoRow(scriptCard,shortcutHint);
        AddAutoRow(stack,scriptCard);
        var card = SettingsCard("featureRefreshCard");
        AddSectionHeading(card, "状态刷新", "配置工作区的状态检查频率，设置立即生效。" );
        autoRefresh = new SettingToggle { Name = "autoRefresh", Checked = preferences.AutoRefresh,
            AccessibleName = "自动刷新", Anchor = AnchorStyles.Right };
        autoRefresh.CheckedChanged += delegate { SaveRefreshPreferences(); };
        AddAutoRow(card, SettingRow("自动刷新", "静默检查安装和运行状态，不打断当前操作。", autoRefresh));
        AddAutoRow(card, SettingsDivider());
        var interval = new RefreshIntervalControl(preferences.RefreshSeconds);
        refreshSeconds = interval.Seconds;
        refreshSeconds.ValueChanged += delegate { SaveRefreshPreferences(); };
        AddAutoRow(card, SettingRow("刷新间隔", "2–300 秒，默认 8 秒；关闭自动刷新后仍可手动检查。", interval));
        AddAutoRow(stack, card);
        var reset = MakeButton("恢复功能默认设置", false, 170); reset.Name = "resetFeatureSettings";
        reset.Click += delegate { autoRefresh.Checked = true; refreshSeconds.Value = 8; linksInNewWindow.Checked = true;
            hideScriptStart.Checked=true;scriptShortcut.Shortcut="ALT+R";SaveScriptSettings();SaveRefreshPreferences(); };
        AddAutoRow(stack, ButtonRow(reset));
    }

    private bool SaveScriptSettings() {
        if (restoringScriptSettings || scriptShortcut == null) return false;
        var next = new ScriptFeatureSettings(hideScriptStart.Checked,scriptShortcut.Shortcut);
        try { settingsStore.Features.SaveScript(next); scriptPreferences=next; return true; }
        catch (Exception e) {
            restoringScriptSettings=true;hideScriptStart.Checked=scriptPreferences.HideStart;scriptShortcut.Shortcut=scriptPreferences.Shortcut;restoringScriptSettings=false;
            ShowMessage("脚本设置未保存，已恢复原设置："+e.Message,MessageBoxIcon.Warning);
            return false;
        }
    }

    private Control SettingRow(string title, string description, Control value) {
        var row = new TableLayoutPanel { Name = value.Name + "SettingRow", AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink,
            ColumnCount = 2, RowCount = 1, Margin = new Padding(0), Padding = new Padding(0, 13, 0, 13) };
        row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100)); row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        row.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var text = VerticalStack(); text.Dock = DockStyle.Top; text.Margin = new Padding(0, 0, 28, 0);
        AddAutoRow(text, SettingsLabel(title, 11, false, Ink));
        var detail = SettingsLabel(description, 9, false, Muted); detail.Margin = new Padding(0, 7, 0, 0);
        AddAutoRow(text, detail);
        value.Anchor = AnchorStyles.Right; value.Margin = new Padding(0);
        row.Controls.Add(text, 0, 0); row.Controls.Add(value, 1, 0); return row;
    }

    private Button MakeButton(string text,bool primary,int width) {
        var b=new Button { Text=text,Width=width,Height=36,FlatStyle=FlatStyle.Flat,BackColor=primary?Purple:Color.White,ForeColor=primary?Color.White:Navy,Cursor=Cursors.Hand,Margin=new Padding(0,0,10,0),UseVisualStyleBackColor=false };
        b.FlatAppearance.BorderColor=primary?Purple:Color.FromArgb(207,216,224);b.FlatAppearance.MouseOverBackColor=primary?Color.FromArgb(133,53,196):Color.FromArgb(245,238,252);return b;
    }
    private void StyleTab(Button button,bool selected){if(button==null)return;button.BackColor=selected?Color.FromArgb(239,229,249):Color.White;button.ForeColor=selected?Purple:Navy;button.FlatAppearance.BorderColor=selected?Purple:Color.FromArgb(218,225,232);}
    private TableLayoutPanel MakeCard(string title,string name,out Label value,out Label detail) {
        var card=new TableLayoutPanel { Dock=DockStyle.Fill,BackColor=Color.White,ColumnCount=1,RowCount=3,Padding=new Padding(16,15,12,12),Margin=new Padding(0,0,12,0) };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));card.RowStyles.Add(new RowStyle(SizeType.Absolute,25));card.RowStyles.Add(new RowStyle(SizeType.Absolute,40));card.RowStyles.Add(new RowStyle(SizeType.Percent,100));
        card.Controls.Add(new Label { Text=title,ForeColor=Muted,AutoSize=true },0,0);
        value=new Label { Name=name,Text="待检查",Font=new Font(Font.FontFamily,17,FontStyle.Bold),ForeColor=Muted,Dock=DockStyle.Fill,AutoEllipsis=true };
        detail=new Label { Text="",ForeColor=Muted,Dock=DockStyle.Fill,AutoEllipsis=true };card.Controls.Add(value,0,1);card.Controls.Add(detail,0,2);return card;
    }

    private void SavePreferences(){try{settingsStore.Save(preferences);}catch(Exception e){ShowMessage("设置已生效，但无法保存："+e.Message,MessageBoxIcon.Warning);}}
    private string FormatLastCheck(){DateTimeOffset value;return DateTimeOffset.TryParse(preferences.LastUpdateCheck,out value)?value.LocalDateTime.ToString("yyyy-MM-dd HH:mm"):"尚未检查";}
    internal async Task CheckForUpdatesAsync(bool manual) {
        if(updateChecking||updateDownloading||updateInstalling||foregroundBusy||closing||relaunching)return;
        updateChecking=true;downloadedDirectory=null;updateStatus.Text="正在检查 GitHub Releases…";updateStatus.ForeColor=Muted;UpdateUpdateButtons();
        try{
            var result=await updateService.CheckAsync();if(closing)return;release=result;
            preferences.LastUpdateCheck=DateTimeOffset.Now.ToString("o");settingsStore.Save(preferences);lastUpdateLabel.Text=FormatLastCheck();
            switch(result.State){
                case UpdateState.Available:updateStatus.Text="发现新版本 v"+result.Version+(result.CanDownload?"":" · 请查看发布页面");updateStatus.ForeColor=Purple;break;
                case UpdateState.Current:updateStatus.Text="已是最新稳定版本 · v"+result.Version;updateStatus.ForeColor=Green;break;
                case UpdateState.Ahead:updateStatus.Text="本地 v"+AppInfo.Version+" 高于 GitHub v"+result.Version;updateStatus.ForeColor=Muted;break;
                default:updateStatus.Text="仓库尚无稳定 Release";updateStatus.ForeColor=Muted;break;
            }
        }catch(Exception e){if(!closing){release=null;updateStatus.Text=e.Message;updateStatus.ForeColor=Amber;tips.SetToolTip(updateStatus,e.Message);}}
        finally{updateChecking=false;if(!closing)UpdateUpdateButtons();}
    }
    private async Task DownloadUpdateAsync() {
        if(release==null||!release.CanDownload||updateDownloading||foregroundBusy||updateInstalling||closing||relaunching)return;
        updateDownloading=true;UpdateUpdateButtons();
        try{
            var progress=new Progress<int>(p=>{if(!closing)updateStatus.Text="正在下载更新… "+p+"%";});
            downloadedDirectory=await updateService.DownloadAsync(release,progress);if(closing)return;
            updateStatus.Text="更新已下载并通过 SHA-256 校验";updateStatus.ForeColor=Green;
        }catch(Exception e){if(!closing){updateStatus.Text=e.Message;updateStatus.ForeColor=Amber;tips.SetToolTip(updateStatus,e.Message);}}
        finally{updateDownloading=false;if(!closing)UpdateUpdateButtons();}
    }
    private async Task OpenDownloadedVersionAsync() {
        if(String.IsNullOrEmpty(downloadedDirectory)||closing||foregroundBusy)return;
        if(MessageBox.Show(this,"将应用已校验的新版本，并切换到新版管理界面。已安装 v1.0.1 及以上兼容版本的客户端可保持登录；旧版本会提示正常关闭后安装。", "应用热更新",MessageBoxButtons.OKCancel,MessageBoxIcon.Information)!=DialogResult.OK)return;
        updateInstalling=true;requestGeneration++;poll.Stop();UpdateButtons();
        try{
            string candidateVersion = FileVersionInfo.GetVersionInfo(Path.Combine(downloadedDirectory,"enhancement.exe")).ProductVersion;
            if (GitHubUpdateService.ParseVersion(candidateVersion) != GitHubUpdateService.ParseVersion(release.Version))
                throw new InvalidDataException("更新包内的软件版本与发布版本不一致，已停止更新。");
            // 使用下载包自己的后台服务，更新已安装 Agent 及当前 JVM 后才交接 GUI。
            // 没有选中安装目录时只切换 GUI；不会猜测并修改其他安装。
            if (!String.IsNullOrWhiteSpace(directories.Text)) {
                var nextService = new PowerShellEnhancementService(downloadedDirectory);
                await nextService.InvokeAsync("update", directories.Text.Trim());
            }
            var process=Process.Start(new ProcessStartInfo(Path.Combine(downloadedDirectory,"enhancement.exe")){UseShellExecute=true,WorkingDirectory=downloadedDirectory,Arguments=String.IsNullOrWhiteSpace(directories.Text)?"":"--directory "+GuiData.Quote(directories.Text)});
            bool ready=await Task.Run(delegate{using(process){if(process==null)return false;var watch=Stopwatch.StartNew();while(watch.ElapsedMilliseconds<15000){if(process.HasExited)return false;process.Refresh();if(process.MainWindowHandle!=IntPtr.Zero)return true;System.Threading.Thread.Sleep(100);}return false;}});
            if(ready){allowRelaunchClose=true;Close();}else ShowMessage("新版本未能启动，当前窗口继续保留。",MessageBoxIcon.Warning);
        }catch(Exception e){ShowMessage(e.Message,MessageBoxIcon.Error);}
        finally{updateInstalling=false;if(!closing){ConfigurePolling();UpdateButtons();}}
    }
    private void UpdateUpdateButtons(){
        if(checkUpdatesButton==null)return;bool available=!updateChecking&&!updateDownloading&&!updateInstalling&&!foregroundBusy&&!relaunching&&!closing;
        checkUpdatesButton.Enabled=available;autoCheckUpdates.Enabled=available;
        downloadUpdateButton.Visible=release!=null&&release.CanDownload;downloadUpdateButton.Enabled=available&&release!=null&&release.CanDownload;
        openUpdateButton.Visible=!String.IsNullOrEmpty(downloadedDirectory);openUpdateButton.Enabled=available&&!String.IsNullOrEmpty(downloadedDirectory);
        updateActionRow.Visible=(release!=null&&release.CanDownload)||!String.IsNullOrEmpty(downloadedDirectory);
    }
    private void OpenProjectLink(string url){try{Process.Start(new ProcessStartInfo(url){UseShellExecute=true});}catch(Exception e){ShowMessage(e.Message,MessageBoxIcon.Error);}}
}
