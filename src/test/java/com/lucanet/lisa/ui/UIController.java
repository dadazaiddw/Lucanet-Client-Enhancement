package com.lucanet.lisa.ui;
import com.lucanet.lisa.applicationframe.UIContainer;
import com.lucanet.lisa.useraction.AbstractUserAction;
public final class UIController {
    public static int originalNavigations, newWindows;
    public static String lastUri;
    public static boolean missingAction, failAction, failBeforeWindow, missingContainer, disabledAction;
    public static UIController getInstance() { return new UIController(); }
    public UIContainer getCurrentUIContainer() { return missingContainer ? null : new UIContainer(); }
    public AbstractUserAction createOpenHyperlinkUserAction(UIContext context, String uri, boolean newWindow) {
        if (!newWindow) { throw new AssertionError("Must request native new-window action"); }
        return missingAction ? null : new AbstractUserAction(uri);
    }
}
