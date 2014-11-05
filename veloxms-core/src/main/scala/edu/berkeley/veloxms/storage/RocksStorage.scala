package edu.berkeley.veloxms.storage

import org.rocksdb.{RocksDBException, RocksDB, Options}
import edu.berkeley.veloxms._
import edu.berkeley.veloxms.util.{Logging, KryoThreadLocal}
import scala.util._
import java.nio.ByteBuffer
import scala.collection.immutable.HashMap

class RocksStorage ( usersPath: String,
                     itemsPath: String,
                     ratingsPath: String,
                     val numFactors: Int ) extends ModelStorage[FeatureVector] with Logging {

  // this is a static method that loads the RocksDB C++ library
  RocksDB.loadLibrary()
  val users = RocksUtil.getOrCreateDb(usersPath) match {
    case Success(s) => s
    case Failure(f) => throw new RuntimeException(
        s"Couldn't open database: ${f.getMessage}")
  }

  val items = RocksUtil.getOrCreateDb(itemsPath) match {
    case Success(s) => s
    case Failure(f) => throw new RuntimeException(
        s"Couldn't open database: ${f.getMessage}")
  }

  val ratings = RocksUtil.getOrCreateDb(ratingsPath) match {
    case Success(s) => s
    case Failure(f) => throw new RuntimeException(
        s"Couldn't open database: ${f.getMessage}")
  }

  def getFeatureData(itemId: Long): Try[FeatureVector] = {
    try {
      val rawBytes = ByteBuffer.wrap(items.get(StorageUtils.long2ByteArr(itemId)))
      val kryo = KryoThreadLocal.kryoTL.get
      val array = kryo.deserialize(rawBytes).asInstanceOf[FeatureVector]
      Success(array)
    } catch {
      case u: Throwable => Failure(u)
    }
  }

  def getUserFactors(userId: Long): Try[WeightVector] = {
    try {
      val rawBytes = ByteBuffer.wrap(users.get(StorageUtils.long2ByteArr(userId)))
      val kryo = KryoThreadLocal.kryoTL.get
      val array = kryo.deserialize(rawBytes).asInstanceOf[WeightVector]
      Success(array)
    } catch {
      case u: Throwable => Failure(u)
    }
  }

  def getAllObservations(userId: Long): Try[Map[Long, Double]] = {
    try {
      val rawBytes = ByteBuffer.wrap(ratings.get(StorageUtils.long2ByteArr(userId)))
      val kryo = KryoThreadLocal.kryoTL.get
      val result = kryo.deserialize(rawBytes).asInstanceOf[Map[Long, Double]]
      Success(result)
    } catch {
      case u: Throwable => Failure(u)
    }
  }

  def addScore(userId: Long, itemId: Long, score: Double) = {
    try {
      val uidBytes = StorageUtils.long2ByteArr(userId)
      var entry = ratings.get(uidBytes) match {
        case null => new HashMap[Long, Double]()
        case a: Array[Byte] => {
          val kryo = KryoThreadLocal.kryoTL.get
          kryo.deserialize(ByteBuffer.wrap(a)).asInstanceOf[Map[Long, Double]]
        }
      }

      entry = entry + (itemId -> score)
      val kryo = KryoThreadLocal.kryoTL.get
      val entryBytes = kryo.serialize(entry).array

      ratings.put(uidBytes, entryBytes)
    } catch {
      case u: Throwable => throw new RuntimeException(s"Unexpected put failure: ${u.getMessage()}")
    }
  }

  def stop() = {
    users.close
    items.close
    ratings.close
  }
}

object RocksUtil {

  def getOrCreateDb(path: String): Try[RocksDB] = {
    val options = new Options().setCreateIfMissing(true)
    try {
      Success(RocksDB.open(options, path))
    } catch {
      case ex: RocksDBException => {
        Failure(new RuntimeException(ex.getMessage()))
      }
    }
  }
}