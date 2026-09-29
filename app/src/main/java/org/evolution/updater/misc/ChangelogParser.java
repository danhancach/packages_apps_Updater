/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.misc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phan tich file changelog text thanh cac section version/date/items. */
public final class ChangelogParser {

    private static final Pattern DATE_IN_TITLE = Pattern.compile(
            "\\b(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|"
                    + "\\d{4}-\\d{2}-\\d{2}|"
                    + "(January|February|March|April|May|June|July|August|September|"
                    + "October|November|December)\\s+\\d{1,2},?\\s+\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private ChangelogParser() {
    }

    public static List<ChangelogSection> parse(String text) {
        List<ChangelogSection> sections = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return sections;
        }

        String normalized = text.replace("\r\n", "\n").trim();
        String[] blocks = normalized.split("\n\\s*\n");

        for (String block : blocks) {
            ChangelogSection section = parseBlock(block.trim());
            if (section != null) {
                sections.add(section);
            }
        }

        if (sections.isEmpty()) {
            ChangelogSection fallback = parseBlock(normalized);
            if (fallback != null) {
                sections.add(fallback);
            }
        }

        return sections;
    }

    private static ChangelogSection parseBlock(String block) {
        if (block == null || block.isEmpty()) {
            return null;
        }

        String[] lines = block.split("\n");
        String title = null;
        String date = null;
        List<String> items = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (title == null) {
                title = line;
                date = extractDate(title);
                continue;
            }

            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
                items.add(line.substring(2).trim());
            } else if (line.startsWith("-") || line.startsWith("*") || line.startsWith("•")) {
                items.add(line.substring(1).trim());
            } else {
                items.add(line);
            }
        }

        if (title == null) {
            return null;
        }
        return new ChangelogSection(title, date, items);
    }

    private static String extractDate(String title) {
        Matcher matcher = DATE_IN_TITLE.matcher(title);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
