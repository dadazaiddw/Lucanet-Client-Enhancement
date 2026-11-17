package com.lucanet.common.dom.dashboard.elements.ui.dashboardbuilder;

import java.awt.*;
import javax.swing.*;
import com.lucanet.lisa.widgets.LNPanel;
import com.lucanet.lisa.widgets.perspective.ResizePanel;
import ln.ui_x.dialog.XDialog;

/** Mirrors only the verified assembly signature and component ancestry, without business execution. */
public final class SceneDialogCreator {
    private final LNPanel a=new LNPanel();
    public final JPanel title=new JPanel();
    public final ResizePanel resize=new ResizePanel();
    public final JTextField input=new JTextField("unsaved input");
    public final c close=new c();
    public final TestDialog dialog;
    public int cancels;
    public SceneDialogCreator(boolean modal) {
        this(null,modal);
    }
    public SceneDialogCreator(Window owner,boolean modal) {
        dialog=new TestDialog(owner,modal);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setUndecorated(true);dialog.setResizable(false);
        a.setLayout(new BorderLayout());dialog.setContentPane(a);
        close.addActionListener(event->{cancels++;dialog.dispose();});
        a(null);
        dialog.setBounds(-18000,-18000,440,240);
    }
    private void a(XDialog ignored) {
        // Locals and branches exercise stack-map verification at the inserted RETURN hook.
        JPanel content=new JPanel(new BorderLayout());
        if(input.getText().isEmpty())content.setName("empty");
        else content.setName("input");
        title.add(close);title.setBackground(Color.MAGENTA);
        content.add(input);resize.add(title,BorderLayout.NORTH);resize.add(content,BorderLayout.CENTER);
        a.add(resize);
    }
    public static final class c extends JButton { public c(){super("original close");} }
    public static final class TestDialog extends JDialog {
        public int shows,disposes,showsFinished;
        public TestDialog(Window owner,boolean modal){super(owner,"scene fixture",modal?ModalityType.DOCUMENT_MODAL:ModalityType.MODELESS);setType(Window.Type.UTILITY);}
        @SuppressWarnings("deprecation") @Override public void show(){shows++;super.show();showsFinished++;}
        @Override public void dispose(){disposes++;super.dispose();}
    }
}
