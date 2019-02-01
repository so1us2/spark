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

package org.apache.spark.sql.execution.exchange

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.MapOutputStatistics
import org.apache.spark.internal.Logging

/**
 * A coordinator used to determines how we shuffle data between stages generated by Spark SQL.
 * Right now, the work of this coordinator is to determine the number of post-shuffle partitions
 * for a stage that needs to fetch shuffle data from one or multiple stages.
 *
 * A coordinator is constructed with two parameters, `targetPostShuffleInputSize`,
 * and `minNumPostShufflePartitions`.
 *  - `targetPostShuffleInputSize` is the targeted size of a post-shuffle partition's
 *    input data size. With this parameter, we can estimate the number of post-shuffle partitions.
 *    This parameter is configured through
 *    `spark.sql.adaptive.shuffle.targetPostShuffleInputSize`.
 *  - `minNumPostShufflePartitions` is used to make sure that there are at least
 *    `minNumPostShufflePartitions` post-shuffle partitions.
 *
 * The strategy used to determine the number of post-shuffle partitions is described as follows.
 * To determine the number of post-shuffle partitions, we have a target input size for a
 * post-shuffle partition. Once we have size statistics of all pre-shuffle partitions, we will do
 * a pass of those statistics and pack pre-shuffle partitions with continuous indices to a single
 * post-shuffle partition until adding another pre-shuffle partition would cause the size of a
 * post-shuffle partition to be greater than the target size.
 *
 * For example, we have two stages with the following pre-shuffle partition size statistics:
 * stage 1: [100 MB, 20 MB, 100 MB, 10MB, 30 MB]
 * stage 2: [10 MB,  10 MB, 70 MB,  5 MB, 5 MB]
 * assuming the target input size is 128 MB, we will have four post-shuffle partitions,
 * which are:
 *  - post-shuffle partition 0: pre-shuffle partition 0 (size 110 MB)
 *  - post-shuffle partition 1: pre-shuffle partition 1 (size 30 MB)
 *  - post-shuffle partition 2: pre-shuffle partition 2 (size 170 MB)
 *  - post-shuffle partition 3: pre-shuffle partition 3 and 4 (size 50 MB)
 */
class ExchangeCoordinator(
    advisoryTargetPostShuffleInputSize: Long,
    minNumPostShufflePartitions: Int = 1)
  extends Logging {

  /**
   * Estimates partition start indices for post-shuffle partitions based on
   * mapOutputStatistics provided by all pre-shuffle stages.
   */
  def estimatePartitionStartIndices(
      mapOutputStatistics: Array[MapOutputStatistics]): Array[Int] = {

    // If minNumPostShufflePartitions is defined, it is possible that we need to use a
    // value less than advisoryTargetPostShuffleInputSize as the target input size of
    // a post shuffle task.
    val totalPostShuffleInputSize = mapOutputStatistics.map(_.bytesByPartitionId.sum).sum
    // The max at here is to make sure that when we have an empty table, we
    // only have a single post-shuffle partition.
    // There is no particular reason that we pick 16. We just need a number to
    // prevent maxPostShuffleInputSize from being set to 0.
    val maxPostShuffleInputSize = math.max(
      math.ceil(totalPostShuffleInputSize / minNumPostShufflePartitions.toDouble).toLong, 16)
    val targetPostShuffleInputSize =
      math.min(maxPostShuffleInputSize, advisoryTargetPostShuffleInputSize)

    logInfo(
      s"advisoryTargetPostShuffleInputSize: $advisoryTargetPostShuffleInputSize, " +
      s"targetPostShuffleInputSize $targetPostShuffleInputSize.")

    // Make sure we do get the same number of pre-shuffle partitions for those stages.
    val distinctNumPreShufflePartitions =
      mapOutputStatistics.map(stats => stats.bytesByPartitionId.length).distinct
    // The reason that we are expecting a single value of the number of pre-shuffle partitions
    // is that when we add Exchanges, we set the number of pre-shuffle partitions
    // (i.e. map output partitions) using a static setting, which is the value of
    // spark.sql.shuffle.partitions. Even if two input RDDs are having different
    // number of partitions, they will have the same number of pre-shuffle partitions
    // (i.e. map output partitions).
    assert(
      distinctNumPreShufflePartitions.length == 1,
      "There should be only one distinct value of the number pre-shuffle partitions " +
        "among registered Exchange operator.")
    val numPreShufflePartitions = distinctNumPreShufflePartitions.head

    val partitionStartIndices = ArrayBuffer[Int]()
    // The first element of partitionStartIndices is always 0.
    partitionStartIndices += 0

    var postShuffleInputSize = 0L

    var i = 0
    while (i < numPreShufflePartitions) {
      // We calculate the total size of ith pre-shuffle partitions from all pre-shuffle stages.
      // Then, we add the total size to postShuffleInputSize.
      var nextShuffleInputSize = 0L
      var j = 0
      while (j < mapOutputStatistics.length) {
        nextShuffleInputSize += mapOutputStatistics(j).bytesByPartitionId(i)
        j += 1
      }

      // If including the nextShuffleInputSize would exceed the target partition size, then start a
      // new partition.
      if (i > 0 && postShuffleInputSize + nextShuffleInputSize > targetPostShuffleInputSize) {
        partitionStartIndices += i
        // reset postShuffleInputSize.
        postShuffleInputSize = nextShuffleInputSize
      } else postShuffleInputSize += nextShuffleInputSize

      i += 1
    }

    partitionStartIndices.toArray
  }

  override def toString: String = {
    s"coordinator[target post-shuffle partition size: $advisoryTargetPostShuffleInputSize]"
  }
}
