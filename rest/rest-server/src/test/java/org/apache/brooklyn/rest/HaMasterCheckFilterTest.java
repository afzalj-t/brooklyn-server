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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.rest;

import static org.testng.Assert.assertEquals;

import java.io.File;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.EntityManager;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityMode;
import org.apache.brooklyn.api.mgmt.ha.ManagementNodeState;
import org.apache.brooklyn.camp.brooklyn.BrooklynCampPlatformLauncherNoServer;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.mgmt.rebind.RebindTestUtils;
import org.apache.brooklyn.entity.stock.BasicApplication;
import org.apache.brooklyn.rest.security.provider.AnyoneSecurityProvider;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.http.HttpTool;
import org.apache.brooklyn.util.http.HttpToolResponse;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.time.Duration;
import org.apache.http.client.HttpClient;
import org.eclipse.jetty.server.Server;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;

public class HaMasterCheckFilterTest extends BrooklynRestApiLauncherTestFixture {
    private static final Duration TIMEOUT = Duration.THIRTY_SECONDS;

    private File mementoDir;
    private ManagementContext writeMgmt;
    private ManagementContext readMgmt;
    private String appId;
    private Server server;
    private HttpClient client;

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        server.stop();
        Entities.destroyAll(writeMgmt);
        Entities.destroyAll(readMgmt);
        Os.deleteRecursively(mementoDir);
    }

    @Test(groups = "Integration")
    public void testEntitiesExistOnDisabledHA() throws Exception {
        initHaCluster(HighAvailabilityMode.DISABLED, HighAvailabilityMode.DISABLED);
        assertReadIsMaster();
        assertEntityExists(new ReturnCodeNotRetry());
    }

    @Test(groups = "Integration")
    public void testEntitiesExistOnMasterPromotion() throws Exception {
        initHaCluster(HighAvailabilityMode.AUTO, HighAvailabilityMode.AUTO);
        assertEquals(getAppResponseCode(), 403);
        stopWriteNode();
        assertEntityExists(new ReturnCodeNotRetryAndNodeIsMaster());
        assertReadIsMaster();
    }

    @Test(groups = "Integration")
    public void testEntitiesExistOnHotStandbyAndPromotion() throws Exception {
        initHaCluster(HighAvailabilityMode.AUTO, HighAvailabilityMode.HOT_STANDBY);
        assertEntityExists(new ReturnCodeNotRetry());
        stopWriteNode();
        // once the node claims master we should get a 200
        assertEntityExists(new ReturnCodeNotRetryAndNodeIsMaster());
        assertReadIsMaster();
    }

    @Test(groups = "Integration")
    public void testEntitiesExistOnHotBackup() throws Exception {
        initHaCluster(HighAvailabilityMode.AUTO, HighAvailabilityMode.HOT_BACKUP);
        Asserts.continually(
                new ReturnCodeSupplier(),
                Predicates.or(Predicates.equalTo(200), Predicates.equalTo(403)),
                Duration.THIRTY_SECONDS, 
                null,
                null);
    }

    private HttpClient getClient(Server server) {
        HttpClient client = HttpTool.httpClientBuilder()
                .uri(getBaseUriRest(server))
                .build();
        return client;
    }

    private int getAppResponseCode() {
        HttpToolResponse response = HttpTool.httpGet(
                client, URI.create(getBaseUriRest() + "applications/" + appId),
                ImmutableMap.<String,String>of());
        return response.getResponseCode();
    }

    private String createApp(ManagementContext mgmt) {
        EntityManager entityMgr = mgmt.getEntityManager();
        Entity app = entityMgr.createEntity(EntitySpec.create(BasicApplication.class));
        return app.getId();
    }

    private ManagementContext createManagementContext(File mementoDir, HighAvailabilityMode mode) {
        ManagementContext mgmt = RebindTestUtils.managementContextBuilder(mementoDir, getClass().getClassLoader())
                .persistPeriodMillis(1)
                .forLive(false)
                .emptyCatalog(true)
                .haMode(mode)
                .buildStarted();

        new BrooklynCampPlatformLauncherNoServer()
            .useManagementContext(mgmt)
            .launch();

        return mgmt;
    }

    private void initHaCluster(HighAvailabilityMode writeMode, HighAvailabilityMode readMode) throws InterruptedException, TimeoutException {
        mementoDir = Os.newTempDir(getClass());

        writeMgmt = createManagementContext(mementoDir, writeMode);
        appId = createApp(writeMgmt);
        writeMgmt.getRebindManager().waitForPendingComplete(TIMEOUT, true);

        if (readMode == HighAvailabilityMode.DISABLED) {
            //no HA, one node only
            readMgmt = writeMgmt;
        } else {
            readMgmt = createManagementContext(mementoDir, readMode);
        }

        server = useServerForTest(baseLauncher()
                .securityProvider(AnyoneSecurityProvider.class)
                .managementContext(readMgmt)
                .forceUseOfDefaultCatalogWithJavaClassPath(true)
                .withoutJsgui()
                .disableHighAvailability(false)
                .start());
        client = getClient(server);
    }

    private void assertEntityExists(Callable<Integer> c) {
        assertEquals((int)Asserts.succeedsEventually(c), 200);
    }
    
    private void assertEntityNotFound(Callable<Integer> c) {
        assertEquals((int)Asserts.succeedsEventually(c), 404);
    }

    private void assertReadIsMaster() {
        assertEquals(readMgmt.getHighAvailabilityManager().getNodeState(), ManagementNodeState.MASTER);
    }

    private void stopWriteNode() {
        writeMgmt.getHighAvailabilityManager().stop();
    }

    private class ReturnCodeNotRetry implements Callable<Integer> {
        @Override
        public Integer call() {
            int retCode = getAppResponseCode();
            if (retCode == 403) {
                throw new RuntimeException("Not ready, retry. Response - " + retCode);
            } else {
                return retCode;
            }
        }
    }

    private class ReturnCodeNotRetryAndNodeIsMaster extends ReturnCodeNotRetry {
        @Override
        public Integer call() {
            ManagementNodeState state = readMgmt.getHighAvailabilityManager().getNodeState();
            if (state != ManagementNodeState.MASTER) {
                throw new RuntimeException("Not master yet " + state);
            }
            return super.call();
        }
    }

    private class ReturnCodeSupplier implements Supplier<Integer> {
        @Override
        public Integer get() {
            return getAppResponseCode();
        }
    }

}
