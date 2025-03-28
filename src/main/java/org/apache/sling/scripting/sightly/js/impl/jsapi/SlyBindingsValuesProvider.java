/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.scripting.sightly.js.impl.jsapi;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.scripting.LazyBindings;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.js.impl.JsEnvironment;
import org.apache.sling.scripting.sightly.js.impl.Variables;
import org.apache.sling.scripting.sightly.js.impl.async.AsyncContainer;
import org.apache.sling.scripting.sightly.js.impl.async.AsyncExtractor;
import org.apache.sling.scripting.sightly.js.impl.async.TimingBindingsValuesProvider;
import org.apache.sling.scripting.sightly.js.impl.async.TimingFunction;
import org.apache.sling.scripting.sightly.js.impl.cjs.CommonJsModule;
import org.apache.sling.scripting.sightly.js.impl.rhino.HybridObject;
import org.apache.sling.scripting.sightly.js.impl.rhino.JsValueAdapter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the {@code sightly} namespace for usage in HTL &amp; JS scripts called from Sightly
 */
@Component(
        service = SlyBindingsValuesProvider.class,
        configurationPid = "org.apache.sling.scripting.sightly.js.impl.jsapi.SlyBindingsValuesProvider")
@Designate(ocd = SlyBindingsValuesProvider.Configuration.class)
@SuppressWarnings("unused")
public class SlyBindingsValuesProvider {

    @ObjectClassDefinition(
            name = "Apache Sling Scripting HTL JavaScript Use-API Factories Configuration",
            description = "HTL JavaScript Use-API Factories configuration options")
    @interface Configuration {

        @AttributeDefinition(
                name = "Script Factories",
                description = "Script factories to load in the bindings map. The entries should be in the form "
                        + "'namespace:/path/from/repository'. If the factories depend on each other, add them in the correct order of their"
                        + " dependency chain.")
        String[] org_apache_sling_scripting_sightly_js_bindings() default SlyBindingsValuesProvider.SLING_NS_PATH;
    }

    public static final String SCR_PROP_JS_BINDING_IMPLEMENTATIONS = "org.apache.sling.scripting.sightly.js.bindings";

    public static final String SLING_NS_PATH = "sightly:/libs/sling/sightly/js/internal/sly.js";
    public static final String Q_PATH = "/libs/sling/sightly/js/3rd-party/q.js";

    private static final String REQ_NS = SlyBindingsValuesProvider.class.getCanonicalName();

    private static final Logger LOGGER = LoggerFactory.getLogger(SlyBindingsValuesProvider.class);

    private final AsyncExtractor asyncExtractor = new AsyncExtractor();
    private final JsValueAdapter jsValueAdapter = new JsValueAdapter(asyncExtractor);

    private Map<String, String> scriptPaths = new HashMap<>();
    private Map<String, Function> factories = new HashMap<>();

    private Script qScript;
    private final ScriptableObject qScope = createQScope();

    public void initialise(ResourceResolver resourceResolver, JsEnvironment environment, Bindings bindings) {
        if (needsInit()) {
            init(resourceResolver, environment, bindings);
        }
    }

    public void processBindings(Bindings bindings) {
        if (needsInit()) {
            throw new SightlyException("Attempted to call processBindings without calling initialise first.");
        }
        Context context = null;
        try {
            context = Context.enter();
            Object qInstance = obtainQInstance(context, bindings);
            if (qInstance == null) {
                return;
            }
            for (Map.Entry<String, Function> entry : factories.entrySet()) {
                addBinding(context, entry.getValue(), bindings, entry.getKey(), qInstance);
            }
        } finally {
            if (context != null) {
                Context.exit();
            }
        }
    }

    public Map<String, String> getScriptPaths() {
        return Collections.unmodifiableMap(scriptPaths);
    }

    @Activate
    protected void activate(Configuration configuration) {
        String[] configuredFactories = PropertiesUtil.toStringArray(
                configuration.org_apache_sling_scripting_sightly_js_bindings(), new String[] {SLING_NS_PATH});
        scriptPaths = new LinkedHashMap<>(configuredFactories.length);
        for (String f : configuredFactories) {
            String[] parts = f.split(":");
            if (parts.length == 2) {
                scriptPaths.put(parts[0], parts[1]);
            }
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext componentContext) {
        if (scriptPaths != null) {
            scriptPaths.clear();
        }
        if (factories != null) {
            factories.clear();
        }
    }

    private void addBinding(Context context, Function factory, Bindings bindings, String globalName, Object qInstance) {
        if (factory == null) {
            return;
        }
        Object result = factory.call(context, factory, factory, new Object[] {bindings, qInstance});
        HybridObject global = new HybridObject((Scriptable) result, jsValueAdapter);
        bindings.put(globalName, global);
    }

    private boolean needsInit() {
        return factories == null || factories.isEmpty() || qScript == null;
    }

    private synchronized void init(ResourceResolver resourceResolver, JsEnvironment jsEnvironment, Bindings bindings) {
        if (needsInit()) {
            factories = new HashMap<>(scriptPaths.size());
            for (Map.Entry<String, String> entry : scriptPaths.entrySet()) {
                factories.put(entry.getKey(), loadFactory(resourceResolver, jsEnvironment, entry.getValue(), bindings));
            }
            qScript = loadQScript(resourceResolver);
        }
    }

    private Function loadFactory(
            ResourceResolver resolver, JsEnvironment jsEnvironment, String path, Bindings bindings) {
        Resource resource = resolver.getResource(path);
        if (resource == null) {
            throw new SightlyException("Sly namespace loader could not find the following script: " + path);
        }
        InputStream inputStream = resource.adaptTo(InputStream.class);
        if (inputStream == null) {
            throw new SightlyException("Sly namespace loader could not read the following script: " + path);
        }
        AsyncContainer container = jsEnvironment.runScript(
                new ScriptNameAwareReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8), resource.getPath()),
                createBindings(bindings, resource.getPath()),
                new LazyBindings());
        Object obj = container.getResult();
        if (!(obj instanceof Function)) {
            throw new SightlyException("Script " + path + " was expected to return a function.");
        }
        return (Function) obj;
    }

    private Bindings createBindings(Bindings global, String factoryPath) {
        Bindings bindings = new LazyBindings();
        bindings.putAll(global);
        bindings.put(ScriptEngine.FILENAME, factoryPath);
        TimingBindingsValuesProvider.INSTANCE.addBindings(bindings);
        return bindings;
    }

    private Object obtainQInstance(Context context, Bindings bindings) {
        if (qScript == null) {
            return null;
        }
        HttpServletRequest request = (HttpServletRequest) bindings.get(SlingBindings.REQUEST);
        Object qInstance = null;
        if (request != null) {
            qInstance = request.getAttribute(REQ_NS);
        }
        if (qInstance == null) {
            qInstance = createQInstance(context, qScript);
            if (request != null) {
                request.setAttribute(REQ_NS, qInstance);
            }
        }
        return qInstance;
    }

    private ScriptableObject createQScope() {
        Context context = Context.enter();
        try {
            ScriptableObject scope = context.initStandardObjects();
            ScriptableObject.putProperty(scope, Variables.SET_IMMEDIATE, TimingFunction.INSTANCE);
            ScriptableObject.putProperty(scope, Variables.SET_TIMEOUT, TimingFunction.INSTANCE);
            return scope;
        } finally {
            Context.exit();
        }
    }

    private Object createQInstance(Context context, Script qScript) {
        CommonJsModule module = new CommonJsModule();
        Scriptable tempScope = context.newObject(qScope);
        ScriptableObject.putProperty(tempScope, Variables.MODULE, module);
        ScriptableObject.putProperty(tempScope, Variables.EXPORTS, module.getExports());
        qScript.exec(context, tempScope);
        return module.getExports();
    }

    private Script loadQScript(ResourceResolver resolver) {
        Context context = Context.enter();
        context.initStandardObjects();
        context.setOptimizationLevel(9);
        Resource resource = resolver.getResource(Q_PATH);
        if (resource == null) {
            LOGGER.warn("Could not load Q library at path: " + Q_PATH);
            return null;
        }
        try (InputStream reader = resource.adaptTo(InputStream.class)) {
            if (reader == null) {
                LOGGER.warn("Could not read content of Q library");
                return null;
            }
            return context.compileReader(new InputStreamReader(reader, StandardCharsets.UTF_8), Q_PATH, 0, null);
        } catch (IOException e) {
            LOGGER.error("Unable to compile the Q library at path " + Q_PATH + ".", e);
        } finally {
            Context.exit();
        }
        return null;
    }
}
