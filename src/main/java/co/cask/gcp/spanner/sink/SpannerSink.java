/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.gcp.spanner.sink;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.batch.Output;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.batch.BatchRuntimeContext;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.BatchSinkContext;
import co.cask.gcp.spanner.common.SpannerUtil;
import co.cask.hydrator.common.LineageRecorder;
import co.cask.hydrator.common.ReferenceBatchSink;
import co.cask.hydrator.common.batch.sink.SinkOutputFormatProvider;
import com.google.cloud.spanner.Database;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Operation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.spanner.admin.database.v1.CreateDatabaseMetadata;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


/**
 * This class extends {@link ReferenceBatchSink} to write to Google Cloud Spanner.
 *
 * Uses a {@link SpannerOutputFormat} and {@link SpannerOutputFormat.SpannerRecordWriter} to configure
 * and write to spanner. The <code>prepareRun</code> method configures the job by extracting
 * the user provided configuration and preparing it to be passed to {@link SpannerOutputFormat}.
 *
 */
@Plugin(type = "batchsink")
@Name(SpannerSink.NAME)
@Description("Batch sink to write to Cloud Spanner. Cloud Spanner is a fully managed, mission-critical, " +
  "relational database service that offers transactional consistency at global scale, schemas, " +
  "SQL (ANSI 2011 with extensions), and automatic, synchronous replication for high availability.")
public final class SpannerSink extends BatchSink<StructuredRecord, NullWritable, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(SpannerSink.class);
  public static final String NAME = "Spanner";
  private final SpannerSinkConfig config;

  public SpannerSink(SpannerSinkConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    super.configurePipeline(pipelineConfigurer);
    config.validate();
  }

  @Override
  public void prepareRun(BatchSinkContext context) {
    config.validate();

    try {
      Spanner spanner = SpannerUtil.getSpannerService(config.getServiceAccountFilePath(), config.getProject());
      DatabaseId db = DatabaseId.of(config.getProject(), config.instance, config.database);
      DatabaseClient dbClient = spanner.getDatabaseClient(db);
      DatabaseAdminClient dbAdminClient = spanner.getDatabaseAdminClient();
      // create database
      createIfNotPresent(dbAdminClient);
      // create table
      String createStmt = SpannerUtil.convertSchemaToCreateStatement(config.table, config.getSchema());
      LOG.debug("Creating table with create statement: {}", createStmt);
      Operation<Void, UpdateDatabaseDdlMetadata> op = dbAdminClient.updateDatabaseDdl(
        config.instance, config.database, Collections.singletonList(createStmt), null);
      op.waitFor().getResult();

        ResultSet resultSet = dbClient
          .singleUse()
          .executeQuery(Statement.of("SELECT\n" +
                                       "    t.table_name\n" +
                                       "FROM\n" +
                                       "    information_schema.tables AS t\n" +
                                       "WHERE\n" +
                                       "    t.table_catalog = '' AND t.table_schema = '' AND\n" +
                                       "    t.table_name = '" + config.table + "'"));
        int count = 0;
        while (resultSet.next()) {
          count++;
          LOG.info("Got result table");
        }
        LOG.info("## Result set size: {}", count);
      spanner.close();
    } catch (IOException e) {
      throw new RuntimeException("Exception while trying to get Spanner service. ", e);
    }

    Configuration configuration = new Configuration();

    LineageRecorder lineageRecorder = new LineageRecorder(context, config.referenceName);
    lineageRecorder.createExternalDataset(config.getSchema());

    SpannerOutputFormat.configure(configuration, config);
    context.addOutput(Output.of(config.referenceName,
                                new SinkOutputFormatProvider(SpannerOutputFormat.class, configuration)));

    List<Schema.Field> fields = config.getSchema().getFields();
    if (fields != null && !fields.isEmpty()) {
        // Record the field level WriteOperation
        lineageRecorder.recordWrite("Write", "Wrote to Spanner table.",
                                    fields.stream().map(Schema.Field::getName).collect(Collectors.toList()));
    }
  }

  private boolean createIfNotPresent(DatabaseAdminClient dbAdminClient) {
    try {
      // check if database exists by getting it. If database does not exist SpannerException will be thrown. Spanner
      // java apis does not have exists method to check if database is present or not. That's why we are relying on
      // SpannerException's ErrorCode.NOT_FOUND status code.
      dbAdminClient.getDatabase(config.instance, config.database);
    } catch (SpannerException e) {
      if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
        LOG.info("Database not found. Creating database {}.", config.database);
        // Create database
        Operation<Database, CreateDatabaseMetadata> op =
          dbAdminClient.createDatabase(config.instance, config.database, Collections.emptyList());
        Database db = op.waitFor().getResult();
        return true;
      } else {
        throw e;
      }
    }
    return false;
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    config.validate();
  }

  @Override
  public void transform(StructuredRecord input, Emitter<KeyValue<NullWritable, StructuredRecord>> emitter) {
    emitter.emit(new KeyValue<>(null, input));
  }

  @Override
  public void destroy() {
    super.destroy();
  }
}
