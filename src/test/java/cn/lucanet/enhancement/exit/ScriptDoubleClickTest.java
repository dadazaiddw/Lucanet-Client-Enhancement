package cn.lucanet.enhancement.exit;

import java.awt.EventQueue;
import java.nio.file.*;
import java.util.concurrent.*;
import com.lucanet.identifiable.shared.datamodel.IdentifiableView;
import com.lucanet.lisa.applicationframe.UIContainer;
import com.lucanet.lisa.applicationframe.context.DetailPanelContext;
import com.lucanet.lisa.widgets.treetable.LNTreeTableCell;
import com.lucanet.sarah.client.misc.HierarchyElementPopupController;
import com.lucanet.summa.client.sections.script.controllers.ScriptPopupController;
import com.lucanet.summa.client.sections.script.panels.ScriptGeneralPanel;

public final class ScriptDoubleClickTest {
    private static final String SCRIPT="com.lucanet.summa.database.model.script.ScriptNode";
    public static void main(String[] args)throws Exception {
        Path settings=Path.of(System.getProperty("lucanet.enhancement.features"));Files.createDirectories(settings.getParent());Files.deleteIfExists(settings);
        Context context=new Context(new UIContainer(),new IdentifiableView(2466470,SCRIPT));
        ScriptPopupController controller=edt(()->new ScriptPopupController(context));
        edt(()->null); // install the runtime hook before the native panel initializes
        ScriptGeneralPanel panel=edt(()->{ScriptGeneralPanel value=new ScriptGeneralPanel(context);value.initGeneralPanel(null);return value;});
        edt(()->null);
        LNTreeTableCell script=new LNTreeTableCell(context.current);
        check(edt(()->controller.interact(4,script)) && panel.openedDialogs==1,"double-click opens original run dialog");
        check(panel.startedScripts==0,"double-click never invokes start");
        Files.writeString(settings,"scriptDoubleClick=false\n");
        check(!edt(()->controller.interact(4,script)) && panel.openedDialogs==1 && controller.originalDoubleClicks==1,"disabled immediately, original tree handling retained");
        Files.writeString(settings,"scriptDoubleClick=true\n");
        check(edt(()->controller.interact(4,script)) && panel.openedDialogs==2,"re-enabled immediately in same client");
        edt(()->{controller.interact(3,script);controller.interact(7,script);return null;});
        check(panel.openedDialogs==2 && controller.originalOtherEvents==2,"single-click and Enter unchanged");
        check(!edt(()->controller.interact(4,new LNTreeTableCell(new IdentifiableView(20,"com.lucanet.summa.database.model.script.ScriptFolder")))) && panel.openedDialogs==2,"folder unchanged");
        check(!edt(()->controller.interact(4,new LNTreeTableCell(new IdentifiableView(30,SCRIPT)))) && panel.openedDialogs==2,"mismatched node cannot use stale details");
        edt(()->{panel.runButton().setEnabled(false);return null;});
        check(!edt(()->controller.interact(4,script)) && panel.openedDialogs==2,"native disabled state respected");
        edt(()->{panel.runButton().setEnabled(true);panel.showing=false;return null;});
        check(!edt(()->controller.interact(4,script)),"background/hidden panel not clicked");
        edt(()->{panel.showing=true;panel.fail=true;return null;});
        check(!edt(()->controller.interact(4,script)) && panel.openedDialogs==2,"native failure falls back without opening wrong dialog");
        edt(()->{panel.fail=false;return null;});
        HierarchyElementPopupController unrelated=edt(()->new HierarchyElementPopupController(context));
        check(!edt(()->unrelated.interact(4,script)) && unrelated.originalDoubleClicks==1,"other controllers unchanged even for same node");
        Context another=new Context(new UIContainer(),context.current);
        ScriptPopupController otherWindow=edt(()->new ScriptPopupController(another));
        check(!edt(()->otherWindow.interact(4,script)),"different client window/container cannot execute stale action");
        check(!controller.interact(4,script) && panel.openedDialogs==2,"off-EDT call does not execute UI");
        edt(()->{panel.runButton().doClick(0);return null;});
        check(panel.openedDialogs==3,"original mouse run still works");
        edt(()->{panel.showing=false;context.visibleDetail=new javax.swing.JPanel(){public boolean isShowing(){return true;}};return null;});
        check(edt(()->controller.interact(4,script)) && panel.openedDialogs==4,"hidden general tab inside active details still opens dialog");
        edt(()->{panel.showing=true;panel.putClientProperty("cn.lucanet.enhancement.script.doubleClick.button",null);panel.putClientProperty("cn.lucanet.enhancement.script.doubleClick.context",null);
            ((java.util.function.Consumer<Object>)javax.swing.UIManager.get("cn.lucanet.enhancement.script.doubleClick.register")).accept(panel);return null;});
        check(edt(()->controller.interact(4,script)) && panel.openedDialogs==5,"hot-update backfill handles panels initialized before hook registration");
        System.out.println("PASS: script double-click opens original dialog only; instant on/off, exact node/window, native guards, folder/single/Enter isolation and failure fallback");
    }
    public static final class Context implements DetailPanelContext {
        final UIContainer container;IdentifiableView current;java.awt.Component visibleDetail;
        Context(UIContainer container,IdentifiableView current){this.container=container;this.current=current;}
        public UIContainer getUiContainer(){return container;}
        public Object getCurrentExplorerNode(){return current;}
        public java.awt.Component getDetailPanel(){return visibleDetail;}
    }
    private static <T>T edt(Callable<T> call)throws Exception{FutureTask<T> task=new FutureTask<>(call);EventQueue.invokeAndWait(task);return task.get();}
    private static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
