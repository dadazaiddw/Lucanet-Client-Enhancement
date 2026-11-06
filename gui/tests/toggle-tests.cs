using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

internal static class ToggleTests {
    internal static void Verify(string work) {
        Directory.CreateDirectory(work);
        foreach (float scale in new[] {1F, 1.25F, 1.5F, 1.75F, 2F}) {
            using (var host = new Form { StartPosition = FormStartPosition.Manual, Location = new Point(-20000, -20000),
                    ShowInTaskbar = false, AutoScaleMode = AutoScaleMode.None, ClientSize = new Size(240,100), BackColor = Color.White })
            using (var toggle = new ProbeToggle { Location = new Point(12,12), Size = new Size((int)(48*scale),(int)(26*scale)),
                    BackColor = Color.FromArgb(149,71,211), AccessibleName = "测试开关" }) {
                var other = new Button { Location = new Point(120,12), Text = "Other", TabIndex = 1 };
                host.Controls.Add(toggle); host.Controls.Add(other); host.Show(); Application.DoEvents(); other.Focus();
                int changed = 0; toggle.CheckedChanged += delegate { changed++; };
                using (var sheet = new Bitmap(560,210)) using (var graphics = Graphics.FromImage(sheet)) {
                    graphics.Clear(Color.White);
                    int column = 0;
                    using (var off = Render(toggle)) {
                        AssertClean(off, Color.White, "off " + scale); AssertKnob(off, false, scale);
                        DrawSample(graphics, off, "关闭", column++);
                    }
                    toggle.EnterMouse();
                    using (var hover = Render(toggle)) { AssertClean(hover, Color.White, "hover " + scale); DrawSample(graphics, hover, "悬停", column++); }
                    toggle.DownMouse();
                    using (var pressed = Render(toggle)) { AssertClean(pressed, Color.White, "pressed " + scale); DrawSample(graphics, pressed, "按下", column++); }
                    toggle.UpMouse(); toggle.LeaveMouse();
                    toggle.AccessibilityObject.DoDefaultAction();
                    Assert(toggle.Checked && changed == 1, "accessibility click toggles once");
                    Assert((toggle.AccessibilityObject.State & AccessibleStates.Checked) != 0, "checked accessibility state");
                    using (var on = Render(toggle)) { AssertClean(on, Color.White, "on " + scale); AssertKnob(on, true, scale); DrawSample(graphics, on, "开启", column++); }
                    toggle.Focus(); toggle.ForceFocusCue = true;
                    using (var focused = Render(toggle)) { AssertClean(focused, Color.White, "focus " + scale); DrawSample(graphics, focused, "键盘焦点", column++); }
                    toggle.Space();
                    Assert(!toggle.Checked && changed == 2, "space key toggles once");
                    toggle.ForceFocusCue = false; other.Focus();
                    for (int i=0; i<12; i++) { toggle.Checked = !toggle.Checked; using (var frame = Render(toggle)) AssertClean(frame, Color.White, "repaint " + scale); }
                    toggle.Enabled = false;
                    using (var disabled = Render(toggle)) { AssertClean(disabled, Color.White, "disabled " + scale); DrawSample(graphics, disabled, "禁用", column++); }
                    host.BackColor = Color.FromArgb(246,248,250);
                    using (var themed = Render(toggle)) AssertClean(themed, host.BackColor, "parent surface " + scale);
                    sheet.Save(Path.Combine(work,"toggle-states-"+(int)(scale*100)+".png"));
                }
                host.Close();
            }
        }
        Console.WriteLine("PASS: toggle off/on/hover/pressed/focus/disabled repaint at 100%-200%, clean parent-colored corners, accessibility and Space key");
    }
    private static Bitmap Render(Control control) {
        Application.DoEvents();
        var image = new Bitmap(control.Width,control.Height);
        control.DrawToBitmap(image,new Rectangle(Point.Empty,image.Size)); return image;
    }
    private static void AssertClean(Bitmap image, Color background, string name) {
        foreach (var point in new[] { new Point(0,0),new Point(image.Width-1,0),new Point(0,image.Height-1),new Point(image.Width-1,image.Height-1),new Point(1,1) })
            Assert(image.GetPixel(point.X,point.Y).ToArgb() == background.ToArgb(), name + ": rectangular background/focus residue");
    }
    private static void AssertKnob(Bitmap image,bool on,float scale) {
        int x = (int)(on ? image.Width-13*scale : 13*scale);
        Color color = image.GetPixel(x,image.Height/2);
        Assert(color.R>240 && color.G>240 && color.B>240,"white knob on correct side");
        color=image.GetPixel(image.Width/2,image.Height/2);
        Assert(on ? color.R>color.G+35 && color.B>color.G+35 : Math.Abs(color.B-color.R)<40,"track matches state");
    }
    private static void DrawSample(Graphics g,Bitmap image,string title,int index) {
        int x=16+(index%3)*180,y=10+(index/3)*100;
        using(var font=new Font("Microsoft YaHei UI",9))g.DrawString(title,font,Brushes.Black,x,y);
        g.DrawImageUnscaled(image,x,y+26);
    }
    private static void Assert(bool value,string message) { if(!value)throw new Exception(message); }
    private sealed class ProbeToggle : SettingToggle {
        internal bool ForceFocusCue;
        protected override bool ShowFocusCues { get { return ForceFocusCue; } }
        internal void EnterMouse() { OnMouseEnter(EventArgs.Empty); }
        internal void LeaveMouse() { OnMouseLeave(EventArgs.Empty); }
        internal void DownMouse() { OnMouseDown(new MouseEventArgs(MouseButtons.Left,1,Width/2,Height/2,0)); }
        internal void UpMouse() { OnMouseUp(new MouseEventArgs(MouseButtons.Left,1,Width/2,Height/2,0)); }
        internal void Space() { OnKeyDown(new KeyEventArgs(Keys.Space)); OnKeyUp(new KeyEventArgs(Keys.Space)); }
    }
}
