/*
 * Copyright (c) 2010-2014 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.testing.longtest;

import com.evolveum.midpoint.model.test.AbstractModelIntegrationTest;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.constants.MidPointConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.test.IntegrationTestTools;
import com.evolveum.midpoint.test.util.TestUtil;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_2a.*;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.opends.server.types.Entry;
import org.opends.server.types.LDIFImportConfig;
import org.opends.server.util.LDIFException;
import org.opends.server.util.LDIFReader;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import javax.xml.namespace.QName;

import java.io.IOException;

import static com.evolveum.midpoint.test.IntegrationTestTools.display;

/**
 * @author lazyman
 */
@ContextConfiguration(locations = {"classpath:ctx-longtest-test-main.xml"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class TestRepositoryPerformance extends AbstractModelIntegrationTest {

    private static final Trace LOGGER = TraceManager.getTrace(TestRepositoryPerformance.class);

    private static final String SYSTEM_CONFIGURATION_FILENAME = COMMON_DIR_NAME + "/system-configuration.xml";
    private static final String SYSTEM_CONFIGURATION_OID = SystemObjectsType.SYSTEM_CONFIGURATION.value();

    private static final String USER_ADMINISTRATOR_FILENAME = COMMON_DIR_NAME + "/user-administrator.xml";
    private static final String USER_ADMINISTRATOR_OID = SystemObjectsType.USER_ADMINISTRATOR.value();
    private static final String USER_ADMINISTRATOR_USERNAME = "administrator";

    private static final String ROLE_SUPERUSER_FILENAME = COMMON_DIR_NAME + "/role-superuser.xml";
    private static final String ROLE_SUPERUSER_OID = "00000000-0000-0000-0000-000000000004";

    private static final String RESOURCE_OPENDJ_FILENAME = COMMON_DIR_NAME + "/resource-opendj-generic-sync.xml";
    private static final String RESOURCE_OPENDJ_NAME = "Localhost OpenDJ";
    private static final String RESOURCE_OPENDJ_OID = "10000000-0000-0000-0000-000000000030";
    private static final String RESOURCE_OPENDJ_NAMESPACE = MidPointConstants.NS_RI;


    //222 org. units, 2160 users
//    private static final int[] TREE_LEVELS = {2, 5, 7, 2};
//    private static final int[] TREE_LEVELS_USER = {5, 5, 20, 5};

    //1378 org. units, 13286 users
//    private static final int[] TREE_LEVELS = {2, 8, 5, 16};
//    private static final int[] TREE_LEVELS_USER = {3, 5, 5, 10};

    //98 org. units, 886 users
//    private static final int[] TREE_LEVELS = {2, 8, 5};
//    private static final int[] TREE_LEVELS_USER = {3, 5, 10};

    //18 org. units, 86 users
    private static final int[] TREE_LEVELS = {2, 8};
    private static final int[] TREE_LEVELS_USER = {3, 5};

    private int USER_COUNT;
    private int ORG_COUNT;

    private PrismObject<ResourceType> resourceOpenDj;

    @Override
    protected void startResources() throws Exception {
        openDJController.startCleanServer();
    }

    @AfterClass
    public static void stopResources() throws Exception {
        openDJController.stop();
    }

    @Override
    public void initSystem(Task initTask, OperationResult initResult) throws Exception {
        super.initSystem(initTask, initResult);

        loadOpenDJWithData();

        modelService.postInit(initResult);

        // System Configuration and administrator
        repoAddObjectFromFile(SYSTEM_CONFIGURATION_FILENAME, SystemConfigurationType.class, initResult);
        PrismObject<UserType> userAdministrator = repoAddObjectFromFile(USER_ADMINISTRATOR_FILENAME, UserType.class, initResult);
        repoAddObjectFromFile(ROLE_SUPERUSER_FILENAME, RoleType.class, initResult);
        login(userAdministrator);

        // Resources
        resourceOpenDj = importAndGetObjectFromFile(ResourceType.class, RESOURCE_OPENDJ_FILENAME, RESOURCE_OPENDJ_OID,
                initTask, initResult);
        openDJController.setResource(resourceOpenDj);

        assumeAssignmentPolicy(AssignmentPolicyEnforcementType.RELATIVE);
    }

    private void loadOpenDJWithData() throws IOException, LDIFException {
        long ldapPopStart = System.currentTimeMillis();
        int count = loadOpenDJ(TREE_LEVELS, TREE_LEVELS_USER, openDJController.getSuffixPeople(), 0);
        long ldapPopEnd = System.currentTimeMillis();

        IntegrationTestTools.display("Loaded " + count + " LDAP entries in " + ((ldapPopEnd - ldapPopStart) / 1000) + " seconds");
    }

    private int loadOpenDJ(int[] TREE_SIZE, int[] USER_COUNT, String dnSuffix, int count)
            throws IOException, LDIFException {

        if (TREE_SIZE.length == 0) {
            return count;
        }

        for (int i = 0; i < TREE_SIZE[0]; i++) {
            String ou = "L" + TREE_SIZE.length + "o" + i;
            String newSuffix = "ou=" + ou + ',' + dnSuffix;

            Entry org = createOrgEntry(ou, dnSuffix);
            openDJController.addEntry(org);
            count++;

            for (int u = 0; u < USER_COUNT[0]; u++) {
                String uid = "L" + TREE_SIZE.length + "o" + i + "u" + u;
                String sn = "Doe" + uid;

                Entry user = createUserEntry(uid, newSuffix, sn);
                openDJController.addEntry(user);
                count++;
            }

            count += loadOpenDJ(ArrayUtils.remove(TREE_SIZE, 0), ArrayUtils.remove(USER_COUNT, 0), newSuffix, 0);
        }

        return count;
    }

    private Entry createUserEntry(String uid, String suffix, String sn) throws IOException, LDIFException {
        StringBuilder sb = new StringBuilder();
        String dn = "uid=" + uid + "," + suffix;
        sb.append("dn: ").append(dn).append('\n');
        sb.append("objectClass: inetOrgPerson\n");
        sb.append("uid: ").append(uid).append('\n');
        sb.append("givenName: ").append("John").append('\n');
        sb.append("cn: ").append("John " + sn).append('\n');
        sb.append("sn: ").append(sn).append('\n');
        LDIFImportConfig importConfig = new LDIFImportConfig(IOUtils.toInputStream(sb.toString(), "utf-8"));
        LDIFReader ldifReader = new LDIFReader(importConfig);
        Entry ldifEntry = ldifReader.readEntry();
        return ldifEntry;
    }

    private Entry createOrgEntry(String ou, String suffix) throws IOException, LDIFException {
        StringBuilder sb = new StringBuilder();
        String dn = "ou=" + ou + "," + suffix;
        sb.append("dn: ").append(dn).append("\n");
        sb.append("objectClass: organizationalUnit\n");
        sb.append("ou: ").append(ou).append("\n");
        sb.append("description: ").append("This is sparta! ...or " + ou).append("\n");
        LDIFImportConfig importConfig = new LDIFImportConfig(IOUtils.toInputStream(sb.toString(), "utf-8"));
        LDIFReader ldifReader = new LDIFReader(importConfig);
        Entry ldifEntry = ldifReader.readEntry();
        return ldifEntry;
    }

    @Test
    public void test100TreeImport() throws Exception {
        final String TEST_NAME = "test100TreeImport";
        TestUtil.displayTestTile(this, TEST_NAME);

//        Task task = taskManager.createTaskInstance(TestLdap.class.getName() + "." + TEST_NAME);
//        task.setOwner(getUser(USER_ADMINISTRATOR_OID));
//        OperationResult result = task.getResult();
//
//        // WHEN
//        modelService.importFromResource(RESOURCE_OPENDJ_OID,
//                new QName(RESOURCE_OPENDJ_NAMESPACE, "AccountObjectClass"), task, result);
//
//        // THEN
//        TestUtil.displayThen(TEST_NAME);
//        OperationResult subresult = result.getLastSubresult();
//        TestUtil.assertInProgress("importAccountsFromResource result", subresult);
//
//        waitForTaskFinish(task, true, 20000 + (USER_COUNT + ORG_COUNT) * 2000);
//
//        // THEN
//        TestUtil.displayThen(TEST_NAME);
//
//        int userCount = modelService.countObjects(UserType.class, null, null, task, result);
//        display("Users", userCount);
//        AssertJUnit.assertEquals("Unexpected number of users", (USER_COUNT + ORG_COUNT), userCount);
    }

    @Test
    public void test200MoveRootChild() throws Exception {
        final String TEST_NAME = "test200MoveRootChild";
        TestUtil.displayTestTile(this, TEST_NAME);

        //todo move one child of one root to root position
    }
}