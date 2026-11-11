package com.lucanet.lisa.widgets.treetable;
public final class LNTreeTableCell {
    private final Object node;
    private final int column;
    public LNTreeTableCell(Object node){this(node,0);}
    public LNTreeTableCell(Object node,int column){this.node=node;this.column=column;}
    public Object getNode(){return node;}
    public int getColumn(){return column;}
}
