/*
 *
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
 *
 */
package org.apache.qpid.server.configuration.startup;

import junit.framework.TestCase;
import org.apache.qpid.server.configuration.ConfigurationEntry;
import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.model.adapter.StandardVirtualHostAdapter;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.store.MessageStore;
import org.apache.qpid.server.store.TestMemoryMessageStore;
import org.apache.qpid.server.virtualhost.StandardVirtualHostFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VirtualHostCreationTest extends TestCase
{

    public void testCreateVirtualHostFromStoreConfigAttributes()
    {
        SecurityManager securityManager = mock(SecurityManager.class);
        ConfigurationEntry entry = mock(ConfigurationEntry.class);
        Broker parent = mock(Broker.class);
        when(parent.getAttribute(Broker.VIRTUALHOST_HOUSEKEEPING_CHECK_PERIOD)).thenReturn(3000l);
        when(parent.getSecurityManager()).thenReturn(securityManager);

        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(VirtualHost.NAME, getName());
        attributes.put(VirtualHost.TYPE, StandardVirtualHostFactory.TYPE);

        attributes.put(VirtualHost.MESSAGE_STORE_SETTINGS, Collections.singletonMap(MessageStore.STORE_TYPE, TestMemoryMessageStore.TYPE));
        when(entry.getAttributes()).thenReturn(attributes);

        VirtualHost host = new StandardVirtualHostAdapter(UUID.randomUUID(),attributes,parent);

        assertNotNull("Null is returned", host);
        assertEquals("Unexpected name", getName(), host.getName());
    }

    public void testCreateWithoutMandatoryAttributesResultsInException()
    {
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(VirtualHost.NAME, getName());
        attributes.put(VirtualHost.TYPE, StandardVirtualHostFactory.TYPE);
        attributes.put(VirtualHost.MESSAGE_STORE_SETTINGS, Collections.singletonMap(MessageStore.STORE_TYPE,
                                                                                    TestMemoryMessageStore.TYPE));
        String[] mandatoryAttributes = {VirtualHost.NAME, VirtualHost.TYPE, VirtualHost.MESSAGE_STORE_SETTINGS};

        checkMandatoryAttributesAreValidated(mandatoryAttributes, attributes);
    }

    public void checkMandatoryAttributesAreValidated(String[] mandatoryAttributes, Map<String, Object> attributes)
    {
        SecurityManager securityManager = mock(SecurityManager.class);
        ConfigurationEntry entry = mock(ConfigurationEntry.class);
        Broker parent = mock(Broker.class);
        when(parent.getSecurityManager()).thenReturn(securityManager);

        for (String name : mandatoryAttributes)
        {
            Map<String, Object> copy = new HashMap<String, Object>(attributes);
            copy.remove(name);
            when(entry.getAttributes()).thenReturn(copy);
            try
            {
                VirtualHost host = new StandardVirtualHostAdapter(UUID.randomUUID(),copy,parent);
                fail("Cannot create a virtual host without a mandatory attribute " + name);
            }
            catch(IllegalConfigurationException e)
            {
                // pass
            }
            catch(IllegalArgumentException e)
            {
                // pass
            }
        }
    }
}