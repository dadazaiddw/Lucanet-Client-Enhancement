package com.lucanet.summa.client.sections.position;
public final class PositionMenuController extends com.lucanet.sarah.client.misc.HierarchyElementPopupController {
    public PositionMenuController(com.lucanet.lisa.ui.UIContext context){super(context);}
    @Override protected boolean handlePostSelectionDoubleClick(com.lucanet.lisa.widgets.treetable.LNTreeTableCell cell){originalDoubleClicks++;return true;}
}
