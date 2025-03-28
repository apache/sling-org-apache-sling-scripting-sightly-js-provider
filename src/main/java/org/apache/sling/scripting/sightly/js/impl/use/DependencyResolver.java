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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
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
            throw new SightlyException(
                    "Only JS scripts are allowed as dependencies. Invalid dependency: " + dependency);
        }
        // attempt to retrieve the dependency directly (as an absolute path or relative to the search paths)
        Resource scriptResource = scriptingResourceResolver.getResource(dependency);
        Resource caller = getCaller(bindings);
        if (caller != null) {
            Resource callerType = caller.getParent();
            if (scriptResource == null && callerType != null) {
                SlingHttpServletRequest request = (SlingHttpServletRequest) bindings.get(SlingBindings.REQUEST);
                String driverType = request.getResource().getResourceType();
                Resource driver = scriptingResourceResolver.getResource(driverType);
                if (driver != null) {
                    Resource hierarchyResource = getHierarchyResource(callerType, driver);
                    while (hierarchyResource != null && scriptResource == null) {
                        if (dependency.startsWith("..")) {
                            // relative path
                            String absolutePath =
                                    ResourceUtil.normalize(hierarchyResource.getPath() + "/" + dependency);
                            if (StringUtils.isNotEmpty(absolutePath)) {
                                scriptResource = scriptingResourceResolver.getResource(absolutePath);
                            }
                        } else {
                            scriptResource = hierarchyResource.getChild(dependency);
                        }
                        if (scriptResource == null) {
                            String nextType = hierarchyResource.getResourceSuperType();
                            if (nextType != null) {
                                hierarchyResource = scriptingResourceResolver.getResource(nextType);
                            } else {
                                hierarchyResource = null;
                            }
                        }
                    }
                }
                // cannot find a dependency relative to the resource type; locate it solely based on the caller
                if (scriptResource == null) {
                    if (dependency.startsWith("..")) {
                        // relative path
                        String absolutePath = ResourceUtil.normalize(callerType.getPath() + "/" + dependency);
                        if (StringUtils.isNotEmpty(absolutePath)) {
                            scriptResource = scriptingResourceResolver.getResource(absolutePath);
                        }
                    } else {
                        scriptResource = callerType.getChild(dependency);
                    }
                }
            }
        }
        if (scriptResource == null) {
            throw new SightlyException(String.format("Unable to load script dependency %s.", dependency));
        }
        InputStream scriptStream = scriptResource.adaptTo(InputStream.class);
        if (scriptStream == null) {
            throw new SightlyException(String.format("Unable to read script %s.", dependency));
        }
        return new ScriptNameAwareReader(
                new InputStreamReader(scriptStream, StandardCharsets.UTF_8), scriptResource.getPath());
    }

    private Resource getCaller(Bindings bindings) {
        Resource caller = null;
        String callerName = (String) bindings.get(ScriptEngine.FILENAME);
        if (StringUtils.isNotEmpty(callerName)) {
            caller = scriptingResourceResolver.getResource(callerName);
        }
        if (caller == null) {
            SlingScriptHelper scriptHelper = Utils.getHelper(bindings);
            if (scriptHelper != null) {
                caller = scriptingResourceResolver.getResource(
                        scriptHelper.getScript().getScriptResource().getPath());
            }
        }
        return caller;
    }

    private Resource getHierarchyResource(@NotNull Resource caller, @NotNull Resource driver) {
        if (caller.getPath().equals(driver.getPath())) {
            return caller;
        }
        if (isResourceType(caller, driver)) {
            return caller;
        }
        if (isResourceType(driver, caller)) {
            return driver;
        }
        int callerOverlayIndex = 0;
        int driverOverlayIndex = 0;
        String callerRelativePath = null;
        String driverRelativePath = null;
        int spIndex = 0;
        for (String sp : scriptingResourceResolver.getSearchPath()) {
            if (caller.getPath().startsWith(sp)) {
                callerRelativePath = caller.getPath().substring(sp.length());
                callerOverlayIndex = spIndex;
            }
            if (driver.getPath().startsWith(sp)) {
                driverRelativePath = driver.getPath().substring(sp.length());
                driverOverlayIndex = spIndex;
            }
            if (callerRelativePath != null && driverRelativePath != null) {
                break;
            }
            spIndex++;
        }
        if (callerRelativePath != null && callerRelativePath.equals(driverRelativePath)) {
            if (callerOverlayIndex < driverOverlayIndex) {
                return caller;
            }
            return driver;
        }
        return null;
    }

    private boolean isResourceType(@NotNull Resource resource, @NotNull Resource parent) {
        if (parent.getPath().equals(resource.getPath())) {
            return true;
        }
        String resourceSuperType = resource.getResourceSuperType();
        while (resourceSuperType != null) {
            Resource intermediateType = scriptingResourceResolver.getResource(resourceSuperType);
            if (intermediateType != null) {
                if (intermediateType.getPath().equals(parent.getPath())) {
                    return true;
                }
                resourceSuperType = intermediateType.getResourceSuperType();
            } else {
                return false;
            }
        }
        return false;
    }
}
