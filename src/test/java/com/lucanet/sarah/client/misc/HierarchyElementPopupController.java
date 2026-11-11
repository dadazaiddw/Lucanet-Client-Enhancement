package com.lucanet.sarah.client.misc;
import com.lucanet.lisa.ui.UIContext;
import com.lucanet.lisa.widgets.treetable.LNTreeTableCell;
public class HierarchyElementPopupController {
    private final UIContext context;
    public int originalDoubleClicks,originalOtherEvents;
    public HierarchyElementPopupController(UIContext context){this.context=context;}
    protected final UIContext getUIContext(){return context;}
    protected boolean handlePostSelectionDoubleClick(LNTreeTableCell cell){originalDoubleClicks++;return false;}
    public boolean interact(int event,LNTreeTableCell cell){
        if(event==4)return handlePostSelectionDoubleClick(cell);
        originalOtherEvents++;return false;
    }
}
