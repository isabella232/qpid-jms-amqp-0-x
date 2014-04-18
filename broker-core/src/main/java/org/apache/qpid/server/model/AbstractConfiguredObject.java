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
package org.apache.qpid.server.model;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.security.auth.Subject;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.configuration.updater.ChangeAttributesTask;
import org.apache.qpid.server.configuration.updater.ChangeStateTask;
import org.apache.qpid.server.configuration.updater.CreateChildTask;
import org.apache.qpid.server.configuration.updater.SetAttributeTask;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.store.ConfiguredObjectRecord;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.ServerScopedRuntimeException;
import org.apache.qpid.util.Strings;

public abstract class AbstractConfiguredObject<X extends ConfiguredObject<X>> implements ConfiguredObject<X>
{
    private static final String ID = "id";

    private static final Map<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectAttribute<?,?>>> _allAttributes =
            Collections.synchronizedMap(new HashMap<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectAttribute<?, ?>>>());

    private static final Map<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectStatistic<?,?>>> _allStatistics =
            Collections.synchronizedMap(new HashMap<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectStatistic<?, ?>>>());

    private static final Map<Class<? extends ConfiguredObject>, Map<String, ConfiguredObjectAttribute<?,?>>> _allAttributeTypes =
            Collections.synchronizedMap(new HashMap<Class<? extends ConfiguredObject>, Map<String, ConfiguredObjectAttribute<?, ?>>>());

    private static final Map<Class<? extends ConfiguredObject>, Map<String, AutomatedField>> _allAutomatedFields =
            Collections.synchronizedMap(new HashMap<Class<? extends ConfiguredObject>, Map<String, AutomatedField>>());
    private static final Map<Class, Object> SECURE_VALUES;

    public static final String SECURED_STRING_VALUE = "********";

    static
    {
        Map<Class,Object> secureValues = new HashMap<Class, Object>();
        secureValues.put(String.class, SECURED_STRING_VALUE);
        secureValues.put(Integer.class, 0);
        secureValues.put(Long.class, 0l);
        secureValues.put(Byte.class, (byte)0);
        secureValues.put(Short.class, (short)0);
        secureValues.put(Double.class, (double)0);
        secureValues.put(Float.class, (float)0);

        SECURE_VALUES = Collections.unmodifiableMap(secureValues);
    }

    private static final Map<String, String> _defaultContext =
            Collections.synchronizedMap(new HashMap<String, String>());

    private final AtomicBoolean _open = new AtomicBoolean();

    private final Map<String,Object> _attributes = new HashMap<String, Object>();
    private final Map<Class<? extends ConfiguredObject>, ConfiguredObject> _parents =
            new HashMap<Class<? extends ConfiguredObject>, ConfiguredObject>();
    private final Collection<ConfigurationChangeListener> _changeListeners =
            new ArrayList<ConfigurationChangeListener>();

    private final Map<Class<? extends ConfiguredObject>, Collection<ConfiguredObject<?>>> _children =
            new ConcurrentHashMap<Class<? extends ConfiguredObject>, Collection<ConfiguredObject<?>>>();
    private final Map<Class<? extends ConfiguredObject>, Map<UUID,ConfiguredObject<?>>> _childrenById =
            new ConcurrentHashMap<Class<? extends ConfiguredObject>, Map<UUID,ConfiguredObject<?>>>();
    private final Map<Class<? extends ConfiguredObject>, Map<String,ConfiguredObject<?>>> _childrenByName =
            new ConcurrentHashMap<Class<? extends ConfiguredObject>, Map<String,ConfiguredObject<?>>>();


    @ManagedAttributeField
    private final UUID _id;

    private final TaskExecutor _taskExecutor;

    private final Class<? extends ConfiguredObject> _category;
    private final Class<? extends ConfiguredObject> _bestFitInterface;

    @ManagedAttributeField
    private long _createdTime;

    @ManagedAttributeField
    private String _createdBy;

    @ManagedAttributeField
    private long _lastUpdatedTime;

    @ManagedAttributeField
    private String _lastUpdatedBy;

    @ManagedAttributeField
    private String _name;

    @ManagedAttributeField
    private Map<String,String> _context;

    @ManagedAttributeField
    private boolean _durable;

    @ManagedAttributeField
    private String _description;

    @ManagedAttributeField
    private LifetimePolicy _lifetimePolicy;

    private final Map<String, ConfiguredObjectAttribute<?,?>> _attributeTypes;
    private final Map<String, AutomatedField> _automatedFields;

    @ManagedAttributeField
    private String _type;

    protected static Map<String,Object> combineIdWithAttributes(UUID id, Map<String,Object> attributes)
    {
        Map<String,Object> combined = new HashMap<String, Object>(attributes);
        combined.put(ID, id);
        return combined;
    }

    protected static Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parentsMap(ConfiguredObject<?>... parents)
    {
        final Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parentsMap =
                new HashMap<Class<? extends ConfiguredObject>, ConfiguredObject<?>>();

        for(ConfiguredObject<?> parent : parents)
        {
            parentsMap.put(parent.getCategoryClass(), parent);
        }
        return parentsMap;
    }


    protected AbstractConfiguredObject(final Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parents,
                                       Map<String, Object> attributes,
                                       TaskExecutor taskExecutor)
    {
        _taskExecutor = taskExecutor;
        Object idObj = attributes.get(ID);

        UUID uuid;
        if(idObj == null)
        {
            uuid = UUID.randomUUID();
            attributes = new HashMap<String, Object>(attributes);
            attributes.put(ID, uuid);
        }
        else
        {
            uuid = AttributeValueConverter.UUID_CONVERTER.convert(idObj, this);
        }
        _id = uuid;

        _name = AttributeValueConverter.STRING_CONVERTER.convert(attributes.get(NAME),this);
        if(_name == null)
        {
            throw new IllegalArgumentException("The name attribute is mandatory for " + getClass().getSimpleName() + " creation.");
        }

        _attributeTypes = getAttributeTypes(getClass());
        _automatedFields = getAutomatedFields(getClass());

        _category = Model.getCategory(getClass());
        _type = Model.getType(getClass());
        _bestFitInterface = calculateBestFitInterface();

        if(attributes.get(TYPE) != null)
        {
            if(!_type.equals(attributes.get(TYPE)))
            {
                throw new IllegalConfigurationException("Provided type is " + attributes.get(TYPE)
                                                        + " but calculated type is " + _type);
            }
        }

        for (Class<? extends ConfiguredObject> childClass : Model.getInstance().getChildTypes(getCategoryClass()))
        {
            _children.put(childClass, new CopyOnWriteArrayList<ConfiguredObject<?>>());
            _childrenById.put(childClass, new ConcurrentHashMap<UUID, ConfiguredObject<?>>());
            _childrenByName.put(childClass, new ConcurrentHashMap<String, ConfiguredObject<?>>());
        }

        for(ConfiguredObject<?> parent : parents.values())
        {
            if(parent instanceof AbstractConfiguredObject<?>)
            {
                ((AbstractConfiguredObject<?>)parent).registerChild(this);
            }
        }

        for(Map.Entry<Class<? extends ConfiguredObject>, ConfiguredObject<?>> entry : parents.entrySet())
        {
            addParent((Class<ConfiguredObject<?>>) entry.getKey(), entry.getValue());
        }

        Object durableObj = attributes.get(DURABLE);
        _durable = AttributeValueConverter.BOOLEAN_CONVERTER.convert(durableObj == null ? _attributeTypes.get(DURABLE).getAnnotation().defaultValue() : durableObj, this);

        Collection<String> names = getAttributeNames();
        if(names!=null)
        {
            for (String name : names)
            {
                if (attributes.containsKey(name))
                {
                    final Object value = attributes.get(name);
                    if(value != null)
                    {
                        _attributes.put(name, value);
                    }
                }
            }
        }

        if(!_attributes.containsKey(CREATED_BY))
        {
            final AuthenticatedPrincipal currentUser = SecurityManager.getCurrentUser();
            if(currentUser != null)
            {
                _attributes.put(CREATED_BY, currentUser.getName());
            }
        }
        if(!_attributes.containsKey(CREATED_TIME))
        {
            _attributes.put(CREATED_TIME, System.currentTimeMillis());
        }
        for(ConfiguredObjectAttribute<?,?> attr : _attributeTypes.values())
        {
            if(attr.getAnnotation().mandatory() && !(_attributes.containsKey(attr.getName())
                                                     || !"".equals(attr.getAnnotation().defaultValue())))
            {
                deleted();
                throw new IllegalArgumentException("Mandatory attribute " + attr.getName() + " not supplied for instance of " + getClass().getName());
            }
        }
    }

    private Class<? extends ConfiguredObject> calculateBestFitInterface()
    {
        Set<Class<? extends ConfiguredObject>> candidates = new HashSet<Class<? extends ConfiguredObject>>();
        findBestFitInterface(getClass(), candidates);
        switch(candidates.size())
        {
            case 0:
                throw new ServerScopedRuntimeException("The configured object class " + getClass().getSimpleName() + " does not seem to implement an interface");
            case 1:
                return candidates.iterator().next();
            default:
                throw new ServerScopedRuntimeException("The configured object class " + getClass().getSimpleName() + " implements no single common interface which extends ConfiguredObject");
        }
    }

    private static final void findBestFitInterface(Class<? extends ConfiguredObject> clazz, Set<Class<? extends ConfiguredObject>> candidates)
    {
        for(Class<?> interfaceClass : clazz.getInterfaces())
        {
            if(ConfiguredObject.class.isAssignableFrom(interfaceClass))
            {
                checkCandidate((Class<? extends ConfiguredObject>) interfaceClass, candidates);
            }
        }
        if(clazz.getSuperclass() != null & ConfiguredObject.class.isAssignableFrom(clazz.getSuperclass()))
        {
            findBestFitInterface((Class<? extends ConfiguredObject>) clazz.getSuperclass(), candidates);
        }
    }

    private static void checkCandidate(final Class<? extends ConfiguredObject> interfaceClass,
                                       final Set<Class<? extends ConfiguredObject>> candidates)
    {
        if(!candidates.contains(interfaceClass))
        {
            Iterator<Class<? extends ConfiguredObject>> candidateIterator = candidates.iterator();

            while(candidateIterator.hasNext())
            {
                Class<? extends ConfiguredObject> existingCandidate = candidateIterator.next();
                if(existingCandidate.isAssignableFrom(interfaceClass))
                {
                    candidateIterator.remove();
                }
                else if(interfaceClass.isAssignableFrom(existingCandidate))
                {
                    return;
                }
            }

            candidates.add(interfaceClass);

        }
    }

    private void automatedSetValue(final String name, Object value)
    {
        try
        {
            final ConfiguredObjectAttribute attribute = _attributeTypes.get(name);
            if(value == null && !"".equals(attribute.getAnnotation().defaultValue()))
            {
                value = attribute.getAnnotation().defaultValue();
            }
            AutomatedField field = _automatedFields.get(name);

            if(field.getPreSettingAction() != null)
            {
                field.getPreSettingAction().invoke(this);
            }
            field.getField().set(this, attribute.convert(value, this));

            if(field.getPostSettingAction() != null)
            {
                field.getPostSettingAction().invoke(this);
            }
        }
        catch (IllegalAccessException e)
        {
            throw new ServerScopedRuntimeException("Unable to set the automated attribute " + name + " on the configure object type " + getClass().getName(),e);
        }
        catch (InvocationTargetException e)
        {
            if(e.getCause() instanceof RuntimeException)
            {
                throw (RuntimeException) e.getCause();
            }
            throw new ServerScopedRuntimeException("Unable to set the automated attribute " + name + " on the configure object type " + getClass().getName(),e);
        }
    }

    public final void open()
    {
        if(_open.compareAndSet(false,true))
        {
            doResolution(true);
            doValidation(true);
            doOpening(true);
        }
    }


    public final void create()
    {
        if(_open.compareAndSet(false,true))
        {
            doResolution(true);
            doValidation(true);
            doCreation(true);
            doOpening(true);
        }
    }

    protected void doOpening(final boolean skipCheck)
    {
        if(skipCheck || _open.compareAndSet(false,true))
        {
            onOpen();
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doOpening(false);
                    }
                }
            });
        }
    }

    protected final void doValidation(final boolean skipCheck)
    {
        if(skipCheck || !_open.get())
        {
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doValidation(false);
                    }
                }
            });
            validate();
        }
    }

    protected final void doResolution(final boolean skipCheck)
    {
        if(skipCheck || !_open.get())
        {
            resolve();
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doResolution(false);
                    }
                }
            });
        }
    }

    protected final void doCreation(final boolean skipCheck)
    {
        if(skipCheck || !_open.get())
        {
            onCreate();
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doCreation(false);
                    }
                }
            });
        }
    }

    private void applyToChildren(Action<ConfiguredObject<?>> action)
    {
        for (Class<? extends ConfiguredObject> childClass : Model.getInstance().getChildTypes(getCategoryClass()))
        {
            Collection<? extends ConfiguredObject> children = getChildren(childClass);
            if (children != null)
            {
                for (ConfiguredObject<?> child : children)
                {
                    action.performAction(child);
                }
            }
        }
    }

    public void
    validate()
    {
    }

    protected void resolve()
    {
        for (ConfiguredObjectAttribute<?, ?> attr : _attributeTypes.values())
        {
            String attrName = attr.getName();
            ManagedAttribute attrAnnotation = attr.getAnnotation();
            if (attrAnnotation.automate())
            {
                if (_attributes.containsKey(attrName))
                {
                    automatedSetValue(attrName, _attributes.get(attrName));
                }
                else if (!"".equals(attrAnnotation.defaultValue()))
                {
                    automatedSetValue(attrName, attrAnnotation.defaultValue());
                }

            }
        }
    }

    protected void onOpen()
    {
    }


    protected void onCreate()
    {
    }

    public final UUID getId()
    {
        return _id;
    }

    public final String getName()
    {
        return _name;
    }

    public final boolean isDurable()
    {
        return _durable;
    }


    public Class<? extends ConfiguredObject> getCategoryClass()
    {
        return _category;
    }

    public Map<String,String> getContext()
    {
        return _context == null ? null : Collections.unmodifiableMap(_context);
    }

    public State getDesiredState()
    {
        return null;  //TODO
    }

    @Override
    public final State setDesiredState(final State currentState, final State desiredState)
            throws IllegalStateTransitionException, AccessControlException
    {
        if (_taskExecutor.isTaskExecutorThread())
        {
            authoriseSetDesiredState(currentState, desiredState);
            if (setState(currentState, desiredState))
            {
                notifyStateChanged(currentState, desiredState);
                return desiredState;
            }
            else
            {
                return getState();
            }
        }
        else
        {
            return _taskExecutor.submitAndWait(new ChangeStateTask(this, currentState, desiredState));
        }
    }

    /**
     * @return true when the state has been successfully updated to desiredState or false otherwise
     */
    protected abstract boolean setState(State currentState, State desiredState);

    protected void notifyStateChanged(final State currentState, final State desiredState)
    {
        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.stateChanged(this, currentState, desiredState);
            }
        }
    }

    public void addChangeListener(final ConfigurationChangeListener listener)
    {
        if(listener == null)
        {
            throw new NullPointerException("Cannot add a null listener");
        }
        synchronized (_changeListeners)
        {
            if(!_changeListeners.contains(listener))
            {
                _changeListeners.add(listener);
            }
        }
    }

    public boolean removeChangeListener(final ConfigurationChangeListener listener)
    {
        if(listener == null)
        {
            throw new NullPointerException("Cannot remove a null listener");
        }
        synchronized (_changeListeners)
        {
            return _changeListeners.remove(listener);
        }
    }

    protected void childAdded(ConfiguredObject child)
    {
        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.childAdded(this, child);
            }
        }
    }

    protected void childRemoved(ConfiguredObject child)
    {
        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.childRemoved(this, child);
            }
        }
    }

    protected void attributeSet(String attributeName, Object oldAttributeValue, Object newAttributeValue)
    {

        final AuthenticatedPrincipal currentUser = SecurityManager.getCurrentUser();
        if(currentUser != null)
        {
            _attributes.put(LAST_UPDATED_BY, currentUser.getName());
            _lastUpdatedBy = currentUser.getName();
        }
        final long currentTime = System.currentTimeMillis();
        _attributes.put(LAST_UPDATED_TIME, currentTime);
        _lastUpdatedTime = currentTime;

        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.attributeSet(this, attributeName, oldAttributeValue, newAttributeValue);
            }
        }
    }

    @Override
    public Object getAttribute(String name)
    {
        ConfiguredObjectAttribute<X,?> attr = (ConfiguredObjectAttribute<X, ?>) _attributeTypes.get(name);
        if(attr != null && (attr.getAnnotation().automate() || attr.getAnnotation().derived()))
        {
            Object value = attr.getValue((X)this);
            if(value != null && attr.getAnnotation().secure() &&
               !SecurityManager.isSystemProcess())
            {
                return SECURE_VALUES.get(value.getClass());
            }
            else
            {
                return value;
            }
        }
        else
        {
            Object value = getActualAttribute(name);
            return value;
        }
    }

    @Override
    public String getDescription()
    {
        return _description;
    }

    @Override
    public LifetimePolicy getLifetimePolicy()
    {
        return _lifetimePolicy;
    }

    @Override
    public <T> T getAttribute(final ConfiguredObjectAttribute<? super X, T> attr)
    {
        return (T) getAttribute(attr.getName());
    }

    @Override
    public final Map<String, Object> getActualAttributes()
    {
        synchronized (_attributes)
        {
            return new HashMap<String, Object>(_attributes);
        }
    }

    private Object getActualAttribute(final String name)
    {
        if(CREATED_BY.equals(name))
        {
            return getCreatedBy();
        }
        else if(CREATED_TIME.equals(name))
        {
            return getCreatedTime();
        }
        else
        {
            synchronized (_attributes)
            {
                return _attributes.get(name);
            }
        }
    }

    public Object setAttribute(final String name, final Object expected, final Object desired)
            throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        if (_taskExecutor.isTaskExecutorThread())
        {
            authoriseSetAttributes(createProxyForValidation(Collections.singletonMap(name, desired)),
                                   Collections.singleton(name));

            if (changeAttribute(name, expected, desired))
            {
                attributeSet(name, expected, desired);
                return desired;
            }
            else
            {
                return getAttribute(name);
            }
        }
        else
        {
            return _taskExecutor.submitAndWait(new SetAttributeTask(this, name, expected, desired));
        }
    }

    protected boolean changeAttribute(final String name, final Object expected, final Object desired)
    {
        synchronized (_attributes)
        {
            Object currentValue = getAttribute(name);
            if((currentValue == null && expected == null)
               || (currentValue != null && currentValue.equals(expected)))
            {
                //TODO: don't put nulls
                _attributes.put(name, desired);
                ConfiguredObjectAttribute<?,?> attr = _attributeTypes.get(name);
                if(attr != null && attr.getAnnotation().automate())
                {
                    automatedSetValue(name, desired);
                }
                return true;
            }
            else
            {
                return false;
            }
        }
    }

    public <T extends ConfiguredObject> T getParent(final Class<T> clazz)
    {
        synchronized (_parents)
        {
            return (T) _parents.get(clazz);
        }
    }

    private <T extends ConfiguredObject> void addParent(Class<T> clazz, T parent)
    {
        synchronized (_parents)
        {
            _parents.put(clazz, parent);
        }

    }

    public final Collection<String> getAttributeNames()
    {
        return getAttributeNames(getClass());
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " [id=" + _id + ", name=" + getName() + "]";
    }

    public ConfiguredObjectRecord asObjectRecord()
    {
        return new ConfiguredObjectRecord()
        {
            @Override
            public UUID getId()
            {
                return AbstractConfiguredObject.this.getId();
            }

            @Override
            public String getType()
            {
                return getCategoryClass().getSimpleName();
            }

            @Override
            public Map<String, Object> getAttributes()
            {
                return Subject.doAs(SecurityManager.getSubjectWithAddedSystemRights(), new PrivilegedAction<Map<String, Object>>()
                {
                    @Override
                    public Map<String, Object> run()
                    {
                        Map<String,Object> actualAttributes = new HashMap<String, Object>(getActualAttributes());
                        for(Map.Entry<String,Object> entry : actualAttributes.entrySet())
                        {
                            if(entry.getValue() instanceof ConfiguredObject)
                            {
                                entry.setValue(((ConfiguredObject)entry.getValue()).getId());
                            }
                        }
                        actualAttributes.remove(ID);
                        return actualAttributes;
                    }
                });
            }

            @Override
            public Map<String, ConfiguredObjectRecord> getParents()
            {
                Map<String, ConfiguredObjectRecord> parents = new LinkedHashMap<String, ConfiguredObjectRecord>();
                for(Class<? extends ConfiguredObject> parentClass : Model.getInstance().getParentTypes(getCategoryClass()))
                {
                    ConfiguredObject parent = getParent(parentClass);
                    if(parent != null)
                    {
                        parents.put(parentClass.getSimpleName(), parent.asObjectRecord());
                    }
                }
                return parents;
            }

            @Override
            public String toString()
            {
                return getClass().getSimpleName() + "[name=" + getName() + ", categoryClass=" + getCategoryClass() + ", type="
                        + getType() + ", id=" + getId() + "]";
            }
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    public <C extends ConfiguredObject> C createChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        if (_taskExecutor.isTaskExecutorThread())
        {
            authoriseCreateChild(childClass, attributes, otherParents);
            C child = addChild(childClass, attributes, otherParents);
            if (child != null)
            {
                childAdded(child);
            }
            return child;
        }
        else
        {
            return (C)_taskExecutor.submitAndWait(new CreateChildTask(this, childClass, attributes, otherParents));
        }
    }

    protected <C extends ConfiguredObject> C addChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        throw new UnsupportedOperationException();
    }

    private <C extends ConfiguredObject> void registerChild(final C child)
    {

        Class categoryClass = child.getCategoryClass();
        UUID childId = child.getId();
        String name = child.getName();
        if(_childrenById.get(categoryClass).containsKey(childId))
        {
            throw new DuplicateIdException(child);
        }
        if(_childrenByName.get(categoryClass).containsKey(name))
        {
            Collection<Class<? extends ConfiguredObject>> parentTypes =
                    new ArrayList<Class<? extends ConfiguredObject>>(Model.getInstance().getParentTypes(categoryClass));
            parentTypes.remove(getCategoryClass());
            boolean duplicate = true;

            C existing = (C) _childrenByName.get(categoryClass).get(name);
            for(Class<? extends ConfiguredObject> parentType : parentTypes)
            {
                ConfiguredObject existingParent = existing.getParent(parentType);
                ConfiguredObject childParent = child.getParent(parentType);
                duplicate =  existingParent == childParent;
                if(!duplicate)
                {
                    break;
                }
            }

            if(duplicate)
            {
                throw new DuplicateNameException(child);
            }
        }
        _children.get(categoryClass).add(child);
        _childrenById.get(categoryClass).put(childId,child);
        _childrenByName.get(categoryClass).put(name, child);

    }


    protected void deleted()
    {
        for (ConfiguredObject<?> parent : _parents.values())
        {
            if (parent instanceof AbstractConfiguredObject<?>)
            {
                AbstractConfiguredObject<?> parentObj = (AbstractConfiguredObject<?>) parent;
                parentObj.unregisterChild(this);
                parentObj.childRemoved(this);
            }
        }
    }


    private <C extends ConfiguredObject> void unregisterChild(final C child)
    {
        Class categoryClass = child.getCategoryClass();
        _children.get(categoryClass).remove(child);
        _childrenById.get(categoryClass).remove(child.getId());
        _childrenByName.get(categoryClass).remove(child.getName());
    }

    @Override
    public final <C extends ConfiguredObject> C getChildById(final Class<C> clazz, final UUID id)
    {
        return (C) _childrenById.get(Model.getCategory(clazz)).get(id);
    }

    @Override
    public final <C extends ConfiguredObject> C getChildByName(final Class<C> clazz, final String name)
    {
        return (C) _childrenByName.get(Model.getCategory(clazz)).get(name);
    }

    @Override
    public <C extends ConfiguredObject> Collection<C> getChildren(final Class<C> clazz)
    {
        return Collections.unmodifiableList((List<? extends C>) _children.get(clazz));
    }

    public TaskExecutor getTaskExecutor()
    {
        return _taskExecutor;
    }

    @Override
    public void setAttributes(final Map<String, Object> attributes) throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        if (getTaskExecutor().isTaskExecutorThread())
        {
            authoriseSetAttributes(createProxyForValidation(attributes), attributes.keySet());
            changeAttributes(attributes);
        }
        else
        {
            getTaskExecutor().submitAndWait(new ChangeAttributesTask(this, attributes));
        }
    }

    protected void authoriseSetAttributes(final ConfiguredObject<?> proxyForValidation,
                                          final Set<String> modifiedAttributes)
    {

    }

    protected void changeAttributes(final Map<String, Object> attributes)
    {
        validateChange(createProxyForValidation(attributes), attributes.keySet());
        Collection<String> names = getAttributeNames();
        for (String name : names)
        {
            if (attributes.containsKey(name))
            {
                Object desired = attributes.get(name);
                Object expected = getAttribute(name);
                if(((_attributes.get(name) != null && !_attributes.get(name).equals(attributes.get(name)))
                     || attributes.get(name) != null)
                    && changeAttribute(name, expected, desired))
                {
                    attributeSet(name, expected, desired);
                }
            }
        }
    }

    protected void validateChange(final ConfiguredObject<?> proxyForValidation, final Set<String> changedAttributes)
    {
        if(!getId().equals(proxyForValidation.getId()))
        {
            throw new IllegalConfigurationException("Cannot change existing configured object id");
        }
    }

    private ConfiguredObject<?> createProxyForValidation(final Map<String, Object> attributes)
    {
        return (ConfiguredObject<?>) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{_bestFitInterface},
                                      new AttributeGettingHandler(attributes));
    }

    protected void authoriseSetDesiredState(State currentState, State desiredState) throws AccessControlException
    {
        // allowed by default
    }

    protected <C extends ConfiguredObject> void authoriseCreateChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents) throws AccessControlException
    {
        // allowed by default
    }

    /**
     * Returns a map of effective attribute values that would result
     * if applying the supplied changes. Does not apply the changes.
     */
    protected Map<String, Object> generateEffectiveAttributes(Map<String,Object> changedValues)
    {
        //Build a new set of effective attributes that would be
        //the result of applying the attribute changes, so we
        //can validate the configuration that would result

        Map<String, Object> existingActualValues = getActualAttributes();

        //create a new merged map, starting with the defaults
        Map<String, Object> merged =  new HashMap<String, Object>();

        for(String name : getAttributeNames())
        {
            if(changedValues.containsKey(name))
            {
                Object changedValue = changedValues.get(name);
                if(changedValue != null)
                {
                    //use the new non-null value for the merged values
                    merged.put(name, changedValue);
                }
                else
                {
                    //we just use the default (if there was one) since the changed
                    //value is null and effectively clears any existing actual value
                }
            }
            else if(existingActualValues.get(name) != null)
            {
                //Use existing non-null actual value for the merge
                merged.put(name, existingActualValues.get(name));
            }
            else
            {
                //There was neither a change or an existing non-null actual
                //value, so just use the default value (if there was one).
            }
        }

        return merged;
    }

    @Override
    public final String getLastUpdatedBy()
    {
        return _lastUpdatedBy;
    }

    @Override
    public final long getLastUpdatedTime()
    {
        return _lastUpdatedTime;
    }

    @Override
    public final String getCreatedBy()
    {
        return _createdBy;
    }

    protected String getCurrentUserName()
    {
        Subject currentSubject = Subject.getSubject(AccessController.getContext());
        Set<AuthenticatedPrincipal> principals =
                currentSubject == null ? null : currentSubject.getPrincipals(AuthenticatedPrincipal.class);
        if(principals == null || principals.isEmpty())
        {
            return null;
        }
        else
        {
            return principals.iterator().next().getName();
        }
    }

    @Override
    public final long getCreatedTime()
    {
        return _createdTime;
    }

    @Override
    public final String getType()
    {
        return _type;
    }


    @Override
    public Map<String,Number> getStatistics()
    {
        Collection<ConfiguredObjectStatistic> stats = getStatistics(getClass());
        Map<String,Number> map = new HashMap<String,Number>();
        for(ConfiguredObjectStatistic stat : stats)
        {
            map.put(stat.getName(), (Number) stat.getValue(this));
        }
        return map;
    }


    public <Y extends ConfiguredObject<Y>> Y findConfiguredObject(Class<Y> clazz, String name)
    {
        Collection<Y> reachable = getReachableObjects(this,clazz);
        for(Y candidate : reachable)
        {
            if(candidate.getName().equals(name))
            {
                return candidate;
            }
        }
        return null;
    }

    //=========================================================================================

    static String interpolate(ConfiguredObject<?> object, String value)
    {
        Map<String,String> inheritedContext = new HashMap<String, String>();
        generateInheritedContext(object, inheritedContext);
        return Strings.expand(value, false,
                              new Strings.MapResolver(inheritedContext),
                              Strings.JAVA_SYS_PROPS_RESOLVER,
                              Strings.ENV_VARS_RESOLVER,
                              new Strings.MapResolver(_defaultContext));
    }

    static void generateInheritedContext(final ConfiguredObject<?> object,
                                                 final Map<String, String> inheritedContext)
    {
        Collection<Class<? extends ConfiguredObject>> parents =
                Model.getInstance().getParentTypes(object.getCategoryClass());
        if(parents != null && !parents.isEmpty())
        {
            ConfiguredObject parent = object.getParent(parents.iterator().next());
            if(parent != null)
            {
                generateInheritedContext(parent, inheritedContext);
            }
        }
        if(object.getContext() != null)
        {
            inheritedContext.putAll(object.getContext());
        }
    }


    private static void addToAttributesSet(final Class<? extends ConfiguredObject> clazz, final ConfiguredObjectAttribute<?, ?> attribute)
    {
        synchronized (_allAttributes)
        {
            Collection<ConfiguredObjectAttribute<?,?>> classAttributes = _allAttributes.get(clazz);
            if(classAttributes == null)
            {
                classAttributes = new ArrayList<ConfiguredObjectAttribute<?, ?>>();
                for(Map.Entry<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectAttribute<?,?>>> entry : _allAttributes.entrySet())
                {
                    if(entry.getKey().isAssignableFrom(clazz))
                    {
                        classAttributes.addAll(entry.getValue());
                    }
                }
                _allAttributes.put(clazz, classAttributes);

            }
            for(Map.Entry<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectAttribute<?,?>>> entry : _allAttributes.entrySet())
            {
                if(clazz.isAssignableFrom(entry.getKey()))
                {
                    entry.getValue().add(attribute);
                }
            }

        }
    }
    private static void addToStatisticsSet(final Class<? extends ConfiguredObject> clazz, final ConfiguredObjectStatistic<?, ?> statistic)
    {
        synchronized (_allStatistics)
        {
            Collection<ConfiguredObjectStatistic<?,?>> classAttributes = _allStatistics.get(clazz);
            if(classAttributes == null)
            {
                classAttributes = new ArrayList<ConfiguredObjectStatistic<?, ?>>();
                for(Map.Entry<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectStatistic<?,?>>> entry : _allStatistics.entrySet())
                {
                    if(entry.getKey().isAssignableFrom(clazz))
                    {
                        classAttributes.addAll(entry.getValue());
                    }
                }
                _allStatistics.put(clazz, classAttributes);

            }
            for(Map.Entry<Class<? extends ConfiguredObject>, Collection<ConfiguredObjectStatistic<?,?>>> entry : _allStatistics.entrySet())
            {
                if(clazz.isAssignableFrom(entry.getKey()))
                {
                    entry.getValue().add(statistic);
                }
            }

        }
    }

    private static class AutomatedField
    {
        private final Field _field;
        private final Method _preSettingAction;
        private final Method _postSettingAction;

        private AutomatedField(final Field field, final Method preSettingAction, final Method postSettingAction)
        {
            _field = field;
            _preSettingAction = preSettingAction;
            _postSettingAction = postSettingAction;
        }

        public Field getField()
        {
            return _field;
        }

        public Method getPreSettingAction()
        {
            return _preSettingAction;
        }

        public Method getPostSettingAction()
        {
            return _postSettingAction;
        }
    }

    private static <X extends ConfiguredObject> void processAttributes(final Class<X> clazz)
    {
        synchronized (_allAttributes)
        {
            if(_allAttributes.containsKey(clazz))
            {
                return;
            }


            for(Class<?> parent : clazz.getInterfaces())
            {
                if(ConfiguredObject.class.isAssignableFrom(parent))
                {
                    processAttributes((Class<? extends ConfiguredObject>)parent);
                }
            }
            final Class<? super X> superclass = clazz.getSuperclass();
            if(superclass != null && ConfiguredObject.class.isAssignableFrom(superclass))
            {
                processAttributes((Class<? extends ConfiguredObject>) superclass);
            }

            final ArrayList<ConfiguredObjectAttribute<?, ?>> attributeList = new ArrayList<ConfiguredObjectAttribute<?, ?>>();
            final ArrayList<ConfiguredObjectStatistic<?, ?>> statisticList = new ArrayList<ConfiguredObjectStatistic<?, ?>>();

            _allAttributes.put(clazz, attributeList);
            _allStatistics.put(clazz, statisticList);

            for(Class<?> parent : clazz.getInterfaces())
            {
                if(ConfiguredObject.class.isAssignableFrom(parent))
                {
                    Collection<ConfiguredObjectAttribute<?, ?>> attrs = _allAttributes.get(parent);
                    for(ConfiguredObjectAttribute<?,?> attr : attrs)
                    {
                        if(!attributeList.contains(attr))
                        {
                            attributeList.add(attr);
                        }
                    }
                    Collection<ConfiguredObjectStatistic<?, ?>> stats = _allStatistics.get(parent);
                    for(ConfiguredObjectStatistic<?,?> stat : stats)
                    {
                        if(!statisticList.contains(stat))
                        {
                            statisticList.add(stat);
                        }
                    }
                }
            }
            if(superclass != null && ConfiguredObject.class.isAssignableFrom(superclass))
            {
                Collection<ConfiguredObjectAttribute<?, ?>> attrs = _allAttributes.get(superclass);
                Collection<ConfiguredObjectStatistic<?, ?>> stats = _allStatistics.get(superclass);
                for(ConfiguredObjectAttribute<?,?> attr : attrs)
                {
                    if(!attributeList.contains(attr))
                    {
                        attributeList.add(attr);
                    }
                }
                for(ConfiguredObjectStatistic<?,?> stat : stats)
                {
                    if(!statisticList.contains(stat))
                    {
                        statisticList.add(stat);
                    }
                }
            }


            for(Method m : clazz.getDeclaredMethods())
            {
                ManagedAttribute annotation = m.getAnnotation(ManagedAttribute.class);
                if(annotation != null)
                {
                    if(!clazz.isInterface() || !ConfiguredObject.class.isAssignableFrom(clazz))
                    {
                        throw new ServerScopedRuntimeException("Can only define ManagedAttributes on interfaces which extend " + ConfiguredObject.class.getSimpleName() + ". " + clazz.getSimpleName() + " does not meet these criteria.");
                    }
                    addToAttributesSet(clazz, new ConfiguredObjectAttribute(clazz, m, annotation));
                }
                else
                {
                    ManagedStatistic statAnnotation = m.getAnnotation(ManagedStatistic.class);
                    if(statAnnotation != null)
                    {
                        if(!clazz.isInterface() || !ConfiguredObject.class.isAssignableFrom(clazz))
                        {
                            throw new ServerScopedRuntimeException("Can only define ManagedStatistics on interfaces which extend " + ConfiguredObject.class.getSimpleName() + ". " + clazz.getSimpleName() + " does not meet these criteria.");
                        }
                        addToStatisticsSet(clazz, new ConfiguredObjectStatistic(clazz,m));
                    }
                }
            }

            Map<String,ConfiguredObjectAttribute<?,?>> attrMap = new HashMap<String, ConfiguredObjectAttribute<?, ?>>();
            Map<String,AutomatedField> fieldMap = new HashMap<String, AutomatedField>();


            Collection<ConfiguredObjectAttribute<?, ?>> attrCol = _allAttributes.get(clazz);
            for(ConfiguredObjectAttribute<?,?> attr : attrCol)
            {
                attrMap.put(attr.getName(), attr);
                if(attr.getAnnotation().automate())
                {
                    fieldMap.put(attr.getName(), findField(attr, clazz));
                }

            }
            _allAttributeTypes.put(clazz, attrMap);
            _allAutomatedFields.put(clazz, fieldMap);

            for(Field field : clazz.getDeclaredFields())
            {
                if(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()) && field.isAnnotationPresent(ManagedContextDefault.class))
                {
                    try
                    {
                        String name = field.getAnnotation(ManagedContextDefault.class).name();
                        Object value = field.get(null);
                        if(!_defaultContext.containsKey(name))
                        {
                            _defaultContext.put(name,String.valueOf(value));
                        }
                        else
                        {
                            throw new IllegalArgumentException("Multiple definitions of the default context variable ${"+name+"}");
                        }
                    }
                    catch (IllegalAccessException e)
                    {
                        throw new ServerScopedRuntimeException("Unkecpected illegal access exception (only inspecting public static fields)", e);
                    }
                }
            }
        }
    }

    private static AutomatedField findField(final ConfiguredObjectAttribute<?, ?> attr, Class<?> objClass)
    {
        Class<?> clazz = objClass;
        while(clazz != null)
        {
            for(Field field : clazz.getDeclaredFields())
            {
                if(field.isAnnotationPresent(ManagedAttributeField.class) && field.getName().equals("_" + attr.getName().replace('.','_')))
                {
                    try
                    {
                        ManagedAttributeField annotation = field.getAnnotation(ManagedAttributeField.class);
                        field.setAccessible(true);
                        Method beforeSet;
                        if (!"".equals(annotation.beforeSet()))
                        {
                            beforeSet = clazz.getDeclaredMethod(annotation.beforeSet());
                            beforeSet.setAccessible(true);
                        }
                        else
                        {
                            beforeSet = null;
                        }
                        Method afterSet;
                        if (!"".equals(annotation.afterSet()))
                        {
                            afterSet = clazz.getDeclaredMethod(annotation.afterSet());
                            afterSet.setAccessible(true);
                        }
                        else
                        {
                            afterSet = null;
                        }
                        return new AutomatedField(field, beforeSet, afterSet);
                    }
                    catch (NoSuchMethodException e)
                    {
                        throw new ServerScopedRuntimeException("Cannot find method referenced by annotation for pre/post setting action", e);
                    }

                }
            }
            clazz = clazz.getSuperclass();
        }
        if(objClass.isInterface() || Modifier.isAbstract(objClass.getModifiers()))
        {
            return null;
        }
        throw new ServerScopedRuntimeException("Unable to find field definition for automated field " + attr.getName() + " in class " + objClass.getName());
    }

    public static <X extends ConfiguredObject> Collection<String> getAttributeNames(Class<X> clazz)
    {
        final Collection<ConfiguredObjectAttribute<? super X, ?>> attrs = getAttributes(clazz);

        return new AbstractCollection<String>()
        {
            @Override
            public Iterator<String> iterator()
            {
                final Iterator<ConfiguredObjectAttribute<? super X, ?>> underlyingIterator = attrs.iterator();
                return new Iterator<String>()
                {
                    @Override
                    public boolean hasNext()
                    {
                        return underlyingIterator.hasNext();
                    }

                    @Override
                    public String next()
                    {
                        return underlyingIterator.next().getName();
                    }

                    @Override
                    public void remove()
                    {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override
            public int size()
            {
                return attrs.size();
            }
        };

    }

    protected static <X extends ConfiguredObject> Collection<ConfiguredObjectAttribute<? super X, ?>> getAttributes(final Class<X> clazz)
    {
        if(!_allAttributes.containsKey(clazz))
        {
            processAttributes(clazz);
        }
        final Collection<ConfiguredObjectAttribute<? super X, ?>> attributes = (Collection) _allAttributes.get(clazz);
        return attributes;
    }


    protected static Collection<ConfiguredObjectStatistic> getStatistics(final Class<? extends ConfiguredObject> clazz)
    {
        if(!_allStatistics.containsKey(clazz))
        {
            processAttributes(clazz);
        }
        final Collection<ConfiguredObjectStatistic> statistics = (Collection) _allStatistics.get(clazz);
        return statistics;
    }


    private static Map<String, ConfiguredObjectAttribute<?, ?>> getAttributeTypes(final Class<? extends ConfiguredObject> clazz)
    {
        if(!_allAttributeTypes.containsKey(clazz))
        {
            processAttributes(clazz);
        }
        return _allAttributeTypes.get(clazz);
    }

    private static Map<String, AutomatedField> getAutomatedFields(Class<? extends ConfiguredObject> clazz)
    {
        if(!_allAutomatedFields.containsKey(clazz))
        {
            processAttributes(clazz);
        }
        return _allAutomatedFields.get(clazz);
    }

    static <X extends ConfiguredObject<X>> Collection<X> getReachableObjects(final ConfiguredObject<?> object,
                                                                                     final Class<X> clazz)
    {
        Class<? extends ConfiguredObject> category = Model.getCategory(object.getClass());
        Class<? extends ConfiguredObject> ancestorClass = getAncestorClassWithGivenDescendant(category, clazz);
        if(ancestorClass != null)
        {
            ConfiguredObject ancestor = getAncestor(ancestorClass, category, object);
            if(ancestor != null)
            {
                return getAllDescendants(ancestor, ancestorClass, clazz);
            }
        }
        return null;
    }

    private static <X extends ConfiguredObject<X>> Collection<X> getAllDescendants(final ConfiguredObject ancestor,
                                                                                   final Class<? extends ConfiguredObject> ancestorClass,
                                                                                   final Class<X> clazz)
    {
        Set<X> descendants = new HashSet<X>();
        for(Class<? extends ConfiguredObject> childClass : Model.getInstance().getChildTypes(ancestorClass))
        {
            Collection<? extends ConfiguredObject> children = ancestor.getChildren(childClass);
            if(childClass == clazz)
            {

                if(children != null)
                {
                    descendants.addAll((Collection<X>)children);
                }
            }
            else
            {
                if(children != null)
                {
                    for(ConfiguredObject child : children)
                    {
                        descendants.addAll(getAllDescendants(child, childClass, clazz));
                    }
                }
            }
        }
        return descendants;
    }

    private static ConfiguredObject getAncestor(final Class<? extends ConfiguredObject> ancestorClass,
                                                final Class<? extends ConfiguredObject> category,
                                                final ConfiguredObject<?> object)
    {
        if(ancestorClass.isInstance(object))
        {
            return object;
        }
        else
        {
            for(Class<? extends ConfiguredObject> parentClass : Model.getInstance().getParentTypes(category))
            {
                ConfiguredObject parent = object.getParent(parentClass);
                if(parent == null)
                {
                    System.err.println(parentClass.getSimpleName());
                }
                ConfiguredObject ancestor = getAncestor(ancestorClass, parentClass, parent);
                if(ancestor != null)
                {
                    return ancestor;
                }
            }
        }
        return null;
    }

    private static Class<? extends ConfiguredObject> getAncestorClassWithGivenDescendant(
            final Class<? extends ConfiguredObject> category,
            final Class<? extends ConfiguredObject> descendantClass)
    {
        Model model = Model.getInstance();
        Collection<Class<? extends ConfiguredObject>> candidateClasses =
                Collections.<Class<? extends ConfiguredObject>>singleton(category);
        while(!candidateClasses.isEmpty())
        {
            for(Class<? extends ConfiguredObject> candidate : candidateClasses)
            {
                if(hasDescendant(candidate, descendantClass))
                {
                    return candidate;
                }
            }
            Set<Class<? extends ConfiguredObject>> previous = new HashSet<Class<? extends ConfiguredObject>>(candidateClasses);
            candidateClasses = new HashSet<Class<? extends ConfiguredObject>>();
            for(Class<? extends ConfiguredObject> prev : previous)
            {
                candidateClasses.addAll(model.getParentTypes(prev));
            }
        }
        return null;
    }

    private static boolean hasDescendant(final Class<? extends ConfiguredObject> candidate,
                                         final Class<? extends ConfiguredObject> descendantClass)
    {
        int oldSize = 0;
        Model model = Model.getInstance();

        Set<Class<? extends ConfiguredObject>> allDescendants = new HashSet<Class<? extends ConfiguredObject>>(model.getChildTypes(
                candidate));
        while(allDescendants.size() > oldSize)
        {
            oldSize = allDescendants.size();
            Set<Class<? extends ConfiguredObject>> prev = new HashSet<Class<? extends ConfiguredObject>>(allDescendants);
            for(Class<? extends ConfiguredObject> clazz : prev)
            {
                allDescendants.addAll(model.getChildTypes(clazz));
            }
            if(allDescendants.contains(descendantClass))
            {
                break;
            }
        }
        return allDescendants.contains(descendantClass);
    }


    private class AttributeGettingHandler implements InvocationHandler
    {
        private Map<String,Object> _attributes;

        AttributeGettingHandler(final Map<String, Object> modifiedAttributes)
        {
            Map<String,Object> combinedAttributes = new HashMap<String, Object>(getActualAttributes());
            combinedAttributes.putAll(modifiedAttributes);
            _attributes = combinedAttributes;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable
        {

            if(method.isAnnotationPresent(ManagedAttribute.class))
            {
                ConfiguredObjectAttribute attribute = getAttributeFromMethod(method);
                return getValue(attribute);
            }
            else if(method.getName().equals("getAttribute") && args != null && args.length == 1 && args[0] instanceof String)
            {
                ConfiguredObjectAttribute attribute = _attributeTypes.get((String)args[0]);
                if(attribute != null)
                {
                    return getValue(attribute);
                }
                else
                {
                    return null;
                }
            }
            throw new UnsupportedOperationException("This class is only intended for value validation, and only getters on managed attributes are permitted.");
        }

        protected Object getValue(final ConfiguredObjectAttribute attribute)
        {
            ManagedAttribute annotation = attribute.getAnnotation();
            if(annotation.automate())
            {
                Object value = _attributes.get(attribute.getName());
                return attribute.convert(value == null && !"".equals(annotation.defaultValue()) ? annotation.defaultValue() : value , AbstractConfiguredObject.this);
            }
            else
            {
                return _attributes.get(attribute.getName());
            }
        }

        private ConfiguredObjectAttribute getAttributeFromMethod(final Method method)
        {
            for(ConfiguredObjectAttribute attribute : _attributeTypes.values())
            {
                if(attribute.getGetter().getName().equals(method.getName())
                   && !Modifier.isStatic(method.getModifiers()))
                {
                    return attribute;
                }
            }
            throw new ServerScopedRuntimeException("Unable to find attribute definition for method " + method.getName());
        }
    }

    protected final static class DuplicateIdException extends RuntimeException
    {
        public DuplicateIdException(final ConfiguredObject<?> child)
        {
            super("Child of type " + child.getClass().getSimpleName() + " already exists with id of " + child.getId());
        }
    }

    protected final static class DuplicateNameException extends RuntimeException
    {
        private final String _name;
        public DuplicateNameException(final ConfiguredObject<?> child)
        {
            super("Child of type " + child.getClass().getSimpleName() + " already exists with name of " + child.getName());
            _name = child.getName();
        }

        public String getName()
        {
            return _name;
        }
    }
}
