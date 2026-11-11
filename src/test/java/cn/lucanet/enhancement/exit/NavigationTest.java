package cn.lucanet.enhancement.exit;

import java.nio.file.*;
import javax.swing.SwingUtilities;
import com.lucanet.assembly.summa.client.SummaClient;
import com.lucanet.lisa.ui.UIController;
import com.lucanet.lisa.useraction.AbstractUserAction;
import java.awt.event.ActionEvent;
import java.lang.reflect.InvocationTargetException;

public final class NavigationTest {
    public static void main(String[] args) throws Exception {
        Path settings = Path.of(System.getProperty("lucanet.enhancement.features"));
        Files.createDirectories(settings.getParent()); Files.deleteIfExists(settings);
        String link = "ln:goto/LNFC/%E4%B8%AD%E6%96%87?oid=2466470&extra=one%20two#tab";
        // 先复现客户报告：反射执行在创建窗口/跳转之前被服务端访问入口拒绝。
        try {
            AbstractUserAction.class.getMethod("executeNow", ActionEvent.class)
                    .invoke(new AbstractUserAction(link), new ActionEvent(new Object(), 0, "old-implementation"));
            throw new AssertionError("Regression fixture did not reject reflective execution");
        } catch (InvocationTargetException expected) {
            check(expected.getCause().getMessage().equals("no reflection allowed!"), "exact production failure reproduced");
        }
        check(UIController.newWindows == 0 && UIController.lastUri == null, "old reflection bug occurs before navigation");
        navigate(link);
        check(UIController.newWindows == 1 && UIController.originalNavigations == 0 && link.equals(UIController.lastUri), "default enabled, full URI preserved");
        Files.writeString(settings,"linksInNewWindow=false\n"); navigate(link);
        check(UIController.originalNavigations == 1 && UIController.newWindows == 1, "disabled immediately in same JVM");
        Files.writeString(settings,"linksInNewWindow=true\n"); navigate(link);
        check(UIController.newWindows == 2, "re-enabled immediately in same JVM");
        for (String uri : new String[]{null,"https://example.com/?oid=1","ln:goto/LNFC/db", "ln:goto/LNADMIN/db?oid=2", "ln:goto/LNFC/db?oid=x", "ln:goto/LNFC/db?oid=1&oid=2", "ln:goto/LNFC/db?oid=%"}) navigate(uri);
        check(UIController.newWindows == 2 && UIController.originalNavigations == 8, "unrelated and invalid links follow original code");
        UIController.missingAction = true; navigate(link); UIController.missingAction = false;
        UIController.missingContainer = true; navigate(link); UIController.missingContainer = false;
        check(UIController.originalNavigations == 10, "unsupported API/context fall back");
        UIController.failAction = true; navigate(link); UIController.failAction = false;
        check(UIController.originalNavigations == 11 && UIController.newWindows == 3, "failure after window creation must not swallow navigation");
        UIController.failBeforeWindow = true; navigate(link); UIController.failBeforeWindow = false;
        check(UIController.originalNavigations == 12 && UIController.newWindows == 3, "failure before window creation restores original navigation");
        UIController.disabledAction = true; navigate(link); UIController.disabledAction = false;
        check(UIController.originalNavigations == 13 && UIController.newWindows == 3, "disabled action restores original navigation");
        SummaClient.navigate(link); check(UIController.originalNavigations == 14, "no UI work outside event thread");
        for (String normalized : new String[]{"ln://server:8080/goto/LNFC/db?oid=42", "lns://server/goto/LNFC/db?oid=42#tab"}) {
            navigate(normalized); check(normalized.equals(UIController.lastUri), "normalized native URI unchanged");
        }
        check(UIController.newWindows == 5, "Manager-normalized host and SSL routes");
        System.out.println("PASS: reproduced no-reflection failure; direct Swing action opens/navigates, live on/off, exact URI, failures always restore original navigation");
    }
    static void navigate(String uri) throws Exception { SwingUtilities.invokeAndWait(() -> SummaClient.navigate(uri)); }
    static void check(boolean condition, String text) { if (!condition) { throw new AssertionError(text); } }
}
