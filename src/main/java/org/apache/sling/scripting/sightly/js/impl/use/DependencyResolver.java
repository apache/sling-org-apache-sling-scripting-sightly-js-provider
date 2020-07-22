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
import java.nio.charset.StandardCharsets;

import javax.script.Bindings;
import javax.script.ScriptEngine;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.js.impl.Utils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves dependencies specified by the Use function
 */
public class DependencyResolver {

    private final ResourceResolver scriptingResourceResolver;

    public DependencyResolver(@NotNull ResourceResolver scriptingResourceResolver) {
        this.scriptingResourceResolver = scriptingResourceResolver;
    }

    public @Nullable ScriptNameAwareReader resolve(Bindings bindings, String dependency) {
        if (!Utils.isJsScript(dependency)) {
            throw new SightlyException("Only JS scripts are allowed as dependencies. Invalid dependency: " + dependency);
        }
        ScriptNameAwareReader reader = null;
        IOException ioException = null;
        try {
            Resource scriptResource = null;
            if (dependency.startsWith("/")) {
                scriptResource = scriptingResourceResolver.getResource(dependency);
            }
            if (scriptResource == null) {
                String callerName = (String) bindings.get(ScriptEngine.FILENAME);
                Resource caller = null;
                if (StringUtils.isNotEmpty(callerName)) {
                    caller = scriptingResourceResolver.getResource(callerName);
                }
                if (caller == null) {
                    SlingScriptHelper scriptHelper = Utils.getHelper(bindings);
                    if (scriptHelper != null) {
                        caller = scriptHelper.getScript().getScriptResource();
                    }
                }
                if (caller != null && Utils.isJsScript(caller.getName()) &&
                        ("sling/bundle/resource".equals(caller.getResourceType()) || "nt:file".equals(caller.getResourceType()))) {
                    if (dependency.startsWith("..")) {
                        scriptResource = caller.getChild(dependency);
                    } else {
                        caller = caller.getParent();
                        if (caller != null) {
                            scriptResource = caller.getChild(dependency);
                        }
                    }
                }

            }
            if (scriptResource == null) {
                Resource requestResource = (Resource) bindings.get(SlingBindings.RESOURCE);
                String type = requestResource.getResourceType();
                while (scriptResource == null && type != null) {
                    Resource servletResource = null;
                    if (!type.startsWith("/")) {
                        for (String searchPath : scriptingResourceResolver.getSearchPath()) {
                            String normalizedPath = ResourceUtil.normalize(searchPath + "/" + type);
                            servletResource = resolveServletResource(normalizedPath);
                            if (servletResource != null) {
                                break;
                            }
                        }
                    } else {
                        servletResource = resolveServletResource(type);
                    }
                    if (servletResource != null) {
                        if (dependency.startsWith(".")) {
                            // relative path
                            String absolutePath = ResourceUtil.normalize(servletResource.getPath() + "/" + dependency);
                            if (StringUtils.isNotEmpty(absolutePath)) {
                                scriptResource = scriptingResourceResolver.resolve(absolutePath);
                            }
                        } else {
                            scriptResource = servletResource.getChild(dependency);
                        }
                        type = servletResource.getResourceSuperType();
                    } else {
                        type = null;
                    }
                }
            }

            if (scriptResource == null) {
                throw  new SightlyException(String.format("Unable to load script dependency %s.", dependency));
            }
            reader = new ScriptNameAwareReader(new StringReader(IOUtils.toString(scriptResource.adaptTo(InputStream.class),
                    StandardCharsets.UTF_8)), scriptResource.getPath());
        } catch (IOException e) {
            ioException = e;
        }
        if (ioException != null) {
            throw new SightlyException(String.format("Unable to load script dependency %s.", dependency), ioException);
        }
        return reader;
    }

    Resource resolveServletResource(String type) {
        Resource servletResource = scriptingResourceResolver.resolve(type);
        if (ResourceUtil.isNonExistingResource(servletResource)) {
            servletResource = scriptingResourceResolver.getResource(type);
        }
        return servletResource;
    }

}
