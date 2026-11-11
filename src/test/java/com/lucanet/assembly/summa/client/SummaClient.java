package com.lucanet.assembly.summa.client;

import com.lucanet.lisa.ui.UIController;

/** 模拟真实已登录外部链接监听器；私有 a(String) 的签名与 2311 / 2505 一致。 */
public final class SummaClient {
    public static final class a {
        private void a(String uri) { UIController.originalNavigations++; }
        public void navigate(String uri) { a(uri); }
    }
    public static void navigate(String uri) { new a().navigate(uri); }
}
