// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.kudu.backup

import java.nio.file.Files
import java.util

import com.google.common.base.Objects
import org.apache.commons.io.FileUtils
import org.apache.kudu.ColumnSchema.{ColumnSchemaBuilder, CompressionAlgorithm, Encoding}
import org.apache.kudu.client.PartitionSchema.HashBucketSchema
import org.apache.kudu.client.{CreateTableOptions, KuduTable, PartialRow, PartitionSchema, TestUtils}
import org.apache.kudu.{ColumnSchema, Schema, Type}
import org.apache.kudu.spark.kudu._
import org.apache.kudu.util.DecimalUtil
import org.junit.Assert._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, Matchers}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class TestKuduBackup extends FunSuite with TestContext with Matchers {
  val log: Logger = LoggerFactory.getLogger(getClass)

  test("Simple Backup and Restore") {
    insertRows(100) // Insert data into the default test table.

    backupAndRestore(tableName)

    val rdd = kuduContext.kuduRDD(ss.sparkContext, s"$tableName-restore", List("key"))
    assert(rdd.collect.length == 100)

    val tA = kuduClient.openTable(tableName)
    val tB = kuduClient.openTable(s"$tableName-restore")
    assertEquals(tA.getNumReplicas, tB.getNumReplicas)
    assertTrue(schemasMatch(tA.getSchema, tB.getSchema))
    assertTrue(partitionSchemasMatch(tA.getPartitionSchema, tB.getPartitionSchema))
  }

  test("Random Backup and Restore") {
    Random.javaRandomToRandom(TestUtils.getRandom)

    val table = createRandomTable()
    val tableName = table.getName
    loadRandomData(table)

    backupAndRestore(tableName)

    val backupRows = kuduContext.kuduRDD(ss.sparkContext, s"$tableName").collect
    val restoreRows = kuduContext.kuduRDD(ss.sparkContext, s"$tableName-restore").collect
    assertEquals(backupRows.length, restoreRows.length)

    val tA = kuduClient.openTable(tableName)
    val tB = kuduClient.openTable(s"$tableName-restore")
    assertEquals(tA.getNumReplicas, tB.getNumReplicas)
    assertTrue(schemasMatch(tA.getSchema, tB.getSchema))
    assertTrue(partitionSchemasMatch(tA.getPartitionSchema, tB.getPartitionSchema))
  }

  // TODO: Move to a Schema equals/equivalent method.
  def schemasMatch(before: Schema, after: Schema): Boolean = {
    if (before eq after) return true
    if (before.getColumns.size != after.getColumns.size) return false
    (0 until before.getColumns.size).forall { i =>
      columnsMatch(before.getColumnByIndex(i), after.getColumnByIndex(i))
    }
  }

  // TODO: Move to a ColumnSchema equals/equivalent method.
  def columnsMatch(before: ColumnSchema, after: ColumnSchema): Boolean = {
    if (before eq after) return true
    Objects.equal(before.getName, after.getName) &&
      Objects.equal(before.getType, after.getType) &&
      Objects.equal(before.isKey, after.isKey) &&
      Objects.equal(before.isNullable, after.isNullable) &&
      defaultValuesMatch(before.getDefaultValue, after.getDefaultValue) &&
      Objects.equal(before.getDesiredBlockSize, after.getDesiredBlockSize) &&
      Objects.equal(before.getEncoding, after.getEncoding) &&
      Objects.equal(before.getCompressionAlgorithm, after.getCompressionAlgorithm) &&
      Objects.equal(before.getTypeAttributes, after.getTypeAttributes)
  }

  // Special handling because default values can be a byte array which is not
  // handled by Guava's Objects.equals.
  // See https://github.com/google/guava/issues/1425
  def defaultValuesMatch(before: Any, after: Any): Boolean = {
    if (before.isInstanceOf[Array[Byte]] && after.isInstanceOf[Array[Byte]]) {
      util.Objects.deepEquals(before, after)
    } else {
      Objects.equal(before, after)
    }
  }

  // TODO: Move to a PartitionSchema equals/equivalent method.
  def partitionSchemasMatch(before: PartitionSchema, after: PartitionSchema): Boolean = {
    if (before eq after) return true
    val beforeBuckets = before.getHashBucketSchemas.asScala
    val afterBuckets = after.getHashBucketSchemas.asScala
    if (beforeBuckets.size != afterBuckets.size) return false
    val hashBucketsMatch = (0 until beforeBuckets.size).forall { i =>
      HashBucketSchemasMatch(beforeBuckets(i), afterBuckets(i))
    }
    hashBucketsMatch &&
      Objects.equal(before.getRangeSchema.getColumnIds, after.getRangeSchema.getColumnIds)
  }

  def HashBucketSchemasMatch(before: HashBucketSchema, after: HashBucketSchema): Boolean = {
    if (before eq after) return true
    Objects.equal(before.getColumnIds, after.getColumnIds) &&
      Objects.equal(before.getNumBuckets, after.getNumBuckets) &&
      Objects.equal(before.getSeed, after.getSeed)
  }

  // TODO: Move to a test utility in kudu-client since it's generally useful.
  def createRandomTable(): KuduTable = {
    val columnCount = Random.nextInt(50) + 1 // At least one column.
    val keyCount = Random.nextInt(columnCount) + 1 // At least one key.

    val types = Type.values()
    val keyTypes = types.filter { t => !Array(Type.BOOL, Type.FLOAT, Type.DOUBLE).contains(t)}
    val compressions = CompressionAlgorithm.values().filter(_ != CompressionAlgorithm.UNKNOWN)
    val blockSizes = Array(0, 4096, 524288, 1048576) // Default, min, middle, max.

    val columns = (0 until columnCount).map { i =>
      val key = i < keyCount
      val t = if (key) {
        keyTypes(Random.nextInt(keyTypes.length))
      } else {
        types(Random.nextInt(types.length))
      }
      val precision = Random.nextInt(DecimalUtil.MAX_DECIMAL_PRECISION) + 1
      val scale =  Random.nextInt(precision)
      val typeAttributes = DecimalUtil.typeAttributes(precision, scale)
      val nullable = Random.nextBoolean() && !key
      val compression = compressions(Random.nextInt(compressions.length))
      val blockSize = blockSizes(Random.nextInt(blockSizes.length))
      val encodings = t match {
        case Type.INT8 | Type.INT16 | Type.INT32 |Type.INT64 | Type.UNIXTIME_MICROS =>
          Array(Encoding.AUTO_ENCODING, Encoding.PLAIN_ENCODING, Encoding.BIT_SHUFFLE, Encoding.RLE)
        case Type.FLOAT | Type.DOUBLE |Type.DECIMAL =>
          Array(Encoding.AUTO_ENCODING, Encoding.PLAIN_ENCODING, Encoding.BIT_SHUFFLE)
        case Type.STRING | Type.BINARY =>
          Array(Encoding.AUTO_ENCODING, Encoding.PLAIN_ENCODING, Encoding.PREFIX_ENCODING, Encoding.DICT_ENCODING)
        case Type.BOOL =>
          Array(Encoding.AUTO_ENCODING, Encoding.PLAIN_ENCODING, Encoding.RLE)
        case _ => throw new IllegalArgumentException(s"Unsupported type $t")
      }
      val encoding = encodings(Random.nextInt(encodings.length))

      val builder = new ColumnSchemaBuilder(s"${t.getName}-$i", t)
        .key(key)
        .nullable(nullable)
        .compressionAlgorithm(compression)
        .desiredBlockSize(blockSize)
        .encoding(encoding)
      // Add type attributes to decimal columns.
      if (t == Type.DECIMAL) {
        builder.typeAttributes(typeAttributes)
      }
      // Half the columns have defaults.
      if (Random.nextBoolean()) {
        val defaultValue =
          t match {
            case Type.BOOL => Random.nextBoolean()
            case Type.INT8 => Random.nextInt(Byte.MaxValue).asInstanceOf[Byte]
            case Type.INT16 => Random.nextInt(Short.MaxValue).asInstanceOf[Short]
            case Type.INT32 => Random.nextInt()
            case Type.INT64 | Type.UNIXTIME_MICROS => Random.nextLong()
            case Type.FLOAT => Random.nextFloat()
            case Type.DOUBLE => Random.nextDouble()
            case Type.DECIMAL =>
              DecimalUtil.minValue(typeAttributes.getPrecision, typeAttributes.getScale)
            case Type.STRING => Random.nextString(Random.nextInt(100))
            case Type.BINARY => Random.nextString(Random.nextInt(100)).getBytes()
            case _ => throw new IllegalArgumentException(s"Unsupported type $t")
          }
        builder.defaultValue(defaultValue)
      }
      builder.build()
    }
    val keyColumns = columns.filter(_.isKey)

    val schema = new Schema(columns.asJava)

    val options = new CreateTableOptions().setNumReplicas(1)
    // Add hash partitioning (Max out at 3 levels to avoid being excessive).
    val hashPartitionLevels = Random.nextInt(Math.min(keyCount, 3))
    (0 to hashPartitionLevels).foreach { level =>
      val hashColumn = keyColumns(level)
      val hashBuckets = Random.nextInt(8) + 2 // Minimum of 2 hash buckets.
      val hashSeed = Random.nextInt()
      options.addHashPartitions(List(hashColumn.getName).asJava,  hashBuckets, hashSeed)
    }
    val hasRangePartition = Random.nextBoolean() && keyColumns.exists(_.getType == Type.INT64)
    if (hasRangePartition) {
      val rangeColumn = keyColumns.filter(_.getType == Type.INT64).head
      options.setRangePartitionColumns(List(rangeColumn.getName).asJava)
      val splits = Random.nextInt(8)
      val used = new util.ArrayList[Long]()
      var i = 0
      while (i < splits) {
        val split = schema.newPartialRow()
        val value = Random.nextLong()
        if (!used.contains(value)) {
          used.add(value)
          split.addLong(rangeColumn.getName, Random.nextLong())
          i = i + 1
        }
      }
    }

    val name = s"random-${System.currentTimeMillis()}"
    kuduClient.createTable(name, schema, options)
  }

  // TODO: Add updates and deletes when incremental backups are supported.
  def loadRandomData(table: KuduTable): IndexedSeq[PartialRow] = {
    val rowCount = Random.nextInt(200)

    val kuduSession = kuduClient.newSession()
    (0 to rowCount).map { i =>
      val upsert = table.newUpsert()
      val row = upsert.getRow
      table.getSchema.getColumns.asScala.foreach { col =>
        // Set nullable columns to null ~10% of the time.
        if (col.isNullable && Random.nextInt(10) == 0) {
          row.setNull(col.getName)
        }
        // Use the column default value  ~10% of the time.
        if (col.getDefaultValue != null && !col.isKey && Random.nextInt(10) == 0) {
          // Use the default value.
        } else {
          col.getType match {
            case Type.BOOL =>
              row.addBoolean(col.getName, Random.nextBoolean())
            case Type.INT8 =>
              row.addByte(col.getName, Random.nextInt(Byte.MaxValue).asInstanceOf[Byte])
            case Type.INT16 =>
              row.addShort(col.getName, Random.nextInt(Short.MaxValue).asInstanceOf[Short])
            case Type.INT32 =>
              row.addInt(col.getName, Random.nextInt())
            case Type.INT64 | Type.UNIXTIME_MICROS =>
              row.addLong(col.getName, Random.nextLong())
            case Type.FLOAT =>
              row.addFloat(col.getName, Random.nextFloat())
            case Type.DOUBLE =>
              row.addDouble(col.getName, Random.nextDouble())
            case Type.DECIMAL =>
              val attributes = col.getTypeAttributes
              val max = DecimalUtil.maxValue(attributes.getPrecision, attributes.getScale)
              row.addDecimal(col.getName, max)
            case Type.STRING =>
              row.addString(col.getName, Random.nextString(Random.nextInt(100)))
            case Type.BINARY =>
              row.addBinary(col.getName, Random.nextString(Random.nextInt(100)).getBytes())
            case _ =>
              throw new IllegalArgumentException(s"Unsupported type ${col.getType}")
          }
        }
      }
      kuduSession.apply(upsert)
      row
    }
  }

  def backupAndRestore(tableName: String): Unit = {
    val dir = Files.createTempDirectory("backup")
    val path = dir.toUri.toString

    val backupOptions = new KuduBackupOptions(Seq(tableName), path, miniCluster.getMasterAddresses)
    KuduBackup.run(backupOptions, ss)

    val restoreOptions = new KuduRestoreOptions(Seq(tableName), path, miniCluster.getMasterAddresses)
    KuduRestore.run(restoreOptions, ss)

    FileUtils.deleteDirectory(dir.toFile)
  }
}
