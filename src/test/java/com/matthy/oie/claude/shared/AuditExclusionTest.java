/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.shared;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

/**
 * The engine writes every @Param of an audited operation into the event log, unless it is
 * marked excludeFromAudit. Request bodies of audited operations must therefore be excluded,
 * because they can hold API keys (issue #81).
 */
public class AuditExclusionTest {

    // Audited operations whose body is known to hold nothing sensitive.
    private static final Set<String> SAFE_BODIES = new HashSet<>(Arrays.asList(
            "claudeConfirmAction")); // {actionId, approved}

    @Test
    public void setSettingsBodyIsNotAudited() throws Exception {
        Method m = ClaudeServletInterface.class.getMethod("setSettings", String.class);
        Param body = param(m, 0);
        assertTrue("setSettings body must have excludeFromAudit = true", body.excludeFromAudit());
    }

    @Test
    public void auditedOperationsDoNotLogBodies() {
        for (Method m : ClaudeServletInterface.class.getMethods()) {
            MirthOperation op = m.getAnnotation(MirthOperation.class);
            if (op == null || !op.auditable() || SAFE_BODIES.contains(op.name())) {
                continue;
            }
            for (int i = 0; i < m.getParameterCount(); i++) {
                Param p = param(m, i);
                if (p != null && "body".equals(p.value()) && !p.excludeFromAudit()) {
                    fail(op.name() + " is audited and logs its body; add excludeFromAudit = true");
                }
            }
        }
    }

    private static Param param(Method m, int index) {
        for (Annotation a : m.getParameterAnnotations()[index]) {
            if (a instanceof Param) {
                return (Param) a;
            }
        }
        return null;
    }
}
