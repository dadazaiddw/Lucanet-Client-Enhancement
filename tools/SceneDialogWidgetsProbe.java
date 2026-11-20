import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;

/** Real Lucanet panels/layout/close button, an isolated native Swing window and a harmless text input. */
public final class SceneDialogWidgetsProbe {
    static final String CREATOR="com.lucanet.common.dom.dashboard.elements.ui.dashboardbuilder.SceneDialogCreator";
    static final String ACTIVE="cn.lucanet.enhancement.sceneDialog.v1.controller";
    static JDialog dialog;static JComponent root,title,resize;static AbstractButton close;
    static JTextField input;static int cancels;
    public static void main(String[] args)throws Exception {
        try {
            Class.forName(CREATOR,false,ClassLoader.getSystemClassLoader());edt(()->null);
            edt(()->{build();return null;});
            if(dialog.isUndecorated() || title.isVisible() || resize.getBorder()!=null)throw new AssertionError("Real widgets not styled");
            if(!dialog.getTitle().isEmpty())throw new AssertionError("Scene caption not cleared before show");
            edt(()->{dialog.setVisible(true);return null;});
            Path settings=Path.of(System.getProperty("lucanet.enhancement.features"));
            for(boolean enabled:new boolean[]{false,true,false,true}) {
                Files.writeString(settings,"nativeSceneDialogFrame="+enabled+"\n");
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(edt(()->dialog.isUndecorated()==enabled) && System.nanoTime()<end)Thread.sleep(25);
                if(edt(()->dialog.isUndecorated()==enabled || title.isVisible()==enabled || !input.getText().equals("keep actual layout input")))throw new AssertionError("Real widget live switch failed");
                if(enabled && edt(()->input.getY()>2))throw new AssertionError("Hidden original title still consumes layout space: "+input.getBounds());
                if(cancels!=0)throw new AssertionError("Switch ran original action");
                edt(()->{dialog.setTitle("updated scene caption");return null;});
                if(!edt(()->dialog.getTitle().isEmpty()))throw new AssertionError("Live scene caption not cleared");
            }
            edt(()->{dialog.dispatchEvent(new WindowEvent(dialog,WindowEvent.WINDOW_CLOSING));return null;});
            if(cancels!=1 || dialog.isDisplayable())throw new AssertionError("Close did not use original button");
            System.out.println("PASS: actual Lucanet LNPanel/ResizePanel/close button/layout live switches, hidden title collapses, original close action retained");
        } finally {
            edt(()->{Object controller=UIManager.get(ACTIVE);if(controller instanceof AutoCloseable)((AutoCloseable)controller).close();for(Window w:Window.getWindows())w.dispose();return null;});
        }
    }
    @SuppressWarnings("unchecked") static void build()throws Exception {
        UIManager.put("LNButtonUI","com.lucanet.lisa.widgets.button.LNButtonUI");
        dialog=new JDialog((Window)null);dialog.setTitle("scene caption");dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);dialog.setUndecorated(true);
        root=(JComponent)Class.forName("com.lucanet.lisa.widgets.LNPanel").getConstructor().newInstance();
        title=(JComponent)Class.forName("com.lucanet.lisa.widgets.LNPanel").getConstructor().newInstance();
        Constructor<?> constructor=Class.forName(CREATOR+"$c").getDeclaredConstructor();constructor.setAccessible(true);close=(AbstractButton)constructor.newInstance();
        if(close.getUI()==null)throw new AssertionError("Native close button UI missing");
        title.setLayout(new BorderLayout());title.add(close,BorderLayout.EAST);title.setPreferredSize(new Dimension(16,16));
        Class<?> location=Class.forName("com.lucanet.lisa.widgets.perspective.ResizePanel$DragLocation");
        Object locations=Array.newInstance(location,location.getEnumConstants().length);
        for(int i=0;i<location.getEnumConstants().length;i++)Array.set(locations,i,location.getEnumConstants()[i]);
        resize=(JComponent)Class.forName("com.lucanet.lisa.widgets.perspective.ResizePanel").getConstructor(Component.class,Color.class,locations.getClass()).newInstance(dialog,Color.MAGENTA,locations);
        Class<?> flow=Class.forName("com.lucanet.lisa.widgets.layout.VerticalFormFlowLayout");
        Object layout=flow.getMethod("createNoGap",Container.class).invoke(null,resize);
        flow.getMethod("addRow",Component.class).invoke(layout,title);
        input=new JTextField("keep actual layout input");flow.getMethod("addFillableRow",Component.class).invoke(layout,input);
        root.setLayout(new BorderLayout());root.add(resize);dialog.setContentPane(root);dialog.setBounds(-18000,-18000,440,240);
        close.addActionListener(e->{cancels++;dialog.dispose();});
        ((Consumer<Object>)UIManager.get(ACTIVE)).accept(root);
    }
    static <T>T edt(Callable<T> action)throws Exception {
        FutureTask<T> task=new FutureTask<>(action);EventQueue.invokeAndWait(task);return task.get();
    }
}
