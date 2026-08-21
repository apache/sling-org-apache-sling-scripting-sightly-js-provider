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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DependencyResolverTest {

    private static final String CALLER_PATH = "/libs/caller/caller.html";
    private static final String SCRIPT_PATH = "/libs/caller/caller.js";

    private static final String[] SEARCH_PATH = {"/apps/", "/libs/"};

    private static final String XF_TYPE = "cq/experience-fragments/components/xfpage";
    private static final String APPS_XF = "/apps/" + XF_TYPE;
    private static final String LIBS_XF = "/libs/" + XF_TYPE;
    private static final String FOUNDATION_TYPE = "wcm/foundation/components/page";
    private static final String LIBS_FOUNDATION = "/libs/" + FOUNDATION_TYPE;
    private static final String CALLER_HTL = LIBS_XF + "/head.nocloudconfigs.html";
    private static final String HEAD_JS = LIBS_FOUNDATION + "/head.js";

    @Mock
    private ResourceResolver scriptingResourceResolver;

    @Mock
    private Resource caller;

    @Mock
    private Resource callerParent;

    @Mock
    private Resource dependency;

    @Mock
    private Resource content;

    @Mock
    private SlingHttpServletRequest request;

    private DependencyResolver dependencyResolver;
    private Bindings bindings;

    @BeforeEach
    void beforeEach() {
        when(dependency.getPath()).thenReturn(SCRIPT_PATH);
        when(caller.getParent()).thenReturn(callerParent);
        when(scriptingResourceResolver.getResource(CALLER_PATH)).thenReturn(caller);
        when(scriptingResourceResolver.getResource(SCRIPT_PATH)).thenReturn(dependency);
        when(scriptingResourceResolver.getSearchPath()).thenReturn(SEARCH_PATH);
        dependencyResolver = new DependencyResolver(scriptingResourceResolver);
        bindings = new SlingBindings();
        bindings.put(ScriptEngine.FILENAME, CALLER_PATH);
        bindings.put(SlingBindings.REQUEST, request);
    }

    @Test
    void testResourceLoading_streamNotRead() throws IOException {
        InputStream stream = mock(InputStream.class);

        // Configure the mock to return data and simulate EOF
        byte[] mockData = "mocked content".getBytes();
        AtomicInteger readCount = new AtomicInteger(0);

        // Simulate reading data and EOF
        when(stream.read(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int offset = invocation.getArgument(1);
            int length = invocation.getArgument(2);

            // Check if there's data left to read
            if (readCount.get() >= mockData.length) {
                return -1; // Simulate EOF
            }

            // Simulate reading from the data
            int bytesRead = Math.min(length, mockData.length - readCount.get());
            System.arraycopy(mockData, readCount.get(), invocation.getArgument(0), offset, bytesRead);
            readCount.addAndGet(bytesRead);
            return bytesRead;
        });

        when(stream.read()).thenAnswer(invocation -> {
            // Simulate single-byte reads
            if (readCount.get() >= mockData.length) {
                return -1; // Simulate EOF
            }
            return (int) mockData[readCount.getAndIncrement()];
        });

        when(dependency.adaptTo(InputStream.class)).thenReturn(stream);

        ScriptNameAwareReader reader = dependencyResolver.resolve(bindings, SCRIPT_PATH);

        assertNotNull(reader);
        assertEquals(SCRIPT_PATH, reader.getScriptName());

        verify(stream, never()).read(any(), anyInt(), anyInt());
        verify(stream, never()).read();
        verify(stream, never()).close();
    }

    /**
     * Regression: /apps overlay of xfpage without sling:resourceSuperType (and without head.js)
     * must still resolve inherited head.js from the foundation page via the /libs caller.
     */
    @Test
    void resolveInheritedDependency_appsOverlayWithoutSuperType() {
        Resource libsXf = mockResource(LIBS_XF, FOUNDATION_TYPE);
        Resource appsXf = mockResource(APPS_XF, null);
        Resource foundation = mockResource(LIBS_FOUNDATION, null);
        Resource headJs = mockJsResource(HEAD_JS);
        Resource callerHtl = mockResource(CALLER_HTL, "nt:file");
        Resource contentResource = mockContent("/content/xf/master/jcr:content", XF_TYPE);

        when(callerHtl.getParent()).thenReturn(libsXf);
        when(libsXf.getChild("head.js")).thenReturn(null);
        when(appsXf.getChild("head.js")).thenReturn(null);
        when(foundation.getChild("head.js")).thenReturn(headJs);

        when(scriptingResourceResolver.getResource(CALLER_HTL)).thenReturn(callerHtl);
        when(scriptingResourceResolver.getResource(any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (CALLER_HTL.equals(path)) {
                return callerHtl;
            }
            if ("head.js".equals(path)) {
                return null;
            }
            if (XF_TYPE.equals(path) || APPS_XF.equals(path)) {
                return appsXf;
            }
            if (LIBS_XF.equals(path)) {
                return libsXf;
            }
            if (FOUNDATION_TYPE.equals(path) || LIBS_FOUNDATION.equals(path)) {
                return foundation;
            }
            if (HEAD_JS.equals(path)) {
                return headJs;
            }
            return null;
        });

        when(request.getResource()).thenReturn(contentResource);

        bindings.put(ScriptEngine.FILENAME, CALLER_HTL);

        ScriptNameAwareReader reader = dependencyResolver.resolve(bindings, "head.js");
        assertNotNull(reader);
        assertEquals(HEAD_JS, reader.getScriptName());
    }

    /**
     * Overlay under /apps that provides head.js must win when resolving relative to the /libs caller.
     */
    @Test
    void resolveDependency_prefersAppsOverlayScript() {
        String appsHeadJs = APPS_XF + "/head.js";
        Resource libsXf = mockResource(LIBS_XF, FOUNDATION_TYPE);
        Resource appsXf = mockResource(APPS_XF, FOUNDATION_TYPE);
        Resource appsHead = mockJsResource(appsHeadJs);
        Resource callerHtl = mockResource(CALLER_HTL, "nt:file");
        Resource contentResource = mockContent("/content/xf/master/jcr:content", XF_TYPE);

        when(callerHtl.getParent()).thenReturn(libsXf);
        when(libsXf.getChild("head.js")).thenReturn(null);
        when(appsXf.getChild("head.js")).thenReturn(appsHead);

        when(scriptingResourceResolver.getResource(any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (CALLER_HTL.equals(path)) {
                return callerHtl;
            }
            if ("head.js".equals(path)) {
                return null;
            }
            if (XF_TYPE.equals(path) || APPS_XF.equals(path)) {
                return appsXf;
            }
            if (LIBS_XF.equals(path)) {
                return libsXf;
            }
            if (appsHeadJs.equals(path)) {
                return appsHead;
            }
            return null;
        });
        when(request.getResource()).thenReturn(contentResource);

        bindings.put(ScriptEngine.FILENAME, CALLER_HTL);

        ScriptNameAwareReader reader = dependencyResolver.resolve(bindings, "head.js");
        assertNotNull(reader);
        assertEquals(appsHeadJs, reader.getScriptName());
    }

    /**
     * SLING-9657: a Use script next to the caller (partials/head.js) must win over a same-named
     * script on a resourceSuperType.
     */
    @Test
    void resolveDependency_localCallerWinsOverSuperType() {
        String projectPage = "/apps/project/page";
        String partials = projectPage + "/partials";
        String localHead = partials + "/head.js";
        String superHead = "/apps/page/head.js";
        String callerHtl = partials + "/head.html";

        Resource partialsResource = mockResource(partials, null);
        Resource projectPageResource = mockResource(projectPage, "page");
        Resource pageResource = mockResource("/apps/page", null);
        Resource localHeadResource = mockJsResource(localHead);
        Resource superHeadResource = mockJsResource(superHead);
        Resource callerHtlResource = mockResource(callerHtl, "nt:file");
        Resource contentResource = mockContent("/content/page", "project/page");

        when(callerHtlResource.getParent()).thenReturn(partialsResource);
        when(partialsResource.getChild("head.js")).thenReturn(localHeadResource);
        when(projectPageResource.getChild("head.js")).thenReturn(null);
        when(pageResource.getChild("head.js")).thenReturn(superHeadResource);

        when(scriptingResourceResolver.getResource(any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (callerHtl.equals(path)) {
                return callerHtlResource;
            }
            if ("head.js".equals(path)) {
                return null;
            }
            if ("project/page".equals(path) || projectPage.equals(path)) {
                return projectPageResource;
            }
            if ("page".equals(path) || "/apps/page".equals(path)) {
                return pageResource;
            }
            if (localHead.equals(path)) {
                return localHeadResource;
            }
            if (superHead.equals(path)) {
                return superHeadResource;
            }
            return null;
        });
        when(request.getResource()).thenReturn(contentResource);

        bindings.put(ScriptEngine.FILENAME, callerHtl);

        ScriptNameAwareReader reader = dependencyResolver.resolve(bindings, "head.js");
        assertNotNull(reader);
        assertEquals(localHead, reader.getScriptName());
    }

    @Test
    void resolveDependency_missingThrows() {
        Resource libsXf = mockResource(LIBS_XF, null);
        Resource appsXf = mockResource(APPS_XF, null);
        Resource callerHtl = mockResource(CALLER_HTL, "nt:file");
        Resource contentResource = mockContent("/content/xf/master/jcr:content", XF_TYPE);

        when(callerHtl.getParent()).thenReturn(libsXf);
        when(libsXf.getChild("head.js")).thenReturn(null);
        when(appsXf.getChild("head.js")).thenReturn(null);

        when(scriptingResourceResolver.getResource(any())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            if (CALLER_HTL.equals(path)) {
                return callerHtl;
            }
            if (XF_TYPE.equals(path) || APPS_XF.equals(path)) {
                return appsXf;
            }
            if (LIBS_XF.equals(path)) {
                return libsXf;
            }
            return null;
        });
        when(request.getResource()).thenReturn(contentResource);

        bindings.put(ScriptEngine.FILENAME, CALLER_HTL);

        assertThrows(
                org.apache.sling.scripting.sightly.SightlyException.class,
                () -> dependencyResolver.resolve(bindings, "head.js"));
    }

    private static Resource mockResource(String path, String resourceSuperType) {
        Resource resource = mock(Resource.class);
        when(resource.getPath()).thenReturn(path);
        when(resource.getResourceSuperType()).thenReturn(resourceSuperType);
        return resource;
    }

    private static Resource mockContent(String path, String resourceType) {
        Resource resource = mock(Resource.class);
        when(resource.getPath()).thenReturn(path);
        when(resource.getResourceType()).thenReturn(resourceType);
        return resource;
    }

    private static Resource mockJsResource(String path) {
        Resource resource = mockResource(path, null);
        when(resource.adaptTo(InputStream.class))
                .thenReturn(new ByteArrayInputStream("use(function(){});".getBytes(StandardCharsets.UTF_8)));
        return resource;
    }
}
