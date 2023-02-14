/*
 *    Copyright (c) 2023, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.test.multitenant;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.supertokens.ProcessState;
import io.supertokens.config.Config;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlagTestContent;
import io.supertokens.pluginInterface.exceptions.DbInitException;
import io.supertokens.pluginInterface.exceptions.InvalidConfigException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.exceptions.StorageTransactionLogicException;
import io.supertokens.pluginInterface.multitenancy.*;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.session.accessToken.AccessTokenSigningKey;
import io.supertokens.storageLayer.StorageLayer;
import io.supertokens.test.TestingProcessManager;
import io.supertokens.test.Utils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import java.io.IOException;

import static org.junit.Assert.*;

public class SigningKeysTest {
    @Rule
    public TestRule watchman = Utils.getOnFailure();

    @AfterClass
    public static void afterTesting() {
        Utils.afterTesting();
    }

    @Before
    public void beforeEach() {
        Utils.reset();
    }

    @Test
    public void normalConfigContinuesToWork()
            throws InterruptedException, IOException, StorageQueryException, StorageTransactionLogicException,
            TenantOrAppNotFoundException {
        String[] args = {"../"};

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args, false);
        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{EE_FEATURES.MULTI_TENANCY});
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));

        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.LOADING_ALL_TENANT_CONFIG));

        assertEquals(
                AccessTokenSigningKey.getInstance(new TenantIdentifier(null, null, null), process.main).getAllKeys()
                        .size(), 1);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void keysAreGeneratedForAllUserPoolIds()
            throws InterruptedException, IOException, StorageQueryException, StorageTransactionLogicException,
            InvalidConfigException, DbInitException, TenantOrAppNotFoundException {
        String[] args = {"../"};

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args, false);
        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{EE_FEATURES.MULTI_TENANCY});
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));

        JsonObject tenantConfig = new JsonObject();
        StorageLayer.getStorage(new TenantIdentifier(null, null, null), process.getProcess())
                .modifyConfigToAddANewUserPoolForTesting(tenantConfig, 2);
        tenantConfig.add("access_token_signing_key_update_interval", new JsonPrimitive(200));

        TenantConfig[] tenants = new TenantConfig[]{
                new TenantConfig(new TenantIdentifier("c1", null, null), new EmailPasswordConfig(false),
                        new ThirdPartyConfig(false, new ThirdPartyConfig.Provider[0]),
                        new PasswordlessConfig(false),
                        tenantConfig)};

        Config.loadAllTenantConfig(process.getProcess(), tenants);

        StorageLayer.loadAllTenantStorage(process.getProcess(), tenants);

        AccessTokenSigningKey.loadForAllTenants(process.getProcess(), tenants);

        assertEquals(
                AccessTokenSigningKey.getInstance(new TenantIdentifier(null, null, null), process.main).getAllKeys()
                        .size(), 1);
        assertEquals(
                AccessTokenSigningKey.getInstance(new TenantIdentifier("c1", null, null), process.main).getAllKeys()
                        .size(), 1);
        AccessTokenSigningKey.KeyInfo baseTenant = AccessTokenSigningKey.getInstance(
                        new TenantIdentifier(null, null, null), process.main)
                .getAllKeys().get(0);
        AccessTokenSigningKey.KeyInfo c1Tenant = AccessTokenSigningKey.getInstance(
                        new TenantIdentifier("c1", null, null), process.main)
                .getAllKeys().get(0);

        assertNotEquals(baseTenant.createdAtTime, c1Tenant.createdAtTime);
        assertNotEquals(baseTenant.expiryTime, c1Tenant.expiryTime);
        assertTrue(baseTenant.expiryTime + (31 * 3600 * 1000) < c1Tenant.expiryTime);
        assertNotEquals(baseTenant.value, c1Tenant.value);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }

    @Test
    public void signingKeyClassesAreThereForAllTenants()
            throws InterruptedException, IOException, InvalidConfigException, DbInitException, StorageQueryException,
            StorageTransactionLogicException, TenantOrAppNotFoundException {
        String[] args = {"../"};

        TestingProcessManager.TestingProcess process = TestingProcessManager.start(args, false);
        FeatureFlagTestContent.getInstance(process.getProcess())
                .setKeyValue(FeatureFlagTestContent.ENABLED_FEATURES, new EE_FEATURES[]{EE_FEATURES.MULTI_TENANCY});
        process.startProcess();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STARTED));

        JsonObject tenantConfig = new JsonObject();
        StorageLayer.getStorage(new TenantIdentifier(null, null, null), process.getProcess())
                .modifyConfigToAddANewUserPoolForTesting(tenantConfig, 2);
        tenantConfig.add("access_token_signing_key_update_interval", new JsonPrimitive(200));
        JsonObject tenantConfig2 = new JsonObject();
        StorageLayer.getStorage(new TenantIdentifier(null, null, null), process.getProcess())
                .modifyConfigToAddANewUserPoolForTesting(tenantConfig2, 3);
        tenantConfig2.add("access_token_signing_key_update_interval", new JsonPrimitive(400));

        TenantConfig[] tenants = new TenantConfig[]{
                new TenantConfig(new TenantIdentifier("c1", null, null), new EmailPasswordConfig(false),
                        new ThirdPartyConfig(false, new ThirdPartyConfig.Provider[0]),
                        new PasswordlessConfig(false),
                        tenantConfig),
                new TenantConfig(new TenantIdentifier("c2", null, null), new EmailPasswordConfig(false),
                        new ThirdPartyConfig(false, new ThirdPartyConfig.Provider[0]),
                        new PasswordlessConfig(false),
                        tenantConfig2)};

        Config.loadAllTenantConfig(process.getProcess(), tenants);

        StorageLayer.loadAllTenantStorage(process.getProcess(), tenants);

        AccessTokenSigningKey.loadForAllTenants(process.getProcess(), tenants);

        assertEquals(
                AccessTokenSigningKey.getInstance(new TenantIdentifier(null, null, null), process.main).getAllKeys()
                        .size(), 1);
        assertEquals(
                AccessTokenSigningKey.getInstance(new TenantIdentifier("c1", null, null), process.main).getAllKeys()
                        .size(), 1);
        AccessTokenSigningKey.KeyInfo baseTenant = AccessTokenSigningKey.getInstance(
                        new TenantIdentifier(null, null, null), process.main)
                .getAllKeys().get(0);
        AccessTokenSigningKey.KeyInfo c1Tenant = AccessTokenSigningKey.getInstance(
                        new TenantIdentifier("c1", null, null), process.main)
                .getAllKeys().get(0);
        AccessTokenSigningKey.KeyInfo c2Tenant = AccessTokenSigningKey.getInstance(
                        new TenantIdentifier("c2", null, null), process.main)
                .getAllKeys().get(0);
        AccessTokenSigningKey.KeyInfo c3Tenant = AccessTokenSigningKey.getInstance(
                        new TenantIdentifier("c3", null, null), process.main)
                .getAllKeys().get(0);

        assertNotEquals(baseTenant.createdAtTime, c1Tenant.createdAtTime);
        assertNotEquals(baseTenant.expiryTime, c1Tenant.expiryTime);
        assertNotEquals(baseTenant.expiryTime, c2Tenant.expiryTime);
        assertTrue(baseTenant.expiryTime + (31 * 3600 * 1000) < c1Tenant.expiryTime);
        assertNotEquals(baseTenant.value, c1Tenant.value);
        assertTrue(baseTenant.expiryTime + (60 * 3600 * 1000) < c2Tenant.expiryTime);
        assertNotEquals(baseTenant.value, c2Tenant.value);

        assertEquals(baseTenant.expiryTime, c3Tenant.expiryTime);
        assertEquals(baseTenant.value, c3Tenant.value);

        process.kill();
        assertNotNull(process.checkOrWaitForEvent(ProcessState.PROCESS_STATE.STOPPED));
    }
}
