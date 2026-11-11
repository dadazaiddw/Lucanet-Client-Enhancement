package cn.lucanet.enhancement.exit;

import java.awt.event.ActionEvent;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.swing.Action;
import javax.swing.SwingUtilities;

/** 仅在外部链接监听器已完成登录后的导航入口调用，不拦截菜单/工作区内部导航。 */
final class LinkNavigation {
    static boolean isOidLink(String text) {
        if (text == null) { return false; }
        try {
            // ln:goto 是 opaque URI，不能使用 URI.getQuery()；先读取 scheme-specific part。
            URI uri = URI.create(text);
            if (!"ln".equalsIgnoreCase(uri.getScheme()) && !"lns".equalsIgnoreCase(uri.getScheme())) { return false; }
            String route, query;
            if (uri.isOpaque()) {
                if (!"ln".equalsIgnoreCase(uri.getScheme())) { return false; }
                String part = uri.getRawSchemeSpecificPart(); int split = part.indexOf('?');
                if (split < 0) { return false; }
                route = part.substring(0, split); query = part.substring(split + 1);
            } else {
                // Manager/首次登录可把原链接规范化为 ln[s]://host/goto/LNFC/db?oid=…。
                if (uri.getRawAuthority() == null || uri.getRawUserInfo() != null) { return false; }
                route = uri.getRawPath(); query = uri.getRawQuery();
                if (route == null || !route.startsWith("/")) { return false; }
                route = route.substring(1);
            }
            if (query == null || !route.matches("(?i)goto/LNFC/[^/?]+")) { return false; }
            int count = 0;
            for (String pair : query.split("&")) {
                String[] entry = pair.split("=", 2);
                if (URLDecoder.decode(entry[0], StandardCharsets.UTF_8).equals("oid")) {
                    if (entry.length != 2 || !URLDecoder.decode(entry[1], StandardCharsets.UTF_8).matches("[0-9]+")) { return false; }
                    count++;
                }
            }
            return count == 1;
        } catch (IllegalArgumentException failure) { return false; }
    }

    static boolean open(Class<?> source, String uri) {
        if (!isOidLink(uri) || !FeatureSettings.openLinksInNewWindow() || !SwingUtilities.isEventDispatchThread()) { return false; }
        try {
            ClassLoader loader = source.getClassLoader();
            Class<?> controllerType = Class.forName("com.lucanet.lisa.ui.UIController", false, loader);
            Class<?> containerType = Class.forName("com.lucanet.lisa.applicationframe.UIContainer", false, loader);
            Class<?> contextType = Class.forName("com.lucanet.lisa.ui.UIContext", false, loader);
            Class<?> actionType = Class.forName("com.lucanet.lisa.useraction.AbstractUserAction", false, loader);
            AgentRuntime.connectNavigationApi(controllerType, containerType, contextType, actionType);
            Object controller = controllerType.getMethod("getInstance").invoke(null);
            Object container = controllerType.getMethod("getCurrentUIContainer").invoke(controller);
            if (container == null) { return false; }
            Object context = containerType.getMethod("getUIContext").invoke(container);
            // 此原生动作的 true 参数先创建并初始化新窗口，再在该窗口中加载完整 URI。
            // 原始数据库、OID、查询参数和 fragment 均原样交给 Lucanet 进行权限与有效性检查。
            Object action = controllerType.getMethod("createOpenHyperlinkUserAction", contextType, String.class, boolean.class)
                    .invoke(controller, context, uri, true);
            if (!(action instanceof Action) || !((Action) action).isEnabled()) { return false; }
            // Lucanet 的 VirtualSocket 会拒绝调用栈中存在 java.lang.reflect.Method 的服务请求。
            // 工厂/上下文反射已返回；通过标准 Swing Action 接口直接执行，和原生按钮一致。
            // 不能使用 Method.invoke(executeNow/actionPerformed)，也不修改或关闭原生检查。
            ((Action) action).actionPerformed(new ActionEvent(container, ActionEvent.ACTION_PERFORMED, "enhancement-external-link"));
            EnhancementLog.info("LINK_NEW_WINDOW: native Swing action completed");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            EnhancementLog.error("LINK_NEW_WINDOW_FAILED: falling back to original navigation", failure);
            // 只有动作正常完成才能消费链接。不能因“开始执行过”而吞掉原来的 OID 跳转。
            return false;
        }
    }
}
