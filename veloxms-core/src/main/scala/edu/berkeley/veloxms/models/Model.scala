
package edu.berkeley.veloxms.models

import edu.berkeley.veloxms._
import edu.berkeley.veloxms.storage._
import edu.berkeley.veloxms.util.Logging
import org.codehaus.jackson.JsonNode
import org.jblas.{Solve, DoubleMatrix}

import scala.reflect._
import scala.util.{Failure, Success, Try}


/**
 * Model interface
 * @tparam T The scala type of the item, deserialized from Array[Byte]
 * We defer deserialization to the model interface to encapsulate everything
 * the user must implement into a single class.
 * @tparam U The type of per-item data being stored in the
 * KV store
 */
abstract class Model[T:ClassTag, U] extends Logging {
  private val featureCache: FeatureCache[T] = new FeatureCache[T](FeatureCache.tempBudget)

  /** The number of features in this model.
   * Used for pre-allocating arrays/matrices
   */
  val numFeatures: Int

  /**
   * The default feature vector to use for an incomputable item
   */
  val defaultItem: FeatureVector

  /**
   * Interface to the storage backend. Allows model implementers
   * to access the storage system if needed for computing features,
   * user weights.
   */
  val userStorage: ModelStorage[Long, WeightVector]
  val observationStorage: ModelStorage[Long, Map[T, Double]]

  /** Average user weight vector.
   * Used for warmstart for new users
   */
  val averageUser: WeightVector

  /**
   * User provided implementation for the given model. Will be called
   * by Velox on feature cache miss.
   */
  protected def computeFeatures(data: T) : FeatureVector

  // TODO: probably want to elect a leader to initiate the Spark retraining
  // once we are running a Spark cluster
  def retrainInSpark(sparkMaster: String)

  /**
   * Velox implemented - fetch from local Tachyon partition
   *
   */
  private def getWeightVector(userId: Long) : WeightVector = {
    val result: Option[Array[Double]] = userStorage.get(userId)
    result match {
      case Some(u) => u
      case None => {
        logWarning("User weight not found")
        averageUser
      }
    }
  }

  // TODO(crankshaw) fix the error handling here to return default item features
  // TODO(crankshaw) the error handling here is fucked
  private def getFeatures(item: T): FeatureVector = {
    val features: Try[FeatureVector] = featureCache.getItem(item) match {
      case Some(f) => Success(f)
      case None => {
        Try(computeFeatures(item)).transform(
          (f) => {
            featureCache.addItem(item, f)
            Success(f)
          },
          (t) => {
            logWarning("Couldn't compute item features, using default of 0")
            Success(defaultItem)
          })
      }
    }
    features.get.clone()
  }

  def predict(uid: Long, context: JsonNode): Double = {
    val item: T = jsonMapper.readValue(context, classTag[T].runtimeClass.asInstanceOf[Class[T]])
    val features = getFeatures(item)
    val weightVector = getWeightVector(uid)
    var i = 0
    var score = 0.0
    while (i < numFeatures) {
      score += features(i)*weightVector(i)
      i += 1
    }
    score
  }

  def addObservation(uid: Long, context: JsonNode, score: Double) {
    (this, uid).synchronized {
      val item: T = jsonMapper.readValue(context, classTag[T].runtimeClass.asInstanceOf[Class[T]])
      val allObservationScores = observationStorage.get(uid).getOrElse(Map()) + (item -> score)
      observationStorage.put(uid -> allObservationScores)



      val allItemFeatures: Map[T, FeatureVector] = allObservationScores.map {
        case (itemId, _) => (itemId, getFeatures(itemId))
      }

      val oldUserWeights = getWeightVector(uid)
      val newUserWeights = updateUserWeights(
        allItemFeatures, allObservationScores, numFeatures)
      logInfo(s"Old weight: (${oldUserWeights.mkString(",")})")
      logInfo(s"New weight: (${newUserWeights.mkString(",")})")

      userStorage.put(uid -> newUserWeights)
    }
  }

  private def updateUserWeights(allItemFeatures: Map[T, FeatureVector],
                        allObservationScores: Map[T, Double], k: Int): WeightVector = {


    val itemFeaturesSum = DoubleMatrix.zeros(k, k)
    val itemScoreProductSum = DoubleMatrix.zeros(k)

    var i = 0

    val observedItems = allItemFeatures.keys.toList

    while (i < observedItems.size) {
      val currentItemId = observedItems(i)
      // TODO error handling
      val currentFeaturesArray = allItemFeatures.get(currentItemId) match {
        case Some(f) => f
        case None => throw new Exception(
          s"Missing features in online update -- item: $currentItemId")
      }
      val currentFeatures = new DoubleMatrix(currentFeaturesArray)
      val product = currentFeatures.mmul(currentFeatures.transpose())
      itemFeaturesSum.addi(product)

      val obsScore = allObservationScores.get(currentItemId) match {
        case Some(o) => o
        case None => throw new Exception(
          s"Missing rating in online update -- item: $currentItemId")

      }
      itemScoreProductSum.addi(currentFeatures.mul(obsScore))
      i += 1

    }

    // TODO: There should be no dependency on MatrixFactorizationModel here
    val regularization = DoubleMatrix.eye(k).muli(MatrixFactorizationModel.lambda*k)
    itemFeaturesSum.addi(regularization)
    val newUserWeights = Solve.solve(itemFeaturesSum, itemScoreProductSum)
    newUserWeights.toArray()

  }

}


