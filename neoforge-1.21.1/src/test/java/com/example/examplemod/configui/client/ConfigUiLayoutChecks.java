package com.example.examplemod.configui.client;

/** Geometry checks only: the native authoring shell never starts Minecraft. */
public final class ConfigUiLayoutChecks {
    public static void main(String[] args) {
        int checks = 0;
        for (int width = 320; width <= 1920; width++) {
            var tabs = ConfigUiLayout.tabs(width);
            require(tabs.size() == 4); checks++;
            int previous = 8 - ConfigUiLayout.TAB_GAP;
            int minimum = Integer.MAX_VALUE, maximum = 0;
            for (var tab : tabs) {
                require(tab.x() == previous + ConfigUiLayout.TAB_GAP); checks++;
                require(tab.y() == 34 && tab.bottom() < ConfigUiLayout.CONTENT_TOP); checks++;
                require(tab.width() >= 73 && tab.height() == 22); checks++;
                require(tab.contains(tab.x(), tab.y()) && !tab.contains(tab.right(), tab.bottom())); checks++;
                previous = tab.right(); minimum = Math.min(minimum, tab.width()); maximum = Math.max(maximum, tab.width());
            }
            require(tabs.getLast().right() == width - 8 && maximum - minimum <= 1); checks++;
        }
        System.out.println("ConfigUiLayoutChecks: " + checks + " shared four-tab bounds checks passed; no game launched.");
    }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Native four-tab geometry"); }
}
