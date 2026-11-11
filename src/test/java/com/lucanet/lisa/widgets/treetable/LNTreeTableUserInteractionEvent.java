package com.lucanet.lisa.widgets.treetable;
public final class LNTreeTableUserInteractionEvent extends java.util.EventObject {
    private final int type;private final LNTreeTableContext context;private boolean consumed;
    public LNTreeTableUserInteractionEvent(int type,LNTreeTableContext context){super(context);this.type=type;this.context=context;}
    public int getType(){return type;}
    public LNTreeTableContext getTreeTableContext(){return context;}
    public void consume(){consumed=true;}
    public boolean isConsumed(){return consumed;}
}
