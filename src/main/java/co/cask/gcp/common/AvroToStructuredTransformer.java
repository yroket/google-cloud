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

package co.cask.gcp.common;

import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.format.UnexpectedFormatException;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.hydrator.common.RecordConverter;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Create StructuredRecords from GenericRecords
 */
public class AvroToStructuredTransformer extends RecordConverter<GenericRecord, StructuredRecord> {
  @Override
  public StructuredRecord transform(GenericRecord genericRecord, Schema structuredSchema) throws IOException {
    StructuredRecord.Builder builder = StructuredRecord.builder(structuredSchema);
    for (Schema.Field field : structuredSchema.getFields()) {
      String fieldName = field.getName();
      Object value = convertField(genericRecord.get(fieldName), field.getSchema());
      builder.set(fieldName, value);
    }
    return builder.build();
  }

  @Override
  @Nullable
  protected Object convertField(Object field, Schema fieldSchema) throws IOException {
    if (field == null) {
      return null;
    }

    // Union schema expected to be nullable schema. Underlying non-nullable type should always be simple type
    fieldSchema = fieldSchema.isNullable() ? fieldSchema.getNonNullable() : fieldSchema;
    Schema.Type fieldType = fieldSchema.getType();

    // BigQuery Source only supports simple types, so throw an exception if type is any other than simple type
    if (!fieldType.isSimpleType()) {
      throw new UnexpectedFormatException("Field type " + fieldType + " is not supported.");
    }

    Schema.LogicalType logicalType = fieldSchema.getLogicalType();
    try {
      if (logicalType != null) {
        switch (logicalType) {
          case DATE:
            // date will be in yyyy-mm-dd format
            return Math.toIntExact(LocalDate.parse(field.toString()).toEpochDay());
          case TIME_MILLIS:
            // time will be in hh:mm:ss format
            return Math.toIntExact(TimeUnit.NANOSECONDS.toMillis(LocalTime.parse(field.toString()).toNanoOfDay()));
          case TIME_MICROS:
            // time will be in hh:mm:ss format
            return TimeUnit.NANOSECONDS.toMicros(LocalTime.parse(field.toString()).toNanoOfDay());
          case TIMESTAMP_MILLIS:
          case TIMESTAMP_MICROS:
            return field;
          default:
            throw new UnexpectedFormatException("Field type " + fieldType + " is not supported.");
        }
      }
    } catch (ArithmeticException e) {
      throw new IOException("Field type %s has value that is too large." + fieldType);
    }

    // handle only simple types.
    return super.convertField(field, fieldSchema);
  }
}
