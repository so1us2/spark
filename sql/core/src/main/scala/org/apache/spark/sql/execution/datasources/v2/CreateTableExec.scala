/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalog.v2.{Identifier, StagingTableCatalog, TableCatalog}
import org.apache.spark.sql.catalog.v2.expressions.Transform
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.execution.LeafExecNode
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

case class CreateTableExec(
    catalog: TableCatalog,
    identifier: Identifier,
    tableSchema: StructType,
    partitioning: Seq[Transform],
    tableProperties: Map[String, String],
    ignoreIfExists: Boolean) extends LeafExecNode {
  import org.apache.spark.sql.catalog.v2.CatalogV2Implicits._

  override protected def doExecute(): RDD[InternalRow] = {
    if (!catalog.tableExists(identifier)) {
      try {
        catalog.createTable(identifier, tableSchema, partitioning.toArray, tableProperties.asJava)
      } catch {
        case _: TableAlreadyExistsException if ignoreIfExists =>
          logWarning(s"Table ${identifier.quoted} was created concurrently. Ignoring.")
      }
    } else if (!ignoreIfExists) {
      throw new TableAlreadyExistsException(identifier)
    }

    sqlContext.sparkContext.parallelize(Seq.empty, 1)
  }

  override def output: Seq[Attribute] = Seq.empty
}

case class CreateTableStagingExec(
    catalog: StagingTableCatalog,
    identifier: Identifier,
    tableSchema: StructType,
    partitioning: Seq[Transform],
    tableProperties: Map[String, String],
    ignoreIfExists: Boolean) extends LeafExecNode {
  import org.apache.spark.sql.catalog.v2.CatalogV2Implicits._

  override protected def doExecute(): RDD[InternalRow] = {
    if (!catalog.tableExists(identifier)) {
      try {
        val stagedCreate = catalog.stageCreate(
          identifier, tableSchema, partitioning.toArray, tableProperties.asJava)
        Utils.tryWithSafeFinallyAndFailureCallbacks({
          stagedCreate.commitStagedChanges()
        })(catchBlock = {
          stagedCreate.abortStagedChanges()
        })
      } catch {
        case _: TableAlreadyExistsException if ignoreIfExists =>
          logWarning(s"Table ${identifier.quoted} was created concurrently. Ignoring.")
      }
    } else if (!ignoreIfExists) {
      throw new TableAlreadyExistsException(identifier)
    }

    sqlContext.sparkContext.parallelize(Seq.empty, 1)
  }

  override def output: Seq[Attribute] = Seq.empty
}
