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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.core.ScriptNameAwareReader;
import org.apache.sling.scripting.sightly.js.impl.JsEnvironment;
import org.apache.sling.scripting.sightly.js.impl.async.AsyncContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mozilla.javascript.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlyBindingsValuesProviderTest {

    @Mock
    private ResourceResolver resolver;

    @Mock
    private Resource scriptResource;

    @Mock
    private SlyBindingsValuesProvider.Configuration configuration;

    @Mock
    private JsEnvironment jsEnvironment;

    @Mock
    private AsyncContainer asyncContainer;

    @Mock
    private Function function;

    private InputStream inputStream;

    @BeforeEach
    void setUp() {
        String scriptPath = SlyBindingsValuesProvider.SLING_NS_PATH.split(":")[1];
        inputStream = spy(Objects.requireNonNull(getClass().getResourceAsStream("/SLING-INF" + scriptPath)));
        when(scriptResource.getPath()).thenReturn(scriptPath);
        when(resolver.getResource(scriptPath)).thenReturn(scriptResource);
        when(scriptResource.adaptTo(InputStream.class)).thenReturn(inputStream);
        when(asyncContainer.getResult()).thenReturn(function);
        when(jsEnvironment.runScript(any(ScriptNameAwareReader.class), any(Bindings.class), any(Bindings.class)))
                .thenReturn(asyncContainer);
    }

    @Test
    void testResourceLoading_streamNotRead() throws IOException {
        assertNotNull(inputStream);
        SlyBindingsValuesProvider provider = new SlyBindingsValuesProvider();
        provider.activate(configuration);
        provider.initialise(resolver, jsEnvironment, new SlingBindings());
        verify(inputStream, never()).read();
        verify(inputStream, never()).read(any(byte[].class));
        verify(inputStream, never()).read(any(byte[].class), anyInt(), anyInt());
    }
}
