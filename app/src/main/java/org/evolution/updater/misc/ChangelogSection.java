/*
 * Copyright (C) 2026 The Evolution X Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.updater.misc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mot phien ban / khoi noi dung trong file changelog. */
public class ChangelogSection {

    public final String title;
    public final String date;
    public final List<String> items;

    public ChangelogSection(String title, String date, List<String> items) {
        this.title = title;
        this.date = date;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }
}
