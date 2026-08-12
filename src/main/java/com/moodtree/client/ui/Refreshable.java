/* 可刷新视图：同步完成后主框架会调用 refresh() 让当前页面重载数据。 */
package com.moodtree.client.ui;

public interface Refreshable {
    void refresh();
}
