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

package org.apache.sling.scripting.sightly.js.impl.use;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.script.Bindings;
import javax.script.ScriptEngine;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.scripting.SlingScript;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.engine.BundledUnitManager;
import org.apache.sling.scripting.sightly.js.impl.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves dependencies specified by the Use function
 */
public class DependencyResolver {

    private final BundledUnitManager bundledUnitManager;
    private final ResourceResolver scriptingResourceResolver;

    public DependencyResolver(@NotNull ResourceResolver scriptingResourceResolver, @Nullable BundledUnitManager bundledUnitManager) {
        this.bundledUnitManager = bundledUnitManager;
        this.scriptingResourceResolver = scriptingResourceResolver;
    }

    public @NotNull ScriptNameAwareReader resolve(Bindings bindings, String dependency) {
        if (!Utils.isJsScript(dependency)) {
            throw new SightlyException("Only JS scripts are allowed as dependencies. Invalid dependency: " + dependency);
        }
        ScriptNameAwareReader reader = null;
        IOException ioException = null;
        try {
            if (bundledUnitManager != null) {
                URL script = bundledUnitManager.getScript(bindings, dependency);
                if (script != null) {
                    reader = new ScriptNameAwareReader(new StringReader(IOUtils.toString(script, StandardCharsets.UTF_8)),
                            script.toExternalForm());
                }
            }
            if (reader == null) {
                Resource scriptResource = null;
                if (dependency.startsWith("/")) {
                    scriptResource = scriptingResourceResolver.getResource(dependency);
                }
                if (scriptResource == null) {
                    SlingScriptHelper scriptHelper = Utils.getHelper(bindings);
                    if (scriptHelper != null) {
                        String callerName = (String) bindings.get(ScriptEngine.FILENAME);
                        Resource caller = null;
                        if (StringUtils.isNotEmpty(callerName)) {
                            caller = scriptingResourceResolver.getResource(callerName);
                        }
                        SlingScript slingScript = scriptHelper.getScript();
                        if (caller == null && slingScript != null) {
                            caller = scriptingResourceResolver.getResource(slingScript.getScriptResource().getPath());
                        }
                        if (caller != null) {
                            scriptResource = Utils.getScriptResource(caller, dependency, bindings);
                        }
                    }
                }
                if (scriptResource != null) {
                    reader = new ScriptNameAwareReader(new StringReader(IOUtils.toString(scriptResource.adaptTo(InputStream.class),
                            StandardCharsets.UTF_8)), scriptResource.getPath());
                }
            }
        } catch (IOException e) {
            ioException = e;
        }
        if (reader == null) {
            SightlyException sightlyException = new SightlyException(String.format("Unable to load script dependency %s.", dependency));
            if (ioException != null) {
                sightlyException.initCause(ioException);
            }
            throw sightlyException;
        }
        return reader;
    }

}
