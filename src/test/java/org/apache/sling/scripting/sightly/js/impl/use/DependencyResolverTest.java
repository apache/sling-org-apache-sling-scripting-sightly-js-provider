/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.scripting.sightly.js.impl.use;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import javax.script.Bindings;
import javax.script.ScriptEngine;

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


}
