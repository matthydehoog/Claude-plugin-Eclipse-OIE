package com.matthy.oie.claude.server;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Loads the engine (our SDK-facing code plus the Anthropic SDK, Jackson, Kotlin and OkHttp) from the
 * plugin's lib folder in its own child-first class loader. The engine ships a newer Jackson than
 * the one on the OIE classpath, and the SDK's Kotlin reflection cannot be relocated, so isolation
 * is the only way to keep both working.
 */
final class EngineLoader {

    private static final String ENGINE_CLASS = "com.matthy.oie.claude.engine.SdkModelLoop";

    /** Always from the engine's parent: the JDK, the host side of this plugin and the OIE APIs it uses. */
    private static final String[] PARENT_FIRST = { "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.", "com.matthy.oie.claude.server.", "com.matthy.oie.claude.shared.", "com.mirth.", "org.apache.logging.", "org.slf4j." };

    private final ClassLoader loader;

    EngineLoader() throws IOException {
        File libDir = libDir();
        File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IOException("No engine libraries found in " + libDir.getAbsolutePath());
        }
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) {
            urls.add(jar.toURI().toURL());
        }
        loader = new ChildFirstClassLoader(urls.toArray(new URL[0]), EngineLoader.class.getClassLoader());
    }

    ModelLoop create(Settings settings, String systemPrompt, List<Map<String, Object>> tools, ModelLoop.ToolCaller caller) throws Exception {
        Class<?> engine = Class.forName(ENGINE_CLASS, true, loader);
        return (ModelLoop) engine.getConstructor(String.class, String.class, String.class, int.class, String.class, List.class, ModelLoop.ToolCaller.class)
                .newInstance(settings.apiKey, settings.model, settings.effort, settings.maxToolCalls, systemPrompt, tools, caller);
    }

    /** extensions/claude-assistant/lib, next to the server jar this class was loaded from. */
    private static File libDir() throws IOException {
        try {
            File jar = new File(EngineLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new File(jar.getParentFile(), "lib");
        } catch (Exception e) {
            throw new IOException("Cannot determine the folder of the Claude Assistant extension", e);
        }
    }

    private static final class ChildFirstClassLoader extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (parentFirst(name)) {
                        c = getParent().loadClass(name);
                    } else {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = getParent().loadClass(name);
                        }
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }

        @Override
        public URL getResource(String name) {
            URL url = findResource(name);
            return url != null ? url : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            // Engine resources first (Kotlin builtins, service files), then the parent's.
            List<URL> all = new ArrayList<>();
            Enumeration<URL> own = findResources(name);
            while (own.hasMoreElements()) {
                all.add(own.nextElement());
            }
            ClassLoader parent = getParent();
            if (parent != null && !name.startsWith("META-INF/services/")) {
                Enumeration<URL> inherited = parent.getResources(name);
                while (inherited.hasMoreElements()) {
                    all.add(inherited.nextElement());
                }
            }
            return java.util.Collections.enumeration(all);
        }

        private static boolean parentFirst(String name) {
            for (String prefix : PARENT_FIRST) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
