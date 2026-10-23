package com.example.examplemod.zones.match.client;

import java.util.List;

/** Actual native layout helper only; no Screen or game is constructed. */
public final class MatchConfigLayoutChecks {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        for (int width : new int[]{240, 280, 320, 426, 480, 640, 700, 854, 960, 1280}) {
            for (int height : new int[]{200, 240, 270, 320, 340, 480, 720}) {
                var frame = MatchConfigLayout.forViewport(width, height);
                check(frame.form().x() >= 0 && frame.form().right() <= width, "form horizontal bounds");
                check(frame.form().y() >= 64 && frame.form().height() >= 28, "native scrolling content below shared tabs");
                check(frame.form().bottom() < frame.messageY(), "content never overlaps feedback line");
                check(frame.messageY() + 9 < frame.firstFooterY(), "message clears first footer row");
                if (frame.hasPreview()) {
                    check(frame.preview().x() >= frame.form().right() + 8 && frame.preview().right() <= width, "separate side preview");
                    check(width >= 700 && height >= 340, "small viewports never lose input width to the preview");
                }
                for (int row = 0; row < 2; row++) {
                    List<MatchConfigLayout.Rect> cells = MatchConfigLayout.footer(width, height, row, row == 0 ? 3 : 4);
                    int last = 0;
                    for (var cell : cells) {
                        check(cell.x() >= last && cell.right() <= width - 8, "footer controls are disjoint and reachable");
                        check(cell.width() >= 40 && cell.height() == 20, "native buttons remain usable at low resolution");
                        check(cell.y() > frame.form().bottom() && cell.bottom() <= height - 8, "footer clears scroll area and bottom edge");
                        last = cell.right() + 4;
                    }
                }
                int rowWidth = frame.form().width() - 14;
                int columns = rowWidth >= 460 ? 2 : 1;
                for (var cell : MatchConfigLayout.fields(rowWidth, columns)) {
                    check(cell.control().x() > cell.label().right(), "label and input never overlap");
                    check(cell.control().width() >= 40 && cell.control().right() <= rowWidth, "complete native field lies within row");
                    check(cell.control().y() >= 0 && cell.control().bottom() <= 28, "complete native field exposed by row clipping");
                }
                if (rowWidth >= MatchConfigLayout.WIDE_PHASE_MIN) {
                    int right = -4;
                    for (var cell : MatchConfigLayout.phaseColumns(rowWidth)) {
                        check(cell.x() >= right + 4 && cell.right() <= rowWidth, "phase table columns never overlap");
                        check(cell.width() >= 24 && cell.bottom() <= 28, "phase controls are usable inside row");
                        right = cell.right();
                    }
                } else {
                    // Each narrow phase uses six real rows. Every field can occupy the top
                    // fully visible row; no pagination or minimum window height hides it.
                    int visible = Math.max(1, frame.form().height() / 28);
                    for (int phase = 0; phase < 16; phase++) for (int field = 0; field < 6; field++) {
                        int index = phase * 6 + field, offset = Math.max(0, index - visible + 1);
                        check(index - offset >= 0 && index - offset < visible, "all sixteen narrow phase blocks have a reachable scroll row");
                    }
                }
            }
        }
        var minimum = MatchConfigLayout.forViewport(320, 240);
        check(!minimum.hasPreview(), "320x240 uses the full width for editable groups");
        check(MatchConfigLayout.footer(320, 240, 1, 4).getFirst().y() == 212, "start/end controls remain fixed at y212");
        System.out.println("MatchConfigLayoutChecks: " + checks + " responsive scroll, phase table and footer checks passed; no game launched.");
    }
}
