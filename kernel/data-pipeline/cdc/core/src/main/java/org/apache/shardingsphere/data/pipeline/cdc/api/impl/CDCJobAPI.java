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

package org.apache.shardingsphere.data.pipeline.cdc.api.impl;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.shardingsphere.data.pipeline.api.check.consistency.PipelineDataConsistencyChecker;
import org.apache.shardingsphere.data.pipeline.api.config.ImporterConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.TableNameSchemaNameMapping;
import org.apache.shardingsphere.data.pipeline.api.config.ingest.DumperConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.job.PipelineJobConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.job.yaml.YamlPipelineJobConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.process.PipelineProcessConfiguration;
import org.apache.shardingsphere.data.pipeline.api.datanode.JobDataNodeEntry;
import org.apache.shardingsphere.data.pipeline.api.datanode.JobDataNodeLine;
import org.apache.shardingsphere.data.pipeline.api.datasource.PipelineDataSourceManager;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.PipelineDataSourceConfiguration;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.PipelineDataSourceConfigurationFactory;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.impl.ShardingSpherePipelineDataSourceConfiguration;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.impl.StandardPipelineDataSourceConfiguration;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.yaml.YamlPipelineDataSourceConfigurationSwapper;
import org.apache.shardingsphere.data.pipeline.api.job.JobStatus;
import org.apache.shardingsphere.data.pipeline.api.job.PipelineJobId;
import org.apache.shardingsphere.data.pipeline.api.job.progress.InventoryIncrementalJobItemProgress;
import org.apache.shardingsphere.data.pipeline.api.job.progress.JobItemIncrementalTasksProgress;
import org.apache.shardingsphere.data.pipeline.api.metadata.ActualTableName;
import org.apache.shardingsphere.data.pipeline.api.metadata.LogicTableName;
import org.apache.shardingsphere.data.pipeline.api.pojo.PipelineJobInfo;
import org.apache.shardingsphere.data.pipeline.api.task.progress.IncrementalTaskProgress;
import org.apache.shardingsphere.data.pipeline.cdc.api.job.type.CDCJobType;
import org.apache.shardingsphere.data.pipeline.cdc.api.pojo.StreamDataParameter;
import org.apache.shardingsphere.data.pipeline.cdc.config.job.CDCJobConfiguration;
import org.apache.shardingsphere.data.pipeline.cdc.config.task.CDCTaskConfiguration;
import org.apache.shardingsphere.data.pipeline.cdc.context.CDCProcessContext;
import org.apache.shardingsphere.data.pipeline.cdc.core.job.CDCJob;
import org.apache.shardingsphere.data.pipeline.cdc.core.job.CDCJobId;
import org.apache.shardingsphere.data.pipeline.cdc.yaml.job.YamlCDCJobConfiguration;
import org.apache.shardingsphere.data.pipeline.cdc.yaml.job.YamlCDCJobConfigurationSwapper;
import org.apache.shardingsphere.data.pipeline.core.api.GovernanceRepositoryAPI;
import org.apache.shardingsphere.data.pipeline.core.api.PipelineAPIFactory;
import org.apache.shardingsphere.data.pipeline.core.api.impl.AbstractInventoryIncrementalJobAPIImpl;
import org.apache.shardingsphere.data.pipeline.core.check.consistency.ConsistencyCheckJobItemProgressContext;
import org.apache.shardingsphere.data.pipeline.core.context.InventoryIncrementalProcessContext;
import org.apache.shardingsphere.data.pipeline.core.context.PipelineContext;
import org.apache.shardingsphere.data.pipeline.core.datasource.DefaultPipelineDataSourceManager;
import org.apache.shardingsphere.data.pipeline.core.exception.job.PipelineJobCreationWithInvalidShardingCountException;
import org.apache.shardingsphere.data.pipeline.core.exception.job.PrepareJobWithGetBinlogPositionException;
import org.apache.shardingsphere.data.pipeline.core.metadata.node.PipelineMetaDataNode;
import org.apache.shardingsphere.data.pipeline.core.prepare.PipelineJobPreparerUtils;
import org.apache.shardingsphere.data.pipeline.core.sharding.ShardingColumnsExtractor;
import org.apache.shardingsphere.data.pipeline.core.util.JobDataNodeLineConvertUtil;
import org.apache.shardingsphere.data.pipeline.spi.job.JobType;
import org.apache.shardingsphere.data.pipeline.spi.ratelimit.JobRateLimitAlgorithm;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.infra.datasource.props.DataSourcePropertiesCreator;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.util.exception.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.util.yaml.YamlEngine;
import org.apache.shardingsphere.infra.yaml.config.pojo.YamlRootConfiguration;
import org.apache.shardingsphere.infra.yaml.config.pojo.rule.YamlRuleConfiguration;
import org.apache.shardingsphere.infra.yaml.config.swapper.resource.YamlDataSourceConfigurationSwapper;
import org.apache.shardingsphere.infra.yaml.config.swapper.rule.YamlRuleConfigurationSwapperEngine;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CDC job API.
 */
@Slf4j
public final class CDCJobAPI extends AbstractInventoryIncrementalJobAPIImpl {
    
    private final YamlDataSourceConfigurationSwapper dataSourceConfigSwapper = new YamlDataSourceConfigurationSwapper();
    
    private final YamlRuleConfigurationSwapperEngine ruleConfigSwapperEngine = new YamlRuleConfigurationSwapperEngine();
    
    private final YamlPipelineDataSourceConfigurationSwapper pipelineDataSourceConfigSwapper = new YamlPipelineDataSourceConfigurationSwapper();
    
    /**
     * Create CDC job config.
     *
     * @param param create CDC job param
     * @return job id
     */
    public String createJob(final StreamDataParameter param) {
        YamlCDCJobConfiguration yamlJobConfig = new YamlCDCJobConfiguration();
        yamlJobConfig.setDatabase(param.getDatabase());
        yamlJobConfig.setSchemaTableNames(param.getSchemaTableNames());
        yamlJobConfig.setFull(param.isFull());
        yamlJobConfig.setDecodeWithTX(param.isDecodeWithTX());
        ShardingSphereDatabase database = PipelineContext.getContextManager().getMetaDataContexts().getMetaData().getDatabase(param.getDatabase());
        yamlJobConfig.setDataSourceConfiguration(pipelineDataSourceConfigSwapper.swapToYamlConfiguration(getDataSourceConfiguration(database)));
        List<JobDataNodeLine> jobDataNodeLines = JobDataNodeLineConvertUtil.convertDataNodesToLines(param.getDataNodesMap());
        yamlJobConfig.setJobShardingDataNodes(jobDataNodeLines.stream().map(JobDataNodeLine::marshal).collect(Collectors.toList()));
        JobDataNodeLine tableFirstDataNodes = new JobDataNodeLine(param.getDataNodesMap().entrySet().stream().map(each -> new JobDataNodeEntry(each.getKey(), each.getValue().subList(0, 1)))
                .collect(Collectors.toList()));
        yamlJobConfig.setTablesFirstDataNodes(tableFirstDataNodes.marshal());
        extendYamlJobConfiguration(yamlJobConfig);
        CDCJobConfiguration jobConfig = new YamlCDCJobConfigurationSwapper().swapToObject(yamlJobConfig);
        ShardingSpherePreconditions.checkState(0 != jobConfig.getJobShardingCount(), () -> new PipelineJobCreationWithInvalidShardingCountException(jobConfig.getJobId()));
        GovernanceRepositoryAPI repositoryAPI = PipelineAPIFactory.getGovernanceRepositoryAPI();
        String jobConfigKey = PipelineMetaDataNode.getJobConfigPath(jobConfig.getJobId());
        if (repositoryAPI.isExisted(jobConfigKey)) {
            log.warn("cdc job already exists in registry center, ignore, jobConfigKey={}", jobConfigKey);
            return jobConfig.getJobId();
        }
        repositoryAPI.persist(PipelineMetaDataNode.getJobRootPath(jobConfig.getJobId()), getJobClassName());
        JobConfigurationPOJO jobConfigPOJO = convertJobConfiguration(jobConfig);
        jobConfigPOJO.setDisabled(true);
        repositoryAPI.persist(jobConfigKey, YamlEngine.marshal(jobConfigPOJO));
        if (!param.isFull()) {
            initIncrementalPosition(jobConfig);
        }
        return jobConfig.getJobId();
    }
    
    private void initIncrementalPosition(final CDCJobConfiguration jobConfig) {
        PipelineDataSourceManager dataSourceManager = new DefaultPipelineDataSourceManager();
        String jobId = jobConfig.getJobId();
        try {
            for (int i = 0; i < jobConfig.getJobShardingCount(); i++) {
                if (getJobItemProgress(jobId, i).isPresent()) {
                    continue;
                }
                TableNameSchemaNameMapping tableNameSchemaNameMapping = getTableNameSchemaNameMapping(jobConfig.getSchemaTableNames());
                DumperConfiguration dumperConfig = buildDumperConfiguration(jobConfig, i, tableNameSchemaNameMapping);
                InventoryIncrementalJobItemProgress jobItemProgress = new InventoryIncrementalJobItemProgress();
                jobItemProgress.setSourceDatabaseType(jobConfig.getSourceDatabaseType());
                jobItemProgress.setDataSourceName(dumperConfig.getDataSourceName());
                IncrementalTaskProgress incrementalTaskProgress = new IncrementalTaskProgress();
                incrementalTaskProgress.setPosition(PipelineJobPreparerUtils.getIncrementalPosition(null, dumperConfig, dataSourceManager));
                jobItemProgress.setIncremental(new JobItemIncrementalTasksProgress(incrementalTaskProgress));
                jobItemProgress.setStatus(JobStatus.PREPARE_SUCCESS);
                PipelineAPIFactory.getGovernanceRepositoryAPI().persistJobItemProgress(jobId, i, YamlEngine.marshal(getJobItemProgressSwapper().swapToYamlConfiguration(jobItemProgress)));
            }
        } catch (final SQLException ex) {
            throw new PrepareJobWithGetBinlogPositionException(jobConfig.getJobId(), ex);
        } finally {
            dataSourceManager.close();
        }
    }
    
    private ShardingSpherePipelineDataSourceConfiguration getDataSourceConfiguration(final ShardingSphereDatabase database) {
        Map<String, Map<String, Object>> dataSourceProps = new HashMap<>();
        for (Entry<String, DataSource> entry : database.getResourceMetaData().getDataSources().entrySet()) {
            dataSourceProps.put(entry.getKey(), dataSourceConfigSwapper.swapToMap(DataSourcePropertiesCreator.create(entry.getValue())));
        }
        YamlRootConfiguration targetRootConfig = new YamlRootConfiguration();
        targetRootConfig.setDatabaseName(database.getName());
        targetRootConfig.setDataSources(dataSourceProps);
        Collection<YamlRuleConfiguration> yamlRuleConfigurations = ruleConfigSwapperEngine.swapToYamlRuleConfigurations(database.getRuleMetaData().getConfigurations());
        targetRootConfig.setRules(yamlRuleConfigurations);
        return new ShardingSpherePipelineDataSourceConfiguration(targetRootConfig);
    }
    
    @Override
    public void extendYamlJobConfiguration(final YamlPipelineJobConfiguration yamlJobConfig) {
        YamlCDCJobConfiguration config = (YamlCDCJobConfiguration) yamlJobConfig;
        if (null == yamlJobConfig.getJobId()) {
            config.setJobId(generateJobId(config));
        }
        if (Strings.isNullOrEmpty(config.getSourceDatabaseType())) {
            PipelineDataSourceConfiguration sourceDataSourceConfig = PipelineDataSourceConfigurationFactory.newInstance(config.getDataSourceConfiguration().getType(),
                    config.getDataSourceConfiguration().getParameter());
            config.setSourceDatabaseType(sourceDataSourceConfig.getDatabaseType().getType());
        }
    }
    
    private String generateJobId(final YamlCDCJobConfiguration config) {
        // TODO generate parameter add sink type
        CDCJobId jobId = new CDCJobId(config.getDatabase(), config.getSchemaTableNames(), config.isFull());
        return marshalJobId(jobId);
    }
    
    @Override
    protected String marshalJobIdLeftPart(final PipelineJobId pipelineJobId) {
        CDCJobId jobId = (CDCJobId) pipelineJobId;
        String text = Joiner.on('|').join(jobId.getDatabaseName(), jobId.getSchemaTableNames(), jobId.isFull());
        return DigestUtils.md5Hex(text.getBytes(StandardCharsets.UTF_8));
    }
    
    @Override
    public CDCTaskConfiguration buildTaskConfiguration(final PipelineJobConfiguration pipelineJobConfig, final int jobShardingItem, final PipelineProcessConfiguration pipelineProcessConfig) {
        CDCJobConfiguration jobConfig = (CDCJobConfiguration) pipelineJobConfig;
        TableNameSchemaNameMapping tableNameSchemaNameMapping = getTableNameSchemaNameMapping(jobConfig.getSchemaTableNames());
        DumperConfiguration dumperConfig = buildDumperConfiguration(jobConfig, jobShardingItem, tableNameSchemaNameMapping);
        ImporterConfiguration importerConfig = buildImporterConfiguration(jobConfig, pipelineProcessConfig, jobConfig.getSchemaTableNames(), tableNameSchemaNameMapping);
        CDCTaskConfiguration result = new CDCTaskConfiguration(dumperConfig, importerConfig);
        log.debug("buildTaskConfiguration, result={}", result);
        return result;
    }
    
    private TableNameSchemaNameMapping getTableNameSchemaNameMapping(final Collection<String> tableNames) {
        Map<LogicTableName, String> tableNameSchemaMap = new LinkedHashMap<>();
        for (String each : tableNames) {
            String[] split = each.split("\\.");
            if (split.length > 1) {
                tableNameSchemaMap.put(new LogicTableName(split[1]), split[0]);
            }
        }
        return new TableNameSchemaNameMapping(tableNameSchemaMap);
    }
    
    private static DumperConfiguration buildDumperConfiguration(final CDCJobConfiguration jobConfig, final int jobShardingItem, final TableNameSchemaNameMapping tableNameSchemaNameMapping) {
        JobDataNodeLine dataNodeLine = jobConfig.getJobShardingDataNodes().get(jobShardingItem);
        Map<ActualTableName, LogicTableName> tableNameMap = new LinkedHashMap<>();
        dataNodeLine.getEntries().forEach(each -> each.getDataNodes().forEach(node -> tableNameMap.put(new ActualTableName(node.getTableName()), new LogicTableName(each.getLogicTableName()))));
        String dataSourceName = dataNodeLine.getEntries().iterator().next().getDataNodes().iterator().next().getDataSourceName();
        StandardPipelineDataSourceConfiguration actualDataSourceConfig = jobConfig.getDataSourceConfig().getActualDataSourceConfiguration(dataSourceName);
        DumperConfiguration result = new DumperConfiguration();
        result.setJobId(jobConfig.getJobId());
        result.setDataSourceName(dataSourceName);
        result.setDataSourceConfig(actualDataSourceConfig);
        result.setTableNameMap(tableNameMap);
        result.setTableNameSchemaNameMapping(tableNameSchemaNameMapping);
        result.setDecodeWithTX(jobConfig.isDecodeWithTX());
        return result;
    }
    
    private ImporterConfiguration buildImporterConfiguration(final CDCJobConfiguration jobConfig, final PipelineProcessConfiguration pipelineProcessConfig, final Collection<String> schemaTableNames,
                                                             final TableNameSchemaNameMapping tableNameSchemaNameMapping) {
        PipelineDataSourceConfiguration dataSourceConfig = PipelineDataSourceConfigurationFactory.newInstance(jobConfig.getDataSourceConfig().getType(),
                jobConfig.getDataSourceConfig().getParameter());
        CDCProcessContext processContext = new CDCProcessContext(jobConfig.getJobId(), pipelineProcessConfig);
        JobRateLimitAlgorithm writeRateLimitAlgorithm = processContext.getWriteRateLimitAlgorithm();
        int batchSize = pipelineProcessConfig.getWrite().getBatchSize();
        Map<LogicTableName, Set<String>> shardingColumnsMap = new ShardingColumnsExtractor()
                .getShardingColumnsMap(jobConfig.getDataSourceConfig().getRootConfig().getRules(), schemaTableNames.stream().map(LogicTableName::new).collect(Collectors.toSet()));
        return new ImporterConfiguration(dataSourceConfig, shardingColumnsMap, tableNameSchemaNameMapping, batchSize, writeRateLimitAlgorithm, 0, 1);
    }
    
    @Override
    public CDCProcessContext buildPipelineProcessContext(final PipelineJobConfiguration pipelineJobConfig) {
        return new CDCProcessContext(pipelineJobConfig.getJobId(), showProcessConfiguration());
    }
    
    @Override
    public PipelineJobConfiguration getJobConfiguration(final String jobId) {
        return getJobConfiguration(getElasticJobConfigPOJO(jobId));
    }
    
    @Override
    protected PipelineJobConfiguration getJobConfiguration(final JobConfigurationPOJO jobConfigPOJO) {
        return new YamlCDCJobConfigurationSwapper().swapToObject(jobConfigPOJO.getJobParameter());
    }
    
    @Override
    protected YamlPipelineJobConfiguration swapToYamlJobConfiguration(final PipelineJobConfiguration jobConfig) {
        return new YamlCDCJobConfigurationSwapper().swapToYamlConfiguration((CDCJobConfiguration) jobConfig);
    }
    
    @Override
    protected PipelineJobInfo getJobInfo(final String jobId) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public void rollback(final String jobId) throws SQLException {
        stop(jobId);
        dropJob(jobId);
    }
    
    @Override
    public void commit(final String jobId) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    protected PipelineDataConsistencyChecker buildPipelineDataConsistencyChecker(final PipelineJobConfiguration pipelineJobConfig, final InventoryIncrementalProcessContext processContext,
                                                                                 final ConsistencyCheckJobItemProgressContext progressContext) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    protected String getTargetDatabaseType(final PipelineJobConfiguration pipelineJobConfig) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    protected String getJobClassName() {
        return CDCJob.class.getName();
    }
    
    @Override
    public JobType getJobType() {
        return new CDCJobType();
    }
}
