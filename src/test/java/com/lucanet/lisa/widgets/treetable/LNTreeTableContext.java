package com.lucanet.lisa.widgets.treetable;
public final class LNTreeTableContext {
    private final com.lucanet.lisa.ui.UIContext context;private final LNTreeTableCell cell;
    public LNTreeTableContext(com.lucanet.lisa.ui.UIContext context,LNTreeTableCell cell){this.context=context;this.cell=cell;}
    public com.lucanet.lisa.ui.UIContext getUIContext(){return context;}
    public LNTreeTableCell getInteractedCell(){return cell;}
}
