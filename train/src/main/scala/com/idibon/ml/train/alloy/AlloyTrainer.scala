package com.idibon.ml.train.alloy

import com.idibon.ml.alloy._
import com.idibon.ml.common.Engine
import com.idibon.ml.predict._
import com.idibon.ml.predict.ensemble.GangModel
import com.idibon.ml.predict.rules.DocumentRules
import com.idibon.ml.train.datagenerator.SparkDataGenerator
import com.idibon.ml.train.furnace.Furnace
import org.json4s.JObject
import org.json4s.JsonAST.JArray

import scala.collection.mutable

/**
  * This is the trait that an alloy trainer implements.
  *
  * It contains anything that can be used by all and is fairly static, like rules generation.
  *
  * @author "Stefan Krawczyk <stefan@idibon.com>"
  */
trait AlloyTrainer {
  /** Trains a model and generates an Alloy from it
    *
    * Callers must provide a callback function which returns a traversable
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
    * @param name - a user-friendly name for the Alloy
    * @param docs - a callback function returning a traversable sequence
    *   of JSON training documents, such as those generated by export_training_to_idiml.rb
    * @param labelAndRules a callback function returning a traversable sequence
    *   of JSON Config. Should only be one line,   generated by export_training_to_idiml.rb.
    * @param config training configuration parameters. Optional.
    * @return an Alloy with the trained model
    */
  def trainAlloy(name: String, docs: () => TraversableOnce[JObject],
    labelAndRules: JObject, config: Option[JObject]): Alloy[Classification]

  /**
    * Creates a map of label to rules from some JSON data.
    *
    * It expects something like {"label": label, "rule": "REGEX or TEXT", "weight": WEIGHT_VALUE}.
    *
    * @param rules a JArray of rules one rule per label per entry, such as those generated by export_training_to_idiml.rb.
    * @return a map of label -> list of rules.
    */
  def rulesGenerator(rules: JArray): Map[String, List[(String, Float)]] = {
    implicit val formats = org.json4s.DefaultFormats
    rules.arr.map(x => {
      val label = (x \ "label").extract[String]
      val expression = (x \ "expression").extract[String]
      val weight = (x \ "weight").extract[Float]
      (label, expression, weight)
    }).foldRight(new mutable.HashMap[String, List[(String, Float)]]()){
      case ((label, expression, weight), map) => {
        val list = map.getOrElse(label, List[(String, Float)]())
        map.put(label, list :+ (expression, weight))
        map
      }
    }.toMap
  }

  /**
    * Helper function to create a map of String to Label.
    *
    * @param labelInfo a JSON object of UUID -> Label
    * @return
    */
  def uuidToLabelGenerator(labelInfo: JObject): Seq[Label] = {
    implicit val formats = org.json4s.DefaultFormats
    labelInfo.extract[Map[String, String]].map({ case (uuid, labelName) => {
      new Label(uuid, labelName)
    }}).toList
  }

  /**
    * Default implemenation of taking MLModels and creating Predict models that have rules with them.
    *
    * @param models
    * @param rules
    * @return
    */
  def mergeRulesWithModels(models: Map[String, PredictModel[Classification]],
    rules: Map[String, List[(String, Float)]]): GangModel = {

    // give each model a unique name in the gang
    val allModels = (models.map({ case (l, m) => (s"model.$l", m) })) ++
      rules.map({ case (l, d) => (s"rules.$l", new DocumentRules(l, d)) })

    new GangModel(allModels.toMap)
  }
}

object BaseTrainer {
  val DEFAULT_NUMBER_VALIDATION_EXAMPLES = 30
  val PIPELINE_CONFIG = "pipelineConfig"
}

/**
  * Base class for trainers that follow a fairly orthodox approach to training.
  *
  * @param engine
  * @param dataGen
  * @param furnace
  */
abstract class BaseTrainer(protected val engine: Engine,
                           protected val dataGen: SparkDataGenerator,
                           protected val furnace: Furnace[Classification],
                           protected val numberOfValidationExamples: Int = BaseTrainer.DEFAULT_NUMBER_VALIDATION_EXAMPLES)
  extends AlloyTrainer {

  /** Trains a model and generates an Alloy from it
    *
    * Callers must provide a callback function which returns a traversable
    * list of documents; this function will be called multiple times, and
    * each invocation of the function must return an instance that will
    * traverse over the exact set of documents traversed by previous instances.
    *
    * Traversed documents should match the format generated by
    * idibin.git:/idibin/bin/open_source_integration/export_training_to_idiml.rb
    *
    * { "content": "Who drives a chevy maliby Would you recommend it?
    * "metadata": { "iso_639_1": "en" },
    * "annotations: [{ "label": { "name": "Intent" }, "isPositive": true }]}
    *
    * @param name  a user-friendly name for the Alloy
    * @param docs     - a callback function returning a traversable sequence
    *                 of JSON training documents, such as those generated by export_training_to_idiml.rb
    * @param labelsAndRules    a callback function returning a traversable sequence
    *                 of JSON Rules, one rule per label per line, such as those generated by export_training_to_idiml.rb.
    * @param config   training configuration parameters. Optional.
    * @return an Alloy with the trained model
    */
  override def trainAlloy(name: String,
                          docs: () => TraversableOnce[JObject],
                          labelsAndRules: JObject,
                          config: Option[JObject]): Alloy[Classification] = {
    implicit val formats = org.json4s.DefaultFormats
    val rules = (labelsAndRules \ "rules").extract[JArray]
    val uuidTolabel = (labelsAndRules \ "uuid_to_label").extract[JObject]
    val task_type = (labelsAndRules \ "task_type").extract[String]
    // Create uuidsByLabel TODO: Figure out how this should be passed in.
    val labels = uuidToLabelGenerator(uuidTolabel)
    // Parse Rules
    val parsedRules = rulesGenerator(rules)
    /* delegate to trainer:
      - creating pipelines,
      - training MLModels
     */
    val gang = mergeRulesWithModels(
      melt(docs, dataGen, config.map(c => (c \ BaseTrainer.PIPELINE_CONFIG).extract[JObject]), task_type),
      parsedRules)

    val validationResults = createValidationSet(docs, numberOfValidationExamples)

    // if a config JSON was provided, return an alloy with it
    config.map(configJson => {
      new BaseAlloy(name, labels, Map("gang" -> gang))
          with HasValidationData with HasTrainingConfig with HasTrainingSummary {
        def validationExamples = validationResults
        def trainingConfig = configJson
      }
    }).getOrElse({
      new BaseAlloy(name, labels, Map("gang" -> gang))
          with HasValidationData with HasTrainingSummary {
        def validationExamples = validationResults
      }
    })
  }

  /**
    * Creates a validation set of size num.
    *
    * @param docs the docs used for training
    * @param num the number of results to create
    * @return ValidationExamples which is a list of ValidationExample
    */
  def createValidationSet(docs: () => TraversableOnce[JObject],
    num: Int): Seq[JObject] = {

    docs().toStream
      .distinct  // get distinct items using .equals (this should be lazily evaluated)
      .take(num) // take the first num items
      .toList    // return as a list
  }

  /**
    * This is the method where each alloy trainer does its magic and creates the MLModel(s) required.
    *
    * @param rawData the raw data to use for training.
    * @param dataGen the datagenerator to use with the raw data.
    * @param pipelineConfig the pipeline configuration to use.
    * @param classification_type this tells us what kind of classification we're doing.
    * @return
    */
  def melt(rawData: () => TraversableOnce[JObject],
           dataGen: SparkDataGenerator,
           pipelineConfig: Option[JObject],
           classification_type: String = "classification.single"): Map[String, PredictModel[Classification]]

}



///**
//  * Trains K models using K feature pipelines - one for each model.
//  *
//  * @param engine
//  * @param rDDGenerator
//  * @param featurePipelines
//  * @param furnace
//  */
//class KClassKFP(engine: Engine,
//                rDDGenerator: RDDGenerator,
//                featurePipelines: Map[String, FeaturePipeline],
//                furnace: Furnace)
//  extends BaseTrainer(engine, rDDGenerator) with StrictLogging {
//
//}
//
//
