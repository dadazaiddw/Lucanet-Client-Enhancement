package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.swing.*;
import com.lucanet.common.dom.dashboard.elements.ui.dashboardbuilder.SceneDialogCreator;

/** Uses real Windows peers off screen; no installed client or user preferences are touched. */
public final class SceneDialogStyleTest {
    private static final String ACTIVE="cn.lucanet.enhancement.sceneDialog.v1.controller";
    private static Path settings;
    public static void main(String[] args)throws Exception {
        settings=Path.of(System.getProperty("lucanet.enhancement.features"));
        Files.createDirectories(settings.getParent());Files.deleteIfExists(settings);
        try {
            for(boolean modal:new boolean[]{false,true})verifyDialog(modal);
            // A dialog opened while disabled keeps the original style; the existing window updates later.
            write(false);Thread.sleep(400);
            SceneDialogCreator disabled=edt(()->new SceneDialogCreator(false));
            check(edt(()->disabled.dialog.isUndecorated() && disabled.title.isVisible()),"disabled on creation");
            check(edt(()->disabled.dialog.getTitle().isEmpty()),"scene caption empty with original frame");
            show(disabled);write(true);await(()->!disabled.dialog.isUndecorated(),"enable on existing window");
            edt(()->{disabled.close.setEnabled(false);disabled.dialog.dispatchEvent(new WindowEvent(disabled.dialog,WindowEvent.WINDOW_CLOSING));return null;});
            check(disabled.cancels==0,"disabled original close is respected");
            edt(()->{disabled.close.setEnabled(true);disabled.dialog.dispatchEvent(new WindowEvent(disabled.dialog,WindowEvent.WINDOW_CLOSING));return null;});
            check(disabled.cancels==1 && disabled.dialog.disposes==1,"native close routes original action exactly once");
            verifyReplacement();
            SceneDialogCreator cleanup=edt(()->new SceneDialogCreator(false));show(cleanup);
            edt(()->{cleanup.dialog.setTitle("latest scene title");return null;});
            check(edt(()->cleanup.dialog.getTitle().isEmpty()),"later scene title remains empty");
            edt(()->{((AutoCloseable)UIManager.get(ACTIVE)).close();return null;});
            check(edt(()->cleanup.dialog.isUndecorated() && cleanup.title.isVisible() && cleanup.resize.getBorder()!=null),"controller cleanup restores original style");
            check(edt(()->cleanup.dialog.getTitle().equals("latest scene title")),"controller cleanup restores latest original caption");
            check(edt(()->cleanup.dialog.getPropertyChangeListeners("title").length==0),"caption listener removed on cleanup");
            check(cleanup.cancels==0 && cleanup.dialog.disposes==0,"cleanup does not close scene");
            edt(()->{cleanup.close.doClick(0);return null;});
            System.out.println("PASS: blank scene captions before show and after live title/frame changes, modeless/modal live toggles, input/selection/bounds retained, no dispose/show/cancel on toggles, owned child deferral, unrelated dialog isolation and original close action");
        } finally {
            edt(()->{Object controller=UIManager.get(ACTIVE);if(controller instanceof AutoCloseable)((AutoCloseable)controller).close();for(Window w:Window.getWindows())w.dispose();return null;});
        }
    }
    private static void verifyReplacement()throws Exception {
        SceneDialogCreator scene=edt(()->new SceneDialogCreator(false));show(scene);
        Object old=edt(()->UIManager.get(ACTIVE));
        java.lang.reflect.Method install=old.getClass().getDeclaredMethod("ensureInstalled");install.setAccessible(true);
        JWindow child=edt(()->{JWindow w=new JWindow(scene.dialog);w.setBounds(-18000,-18000,50,50);w.setVisible(true);return w;});
        edt(()->{((AutoCloseable)old).close();install.invoke(null);return null;});
        check(edt(()->!scene.dialog.isUndecorated() && child.isVisible()),"replacement defers while child is visible");
        edt(()->{child.dispose();return null;});Thread.sleep(700);
        write(false);await(scene.dialog::isUndecorated,"replacement adopts deferred window");
        write(true);await(()->!scene.dialog.isUndecorated(),"replacement observes live configuration");
        // Same stable protocol pointer used when a failed hot update rolls back to a version without this feature.
        java.lang.reflect.Field current=Class.forName("cn.lucanet.enhancement.exit.LiveBridge",false,null).getDeclaredField("current");current.setAccessible(true);
        Object module=current.get(null);
        try{current.set(null,null);await(()->scene.dialog.isUndecorated() && UIManager.get(ACTIVE)==null,"retired module restores original appearance");}
        finally{current.set(null,module);}
        edt(()->{install.invoke(null);return null;});await(()->!scene.dialog.isUndecorated(),"active module resumes");
        check(scene.cancels==0 && scene.dialog.disposes==0,"module replacement keeps scene alive");
        edt(()->{scene.dialog.dispatchEvent(new WindowEvent(scene.dialog,WindowEvent.WINDOW_CLOSING));return null;});
        check(scene.cancels==1,"replacement never duplicates close actions");
    }
    private static void verifyDialog(boolean modal)throws Exception {
        Files.deleteIfExists(settings);Thread.sleep(400);
        JFrame owner=edt(()->{JFrame f=new JFrame();f.setBounds(-20000,-20000,100,100);f.setVisible(true);return f;});
        SceneDialogCreator scene=edt(()->new SceneDialogCreator(owner,modal));
        check(edt(()->!scene.dialog.isUndecorated() && !scene.title.isVisible() && scene.resize.getBorder()==null),"default native before first show");
        check(edt(()->scene.dialog.getTitle().isEmpty()),"empty native caption before first show");
        show(scene);
        Rectangle bounds=edt(()->scene.dialog.getBounds());
        edt(()->{scene.input.setText("keep 未保存 input");scene.input.select(2,6);return null;});
        JDialog unrelated=edt(()->{JDialog d=new JDialog();d.setTitle("other dialog");d.setUndecorated(true);d.setBounds(-19000,-19000,80,80);d.setVisible(true);return d;});
        int[] closed={0};edt(()->{scene.dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed[0]++;}});return null;});
        for(int i=0;i<6;i++) {
            boolean enabled=i%2!=0;write(enabled);
            await(()->scene.dialog.isUndecorated()!=enabled,"live style change");
            edt(()->{scene.dialog.setTitle("updated scene title");return null;});
            check(edt(()->scene.dialog.getTitle().isEmpty()),"caption stays empty after live title update");
            edt(()->{scene.dialog.setTitle("");return null;});
            check(edt(()->scene.dialog.getTitle().isEmpty()),"empty title never falls back to owner caption");
            check(edt(()->scene.dialog.isVisible() && scene.dialog.isModal()==modal && scene.dialog.getBounds().equals(bounds)
                    && scene.input.getText().equals("keep 未保存 input") && scene.input.getSelectionStart()==2 && scene.input.getSelectionEnd()==6
                    && scene.title.isVisible()!=enabled && (scene.resize.getBorder()==null)==enabled),"state and appearance retained");
            check(scene.cancels==0 && scene.dialog.disposes==0 && scene.dialog.shows==1 && closed[0]==0,"no scene lifecycle triggered");
            check(edt(()->unrelated.isUndecorated() && unrelated.getTitle().equals("other dialog")),"unrelated dialog unchanged");
            if(modal && Boolean.getBoolean("fixture.checkNativeModality"))edt(()->{
                java.lang.reflect.Field peer=Component.class.getDeclaredField("peer");peer.setAccessible(true);
                java.lang.reflect.Field blocker=Class.forName("sun.awt.windows.WWindowPeer").getDeclaredField("modalBlocker");blocker.setAccessible(true);
                check(blocker.get(peer.get(owner))==peer.get(scene.dialog),"native owner keeps current modal blocker peer");return null;
            });
        }
        JWindow popup=edt(()->{JWindow p=new JWindow(scene.dialog);p.setBounds(-18000,-18000,60,60);p.setVisible(true);return p;});
        write(false);Thread.sleep(500);
        check(edt(()->!scene.dialog.isUndecorated() && popup.isVisible()),"defer while owned popup is visible");
        edt(()->{popup.setVisible(false);return null;});
        await(scene.dialog::isUndecorated,"apply after owned popup closes");
        check(edt(()->popup.isDisplayable() && popup.getOwner()==scene.dialog),"hidden owned peer preserved");
        edt(()->{popup.setVisible(true);popup.dispose();unrelated.dispose();scene.close.doClick(0);return null;});
        check(scene.cancels==1 && scene.dialog.disposes==1,"original close still works after switching");
        await(()->scene.dialog.showsFinished==1,"modal loop terminates on original close");
        edt(()->{owner.dispose();return null;});
    }
    private static void show(SceneDialogCreator scene)throws Exception {
        EventQueue.invokeLater(()->scene.dialog.setVisible(true));await(scene.dialog::isVisible,"scene shown");
    }
    private static void write(boolean enabled)throws Exception {
        Path temp=settings.resolveSibling("scene-next.properties");Files.writeString(temp,"nativeSceneDialogFrame="+enabled+"\n");
        Files.move(temp,settings,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void await(Callable<Boolean> condition,String message)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){if(edt(condition))return;Thread.sleep(25);}throw new AssertionError(message);
    }
    private static <T>T edt(Callable<T> action)throws Exception {
        FutureTask<T> task=new FutureTask<>(action);EventQueue.invokeAndWait(task);return task.get();
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
