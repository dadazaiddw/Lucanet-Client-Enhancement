package com.lucanet.lisa.ui;
public interface UIContext {
    default com.lucanet.lisa.applicationframe.UIContainer getUiContainer(){return null;}
}
