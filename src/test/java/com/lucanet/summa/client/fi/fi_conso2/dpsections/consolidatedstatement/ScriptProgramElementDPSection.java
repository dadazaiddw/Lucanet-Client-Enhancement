package com.lucanet.summa.client.fi.fi_conso2.dpsections.consolidatedstatement;
import javax.swing.*;
import com.lucanet.lisa.applicationframe.context.DetailPanelContext;
public final class ScriptProgramElementDPSection extends JPanel {
    private final JButton run=new JButton("运行");private final DetailPanelContext context;public long modelOID;
    public int openedDialogs;public boolean showing=true;
    public ScriptProgramElementDPSection(DetailPanelContext context,long oid){this.context=context;modelOID=oid;add(run);run.addActionListener(event->{
        if(StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES).walk(frames->frames.anyMatch(frame->frame.getClassName().equals("java.lang.reflect.Method"))))throw new IllegalStateException("no reflection allowed!");
        openedDialogs++;
    });}
    public DetailPanelContext getDetailPanelContext(){return context;}
    protected JButton getExecuteButton(){return run;}
    protected long getObjectID(){return modelOID;}
    @Override public boolean isShowing(){return showing;}
    @Override public boolean isDisplayable(){return true;}
}
