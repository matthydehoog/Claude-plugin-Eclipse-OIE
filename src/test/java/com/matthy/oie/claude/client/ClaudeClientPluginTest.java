/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.jdesktop.swingx.JXTaskPane;
import org.junit.Test;

public class ClaudeClientPluginTest {

    private static AbstractAction action(String name) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {}
        };
    }

    @Test
    public void aDashboardTaskComesBackWhenOieHidesIt() throws Exception {
        JXTaskPane pane = new JXTaskPane();
        JPopupMenu menu = new JPopupMenu();
        for (String name : new String[] { "Refresh", "Ask AI Assistant" }) {
            pane.add(action(name));
            menu.add(action(name));
        }
        ClaudeClientPlugin.keepVisible(1, pane, menu);
        Component task = pane.getContentPane().getComponent(1);
        Component item = menu.getComponent(1);
        Component oieTask = pane.getContentPane().getComponent(0);

        // What DashboardPanel.updatePopupMenu does: hide everything from index 0 (here) to the end.
        SwingUtilities.invokeAndWait(() -> {
            oieTask.setVisible(false);
            task.setVisible(false);
            item.setVisible(false);
        });
        SwingUtilities.invokeAndWait(() -> {}); // the hidden events
        SwingUtilities.invokeAndWait(() -> {}); // the invokeLater that shows them again

        assertTrue(task.isVisible());
        assertTrue(item.isVisible());
        assertFalse("OIE's own tasks are left alone", oieTask.isVisible());
    }
}
