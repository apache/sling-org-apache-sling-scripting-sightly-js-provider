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
package org.apache.sling.scripting.sightly.js.impl.use;

import javax.script.Bindings;
import javax.script.ScriptEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.scripting.LazyBindings;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.js.impl.JsEnvironment;
import org.apache.sling.scripting.sightly.js.impl.Utils;
import org.apache.sling.scripting.sightly.js.impl.async.AsyncContainer;
import org.apache.sling.scripting.sightly.js.impl.loop.EventLoopInterop;
import org.apache.sling.scripting.sightly.js.impl.rhino.JsUtils;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * The JavaScript {@code use} function
 */
public class UseFunction extends BaseFunction {

    private final JsEnvironment jsEnvironment;
    private final DependencyResolver dependencyResolver;
    private final Scriptable thisObj;
    private final Bindings globalBindings;

    public UseFunction(
            JsEnvironment jsEnvironment,
            DependencyResolver dependencyResolver,
            Bindings globalBindings,
            Bindings arguments) {
        this.jsEnvironment = jsEnvironment;
        this.dependencyResolver = dependencyResolver;
        this.globalBindings = globalBindings;
        this.thisObj = createThisBinding(arguments);
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        Function function;
        List<String> depNames;
        if (args.length == 0) {
            throw new IllegalArgumentException("Not enough arguments for use");
        } else if (args.length == 1) {
            function = decodeCallback(args[0]);
            depNames = Collections.emptyList();
        } else {
            function = decodeCallback(args[1]);
            depNames = decodeDepNames(args[0]);
        }
        return use(depNames, function, cx, scope);
    }

    private Object use(List<String> depNames, final Function callback, final Context cx, final Scriptable scope) {
        final AsyncContainer asyncContainer = new AsyncContainer();
        if (depNames.isEmpty()) {
            callImmediate(callback, asyncContainer, cx, scope);
        } else {
            final int[] counter = {depNames.size()};
            final Object[] dependencies = new Object[depNames.size()];
            for (int i = 0; i < depNames.size(); i++) {
                final int dependencyPos = i;
                String dependency = depNames.get(i);
                ScriptNameAwareReader dependencyReader = dependencyResolver.resolve(globalBindings, dependency);
                if (dependencyReader == null) {
                    throw new SightlyException("Cannot locate script " + dependency);
                }
                Bindings bindings = new LazyBindings();
                bindings.putAll(globalBindings);
                bindings.put(ScriptEngine.FILENAME, dependencyReader.getScriptName());
                jsEnvironment.runScript(dependencyReader, bindings, Utils.EMPTY_BINDINGS, arg -> {
                    counter[0]--;
                    dependencies[dependencyPos] = arg;
                    if (counter[0] == 0) {
                        Object result = JsUtils.callFn(callback, cx, scope, thisObj, dependencies);
                        asyncContainer.complete(result);
                    }
                });
            }
        }
        return asyncContainer;
    }

    private void callImmediate(
            final Function callback, final AsyncContainer asyncContainer, final Context cx, final Scriptable scope) {
        EventLoopInterop.schedule(cx, () -> {
            Object value = JsUtils.callFn(callback, cx, scope, thisObj, new Object[0]);
            asyncContainer.complete(value);
        });
    }

    private Function decodeCallback(Object obj) {
        if (!(obj instanceof Function)) {
            throw new IllegalArgumentException("No callback argument supplied");
        }
        return (Function) obj;
    }

    private List<String> decodeDepNames(Object obj) {
        if (obj instanceof NativeArray) {
            return decodeDepArray((NativeArray) obj);
        }
        return Collections.singletonList(jsToString(obj));
    }

    private List<String> decodeDepArray(NativeArray nativeArray) {
        int depLength = (int) nativeArray.getLength();
        List<String> depNames = new ArrayList<>(depLength);
        for (int i = 0; i < depLength; i++) {
            String depName = jsToString(nativeArray.get(i, nativeArray));
            depNames.add(depName);
        }
        return depNames;
    }

    private String jsToString(Object obj) {
        return (String) Context.jsToJava(obj, String.class);
    }

    private Scriptable createThisBinding(Bindings arguments) {
        NativeObject nativeObject = new NativeObject();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            ScriptableObject.putProperty(nativeObject, entry.getKey(), entry.getValue());
        }
        return nativeObject;
    }
}
