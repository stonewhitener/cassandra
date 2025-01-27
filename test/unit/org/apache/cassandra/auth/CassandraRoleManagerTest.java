/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.schema.SchemaConstants;
import org.apache.cassandra.service.StorageService;
import org.assertj.core.api.Assertions;

import static org.apache.cassandra.auth.AuthTestUtils.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CassandraRoleManagerTest
{
    private static final Logger logger = LoggerFactory.getLogger(CassandraRoleManagerTest.class);

    @BeforeClass
    public static void setupClass()
    {
        SchemaLoader.prepareServer();
        // We start StorageService because confirmFastRoleSetup confirms that CassandraRoleManager will
        // take a faster path once the cluster is already setup, which includes checking MessagingService
        // and issuing queries with QueryProcessor.process, which uses TokenMetadata
        DatabaseDescriptor.daemonInitialization();
        StorageService.instance.initServer();
        AuthCacheService.initializeAndRegisterCaches();
    }

    @Before
    public void setup() throws Exception
    {
        ColumnFamilyStore.getIfExists(SchemaConstants.AUTH_KEYSPACE_NAME, AuthKeyspace.ROLES).truncateBlocking();
        ColumnFamilyStore.getIfExists(SchemaConstants.AUTH_KEYSPACE_NAME, AuthKeyspace.ROLE_MEMBERS).truncateBlocking();
    }

    @Test
    public void getGrantedRolesImplMinimizesReads()
    {
        // IRoleManager::getRoleDetails was not in the initial API, so a default impl
        // was added which uses the existing methods on IRoleManager as primitive to
        // construct the Role objects. While this will work for any IRoleManager impl
        // it is inefficient, so CassandraRoleManager has its own implementation which
        // collects all of the necessary info with a single query for each granted role.
        // This just tests that that is the case, i.e. we perform 1 read per role in the
        // transitive set of granted roles
        IRoleManager roleManager = new AuthTestUtils.LocalCassandraRoleManager();
        roleManager.setup();
        for (RoleResource r : ALL_ROLES)
            roleManager.createRole(AuthenticatedUser.ANONYMOUS_USER, r, new RoleOptions());

        // simple role with no grants
        fetchRolesAndCheckReadCount(roleManager, ROLE_A);
        // single level of grants
        grantRolesTo(roleManager, ROLE_A, ROLE_B, ROLE_C);
        fetchRolesAndCheckReadCount(roleManager, ROLE_A);

        // multi level role hierarchy
        grantRolesTo(roleManager, ROLE_B, ROLE_B_1, ROLE_B_2, ROLE_B_3);
        grantRolesTo(roleManager, ROLE_C, ROLE_C_1, ROLE_C_2, ROLE_C_3);
        fetchRolesAndCheckReadCount(roleManager, ROLE_A);

        // Check that when granted roles appear multiple times in parallel levels of the hierarchy, we don't
        // do redundant reads. E.g. here role_b_1, role_b_2 and role_b3 are granted to both role_b and role_c
        // but we only want to actually read them once
        grantRolesTo(roleManager, ROLE_C, ROLE_B_1, ROLE_B_2, ROLE_B_3);
        fetchRolesAndCheckReadCount(roleManager, ROLE_A);
    }

    private void fetchRolesAndCheckReadCount(IRoleManager roleManager, RoleResource primaryRole)
    {
        long before = getRolesReadCount();
        Set<Role> granted = roleManager.getRoleDetails(primaryRole);
        long after = getRolesReadCount();
        assertEquals(granted.size(), after - before);
    }

    @Test
    public void confirmFastRoleSetup()
    {
        IRoleManager roleManager = new AuthTestUtils.LocalCassandraRoleManager();
        roleManager.setup();
        for (RoleResource r : ALL_ROLES)
            roleManager.createRole(AuthenticatedUser.ANONYMOUS_USER, r, new RoleOptions());

        CassandraRoleManager crm = new CassandraRoleManager();

        assertTrue("Expected the role manager to have existing roles before CassandraRoleManager setup", CassandraRoleManager.hasExistingRoles());
    }

    @Test
    public void warmCacheLoadsAllEntries()
    {
        IRoleManager roleManager = new AuthTestUtils.LocalCassandraRoleManager();
        roleManager.setup();
        for (RoleResource r : ALL_ROLES)
            roleManager.createRole(AuthenticatedUser.ANONYMOUS_USER, r, new RoleOptions());

        // Multi level role hierarchy
        grantRolesTo(roleManager, ROLE_B, ROLE_B_1, ROLE_B_2, ROLE_B_3);
        grantRolesTo(roleManager, ROLE_C, ROLE_C_1, ROLE_C_2, ROLE_C_3);

        // Use CassandraRoleManager to get entries for pre-warming a cache, then verify those entries
        CassandraRoleManager crm = new CassandraRoleManager();
        crm.setup();
        Map<RoleResource, Set<Role>> cacheEntries = crm.bulkLoader().get();

        Set<Role> roleBRoles = cacheEntries.get(ROLE_B);
        assertRoleSet(roleBRoles, ROLE_B, ROLE_B_1, ROLE_B_2, ROLE_B_3);

        Set<Role> roleCRoles = cacheEntries.get(ROLE_C);
        assertRoleSet(roleCRoles, ROLE_C, ROLE_C_1, ROLE_C_2, ROLE_C_3);

        for (RoleResource r : ALL_ROLES)
        {
            // We already verified ROLE_B and ROLE_C
            if (r.equals(ROLE_B) || r.equals(ROLE_C))
                continue;

            // Check the cache entries for the roles without any further grants
            assertRoleSet(cacheEntries.get(r), r);
        }
    }

    @Test
    public void warmCacheWithEmptyTable()
    {
        CassandraRoleManager crm = new CassandraRoleManager();
        crm.setup();
        Map<RoleResource, Set<Role>> cacheEntries = crm.bulkLoader().get();
        assertTrue(cacheEntries.isEmpty());
    }

    private void assertRoleSet(Set<Role> actual, RoleResource...expected)
    {
        assertEquals(expected.length, actual.size());

        for (RoleResource expectedRole : expected)
            assertTrue(actual.stream().anyMatch(role -> role.resource.equals(expectedRole)));
    }

    @Test
    public void disconnectsAttemptedOnPeriodWithJitter() throws InterruptedException
    {
        AtomicInteger numDisconnectAttempts = new AtomicInteger();

        // min: 800ms, max: 900ms
        Map<String, String> params = Map.of(
            CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_PERIOD, "800ms",
            CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_MAX_JITTER, "100ms"
        );

        CassandraRoleManager crm = new CassandraRoleManager(params) {
            @Override
            protected void disconnectInvalidRoles()
            {
                logger.info("Disconnecting invalid roles...");
                numDisconnectAttempts.incrementAndGet();
            }
        };

        crm.scheduleDisconnectInvalidRoleTask();
        Thread.sleep(3_000);
        Assertions.assertThat(numDisconnectAttempts.get()).isGreaterThanOrEqualTo(3);
        Assertions.assertThat(numDisconnectAttempts.get()).isLessThan(4);
        numDisconnectAttempts.set(0);

        crm.setInvalidClientDisconnectPeriodMillis(100); // min: 100ms, max: 200ms
        Thread.sleep(3_000);
        Assertions.assertThat(numDisconnectAttempts.get()).isGreaterThanOrEqualTo(10); // 15 - padding
        Assertions.assertThat(numDisconnectAttempts.get()).isLessThan(30);

        crm.setInvalidClientDisconnectPeriodMillis(0);
        int totalDisconnectAttempts = numDisconnectAttempts.get();
        Thread.sleep(3_000);
        Assertions.assertThat(numDisconnectAttempts.get()).isEqualTo(totalDisconnectAttempts);
    }

    @Test
    public void ctorInvalidRoleDisconnectOptions()
    {
        CassandraRoleManager crm = new CassandraRoleManager(Map.of());
        Assertions.assertThat(crm.getInvalidClientDisconnectPeriodMillis()).isEqualTo(0);
        Assertions.assertThat(crm.getInvalidClientDisconnectMaxJitterMillis()).isEqualTo(0);

        crm = new CassandraRoleManager(Map.of(
            CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_PERIOD, "1s",
            CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_MAX_JITTER, "2s"
        ));
        Assertions.assertThat(crm.getInvalidClientDisconnectPeriodMillis()).isEqualTo(1000);
        Assertions.assertThat(crm.getInvalidClientDisconnectMaxJitterMillis()).isEqualTo(2000);

        // Non-duration input
        Map<String, String> params = new HashMap<>();
        params.put(CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_PERIOD, "notduration");
        Assertions.assertThatThrownBy(() -> new CassandraRoleManager(params)).isOfAnyClassIn(IllegalArgumentException.class).hasMessageContaining("Invalid duration: ");

        // Both fields optional
        crm = new CassandraRoleManager(Map.of(
            CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_PERIOD, "1s"
            // No jitter
        ));
        Assertions.assertThat(crm.getInvalidClientDisconnectPeriodMillis()).isEqualTo(1000);
        Assertions.assertThat(crm.getInvalidClientDisconnectMaxJitterMillis()).isEqualTo(0);

        crm = new CassandraRoleManager(Map.of(
            // No period
            CassandraRoleManager.PARAM_INVALID_ROLE_DISCONNECT_TASK_MAX_JITTER, "1s"
        ));
        Assertions.assertThat(crm.getInvalidClientDisconnectPeriodMillis()).isEqualTo(0);
        Assertions.assertThat(crm.getInvalidClientDisconnectMaxJitterMillis()).isEqualTo(1000);
    }
}
