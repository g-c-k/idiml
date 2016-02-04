package com.idibon.ml.train.datagenerator

import java.io.File
import java.nio.file.FileSystems
import java.security.SecureRandom

import com.idibon.ml.common.Engine
import com.idibon.ml.feature.FeaturePipeline
import com.typesafe.scalalogging.StrictLogging
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.json4s._

import scala.collection.mutable
import scala.util.{Success, Try}

/**
  * SparkData Generator Trait
  *
  * Based on DataFrames, since they look to be the way forward in Spark, and if RDDs are needed,
  * they can be easily converted by the consumers of this.
  *
  */
trait SparkDataGenerator{
  /**
    *
    * @param engine
    * @param pipeline
    * @param docs
    * @return
    */
  def getLabeledPointData(engine: Engine,
                          pipeline: FeaturePipeline,
                          docs: () => TraversableOnce[JObject]): Option[Map[String, DataFrame]]

  /**
    * Add a shutdown hook to make sure that we clean up all temp files,
    * regardless of how the JVM terminates.
    *
    * @param trainerTemp
    * @param files
    */
  protected def addShutdownHook(trainerTemp: File, files: Map[String, Try[File]]): Unit = {
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run {
        // delete all of the temporary files
        files.foreach(_ match {
          case (labelName: String, file: Success[File]) => file.get.delete
          case _ => {}
        })
        // and the random parent folder
        trainerTemp.delete
      }
    })
  }

  /**
    * Creates subdirectory where data should be stored.
    *
    * @return
    */
  protected def createTrainingDirs(): File = {
    /* use a random subdirectory within the system temp directory for
    * storing the intermediate training files */
    val trainerTemp = FileSystems.getDefault.getPath(
      System.getProperty("java.io.tmpdir"), "idiml", "training",
      Math.abs(SecureRandom.getInstance("SHA1PRNG").nextInt).toString).toFile

    trainerTemp.mkdirs()

    trainerTemp
  }
}

/**
  * Base Class for creating dataframes for training on.
  *
  * This contains common logic, e.g. creating temp directories, converting to
  * parquet, etc. that is used by subclasses.
  *
  */
abstract class DataFrameBase extends SparkDataGenerator with StrictLogging {

  /** Produces a DataFrame of LabeledPoints for each distinct label name.
    *
    * Dataframes are persisted to parquet format and that's what they're backed by
    *
    * Creates a set of labeled points for each label in the training document
    * set, writes the points out to a temporary Parquet file, and re-loads
    * the file as a DataFrame ready for training.
    *
    * Callers should provide a callback function which returns a traversable
    * list of documents; this function will be called multiple times, and
    * each invocation of the function must return an instance that will
    * traverse over the exact set of documents traversed by previous instances.
    *
    * Traversed documents should match the format generated by
    * idibin.git:/idibin/bin/open_source_integration/export_training_to_idiml.rb
    *
    *   { "content": "Who drives a chevy maliby Would you recommend it?
    *     "metadata": { "iso_639_1": "en" },
    *     "annotations: [{ "label": { "name": "Intent" }, "isPositive": true }]}
    *
    * @param engine: the current idiml engine context
    * @param pipeline: a FeaturePipeline to use for processing documents. Already primed.
    * @param docs: a callback function returning the training documents
    * @return a Map from label name to a DataFrame of LabeledPoints for that label
    */
  override def getLabeledPointData(engine: Engine,
                                   pipeline: FeaturePipeline,
                                   docs: () => TraversableOnce[JObject]): Option[Map[String, DataFrame]] = {
    val perLabelLPs = createPerLabelLPs(pipeline, docs)
    // Generate the RDDs, given the per-label list of LabeledPoints we just created -- now it's all in memory.
    val perLabelRDDs = createPerLabelRDDs(engine, perLabelLPs)
    // create training directories - return file where to save stuff.
    val trainerTemp = createTrainingDirs()

    val sqlContext = new org.apache.spark.sql.SQLContext(engine.sparkContext)
    // convert RDDs to data frames
    val files = createPerLabelDFs(trainerTemp, sqlContext, perLabelRDDs)
    // make sure the files go away
    addShutdownHook(trainerTemp, files)

    // only train if all labels were successfully stored
    if (files.exists({ case (_, file) => file.isFailure })) {
      None
    } else {
      Some(files.map({ case (label, file) => {
        label -> sqlContext.read.parquet(file.get.getAbsolutePath)
      }
      }))
    }
  }

  /**
    * Converts the in memory RDDs to dataframes that have been persisted to
    * parquet format on disk at the location of trainerTemp.
    *
    * @param trainerTemp the base directory where to save these files.
    * @param sqlContext the sql context to use to create data frames
    * @param perLabelRDDs the map of label -> RDD[LabeledPoints] to convert.
    * @return map of label -> file (location of parquet file)
    */
  def createPerLabelDFs(trainerTemp: File,
                        sqlContext: org.apache.spark.sql.SQLContext,
                        perLabelRDDs: Map[String, RDD[LabeledPoint]]): Map[String, Try[File]] = {
    perLabelRDDs.zipWithIndex.map({ case ((label, rdd), index) => {
      (label, Try({
        /* can't call File.createTempFile here, because the parquet writer
         * doesn't like to overwrite files, including the empty file created
         * by File.createTempFile, so use the integer index of the label
         * within a random temp directory. :angry: */
        val file = new File(trainerTemp, s"idiml-${index}.parquet")
        logger.info(s"Saving RDD for $label to $file")
        try {
          sqlContext.createDataFrame(rdd)
            .write.parquet(file.getAbsolutePath)
          file
        } catch {
          case error: Throwable => {
            /* if saving fails for any reason, delete the temporary file
             * and map store a Failure in the map */
            logger.error(s"Failed to save training data for $label", error)
            file.delete
            throw error
          }
        }
      }))
    }
    })
  }

  /**
    * Creates a map of label -> RDD of labeled points.
    *
    * @param engine the engine to use to parallelize the data
    * @param perLabelLPs map of label to list of labeled points
    * @return
    */
  def createPerLabelRDDs(engine: Engine,
                         perLabelLPs: Map[String, List[LabeledPoint]]): Map[String, RDD[LabeledPoint]] = {
    val perLabelRDDs = mutable.HashMap[String, RDD[LabeledPoint]]()
    val logLine = perLabelLPs.map {
      case (label, lp) => {
        perLabelRDDs(label) = engine.sparkContext.parallelize(lp)
        val splits = lp.groupBy(x => x.label).map(x => s"Polarity: ${x._1}, Size: ${x._2.size}").toList
        // create some data for logging.
        (label, lp.size, splits)
      }
    }.foldRight("") {
      // create atomic log line.
      case ((label, size, splits), line) => {
        line + s"\nCreated $size data points for $label; with splits $splits"
      }
    }
    logger.info(logLine)
    perLabelRDDs.toMap
  }

  /**
    * Method that turns an raw data into featurized labeled points, split by "label".
    *
    * @param pipeline primed pipeline.
    * @param docs the raw docs to featurize and created points from.
    * @return map that contains a "label" -> List of labeled points.
    */
  def createPerLabelLPs(pipeline: FeaturePipeline,
                        docs: () => TraversableOnce[JObject]): Map[String, List[LabeledPoint]]
}