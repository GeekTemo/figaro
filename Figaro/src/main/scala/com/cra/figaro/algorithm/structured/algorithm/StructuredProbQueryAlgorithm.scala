/*
 * StructuredProbQueryAlgorithm.scala
 * SFI algorithms that compute conditional probabilities of queries.
 *
 * Created By:      Brian Ruttenberg (bruttenberg@cra.com)
 * Creation Date:   December 30, 2015
 *
 * Copyright 2017 Avrom J. Pfeffer and Charles River Analytics, Inc.
 * See http://www.cra.com or email figaro@cra.com for information.
 *
 * See http://www.github.com/p2t2/figaro for a copy of the software license.
 */
package com.cra.figaro.algorithm.structured.algorithm

import com.cra.figaro.language._
import com.cra.figaro.algorithm._
import com.cra.figaro.algorithm.factored.factors.{Factor, SumProductSemiring}
import com.cra.figaro.algorithm.factored.factors.factory.Factory
import com.cra.figaro.algorithm.structured._
import com.cra.figaro.algorithm.lazyfactored.Extended
import com.cra.figaro.algorithm.structured.solver.Solution

abstract class StructuredProbQueryAlgorithm(universe: Universe, collection: ComponentCollection, val queryTargets: Element[_]*)
  extends StructuredAlgorithm(universe, collection) with ProbQueryAlgorithm {

  def this(universe: Universe, queryTargets: Element[_]*) {
    this(universe, new ComponentCollection, queryTargets:_*)
  }

  override def problemTargets = queryTargets.toList

  // Solutions are unnormalized factors marginalized to individual targets.
  protected var targetFactors: Map[Bounds, Map[Element[_], Factor[Double]]] = Map()

  // For each of the bounds, marginalize to each target element
  override def processSolutions(solutions: Map[Bounds, Solution]): Unit = {
    targetFactors = for((bounds, (solution, _)) <- solutions) yield {
      val joint = solution.foldLeft(Factory.unit(SumProductSemiring()))(_.product(_))
      val marginalsByTarget = queryTargets.map { target =>
        val targetVar = collection(target).variable
        val factor = joint.marginalizeTo(targetVar)
        (target, factor)
      }
      bounds -> marginalsByTarget.toMap[Element[_], Factor[Double]]
    }
  }

  protected def useBoundsString: String =
    "use a lazy algorithm that computes bounds, or a ranging strategy that avoids *"

  /**
   * Computes the normalized distribution over a single target element.
   * Throws an IllegalArgumentException if the range of the target contains star, or if lower and upper bounds are needed.
   */
  override def computeDistribution[T](target: Element[T]): Stream[(Double, T)] = {
    val targetVar = collection(target).variable
    if(targetVar.valueSet.hasStar) {
      throw new IllegalArgumentException("target range contains *; " + useBoundsString)
    }
    val solutions = targetFactors
    if(solutions.size > 1) {
      throw new IllegalArgumentException("this model requires lower and upper bounds; " + useBoundsString)
    }
    val factor = solutions.head._2(target)
    val normalizer = factor.foldLeft(0.0, _ + _)
    val dist = factor.getIndices.map(indices => (factor.get(indices) / normalizer, targetVar.range(indices.head).value))
    dist.toStream
  }

  /**
   * Computes the expectation of a given function for single target element.
   * Throws an IllegalArgumentException if the range of the target contains star, or if lower and upper bounds are needed.
   */
  override def computeExpectation[T](target: Element[T], function: T => Double): Double = {
    def get(pair: (Double, T)) = pair._1 * function(pair._2)
    (0.0 /: computeDistribution(target))(_ + get(_))
  }

  def distribution(target: List[Element[_]]): (List[(String, ProblemComponent[_])], List[(Double, List[Extended[_]])]) = {
    val targetVars = target.map(collection(_).variable)
    val jointFactor = problem.solution.foldLeft(Factory.unit(SumProductSemiring()))(_.product(_))
    val unnormalizedTargetFactor = jointFactor.marginalizeTo(targetVars: _*)
    val z = unnormalizedTargetFactor.foldLeft(0.0, _ + _)
    val targetFactor = unnormalizedTargetFactor.mapTo((d: Double) => d / z)
    val components = nameComponents(target, targetFactor)
    val dist = targetFactor.getIndices.map(f => (targetFactor.get(f), targetFactor.convertIndicesToValues(f))).toList
    (components, dist)
  }
  
  private def nameComponents(targets: Seq[Element[_]], factor: Factor[_]): List[(String, ProblemComponent[_])] = {
    val targetVars: Seq[(String, ProblemComponent[_])] = targets.map(t => (t.name.string, collection(t)))
    val variables = factor.variables
    val mappedElementNames = targetVars.map(t => (t._1, t._2, variables.indexOf(t._2.variable))).sortBy(_._3).toList
    for ((name, component, pos) <- mappedElementNames) yield (name, component)
  }

}

trait OneTimeStructuredProbQuery extends StructuredProbQueryAlgorithm with OneTimeStructured with OneTimeProbQuery

trait AnytimeStructuredProbQuery extends StructuredProbQueryAlgorithm with AnytimeStructured with AnytimeProbQuery
