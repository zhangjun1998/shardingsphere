/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingproxypg.runtime.schema;

import com.google.common.eventbus.Subscribe;
import lombok.Getter;
import org.apache.shardingsphere.api.config.rule.MasterSlaveRuleConfiguration;
import org.apache.shardingsphere.api.config.rule.ShardingRuleConfiguration;
import org.apache.shardingsphere.core.constant.DatabaseType;
import org.apache.shardingsphere.core.constant.properties.ShardingPropertiesConstant;
import org.apache.shardingsphere.core.metadata.ShardingMetaData;
import org.apache.shardingsphere.core.rule.MasterSlaveRule;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.orchestration.internal.registry.config.event.MasterSlaveRuleChangedEvent;
import org.apache.shardingsphere.orchestration.internal.registry.state.event.DisabledStateChangedEvent;
import org.apache.shardingsphere.orchestration.internal.registry.state.schema.OrchestrationShardingSchema;
import org.apache.shardingsphere.orchestration.internal.rule.OrchestrationMasterSlaveRule;
import org.apache.shardingsphere.shardingproxypg.backend.BackendExecutorContext;
import org.apache.shardingsphere.shardingproxypg.config.yaml.YamlDataSourceParameter;
import org.apache.shardingsphere.shardingproxypg.runtime.GlobalRegistry;
import org.apache.shardingsphere.shardingproxypg.runtime.metadata.ProxyTableMetaDataConnectionManager;

import java.util.Map;

/**
 * Master-slave schema.
 *
 * @author panjuan
 */
@Getter
public final class MasterSlaveSchema extends LogicSchema {
    
    private MasterSlaveRule masterSlaveRule;
    
    private final ShardingMetaData metaData;
    
    public MasterSlaveSchema(final String name, final Map<String, YamlDataSourceParameter> dataSources, final MasterSlaveRuleConfiguration masterSlaveRuleConfig, final boolean isUsingRegistry) {
        super(name, dataSources);
        masterSlaveRule = createMasterSlaveRule(masterSlaveRuleConfig, isUsingRegistry);
        metaData = createShardingMetaData();
    }
    
    private MasterSlaveRule createMasterSlaveRule(final MasterSlaveRuleConfiguration masterSlaveRuleConfig, final boolean isUsingRegistry) {
        return isUsingRegistry ? new OrchestrationMasterSlaveRule(masterSlaveRuleConfig) : new MasterSlaveRule(masterSlaveRuleConfig);
    }
    
    private ShardingMetaData createShardingMetaData() {
        return new ShardingMetaData(getDataSourceURLs(getDataSources()), new ShardingRule(new ShardingRuleConfiguration(), getDataSources().keySet()), 
                DatabaseType.PostgreSQL, BackendExecutorContext.getInstance().getExecuteEngine(), new ProxyTableMetaDataConnectionManager(getBackendDataSource()),
                GlobalRegistry.getInstance().getShardingProperties().<Integer>getValue(ShardingPropertiesConstant.MAX_CONNECTIONS_SIZE_PER_QUERY), 
                GlobalRegistry.getInstance().getShardingProperties().<Boolean>getValue(ShardingPropertiesConstant.CHECK_TABLE_METADATA_ENABLED));
    }
    
    /**
     * Renew master-slave rule.
     *
     * @param masterSlaveRuleChangedEvent master-slave rule changed event.
     */
    @Subscribe
    public synchronized void renew(final MasterSlaveRuleChangedEvent masterSlaveRuleChangedEvent) {
        if (getName().equals(masterSlaveRuleChangedEvent.getShardingSchemaName())) {
            masterSlaveRule = new OrchestrationMasterSlaveRule(masterSlaveRuleChangedEvent.getMasterSlaveRuleConfiguration());
        }
    }
    
    /**
     * Renew disabled data source names.
     *
     * @param disabledStateChangedEvent disabled state changed event
     */
    @Subscribe
    public synchronized void renew(final DisabledStateChangedEvent disabledStateChangedEvent) {
        OrchestrationShardingSchema shardingSchema = disabledStateChangedEvent.getShardingSchema();
        if (getName().equals(shardingSchema.getSchemaName())) {
            ((OrchestrationMasterSlaveRule) masterSlaveRule).updateDisabledDataSourceNames(shardingSchema.getDataSourceName(), disabledStateChangedEvent.isDisabled());
        }
    }
}
