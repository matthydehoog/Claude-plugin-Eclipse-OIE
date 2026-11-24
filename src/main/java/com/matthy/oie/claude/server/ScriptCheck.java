/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;

import com.mirth.connect.server.controllers.ControllerFactory;

/**
 * Compiles proposed scripts with OIE's own Rhino and language version (the global context factory,
 * which applies rhino.languageversion from mirth.properties), the way OIE compiles them on deploy.
 * Compiling only parses and generates code: nothing is executed.
 */
final class ScriptCheck {

    private static final Logger logger = LogManager.getLogger(ScriptCheck.class);

    /**
     * OIE runs channel scripts inside a generated function, so return statements are allowed. The
     * wrapper starts on line 0, so Rhino's line numbers are the script's own.
     */
    private static final String PREFIX = "function doScript() {\n";
    private static final String SUFFIX = "\n}";

    private final Supplier<ContextFactory> factory;

    /** With OIE's global context factory, or plain ES6 Rhino where that is not available. */
    ScriptCheck() {
        this(ScriptCheck::oieFactory);
    }

    ScriptCheck(Supplier<ContextFactory> factory) {
        this.factory = factory;
    }

    /**
     * @param scripts JavaScript by label (e.g. "transformer step #2 'Map PID' of connector 1"), in order
     * @return one message per script that does not compile: label, line, Rhino's error and the line
     */
    List<String> check(Map<String, String> scripts) {
        List<String> errors = new ArrayList<>();
        if (scripts == null || scripts.isEmpty()) {
            return errors;
        }
        Context cx = factory.get().enterContext();
        try {
            // Interpreted: a syntax check needs the parser, not generated classes.
            cx.setOptimizationLevel(-1);
            for (Map.Entry<String, String> entry : scripts.entrySet()) {
                String script = entry.getValue();
                if (script == null || script.isBlank()) {
                    continue;
                }
                try {
                    cx.compileString(PREFIX + script + SUFFIX, entry.getKey(), 0, null);
                } catch (RhinoException e) {
                    errors.add(describe(entry.getKey(), script, e));
                }
            }
        } finally {
            Context.exit();
        }
        return errors;
    }

    /** "ES6" or "1.8": the language version the scripts are checked with. */
    String languageVersion() {
        Context cx = factory.get().enterContext();
        try {
            int v = cx.getLanguageVersion();
            return v >= Context.VERSION_ES6 ? "ES6" : v == Context.VERSION_DEFAULT ? "default" : (v / 100) + "." + (v % 100 / 10);
        } finally {
            Context.exit();
        }
    }

    static String describe(String label, String script, RhinoException e) {
        int line = e.lineNumber();
        String[] lines = script.split("\r?\n", -1);
        String source = line >= 1 && line <= lines.length ? lines[line - 1].trim() : (e.lineSource() == null ? "" : e.lineSource().trim());
        if (source.length() > 120) {
            source = source.substring(0, 120) + "…";
        }
        String where = line >= 1 && line <= lines.length ? ", line " + line : line > lines.length ? ", at the end" : "";
        return label + where + ": " + e.details() + (source.isEmpty() ? "" : "\n    " + source);
    }

    private static ContextFactory oieFactory() {
        try {
            ContextFactory f = ControllerFactory.getFactory().createContextFactoryController().getGlobalContextFactory();
            if (f != null) {
                return f;
            }
        } catch (Throwable t) {
            logger.debug("AI Assistant: OIE's JavaScript context factory is not available, checking with ES6", t);
        }
        return ES6;
    }

    /** Rhino with OIE's default language version (rhino.languageversion = es6). */
    static final ContextFactory ES6 = new ContextFactory() {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            return cx;
        }
    };
}
