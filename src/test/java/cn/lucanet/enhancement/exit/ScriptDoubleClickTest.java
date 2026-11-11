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
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Path settings=Path.of(System.getProperty("lucanet.enhancement.features"));Files.createDirectories(settings.getParent());Files.deleteIfExists(settings);
        Context context=new Context(new UIContainer(),new IdentifiableView(2466470,SCRIPT));
        ScriptPopupController controller=edt(()->new ScriptPopupController(context));
        edt(()->null); // install the runtime hook before the native panel initializes
        ScriptGeneralPanel panel=edt(()->{ScriptGeneralPanel value=new ScriptGeneralPanel(context);value.initGeneralPanel(null);return value;});
        edt(()->null);
        edt(()->{((java.util.function.Consumer<Object>)javax.swing.UIManager.get("cn.lucanet.enhancement.script.doubleClick.register")).accept(panel);return null;});
        LNTreeTableCell script=new LNTreeTableCell(context.current);
        check(edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==1,"double-click opens original run dialog");
        check(panel.startedScripts==0,"double-click never invokes start");
        Files.writeString(settings,"scriptDoubleClick=false\n");
        check(!edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==1 && controller.originalDoubleClicks==1,"disabled immediately, original tree handling retained");
        Files.writeString(settings,"scriptDoubleClick=true\n");
        check(edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==2,"re-enabled immediately in same client");
        edt(()->{gesture(context,controller,3,script);gesture(context,controller,7,script);return null;});
        check(panel.openedDialogs==2 && controller.originalOtherEvents==2,"single-click and Enter unchanged");
        check(!edt(()->gesture(context,controller,4,new LNTreeTableCell(new IdentifiableView(20,"com.lucanet.summa.database.model.script.ScriptFolder")))) && panel.openedDialogs==2,"folder unchanged");
        check(!edt(()->gesture(context,controller,4,new LNTreeTableCell(new IdentifiableView(30,SCRIPT)))) && panel.openedDialogs==2,"mismatched node cannot use stale details");
        edt(()->{panel.runButton().setEnabled(false);return null;});
        check(!edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==2,"native disabled state respected");
        edt(()->{panel.runButton().setEnabled(true);panel.showing=false;return null;});
        check(!edt(()->gesture(context,controller,4,script)),"background/hidden panel not clicked");
        edt(()->{panel.showing=true;panel.fail=true;return null;});
        check(!edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==2,"native failure falls back without opening wrong dialog");
        edt(()->{panel.fail=false;return null;});
        HierarchyElementPopupController unrelated=edt(()->new HierarchyElementPopupController(context));
        check(!edt(()->unrelated.interact(4,script)) && unrelated.originalDoubleClicks==1,"other controllers unchanged even for same node");
        Context another=new Context(new UIContainer(),context.current);
        ScriptPopupController otherWindow=edt(()->new ScriptPopupController(another));
        check(!edt(()->gesture(another,otherWindow,4,script)),"different client window/container cannot execute stale action");
        check(!gesture(context,controller,4,script) && panel.openedDialogs==2,"off-EDT call does not execute UI");
        edt(()->{panel.runButton().doClick(0);return null;});
        check(panel.openedDialogs==3,"original mouse run still works");
        edt(()->{panel.showing=false;context.visibleDetail=new javax.swing.JPanel(){public boolean isShowing(){return true;}};return null;});
        check(edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==4,"hidden general tab inside active details still opens dialog");
        edt(()->{panel.showing=true;panel.putClientProperty("cn.lucanet.enhancement.script.doubleClick.button",null);panel.putClientProperty("cn.lucanet.enhancement.script.doubleClick.context",null);
            ((java.util.function.Consumer<Object>)javax.swing.UIManager.get("cn.lucanet.enhancement.script.doubleClick.register")).accept(panel);return null;});
        check(edt(()->gesture(context,controller,4,script)) && panel.openedDialogs==5,"hot-update backfill handles panels initialized before hook registration");
        com.lucanet.summa.client.sections.position.PositionMenuController custom=edt(()->new com.lucanet.summa.client.sections.position.PositionMenuController(context));
        Object wrapped=new com.lucanet.summa.client.sections.position.multidim.MultiDimNode(new DimScript(new IdentifiableView(2466470,SCRIPT)));
        edt(()->{context.current=wrapped;return null;});
        LNTreeTableCell customScript=new LNTreeTableCell(wrapped);
        check(edt(()->gesture(context,custom,4,customScript)) && panel.openedDialogs==6 && custom.originalDoubleClicks==0,"custom section script intercepts before overriding controller");
        edt(()->{context.current=new IdentifiableView(2466470,SCRIPT);return null;});
        check(edt(()->gesture(context,custom,4,customScript)) && panel.openedDialogs==7,"wrapped clicked node matches unwrapped details");
        Files.writeString(settings,"scriptDoubleClick=false\n");
        edt(()->gesture(context,custom,4,customScript));
        check(panel.openedDialogs==7 && custom.originalDoubleClicks==1,"custom section disable is immediate");
        Files.writeString(settings,"scriptDoubleClick=true\n");
        edt(()->gesture(context,custom,4,new LNTreeTableCell(wrapped,2)));
        check(panel.openedDialogs==7 && custom.originalDoubleClicks==2,"numeric column retains native drill-down");
        Object wrappedFolder=new com.lucanet.summa.client.sections.position.multidim.MultiDimNode(new DimScript(new IdentifiableView(20,"com.lucanet.summa.database.model.script.ScriptFolder")));
        edt(()->gesture(context,custom,4,new LNTreeTableCell(wrappedFolder)));
        check(panel.openedDialogs==7 && custom.originalDoubleClicks==3,"custom section folders retain native behavior");
        Object different=new com.lucanet.summa.client.sections.position.multidim.MultiDimNode(new DimScript(new IdentifiableView(999,SCRIPT)));
        edt(()->gesture(context,custom,4,new LNTreeTableCell(different)));
        check(panel.openedDialogs==7,"different wrapped script cannot click stale run action");
        check(edt(()->gesture(context,custom,4,customScript)) && panel.openedDialogs==8 && panel.startedScripts==0,"custom re-enable opens only the correct dialog");
        Object programNode=new com.lucanet.summa.client.sections.position.multidim.MultiDimNode(new DimScript(new IdentifiableView(4321,"program-script-kind")));
        Context programContext=new Context(new UIContainer(),programNode);
        var program=edt(()->new com.lucanet.summa.client.fi.fi_conso2.dpsections.consolidatedstatement.ScriptProgramElementDPSection(programContext,4321));
        edt(()->{((java.util.function.Consumer<Object>)javax.swing.UIManager.get("cn.lucanet.enhancement.script.doubleClick.register")).accept(program);return null;});
        var programController=edt(()->new com.lucanet.summa.client.sections.position.PositionMenuController(programContext));
        check(edt(()->gesture(programContext,programController,4,new LNTreeTableCell(programNode))) && program.openedDialogs==1,"program-type script uses its own native execution button");
        edt(()->{program.modelOID=123;return null;});
        edt(()->gesture(programContext,programController,4,new LNTreeTableCell(programNode)));
        check(program.openedDialogs==1,"stale program configuration model cannot open wrong script");
        Object newer=new com.lucanet.summa.client.sections.position.multidim.MultiDimNode(new NewDimScript(new IdentifiableView(2466470,SCRIPT)));
        edt(()->{context.current=newer;return null;});
        check(edt(()->gesture(context,custom,4,new LNTreeTableCell(newer))) && panel.openedDialogs==9,"new fishared wrapper resolves the same script identity");
        // 本机 GR 栏实测：同一裸 TreeDataNode 使用 ScriptHierarchyElement，而非 ScriptNode。
        Object actual=new com.lucanet.identifiable.shared.datamodel.TreeDataNode(2469438,"com.lucanet.summa.database.model.script.ScriptHierarchyElement");
        edt(()->{context.current=actual;panel.setDPSectionModel(new com.lucanet.summa.client.sections.script.dpsections.ScriptDPSectionModel(actual));return null;});
        check(edt(()->gesture(context,custom,4,new LNTreeTableCell(actual))) && panel.openedDialogs==10,"captured GR hierarchy element opens its script dialog");
        edt(()->{panel.setDPSectionModel(new com.lucanet.summa.client.sections.script.dpsections.ScriptFolderDPSectionModel(actual));return null;});
        edt(()->gesture(context,custom,4,new LNTreeTableCell(actual)));
        check(panel.openedDialogs==10,"same hierarchy delegate with folder model never runs");
        edt(()->{panel.setDPSectionModel(new com.lucanet.summa.client.sections.script.dpsections.ScriptDPSectionModel(new IdentifiableView(123,SCRIPT)));return null;});
        edt(()->gesture(context,custom,4,new LNTreeTableCell(actual)));
        check(panel.openedDialogs==10,"hierarchy detail model must match clicked OID");
        System.out.println("PASS: standard/custom-section/program scripts; immediate on/off, wrapped-node identity, node/window/model guards, unchanged folders/metrics/single/Enter, original run without auto-start");
    }
    private static boolean gesture(com.lucanet.lisa.ui.UIContext context,HierarchyElementPopupController controller,int type,LNTreeTableCell cell){
        var event=new com.lucanet.lisa.widgets.treetable.LNTreeTableUserInteractionEvent(type,new com.lucanet.lisa.widgets.treetable.LNTreeTableContext(context,cell));
        new com.lucanet.lisa.applicationframe.section.explorer.Explorer.c(controller).userInteracted(event);
        return event.isConsumed();
    }
    public static final class DimScript implements com.lucanet.summa.shared.datamodel.IdentifiableViewDimNode {
        private final IdentifiableView node;
        DimScript(IdentifiableView node){this.node=node;}
        public IdentifiableView getIdentifiableView(){return node;}
    }
    public static final class NewDimScript implements com.lucanet.fishared.datamodel.IdentifiableViewDimNode {
        private final IdentifiableView node;
        NewDimScript(IdentifiableView node){this.node=node;}
        public IdentifiableView getIdentifiableView(){return node;}
    }
    public static final class Context implements DetailPanelContext {
        final UIContainer container;Object current;java.awt.Component visibleDetail;
        Context(UIContainer container,Object current){this.container=container;this.current=current;}
        public UIContainer getUiContainer(){return container;}
        public Object getCurrentExplorerNode(){return current;}
        public java.awt.Component getDetailPanel(){return visibleDetail;}
    }
    private static <T>T edt(Callable<T> call)throws Exception{FutureTask<T> task=new FutureTask<>(call);EventQueue.invokeAndWait(task);return task.get();}
    private static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
