/**
 * Copyright (c) 2011 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * Portions Copyrighted 2011 [name of copyright owner]
 */
package com.evolveum.midpoint.model.lens;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.Validate;

import com.evolveum.midpoint.common.refinery.ResourceShadowDiscriminator;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.api.context.ModelState;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_2.AccountSynchronizationSettingsType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.PasswordPolicyType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_2.UserTemplateType;

/**
 * @author semancik
 *
 */
public class LensContext<F extends ObjectType, P extends ObjectType> implements ModelContext<F, P> {
	
	private ModelState state = ModelState.INITIAL;
	
	/**
     * Channel that is the source of primary change (GUI, live sync, import, ...)
     */
    private String channel;

	private LensFocusContext<F> focusContext;
	private Collection<LensProjectionContext<P>> projectionContexts = new ArrayList<LensProjectionContext<P>>();
	
	transient private UserTemplateType userTemplate;
	transient private AccountSynchronizationSettingsType accountSynchronizationSettings;
	transient private PasswordPolicyType globalPasswordPolicy;
	
	private Class<F> focusClass;
	private Class<P> projectionClass;
	
	/**
     * True if we want to reconcile all accounts in this context.
     */
    private boolean doReconciliationForAllProjections = false;
    
    /**
	 * Current wave of computation and execution.
	 */
	int wave = 0;
	
	transient private boolean isFresh = false;
	
	/**
     * Cache of resource instances. It is used to reduce the number of read (getObject) calls for ResourceType objects.
     */
    transient private Map<String, ResourceType> resourceCache;
	
	transient private PrismContext prismContext;
	
	public LensContext(Class<F> focusClass, Class<P> projectionClass, PrismContext prismContext) {
		Validate.notNull(prismContext, "No prismContext");
		if (focusClass == null && projectionClass == null) {
			throw new IllegalArgumentException("Neither focus class nor projection class was specified");
		}
		
        this.prismContext = prismContext;
        this.focusClass = focusClass;
        this.projectionClass = projectionClass;
    }
	
	public PrismContext getPrismContext() {
		return prismContext;
	}
	
	protected PrismContext getNotNullPrismContext() {
		if (prismContext == null) {
			throw new IllegalStateException("Null prism context in "+this+"; the context was not adopted (most likely)");
		}
		return prismContext;
	}
	
	@Override
	public ModelState getState() {
		return state;
	}

	public void setState(ModelState state) {
		this.state = state;
	}

	@Override
	public LensFocusContext<F> getFocusContext() {
		return focusContext;
	}
	
	public void setFocusContext(LensFocusContext<F> focusContext) {
		this.focusContext = focusContext;
	}
	
	public LensFocusContext<F> createFocusContext() {
		focusContext = new LensFocusContext<F>(focusClass, this);
		return focusContext;
	}
	
	public LensFocusContext<F> getOrCreateFocusContext() {
		if (focusContext == null) {
			createFocusContext();
		}
		return focusContext;
	}

	@Override
	public Collection<LensProjectionContext<P>> getProjectionContexts() {
		return projectionContexts;
	}
	
	public void addProjectionContext(LensProjectionContext<P> projectionContext) {
		projectionContexts.add(projectionContext);
	}
	
	public LensProjectionContext<P> findProjectionContextByOid(String oid) {
		for (LensProjectionContext<P> projCtx: getProjectionContexts()) {
			if (oid.equals(projCtx.getOid())) {
				return projCtx;
			}
		}
		return null;
	}
	
	public LensProjectionContext<P> findProjectionContext(ResourceShadowDiscriminator rat) {
		for (LensProjectionContext<P> projCtx: getProjectionContexts()) {
			if (rat.equals(projCtx.getResourceShadowDiscriminator())) {
				return projCtx;
			}
		}
		return null;
	}
	
	public LensProjectionContext<P> findOrCreateProjectionContext(ResourceShadowDiscriminator rat) {
		LensProjectionContext<P> projectionContext = findProjectionContext(rat);
		if (projectionContext == null) {
			projectionContext = createProjectionContext(rat);
		}
		return projectionContext;
	}
	
	public UserTemplateType getUserTemplate() {
		return userTemplate;
	}

	public void setUserTemplate(UserTemplateType userTemplate) {
		this.userTemplate = userTemplate;
	}

	public AccountSynchronizationSettingsType getAccountSynchronizationSettings() {
		return accountSynchronizationSettings;
	}

	public void setAccountSynchronizationSettings(
			AccountSynchronizationSettingsType accountSynchronizationSettings) {
		this.accountSynchronizationSettings = accountSynchronizationSettings;
	}
	
	public PasswordPolicyType getGlobalPasswordPolicy() {
		return globalPasswordPolicy;
	}
	
	public void setGlobalPasswordPolicy(PasswordPolicyType globalPasswordPolicy) {
		this.globalPasswordPolicy = globalPasswordPolicy;
	}
	
	public int getWave() {
		return wave;
	}

	public void setWave(int wave) {
		this.wave = wave;
	}
	
	public void incrementWave() {
		wave++;
	}
	
	public int getMaxWave() {
		int maxWave = 0;
		for (LensProjectionContext<P> projContext: projectionContexts) {
			if (projContext.getWave() > maxWave) {
				maxWave = projContext.getWave();
			}
		}
		return maxWave;
	}
	
	public boolean isFresh() {
		return isFresh;
	}

	public void setFresh(boolean isFresh) {
		this.isFresh = isFresh;
	}

	public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

	public boolean isDoReconciliationForAllProjections() {
		return doReconciliationForAllProjections;
	}

	public void setDoReconciliationForAllProjections(boolean doReconciliationForAllProjections) {
		this.doReconciliationForAllProjections = doReconciliationForAllProjections;
	}
	
	/**
     * Returns all changes, user and all accounts. Both primary and secondary changes are returned, but
     * these are not merged.
     * TODO: maybe it would be better to merge them.
     */
    public Collection<ObjectDelta<? extends ObjectType>> getAllChanges() throws SchemaException {
        Collection<ObjectDelta<? extends ObjectType>> allChanges = new ArrayList<ObjectDelta<? extends ObjectType>>();
        if (focusContext != null) {
	        addChangeIfNotNull(allChanges, focusContext.getPrimaryDelta());
	        addChangeIfNotNull(allChanges, focusContext.getSecondaryDelta());
        }
        for (LensProjectionContext<P> projCtx: getProjectionContexts()) {
            addChangeIfNotNull(allChanges, projCtx.getPrimaryDelta());
            addChangeIfNotNull(allChanges, projCtx.getSecondaryDelta());
        }
        return allChanges;
    }
    
    private <T extends ObjectType> void addChangeIfNotNull(Collection<ObjectDelta<? extends ObjectType>> changes,
            ObjectDelta<T> change) {
        if (change != null) {
            changes.add(change);
        }
    }
	
	public void recompute() throws SchemaException {
		recomputeFocus();
		recomputeProjections();
	}
	
	public void recomputeFocus() throws SchemaException {
		if (focusContext != null) {
			focusContext.recompute();
		}
	}
	
	public void recomputeProjections() throws SchemaException {
		for (LensProjectionContext<P> projCtx: getProjectionContexts()) {
			projCtx.recompute();
		}
	}

	public void checkConsistence() {
		if (focusContext != null) {
			focusContext.checkConsistence();
		}
		for (LensProjectionContext<P> projectionContext: projectionContexts) {
			projectionContext.checkConsistence(this.toString(), isFresh);
		}
	}
	
	public LensProjectionContext<P> createProjectionContext() {
		return createProjectionContext(null);
	}
	
	public LensProjectionContext<P> createProjectionContext(ResourceShadowDiscriminator rat) {
		LensProjectionContext<P> projCtx = new LensProjectionContext<P>(projectionClass, this, rat);
		addProjectionContext(projCtx);
		return projCtx;
	}
	
	private Map<String, ResourceType> getResourceCache() {
		if (resourceCache == null) {
			resourceCache = new HashMap<String, ResourceType>();
		}
		return resourceCache;
	}

	/**
     * Returns a resource for specified account type.
     * This is supposed to be efficient, taking the resource from the cache. It assumes the resource is in the cache.
     *
     * @see SyncContext#rememberResource(ResourceType)
     */
    public ResourceType getResource(ResourceShadowDiscriminator rat) {
        return getResource(rat.getResourceOid());
    }
    
    /**
     * Returns a resource for specified account type.
     * This is supposed to be efficient, taking the resource from the cache. It assumes the resource is in the cache.
     *
     * @see SyncContext#rememberResource(ResourceType)
     */
    public ResourceType getResource(String resourceOid) {
        return getResourceCache().get(resourceOid);
    }
	
	/**
     * Puts resources in the cache for later use. The resources should be fetched from provisioning
     * and have pre-parsed schemas. So the next time just reuse them without the other overhead.
     */
    public void rememberResources(Collection<ResourceType> resources) {
        for (ResourceType resourceType : resources) {
            rememberResource(resourceType);
        }
    }

    /**
     * Puts resource in the cache for later use. The resource should be fetched from provisioning
     * and have pre-parsed schemas. So the next time just reuse it without the other overhead.
     */
    public void rememberResource(ResourceType resourceType) {
    	getResourceCache().put(resourceType.getOid(), resourceType);
    }
    
    public void adopt(PrismContext prismContext) throws SchemaException {
    	this.prismContext = prismContext;
    	
    	if (focusContext != null) {
    		focusContext.adopt(prismContext);
    	}
    	for (LensProjectionContext<P> projectionContext: projectionContexts) {
    		projectionContext.adopt(prismContext);
    	}
    }
    
    public LensContext<F, P> clone() {
    	LensContext<F, P> clone = new LensContext<F, P>(focusClass, projectionClass, prismContext);
    	copyValues(clone);
    	return clone;
    }
    
    protected void copyValues(LensContext<F, P> clone) {
    	clone.state = this.state;
    	clone.channel = this.channel;
    	clone.doReconciliationForAllProjections = this.doReconciliationForAllProjections;
    	clone.focusClass = this.focusClass;
    	clone.isFresh = this.isFresh;
    	clone.prismContext = this.prismContext;
    	clone.projectionClass = this.projectionClass;
    	clone.resourceCache = cloneResourceCache();
    	// User template is de-facto immutable, OK to just pass reference here.
    	clone.userTemplate = this.userTemplate;
    	clone.wave = this.wave;
    	
    	if (this.focusContext != null) {
    		clone.focusContext = this.focusContext.clone(this);
    	}
    	
    	for (LensProjectionContext<P> thisProjectionContext: this.projectionContexts) {
    		clone.projectionContexts.add(thisProjectionContext.clone(this));
    	}
    }

	private Map<String, ResourceType> cloneResourceCache() {
		if (resourceCache == null) {
			return null;
		}
		Map<String, ResourceType> clonedMap = new HashMap<String, ResourceType>();
		for (Entry<String, ResourceType> entry: resourceCache.entrySet()) {
			clonedMap.put(entry.getKey(), entry.getValue());
		}
		return clonedMap;
	}

	@Override
    public String debugDump() {
        return debugDump(0);
    }

    @Override
    public String dump() {
        return debugDump(0);
    }
    
    public String dump(boolean showTriples) {
        return debugDump(0, showTriples);
    }

    @Override
    public String debugDump(int indent) {
    	return debugDump(indent, true);
    }
    
    public String debugDump(int indent, boolean showTriples) {
        StringBuilder sb = new StringBuilder();
        DebugUtil.indentDebugDump(sb, indent);
        sb.append("LensContext: state ").append(state);
        sb.append(", wave ").append(wave).append("\n");

        DebugUtil.indentDebugDump(sb, indent + 1);
        sb.append("Settings: ");
        if (accountSynchronizationSettings != null) {
            sb.append("assignments:");
            sb.append(accountSynchronizationSettings.getAssignmentPolicyEnforcement());
        } else {
            sb.append("null");
        }
        sb.append("\n");

        DebugUtil.debugDumpWithLabel(sb, "FOCUS", focusContext, indent + 1);

        sb.append("\n");
        DebugUtil.indentDebugDump(sb, indent + 1);
        sb.append("PROJECTIONS:");
        if (projectionContexts.isEmpty()) {
            sb.append(" none");
        } else {
        	sb.append(" (").append(projectionContexts.size()).append(")");
            for (LensProjectionContext<P> projCtx : projectionContexts) {
            	sb.append(":\n");
            	sb.append(projCtx.debugDump(indent + 2, showTriples));
            }
        }

        return sb.toString();
    }

	@Override
	public String toString() {
		return "LensContext(s=" + state + ", w=" + wave + ": "+focusContext+", "+projectionContexts+")";
	}
	
}
