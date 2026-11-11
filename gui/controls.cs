using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

// 设置行的开关：保留 CheckBox 键盘/辅助功能语义，紫色与 Lucanet 主操作一致。
internal class SettingToggle : CheckBox {
    private bool hovered, pressed;
    internal SettingToggle() {
        AutoSize = false; Size = new Size(48, 26); MinimumSize = new Size(48, 26);
        Cursor = Cursors.Hand; UseVisualStyleBackColor = false; FlatStyle = FlatStyle.Flat;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }

    private Color SurfaceColor {
        get {
            // 原生 CheckBox 的 BackColor/主题背景可能带入紫色矩形，圆角外必须与设置行一致。
            for (Control parent = Parent; parent != null; parent = parent.Parent)
                if (parent.BackColor.A == 255) return parent.BackColor;
            return SystemColors.Window;
        }
    }

    protected override void OnPaintBackground(PaintEventArgs e) {
        using (var background = new SolidBrush(SurfaceColor)) e.Graphics.FillRectangle(background, ClientRectangle);
    }

    protected override void OnPaint(PaintEventArgs e) {
        // 不调用 CheckBox/ButtonBase 的绘制，避免主题背景、原生焦点框或旧帧留在圆角外。
        OnPaintBackground(e);
        GraphicsState state = e.Graphics.Save();
        try {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            float scale = Math.Max(1F, Math.Min(Width / 48F, Height / 26F));
            float pad = 3F * scale;
            var track = new RectangleF(pad, pad, Math.Max(1F, Width - pad * 2), Math.Max(1F, Height - pad * 2));
            Color color = !Enabled ? (Checked ? Color.FromArgb(200, 182, 219) : Color.FromArgb(220, 225, 231))
                : Checked ? (pressed ? Color.FromArgb(120, 47, 182) : hovered ? Color.FromArgb(137, 59, 201) : Color.FromArgb(149, 71, 211))
                : pressed ? Color.FromArgb(135, 148, 163) : hovered ? Color.FromArgb(158, 171, 186) : Color.FromArgb(176, 186, 197);
            using (var path = Capsule(track)) using (var brush = new SolidBrush(color)) e.Graphics.FillPath(brush, path);

            float inset = 3F * scale, diameter = Math.Max(1F, track.Height - inset * 2);
            float x = Checked ? track.Right - inset - diameter : track.Left + inset;
            using (var brush = new SolidBrush(Enabled ? Color.White : Color.FromArgb(247, 248, 250)))
                e.Graphics.FillEllipse(brush, x, track.Top + inset, diameter, diameter);

            // 焦点使用轨道外侧的细圆角描边，仅键盘导航时显示，不再画矩形虚线框。
            if (Enabled && Focused && ShowFocusCues) {
                var focus = track; focus.Inflate(1.5F * scale, 1.5F * scale);
                using (var path = Capsule(focus)) using (var pen = new Pen(Color.FromArgb(149, 71, 211), Math.Max(1F, scale)))
                    e.Graphics.DrawPath(pen, path);
            }
        } finally { e.Graphics.Restore(state); }
    }

    private static GraphicsPath Capsule(RectangleF bounds) {
        var path = new GraphicsPath();
        float diameter = Math.Min(bounds.Width, bounds.Height);
        path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 90, 180);
        path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 180);
        path.CloseFigure(); return path;
    }

    protected override void OnMouseEnter(EventArgs e) { base.OnMouseEnter(e); hovered = true; Invalidate(); }
    protected override void OnMouseLeave(EventArgs e) { base.OnMouseLeave(e); hovered = false; pressed = false; Invalidate(); }
    protected override void OnMouseDown(MouseEventArgs e) { base.OnMouseDown(e); if (e.Button == MouseButtons.Left && Enabled) pressed = true; Invalidate(); }
    protected override void OnMouseUp(MouseEventArgs e) { base.OnMouseUp(e); pressed = false; Invalidate(); }
    protected override void OnKeyDown(KeyEventArgs e) { base.OnKeyDown(e); if (e.KeyCode == Keys.Space && Enabled) { pressed = true; Invalidate(); } }
    protected override void OnKeyUp(KeyEventArgs e) { base.OnKeyUp(e); pressed = false; Invalidate(); }
    protected override void OnMouseCaptureChanged(EventArgs e) { base.OnMouseCaptureChanged(e); if (!Capture) { pressed = false; Invalidate(); } }
    protected override void OnCheckedChanged(EventArgs e) { base.OnCheckedChanged(e); Invalidate(); }
    protected override void OnEnabledChanged(EventArgs e) {
        base.OnEnabledChanged(e);
        if (!Enabled) { hovered = false; pressed = false; }
        Invalidate();
    }
    protected override void OnGotFocus(EventArgs e) { base.OnGotFocus(e); Invalidate(); }
    protected override void OnLostFocus(EventArgs e) { base.OnLostFocus(e); pressed = false; Invalidate(); }
    protected override void OnChangeUICues(UICuesEventArgs e) { base.OnChangeUICues(e); Invalidate(); }
    protected override void OnParentBackColorChanged(EventArgs e) { base.OnParentBackColorChanged(e); Invalidate(); }
}

internal sealed class AppMark : Control {
    private readonly Icon clientIcon = ClientIcon.Load();
    internal AppMark() {
        Size = new Size(72,72);
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }
    public override Size GetPreferredSize(Size proposedSize) {
        // 父布局必须为完整正方形预留高度，不能按单行文字高度裁剪图标。
        int edge = Math.Max(Width, Height);
        return new Size(edge, edge);
    }
    protected override void OnPaint(PaintEventArgs e) {
        int edge = Math.Min(ClientSize.Width, ClientSize.Height);
        e.Graphics.DrawIcon(clientIcon, new Rectangle((ClientSize.Width-edge)/2, (ClientSize.Height-edge)/2, edge, edge));
    }
    protected override void Dispose(bool disposing) { if (disposing) clientIcon.Dispose(); base.Dispose(disposing); }
}

internal static class ClientIcon {
    internal static Icon Load() {
        using (var stream = typeof(ClientIcon).Assembly.GetManifestResourceStream("Lucanet.ClientIcon")) {
            if (stream == null) return (Icon)SystemIcons.Application.Clone();
            using (var icon = new Icon(stream, 32, 32)) return (Icon)icon.Clone();
        }
    }
}

// 避免 NumericUpDown 的 PreferredSize 与 TableLayoutPanel.AutoSize/双向 Anchor 相互反馈。
// 每次布局只从字体计算尺寸，不读取上一次被父容器拉伸后的宽度。
internal sealed class RefreshIntervalControl : Panel {
    internal NumericUpDown Seconds { get; private set; }
    private readonly Label unit;
    internal RefreshIntervalControl(int value) {
        Name = "refreshInterval"; AutoSize = true; AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Margin = new Padding(0); Padding = new Padding(0); Anchor = AnchorStyles.Right;
        Seconds = new NumericUpDown { Name = "refreshSeconds", Minimum = GuiSettingsStore.MinimumSeconds,
            Maximum = GuiSettingsStore.MaximumSeconds, Value = value, TextAlign = HorizontalAlignment.Center,
            Margin = new Padding(0), Anchor = AnchorStyles.Top | AnchorStyles.Left, AccessibleName = "刷新间隔（秒）" };
        unit = new Label { Name = "refreshUnit", Text = "秒", AutoSize = true,
            Margin = new Padding(0), ForeColor = Color.FromArgb(21,43,57), Anchor = AnchorStyles.Top | AnchorStyles.Left };
        Controls.Add(Seconds); Controls.Add(unit);
    }
    private Size InputSize() {
        float scale = Font.Height / 17F;
        int textWidth = TextRenderer.MeasureText("300", Font, Size.Empty, TextFormatFlags.NoPadding | TextFormatFlags.SingleLine).Width;
        return new Size(Math.Max((int)Math.Ceiling(64 * scale), textWidth + (int)Math.Ceiling(30 * scale)), Seconds.PreferredHeight);
    }
    private int Gap { get { return Math.Max(4, (int)Math.Round(4 * Font.Height / 17F)); } }
    public override Size GetPreferredSize(Size proposedSize) {
        if (Seconds == null || unit == null) return base.GetPreferredSize(proposedSize);
        Size input = InputSize(), label = unit.GetPreferredSize(Size.Empty);
        return new Size(input.Width + Gap + label.Width, Math.Max(input.Height, label.Height));
    }
    protected override void OnLayout(LayoutEventArgs e) {
        base.OnLayout(e);
        if (Seconds == null || unit == null) return;
        Size input = InputSize(), label = unit.GetPreferredSize(Size.Empty);
        int rowHeight = Math.Max(input.Height, label.Height);
        Seconds.SetBounds(0, (rowHeight-input.Height)/2, input.Width, input.Height);
        unit.SetBounds(input.Width+Gap, (rowHeight-label.Height)/2, label.Width, label.Height);
    }
    protected override void OnFontChanged(EventArgs e) { base.OnFontChanged(e); PerformLayout(); }
}
