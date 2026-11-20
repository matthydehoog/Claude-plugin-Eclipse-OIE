/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;

import java.util.Collections;

import org.junit.Test;

public class SettingsUninstallerTest {

    @Test
    public void uninstallRemovesTheSettingsSavedUnderThePluginPoint() throws Exception {
        assertEquals(Collections.singletonList("DELETE FROM CONFIGURATION WHERE CATEGORY = 'Claude Assistant'"),
                new SettingsUninstaller().getUninstallStatements());
    }
}
