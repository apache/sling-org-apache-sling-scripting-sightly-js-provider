/*******************************************************************************
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
 ******************************************************************************/
package org.apache.sling.scripting.sightly.js.impl;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import org.apache.commons.io.IOUtils;
import org.apache.sling.api.scripting.LazyBindings;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.js.impl.async.AsyncContainer;
import org.apache.sling.scripting.sightly.js.impl.async.TimingBindingsValuesProvider;
import org.apache.sling.scripting.sightly.js.impl.async.UnaryCallback;
import org.apache.sling.scripting.sightly.js.impl.cjs.CommonJsModule;
import org.apache.sling.scripting.sightly.js.impl.loop.EventLoop;
import org.apache.sling.scripting.sightly.js.impl.loop.EventLoopInterop;
import org.apache.sling.scripting.sightly.js.impl.loop.Task;
import org.apache.sling.scripting.sightly.js.impl.use.DependencyResolver;
import org.apache.sling.scripting.sightly.js.impl.use.UseFunction;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.slf4j.LoggerFactory;

/**
 * Environment for running JS scripts
 */
public class JsEnvironment {

    private final ScriptEngine jsEngine;
    private final Bindings engineBindings;
    private final DependencyResolver dependencyResolver;
    private EventLoop eventLoop;

    public JsEnvironment(@NotNull ScriptEngine jsEngine,
                         @NotNull DependencyResolver dependencyResolver) {
        this.jsEngine = jsEngine;
        this.dependencyResolver = dependencyResolver;
        engineBindings = new LazyBindings();
        TimingBindingsValuesProvider.INSTANCE.addBindings(engineBindings);
    }

    public void initialize() {
        Context context = Context.enter();
        eventLoop = EventLoopInterop.obtainEventLoop(context);
    }

    public void cleanup() {
        Context context = Context.getCurrentContext();
        if (context == null) {
            throw new IllegalStateException("No current context");
        }
        EventLoopInterop.cleanupEventLoop(context);
        Context.exit();
    }

    public void runScript(ScriptNameAwareReader reader, Bindings globalBindings, Bindings arguments, UnaryCallback callback) {
        ScriptContext scriptContext = new SimpleScriptContext();
        CommonJsModule module = new CommonJsModule();
        Bindings scriptBindings = buildBindings(reader, globalBindings, arguments, module);
        scriptContext.setBindings(scriptBindings, ScriptContext.ENGINE_SCOPE);
        runScript(reader, scriptContext, callback, module);
    }

    public AsyncContainer runScript(ScriptNameAwareReader reader, Bindings globalBindings, Bindings arguments) {
        AsyncContainer asyncContainer = new AsyncContainer();
        runScript(reader, globalBindings, arguments, asyncContainer.createCompletionCallback());
        return asyncContainer;
    }

    private Bindings buildBindings(ScriptNameAwareReader reader, Bindings globalBindings, Bindings arguments, CommonJsModule commonJsModule) {
        Bindings bindings = new LazyBindings();
        bindings.putAll(engineBindings);
        bindings.put(ScriptEngine.FILENAME, reader.getScriptName());
        bindings.put(Variables.MODULE, commonJsModule);
        bindings.put(Variables.EXPORTS, commonJsModule.getExports());
        bindings.put(Variables.CONSOLE, new Console(LoggerFactory.getLogger(reader.getScriptName())));
        bindings.putAll(globalBindings);
        UseFunction useFunction = new UseFunction(this, dependencyResolver, bindings, arguments);
        bindings.put(Variables.JS_USE, useFunction);
        return bindings;
    }

    private void runScript(ScriptNameAwareReader reader, ScriptContext scriptContext, UnaryCallback callback, CommonJsModule commonJsModule) {
        eventLoop.schedule(scriptTask(reader, scriptContext, callback, commonJsModule));
    }

    private Task scriptTask(final ScriptNameAwareReader reader, final ScriptContext scriptContext,
                            final UnaryCallback callback, final CommonJsModule commonJsModule) {
        return new Task(() -> {
            try {
                Object result;
                if (jsEngine instanceof Compilable) {
                    result = ((Compilable) jsEngine).compile(reader).eval(scriptContext);
                } else {
                    result = jsEngine.eval(reader, scriptContext);
                }
                if (commonJsModule.isModified()) {
                    result = commonJsModule.getExports();
                }
                if (result instanceof AsyncContainer) {
                    ((AsyncContainer) result).addListener(callback);
                } else {
                    callback.invoke(result);
                }
            } catch (ScriptException e) {
                throw new SightlyException(e);
            } finally {
                IOUtils.closeQuietly(reader);
            }
        });
    }


}
