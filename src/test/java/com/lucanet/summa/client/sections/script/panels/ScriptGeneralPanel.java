package com.lucanet.summa.client.sections.script.panels;

import javax.swing.*;
import com.lucanet.lisa.widgets.LNButton;
import com.lucanet.lisa.applicationframe.context.DetailPanelContext;
import com.lucanet.lisa.applicationframe.section.detailpanel.DPSectionModel;
public final class ScriptGeneralPanel extends JPanel {
    private final LNButton t=new LNButton("运行");
    private final DetailPanelContext context;
    public int openedDialogs,startedScripts;
    public boolean showing=true,fail;
    public ScriptGeneralPanel(DetailPanelContext context){this.context=context;add(t);}
    public void initGeneralPanel(DPSectionModel ignored){t.addActionListener(event->{
        if(StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES).walk(frames->frames.anyMatch(frame->frame.getClassName().equals("java.lang.reflect.Method"))))throw new IllegalStateException("no reflection allowed!");
        if(fail)throw new IllegalStateException("native run refused");
        openedDialogs++;
    });}
    public DetailPanelContext getDetailPanelContext(){return context;}
    public JButton runButton(){return t;}
    @Override public boolean isShowing(){return showing;}
    @Override public boolean isDisplayable(){return true;}
}
