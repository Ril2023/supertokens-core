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

package io.supertokens.multitenancy;

import io.supertokens.Main;
import io.supertokens.ResourceDistributor;
import io.supertokens.config.Config;
import io.supertokens.cronjobs.Cronjobs;
import io.supertokens.exceptions.TenantOrAppNotFoundException;
import io.supertokens.featureflag.EE_FEATURES;
import io.supertokens.featureflag.FeatureFlag;
import io.supertokens.jwt.JWTSigningKey;
import io.supertokens.jwt.exceptions.UnsupportedJWTSigningAlgorithmException;
import io.supertokens.output.Logging;
import io.supertokens.pluginInterface.emailpassword.exceptions.UnknownUserIdException;
import io.supertokens.pluginInterface.exceptions.DbInitException;
import io.supertokens.pluginInterface.exceptions.InvalidConfigException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.multitenancy.TenantConfig;
import io.supertokens.pluginInterface.multitenancy.TenantIdentifier;
import io.supertokens.pluginInterface.multitenancy.exceptions.DuplicateTenantException;
import io.supertokens.pluginInterface.multitenancy.exceptions.UnknownTenantException;
import io.supertokens.pluginInterface.userroles.exception.UnknownRoleException;
import io.supertokens.session.accessToken.AccessTokenSigningKey;
import io.supertokens.session.refreshToken.RefreshTokenKey;
import io.supertokens.storageLayer.StorageLayer;

import java.io.IOException;
import java.util.*;

public class Multitenancy extends ResourceDistributor.SingletonResource {

    public static final String RESOURCE_KEY = "io.supertokens.multitenancy.Multitenancy";
    private Main main;
    private TenantConfig[] tenantConfigs;
    private final Object lock = new Object();

    private Multitenancy(Main main) {
        this.main = main;
        this.tenantConfigs = getAllTenants();
    }

    public static Multitenancy getInstance(Main main) {
        try {
            return (Multitenancy) main.getResourceDistributor()
                    .getResource(new TenantIdentifier(null, null, null), RESOURCE_KEY);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException("Should never come here");
        }
    }

    public static void init(Main main) {
        main.getResourceDistributor()
                .setResource(new TenantIdentifier(null, null, null), RESOURCE_KEY, new Multitenancy(main));
    }

    private TenantConfig[] getAllTenants() {
        TenantConfig[] tenants = MultitenancyUtils.getAllTenantsWithoutFilteringDeletedOnes(main);
        List<TenantConfig> tenantList = new ArrayList<>();

        for (TenantConfig t : tenants) {
            if (t.appIdMarkedAsDeleted || t.connectionUriDomainMarkedAsDeleted) {
                continue;
            }
            tenantList.add(t);
        }

        TenantConfig[] finalResult = new TenantConfig[tenantList.size()];
        for (int i = 0; i < tenantList.size(); i++) {
            finalResult[i] = tenantList.get(i);
        }
        return finalResult;
    }

    public void refreshTenantsInCoreIfRequired() {
        try {
            TenantConfig[] tenantsFromDb = getAllTenants();
            synchronized (lock) {
                boolean hasChanged = false;
                if (tenantsFromDb.length != tenantConfigs.length) {
                    hasChanged = true;
                } else {
                    Set<TenantIdentifier> fromDb = new HashSet<>();
                    for (TenantConfig t : tenantsFromDb) {
                        fromDb.add(t.tenantIdentifier);
                    }
                    for (TenantConfig t : this.tenantConfigs) {
                        if (!fromDb.contains(t.tenantIdentifier)) {
                            hasChanged = true;
                            break;
                        }
                    }
                }

                this.tenantConfigs = tenantsFromDb;
                if (!hasChanged) {
                    return;
                }

                loadConfig();
                loadStorageLayer();
                loadSigningKeys();
                refreshCronjobs();
            }
        } catch (Exception e) {
            Logging.error(main, e.getMessage(), false, e);
        }
    }

    public void loadConfig() throws IOException, InvalidConfigException, StorageQueryException {
        if (Arrays.stream(FeatureFlag.getInstance(main).getEnabledFeatures())
                .noneMatch(ee_features -> ee_features == EE_FEATURES.MULTI_TENANCY)) {
            return;
        }
        Config.loadAllTenantConfig(main, this.tenantConfigs);
    }

    public void loadStorageLayer() throws DbInitException, IOException, InvalidConfigException, StorageQueryException {
        if (Arrays.stream(FeatureFlag.getInstance(main).getEnabledFeatures())
                .noneMatch(ee_features -> ee_features == EE_FEATURES.MULTI_TENANCY)) {
            return;
        }
        StorageLayer.loadAllTenantStorage(main, this.tenantConfigs);
    }

    public void loadSigningKeys() throws UnsupportedJWTSigningAlgorithmException, StorageQueryException {
        if (Arrays.stream(FeatureFlag.getInstance(main).getEnabledFeatures())
                .noneMatch(ee_features -> ee_features == EE_FEATURES.MULTI_TENANCY)) {
            return;
        }
        AccessTokenSigningKey.loadForAllTenants(main, this.tenantConfigs);
        RefreshTokenKey.loadForAllTenants(main, this.tenantConfigs);
        JWTSigningKey.loadForAllTenants(main, this.tenantConfigs);
    }

    private void refreshCronjobs() throws StorageQueryException {
        if (Arrays.stream(FeatureFlag.getInstance(main).getEnabledFeatures())
                .noneMatch(ee_features -> ee_features == EE_FEATURES.MULTI_TENANCY)) {
            return;
        }
        List<TenantIdentifier> list = new ArrayList<>();
        for (TenantConfig t : this.tenantConfigs) {
            list.add(t.tenantIdentifier);
        }
        Cronjobs.getInstance(main).setTenantsInfo(list);
    }

    public static boolean addNewOrUpdateAppOrTenant(Main main, TenantConfig tenant) {
        // TODO: do not allow updating of null, null, null's core config
        // TODO: allow only if connectionuri exists and appid exists (unless this has connectionuri as null or appid
        //  as null)
        TenantConfig[] unfilteredTenants = MultitenancyUtils.getAllTenantsWithoutFilteringDeletedOnes(main);
        for (TenantConfig t : unfilteredTenants) {
            if (t.tenantIdentifier.getConnectionUriDomain().equals(tenant.tenantIdentifier.getConnectionUriDomain())) {
                if (t.connectionUriDomainMarkedAsDeleted) {
                    // TODO: ??
                }
            }
            if (t.tenantIdentifier.getAppId().equals(tenant.tenantIdentifier.getAppId())) {
                if (t.appIdMarkedAsDeleted) {
                    // TODO: ??
                }
            }
        }

        boolean creationInSharedDbSucceeded = false;
        try {
            StorageLayer.getMultitenancyStorage(main).createTenant(tenant);
            creationInSharedDbSucceeded = true;
            Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
            try {
                StorageLayer.getMultitenancyStorageWithTargetStorage(tenant.tenantIdentifier, main)
                        .addTenantIdInUserPool(tenant.tenantIdentifier);
            } catch (TenantOrAppNotFoundException e) {
                // it should never come here, since we just added the tenant above.. but just in case.
                return addNewOrUpdateAppOrTenant(main, tenant);
            }
            return true;
        } catch (DuplicateTenantException e) {
            if (!creationInSharedDbSucceeded) {
                try {
                    StorageLayer.getMultitenancyStorage(main).overwriteTenantConfig(tenant);
                    Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();

                    // we do this extra step cause if previously an attempt to add a tenant failed midway,
                    // such that the main tenant was added in the user pool, but did not get created
                    // in the tenant specific db (cause it's not happening in a transaction), then we
                    // do this to make it consistent.
                    StorageLayer.getMultitenancyStorageWithTargetStorage(tenant.tenantIdentifier, main)
                            .addTenantIdInUserPool(tenant.tenantIdentifier);
                    return false;
                } catch (UnknownTenantException | TenantOrAppNotFoundException ex) {
                    // this can happen cause of a race condition if the tenant was deleted in the middle
                    // of it being recreated.
                    return addNewOrUpdateAppOrTenant(main, tenant);
                } catch (DuplicateTenantException ex) {
                    // we treat this as a success
                    return false;
                }
            } else {
                // we ignore this since it should technically never come here cause it means that the
                // creation in the shared db succeeded, but not in the tenant specific db
                // but if it ever does come here, it doesn't really matter anyway.
                return true;
            }
        }
    }

    public static void deleteTenant(Main main, TenantIdentifier tenantIdentifier)
            throws UnknownTenantException {
        // TODO: cannot delete null tenantId
        try {
            StorageLayer.getMultitenancyStorageWithTargetStorage(tenantIdentifier, main)
                    .deleteTenantIdInUserPool(tenantIdentifier);
        } catch (TenantOrAppNotFoundException | UnknownTenantException e) {
            // we ignore this since it may have been that past deletion attempt deleted this successfully,
            // but not from the main table.
        }
        StorageLayer.getMultitenancyStorage(main).deleteTenant(tenantIdentifier);
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
    }

    public static void deleteApp(Main main, TenantIdentifier tenantIdentifier)
            throws UnknownTenantException {
        if (!tenantIdentifier.getTenantId().equals(TenantIdentifier.DEFAULT_TENANT_ID)) {
            // we only allow app to be deleted via the public tenant.
            // TODO: ??
        }
        // TODO: cannot delete null appId
        StorageLayer.getMultitenancyStorage(main).markAppIdAsDeleted(tenantIdentifier.getAppId());
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
        // TODO: we need to clear all the tenant and app data -> via a cronjob cause we can have data
        // across dbs.
    }

    public static void deleteConnectionUriDomain(Main main, TenantIdentifier tenantIdentifier)
            throws UnknownTenantException {
        if (!tenantIdentifier.getTenantId().equals(TenantIdentifier.DEFAULT_TENANT_ID) &&
                !tenantIdentifier.getAppId().equals(TenantIdentifier.DEFAULT_APP_ID)) {
            // we only allow app to be deleted via the public appId and tenant
            // TODO: ??
        }
        // TODO: cannot delete null connection uri
        StorageLayer.getMultitenancyStorage(main)
                .markConnectionUriDomainAsDeleted(tenantIdentifier.getConnectionUriDomain());
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
        // TODO: we need to clear all the connection uri data -> via a cronjob cause we can have data
        // across dbs.
    }

    public static void addUserIdToTenant(Main main, TenantIdentifier sourceTenantIdentifier, String userId,
                                         String newTenantId)
            throws UnknownTenantException, UnknownUserIdException, TenantOrAppNotFoundException {
        TenantIdentifier targetTenantIdentifier = new TenantIdentifier(sourceTenantIdentifier.getConnectionUriDomain(),
                sourceTenantIdentifier.getAppId(), newTenantId);
        if (sourceTenantIdentifier.equals(targetTenantIdentifier)) {
            // TODO: ??
        }
        StorageLayer.getMultitenancyStorageWithTargetStorage(sourceTenantIdentifier, main)
                .addUserIdToTenant(targetTenantIdentifier, userId);
    }

    public static void addRoleToTenant(Main main, TenantIdentifier sourceTenantIdentifier, String role,
                                       String newTenantId)
            throws UnknownTenantException, UnknownRoleException, TenantOrAppNotFoundException {

        TenantIdentifier targetTenantIdentifier = new TenantIdentifier(sourceTenantIdentifier.getConnectionUriDomain(),
                sourceTenantIdentifier.getAppId(), newTenantId);
        if (sourceTenantIdentifier.equals(targetTenantIdentifier)) {
            // TODO: ??
        }
        StorageLayer.getMultitenancyStorageWithTargetStorage(sourceTenantIdentifier, main)
                .addRoleToTenant(targetTenantIdentifier, role);
    }

    public static TenantConfig getTenantInfo(Main main, TenantIdentifier tenantIdentifier) {
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
        TenantConfig[] tenants = Multitenancy.getInstance(main).tenantConfigs;
        for (TenantConfig t : tenants) {
            if (t.tenantIdentifier.equals(tenantIdentifier)) {
                return t;
            }
        }
        return null;
    }

    public static TenantConfig[] getAllTenantsForApp(TenantIdentifier tenantIdentifier, Main main) {
        if (!tenantIdentifier.getTenantId().equals(TenantIdentifier.DEFAULT_TENANT_ID)) {
            // TODO: ??
        }
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
        TenantConfig[] tenants = Multitenancy.getInstance(main).tenantConfigs;
        List<TenantConfig> tenantList = new ArrayList<>();

        for (TenantConfig t : tenants) {
            if (!t.tenantIdentifier.getAppId().equals(tenantIdentifier.getAppId())) {
                continue;
            }
            tenantList.add(t);
        }

        TenantConfig[] finalResult = new TenantConfig[tenantList.size()];
        for (int i = 0; i < tenantList.size(); i++) {
            finalResult[i] = tenantList.get(i);
        }
        return finalResult;
    }

    public static TenantConfig[] getAllTenantsForConnectionUriDomain(TenantIdentifier tenantIdentifier, Main main) {
        if (!tenantIdentifier.getTenantId().equals(TenantIdentifier.DEFAULT_TENANT_ID) &&
                !tenantIdentifier.getAppId().equals(TenantIdentifier.DEFAULT_APP_ID)) {
            // TODO: ??
        }
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
        TenantConfig[] tenants = Multitenancy.getInstance(main).tenantConfigs;
        List<TenantConfig> tenantList = new ArrayList<>();

        for (TenantConfig t : tenants) {
            if (!t.tenantIdentifier.getConnectionUriDomain().equals(tenantIdentifier.getConnectionUriDomain())) {
                continue;
            }
            tenantList.add(t);
        }

        TenantConfig[] finalResult = new TenantConfig[tenantList.size()];
        for (int i = 0; i < tenantList.size(); i++) {
            finalResult[i] = tenantList.get(i);
        }
        return finalResult;
    }

    public static TenantConfig[] getAllTenants(TenantIdentifier tenantIdentifier, Main main) {
        if (!tenantIdentifier.getTenantId().equals(TenantIdentifier.DEFAULT_TENANT_ID) &&
                !tenantIdentifier.getAppId().equals(TenantIdentifier.DEFAULT_APP_ID) &&
                !tenantIdentifier.getConnectionUriDomain().equals(TenantIdentifier.DEFAULT_CONNECTION_URI)) {
            // TODO: ??
        }
        Multitenancy.getInstance(main).refreshTenantsInCoreIfRequired();
        return Multitenancy.getInstance(main).tenantConfigs;
    }
}