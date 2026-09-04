package org.adguardian.app.debug;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;

public final class DiagnosticSnapshot {
    private static final int MAX_VISITED_NODES = 160;
    private static final int MAX_REPORTED_NODES = 28;
    private static final int MAX_FIELD_LENGTH = 48;

    private DiagnosticSnapshot() {
    }

    public static String capture(AccessibilityNodeInfo root) {
        if (root == null) {
            return "root=null";
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        StringBuilder result = new StringBuilder(2048);
        int visited = 0;
        int reported = 0;

        while (!queue.isEmpty() && visited < MAX_VISITED_NODES && reported < MAX_REPORTED_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            if (node.isVisibleToUser()) {
                String id = compactId(node.getViewIdResourceName());
                String text = compact(node.getText());
                String desc = compact(node.getContentDescription());
                if (!id.isEmpty() || !text.isEmpty() || !desc.isEmpty() || node.isClickable()) {
                    if (reported > 0) {
                        result.append(" || ");
                    }
                    result.append('#').append(reported + 1);
                    if (!id.isEmpty()) {
                        result.append(" id=").append(id);
                    }
                    if (!text.isEmpty()) {
                        result.append(" text=").append(text);
                    }
                    if (!desc.isEmpty()) {
                        result.append(" desc=").append(desc);
                    }
                    if (node.isClickable()) {
                        result.append(" clickable=1");
                    }
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    result.append(" b=")
                            .append(bounds.left).append(',')
                            .append(bounds.top).append(',')
                            .append(bounds.right).append(',')
                            .append(bounds.bottom);
                    reported++;
                }
            }

            int childCount = node.getChildCount();
            for (int index = 0; index < childCount && queue.size() < MAX_VISITED_NODES; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        if (reported == 0) {
            return "no visible diagnostic nodes  visited=" + visited;
        }
        return "visited=" + visited + " reported=" + reported + "  " + result;
    }

    private static String compactId(String value) {
        if (value == null) {
            return "";
        }
        int marker = value.indexOf(":id/");
        return compact(marker >= 0 ? value.substring(marker + 4) : value);
    }

    private static String compact(CharSequence value) {
        return value == null ? "" : compact(value.toString());
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_FIELD_LENGTH) {
            clean = clean.substring(0, MAX_FIELD_LENGTH) + "…";
        }
        return clean;
    }
}
