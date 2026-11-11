package com.lucanet.lisa.applicationframe.section.explorer;
import com.lucanet.lisa.widgets.treetable.LNTreeTableUserInteractionEvent;
import com.lucanet.sarah.client.misc.HierarchyElementPopupController;
public final class Explorer {
    /** 通用 Explorer 包装器；所有专用控制器均从这里分发。 */
    public static final class c {
        private final HierarchyElementPopupController delegate;
        public c(HierarchyElementPopupController delegate){this.delegate=delegate;}
        public void userInteracted(LNTreeTableUserInteractionEvent event){
            if(delegate.interact(event.getType(),event.getTreeTableContext().getInteractedCell()))event.consume();
        }
    }
}
