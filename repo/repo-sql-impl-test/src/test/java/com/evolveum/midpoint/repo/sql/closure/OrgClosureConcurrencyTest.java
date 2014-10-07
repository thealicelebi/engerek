/*
 * Copyright (c) 2010-2014 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.repo.sql.closure;

import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.delta.ReferenceDelta;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OrgType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.evolveum.midpoint.repo.sql.OrgClosureManager.Edge;

/**
 * @author mederly
 */
@ContextConfiguration(locations = {"../../../../../../ctx-test.xml"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrgClosureConcurrencyTest extends AbstractOrgClosureTest {

    private static final Trace LOGGER = TraceManager.getTrace(OrgClosureConcurrencyTest.class);

    private static final int[] ORG_CHILDREN_IN_LEVEL  = { 5, 3, 3, 3, 0  };
    private static final int[] USER_CHILDREN_IN_LEVEL = { 0, 1, 2, 3, 3  };
    private static final int[] PARENTS_IN_LEVEL       = { 0, 1, 2, 3, 3  };
    private static final int[] LINK_ROUNDS_FOR_LEVELS = { 0, 15, 20, 30  };
    private static final int[] NODE_ROUNDS_FOR_LEVELS = { 3, 15, 20, 30  };
    private static final int[] USER_ROUNDS_FOR_LEVELS = { 0, 5 ,5, 10 };

    private OrgClosureTestConfiguration configuration;

    public OrgClosureConcurrencyTest() {
        configuration = new OrgClosureTestConfiguration();
        configuration.setCheckChildrenSets(true);
        configuration.setCheckClosureMatrix(true);
        configuration.setDeletionsToClosureTest(15);
        configuration.setOrgChildrenInLevel(ORG_CHILDREN_IN_LEVEL);
        configuration.setUserChildrenInLevel(USER_CHILDREN_IN_LEVEL);
        configuration.setParentsInLevel(PARENTS_IN_LEVEL);
        configuration.setLinkRoundsForLevel(LINK_ROUNDS_FOR_LEVELS);
        configuration.setNodeRoundsForLevel(NODE_ROUNDS_FOR_LEVELS);
        configuration.setUserRoundsForLevel(USER_ROUNDS_FOR_LEVELS);
    }

    @Override
    public OrgClosureTestConfiguration getConfiguration() {
        return configuration;
    }

    @Test(enabled = true) public void test100LoadOrgStructure() throws Exception { _test100LoadOrgStructure(); }
    @Test(enabled = true) public void test150CheckClosure() throws Exception { _test150CheckClosure(); }
    @Test(enabled = true) public void test200AddRemoveLinksSeq() throws Exception { _test200AddRemoveLinksMT(false); }
    @Test(enabled = true) public void test201AddRemoveLinksRandom() throws Exception { _test200AddRemoveLinksMT(true); }
    @Test(enabled = true) public void test300AddRemoveNodesSeq() throws Exception { _test300AddRemoveNodesMT(false); }
    @Test(enabled = true) public void test301AddRemoveNodesRandom() throws Exception { _test300AddRemoveNodesMT(true); }
    //@Test(enabled = true) public void test310AddRemoveUsers() throws Exception { _test310AddRemoveUsersMT(); }

    /**
     * We randomly select a set of links to be removed.
     * Then we remove them, using a given set of threads.
     * After all threads are done, we will check the closure table consistency.
     *
     * And after that, we will do the reverse, re-adding all the links previously removed.
     * In the end, we again check the consistency.
     */
    protected void _test200AddRemoveLinksMT(final boolean random) throws Exception {
        OperationResult opResult = new OperationResult("===[ test200AddRemoveLinksMT ]===");

        info("test200AddRemoveLinks starting with random = " + random);

        final Set<Edge> edgesToRemove = Collections.synchronizedSet(new HashSet<Edge>());
        final Set<Edge> edgesToAdd = Collections.synchronizedSet(new HashSet<Edge>());

        final List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<Throwable>());

        // parentRef link removal + addition
        for (int level = 0; level < getConfiguration().getLinkRoundsForLevel().length; level++) {
            int rounds = getConfiguration().getLinkRoundsForLevel()[level];
            List<String> levelOids = orgsByLevels.get(level);
            int retries = 0;
            for (int round = 0; round < rounds; round++) {

                int index = (int) Math.floor(Math.random() * levelOids.size());
                String oid = levelOids.get(index);

                OrgType org = repositoryService.getObject(OrgType.class, oid, null, opResult).asObjectable();

                // check if it has no parents (shouldn't occur here!)
                if (org.getParentOrgRef().isEmpty()) {
                    throw new IllegalStateException("No parents in " + org);
                }

                int i = (int) Math.floor(Math.random() * org.getParentOrgRef().size());
                ObjectReferenceType parentOrgRef = org.getParentOrgRef().get(i);

                Edge edge = new Edge(oid, parentOrgRef.getOid());
                if (edgesToRemove.contains(edge)) {
                    round--;
                    if (++retries == 1000) {
                        throw new IllegalStateException("Too many retries");    // primitive attempt to break potential cycles when there is not enough edges to process
                    } else {
                        continue;
                    }
                }
                edgesToRemove.add(edge);
                edgesToAdd.add(edge);
            }
        }

        int numberOfRunners = 3;
        info("Edges to remove/add (" + edgesToRemove.size() + ": " + edgesToRemove);
        info("Number of runners: " + numberOfRunners);
        final List<Thread> runners = Collections.synchronizedList(new ArrayList<Thread>());

        for (int i = 0; i < numberOfRunners; i++) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Edge edge = getNext(edgesToRemove, random);
                            if (edge == null) {
                                break;
                            }
                            LOGGER.info("Removing {}", edge);
                            removeEdge(edge);
                            info(Thread.currentThread().getName() + " removed " + edge);
                            synchronized (OrgClosureConcurrencyTest.this) {
                                edgesToRemove.remove(edge);
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        exceptions.add(e);
                    } finally {
                        runners.remove(Thread.currentThread());
                    }
                }
            };
            Thread t = new Thread(runnable);
            runners.add(t);
            t.start();
        }

        while (!runners.isEmpty()) {
            Thread.sleep(100);          // primitive way of waiting
        }

        if (!edgesToRemove.isEmpty()) {
            throw new AssertionError("Edges to remove is not empty, see the console or log: " + edgesToRemove);
        }

        if (!exceptions.isEmpty()) {
            throw new AssertionError("Found exceptions: " + exceptions);
        }

        checkClosure(orgGraph.vertexSet());
        info("Consistency after removal OK");

        for (int i = 0; i < numberOfRunners; i++) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            Edge edge = getNext(edgesToAdd, random);
                            if (edge == null) {
                                break;
                            }
                            LOGGER.info("Adding {}", edge);
                            addEdge(edge);
                            info(Thread.currentThread().getName() + " re-added " + edge);
                            synchronized (OrgClosureConcurrencyTest.this) {
                                edgesToAdd.remove(edge);
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        exceptions.add(e);
                    } finally {
                        runners.remove(Thread.currentThread());
                    }
                }
            };
            Thread t = new Thread(runnable);
            runners.add(t);
            t.start();
        }

        while (!runners.isEmpty()) {
            Thread.sleep(100);          // primitive way of waiting
        }

        if (!edgesToAdd.isEmpty()) {
            throw new AssertionError("Edges to add is not empty, see the console or log: " + edgesToAdd);
        }

        if (!exceptions.isEmpty()) {
            throw new AssertionError("Found exceptions: " + exceptions);
        }

        checkClosure(orgGraph.vertexSet());

        info("Consistency after re-adding OK");
    }

    private synchronized <T> T getNext(Set<T> items, boolean random) {
        if (items.isEmpty()) {
            return null;
        }
        Iterator<T> iterator = items.iterator();
        if (random) {
            int i = (int) Math.floor(Math.random() * items.size());
            while (i-- > 0) {
                iterator.next();
            }
        }
        return iterator.next();
    }

    private void info(String s) {
        System.out.println(s);
        LOGGER.info(s);
    }

    private void removeEdge(Edge edge) throws ObjectAlreadyExistsException, ObjectNotFoundException, SchemaException {
        List<ItemDelta> modifications = new ArrayList<>();
        ObjectReferenceType parentOrgRef = new ObjectReferenceType();
        parentOrgRef.setType(OrgType.COMPLEX_TYPE);
        parentOrgRef.setOid(edge.getAncestor());
        ItemDelta removeParent = ReferenceDelta.createModificationDelete(OrgType.class, OrgType.F_PARENT_ORG_REF, prismContext, parentOrgRef.asReferenceValue());
        modifications.add(removeParent);
        repositoryService.modifyObject(OrgType.class, edge.getDescendant(), modifications, new OperationResult("dummy"));
        synchronized(this) {
            orgGraph.removeEdge(edge.getDescendant(), edge.getAncestor());
        }
    }

    private void addEdge(Edge edge) throws ObjectAlreadyExistsException, ObjectNotFoundException, SchemaException {
        List<ItemDelta> modifications = new ArrayList<>();
        ObjectReferenceType parentOrgRef = new ObjectReferenceType();
        parentOrgRef.setType(OrgType.COMPLEX_TYPE);
        parentOrgRef.setOid(edge.getAncestor());
        ItemDelta itemDelta = ReferenceDelta.createModificationAdd(OrgType.class, OrgType.F_PARENT_ORG_REF, prismContext, parentOrgRef.asReferenceValue());
        modifications.add(itemDelta);
        repositoryService.modifyObject(OrgType.class, edge.getDescendant(), modifications, new OperationResult("dummy"));
        synchronized(this) {
            orgGraph.addEdge(edge.getDescendant(), edge.getAncestor());
        }
    }

    protected void _test300AddRemoveNodesMT(final boolean random) throws Exception {
        OperationResult opResult = new OperationResult("===[ test300AddRemoveNodesMT ]===");

        info("test300AddRemoveNodes starting with random = " + random);

        final Set<ObjectType> nodesToRemove = Collections.synchronizedSet(new HashSet<ObjectType>());
        final Set<ObjectType> nodesToAdd = Collections.synchronizedSet(new HashSet<ObjectType>());

        final List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<Throwable>());

        for (int level = 0; level < getConfiguration().getNodeRoundsForLevel().length; level++) {
            int rounds = getConfiguration().getNodeRoundsForLevel()[level];
            List<String> levelOids = orgsByLevels.get(level);
            generateNodesAtOneLevel(nodesToRemove, nodesToAdd, OrgType.class, rounds, levelOids, opResult);
        }
        for (int level = 0; level < getConfiguration().getUserRoundsForLevel().length; level++) {
            int rounds = getConfiguration().getUserRoundsForLevel()[level];
            List<String> levelOids = usersByLevels.get(level);
            generateNodesAtOneLevel(nodesToRemove, nodesToAdd, UserType.class, rounds, levelOids, opResult);
        }

        int numberOfRunners = 3;
        final List<Thread> runners = Collections.synchronizedList(new ArrayList<Thread>());

        for (int i = 0; i < numberOfRunners; i++) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            ObjectType objectType = getNext(nodesToRemove, random);
                            if (objectType == null) {
                                break;
                            }
                            LOGGER.info("Removing {}", objectType);
                            try {
                                removeObject(objectType);
                                synchronized (OrgClosureConcurrencyTest.this) {
                                    nodesToRemove.remove(objectType);
                                }
                                info(Thread.currentThread().getName() + " removed " + objectType);
                            } catch (ObjectNotFoundException e) {
                                // this is OK
                                info(Thread.currentThread().getName() + ": " + objectType + " already deleted");
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        exceptions.add(e);
                    } finally {
                        runners.remove(Thread.currentThread());
                    }
                }
            };
            Thread t = new Thread(runnable);
            runners.add(t);
            t.start();
        }

        while (!runners.isEmpty()) {
            Thread.sleep(100);          // primitive way of waiting
        }

        if (!nodesToRemove.isEmpty()) {
            throw new AssertionError("Nodes to remove is not empty, see the console or log: " + nodesToRemove);
        }

        if (!exceptions.isEmpty()) {
            throw new AssertionError("Found exceptions: " + exceptions);
        }

        checkClosure(orgGraph.vertexSet());
        info("Consistency after removing OK");

        for (int i = 0; i < numberOfRunners; i++) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            ObjectType objectType = getNext(nodesToAdd, random);
                            if (objectType == null) {
                                break;
                            }
                            LOGGER.info("Adding {}", objectType);
                            try {
                                addObject(objectType.clone());
                                synchronized (OrgClosureConcurrencyTest.this) {
                                    nodesToAdd.remove(objectType);
                                }
                                info(Thread.currentThread().getName() + " re-added " + objectType);
                            } catch (ObjectAlreadyExistsException e) {
                                // this is OK
                                info(Thread.currentThread().getName() + ": " + objectType + " already exists");
                            }
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        exceptions.add(e);
                    } finally {
                        runners.remove(Thread.currentThread());
                    }
                }
            };
            Thread t = new Thread(runnable);
            runners.add(t);
            t.start();
        }

        while (!runners.isEmpty()) {
            Thread.sleep(100);          // primitive way of waiting
        }

        if (!nodesToAdd.isEmpty()) {
            throw new AssertionError("Nodes to add is not empty, see the console or log: " + nodesToAdd);
        }

        if (!exceptions.isEmpty()) {
            throw new AssertionError("Found exceptions: " + exceptions);
        }

        checkClosure(orgGraph.vertexSet());
        info("Consistency after re-adding OK");
    }

    private void generateNodesAtOneLevel(Set<ObjectType> nodesToRemove, Set<ObjectType> nodesToAdd,
                                         Class<? extends ObjectType> clazz,
                                         int rounds, List<String> candidateOids,
                                         OperationResult opResult) throws ObjectNotFoundException, SchemaException {
        if (candidateOids.isEmpty()) {
            return;
        }
        int retries = 0;
        for (int round = 0; round < rounds; round++) {

            int index = (int) Math.floor(Math.random() * candidateOids.size());
            String oid = candidateOids.get(index);
            ObjectType objectType = repositoryService.getObject(clazz, oid, null, opResult).asObjectable();

            if (nodesToRemove.contains(objectType)) {
                round--;
                if (++retries == 1000) {
                    throw new IllegalStateException("Too many retries");    // primitive attempt to break potential cycles when there is not enough edges to process
                } else {
                    continue;
                }
            }

            nodesToRemove.add(objectType);
            nodesToAdd.add(objectType);
        }
    }

    void removeObject(ObjectType objectType) throws Exception {
        repositoryService.deleteObject(objectType.getClass(), objectType.getOid(), new OperationResult("dummy"));
        synchronized(orgGraph) {
            orgGraph.removeVertex(objectType.getOid());
        }
    }

    void addObject(ObjectType objectType) throws Exception {
        repositoryService.addObject(objectType.asPrismObject(), null, new OperationResult("dummy"));
        synchronized(orgGraph) {
            registerObject(objectType, true);
        }
    }

}
