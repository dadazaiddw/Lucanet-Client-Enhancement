package com.lucanet.lisa.applicationframe.context;
public interface DetailPanelContext extends com.lucanet.lisa.ui.UIContext {
    Object getCurrentExplorerNode();
    default java.awt.Component getDetailPanel(){return null;}
}
